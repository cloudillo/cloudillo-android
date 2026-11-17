// SPDX-FileCopyrightText: Szilárd Hajba
// SPDX-License-Identifier: MPL-2.0

package org.cloudillo.app.bridge

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeTest {
	private val handlers = HashMap<String, suspend (JSONObject) -> Any?>().apply {
		put("echo") { p -> JSONObject().put("x", p.optInt("x")) }
		put("nothing") { null }
		put("boom") { throw IllegalArgumentException("x") }
	}

	private fun reply(req: String) = runBlocking { Bridge.reply(JSONObject(req), handlers, log = {}) }

	@Test
	fun successWrappedAndIdEchoed() {
		val r = reply("""{"v":1,"id":7,"method":"echo","params":{"x":3}}""")
		assertEquals(7, r.getInt("id"))
		assertEquals(3, r.getJSONObject("result").getInt("x"))
		assertFalse(r.has("error"))
	}

	@Test
	fun missingIdIsNull() {
		assertTrue(reply("""{"v":1,"method":"echo"}""").isNull("id"))
	}

	@Test
	fun nullResultIsNull() {
		val r = reply("""{"v":1,"id":1,"method":"nothing"}""")
		assertTrue(r.has("result") && r.isNull("result"))
	}

	@Test
	fun unknownMethodIsError() {
		val r = reply("""{"v":1,"id":1,"method":"nope"}""")
		assertTrue(r.has("error"))
		assertFalse(r.has("result"))
	}

	@Test
	fun wrongVersionIsError() {
		val r = reply("""{"v":2,"id":1,"method":"echo"}""")
		assertTrue(r.has("error"))
		assertFalse(r.has("result"))
	}

	@Test
	fun handlerThrowIsError() {
		assertEquals("x", reply("""{"v":1,"id":1,"method":"boom"}""").getString("error"))
	}
}
