// SPDX-FileCopyrightText: Szilárd Hajba
// SPDX-License-Identifier: MPL-2.0

package org.cloudillo.app.push

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.cloudillo.app.Prefs
import org.cloudillo.app.net.ApiClient
import org.cloudillo.app.net.ApiException
import org.cloudillo.app.store.SecureStore
import org.json.JSONObject

/**
 * Ties the UnifiedPush endpoint to a backend Web Push subscription. Stores (prefs "cloudillo")
 * `<idTag>/pushEndpoint` and `<idTag>/pushSubId`; the distributor and token belong to [UnifiedPush].
 * Everything runs on IO; failures are logged, never thrown.
 */
@SuppressLint("ApplySharedPref")
object PushRegistrar {
	private const val TAG = "PushRegistrar"
	private val lock = Mutex()

	private fun idTag(ctx: Context): String? {
		Prefs.init(ctx)
		return Prefs.idTag
	}

	/** User turned notifications off on this device (`<idTag>/pushOff`): no push registration, no polling. */
	fun isOff(ctx: Context, tag: String) = Prefs.shared(ctx).getBoolean(Prefs.accountKey(tag, "pushOff"), false)

	fun setOff(ctx: Context, tag: String, off: Boolean) {
		Prefs.shared(ctx).edit().putBoolean(Prefs.accountKey(tag, "pushOff"), off).commit()
	}

	fun isSubscribed(ctx: Context, tag: String) = Prefs.shared(ctx).contains(Prefs.accountKey(tag, "pushSubId"))

	/**
	 * Ensures push keys and (re)registers with the distributor: [choice], else the remembered one if still
	 * installed. None chosen: nothing; the user always picks, even a single distributor (MainActivity asks). The
	 * endpoint arrives later in [onNewEndpoint]. No-op without a device key.
	 */
	suspend fun sync(ctx: Context, choice: String? = null) = guarded("sync") {
		val tag = idTag(ctx) ?: return@guarded
		if (isOff(ctx, tag) || SecureStore.deviceKey(ctx, tag) == null) return@guarded
		val keysOk = lock.withLock { // load-generate-save atomically: concurrent syncs must not overwrite each other's keys
			when {
				WebPushCrypto.loadKeys(ctx, tag) != null -> true
				!WebPushCrypto.saveKeys(ctx, tag, WebPushCrypto.generateKeys()) -> false
				else -> { drop(ctx, tag); true } // any old subscription carries the lost keys
			}
		}
		if (!keysOk) return@guarded
		val installed = UnifiedPush.distributors(ctx)
		val dist = choice ?: UnifiedPush.distributor(ctx)?.takeIf { it in installed }
		if (dist == null) {
			if (UnifiedPush.distributor(ctx) != null) { // remembered distributor was uninstalled
				lock.withLock { drop(ctx, tag) }
				UnifiedPush.unregister(ctx)
			}
			return@guarded
		}
		val vapid = ApiClient.device(ctx, tag).getJson("/api/notifications/vapid-key").getString("vapidPublicKey")
		UnifiedPush.register(ctx, dist, vapid)
	}

	/** Subscribes [endpoint] on the backend unless it is already the subscribed one; drops the previous subscription. */
	suspend fun onNewEndpoint(ctx: Context, endpoint: String) = guarded("endpoint") {
		val u = Uri.parse(endpoint)
		if (u.scheme != "https" || u.host.isNullOrEmpty()) {
			Log.w(TAG, "non-https endpoint ignored")
			return@guarded
		}
		val tag = idTag(ctx) ?: return@guarded
		if (SecureStore.deviceKey(ctx, tag) == null) return@guarded
		val keys = WebPushCrypto.loadKeys(ctx, tag) ?: return@guarded
		lock.withLock {
			val p = Prefs.shared(ctx)
			if (endpoint == p.getString(Prefs.accountKey(tag, "pushEndpoint"), null) &&
				p.contains(Prefs.accountKey(tag, "pushSubId"))
			) return@withLock
			drop(ctx, tag)
			val sub = JSONObject()
				.put("endpoint", endpoint)
				.put("keys", JSONObject()
					.put("p256dh", WebPushCrypto.b64url(keys.publicUncompressed))
					.put("auth", WebPushCrypto.b64url(keys.auth)))
			val id = ApiClient.device(ctx, tag).postJson("/api/notifications/subscriptions", JSONObject().put("subscription", sub))
				.get("id").toString()
			p.edit()
				.putString(Prefs.accountKey(tag, "pushEndpoint"), endpoint)
				.putString(Prefs.accountKey(tag, "pushSubId"), id)
				.commit()
		}
	}

	/** The distributor dropped us ([UnifiedPush] already forgot the token): drop the backend subscription. */
	suspend fun onUnregistered(ctx: Context) = guarded("unregistered") {
		val tag = idTag(ctx) ?: return@guarded
		lock.withLock { drop(ctx, tag) }
	}

	/** Device-key clear / account reset: call BEFORE SecureStore.clear (the DELETE needs the device key). */
	suspend fun clear(ctx: Context) = guarded("clear") {
		val tag = idTag(ctx) ?: return@guarded
		lock.withLock { drop(ctx, tag) }
		UnifiedPush.unregister(ctx)
		WebPushCrypto.clearKeys(ctx, tag)
		Notifier.forget(ctx, tag)
	}

	/** Deletes the stored backend subscription (best effort) and forgets it. Hold [lock]. */
	private suspend fun drop(ctx: Context, tag: String) {
		val p = Prefs.shared(ctx)
		val id = p.getString(Prefs.accountKey(tag, "pushSubId"), null)
		if (id != null && SecureStore.deviceKey(ctx, tag) != null) {
			try {
				ApiClient.device(ctx, tag).delete("/api/notifications/subscriptions/${Uri.encode(id)}")
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				// ponytail: orphaned on failure; the backend drops it once the push service answers 404/410
				Log.w(TAG, "subscription delete failed: ${e.javaClass.simpleName}")
			}
		}
		p.edit().remove(Prefs.accountKey(tag, "pushEndpoint")).remove(Prefs.accountKey(tag, "pushSubId")).commit()
	}

	private suspend fun guarded(what: String, block: suspend () -> Unit) {
		withContext(Dispatchers.IO) {
			try {
				block()
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				Log.w(TAG, "$what failed: ${e.javaClass.simpleName} ${(e as? ApiException)?.status ?: ""}")
			}
		}
	}
}
