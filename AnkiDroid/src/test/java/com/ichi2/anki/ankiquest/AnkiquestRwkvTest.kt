// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import com.ichi2.anki.R
import org.junit.Test
import kotlin.test.assertEquals

class AnkiquestRwkvTest {
    @Test
    fun `every state has its own summary`() {
        val states = listOf(RwkvState.Off, RwkvState.Ready) + RwkvBlocker.entries.map { RwkvState.Unavailable(it) }
        val summaries = states.map(AnkiquestRwkv::summary)
        assertEquals(states.size, summaries.toSet().size)
        assertEquals(R.string.ankiquest_rwkv_no_engine, AnkiquestRwkv.summary(RwkvState.Unavailable(RwkvBlocker.NO_ENGINE)))
    }
}
