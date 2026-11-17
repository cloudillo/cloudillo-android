// SPDX-FileCopyrightText: Szilárd Hajba
// SPDX-License-Identifier: MPL-2.0

package org.cloudillo.app.push

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.cloudillo.app.Prefs
import org.cloudillo.app.net.ApiClient
import org.cloudillo.app.store.SecureStore
import org.json.JSONArray

/** Catch-up poll of `GET /api/notifications?since=<lastSeq>`, every 30 min, whether or not UnifiedPush works. */
object NotificationPoller {
	const val JOB_NOTIFY_POLL = 2001
	private const val PAGE = 100

	/** Persisted periodic job (30 min, flex 15, any network); left alone when already scheduled. */
	fun schedule(ctx: Context) {
		val js = ctx.getSystemService(JobScheduler::class.java)
		if (js.getPendingJob(JOB_NOTIFY_POLL) != null) return
		val job = JobInfo.Builder(JOB_NOTIFY_POLL, ComponentName(ctx, NotificationPollerJob::class.java))
			.setPeriodic(30 * 60_000L, 15 * 60_000L)
			.setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
			.setPersisted(true)
			.build()
		if (js.schedule(job) != JobScheduler.RESULT_SUCCESS) Log.w("NotificationPoller", "scheduling poll job failed")
	}

	/**
	 * Shows everything after lastSeq. A device that never synced (lastSeq -1) only adopts the server's
	 * latest seq: the up-to-7-day backlog was already seen in the shell, so it is not replayed.
	 */
	suspend fun poll(ctx: Context) {
		Prefs.init(ctx)
		val tag = Prefs.idTag ?: return
		if (PushRegistrar.isOff(ctx, tag) || SecureStore.deviceKey(ctx, tag) == null) return
		val api = ApiClient.device(ctx, tag)
		val adopt = Notifier.lastSeq(ctx, tag) < 0
		var since = maxOf(Notifier.lastSeq(ctx, tag), 0)
		while (true) {
			val res = api.getJson("/api/notifications", mapOf("since" to since.toString(), "limit" to PAGE.toString()))
			val items = res.optJSONArray("items") ?: break
			if (!adopt) for (i in 0 until items.length()) Notifier.show(ctx, items.getJSONObject(i))
			val last = pageEnd(items, res.optLong("lastSeq", since), since)
			val done = items.length() < PAGE || last <= since
			since = last
			if (done) break
		}
		if (adopt) Notifier.adopt(ctx, tag, since)
	}

	/**
	 * Where the next page starts: the page's own max seq (the server's top-level [lastSeq] may be past a full
	 * page, which would skip entries); empty or seq-less page → maxOf([lastSeq], [since]).
	 */
	internal fun pageEnd(items: JSONArray, lastSeq: Long, since: Long): Long {
		var max = -1L
		for (i in 0 until items.length()) max = maxOf(max, items.optJSONObject(i)?.optLong("seq", -1) ?: -1)
		return if (max >= 0) max else maxOf(lastSeq, since)
	}
}

/** Runs [NotificationPoller.JOB_NOTIFY_POLL]. */
class NotificationPollerJob : JobService() {
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

	override fun onStartJob(params: JobParameters): Boolean {
		scope.launch {
			try {
				NotificationPoller.poll(applicationContext)
			} catch (e: Exception) {
				Log.w("NotificationPoller", "poll failed: ${e.javaClass.simpleName}")
			}
			jobFinished(params, false)
		}
		return true
	}

	/** Periodic: the next run comes anyway. */
	override fun onStopJob(params: JobParameters): Boolean = false

	override fun onDestroy() {
		scope.cancel()
		super.onDestroy()
	}
}
