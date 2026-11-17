// SPDX-FileCopyrightText: Szilárd Hajba
// SPDX-License-Identifier: MPL-2.0

package org.cloudillo.app.push

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPrivateKeySpec
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** RFC 8291 Appendix A / Section 5 vector. */
class WebPushCryptoTest {
	private fun b64(s: String): ByteArray = Base64.getUrlDecoder().decode(s.replace(" ", ""))

	private val keys: WebPushCrypto.Keys by lazy {
		val params = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
			.getParameterSpec(ECParameterSpec::class.java)
		val d = BigInteger(1, b64("q1dXpw3UpT5VOmu_cf_v6ih07Aems3njxI-JWgLcM94"))
		val pkcs8 = KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(d, params)).encoded
		WebPushCrypto.Keys(
			publicUncompressed = b64("BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4"),
			privatePkcs8 = pkcs8,
			auth = b64("BTBZMqHH6r4Tts7J_aSIgg"),
		)
	}

	private val header = b64(
		"DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z 9KsN6nGRTbVYI_c7VJSPQTBtkgcy27ml" +
			"mlMoZIIgDll6e3vCYLocInmYWAmS6Tlz AC8wEqKK6PBru3jl7A8",
	)
	private val ciphertext = b64("8pfeW0KbunFT06SuDKoJH9Ql87S1QUrd irN6GcG7sFz1y1sqLgVi1VhjVkHsUoEs bI_0LpXMuGvnzQ")

	@Test
	fun decryptsRfc8291Vector() {
		val plain = WebPushCrypto.decrypt(header + ciphertext, keys)
		assertEquals("When I grow up, I want to be a watermelon", String(plain, Charsets.UTF_8))
	}

	@Test
	fun rejectsTamperedCiphertext() {
		val body = header + ciphertext
		body[body.size - 1] = (body[body.size - 1].toInt() xor 1).toByte()
		assertThrows(GeneralSecurityException::class.java) { WebPushCrypto.decrypt(body, keys) }
	}
}
