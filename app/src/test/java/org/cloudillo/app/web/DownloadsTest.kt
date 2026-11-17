// SPDX-FileCopyrightText: Szilárd Hajba
// SPDX-License-Identifier: MPL-2.0

package org.cloudillo.app.web

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadsTest {
	@Test
	fun safeName() {
		assertEquals("x.pdf", Downloads.safeName("../../x.pdf"))
		assertEquals("b", Downloads.safeName("a\\b"))
		assertEquals("download", Downloads.safeName(".."))
		assertEquals("ok.txt", Downloads.safeName("ok.txt"))
	}
}
