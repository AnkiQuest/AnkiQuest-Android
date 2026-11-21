// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

/** The module's own resources word core's leaderboard periods, in the order the tabs show them. */
@RunWith(AndroidJUnit4::class)
class AnkiquestBoardsTextTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `every period has its tab title`() {
        assertEquals(
            listOf("Hour", "Today", "Week", "Month", "Year", "Overall"),
            AnkiquestBoards.Period.entries.map { context.getString(it.label) },
        )
    }
}
