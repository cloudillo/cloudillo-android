// SPDX-FileCopyrightText: Szilárd Hajba
// SPDX-License-Identifier: MPL-2.0

package org.cloudillo.app.backup

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground `dataSync` service for user-started backfills on API 29–33 (API 34+ uses a user-initiated job).
 * Drains the queue with a progress notification and stops itself when done; leftovers go back to the jobs.
 */
class BackupService : Service() {
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
	private var job: Job? = null
	@Volatile private var stopped = false

	override fun onBind(intent: Intent?): IBinder? = null

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		startForeground(
			BackupScheduler.NOTIFICATION_ID, BackupScheduler.notification(this, 0, 0),
			ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
		)
		if (job?.isActive != true) job = scope.launch {
			val ctx = applicationContext
			val nm = getSystemService(NotificationManager::class.java)
			withContext(Dispatchers.IO) {
				try {
					BackupScheduler.drain(ctx, BackupScheduler.isUnmetered(ctx, null), { stopped }) { done, total ->
						nm.notify(BackupScheduler.NOTIFICATION_ID, BackupScheduler.notification(ctx, done, total))
					}
					BackupScheduler.scheduleUploads(ctx)
				} catch (e: Exception) {
					Log.w("BackupService", "backfill failed", e)
				}
			}
			stopForeground(STOP_FOREGROUND_REMOVE)
			stopSelf()
		}
		return START_NOT_STICKY
	}

	override fun onDestroy() {
		stopped = true
		scope.cancel()
		super.onDestroy()
	}
}
