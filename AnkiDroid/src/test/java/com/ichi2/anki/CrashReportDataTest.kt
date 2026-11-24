// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki

import anki.backend.BackendError
import anki.backend.backendError
import com.ichi2.anki.CrashReportData.Companion.isDeckNotFoundInLimitsMapException
import com.ichi2.anki.CrashReportData.HelpAction
import com.ichi2.anki.libanki.Note
import com.ichi2.anki.libanki.sched.Counts
import com.ichi2.testutils.JvmTest
import com.ichi2.testutils.ext.orphanSubdeck
import net.ankiweb.rsdroid.BackendException
import net.ankiweb.rsdroid.exceptions.BackendInvalidInputException
import net.ankiweb.rsdroid.exceptions.BackendNotFoundException
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CrashReportDataTest : JvmTest() {
    /** #15195: 'deck not found in limits map' offers a database check and is not reported */
    @Test
    fun `deck not found in limits map offers a database check`() {
        val exception = deckNotFoundInLimitsMap()

        assertTrue(exception.isDeckNotFoundInLimitsMapException())
        assertEquals(HelpAction.OpenCheckDatabase, CrashReportData(exception, "test", true).helpAction)
        assertFalse(CrashReportData(exception, "test", true).shouldReportException())
    }

    /** The backend counts an orphaned subdeck under its nearest ancestor instead of failing (#15195). */
    @Test
    fun `orphaned subdeck counts under its nearest ancestor`() {
        orphanSubdeck()

        assertEquals(Counts(1, 0, 0), col.sched.counts())
    }

    @Test
    fun `missing notetype offers a database check`() {
        val exception = assertFailsWith<BackendNotFoundException> { Note.fromNotetypeId(col, -1) }

        assertEquals(HelpAction.OpenCheckDatabase, CrashReportData(exception, "test", true).helpAction)
    }

    @Test
    fun `missing notetype is not reported as a crash`() {
        val exception = assertFailsWith<BackendNotFoundException> { Note.fromNotetypeId(col, -1) }

        assertFalse(CrashReportData(exception, "test", true).shouldReportException())
    }

    @Test
    fun `other backend errors are still reported`() {
        assertTrue(CrashReportData(BackendException("unexpected error"), "test", true).shouldReportException())
    }
}

/** The backend's error for a deck missing from the limits tree. */
fun deckNotFoundInLimitsMap() =
    BackendInvalidInputException(
        backendError {
            kind = BackendError.Kind.INVALID_INPUT
            message = "deck not found in limits map"
        },
    )
