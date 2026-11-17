// SPDX-FileCopyrightText: Szilárd Hajba
// SPDX-License-Identifier: MPL-2.0

package org.cloudillo.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrefsTest {
	@Test
	fun normalizeIdTag() {
		assertEquals("alice.cloudillo.net", Prefs.normalizeIdTag("  Alice.Cloudillo.NET/ "))
		assertEquals("a.b", Prefs.normalizeIdTag("https://a.b/"))
		assertNull(Prefs.normalizeIdTag("localhost"))
		assertNull(Prefs.normalizeIdTag("a..b"))
		assertNull(Prefs.normalizeIdTag("-a.b"))
		assertNull(Prefs.normalizeIdTag("a.b/path"))
	}
}
