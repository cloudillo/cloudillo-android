// SPDX-FileCopyrightText: Szilárd Hajba
// SPDX-License-Identifier: MPL-2.0

package org.cloudillo.app.net

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ApiClientTest {
	@Test
	fun blank() = assertEquals(0, ApiClient.parse("  ").length())

	@Test
	fun objectEnvelopeUnwrapped() = assertEquals("x", ApiClient.parse("""{"data":{"id":"x"}}""").getString("id"))

	@Test
	fun arrayEnvelopeWhole() {
		val json = ApiClient.parse("""{"data":[1,2],"next":"c"}""")
		assertEquals(2, json.getJSONArray("data").length())
		assertEquals("c", json.getString("next"))
	}

	@Test
	fun html() {
		assertThrows(IOException::class.java) { ApiClient.parse("<html>portal</html>") }
	}
}
