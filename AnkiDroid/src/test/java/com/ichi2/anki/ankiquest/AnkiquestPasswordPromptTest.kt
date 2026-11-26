// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.ankiquest.AnkiquestAccountSettings.Methods
import com.ichi2.anki.ankiquest.AnkiquestAccountSettings.SignIn
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Home card asking players who sign in only with a token to set a password. */
@RunWith(AndroidJUnit4::class)
class AnkiquestPasswordPromptTest : RobolectricTest() {
    private fun methods(signIn: String?): Methods {
        val json = JSONObject("""{"account": false, "password": false, "providers": [], "linked_unavailable": false}""")
        if (signIn != null) json.put("sign_in", signIn)
        return Methods.parse(json)
    }

    @Test
    fun `sign_in is read, and is null from servers without it or with a new value`() {
        assertEquals(SignIn.TOKEN_ONLY, methods("token_only").signIn)
        assertEquals(SignIn.PASSWORD, methods("password").signIn)
        assertEquals(SignIn.PROVIDER, methods("provider").signIn)
        assertNull(methods(null).signIn)
        assertNull(methods("passkey").signIn)
    }

    @Test
    fun pinnedSignInExamples() {
        fun example(name: String): Methods =
            Methods.parse(
                JSONObject(
                    checkNotNull(javaClass.classLoader?.getResource("ankiquest-api/$name.json")).readText(Charsets.UTF_8),
                ),
            )

        val now = 1_800_000_000_000L
        val before = example("sign_in_methods_token_only")
        assertEquals(SignIn.TOKEN_ONLY, before.signIn)
        assertFalse(before.account)
        assertFalse(before.password)
        assertTrue(AnkiquestPasswordPrompt.offered(before, snoozedAt = null, now = now))

        val after = example("password_set_token_only")
        assertEquals(SignIn.PASSWORD, after.signIn)
        assertTrue(after.account)
        assertTrue(after.password)
        assertFalse(AnkiquestPasswordPrompt.offered(after, snoozedAt = null, now = now))
    }

    @Test
    fun `the card is offered to token-only players, hidden after Not now and offered again three days later`() {
        val now = 1_800_000_000_000L
        val snooze = AnkiquestPasswordPrompt.SNOOZE_MS
        assertTrue(AnkiquestPasswordPrompt.offered(methods("token_only"), snoozedAt = null, now = now))
        assertFalse(AnkiquestPasswordPrompt.offered(methods("password"), snoozedAt = null, now = now))
        assertFalse(AnkiquestPasswordPrompt.offered(methods("provider"), snoozedAt = null, now = now))
        assertFalse(AnkiquestPasswordPrompt.offered(methods(null), snoozedAt = null, now = now), "older servers")
        assertFalse(AnkiquestPasswordPrompt.offered(methods("token_only"), snoozedAt = now - snooze + 1, now = now))
        assertTrue(AnkiquestPasswordPrompt.offered(methods("token_only"), snoozedAt = now - snooze, now = now))
        assertEquals(3 * 24 * 60 * 60 * 1000L, snooze)
    }

    @Test
    fun `Not now is remembered for one account on this device`() {
        val prefs = AnkiDroidApp.sharedPrefs()
        val ana = HomeAccount("https://anki.example.test", "ana", "ana-token")
        val bo = HomeAccount("https://anki.example.test", "bo", "bo-token")
        assertNull(AnkiquestPasswordPrompt.snoozedAt(prefs, ana))
        AnkiquestPasswordPrompt.snooze(prefs, ana, 1234L)
        assertEquals(1234L, AnkiquestPasswordPrompt.snoozedAt(prefs, ana))
        assertNull(AnkiquestPasswordPrompt.snoozedAt(prefs, bo))
    }

    @Test
    fun `passwords are counted in characters, as the server does`() {
        // One character, two UTF-16 units: the server's chars().count() and the add-on's len() count one.
        val emoji = "\uD83D\uDE00"
        val length = R.string.ankiquest_password_length
        assertEquals(length, AnkiquestPasswordPrompt.problem(emoji.repeat(9), null))
        assertNull(AnkiquestPasswordPrompt.problem(emoji.repeat(10), null))
        assertNull(AnkiquestPasswordPrompt.problem(emoji.repeat(256), null))
        assertEquals(length, AnkiquestPasswordPrompt.problem(emoji.repeat(257), null))
    }

    @Test
    fun `a second entry must match the first`() {
        val mismatch = R.string.ankiquest_password_mismatch
        assertEquals(mismatch, AnkiquestPasswordPrompt.problem("a new long password", "a different password"))
        assertNull(AnkiquestPasswordPrompt.problem("a new long password", "a new long password"))
    }
}
