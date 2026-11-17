// SPDX-FileCopyrightText: Szilárd Hajba
// SPDX-License-Identifier: MPL-2.0

package org.cloudillo.app.backup

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import android.util.Base64
import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.cloudillo.app.net.ApiClient
import org.cloudillo.app.net.ApiException
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Uploads pending items of one account to `Phone backup/<device name>/<source name>` (device scope root =
 * "Phone backup"). Original bytes only, one file at a time per process, append-only.
 */
class Uploader(private val ctx: Context, private val idTag: String, private val db: BackupDb, private val api: ApiClient) {
	private val settings = BackupSettings(ctx, idTag)
	private var rootId: String? = null

	/** Thrown when the device key is rejected; [drain] sets `relinkNeeded` and stops. */
	private class Relink(e: ApiException) : IOException(e)

	/**
	 * Source folder id, creating `<device>` and `<device>/<source>` on the server when not cached.
	 * [verify]: re-check a DB-cached folder id (one GET) and recreate the folder when it is gone.
	 */
	suspend fun ensureFolders(source: Source, verify: Boolean = false): String = withContext(Dispatchers.IO) {
		source.remoteFolderId ?: run {
			val dev = deviceName()
			val name = clean(source.name)
			val path = "$dev/$name"
			val id = db.remoteFolder(path)?.takeIf { !verify || exists(it) } ?: try {
				child(deviceFolder(dev), name)
			} catch (e: ApiException) {
				// A cached device folder the user deleted/trashed answers 403 (outside the scope tree) or 404.
				val devId = db.remoteFolder(dev)
				if (e.status != 403 && e.status != 404 || devId == null || exists(devId)) throw e
				db.clearRemoteFolder(dev)
				child(deviceFolder(dev), name)
			}
			db.putRemoteFolder(path, id)
			db.setSourceRemoteFolder(source.id, id)
			id
		}
	}

	/** Marks items whose blob id already exists anywhere under the scope root as done; returns the rest. */
	suspend fun precheck(items: List<Item>): List<Item> = withContext(Dispatchers.IO) {
		val withBlob = items.filter { it.blobId != null }
		val known = HashMap<String, String>()
		for (chunk in withBlob.chunked(500)) {
			val body = JSONObject().put("blobIds", JSONArray(chunk.map { it.blobId }))
			val hits = api.postJson("/api/files/${root()}/blob-check", body).optJSONArray("known") ?: continue
			for (i in 0 until hits.length()) hits.getJSONObject(i).let { known[it.getString("blobId")] = it.getString("fileId") }
		}
		items.filter { item ->
			val fileId = item.blobId?.let { known[it] } ?: return@filter true
			db.markDone(item, fileId)
			false
		}
	}

	/** `"b1~" + base64url_nopad(sha256(original bytes))`, the server's orig blob id. Blocking. */
	fun blobId(uri: Uri): String {
		val md = MessageDigest.getInstance("SHA-256")
		val buf = ByteArray(64 * 1024)
		open(uri).use { s -> while (true) { val n = s.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
		return "b1~" + Base64.encodeToString(md.digest(), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
	}

	/**
	 * Uploads eligible pending items, source by source (each in its own `newestFirst` order), until the queue is
	 * empty or [shouldStop]. Per batch of 100: hash missing blob ids, precheck, upload the rest.
	 */
	suspend fun drain(eligible: (Item) -> Boolean, shouldStop: () -> Boolean, onProgress: (done: Int, total: Int) -> Unit) =
		lock.withLock {
			withContext(Dispatchers.IO) {
				if (settings.relinkNeeded) return@withContext
				val sources = db.sources().filter { it.enabled }
				val filters = sources.associate { s -> s.id to { i: Item -> i.sourceId == s.id && eligible(i) } }
				// ponytail: counts by loading every pending row; add a COUNT query to BackupDb if queues get huge.
				val total = sources.sumOf { db.pending(filters.getValue(it.id), it.newestFirst, Int.MAX_VALUE).size }
				var done = 0
				onProgress(done, total)
				try {
					for (s in sources) {
						val filter = filters.getValue(s.id)
						// Re-checked per item so disabling/removing a source mid-run stops it promptly.
						fun live() = db.sources().firstOrNull { it.id == s.id }?.takeIf { it.enabled }
						while (!shouldStop() && live() != null) {
							val batch = db.pending(filter, s.newestFirst, 100)
							if (batch.isEmpty()) break
							val hashed = batch.mapNotNull { if (shouldStop() || live() == null) null else hash(it) }
							val todo = try {
								precheck(hashed).also { done += hashed.size - it.size }
							} catch (e: ApiException) {
								if (e.status == 401 || e.status == 403) throw Relink(e)
								hashed // precheck is an optimisation; the server dedups on upload too
							} catch (e: IOException) {
								if (e is Relink) throw e
								hashed
							}
							onProgress(done, total)
							for (item in todo) {
								if (shouldStop()) break
								// Re-read: a stale folder id is cleared from the DB by upload().
								val cur = live() ?: break // disabled or removed mid-run
								if (upload(cur, item)) onProgress(++done, total)
							}
						}
					}
				} catch (e: Relink) {
					Log.w(TAG, "device key rejected, re-link needed", e)
					settings.relinkNeeded = true
				}
			}
		}

	/** Item with blob id, or null after a read failure (see [readFailed]). */
	private fun hash(item: Item): Item? {
		if (item.blobId != null) return item
		return try {
			val b = blobId(Uri.parse(item.uri))
			db.setBlobId(item, b)
			item.copy(blobId = b)
		} catch (e: Exception) {
			readFailed(item, e)
			null
		}
	}

	/** True when the item is done; otherwise it has been marked per the retry rules (or left for a retry). */
	private suspend fun upload(source: Source, item: Item): Boolean {
		var parent: String? = null
		try {
			// Folder errors back off without touching the item's read/permanent-failure path
			val folder = try { ensureFolders(source) } catch (e: ApiException) {
				if (e.status == 401) throw Relink(e)
				backoff(item, e); return false
			}
			parent = folder
			val query = mapOf(
				"name" to item.name,
				"parentId" to folder,
				"createdAt" to Instant.ofEpochSecond((item.takenMs ?: item.modifiedMs) / 1000).toString(),
				"tags" to "backup,${source.name.replace(',', ' ')}",
			)
			val preset = Uri.encode(settings.effectivePreset(source))
			val input = try {
				open(Uri.parse(item.uri))
			} catch (e: Exception) {
				readFailed(item, e)
				return false
			}
			// Chunked (null length): a stale scan-time size must not break a fixed-length stream.
			// Closed even when the connection fails before the body is written.
			val resp = input.use { inp ->
				api.upload("/api/uploads/$preset", query, item.mime ?: "application/octet-stream", null) { out ->
					inp.copyTo(out, 64 * 1024)
				}
			}
			// UploadResponse flattens FileView: entry id is "id" (also on an `existed: true` dedup hit).
			db.markDone(item, resp.getString("id"))
			return true
		} catch (e: ApiException) {
			when (outcome(e.status)) {
				Outcome.RELINK -> throw Relink(e)
				Outcome.CHECK_FOLDER -> {
					// exists() failing (5xx, network) must not abort the whole drain: retry this item later
					val gone = parent != null && try { !exists(parent) } catch (x: IOException) { backoff(item, x); return false }
					when {
						gone -> {
							// Backup folder deleted/trashed: forget it; the item is retried with a fresh folder.
							db.clearRemoteFolder("${deviceName()}/${clean(source.name)}")
							db.setSourceRemoteFolder(source.id, null)
							backoff(item, e) // not at once: the rest of the batch recreates the folder first
						}
						e.status == 403 -> throw Relink(e)
						else -> db.markFailed(item, "HTTP 404: ${e.body.take(200)}", null)
					}
				}
				Outcome.BACKOFF -> backoff(item, e)
				Outcome.FAIL -> db.markFailed(item, "HTTP ${e.status}: ${e.body.take(200)}", null)
			}
		} catch (e: SecurityException) {
			readFailed(item, e)
		} catch (e: JSONException) {
			backoff(item, e) // 2xx without an "id"
		} catch (e: IOException) {
			if (e is Relink) throw e
			backoff(item, e)
		} catch (e: CancellationException) {
			throw e // an IllegalStateException: must not reach the RuntimeException catch
		} catch (e: RuntimeException) {
			readFailed(item, e) // provider throwing mid-copy
		}
		return false
	}

	/** Unreadable local file: retried with backoff (permission/provider may come back), failed after MAX_READ_ATTEMPTS reads. */
	private fun readFailed(item: Item, e: Exception) {
		if (readGivesUp(item.readAttempts)) db.markFailed(item, "read: ${e.message}", null, read = true) else backoff(item, e, read = true)
	}

	private fun backoff(item: Item, e: Exception, read: Boolean = false) {
		db.markFailed(item, e.toString().take(200), System.currentTimeMillis() + backoffDelayMs(item.attempts), read)
	}

	/** Scope root ("Phone backup") id: a device key listing `/api/files` without filters gets only that row. */
	private suspend fun root(): String = rootId ?: try {
		val data = api.getJson("/api/files").getJSONArray("data")
		if (data.length() == 0) throw IOException("scope root not visible")
		data.getJSONObject(0).getString("id").also { rootId = it }
	} catch (e: ApiException) {
		if (e.status == 401 || e.status == 403) throw Relink(e)
		throw e
	}

	private suspend fun deviceFolder(dev: String): String =
		db.remoteFolder(dev) ?: child(root(), dev).also { db.putRemoteFolder(dev, it) }

	/** Existing folder [name] under [parent] (e.g. after a reinstall), else a new one. */
	private suspend fun child(parent: String, name: String): String {
		val list = api.getJson("/api/files", mapOf("parentId" to parent, "q" to name, "limit" to "100"))
			.optJSONArray("data") ?: JSONArray()
		for (i in 0 until list.length()) {
			val f = list.getJSONObject(i)
			if (f.optString("fileName") == name && f.optString("fileType") == "FLDR") return f.getString("id")
		}
		val body = JSONObject().put("fileTp", "FLDR").put("contentType", "cloudillo/folder")
			.put("fileName", name).put("parentId", parent)
		return api.postJson("/api/files", body).getString("fileId")
	}

	/** Whether entry [id] is still inside the scope tree (trashed folders move under `__trash__` → 403). */
	private suspend fun exists(id: String): Boolean = try {
		api.getJson("/api/files/${Uri.encode(id)}")
		true
	} catch (e: ApiException) {
		if (e.status != 403 && e.status != 404) throw e
		false
	}

	/** Device folder name, pinned on first use (prefs `backup-<idTag>` key `deviceName`) so a rename keeps the folder. */
	private fun deviceName(): String {
		val sp = ctx.applicationContext.getSharedPreferences("backup-$idTag", Context.MODE_PRIVATE)
		sp.getString("deviceName", null)?.let { return it }
		val name = clean(
			Settings.Global.getString(ctx.contentResolver, Settings.Global.DEVICE_NAME)
				?: "${Build.MANUFACTURER} ${Build.MODEL}"
		)
		sp.edit().putString("deviceName", name).apply()
		return name
	}

	/** Original bytes for MediaStore URIs (EXIF GPS kept when ACCESS_MEDIA_LOCATION is granted). */
	private fun open(uri: Uri): InputStream {
		val cr = ctx.contentResolver
		if (uri.authority == MediaStore.AUTHORITY) {
			try {
				cr.openInputStream(MediaStore.setRequireOriginal(uri))?.let { return it }
			} catch (e: SecurityException) {
			} catch (e: UnsupportedOperationException) {
			}
		}
		return cr.openInputStream(uri) ?: throw IOException("cannot open $uri")
	}

	companion object {
		private const val TAG = "Uploader"
		private const val MAX_BACKOFF_MS = 6 * 60 * 60 * 1000L
		private const val MAX_READ_ATTEMPTS = 5

		/** One upload at a time per process. */
		private val lock = Mutex()

		/** Folder names: no '/' (would break the `remote_folders` path keys), never blank. */
		internal fun clean(name: String) = name.replace('/', '_').trim().ifEmpty { "_" }

		internal fun readGivesUp(readAttempts: Int) = readAttempts + 1 >= MAX_READ_ATTEMPTS

		/** 1 min doubling per attempt, capped at 6 h. */
		internal fun backoffDelayMs(attempts: Int) = minOf(60_000L shl minOf(attempts, 10), MAX_BACKOFF_MS)

		internal enum class Outcome { RELINK, CHECK_FOLDER, BACKOFF, FAIL }

		/** What [upload] does with an HTTP error status. */
		internal fun outcome(status: Int) = when (status) {
			401 -> Outcome.RELINK
			403, 404 -> Outcome.CHECK_FOLDER
			408, 429 -> Outcome.BACKOFF
			in 400..499 -> Outcome.FAIL
			else -> Outcome.BACKOFF
		}
	}
}
