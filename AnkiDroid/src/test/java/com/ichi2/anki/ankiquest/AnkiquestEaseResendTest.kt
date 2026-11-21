// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import com.ichi2.anki.ankiquest.AnkiquestEaseResend.Check
import com.ichi2.anki.ankiquest.AnkiquestEaseResend.State
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals

/** The transitions of the one resend of the history with the button, as in the add-on's `test_addon_ease_resend.py`. */
class AnkiquestEaseResendTest {
    @Test
    fun `an account without a stored state owes the resend`() {
        assertEquals(State.PENDING, AnkiquestEaseResend.of(null))
        assertEquals(State.PENDING, AnkiquestEaseResend.of("unknown"))
    }

    @Test
    fun `an interrupted resend is owed again`() {
        assertEquals(State.PENDING, AnkiquestEaseResend.of(State.SENDING.stored))
    }

    @Test
    fun `stored states round trip`() {
        for (state in listOf(State.NOT_NEEDED, State.PENDING, State.DONE)) {
            assertEquals(state, AnkiquestEaseResend.of(state.stored))
        }
    }

    @Test
    fun `start moves only an owed resend to sending`() {
        assertEquals(State.SENDING, AnkiquestEaseResend.start(State.PENDING))
        for (state in listOf(State.NOT_NEEDED, State.SENDING, State.DONE)) {
            assertEquals(state, AnkiquestEaseResend.start(state))
        }
    }

    @Test
    fun `finish records what the upload found`() {
        assertEquals(State.DONE, AnkiquestEaseResend.finish(State.SENDING, Check.RESENT))
        assertEquals(State.NOT_NEEDED, AnkiquestEaseResend.finish(State.SENDING, Check.COVERED))
        assertEquals(State.PENDING, AnkiquestEaseResend.finish(State.SENDING, Check.UNSUPPORTED))
        assertEquals(State.PENDING, AnkiquestEaseResend.finish(State.SENDING, Check.FAILED))
    }

    @Test
    fun `finish leaves other states alone`() {
        for (state in listOf(State.NOT_NEEDED, State.PENDING, State.DONE)) {
            for (check in Check.entries) {
                assertEquals(state, AnkiquestEaseResend.finish(state, check))
            }
        }
    }

    @Test
    fun `a failed upload owes the resend again`() {
        assertEquals(State.PENDING, AnkiquestEaseResend.fail(State.SENDING))
        for (state in listOf(State.NOT_NEEDED, State.PENDING, State.DONE)) {
            assertEquals(state, AnkiquestEaseResend.fail(state))
        }
    }

    @Test
    fun `the history goes in batches within the server limit`() =
        runBlocking {
            val rows = (1L..11L).toList()
            val sent = mutableListOf<List<Long>>()

            val count =
                AnkiquestEaseResend.sendHistory(
                    limit = 5,
                    read = { after -> rows.filter { it > after }.take(5) },
                    id = { it },
                ) { sent.add(it) }

            assertEquals(11, count)
            assertEquals(listOf(5, 5, 1), sent.map { it.size })
            assertEquals(rows, sent.flatten())
        }

    @Test
    fun `an empty history sends nothing`() =
        runBlocking {
            val sent = mutableListOf<List<Long>>()
            assertEquals(0, AnkiquestEaseResend.sendHistory(5, { emptyList<Long>() }, { it }) { sent.add(it) })
            assertEquals(emptyList(), sent)
        }
}
