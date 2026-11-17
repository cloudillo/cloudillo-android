// SPDX-FileCopyrightText: Szilárd Hajba
// SPDX-License-Identifier: MPL-2.0

package org.cloudillo.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.window.OnBackInvokedDispatcher
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.cloudillo.app.backup.BackupDb
import org.cloudillo.app.backup.BackupScheduler
import org.cloudillo.app.bridge.BackupBridge
import org.cloudillo.app.bridge.Bridge
import org.cloudillo.app.bridge.PushBridge
import org.cloudillo.app.push.NotificationPoller
import org.cloudillo.app.push.PushRegistrar
import org.cloudillo.app.push.UnifiedPush
import org.cloudillo.app.store.SecureStore
import org.cloudillo.app.web.Downloads
import org.cloudillo.app.web.ShellChromeClient
import org.cloudillo.app.web.ShellWebViewClient

/** Launcher: hosts the Cloudillo shell (https://<appDomain>/) in a WebView, or sends the user to onboarding. */
class MainActivity : Activity() {
	companion object {
		const val REQ_FILE_CHOOSER = 1
		const val REQ_PERMISSIONS = 2
		const val REQ_TREE = 3
		const val REQ_BACKUP_PERMISSIONS = 4
		/** Shell path (starting with `/`) to open instead of `/`. */
		const val EXTRA_PATH = "org.cloudillo.app.PATH"
		/** Web `parseDocRef` regex without the `cl:` prefix: `<app>/<owner>:<fileId>[?query]`. */
		private val DOC_REF = Regex("""[^/?\s]+/[^/?\s:]+:[^/?\s]+(\?\S*)?""")
	}

	lateinit var idTag: String
		private set
	/** Shell host; equals [idTag] unless the tenant's web domain differs. */
	lateinit var appDomain: String
		private set
	lateinit var webView: WebView
		private set
	/** Null when WebView lacks WEB_MESSAGE_LISTENER. Later features add methods via [Bridge.register]. */
	var bridge: Bridge? = null
		private set
	/** Main-thread scope, cancelled in onDestroy; bridge handlers run here. */
	val scope = MainScope()
	private lateinit var chromeClient: ShellChromeClient
	private lateinit var root: FrameLayout
	private var rendererGone = false
	private lateinit var errorView: View
	private var pendingTree: CompletableDeferred<Uri?>? = null
	private var pendingPermissions: CompletableDeferred<Unit>? = null

	@SuppressLint("SetJavaScriptEnabled") // the shell is a JS app
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		Prefs.init(this)
		val tag = Prefs.idTag
		if (tag == null) {
			startActivity(Intent(this, OnboardingActivity::class.java))
			finish()
			return
		}
		idTag = tag
		appDomain = Prefs.appDomain ?: tag
		// Installs from before app-domain discovery: look it up now, takes effect on the next start (offline: retried)
		if (Prefs.appDomain == null) scope.launch { OnboardingActivity.discoverAppDomain(tag)?.let { Prefs.appDomain = it } }

		// chrome://inspect for debug builds only
		if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) WebView.setWebContentsDebuggingEnabled(true)
		webView = WebView(this)
		webView.settings.apply {
			javaScriptEnabled = true
			domStorageEnabled = true
			mediaPlaybackRequiresUserGesture = false
			mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
			allowFileAccess = false
			setSupportMultipleWindows(false)
		}
		// The shell follows the system theme itself; don't let WebView invert it
		if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
			WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.settings, false)
		}
		webView.webViewClient = ShellWebViewClient(appDomain, tag) { errorView.visibility = View.VISIBLE }
		chromeClient = ShellChromeClient(this)
		webView.webChromeClient = chromeClient
		// Must be attached before the first load so window.CloudilloNative is injected
		if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
			val b = Bridge(this, tag)
			b.attach(webView)
			webView.setDownloadListener(Downloads(this, appDomain, b))
			bridge = b
			BackupBridge.register(b, this)
			PushBridge.register(b, this)
		} else {
			// No bridge: no device key, so backup/push cannot be configured; no downloads either
			Log.w("Cloudillo", "WebView lacks WEB_MESSAGE_LISTENER: native bridge disabled")
		}

		errorView = buildErrorView()
		root = FrameLayout(this)
		root.addView(webView, MATCH_PARENT, MATCH_PARENT)
		root.addView(errorView, MATCH_PARENT, MATCH_PARENT)
		setContentView(root)
		applySystemBars(root)
		errorView.setBackgroundColor(barColor())

		if (savedInstanceState == null || webView.restoreState(savedInstanceState) == null) loadPath(intentPath(intent) ?: "/")

		// Content triggers don't survive reboot; re-arm backup jobs whenever the app starts
		scope.launch(Dispatchers.IO) {
			if (BackupDb.get(this@MainActivity, tag).sources().any { it.enabled }) {
				BackupScheduler.armMediaTrigger(this@MainActivity)
				BackupScheduler.schedulePeriodic(this@MainActivity)
			}
		}
		syncPush()

		if (Build.VERSION.SDK_INT >= 33) {
			// ponytail: always intercepts back, so no predictive back-to-home animation; toggle on canGoBack if wanted
			onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { goBackOrFinish() }
		}
	}

	/**
	 * App start and after `setDeviceKey`: arms the poll job, syncs the push registration and, once a device key
	 * exists, asks for POST_NOTIFICATIONS once and for the distributor when any are installed and none chosen.
	 */
	fun syncPush() {
		scope.launch {
			val ctx = this@MainActivity
			val hasKey = withContext(Dispatchers.IO) {
				NotificationPoller.schedule(ctx)
				PushRegistrar.sync(ctx)
				SecureStore.deviceKey(ctx, idTag) != null
			}
			if (!hasKey || PushRegistrar.isOff(ctx, idTag)) return@launch
			val prefs = Prefs.shared(ctx)
			val asked = Prefs.accountKey(idTag, "notifyPermAsked")
			if (Build.VERSION.SDK_INT >= 33 && !prefs.getBoolean(asked, false) &&
				checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
			) {
				prefs.edit().putBoolean(asked, true).apply()
				awaitPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
			}
			val choices = withContext(Dispatchers.IO) {
				if (UnifiedPush.distributor(ctx) == null) UnifiedPush.distributors(ctx) else emptyList()
			}
			if (choices.isNotEmpty()) chooseDistributor(choices)
		}
	}

	/** Asks which UnifiedPush distributor to use; [UnifiedPush] remembers the pick. Dismissed: asked again next start. */
	@Suppress("DEPRECATION")
	internal fun chooseDistributor(pkgs: List<String>) {
		val labels = pkgs.map {
			try {
				packageManager.getApplicationLabel(packageManager.getApplicationInfo(it, 0)).toString()
			} catch (e: PackageManager.NameNotFoundException) {
				it
			}
		}.toTypedArray()
		AlertDialog.Builder(this)
			.setTitle(R.string.notify_choose_distributor)
			.setItems(labels) { _, i -> scope.launch { PushRegistrar.sync(this@MainActivity, pkgs[i]) } }
			.show()
	}

	/** Loads https://<appDomain><path> in the shell WebView. */
	fun loadPath(path: String) {
		errorView.visibility = View.GONE // a previous failure's overlay would hide the new page
		webView.loadUrl("https://$appDomain${if (path.startsWith('/')) path else "/$path"}")
	}

	// singleTask: share results and cl: links reuse the running shell
	override fun onNewIntent(intent: Intent) {
		super.onNewIntent(intent)
		setIntent(intent)
		if (::webView.isInitialized) intentPath(intent)?.let(::loadPath)
	}

	/** [EXTRA_PATH] or a `cl:` link (`cl:/<path>` or `cl:<app>/<owner>:<fileId>`); null unless a `/` path (exported: untrusted). */
	private fun intentPath(intent: Intent?): String? {
		val data = intent?.data
		val path = intent?.getStringExtra(EXTRA_PATH)
			?: if (intent?.action == Intent.ACTION_VIEW && data?.scheme == "cl") {
				// opaque URI: the scheme-specific part includes the query; stays percent-encoded
				val ref = data.encodedSchemeSpecificPart
				when {
					ref.startsWith('/') -> ref
					DOC_REF.matches(ref) -> "/~/app/$ref"
					else -> null
				}
			} else null
		return path?.takeIf { it.startsWith('/') }
	}

	/** Opens the SAF folder picker; the picked tree URI, or null when cancelled. Main thread. */
	suspend fun pickTree(): Uri? {
		pendingTree?.complete(null)
		val d = CompletableDeferred<Uri?>()
		pendingTree = d
		startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_TREE)
		return d.await()
	}

	/** Runs the runtime-permission prompt for [perms] and waits until the user answers. Main thread. */
	suspend fun awaitPermissions(perms: Array<String>) {
		pendingPermissions?.complete(Unit)
		val d = CompletableDeferred<Unit>()
		pendingPermissions = d
		requestPermissions(perms, REQ_BACKUP_PERMISSIONS)
		d.await()
	}

	private fun goBackOrFinish() {
		if (webView.canGoBack()) webView.goBack() else finish()
	}

	// Only reached on API 29-32; API 33+ uses the OnBackInvokedDispatcher callback in onCreate
	@SuppressLint("GestureBackNavigation", "MissingSuperCall")
	@Suppress("OVERRIDE_DEPRECATION")
	override fun onBackPressed() = goBackOrFinish()

	private fun buildErrorView(): View = LinearLayout(this).apply {
		orientation = LinearLayout.VERTICAL
		gravity = Gravity.CENTER
		visibility = View.GONE
		isClickable = true // keep touches off the WebView's own error page underneath
		addView(TextView(context).apply {
			setText(R.string.error_load)
			gravity = Gravity.CENTER
		})
		addView(Button(context).apply {
			setText(R.string.retry)
			setOnClickListener {
				errorView.visibility = View.GONE
				webView.reload()
			}
		})
	}

	override fun onConfigurationChanged(newConfig: Configuration) {
		super.onConfigurationChanged(newConfig)
		if (!::root.isInitialized) return
		applySystemBars(root)
		errorView.setBackgroundColor(barColor())
	}

	/** The renderer died: this WebView is unusable; rebuild the activity (and WebView) in place. */
	fun onRendererGone() {
		rendererGone = true
		root.removeView(webView)
		recreate()
	}

	override fun onSaveInstanceState(outState: Bundle) {
		super.onSaveInstanceState(outState)
		// A dead renderer's state is useless: skipping it makes the recreated activity load fresh
		if (::webView.isInitialized && !rendererGone) webView.saveState(outState)
	}

	override fun onDestroy() {
		scope.cancel()
		if (::webView.isInitialized) webView.destroy()
		super.onDestroy()
	}

	override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
		when (requestCode) {
			REQ_FILE_CHOOSER -> chromeClient.onFileChooserResult(resultCode, data)
			REQ_TREE -> pendingTree?.complete(if (resultCode == RESULT_OK) data?.data else null).also { pendingTree = null }
			else -> super.onActivityResult(requestCode, resultCode, data)
		}
	}

	override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
		when (requestCode) {
			REQ_PERMISSIONS -> chromeClient.onPermissionsResult()
			REQ_BACKUP_PERMISSIONS -> pendingPermissions?.complete(Unit).also { pendingPermissions = null }
			else -> super.onRequestPermissionsResult(requestCode, permissions, grantResults)
		}
	}
}

fun Context.isNightMode(): Boolean =
	(resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

/** Background drawn behind the transparent system bars. */
fun Context.barColor(): Int = if (isNightMode()) Color.BLACK else Color.WHITE

/**
 * Edge-to-edge: [root] is padded out of the system bars, cutout and IME, and bar icons follow
 * the system night mode (the shell follows the system theme too). Call again on uiMode change.
 */
@Suppress("DEPRECATION")
fun Activity.applySystemBars(root: View) {
	val night = isNightMode()
	root.setBackgroundColor(barColor())
	window.statusBarColor = Color.TRANSPARENT
	window.navigationBarColor = Color.TRANSPARENT
	window.isNavigationBarContrastEnforced = false
	if (Build.VERSION.SDK_INT >= 30) {
		window.setDecorFitsSystemWindows(false)
		val light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
			WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
		window.insetsController?.setSystemBarsAppearance(if (night) 0 else light, light)
	} else {
		window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
			View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
			View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
			(if (night) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR)
	}
	root.setOnApplyWindowInsetsListener { v, insets ->
		if (Build.VERSION.SDK_INT >= 30) {
			val i = insets.getInsets(
				WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime(),
			)
			v.setPadding(i.left, i.top, i.right, i.bottom)
			WindowInsets.CONSUMED
		} else {
			v.setPadding(
				insets.systemWindowInsetLeft,
				insets.systemWindowInsetTop,
				insets.systemWindowInsetRight,
				insets.systemWindowInsetBottom,
			)
			insets.consumeSystemWindowInsets()
		}
	}
	root.requestApplyInsets()
}
