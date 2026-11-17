// SPDX-FileCopyrightText: Szilárd Hajba
// SPDX-License-Identifier: MPL-2.0

package org.cloudillo.app.web

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import android.util.Base64
import android.webkit.DownloadListener
import android.webkit.URLUtil
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.cloudillo.app.MainActivity
import org.cloudillo.app.Prefs
import org.cloudillo.app.bridge.Bridge
import org.cloudillo.app.net.ApiClient
import org.json.JSONObject

/**
 * WebView downloads. https (`https://<appDomain>/cl-download?…` or `https://cl-o.<t>/…`): emit `download`, wait for
 * `downloadToken`, native fetches with redirects refused (the bearer never follows a 3xx) into MediaStore.Downloads.
 * blob:/data:: emit `download`, the shell sends the bytes as `downloadChunk`s into MediaStore.Downloads.
 * Native shows no UI: failures surface as an `error` field on `download` or an error reply, and the shell toasts.
 * Main thread only, except the IO blocks.
 */
class Downloads(private val activity: MainActivity, private val appDomain: String, private val bridge: Bridge) :
	DownloadListener {
	private class Http(val url: String, val name: String, val mime: String?)

	/** A MediaStore Downloads row, IS_PENDING until [finish]; created on first write. */
	private class Entry(private val cr: ContentResolver, val name: String, val mime: String?) {
		private var uri: Uri? = null
		private var out: OutputStream? = null

		fun stream(): OutputStream = out ?: run {
			val v = ContentValues().apply {
				put(MediaStore.Downloads.DISPLAY_NAME, name)
				put(MediaStore.Downloads.MIME_TYPE, mime ?: "application/octet-stream")
				put(MediaStore.Downloads.IS_PENDING, 1)
			}
			val u = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: throw IOException("MediaStore insert failed")
			uri = u
			(cr.openOutputStream(u) ?: throw IOException("MediaStore open failed")).also { out = it }
		}

		fun finish() {
			stream().close()
			cr.update(uri!!, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
		}

		fun abort() {
			try {
				out?.close()
			} catch (e: IOException) {
				// dropping it anyway
			}
			uri?.let { cr.delete(it, null, null) }
		}
	}

	private var nextId = 1
	private val https = HashMap<Int, Http>()
	private val blobs = HashMap<Int, Entry>()

	init {
		bridge.register("downloadToken") { p -> onToken(p) }
		bridge.register("downloadChunk") { p -> onChunk(p) }
	}

	override fun onDownloadStart(url: String, userAgent: String?, contentDisposition: String?, mimetype: String?, contentLength: Long) {
		val reqId = nextId++
		val mime = mimetype?.takeIf { it.isNotBlank() }
		val uri = Uri.parse(url)
		var name = safeName(URLUtil.guessFileName(url, contentDisposition, mime))
		val data = JSONObject().put("reqId", reqId)
		val t = if (uri.scheme == "https") resolve(uri) else null
		when {
			t != null -> {
				name = safeName(t.name ?: name)
				data.put("url", t.url).put("idTag", t.idTag)
				t.hat?.let { data.put("hat", it) }
				https[reqId] = Http(t.url, name, mime)
			}
			uri.scheme == "blob" || uri.scheme == "data" -> {
				data.put("url", url)
				blobs[reqId] = Entry(activity.applicationContext.contentResolver, name, mime)
			}
			else -> data.put("url", url).put("error", "unsupported") // the shell toasts the failure
		}
		data.put("fileName", name)
		mime?.let { data.put("mimeType", it) }
		if (!bridge.emit("download", data)) { // no shell listening yet: nobody to tell
			https.remove(reqId)
			blobs.remove(reqId)
		}
	}

	private class Target(val url: String, val idTag: String, val name: String?, val hat: String?)

	/**
	 * `https://<appDomain>/cl-download?idTag&fileId&name[&hat]` → cl-o file URL; `https://cl-o.<t>/…` as is.
	 * Foreign `cl-o.<t>` targets are intentional (federated shares): the shell decides whether, and which,
	 * token it supplies per idTag in `downloadToken`. Native only guarantees the bearer goes to exactly
	 * `https://cl-o.<t>` on port 443 (built here), never to a page-supplied host.
	 */
	private fun resolve(uri: Uri): Target? {
		if (uri.port != -1 && uri.port != 443) return null
		val host = uri.host ?: return null
		if (host == appDomain && uri.path == "/cl-download") {
			val t = uri.getQueryParameter("idTag")?.takeIf { Prefs.normalizeIdTag(it) == it } ?: return null
			val fileId = uri.getQueryParameter("fileId")?.takeIf { it.isNotEmpty() } ?: return null
			val url = Uri.Builder().scheme("https").encodedAuthority("cl-o.$t")
				.appendEncodedPath("api").appendEncodedPath("files").appendPath(fileId).build().toString()
			return Target(url, t, uri.getQueryParameter("name")?.takeIf { it.isNotBlank() }, uri.getQueryParameter("hat"))
		}
		if (host.startsWith("cl-o.")) {
			val t = host.removePrefix("cl-o.").takeIf { Prefs.normalizeIdTag(it) == it } ?: return null
			return Target(uri.toString(), t, null, null)
		}
		return null
	}

	/**
	 * Fetches with redirects refused (the bearer never follows a 3xx) into MediaStore.Downloads; a throw
	 * (non-2xx, network, MediaStore) becomes the error reply the shell toasts.
	 */
	private suspend fun onToken(p: JSONObject): JSONObject {
		val reqId = p.getInt("reqId")
		val h = https.remove(reqId) ?: throw IllegalArgumentException("unknown reqId $reqId")
		val token = if (p.isNull("token")) null else p.getString("token")
		var entry: Entry? = null
		try {
			withContext(Dispatchers.IO) {
				val conn = ApiClient.open(h.url, token)
				try {
					val status = conn.responseCode
					if (status !in 200..299) throw IOException("HTTP $status")
					val e = Entry(activity.applicationContext.contentResolver, h.name, h.mime ?: conn.contentType?.substringBefore(';'))
					entry = e
					conn.inputStream.use { it.copyTo(e.stream(), 64 * 1024) }
					e.finish()
				} finally {
					conn.disconnect()
				}
			}
		} catch (ex: Exception) {
			withContext(NonCancellable + Dispatchers.IO) { entry?.abort() }
			throw ex
		}
		return JSONObject().put("ok", true)
	}

	/** The last chunk replies only once the MediaStore row is final; a throw is the error reply the shell toasts. */
	private suspend fun onChunk(p: JSONObject): JSONObject {
		val reqId = p.getInt("reqId")
		val e = blobs[reqId] ?: throw IllegalArgumentException("unknown reqId $reqId")
		if (!p.isNull("error")) {
			blobs.remove(reqId)
			withContext(NonCancellable + Dispatchers.IO) { e.abort() }
			return JSONObject().put("ok", true)
		}
		val last = p.optBoolean("last")
		try {
			withContext(Dispatchers.IO) {
				e.stream().write(Base64.decode(p.getString("b64"), Base64.DEFAULT))
				if (last) e.finish()
			}
		} catch (ex: Exception) {
			blobs.remove(reqId)
			withContext(NonCancellable + Dispatchers.IO) { e.abort() }
			throw ex
		}
		if (last) blobs.remove(reqId)
		return JSONObject().put("ok", true)
	}

	companion object {
		/** Last path segment of a server/URL-supplied name, no leading dots (no "..", no hidden files), never blank. */
		internal fun safeName(n: String) =
			n.substringAfterLast('/').substringAfterLast('\\').trimStart('.').ifBlank { "download" }
	}
}
