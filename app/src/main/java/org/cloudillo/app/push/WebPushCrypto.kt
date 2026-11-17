// SPDX-FileCopyrightText: Szilárd Hajba
// SPDX-License-Identifier: MPL-2.0

package org.cloudillo.app.push

import android.annotation.SuppressLint
import android.content.Context
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.AlgorithmParameters
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.cloudillo.app.Prefs

/**
 * Web Push receiver side: P-256 keys and RFC 8291 / RFC 8188 (aes128gcm) decryption, JCA only.
 * java.util.Base64 (not android.util) so the JVM unit test can run [decrypt].
 * The private key and auth secret are secrets: never log them.
 */
@SuppressLint("ApplySharedPref")
object WebPushCrypto {
	@Suppress("ArrayInDataClass")
	data class Keys(val publicUncompressed: ByteArray, val privatePkcs8: ByteArray, val auth: ByteArray)

	private val p256: ECParameterSpec by lazy {
		AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
			.getParameterSpec(ECParameterSpec::class.java)
	}

	fun b64url(b: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(b)

	fun generateKeys(): Keys {
		val gen = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
		val kp = gen.generateKeyPair()
		val w = (kp.public as ECPublicKey).w
		val pub = byteArrayOf(4) + fixed32(w.affineX) + fixed32(w.affineY)
		val auth = ByteArray(16).also { SecureRandom().nextBytes(it) }
		return Keys(pub, kp.private.encoded, auth)
	}

	/** Decrypts an aes128gcm Web Push body. Throws [GeneralSecurityException] on any malformed or forged input. */
	fun decrypt(body: ByteArray, keys: Keys): ByteArray {
		if (body.size < 21) throw GeneralSecurityException("short header")
		val salt = body.copyOfRange(0, 16)
		val rs = ByteBuffer.wrap(body, 16, 4).int.toLong() and 0xffffffffL
		val idlen = body[20].toInt() and 0xff
		val start = 21 + idlen
		if (rs < 18 || idlen != 65 || body.size <= start) throw GeneralSecurityException("bad header")
		val asPublic = body.copyOfRange(21, start)

		val kf = KeyFactory.getInstance("EC")
		val ka = KeyAgreement.getInstance("ECDH")
		ka.init(kf.generatePrivate(PKCS8EncodedKeySpec(keys.privatePkcs8)))
		ka.doPhase(publicKey(kf, asPublic), true)
		val ecdh = ka.generateSecret()

		// RFC 8291 §3.4
		val prkKey = hmac(keys.auth, ecdh)
		val ikm = hmac(prkKey, "WebPush: info\u0000".toByteArray(), keys.publicUncompressed, asPublic, byteArrayOf(1))
		val prk = hmac(salt, ikm)
		val cek = hmac(prk, "Content-Encoding: aes128gcm\u0000".toByteArray(), byteArrayOf(1)).copyOf(16)
		val nonce = hmac(prk, "Content-Encoding: nonce\u0000".toByteArray(), byteArrayOf(1)).copyOf(12)

		// RFC 8188 §2: records of rs bytes, last one may be shorter; delimiter 0x01, last 0x02
		val out = ByteArrayOutputStream()
		var off = start
		var seq = 0L
		while (off < body.size) {
			val end = minOf(off.toLong() + rs, body.size.toLong()).toInt()
			val last = end == body.size
			val iv = nonce.copyOf()
			for (i in 0 until 8) iv[11 - i] = (iv[11 - i].toInt() xor (seq ushr (8 * i)).toInt()).toByte()
			val c = Cipher.getInstance("AES/GCM/NoPadding")
			c.init(Cipher.DECRYPT_MODE, SecretKeySpec(cek, "AES"), GCMParameterSpec(128, iv))
			val p = c.doFinal(body, off, end - off)
			var i = p.size - 1
			while (i >= 0 && p[i].toInt() == 0) i--
			if (i < 0 || p[i].toInt() != (if (last) 2 else 1)) throw GeneralSecurityException("bad padding")
			out.write(p, 0, i)
			off = end
			seq++
		}
		return out.toByteArray()
	}

	private fun publicKey(kf: KeyFactory, b: ByteArray): java.security.PublicKey {
		if (b[0].toInt() != 4) throw GeneralSecurityException("not an uncompressed point")
		val point = ECPoint(BigInteger(1, b.copyOfRange(1, 33)), BigInteger(1, b.copyOfRange(33, 65)))
		return kf.generatePublic(ECPublicKeySpec(point, p256))
	}

	private fun fixed32(n: BigInteger): ByteArray {
		val b = n.toByteArray()
		return if (b.size >= 32) b.copyOfRange(b.size - 32, b.size) else ByteArray(32 - b.size) + b
	}

	private fun hmac(key: ByteArray, vararg data: ByteArray): ByteArray {
		val m = Mac.getInstance("HmacSHA256")
		m.init(SecretKeySpec(key, "HmacSHA256"))
		data.forEach { m.update(it) }
		return m.doFinal()
	}

	// --- Persistence: prefs "secure" (SecureStore's file), secrets sealed with SecureStore's Keystore key ---

	private const val STORE_ALIAS = "cloudillo-device-key"
	private const val CIPHER = "AES/GCM/NoPadding"

	private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("secure", Context.MODE_PRIVATE)

	/** SecureStore's AES key. Never generated here: two generators racing on one alias would lose the device key. */
	private fun storeKey(): SecretKey? =
		KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(STORE_ALIAS, null) as? SecretKey

	/** Stores [keys] for [idTag]; false if SecureStore holds no key yet (no device key was ever stored). */
	fun saveKeys(ctx: Context, idTag: String, keys: Keys): Boolean {
		val sk = storeKey() ?: return false
		val c = Cipher.getInstance(CIPHER)
		c.init(Cipher.ENCRYPT_MODE, sk)
		val ct = c.doFinal(keys.auth + keys.privatePkcs8)
		val enc = Base64.getEncoder()
		return prefs(ctx).edit()
			.putString(Prefs.accountKey(idTag, "pushPub"), enc.encodeToString(keys.publicUncompressed))
			.putString(Prefs.accountKey(idTag, "pushSecret"), enc.encodeToString(ct))
			.putString(Prefs.accountKey(idTag, "pushSecretIv"), enc.encodeToString(c.iv))
			.commit()
	}

	/** The stored keys, or null if none or they can no longer be decrypted. */
	fun loadKeys(ctx: Context, idTag: String): Keys? {
		val p = prefs(ctx)
		val pub = p.getString(Prefs.accountKey(idTag, "pushPub"), null) ?: return null
		val ct = p.getString(Prefs.accountKey(idTag, "pushSecret"), null) ?: return null
		val iv = p.getString(Prefs.accountKey(idTag, "pushSecretIv"), null) ?: return null
		return try {
			val dec = Base64.getDecoder()
			val c = Cipher.getInstance(CIPHER)
			c.init(Cipher.DECRYPT_MODE, storeKey() ?: return null, GCMParameterSpec(128, dec.decode(iv)))
			val plain = c.doFinal(dec.decode(ct))
			if (plain.size <= 16) return null
			Keys(dec.decode(pub), plain.copyOfRange(16, plain.size), plain.copyOf(16))
		} catch (e: GeneralSecurityException) {
			null
		} catch (e: IllegalArgumentException) {
			null
		}
	}

	fun clearKeys(ctx: Context, idTag: String) {
		prefs(ctx).edit()
			.remove(Prefs.accountKey(idTag, "pushPub"))
			.remove(Prefs.accountKey(idTag, "pushSecret"))
			.remove(Prefs.accountKey(idTag, "pushSecretIv"))
			.commit()
	}
}
