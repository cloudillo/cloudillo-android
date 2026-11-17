// SPDX-FileCopyrightText: Szilárd Hajba
// SPDX-License-Identifier: MPL-2.0

package org.cloudillo.app.net

import android.content.Context
import android.net.Uri
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.cloudillo.app.store.SecureStore
import org.json.JSONException
import org.json.JSONObject

/** Non-2xx response. [body] is the raw response text. */
class ApiException(val status: Int, val body: String) : IOException("HTTP $status")

/**
 * Client for https://cl-o.<idTag>/api…, `Authorization: Bearer <token()>` (the device `cl_` key is accepted
 * as a plain bearer, no JWT exchange). Paths start with "/api/". The backend's `ApiResponse { data, next?,
 * total? }` envelope is unwrapped when `data` is an object; otherwise (array data) the envelope is returned.
 */
class ApiClient(private val idTag: String, private val token: () -> String?) {
	suspend fun getJson(path: String, query: Map<String, String> = emptyMap()): JSONObject =
		request("GET", path, query, null, null, null)

	suspend fun postJson(path: String, body: JSONObject): JSONObject {
		val bytes = body.toString().toByteArray(Charsets.UTF_8)
		return request("POST", path, emptyMap(), "application/json", bytes.size.toLong()) { it.write(bytes) }
	}

	/** DELETE; 404 counts as already gone. */
	suspend fun delete(path: String) {
		try {
			request("DELETE", path, emptyMap(), null, null, null)
		} catch (e: ApiException) {
			if (e.status != 404) throw e
		}
	}

	/** Streams [body] as the request body; [length] null means chunked transfer. */
	suspend fun upload(
		path: String,
		query: Map<String, String>,
		contentType: String,
		length: Long?,
		body: (OutputStream) -> Unit,
	): JSONObject = request("POST", path, query, contentType, length, body)

	private suspend fun request(
		method: String,
		path: String,
		query: Map<String, String>,
		contentType: String?,
		length: Long?,
		body: ((OutputStream) -> Unit)?,
	): JSONObject = withContext(Dispatchers.IO) {
		val b = Uri.Builder().scheme("https").encodedAuthority("cl-o.$idTag").encodedPath(path)
		for ((k, v) in query) b.appendQueryParameter(k, v)
		val conn = open(b.build().toString(), token())
		try {
			conn.requestMethod = method
			conn.setRequestProperty("Accept", "application/json")
			if (body != null) {
				conn.doOutput = true
				contentType?.let { conn.setRequestProperty("Content-Type", it) }
				if (length != null) conn.setFixedLengthStreamingMode(length) else conn.setChunkedStreamingMode(0)
				conn.outputStream.use(body)
			}
			val status = conn.responseCode
			val text = (if (status < 400) conn.inputStream else conn.errorStream)
				?.bufferedReader()?.use { it.readText() } ?: ""
			if (status !in 200..299) throw ApiException(status, text)
			parse(text)
		} finally {
			conn.disconnect()
		}
	}

	companion object {
		/** Client authenticated with the stored device key of [idTag]. */
		fun device(ctx: Context, idTag: String) = ApiClient(idTag) { SecureStore.deviceKey(ctx, idTag) }

		/** Opens [url] with the shared timeouts and an optional bearer. */
		internal fun open(url: String, token: String?): HttpURLConnection {
			val conn = URL(url).openConnection() as HttpURLConnection
			conn.connectTimeout = 10_000
			conn.readTimeout = 60_000
			conn.instanceFollowRedirects = false // a 3xx is an error: the bearer never follows a redirect
			token?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
			return conn
		}

		/** 2xx body → JSON, `data` object envelope unwrapped. */
		internal fun parse(text: String): JSONObject {
			val json = if (text.isBlank()) JSONObject() else try {
				JSONObject(text)
			} catch (e: JSONException) {
				throw IOException("bad JSON response", e) // e.g. a captive portal's 2xx HTML page
			}
			return json.optJSONObject("data") ?: json
		}
	}
}
