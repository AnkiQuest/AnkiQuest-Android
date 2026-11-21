// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.Context
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.ankiquest.AnkiquestMove.OldApp
import com.ichi2.anki.ankiquest.AnkiquestMove.Role
import com.ichi2.anki.ankiquest.AnkiquestMoveImport.Account
import com.ichi2.anki.ankiquest.AnkiquestMoveImport.Next
import com.ichi2.anki.ankiquest.AnkiquestMoveImport.Reason
import com.ichi2.anki.ankiquest.AnkiquestMoveImport.Result
import com.ichi2.anki.ankiquest.AnkiquestMoveImport.State
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
class AnkiquestMoveImportTest : RobolectricTest() {
    private val states = listOf(State.NotNeeded, State.Offered, State.Importing, State.Done(Next.SYNC), State.Failed(Reason.REFUSED))

    @Test
    fun `only the new app without an account and with the old app installed offers`() {
        assertEquals(State.Offered, AnkiquestMoveImport.start(Role.NEW, OldApp.READY, Account.NONE))
        for (role in Role.entries) {
            for (old in OldApp.entries) {
                for (account in Account.entries) {
                    if (role == Role.NEW && old == OldApp.READY && account == Account.NONE) {
                        continue
                    }
                    assertEquals(State.NotNeeded, AnkiquestMoveImport.start(role, old, account), "$role $old $account")
                }
            }
        }
    }

    @Test
    fun `accepting the offer imports`() {
        assertEquals(State.Importing, AnkiquestMoveImport.accept(State.Offered))
        unchangedOutside(State.Offered, AnkiquestMoveImport::accept)
    }

    @Test
    fun `declining the offer leaves the usual setup`() {
        assertEquals(State.NotNeeded, AnkiquestMoveImport.decline(State.Offered))
        unchangedOutside(State.Offered, AnkiquestMoveImport::decline)
    }

    @Test
    fun `an interrupted import is offered again`() {
        assertEquals(State.Offered, AnkiquestMoveImport.interrupt(State.Importing))
        unchangedOutside(State.Importing, AnkiquestMoveImport::interrupt)
    }

    @Test
    fun `an import ends done or failed`() {
        assertEquals(State.Done(Next.EXPORT), AnkiquestMoveImport.finish(State.Importing, Result.Imported(Next.EXPORT)))
        assertEquals(State.Failed(Reason.MALFORMED), AnkiquestMoveImport.finish(State.Importing, Result.Failed(Reason.MALFORMED)))
        unchangedOutside(State.Importing) { AnkiquestMoveImport.finish(it, Result.Imported(Next.SYNC)) }
    }

    @Test
    fun `an AnkiWeb login means the collection follows by sync`() {
        val prefs = target("with-ankiweb")
        val result = read(prefs, payload(mapOf("hkey" to "synthetic-host-key", Ankiquest.PREF_TOKEN to "synthetic-token")))
        assertEquals(Result.Imported(Next.SYNC), result)
        assertEquals("synthetic-token", prefs.getString(Ankiquest.PREF_TOKEN, null))
        assertEquals("synthetic-host-key", prefs.getString("hkey", null))
    }

    @Test
    fun `without an AnkiWeb login the collection follows by export`() {
        val prefs = target("without-ankiweb")
        assertEquals(Result.Imported(Next.EXPORT), read(prefs, payload(mapOf(Ankiquest.PREF_TOKEN to "synthetic-token"))))
    }

    @Test
    fun `a refused or missing old app fails without touching the settings`() {
        val prefs = target("failed")
        val failures =
            mapOf(
                AnkiquestMoveImport.Source { throw SecurityException("refused") } to Reason.REFUSED,
                AnkiquestMoveImport.Source { throw IllegalArgumentException("Unknown authority") } to Reason.UNAVAILABLE,
                AnkiquestMoveImport.Source { null } to Reason.UNAVAILABLE,
                AnkiquestMoveImport.Source { JSONObject().put("version", 2).put("settings", JSONObject()).toString() } to
                    Reason.UNKNOWN_VERSION,
                AnkiquestMoveImport.Source { "{" } to Reason.MALFORMED,
            )
        for ((source, reason) in failures) {
            assertEquals(Result.Failed(reason), AnkiquestMoveImport.read(source, prefs, exclusions()))
        }
        assertEquals(emptyMap(), prefs.all)
    }

    private fun unchangedOutside(
        applies: State,
        transition: (State) -> State,
    ) {
        for (state in states - applies) {
            assertEquals(state, transition(state), state.toString())
        }
    }

    private fun exclusions() = AnkiquestMovePayload.Exclusions.of(targetContext)

    private fun target(name: String) = targetContext.getSharedPreferences("ankiquest-move-$name", Context.MODE_PRIVATE)

    private fun payload(settings: Map<String, Any>) = AnkiquestMovePayload.encode(settings, exclusions())

    private fun read(
        prefs: SharedPreferences,
        text: String,
    ) = AnkiquestMoveImport.read({ text }, prefs, exclusions())
}
