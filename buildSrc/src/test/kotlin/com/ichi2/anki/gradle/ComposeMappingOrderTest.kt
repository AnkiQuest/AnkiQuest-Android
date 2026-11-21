// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.gradle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Tests for [sortComposeMapping] */
class ComposeMappingOrderTest {
    @Test
    fun `sorts the compose block and keeps every other line`() {
        val unsorted =
            """
            # compiler: R8
            b.Second -> b:
                1:1:void z() -> z
                1:1:void a() -> a
            ComposeStackTrace -> ${'$'}${'$'}compose:
                1:1:void p.B.f(androidx.compose.runtime.Composer,int):3:3 -> m${'$'}2
                1:1:void p.A.g(androidx.compose.runtime.Composer,int):9:9 -> m${'$'}-1
                1:1:void p.A.f(androidx.compose.runtime.Composer,int):5:5 -> m${'$'}7
            a.After -> c:
                1:1:void y() -> y
                1:1:void x() -> x

            """.trimIndent()
        val sorted =
            """
            # compiler: R8
            b.Second -> b:
                1:1:void z() -> z
                1:1:void a() -> a
            ComposeStackTrace -> ${'$'}${'$'}compose:
                1:1:void p.A.f(androidx.compose.runtime.Composer,int):5:5 -> m${'$'}7
                1:1:void p.A.g(androidx.compose.runtime.Composer,int):9:9 -> m${'$'}-1
                1:1:void p.B.f(androidx.compose.runtime.Composer,int):3:3 -> m${'$'}2
            a.After -> c:
                1:1:void y() -> y
                1:1:void x() -> x

            """.trimIndent()

        assertEquals(sorted, sortComposeMapping(unsorted))
    }

    @Test
    fun `sorts a block that ends the file`() {
        val header = COMPOSE_MAPPING_HEADER
        assertEquals(
            "$header\n    b\n    c\n",
            sortComposeMapping("$header\n    c\n    b\n"),
        )
    }

    @Test
    fun `leaves a mapping without a compose block unchanged`() {
        val mapping = "a.B -> c:\n    1:1:void z() -> z\n    1:1:void a() -> a\n"
        assertEquals(mapping, sortComposeMapping(mapping))
    }
}
