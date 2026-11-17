// SPDX-FileCopyrightText: Szilárd Hajba
// SPDX-License-Identifier: MPL-2.0

package org.cloudillo.app.backup

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** A backup source. `kind` is "media" (key = BUCKET_ID) or "saf" (key = persisted tree URI). */
data class Source(
	val id: String,
	val kind: String,
	val key: String,
	val name: String,
	val enabled: Boolean = false,
	val preset: String? = null,
	val mobileData: Boolean? = null,
	val cutoffMs: Long? = null,
	val newestFirst: Boolean = false,
	val remoteFolderId: String? = null,
	// Not persisted: filled by Sources.mediaBuckets for display.
	val itemCount: Int = 0,
	val totalSize: Long = 0,
) {
	companion object {
		const val MEDIA = "media"
		const val SAF = "saf"
		fun id(kind: String, key: String) = "$kind:$key"
	}
}

/** One local file of a source. `state` is pending|done|failed|skipped. */
data class Item(
	val sourceId: String,
	val localKey: String,
	val uri: String,
	val name: String,
	val mime: String?,
	val size: Long,
	val modifiedMs: Long,
	val takenMs: Long?,
	val blobId: String? = null,
	val state: String = "pending",
	val fileId: String? = null,
	val attempts: Int = 0,
	val readAttempts: Int = 0,
	val lastError: String? = null,
	val retryAtMs: Long? = null,
	val updatedMs: Long = 0,
) {
	val isVideo get() = mime?.startsWith("video/") == true
}

class BackupDb(ctx: Context, idTag: String) :
	SQLiteOpenHelper(ctx.applicationContext, "backup-$idTag.db", null, 2) {

	init {
		setWriteAheadLoggingEnabled(true)
	}

	override fun onCreate(db: SQLiteDatabase) {
		db.execSQL(
			"""CREATE TABLE sources(id TEXT PRIMARY KEY, kind TEXT NOT NULL, key TEXT NOT NULL,
				name TEXT NOT NULL, enabled INTEGER NOT NULL DEFAULT 0, preset TEXT, mobile_data INTEGER,
				cutoff_ms INTEGER, newest_first INTEGER NOT NULL DEFAULT 0, remote_folder_id TEXT,
				UNIQUE(kind, key))"""
		)
		db.execSQL(
			"""CREATE TABLE items(source_id TEXT NOT NULL, local_key TEXT NOT NULL, uri TEXT NOT NULL,
				name TEXT NOT NULL, mime TEXT, size INTEGER NOT NULL, modified_ms INTEGER NOT NULL,
				taken_ms INTEGER, blob_id TEXT, state TEXT NOT NULL, file_id TEXT,
				attempts INTEGER NOT NULL DEFAULT 0, read_attempts INTEGER NOT NULL DEFAULT 0, last_error TEXT, retry_at_ms INTEGER,
				updated_ms INTEGER NOT NULL, PRIMARY KEY(source_id, local_key))"""
		)
		db.execSQL("CREATE INDEX items_state ON items(state, source_id)")
		db.execSQL("CREATE TABLE remote_folders(path TEXT PRIMARY KEY, folder_id TEXT NOT NULL)")
	}

	override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
		if (oldVersion < 2) db.execSQL("ALTER TABLE items ADD COLUMN read_attempts INTEGER NOT NULL DEFAULT 0")
	}

	fun <T> tx(block: () -> T): T {
		val db = writableDatabase
		db.beginTransaction()
		try {
			return block().also { db.setTransactionSuccessful() }
		} finally {
			db.endTransaction()
		}
	}

	// --- sources ---

	/** Inserts a newly discovered source or refreshes its name; never touches its settings. */
	fun upsertSource(s: Source) {
		val db = writableDatabase
		db.insertWithOnConflict("sources", null, ContentValues().apply {
			put("id", s.id)
			put("kind", s.kind)
			put("key", s.key)
			put("name", s.name)
		}, SQLiteDatabase.CONFLICT_IGNORE)
		db.update("sources", ContentValues().apply { put("name", s.name) }, "id = ?", arrayOf(s.id))
	}

	fun sources(): List<Source> =
		readableDatabase.query("sources", null, null, null, null, null, "kind, name").use { c ->
			generateSequence { if (c.moveToNext()) source(c) else null }.toList()
		}

	fun setSourceEnabled(id: String, enabled: Boolean) = setSource(id) { put("enabled", if (enabled) 1 else 0) }
	fun setSourcePreset(id: String, preset: String?) = setSource(id) { put("preset", preset) }
	fun setSourceMobileData(id: String, mobileData: Boolean?) =
		setSource(id) { put("mobile_data", mobileData?.let { if (it) 1 else 0 }) }
	fun setSourceCutoff(id: String, cutoffMs: Long?) = setSource(id) { put("cutoff_ms", cutoffMs) }
	fun setSourceNewestFirst(id: String, newestFirst: Boolean) =
		setSource(id) { put("newest_first", if (newestFirst) 1 else 0) }
	fun setSourceRemoteFolder(id: String, folderId: String?) = setSource(id) { put("remote_folder_id", folderId) }

	private fun setSource(id: String, values: ContentValues.() -> Unit) {
		writableDatabase.update("sources", ContentValues().apply(values), "id = ?", arrayOf(id))
	}

	// --- items ---

	/**
	 * Records a scanned file. New, or changed size/modified → (re)queued as pending with blob id,
	 * file id, attempts and error cleared; returns true. Unchanged → left alone; returns false.
	 */
	fun upsertScannedItem(item: Item): Boolean {
		val db = writableDatabase
		db.query(
			"items", arrayOf("size", "modified_ms"), "source_id = ? AND local_key = ?",
			arrayOf(item.sourceId, item.localKey), null, null, null,
		).use { c ->
			if (c.moveToFirst() && c.getLong(0) == item.size && c.getLong(1) == item.modifiedMs) return false
		}
		db.insertWithOnConflict("items", null, ContentValues().apply {
			put("source_id", item.sourceId)
			put("local_key", item.localKey)
			put("uri", item.uri)
			put("name", item.name)
			put("mime", item.mime)
			put("size", item.size)
			put("modified_ms", item.modifiedMs)
			put("taken_ms", item.takenMs)
			putNull("blob_id")
			put("state", "pending")
			putNull("file_id")
			put("attempts", 0)
			put("read_attempts", 0)
			putNull("last_error")
			putNull("retry_at_ms")
			put("updated_ms", System.currentTimeMillis())
		}, SQLiteDatabase.CONFLICT_REPLACE)
		return true
	}

	/**
	 * Up to [limit] pending items of enabled sources that are due for (re)try, respect the source
	 * cutoff and pass [eligible], ordered by taken (else modified) time.
	 */
	fun pending(eligible: (Item) -> Boolean, newestFirst: Boolean, limit: Int): List<Item> {
		val out = ArrayList<Item>()
		// ponytail: sorts the whole pending set per call; add an (state, taken/modified) index if queues get huge.
		readableDatabase.rawQuery(
			"""SELECT i.* FROM items i JOIN sources s ON s.id = i.source_id
				WHERE i.state = 'pending' AND s.enabled = 1
				AND (i.retry_at_ms IS NULL OR i.retry_at_ms <= ?)
				AND (s.cutoff_ms IS NULL OR COALESCE(i.taken_ms, i.modified_ms) >= s.cutoff_ms)
				ORDER BY COALESCE(i.taken_ms, i.modified_ms) ${if (newestFirst) "DESC" else "ASC"}""",
			arrayOf(System.currentTimeMillis().toString()),
		).use { c ->
			while (out.size < limit && c.moveToNext()) {
				val item = item(c)
				if (eligible(item)) out += item
			}
		}
		return out
	}

	fun nextPending(eligible: (Item) -> Boolean, newestFirst: Boolean): Item? =
		pending(eligible, newestFirst, 1).firstOrNull()

	fun setBlobId(item: Item, blobId: String) = setItem(item) { put("blob_id", blobId) }

	fun markDone(item: Item, fileId: String) = setItem(item) {
		put("state", "done")
		put("file_id", fileId)
		putNull("last_error")
		putNull("retry_at_ms")
	}

	/** `retryAtMs` null → `failed` (no automatic retry); else stays `pending` until then. Bumps attempts (and read_attempts if [read]). */
	fun markFailed(item: Item, error: String, retryAtMs: Long?, read: Boolean = false) = setItem(item) {
		put("state", if (retryAtMs == null) "failed" else "pending")
		put("attempts", item.attempts + 1)
		if (read) put("read_attempts", item.readAttempts + 1)
		put("last_error", error)
		put("retry_at_ms", retryAtMs)
	}

	private fun setItem(item: Item, values: ContentValues.() -> Unit) {
		val cv = ContentValues().apply(values)
		cv.put("updated_ms", System.currentTimeMillis())
		writableDatabase.update(
			"items", cv, "source_id = ? AND local_key = ?", arrayOf(item.sourceId, item.localKey),
		)
	}

	/** Item count per state ("pending", "done", …) for one source; absent states are missing. */
	fun counts(sourceId: String): Map<String, Int> =
		readableDatabase.rawQuery(
			"SELECT state, COUNT(*) FROM items WHERE source_id = ? GROUP BY state", arrayOf(sourceId),
		).use { c ->
			generateSequence { if (c.moveToNext()) c.getString(0) to c.getInt(1) else null }.toMap()
		}

	// --- remote folder cache ---

	fun remoteFolder(path: String): String? =
		readableDatabase.query(
			"remote_folders", arrayOf("folder_id"), "path = ?", arrayOf(path), null, null, null,
		).use { c -> if (c.moveToFirst()) c.getString(0) else null }

	fun putRemoteFolder(path: String, folderId: String) {
		writableDatabase.insertWithOnConflict("remote_folders", null, ContentValues().apply {
			put("path", path)
			put("folder_id", folderId)
		}, SQLiteDatabase.CONFLICT_REPLACE)
	}

	/** Forgets [path] and every cached path below it (e.g. after a 404 on the cached id). */
	fun clearRemoteFolder(path: String) {
		writableDatabase.delete(
			"remote_folders", "path = ?1 OR substr(path, 1, length(?1) + 1) = ?1 || '/'", arrayOf(path),
		)
	}

	// --- cursor mapping ---

	private fun source(c: Cursor) = Source(
		id = c.str("id")!!,
		kind = c.str("kind")!!,
		key = c.str("key")!!,
		name = c.str("name")!!,
		enabled = c.long("enabled") == 1L,
		preset = c.str("preset"),
		mobileData = c.long("mobile_data")?.let { it == 1L },
		cutoffMs = c.long("cutoff_ms"),
		newestFirst = c.long("newest_first") == 1L,
		remoteFolderId = c.str("remote_folder_id"),
	)

	private fun item(c: Cursor) = Item(
		sourceId = c.str("source_id")!!,
		localKey = c.str("local_key")!!,
		uri = c.str("uri")!!,
		name = c.str("name")!!,
		mime = c.str("mime"),
		size = c.long("size")!!,
		modifiedMs = c.long("modified_ms")!!,
		takenMs = c.long("taken_ms"),
		blobId = c.str("blob_id"),
		state = c.str("state")!!,
		fileId = c.str("file_id"),
		attempts = c.long("attempts")!!.toInt(),
		readAttempts = c.long("read_attempts")!!.toInt(),
		lastError = c.str("last_error"),
		retryAtMs = c.long("retry_at_ms"),
		updatedMs = c.long("updated_ms")!!,
	)

	private fun Cursor.str(col: String): String? =
		getColumnIndexOrThrow(col).let { if (isNull(it)) null else getString(it) }

	private fun Cursor.long(col: String): Long? =
		getColumnIndexOrThrow(col).let { if (isNull(it)) null else getLong(it) }

	companion object {
		private val open = HashMap<String, BackupDb>()

		/** Process-wide instance per account, so jobs, service and UI share one connection pool. */
		@Synchronized
		fun get(ctx: Context, idTag: String): BackupDb = open.getOrPut(idTag) { BackupDb(ctx, idTag) }
	}
}
