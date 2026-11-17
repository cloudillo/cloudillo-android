// SPDX-FileCopyrightText: Szilárd Hajba
// SPDX-License-Identifier: MPL-2.0

package org.cloudillo.app.push

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import org.cloudillo.app.MainActivity
import org.cloudillo.app.Prefs
import org.cloudillo.app.R
import org.json.JSONObject

/**
 * Shows server-rendered notifications (`{title, body, path?, image?, tag?, seq?}`) on channel [CHANNEL].
 * Dedupe: prefs "cloudillo" `<idTag>/lastSeq`; an entry with `seq <= lastSeq` is dropped, a shown one advances it.
 * Android notification tag = payload `tag` (default "push"), id = `seq`. `image` is ignored in v1.
 */
@SuppressLint("ApplySharedPref", "MissingPermission")
object Notifier {
	const val CHANNEL = "notifications"

	private fun key(tag: String) = Prefs.accountKey(tag, "lastSeq")

	fun ensureChannel(ctx: Context) {
		ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
			NotificationChannel(CHANNEL, ctx.getString(R.string.notify_channel), NotificationManager.IMPORTANCE_DEFAULT),
		)
	}

	/** Highest seq seen, or -1 when this device has never synced. */
	fun lastSeq(ctx: Context, tag: String): Long = Prefs.shared(ctx).getLong(key(tag), -1)

	/** Advances lastSeq to [seq] without showing anything (never moves it back). */
	@Synchronized
	fun adopt(ctx: Context, tag: String, seq: Long) {
		if (seq > lastSeq(ctx, tag)) Prefs.shared(ctx).edit().putLong(key(tag), seq).commit()
	}

	/** Backend seqs are contiguous per tenant, so [seq] past lastSeq + 1 means pushes were lost. */
	internal fun isGap(lastSeq: Long, seq: Long) = lastSeq >= 0 && seq > lastSeq + 1

	internal fun forget(ctx: Context, tag: String) {
		Prefs.shared(ctx).edit().remove(key(tag)).commit()
	}

	/** [advance] false: shows without moving lastSeq, so a gap left by a failed poll is still fetched next time. */
	@Synchronized
	fun show(ctx: Context, entry: JSONObject, advance: Boolean = true) {
		Prefs.init(ctx)
		val tag = Prefs.idTag ?: return
		val seq = entry.optLong("seq", -1)
		if (seq >= 0) {
			if (seq <= lastSeq(ctx, tag)) return
			if (advance) adopt(ctx, tag, seq)
		}
		ensureChannel(ctx)
		// No seq (log append failed on the server): shown without dedupe under a time-based id
		val id = if (seq >= 0) seq.toInt() else (System.currentTimeMillis() and 0x7fffffff).toInt()
		val intent = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
		entry.optString("path").takeIf { it.startsWith('/') }?.let { intent.putExtra(MainActivity.EXTRA_PATH, it) }
		val pi = PendingIntent.getActivity(ctx, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
		val body = entry.optString("body")
		val n = Notification.Builder(ctx, CHANNEL)
			.setSmallIcon(R.drawable.ic_launcher_monochrome)
			.setContentTitle(entry.optString("title"))
			.setContentText(body)
			.setStyle(Notification.BigTextStyle().bigText(body))
			.setContentIntent(pi)
			.setAutoCancel(true)
			.setVisibility(Notification.VISIBILITY_PRIVATE)
			.build()
		// Without POST_NOTIFICATIONS (API 33+) the system drops it silently; lastSeq still advances
		ctx.getSystemService(NotificationManager::class.java)
			.notify(if (entry.isNull("tag")) "push" else entry.optString("tag"), id, n) // non-null: never collides with backup/share ids
	}
}
