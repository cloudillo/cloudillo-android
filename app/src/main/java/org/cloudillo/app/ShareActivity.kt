// SPDX-FileCopyrightText: Szilárd Hajba
// SPDX-License-Identifier: MPL-2.0

package org.cloudillo.app

import android.app.Activity
import android.app.NotificationManager
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.cloudillo.app.backup.BackupDb
import org.cloudillo.app.backup.BackupScheduler
import org.cloudillo.app.backup.BackupSettings
import org.cloudillo.app.backup.Source
import org.cloudillo.app.backup.Uploader
import org.cloudillo.app.net.ApiClient
import org.cloudillo.app.store.SecureStore
import java.io.IOException
import java.time.Instant

/**
 * "Share to Cloudillo": uploads the shared files with the global default preset into
 * `<device>/Shared` of the backup tree after a confirm, then opens that folder in the shell. Stays open while
 * uploading so the sender's URI grants stay valid; back cancels.
 */
class ShareActivity : Activity() {
	private companion object {
		const val NOTIFICATION_ID = 2 // BackupScheduler.NOTIFICATION_ID is 1
		const val TAG = "CloudilloShare"
	}

	private val scope = MainScope()

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		Prefs.init(this)
		val tag = Prefs.idTag
		if (tag == null || SecureStore.deviceKey(this, tag) == null) {
			Toast.makeText(this, R.string.share_login_first, Toast.LENGTH_LONG).show()
			startActivity(Intent(this, MainActivity::class.java))
			finish()
			return
		}
		// Only content:// — a file:// URI could point into this app's private storage. Only granted ClipData URIs:
		// startActivity checks the sender can grant each one, so it can't make us read what only we can (MediaStore)
		val uris = sharedUris().filter { it.scheme == ContentResolver.SCHEME_CONTENT }
		if (uris.isEmpty()) {
			Toast.makeText(this, R.string.share_nothing, Toast.LENGTH_LONG).show()
			finish()
			return
		}

		setFinishOnTouchOutside(false)
		// Any app can fire this intent: nothing leaves the device until the user confirms
		val tv = TextView(this).apply { text = resources.getQuantityString(R.plurals.share_confirm, uris.size, uris.size) }
		setContentView(column(
			tv,
			Button(this).apply { setText(R.string.share_upload); setOnClickListener { start(tag, uris) } },
			Button(this).apply { setText(android.R.string.cancel); setOnClickListener { finish() } },
		))
		// Show what the sender handed over: the user confirms names, not just a count
		scope.launch {
			val names = withContext(Dispatchers.IO) { uris.map { runCatching { displayName(it) }.getOrDefault("?") } }
			tv.append("\n\n" + names.take(10).joinToString("\n") + if (names.size > 10) "\n…" else "")
		}
	}

	private fun column(vararg views: View) = LinearLayout(this).apply {
		orientation = LinearLayout.VERTICAL
		gravity = Gravity.CENTER
		val pad = (24 * resources.displayMetrics.density).toInt()
		setPadding(pad, pad, pad, pad)
		views.forEach { addView(it) }
	}

	private fun start(tag: String, uris: List<Uri>) {
		val status = TextView(this)
		val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = uris.size }
		setContentView(column(TextView(this).apply { setText(R.string.share_title) }, bar, status))

		val nm = getSystemService(NotificationManager::class.java)
		fun progress(done: Int) {
			status.text = getString(R.string.backup_progress, done, uris.size)
			bar.progress = done
			nm.notify(NOTIFICATION_ID, BackupScheduler.notification(this, done, uris.size, R.string.share_title))
		}

		scope.launch {
			var folder: String? = null
			var error: String? = null
			try {
				progress(0)
				val db = withContext(Dispatchers.IO) { BackupDb.get(this@ShareActivity, tag) }
				val api = ApiClient.device(this@ShareActivity, tag)
				// ponytail: shares the folder with a media bucket named "Shared", if there is one
				val shared = Source(Source.id("share", "shared"), "share", "shared", "Shared")
				folder = Uploader(this@ShareActivity, tag, db, api).ensureFolders(shared, verify = true)
				val preset = Uri.encode(BackupSettings(this@ShareActivity, tag).defaultPreset)
				uris.forEachIndexed { i, uri ->
					try {
						upload(api, preset, folder, uri)
					} catch (e: Exception) {
						if (e is CancellationException) throw e
						Log.w(TAG, "upload failed: ${e.javaClass.simpleName}") // no URI, no throwable (its message has the URI)
						error = e.message ?: e.javaClass.simpleName
					}
					progress(i + 1)
				}
			} catch (e: Exception) {
				if (e is CancellationException) throw e
				Log.w(TAG, "share failed", e)
				error = e.message ?: e.javaClass.simpleName
			} finally {
				nm.cancel(NOTIFICATION_ID)
			}
			error?.let { Toast.makeText(this@ShareActivity, getString(R.string.share_failed, it), Toast.LENGTH_LONG).show() }
			if (folder != null) {
				startActivity(
					Intent(this@ShareActivity, MainActivity::class.java)
						.putExtra(MainActivity.EXTRA_PATH, "/~/app/files?parentId=${Uri.encode(folder)}"),
				)
			}
			finish()
		}
	}

	private suspend fun upload(api: ApiClient, preset: String, folder: String, uri: Uri) {
		val name = withContext(Dispatchers.IO) { displayName(uri) }
		val query = mapOf(
			"name" to name,
			"parentId" to folder,
			"createdAt" to Instant.ofEpochSecond(System.currentTimeMillis() / 1000).toString(),
			"tags" to "Shared",
		)
		val mime = contentResolver.getType(uri) ?: "application/octet-stream"
		val ctx = currentCoroutineContext()
		api.upload("/api/uploads/$preset", query, mime, null) { out ->
			(contentResolver.openInputStream(uri) ?: throw IOException("cannot open $uri")).use { input ->
				val buf = ByteArray(64 * 1024)
				while (true) {
					ctx.ensureActive() // back cancels mid-file; unwinds through ApiClient's disconnect
					val n = input.read(buf)
					if (n < 0) break
					out.write(buf, 0, n)
				}
			}
		}
	}

	private fun displayName(uri: Uri): String {
		var name: String? = null
		contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
			if (c.moveToFirst() && !c.isNull(0)) name = c.getString(0)
		}
		return (name ?: uri.lastPathSegment ?: "shared").substringAfterLast('/')
	}

	// SEND/SEND_MULTIPLE: the framework copies EXTRA_STREAM into ClipData with the read grant
	private fun sharedUris(): List<Uri> {
		val i = intent ?: return emptyList()
		if (i.action != Intent.ACTION_SEND && i.action != Intent.ACTION_SEND_MULTIPLE) return emptyList()
		if (i.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0) return emptyList()
		val clip = i.clipData ?: return emptyList()
		return (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
	}

	override fun onDestroy() {
		scope.cancel()
		super.onDestroy()
	}
}
