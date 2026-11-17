// SPDX-FileCopyrightText: Szilárd Hajba
// SPDX-License-Identifier: MPL-2.0

package org.cloudillo.app.backup

import android.content.Context

/** Upload profile names the server accepts (`/api/uploads/<name>`). */
val PROFILES = setOf("orig", "preview", "standard", "high", "compact")

/** Maps a stored preset name, possibly a pre-profile legacy one, to a profile name. */
fun profileName(stored: String): String = when (stored) {
	in PROFILES -> stored
	"archive" -> "preview"
	"high_quality" -> "high"
	"mobile" -> "compact"
	"orig-only" -> "orig"
	else -> "standard" // includes legacy "default"
}

/** Global backup settings of one account (SharedPreferences "backup-<idTag>") + per-source fallback. */
class BackupSettings(ctx: Context, idTag: String) {
	private val sp = ctx.applicationContext.getSharedPreferences("backup-$idTag", Context.MODE_PRIVATE)

	var defaultPreset: String
		get() = profileName(sp.getString("defaultPreset", null) ?: "standard")
		set(v) = sp.edit().putString("defaultPreset", v).apply()

	var defaultMobileData: Boolean
		get() = sp.getBoolean("defaultMobileData", false)
		set(v) = sp.edit().putBoolean("defaultMobileData", v).apply()

	var requiresCharging: Boolean
		get() = sp.getBoolean("requiresCharging", false)
		set(v) = sp.edit().putBoolean("requiresCharging", v).apply()

	var videosWifiOnly: Boolean
		get() = sp.getBoolean("videosWifiOnly", true)
		set(v) = sp.edit().putBoolean("videosWifiOnly", v).apply()

	/** Set on HTTP 401/403 from an upload; uploads stay stopped until the device is re-linked. */
	var relinkNeeded: Boolean
		get() = sp.getBoolean("relinkNeeded", false)
		set(v) = sp.edit().putBoolean("relinkNeeded", v).apply()

	fun effectivePreset(source: Source): String = source.preset?.let(::profileName) ?: defaultPreset

	fun effectiveMobileData(source: Source): Boolean = source.mobileData ?: defaultMobileData
}
