// SPDX-FileCopyrightText: Szilárd Hajba
// SPDX-License-Identifier: MPL-2.0

package org.cloudillo.app.bridge

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.cloudillo.app.MainActivity
import org.cloudillo.app.backup.BackupDb
import org.cloudillo.app.backup.BackupScheduler
import org.cloudillo.app.backup.BackupSettings
import org.cloudillo.app.backup.PROFILES
import org.cloudillo.app.backup.Source
import org.cloudillo.app.backup.Sources
import org.cloudillo.app.backup.profileName
import org.json.JSONArray
import org.json.JSONObject

/** The `backup.*` bridge methods behind the shell's "This device" settings page. */
object BackupBridge {
	fun register(bridge: Bridge, activity: MainActivity) {
		val a = activity
		val db = BackupDb.get(a, a.idTag)
		val st = BackupSettings(a, a.idTag)

		fun source(id: String): Source = db.sources().firstOrNull { it.id == id } ?: throw IllegalArgumentException("unknown source: $id")
		fun JSONObject.id(): String = getString("id")
		fun JSONObject.preset(k: String): String? =
			if (isNull(k)) null else getString(k).also { require(it in PROFILES) { "unknown preset: $it" } }

		suspend fun state(discover: Boolean = false): JSONObject = withContext(Dispatchers.IO) {
			// Without media permission this just finds nothing; never let it break the page
			if (discover) runCatching { Sources.mediaBuckets(a) }.getOrDefault(emptyList()).forEach(db::upsertSource)
			JSONObject()
				.put("permissions", permissions(a))
				.put("relinkNeeded", st.relinkNeeded)
				.put("globals", JSONObject()
					.put("defaultPreset", st.defaultPreset)
					.put("defaultMobileData", st.defaultMobileData)
					.put("requiresCharging", st.requiresCharging)
					.put("videosWifiOnly", st.videosWifiOnly))
				.put("sources", JSONArray(db.sources().map { sourceJson(db, it) }))
				.put("running", BackupScheduler.progress != null)
				.put("progress", BackupScheduler.progress?.let { (d, t) -> JSONObject().put("done", d).put("total", t) } ?: JSONObject.NULL)
		}

		// Uploader progress arrives on IO; forward on main. scope dies with the activity, so drop the listener then.
		val listener: () -> Unit = {
			val p = BackupScheduler.progress
			a.scope.launch {
				if (p != null) bridge.emit("backup.progress", JSONObject().put("done", p.first).put("total", p.second))
				else bridge.emit("backup.changed", JSONObject()) // drain finished: counts changed
			}
		}
		BackupScheduler.progressListeners += listener
		a.scope.coroutineContext[Job]?.invokeOnCompletion { BackupScheduler.progressListeners -= listener }

		/** Runs [block] on IO, tells the shell to re-fetch, returns the new state. */
		suspend fun mutate(block: () -> Unit): JSONObject {
			withContext(Dispatchers.IO) { block() }
			bridge.emit("backup.changed", JSONObject())
			return state()
		}

		bridge.register("backup.getState") { state(discover = true) }

		bridge.register("backup.setGlobals") { p ->
			mutate {
				if (p.has("defaultPreset")) st.defaultPreset = p.preset("defaultPreset") ?: throw IllegalArgumentException("defaultPreset is null")
				if (p.has("defaultMobileData")) st.defaultMobileData = p.getBoolean("defaultMobileData")
				if (p.has("requiresCharging")) st.requiresCharging = p.getBoolean("requiresCharging")
				if (p.has("videosWifiOnly")) st.videosWifiOnly = p.getBoolean("videosWifiOnly")
				BackupScheduler.scheduleUploads(a) // mobile-data default may make more items eligible
			}
		}

		bridge.register("backup.setSource") { p ->
			mutate {
				val id = source(p.id()).id
				if (p.has("preset")) db.setSourcePreset(id, p.preset("preset"))
				if (p.has("mobileData")) db.setSourceMobileData(id, if (p.isNull("mobileData")) null else p.getBoolean("mobileData"))
				if (p.has("newestFirst")) db.setSourceNewestFirst(id, p.getBoolean("newestFirst"))
				BackupScheduler.scheduleUploads(a)
			}
		}

		bridge.register("backup.estimate") { p ->
			val (count, bytes) = withContext(Dispatchers.IO) { Sources.estimate(a, source(p.id())) }
			JSONObject().put("count", count).put("bytes", bytes)
		}

		bridge.register("backup.enableSource") { p ->
			mutate {
				val id = source(p.id()).id
				db.setSourceCutoff(id, if (p.optBoolean("includeExisting")) null else System.currentTimeMillis())
				db.setSourceEnabled(id, true)
				Sources.scan(a, db, source(id))
				BackupScheduler.armMediaTrigger(a)
				BackupScheduler.schedulePeriodic(a)
				BackupScheduler.startBackfill(a) // the shell is visible: UIDT / FGS start allowed
			}
		}

		bridge.register("backup.disableSource") { p ->
			mutate { db.setSourceEnabled(source(p.id()).id, false) }
		}

		bridge.register("backup.addFolder") {
			val uri = a.pickTree() ?: return@register null
			val s = withContext(Dispatchers.IO) { Sources.safSource(a, uri).also(db::upsertSource) }
			bridge.emit("backup.changed", JSONObject())
			withContext(Dispatchers.IO) { sourceJson(db, source(s.id)) }
		}

		bridge.register("backup.removeFolder") { p ->
			mutate {
				val s = source(p.id())
				require(s.kind == Source.SAF) { "not a folder source: ${s.id}" }
				// Server files are never touched.
				db.tx {
					db.writableDatabase.delete("items", "source_id = ?", arrayOf(s.id))
					db.writableDatabase.delete("sources", "id = ?", arrayOf(s.id))
				}
				runCatching {
					a.contentResolver.releasePersistableUriPermission(Uri.parse(s.key), Intent.FLAG_GRANT_READ_URI_PERMISSION)
				}
			}
		}

		bridge.register("backup.requestPermissions") {
			a.awaitPermissions(requestable())
			bridge.emit("backup.changed", JSONObject())
			permissions(a)
		}

		bridge.register("backup.runNow") {
			mutate {
				st.relinkNeeded = false // explicit retry; the drain sets it again if the key is still rejected
				for (s in db.sources()) if (s.enabled) Sources.scan(a, db, s)
				BackupScheduler.startBackfill(a)
			}
		}

		bridge.register("backup.failures") { p ->
			withContext(Dispatchers.IO) {
				db.readableDatabase.rawQuery(
					"SELECT name, last_error, attempts FROM items WHERE source_id = ? AND state = 'failed' ORDER BY name",
					arrayOf(p.id()),
				).use { c ->
					val out = JSONArray()
					while (c.moveToNext()) {
						out.put(JSONObject()
							.put("name", c.getString(0))
							.put("lastError", if (c.isNull(1)) JSONObject.NULL else c.getString(1))
							.put("attempts", c.getInt(2)))
					}
					out
				}
			}
		}
	}

	/** Blocking (counts query). */
	private fun sourceJson(db: BackupDb, s: Source): JSONObject {
		val c = db.counts(s.id)
		return JSONObject()
			.put("id", s.id)
			.put("kind", s.kind)
			.put("name", s.name)
			.put("enabled", s.enabled)
			.put("preset", s.preset?.let(::profileName) ?: JSONObject.NULL)
			.put("mobileData", s.mobileData ?: JSONObject.NULL)
			.put("cutoffMs", s.cutoffMs ?: JSONObject.NULL)
			.put("newestFirst", s.newestFirst)
			.put("counts", JSONObject()
				.put("total", c.values.sum())
				.put("done", c["done"] ?: 0)
				.put("pending", c["pending"] ?: 0)
				.put("failed", c["failed"] ?: 0))
	}

	private fun permissions(a: MainActivity): JSONObject {
		fun has(p: String) = a.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
		val media = when {
			Build.VERSION.SDK_INT >= 33 && has(Manifest.permission.READ_MEDIA_IMAGES) -> "granted"
			Build.VERSION.SDK_INT >= 34 && has(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> "limited"
			Build.VERSION.SDK_INT < 33 && has(Manifest.permission.READ_EXTERNAL_STORAGE) -> "granted"
			else -> "denied"
		}
		val notifications = Build.VERSION.SDK_INT < 33 || has(Manifest.permission.POST_NOTIFICATIONS)
		return JSONObject().put("media", media).put("notifications", notifications)
	}

	private fun requestable(): Array<String> = when {
		Build.VERSION.SDK_INT >= 34 -> arrayOf(
			Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO,
			Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
			Manifest.permission.ACCESS_MEDIA_LOCATION, Manifest.permission.POST_NOTIFICATIONS,
		)
		Build.VERSION.SDK_INT >= 33 -> arrayOf(
			Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO,
			Manifest.permission.ACCESS_MEDIA_LOCATION, Manifest.permission.POST_NOTIFICATIONS,
		)
		else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.ACCESS_MEDIA_LOCATION)
	}
}
