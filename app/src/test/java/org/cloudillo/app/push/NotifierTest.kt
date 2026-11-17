// SPDX-FileCopyrightText: Szilárd Hajba
// SPDX-License-Identifier: MPL-2.0

package org.cloudillo.app.push

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotifierTest {
	@Test
	fun isGap() {
		assertFalse(Notifier.isGap(-1, 5)) // never synced
		assertFalse(Notifier.isGap(7, 8))
		assertTrue(Notifier.isGap(7, 10))
		assertFalse(Notifier.isGap(7, 7))
		assertFalse(Notifier.isGap(7, -1)) // no seq
	}
}
