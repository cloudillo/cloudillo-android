// SPDX-FileCopyrightText: Szilárd Hajba
// SPDX-License-Identifier: MPL-2.0

package org.cloudillo.app.backup

import org.cloudillo.app.backup.Uploader.Companion.Outcome as o
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UploaderTest {
	private val sixHours = 6 * 60 * 60 * 1000L

	@Test
	fun backoffDelay() {
		assertEquals(60_000L, Uploader.backoffDelayMs(0))
		assertEquals(15_360_000L, Uploader.backoffDelayMs(8))
		assertEquals(sixHours, Uploader.backoffDelayMs(9))
		assertEquals(sixHours, Uploader.backoffDelayMs(100))
	}

	@Test
	fun clean() {
		assertEquals("a_b", Uploader.clean("a/b "))
		assertEquals("_", Uploader.clean("   "))
	}

	@Test
	fun outcome() {
		mapOf(
			401 to o.RELINK, 403 to o.CHECK_FOLDER, 404 to o.CHECK_FOLDER, 408 to o.BACKOFF, 429 to o.BACKOFF,
			400 to o.FAIL, 413 to o.FAIL, 500 to o.BACKOFF, 503 to o.BACKOFF,
		).forEach { (status, want) -> assertEquals("HTTP $status", want, Uploader.outcome(status)) }
	}

	@Test
	fun readGivesUp() {
		assertFalse(Uploader.readGivesUp(3))
		assertTrue(Uploader.readGivesUp(4))
	}
}
