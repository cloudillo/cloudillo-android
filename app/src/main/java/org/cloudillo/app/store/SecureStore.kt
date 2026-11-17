// SPDX-FileCopyrightText: Szilárd Hajba
// SPDX-License-Identifier: MPL-2.0

package org.cloudillo.app.store

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.cloudillo.app.Prefs

/**
 * Device key at rest: AES-256-GCM with a non-auth-bound AndroidKeyStore key (background jobs use it),
 * ciphertext + IV base64 in private SharedPreferences "secure", keyed per idTag. Never log the key.
 * Writes use `commit()` on purpose: callers are on Dispatchers.IO and may finish the activity right after.
 */
@SuppressLint("ApplySharedPref")
object SecureStore {
	private const val ALIAS = "cloudillo-device-key"
	private const val CIPHER = "AES/GCM/NoPadding"

	private fun prefs(ctx: Context): SharedPreferences =
		ctx.applicationContext.getSharedPreferences("secure", Context.MODE_PRIVATE)

	@Synchronized
	private fun secretKey(): SecretKey {
		val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
		(ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
		val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
		gen.init(
			KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
				.setBlockModes(KeyProperties.BLOCK_MODE_GCM)
				.setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
				.setKeySize(256)
				.build(),
		)
		return gen.generateKey()
	}

	fun putDeviceKey(ctx: Context, idTag: String, key: String, keyId: String) {
		val c = Cipher.getInstance(CIPHER)
		c.init(Cipher.ENCRYPT_MODE, secretKey())
		val ct = c.doFinal(key.toByteArray(Charsets.UTF_8))
		prefs(ctx).edit()
			.putString(Prefs.accountKey(idTag, "deviceKey"), Base64.encodeToString(ct, Base64.NO_WRAP))
			.putString(Prefs.accountKey(idTag, "deviceKeyIv"), Base64.encodeToString(c.iv, Base64.NO_WRAP))
			.putString(Prefs.accountKey(idTag, "deviceKeyId"), keyId)
			.commit()
	}

	/** The decrypted device key, or null if none is stored or it can no longer be decrypted. */
	fun deviceKey(ctx: Context, idTag: String): String? {
		val p = prefs(ctx)
		val ct = p.getString(Prefs.accountKey(idTag, "deviceKey"), null) ?: return null
		val iv = p.getString(Prefs.accountKey(idTag, "deviceKeyIv"), null) ?: return null
		return try {
			val c = Cipher.getInstance(CIPHER)
			c.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
			String(c.doFinal(Base64.decode(ct, Base64.NO_WRAP)), Charsets.UTF_8)
		} catch (e: GeneralSecurityException) {
			null
		} catch (e: IllegalArgumentException) {
			null
		}
	}

	/** Server-side id of the stored key (for revocation), or null. */
	fun deviceKeyId(ctx: Context, idTag: String): String? =
		prefs(ctx).getString(Prefs.accountKey(idTag, "deviceKeyId"), null)

	fun clear(ctx: Context, idTag: String) {
		prefs(ctx).edit()
			.remove(Prefs.accountKey(idTag, "deviceKey"))
			.remove(Prefs.accountKey(idTag, "deviceKeyIv"))
			.remove(Prefs.accountKey(idTag, "deviceKeyId"))
			.commit()
	}
}
