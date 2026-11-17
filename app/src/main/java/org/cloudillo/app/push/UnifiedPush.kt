// SPDX-FileCopyrightText: Szilárd Hajba
// SPDX-License-Identifier: MPL-2.0

package org.cloudillo.app.push

import android.annotation.SuppressLint
import android.app.BroadcastOptions
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.security.GeneralSecurityException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.cloudillo.app.Prefs
import org.json.JSONException
import org.json.JSONObject

/**
 * Own UnifiedPush connector (no connector library), spec AND_3.1.0 (https://unifiedpush.org/developers/spec/android/).
 * One registration per app; token and distributor live in prefs "cloudillo" under up/token, up/distributor.
 * Link Activity flow, TEMP_UNAVAILABLE fallback and RAISE_TO_FOREGROUND are not implemented.
 */
@SuppressLint("ApplySharedPref")
object UnifiedPush {
	const val ACTION_REGISTER = "org.unifiedpush.android.distributor.REGISTER"
	const val ACTION_UNREGISTER = "org.unifiedpush.android.distributor.UNREGISTER"
	const val ACTION_MESSAGE_ACK = "org.unifiedpush.android.distributor.MESSAGE_ACK"
	const val ACTION_NEW_ENDPOINT = "org.unifiedpush.android.connector.NEW_ENDPOINT"
	const val ACTION_MESSAGE = "org.unifiedpush.android.connector.MESSAGE"
	const val ACTION_UNREGISTERED = "org.unifiedpush.android.connector.UNREGISTERED"
	const val ACTION_REGISTRATION_FAILED = "org.unifiedpush.android.connector.REGISTRATION_FAILED"
	const val ACTION_TEMP_UNAVAILABLE = "org.unifiedpush.android.connector.TEMP_UNAVAILABLE"
	private const val KEY_TOKEN = "up/token"
	private const val KEY_DISTRIBUTOR = "up/distributor"

	fun token(ctx: Context): String? = Prefs.shared(ctx).getString(KEY_TOKEN, null)
	fun distributor(ctx: Context): String? = Prefs.shared(ctx).getString(KEY_DISTRIBUTOR, null)

	/** Package names of installed distributors (apps receiving REGISTER), excluding ourselves. */
	@Suppress("DEPRECATION")
	fun distributors(ctx: Context): List<String> =
		ctx.packageManager.queryBroadcastReceivers(Intent(ACTION_REGISTER), 0)
			.map { it.activityInfo.packageName }
			.filter { it != ctx.packageName }
			.distinct()

	/** Registers with [distributor]; the endpoint arrives later as NEW_ENDPOINT. [vapid]: base64url uncompressed P-256 key. */
	fun register(ctx: Context, distributor: String, vapid: String) {
		val old = distributor(ctx)
		if (old != null && old != distributor) unregister(ctx)
		// Same distributor: re-register with the same token (spec: refreshes the existing registration)
		val token = token(ctx) ?: UUID.randomUUID().toString()
		Prefs.shared(ctx).edit().putString(KEY_TOKEN, token).putString(KEY_DISTRIBUTOR, distributor).commit()
		send(ctx, Intent(ACTION_REGISTER).setPackage(distributor).putExtra("token", token).putExtra("vapid", vapid))
	}

	fun unregister(ctx: Context) {
		val token = token(ctx)
		val distributor = distributor(ctx)
		if (token != null && distributor != null) {
			send(ctx, Intent(ACTION_UNREGISTER).setPackage(distributor).putExtra("token", token))
		}
		forget(ctx)
	}

	internal fun forget(ctx: Context) {
		Prefs.shared(ctx).edit().remove(KEY_TOKEN).remove(KEY_DISTRIBUTOR).commit()
	}

	internal fun ack(ctx: Context, token: String, id: String?) {
		val distributor = distributor(ctx) ?: return
		if (id == null) return
		ctx.sendBroadcast(Intent(ACTION_MESSAGE_ACK).setPackage(distributor).putExtra("token", token).putExtra("id", id))
	}

	/** Identifies us to the distributor: `pi` (needed below SDK 34) plus shared identity on 34+. */
	private fun send(ctx: Context, intent: Intent) {
		val pi = PendingIntent.getBroadcast(
			ctx,
			0,
			Intent("org.unifiedpush.dummy_app").setPackage("org.unifiedpush.dummy_app"),
			PendingIntent.FLAG_IMMUTABLE,
		)
		intent.putExtra("pi", pi)
		if (Build.VERSION.SDK_INT >= 34) {
			ctx.sendBroadcast(intent, null, BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle())
		} else {
			ctx.sendBroadcast(intent)
		}
	}
}

/** Receives distributor callbacks. Exported (distributors are other apps); every intent must carry our token. */
class UnifiedPushReceiver : BroadcastReceiver() {
	override fun onReceive(ctx: Context, intent: Intent) {
		val token = intent.getStringExtra("token") ?: return
		if (token != UnifiedPush.token(ctx)) return
		when (intent.action) {
			UnifiedPush.ACTION_NEW_ENDPOINT -> {
				val endpoint = intent.getStringExtra("endpoint") ?: return
				UnifiedPush.ack(ctx, token, intent.getStringExtra("id"))
				onNewEndpoint(ctx, endpoint)
			}
			UnifiedPush.ACTION_MESSAGE -> {
				val body = intent.getByteArrayExtra("bytesMessage") ?: return
				UnifiedPush.ack(ctx, token, intent.getStringExtra("id"))
				Prefs.init(ctx)
				val idTag = Prefs.idTag ?: return
				val keys = WebPushCrypto.loadKeys(ctx, idTag) ?: return
				val plain = try {
					WebPushCrypto.decrypt(body, keys)
				} catch (e: GeneralSecurityException) {
					Log.w("UnifiedPush", "undecryptable push message dropped")
					return
				}
				onMessage(ctx, idTag, String(plain, Charsets.UTF_8))
			}
			UnifiedPush.ACTION_UNREGISTERED -> {
				UnifiedPush.forget(ctx)
				onUnregistered(ctx)
			}
			UnifiedPush.ACTION_REGISTRATION_FAILED -> {
				Log.w("UnifiedPush", "registration failed: ${intent.getStringExtra("reason")}")
				UnifiedPush.forget(ctx)
			}
			// TEMP_UNAVAILABLE: optional fallback distributor ignored (spec: MAY); poll covers the gap.
		}
	}

	private fun onNewEndpoint(ctx: Context, endpoint: String) = async { PushRegistrar.onNewEndpoint(ctx, endpoint) }

	/**
	 * [json]: decrypted `NotificationPayload` JSON. Normally shown synchronously (no network, no goAsync). After a
	 * seq gap (lost pushes) it polls first (goAsync), which shows the missing entries and this one in order, then
	 * shows this entry without advancing lastSeq: dropped if the poll covered it, otherwise shown with the gap kept
	 * open for the next poll (same tag/id, so a re-show replaces rather than duplicates).
	 */
	private fun onMessage(ctx: Context, idTag: String, json: String) {
		val entry = try {
			JSONObject(json)
		} catch (e: JSONException) {
			Log.w("UnifiedPush", "malformed push payload dropped")
			return
		}
		if (!Notifier.isGap(Notifier.lastSeq(ctx, idTag), entry.optLong("seq", -1))) return Notifier.show(ctx, entry)
		async {
			try {
				NotificationPoller.poll(ctx)
			} catch (e: Exception) {
				if (e is CancellationException) throw e
				Log.w("UnifiedPush", "poll failed: ${e.javaClass.simpleName}")
			}
			Notifier.show(ctx, entry, advance = false)
		}
	}

	private fun onUnregistered(ctx: Context) = async { PushRegistrar.onUnregistered(ctx) }

	/** Keeps the receiver alive while [block] runs (PushRegistrar logs its own failures). */
	private fun async(block: suspend () -> Unit) {
		val pending = goAsync()
		// ponytail: goAsync gives ~10 s (foreground) to 60 s; a slow backend POST can be cut off, the next app-start sync retries
		CoroutineScope(Dispatchers.IO).launch {
			try {
				block()
			} finally {
				pending.finish()
			}
		}
	}
}
