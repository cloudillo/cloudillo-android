// SPDX-FileCopyrightText: Szilárd Hajba
// SPDX-License-Identifier: MPL-2.0

package org.cloudillo.app.bridge

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.cloudillo.app.MainActivity
import org.cloudillo.app.push.PushRegistrar
import org.cloudillo.app.push.UnifiedPush
import org.cloudillo.app.store.SecureStore
import org.json.JSONObject

/** The `push.*` bridge methods behind the shell's "Enable push notifications on this device" switch. */
object PushBridge {
	fun register(bridge: Bridge, activity: MainActivity) {
		val a = activity
		val perm = Manifest.permission.POST_NOTIFICATIONS
		fun granted() = Build.VERSION.SDK_INT < 33 || a.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

		suspend fun state(): JSONObject = withContext(Dispatchers.IO) {
			val permission = granted()
			JSONObject()
				.put("enabled", permission && !PushRegistrar.isOff(a, a.idTag))
				.put("permission", permission)
				.put("hasDeviceKey", SecureStore.deviceKey(a, a.idTag) != null)
				.put("distributors", UnifiedPush.distributors(a).size)
				.put("distributor", UnifiedPush.distributor(a) ?: JSONObject.NULL)
				.put("subscribed", PushRegistrar.isSubscribed(a, a.idTag))
		}

		bridge.register("push.getState") { state() }

		bridge.register("push.enable") {
			withContext(Dispatchers.IO) { PushRegistrar.setOff(a, a.idTag, false) }
			if (!granted()) {
				a.awaitPermissions(arrayOf(perm))
				// Still denied with no rationale: permanently denied, only system settings can grant it
				if (!granted() && !a.shouldShowRequestPermissionRationale(perm)) {
					a.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, a.packageName))
				}
			}
			PushRegistrar.sync(a)
			val choices = withContext(Dispatchers.IO) {
				if (UnifiedPush.distributor(a) == null) UnifiedPush.distributors(a) else emptyList()
			}
			if (choices.isNotEmpty()) a.chooseDistributor(choices)
			state()
		}

		bridge.register("push.disable") {
			withContext(Dispatchers.IO) { PushRegistrar.setOff(a, a.idTag, true) }
			PushRegistrar.clear(a)
			state()
		}
	}
}
