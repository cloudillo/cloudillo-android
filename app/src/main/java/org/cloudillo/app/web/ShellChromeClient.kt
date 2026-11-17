// SPDX-FileCopyrightText: Szilárd Hajba
// SPDX-License-Identifier: MPL-2.0

package org.cloudillo.app.web

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.webkit.MimeTypeMap
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import org.cloudillo.app.MainActivity
import org.cloudillo.app.R

/**
 * File chooser and getUserMedia permissions. Results come back through
 * [MainActivity.onActivityResult] -> [onFileChooserResult] and
 * [MainActivity.onRequestPermissionsResult] -> [onPermissionsResult].
 */
class ShellChromeClient(private val activity: MainActivity) : WebChromeClient() {
	private companion object {
		val CAPTURE = mapOf(
			PermissionRequest.RESOURCE_VIDEO_CAPTURE to Manifest.permission.CAMERA,
			PermissionRequest.RESOURCE_AUDIO_CAPTURE to Manifest.permission.RECORD_AUDIO,
		)
	}

	private var fileCallback: ValueCallback<Array<Uri>>? = null
	private var pendingPermission: PermissionRequest? = null
	private val allowedOrigins = HashSet<String>()

	override fun onShowFileChooser(
		view: WebView,
		callback: ValueCallback<Array<Uri>>,
		params: FileChooserParams,
	): Boolean {
		fileCallback?.onReceiveValue(null)
		fileCallback = callback
		// accept="" may hold MIME types or ".ext" entries
		val mimes = params.acceptTypes
			.flatMap { it.split(',') }
			.map { it.trim().lowercase() }
			.mapNotNull {
				when {
					'/' in it -> it
					it.startsWith('.') -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(it.substring(1))
					else -> null
				}
			}
			.distinct()
		val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
		if (mimes.isNotEmpty()) intent.putExtra(Intent.EXTRA_MIME_TYPES, mimes.toTypedArray())
		if (params.mode == FileChooserParams.MODE_OPEN_MULTIPLE) intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
		try {
			activity.startActivityForResult(intent, MainActivity.REQ_FILE_CHOOSER)
		} catch (e: ActivityNotFoundException) {
			fileCallback = null
			callback.onReceiveValue(null)
		}
		return true
	}

	fun onFileChooserResult(resultCode: Int, data: Intent?) {
		val cb = fileCallback ?: return
		fileCallback = null
		val uris = if (resultCode != Activity.RESULT_OK || data == null) {
			null
		} else {
			val clip = data.clipData
			if (clip != null) Array(clip.itemCount) { clip.getItemAt(it).uri } else data.data?.let { arrayOf(it) }
		}
		cb.onReceiveValue(uris)
	}

	override fun onPermissionRequest(request: PermissionRequest) {
		val wanted = request.resources.filter { it in CAPTURE }
		if (wanted.isEmpty() || !ShellWebViewClient.isAllowedOrigin(request.origin, activity.appDomain, activity.idTag)) {
			request.deny()
			return
		}
		// App iframes (cl-o.<idTag>) need an explicit OK, once per origin while this activity lives; the shell (appDomain) never asks
		val origin = request.origin.toString()
		if (request.origin.host == activity.appDomain || origin in allowedOrigins) {
			proceed(request, wanted)
			return
		}
		AlertDialog.Builder(activity)
			.setMessage(activity.getString(R.string.capture_confirm, request.origin.host))
			.setPositiveButton(R.string.capture_allow) { _, _ ->
				allowedOrigins += origin
				proceed(request, wanted)
			}
			.setNegativeButton(R.string.capture_deny) { _, _ -> request.deny() }
			.setOnCancelListener { request.deny() }
			.show()
	}

	/** Grants [wanted] when the runtime permissions are held, else asks for the missing ones first. */
	private fun proceed(request: PermissionRequest, wanted: List<String>) {
		val missing = wanted.map { CAPTURE.getValue(it) }.filter { !isGranted(it) }
		if (missing.isEmpty()) {
			request.grant(wanted.toTypedArray())
			return
		}
		pendingPermission?.deny()
		pendingPermission = request
		activity.requestPermissions(missing.toTypedArray(), MainActivity.REQ_PERMISSIONS)
	}

	/** Grants whatever capture resources now have their runtime permission; denies if none. */
	fun onPermissionsResult() {
		val request = pendingPermission ?: return
		pendingPermission = null
		val granted = request.resources.filter { res -> CAPTURE[res]?.let { isGranted(it) } == true }
		if (granted.isEmpty()) request.deny() else request.grant(granted.toTypedArray())
	}

	override fun onPermissionRequestCanceled(request: PermissionRequest) {
		if (pendingPermission == request) pendingPermission = null
	}

	private fun isGranted(permission: String) =
		activity.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}
