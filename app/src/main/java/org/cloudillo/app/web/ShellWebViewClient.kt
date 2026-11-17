// SPDX-FileCopyrightText: Szilárd Hajba
// SPDX-License-Identifier: MPL-2.0

package org.cloudillo.app.web

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import org.cloudillo.app.MainActivity
import org.cloudillo.app.R

/**
 * Origin policy: top-level navigation stays in the WebView only for https://<appDomain> and
 * https://cl-o.<idTag>; other http:, https:, mailto: and tel: URLs go to ACTION_VIEW, but only after a tap
 * (or a redirect chain that started with one); script-driven navigations elsewhere, redirects of them, and all
 * other schemes are dropped.
 * Sub-frame navigations are not touched (app iframes live on cl-o.<idTag>).
 */
class ShellWebViewClient(
	private val appDomain: String,
	private val idTag: String,
	private val onMainFrameError: () -> Unit,
) : WebViewClient() {
	companion object {
		fun isAllowedOrigin(uri: Uri, appDomain: String, idTag: String): Boolean =
			uri.scheme == "https" &&
				(uri.port == -1 || uri.port == 443) &&
				(uri.host == appDomain || uri.host == "cl-o.$idTag")
	}

	/** Whether the main-frame navigation that started the current redirect chain was tapped. */
	private var tapped = false

	override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
		if (!request.isForMainFrame) return false
		if (!request.isRedirect) tapped = request.hasGesture()
		if (isAllowedOrigin(request.url, appDomain, idTag)) return false
		// No tap and no redirect of a tapped one: a script-driven navigation may not launch other apps
		if (!request.hasGesture() && !(request.isRedirect && tapped)) return true
		if (request.url.scheme?.lowercase() !in setOf("http", "https", "mailto", "tel")) return true // intent:, market:, …: dropped
		try {
			view.context.startActivity(Intent(Intent.ACTION_VIEW, request.url))
		} catch (e: ActivityNotFoundException) {
			Toast.makeText(view.context, R.string.no_app_for_link, Toast.LENGTH_SHORT).show()
		}
		return true
	}

	// The crashed/killed renderer leaves this WebView unusable: rebuild the activity with a fresh one
	override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
		(view.context as? MainActivity)?.onRendererGone()
		return true
	}

	override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
		if (request.isForMainFrame) onMainFrameError()
	}
}
