// SPDX-FileCopyrightText: Szilárd Hajba
// SPDX-License-Identifier: MPL-2.0

package org.cloudillo.app.push

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationPollerTest {
	@Test
	fun maxFromItemsEvenWhenLastSeqLarger() =
		assertEquals(12L, NotificationPoller.pageEnd(JSONArray("""[{"seq":11},{"seq":12}]"""), 500, 10))

	@Test
	fun emptyPageGivesLastSeq() = assertEquals(500L, NotificationPoller.pageEnd(JSONArray(), 500, 10))

	@Test
	fun missingSeqsIgnored() {
		assertEquals(11L, NotificationPoller.pageEnd(JSONArray("""[{"seq":11},{},{"seq":-1}]"""), 500, 10))
		assertEquals(500L, NotificationPoller.pageEnd(JSONArray("""[{},{"seq":-1}]"""), 500, 10))
	}
}
