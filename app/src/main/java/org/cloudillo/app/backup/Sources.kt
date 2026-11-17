// SPDX-FileCopyrightText: Szilárd Hajba
// SPDX-License-Identifier: MPL-2.0

package org.cloudillo.app.backup

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.BaseColumns
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.MediaStore
import android.provider.MediaStore.MediaColumns
import android.util.Log

/** Enumerates backup sources (MediaStore buckets, SAF trees) and scans their files. Blocking: call off the main thread. */
object Sources {
	private const val TAG = "Sources"
	private const val PAGE = 500
	private val MEDIA_URIS = arrayOf(
		MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
		MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
	)

	/** All image/video buckets with item count and total size (settings at defaults; not read from the DB). */
	fun mediaBuckets(ctx: Context): List<Source> {
		class Acc(val name: String, var count: Int = 0, var size: Long = 0)
		val acc = LinkedHashMap<String, Acc>()
		val proj = arrayOf(MediaColumns.BUCKET_ID, MediaColumns.BUCKET_DISPLAY_NAME, MediaColumns.SIZE)
		for (base in MEDIA_URIS) {
			query(ctx, base, proj, null)?.use { c ->
				while (c.moveToNext()) {
					val key = c.getString(0) ?: continue
					val a = acc.getOrPut(key) { Acc(c.getString(1) ?: key) }
					a.count++
					a.size += c.getLong(2)
				}
			}
		}
		return acc.map { (key, a) ->
			Source(Source.id(Source.MEDIA, key), Source.MEDIA, key, a.name, itemCount = a.count, totalSize = a.size)
		}.sortedBy { it.name.lowercase() }
	}

	/** Source for a tree URI from ACTION_OPEN_DOCUMENT_TREE; takes the persistable read grant. */
	fun safSource(ctx: Context, treeUri: Uri): Source {
		ctx.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
		val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
		val name = query(ctx, docUri, arrayOf(Document.COLUMN_DISPLAY_NAME), null)
			?.use { if (it.moveToFirst()) it.getString(0) else null }
			?: treeUri.lastPathSegment ?: treeUri.toString()
		val key = treeUri.toString()
		return Source(Source.id(Source.SAF, key), Source.SAF, key, name)
	}

	/** Records every file at or after the source's cutoff in [db]; returns how many were (re)queued. */
	fun scan(ctx: Context, db: BackupDb, source: Source): Int {
		val cutoff = source.cutoffMs ?: Long.MIN_VALUE
		var queued = 0
		walk(ctx, source) { batch ->
			db.tx {
				for (item in batch) {
					if ((item.takenMs ?: item.modifiedMs) >= cutoff && db.upsertScannedItem(item)) queued++
				}
			}
		}
		return queued
	}

	/** Count and total size of all existing files of [source], ignoring its cutoff (for the enable dialog). */
	fun estimate(ctx: Context, source: Source): Pair<Int, Long> {
		var count = 0
		var size = 0L
		walk(ctx, source) { batch ->
			count += batch.size
			for (item in batch) size += item.size
		}
		return count to size
	}

	private fun walk(ctx: Context, source: Source, onBatch: (List<Item>) -> Unit) {
		when (source.kind) {
			Source.MEDIA -> walkMedia(ctx, source, onBatch)
			Source.SAF -> walkSaf(ctx, source, onBatch)
			else -> Log.w(TAG, "unknown source kind ${source.kind}")
		}
	}

	/** Keyset paging on _ID, so it terminates even where the provider ignores QUERY_ARG_LIMIT. */
	private fun walkMedia(ctx: Context, source: Source, onBatch: (List<Item>) -> Unit) {
		val proj = arrayOf(
			BaseColumns._ID, MediaColumns.DISPLAY_NAME, MediaColumns.MIME_TYPE, MediaColumns.SIZE,
			MediaColumns.DATE_MODIFIED, MediaColumns.DATE_TAKEN,
		)
		for (base in MEDIA_URIS) {
			var lastId = -1L
			while (true) {
				val args = Bundle().apply {
					putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaColumns.BUCKET_ID} = ? AND ${BaseColumns._ID} > ?")
					putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(source.key, lastId.toString()))
					putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${BaseColumns._ID} ASC")
					putInt(ContentResolver.QUERY_ARG_LIMIT, PAGE)
				}
				val batch = query(ctx, base, proj, args)?.use { c ->
					generateSequence {
						if (!c.moveToNext()) return@generateSequence null
						val id = c.getLong(0)
						Item(
							sourceId = source.id,
							localKey = id.toString(),
							uri = ContentUris.withAppendedId(base, id).toString(),
							name = c.getString(1) ?: id.toString(),
							mime = c.getString(2),
							size = c.getLong(3),
							modifiedMs = c.getLong(4) * 1000,
							takenMs = if (c.isNull(5)) null else c.getLong(5),
						)
					}.toList()
				}.orEmpty()
				if (batch.isEmpty()) break
				onBatch(batch)
				lastId = batch.last().localKey.toLong()
			}
		}
	}

	/** Iterative walk of the tree; one batch per directory. Skips dot-files/dirs and virtual documents. */
	private fun walkSaf(ctx: Context, source: Source, onBatch: (List<Item>) -> Unit) {
		val tree = Uri.parse(source.key)
		val proj = arrayOf(
			Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
			Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS,
		)
		val dirs = mutableListOf(DocumentsContract.getTreeDocumentId(tree))
		while (dirs.isNotEmpty()) {
			val parent = dirs.removeAt(dirs.size - 1)
			val batch = ArrayList<Item>()
			query(ctx, DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent), proj, null)?.use { c ->
				while (c.moveToNext()) {
					val docId = c.getString(0) ?: continue
					val name = c.getString(1) ?: continue
					if (name.startsWith(".")) continue
					val mime = c.getString(2)
					if (mime == Document.MIME_TYPE_DIR) {
						dirs += docId
						continue
					}
					if ((c.getInt(5) and Document.FLAG_VIRTUAL_DOCUMENT) != 0) continue
					batch += Item(
						sourceId = source.id,
						localKey = docId,
						uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId).toString(),
						name = name,
						mime = mime,
						size = c.getLong(3),
						modifiedMs = c.getLong(4),
						takenMs = null,
					)
				}
			}
			if (batch.isNotEmpty()) onBatch(batch)
		}
	}

	/** Revoked media/SAF access surfaces as an empty result, not a crash. */
	private fun query(ctx: Context, uri: Uri, proj: Array<String>, args: Bundle?): Cursor? =
		try {
			ctx.contentResolver.query(uri, proj, args, null)
		} catch (e: SecurityException) {
			Log.w(TAG, "no access to $uri", e)
			null
		}
}
