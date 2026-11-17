// SPDX-FileCopyrightText: Szilárd Hajba
// SPDX-License-Identifier: MPL-2.0

package org.cloudillo.app

import android.content.Context
import android.content.SharedPreferences

/** App preferences in SharedPreferences "cloudillo". Per-account values use [accountKey] so multi-account can come later. */
object Prefs {
	private const val KEY_ID_TAG = "idTag"
	private const val KEY_APP_DOMAIN = "appDomain"
	private lateinit var prefs: SharedPreferences

	fun init(ctx: Context) {
		if (!::prefs.isInitialized) prefs = ctx.applicationContext.getSharedPreferences("cloudillo", Context.MODE_PRIVATE)
	}

	/** The "cloudillo" SharedPreferences, for callers that keep their own keys in it. */
	fun shared(ctx: Context): SharedPreferences {
		init(ctx)
		return prefs
	}

	private val HOSTNAME =
		Regex("""^(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$""")

	/** Lower-cases, strips a pasted scheme and trailing slashes; null unless the rest is a dotted hostname. */
	fun normalizeIdTag(input: String): String? {
		val s = input.trim().lowercase().replaceFirst(Regex("^https?://"), "").trimEnd('/')
		return s.takeIf { HOSTNAME.matches(it) }
	}

	/** The active account (single account in v1); null until onboarding succeeds. */
	var idTag: String?
		get() = prefs.getString(KEY_ID_TAG, null)
		set(value) {
			val e = prefs.edit()
			if (value == null) e.remove(KEY_ID_TAG).remove(KEY_APP_DOMAIN) else e.putString(KEY_ID_TAG, value)
			e.apply()
		}

	/** Shell host of the active account (`/api/me/app-domain`); null until discovered, then use the idTag. */
	var appDomain: String?
		get() = prefs.getString(KEY_APP_DOMAIN, null)
		set(value) {
			prefs.edit().putString(KEY_APP_DOMAIN, value).apply()
		}

	/** The `appDomain` from `/api/me/app-domain` if it is a valid hostname, else [idTag]. */
	fun appDomainOr(raw: String?, idTag: String) = raw?.let(::normalizeIdTag) ?: idTag

	/** Key for a per-account value: "<idTag>/<name>". */
	fun accountKey(idTag: String, name: String) = "$idTag/$name"
}
