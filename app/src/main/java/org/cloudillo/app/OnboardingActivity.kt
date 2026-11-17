// SPDX-FileCopyrightText: Szilárd Hajba
// SPDX-License-Identifier: MPL-2.0

package org.cloudillo.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.io.IOException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.cloudillo.app.net.ApiClient
import org.cloudillo.app.net.ApiException
import org.json.JSONException

/** First-run idTag entry: validate, look up https://cl-o.<idTag>/api/me/app-domain, store both, open MainActivity. */
class OnboardingActivity : Activity() {
	companion object {
		/** Boolean extra: forget the stored idTag and ask again. No UI entry yet; meant for the shell bridge. */
		const val EXTRA_RESET = "reset"

		/**
		 * The app domain (shell host) of [tag], or null if unreachable. Any HTTP response means the node is
		 * reachable (an older server without the endpoint: the idTag is the app domain); only a network/TLS error fails.
		 */
		suspend fun discoverAppDomain(tag: String): String? = try {
			Prefs.appDomainOr(ApiClient(tag) { null }.getJson("/api/me/app-domain").optString("appDomain"), tag)
		} catch (e: ApiException) {
			tag
		} catch (e: IOException) {
			tag.takeIf { e.cause is JSONException } // non-JSON 2xx: still an answer
		}
	}

	private val scope = MainScope()
	private lateinit var input: EditText
	private lateinit var error: TextView
	private lateinit var button: Button

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		Prefs.init(this)
		if (intent.getBooleanExtra(EXTRA_RESET, false)) Prefs.idTag = null

		val pad = (24 * resources.displayMetrics.density).toInt()
		val form = LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL
			gravity = Gravity.CENTER_VERTICAL
			setPadding(pad, pad, pad, pad)
		}
		form.addView(TextView(this).apply {
			setText(R.string.onboarding_title)
			textSize = 24f
		}, MATCH_PARENT, WRAP_CONTENT)
		input = EditText(this).apply {
			setHint(R.string.onboarding_hint)
			inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
			isSingleLine = true
			imeOptions = EditorInfo.IME_ACTION_GO
			setOnEditorActionListener { _, actionId, _ ->
				(actionId == EditorInfo.IME_ACTION_GO).also { if (it) submit() }
			}
		}
		form.addView(input, MATCH_PARENT, WRAP_CONTENT)
		error = TextView(this).apply {
			visibility = View.GONE
			setTextColor(0xFFD32F2F.toInt())
		}
		form.addView(error, MATCH_PARENT, WRAP_CONTENT)
		button = Button(this).apply {
			setText(R.string.onboarding_continue)
			setOnClickListener { submit() }
		}
		form.addView(button, MATCH_PARENT, WRAP_CONTENT)

		val root = FrameLayout(this)
		root.addView(form, MATCH_PARENT, MATCH_PARENT)
		setContentView(root)
		applySystemBars(root)
	}

	override fun onDestroy() {
		scope.cancel()
		super.onDestroy()
	}

	private fun submit() {
		val tag = Prefs.normalizeIdTag(input.text.toString())
		if (tag == null) {
			showError(getString(R.string.onboarding_invalid))
			return
		}
		error.visibility = View.GONE
		button.isEnabled = false
		button.setText(R.string.onboarding_checking)
		scope.launch {
			val domain = discoverAppDomain(tag)
			button.isEnabled = true
			button.setText(R.string.onboarding_continue)
			if (domain == null) {
				showError(getString(R.string.onboarding_unreachable, tag))
				return@launch
			}
			Prefs.idTag = tag
			Prefs.appDomain = domain
			startActivity(
				Intent(this@OnboardingActivity, MainActivity::class.java)
					.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
			)
			finish()
		}
	}

	private fun showError(msg: String) {
		error.text = msg
		error.visibility = View.VISIBLE
	}
}
