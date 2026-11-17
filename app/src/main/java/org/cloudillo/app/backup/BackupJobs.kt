// SPDX-FileCopyrightText: Szilárd Hajba
// SPDX-License-Identifier: MPL-2.0

package org.cloudillo.app.backup

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.cloudillo.app.Prefs
import org.cloudillo.app.R
import org.cloudillo.app.net.ApiClient
import org.cloudillo.app.store.SecureStore

/** Job ids, scheduling and the drain loop shared by [BackupJobService] and [BackupService]. */
object BackupScheduler {
	const val JOB_MEDIA_TRIGGER = 1001
	const val JOB_RESCAN = 1002
	const val JOB_UPLOAD_UNMETERED = 1003
	const val JOB_UPLOAD_ANY = 1004
	const val JOB_BACKFILL = 1005
	const val CHANNEL = "backup"
	const val NOTIFICATION_ID = 1
	private const val TAG = "BackupJobs"
	private const val RESCAN_MS = 6 * 60 * 60 * 1000L

	@Volatile private var channelCreated = false

	/** (done, total) of the drain in progress in this process, or null when idle. */
	@Volatile var progress: Pair<Int, Int>? = null
		private set
	/** Called on the drain's (IO) thread after every [progress] change, including the final reset to null. */
	val progressListeners = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()

	/** Re-arms the one-shot content trigger on new images/videos (not persistable: re-armed by app start and rescan). */
	fun armMediaTrigger(ctx: Context) {
		val flag = JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS
		schedule(ctx, builder(ctx, JOB_MEDIA_TRIGGER)
			.addTriggerContentUri(JobInfo.TriggerContentUri(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, flag))
			.addTriggerContentUri(JobInfo.TriggerContentUri(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, flag))
			.build())
	}

	/** Persisted 6 h rescan of all enabled sources; left alone when already scheduled. */
	fun schedulePeriodic(ctx: Context) {
		if (js(ctx).getPendingJob(JOB_RESCAN) != null) return
		schedule(ctx, builder(ctx, JOB_RESCAN)
			.setPeriodic(RESCAN_MS)
			.setPersisted(true)
			.setRequiresBatteryNotLow(true)
			.build())
	}

	/**
	 * Schedules JOB_UPLOAD_UNMETERED when anything is pending and JOB_UPLOAD_ANY when some pending item may use
	 * mobile data. An already scheduled/running upload job is left alone. Blocking (DB, Keystore).
	 */
	fun scheduleUploads(ctx: Context) {
		val tag = idTag(ctx) ?: return
		val st = BackupSettings(ctx, tag)
		if (st.relinkNeeded || SecureStore.deviceKey(ctx, tag) == null) return
		val db = BackupDb.get(ctx, tag)
		// ponytail: items only waiting on a future retryAtMs schedule nothing; the 6 h rescan picks them up (backoff cap = 6 h).
		for ((id, unmetered) in listOf(JOB_UPLOAD_UNMETERED to true, JOB_UPLOAD_ANY to false)) {
			if (js(ctx).getPendingJob(id) != null || db.nextPending(eligible(db, st, unmetered), false) == null) continue
			// ponytail: a scheduled job keeps its requiresCharging; a toggle applies from the next schedule.
			schedule(ctx, builder(ctx, id)
				.setRequiredNetworkType(if (unmetered) JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_ANY)
				.setRequiresBatteryNotLow(true)
				.setRequiresCharging(st.requiresCharging)
				.setPersisted(true)
				.build())
		}
	}

	/**
	 * Starts a long user-requested backfill: a user-initiated data transfer job on API 34+, else [BackupService].
	 * Call while [activity] is visible (e.g. right after enabling a source). When the system refuses (app no
	 * longer visible), falls back to [scheduleUploads]. Does brief DB I/O (more on fallback).
	 */
	fun startBackfill(activity: Activity) {
		try {
			if (Build.VERSION.SDK_INT < 34) {
				activity.startForegroundService(Intent(activity, BackupService::class.java))
				return
			}
			if (js(activity).getPendingJob(JOB_BACKFILL) != null) return // already running/queued: rescheduling would restart it
			val tag = idTag(activity) ?: return
			val db = BackupDb.get(activity, tag)
			val st = BackupSettings(activity, tag)
			val mobile = db.sources().any { it.enabled && st.effectiveMobileData(it) }
			val job = builder(activity, JOB_BACKFILL)
				.setUserInitiated(true)
				.setRequiredNetworkType(if (mobile) JobInfo.NETWORK_TYPE_ANY else JobInfo.NETWORK_TYPE_UNMETERED)
				.build()
			check(js(activity).schedule(job) == JobScheduler.RESULT_SUCCESS) { "user-initiated job not scheduled" }
		} catch (e: Exception) {
			// ForegroundServiceStartNotAllowedException (API 31–33) or a refused user-initiated job (34+)
			Log.w(TAG, "backfill start refused (app no longer visible?), using regular upload jobs", e)
			scheduleUploads(activity)
		}
	}

	/** Progress notification on channel [CHANNEL] (created on first use). */
	fun notification(ctx: Context, done: Int, total: Int, title: Int = R.string.backup_title): Notification {
		if (!channelCreated) {
			ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
				NotificationChannel(CHANNEL, ctx.getString(R.string.backup_channel), NotificationManager.IMPORTANCE_LOW),
			)
			channelCreated = true
		}
		return Notification.Builder(ctx, CHANNEL)
			.setSmallIcon(android.R.drawable.stat_sys_upload)
			.setContentTitle(ctx.getString(title))
			.setContentText(ctx.getString(R.string.backup_progress, done, total))
			.setProgress(total, done, total == 0)
			.setOngoing(true)
			.setOnlyAlertOnce(true)
			.build()
	}

	/**
	 * Uploads what [unmetered] allows until done or [shouldStop]. Returns true when eligible items remain
	 * (stopped early), so the caller can reschedule. No-op without a device key or while re-link is needed.
	 */
	internal suspend fun drain(ctx: Context, unmetered: Boolean, shouldStop: () -> Boolean, onProgress: (Int, Int) -> Unit): Boolean {
		val tag = idTag(ctx) ?: return false
		val st = BackupSettings(ctx, tag)
		if (st.relinkNeeded || SecureStore.deviceKey(ctx, tag) == null) return false
		val db = BackupDb.get(ctx, tag)
		val eligible = eligible(db, st, unmetered)
		// Unmetered drains (backfill/FGS) read the network once; stop when it turns metered, leftovers reschedule.
		val stop: () -> Boolean = if (unmetered) ({ shouldStop() || !isUnmetered(ctx, null) }) else shouldStop
		try {
			Uploader(ctx, tag, db, ApiClient.device(ctx, tag)).drain(eligible, stop) { done, total ->
				progress = done to total
				progressListeners.forEach { it() }
				onProgress(done, total)
			}
		} finally {
			progress = null
			progressListeners.forEach { it() }
		}
		return !st.relinkNeeded && db.nextPending(eligible, false) != null
	}

	/** Unmetered: everything. Metered: items of sources with effective mobile data, minus videos under videosWifiOnly. */
	internal fun eligible(db: BackupDb, st: BackupSettings, unmetered: Boolean): (Item) -> Boolean {
		if (unmetered) return { true }
		val mobile = db.sources().filter { st.effectiveMobileData(it) }.map { it.id }.toSet()
		val noVideo = st.videosWifiOnly
		return { it.sourceId in mobile && !(noVideo && it.isVideo) }
	}

	/** Whether [network] (default: the active one) is unmetered. */
	internal fun isUnmetered(ctx: Context, network: Network?): Boolean {
		val cm = ctx.getSystemService(ConnectivityManager::class.java)
		val caps = cm.getNetworkCapabilities(network ?: cm.activeNetwork) ?: return false
		return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
	}

	internal fun idTag(ctx: Context): String? {
		Prefs.init(ctx)
		return Prefs.idTag
	}

	private fun js(ctx: Context) = ctx.getSystemService(JobScheduler::class.java)

	private fun builder(ctx: Context, id: Int) = JobInfo.Builder(id, ComponentName(ctx, BackupJobService::class.java))

	private fun schedule(ctx: Context, job: JobInfo) {
		if (js(ctx).schedule(job) != JobScheduler.RESULT_SUCCESS) Log.w(TAG, "scheduling job ${job.id} failed")
	}
}

/** Runs backup jobs 1001–1005 (see [BackupScheduler]) on a background coroutine. */
class BackupJobService : JobService() {
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
	/** Per run (JobParameters has identity equality), so a rescheduled run of the same id is not affected. */
	private val stopped = ConcurrentHashMap.newKeySet<JobParameters>()

	override fun onStartJob(params: JobParameters): Boolean {
		val id = params.jobId
		if (id == BackupScheduler.JOB_BACKFILL && Build.VERSION.SDK_INT >= 34) {
			setNotification(
				params, BackupScheduler.NOTIFICATION_ID, BackupScheduler.notification(this, 0, 0),
				JOB_END_NOTIFICATION_POLICY_REMOVE,
			)
		}
		scope.launch {
			val again = try {
				run(params)
			} catch (e: Exception) {
				Log.w("BackupJobs", "job $id failed", e)
				false
			}
			// A stopped job was already ended by the system; onStopJob's return drives its reschedule.
			if (params !in stopped) jobFinished(params, again)
			stopped -= params
			// Only after jobFinished: scheduling a running job's own id would stop it.
			if (id == BackupScheduler.JOB_MEDIA_TRIGGER) BackupScheduler.armMediaTrigger(applicationContext)
		}
		return true
	}

	override fun onDestroy() {
		scope.cancel()
		super.onDestroy()
	}

	/** Upload jobs ask to be rescheduled (with backoff); scans just end (trigger re-armed by the coroutine). */
	override fun onStopJob(params: JobParameters): Boolean {
		stopped += params
		return params.jobId >= BackupScheduler.JOB_UPLOAD_UNMETERED
	}

	/** Returns true when the job should be rescheduled (uploads left after a stop/time budget). */
	private suspend fun run(params: JobParameters): Boolean {
		val ctx = applicationContext
		val id = params.jobId
		if (id == BackupScheduler.JOB_MEDIA_TRIGGER || id == BackupScheduler.JOB_RESCAN) {
			val tag = BackupScheduler.idTag(ctx) ?: return false
			val db = BackupDb.get(ctx, tag)
			// ponytail: the trigger rescans every enabled media source, not just the changed URIs; filter on triggeredContentUris if slow.
			for (s in db.sources()) {
				if (params in stopped) return false
				if (s.enabled && (id == BackupScheduler.JOB_RESCAN || s.kind == Source.MEDIA)) Sources.scan(ctx, db, s)
			}
			if (id == BackupScheduler.JOB_RESCAN) BackupScheduler.armMediaTrigger(ctx) // content triggers don't survive reboot
			BackupScheduler.scheduleUploads(ctx)
			return false
		}
		val unmetered = when (id) {
			BackupScheduler.JOB_UPLOAD_UNMETERED -> true
			BackupScheduler.JOB_UPLOAD_ANY -> false
			else -> BackupScheduler.isUnmetered(ctx, params.network)
		}
		// Regular jobs get ~10 min; the user-initiated backfill runs until stopped.
		val deadline = if (id == BackupScheduler.JOB_BACKFILL) Long.MAX_VALUE else System.currentTimeMillis() + 9 * 60_000L
		val nm = getSystemService(NotificationManager::class.java)
		return BackupScheduler.drain(ctx, unmetered, { params in stopped || System.currentTimeMillis() > deadline }) { done, total ->
			if (id == BackupScheduler.JOB_BACKFILL) {
				nm.notify(BackupScheduler.NOTIFICATION_ID, BackupScheduler.notification(ctx, done, total))
			}
		}
	}
}
