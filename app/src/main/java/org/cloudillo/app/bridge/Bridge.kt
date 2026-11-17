// SPDX-FileCopyrightText: Szilárd Hajba
// SPDX-License-Identifier: MPL-2.0

package org.cloudillo.app.bridge

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebViewCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.cloudillo.app.BuildConfig
import org.cloudillo.app.MainActivity
import org.cloudillo.app.OnboardingActivity
import org.cloudillo.app.backup.BackupScheduler
import org.cloudillo.app.backup.BackupSettings
import org.cloudillo.app.push.PushRegistrar
import org.cloudillo.app.store.SecureStore
import org.json.JSONException
import org.json.JSONObject

/**
 * JS bridge v1 (`window.CloudilloNative`). Accepts messages only from the main frame of https://<appDomain>.
 * Request `{"v":1,"id","method","params"}` → reply `{"id","result"}` / `{"id","error"}`; events
 * `{"event","data"}` go through the latest main-frame reply proxy. Handlers run on the main thread.
 * Only constructed when WEB_MESSAGE_LISTENER is supported (checked in `MainActivity.onCreate`).
 */
@SuppressLint("RequiresFeature")
class Bridge(private val activity: MainActivity, private val idTag: String) {
	companion object {
		const val VERSION = 1
		private const val TAG = "CloudilloBridge"

		/** Runs the handler for [req] and builds the `{"id","result"}` / `{"id","error"}` reply. */
		internal suspend fun reply(
			req: JSONObject,
			handlers: Map<String, suspend (JSONObject) -> Any?>,
			log: (String) -> Unit = { Log.w(TAG, it) },
		): JSONObject {
			val method = req.optString("method")
			val reply = JSONObject().put("id", req.opt("id") ?: JSONObject.NULL)
			try {
				require(req.optInt("v") == VERSION) { "unsupported bridge version" }
				val h = handlers[method] ?: throw IllegalArgumentException("unknown method: $method")
				reply.put("result", JSONObject.wrap(h(req.optJSONObject("params") ?: JSONObject())) ?: JSONObject.NULL)
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				log("$method failed: ${e.javaClass.simpleName}") // never log params (device key)
				reply.put("error", e.message ?: e.javaClass.simpleName)
			}
			return reply
		}
	}

	private val handlers = HashMap<String, suspend (JSONObject) -> Any?>()
	private var proxy: JavaScriptReplyProxy? = null

	init {
		register("hello") {
			val hasKey = withContext(Dispatchers.IO) { SecureStore.deviceKey(activity, idTag) != null }
			JSONObject()
				.put("bridgeVersion", VERSION)
				.put("appVersion", BuildConfig.VERSION_NAME)
				.put("deviceName", deviceName())
				.put("idTag", idTag)
				.put("hasDeviceKey", hasKey)
				// null drops the key: absent = no key stored
				.put("deviceKeyId", SecureStore.deviceKeyId(activity, idTag))
		}
		register("setDeviceKey") { p ->
			val key = p.getString("key")
			val keyId = p.getString("keyId")
			require(key.isNotEmpty() && keyId.isNotEmpty()) { "key and keyId required" }
			withContext(Dispatchers.IO) {
				SecureStore.putDeviceKey(activity, idTag, key, keyId)
				BackupSettings(activity, idTag).relinkNeeded = false
				BackupScheduler.scheduleUploads(activity)
			}
			activity.syncPush() // push keys can only be stored once the device key exists
			JSONObject().put("ok", true)
		}
		// PushRegistrar.clear before SecureStore.clear: the backend subscription DELETE needs the device key
		register("clearDeviceKey") {
			PushRegistrar.clear(activity)
			withContext(Dispatchers.IO) { SecureStore.clear(activity, idTag) }
			JSONObject().put("ok", true)
		}
		register("resetAccount") {
			PushRegistrar.clear(activity)
			withContext(Dispatchers.IO) { SecureStore.clear(activity, idTag) }
			activity.startActivity(
				Intent(activity, OnboardingActivity::class.java)
					.putExtra(OnboardingActivity.EXTRA_RESET, true)
					.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
			)
			activity.finish()
			JSONObject().put("ok", true)
		}
	}

	/** Adds or replaces a method. The handler's result goes through `JSONObject.wrap`; a throw becomes `error`. */
	fun register(method: String, handler: suspend (JSONObject) -> Any?) {
		handlers[method] = handler
	}

	/** Sends an event to the shell; main thread only. False if the shell has not said `hello` yet. */
	fun emit(event: String, data: JSONObject): Boolean {
		val p = proxy ?: return false
		p.postMessage(JSONObject().put("event", event).put("data", data).toString())
		return true
	}

	fun attach(webView: WebView) {
		WebViewCompat.addWebMessageListener(webView, "CloudilloNative", setOf("https://${activity.appDomain}")) {
			_, message, sourceOrigin, isMainFrame, replyProxy ->
			// Re-check what the allowed-origin rule already enforces; app iframes (cl-o.<idTag>) never get here
			if (!isMainFrame || sourceOrigin.scheme != "https" || sourceOrigin.host != activity.appDomain ||
				(sourceOrigin.port != -1 && sourceOrigin.port != 443)
			) return@addWebMessageListener
			val req = try {
				JSONObject(message.data ?: return@addWebMessageListener)
			} catch (e: JSONException) {
				return@addWebMessageListener
			}
			proxy = replyProxy
			dispatch(req, replyProxy)
		}
	}

	private fun dispatch(req: JSONObject, replyProxy: JavaScriptReplyProxy) {
		activity.scope.launch { replyProxy.postMessage(reply(req, handlers).toString()) }
	}

	private fun deviceName(): String =
		Settings.Global.getString(activity.contentResolver, Settings.Global.DEVICE_NAME)
			?: "${Build.MANUFACTURER} ${Build.MODEL}"
}
