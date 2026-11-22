// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.gradle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class UpstreamCommitTest {
    @Test
    fun `a full commit hash is kept`() {
        val sha = "3c1612efdcf38741f1b46b247bea75902658dc29"
        assertEquals(sha, UpstreamCommit.of(sha))
    }

    @Test
    fun `builds without the property show unknown`() {
        assertEquals(UpstreamCommit.UNKNOWN, UpstreamCommit.of(null))
    }

    @Test
    fun `anything but a full lowercase hash fails the build`() {
        for (value in listOf("", "3c1612e", "3C1612EFDCF38741F1B46B247BEA75902658DC29", "main", "\"; evil()")) {
            assertThrows<IllegalArgumentException>(value) { UpstreamCommit.of(value) }
        }
    }
}
