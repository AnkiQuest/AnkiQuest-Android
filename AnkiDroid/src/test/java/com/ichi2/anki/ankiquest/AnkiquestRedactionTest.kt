// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import org.junit.Test
import kotlin.test.assertEquals

/** Crash report text loses server hosts and credentials, and keeps everything else (#216). */
class AnkiquestRedactionTest {
    private val redaction = AnkiquestRedaction(listOf("Quest.Example.net"), listOf(STORED_TOKEN))

    @Test
    fun `a host is replaced wherever it stands alone`() {
        assertEquals("https://[server]/api/reviews", redaction.text("https://quest.example.net/api/reviews"))
        assertEquals("resolve host \"[server]\".", redaction.text("resolve host \"QUEST.example.net\"."))
    }

    @Test
    fun `other hosts that contain it stay`() {
        val other = "sub.quest.example.net quest.example.network myquest.example.net"

        assertEquals(other, redaction.text(other))
    }

    @Test
    fun `the address the host resolved to goes with it`() {
        assertEquals("connect to [server]:443", redaction.text("connect to quest.example.net/203.0.113.7:443"))
        assertEquals("connect to [server]", redaction.text("connect to quest.example.net/2001:db8::7"))
        assertEquals("[server]/api", redaction.text("quest.example.net/api"))
    }

    /** The `preference_key` cases of AnkiQuest-Server#13's `crash_redaction.json`, until the snapshot has them (#360). */
    @Test
    fun `a host in a preference name is replaced`() {
        val account = "ankiquestUploadedThrough:https://quest.example.net/hill"
        assertEquals("ankiquestUploadedThrough:https://[server]/hill", redaction.text(account))
        assertEquals("ankiquestCapabilities:http://[server]:8080", redaction.text("ankiquestCapabilities:http://quest.example.net:8080"))
        assertEquals("lastSync:[removed]", redaction.text("lastSync:0123456789abcdef0123456789abcdef"))

        val longer = "ankiquestUploadedThrough:https://quest.example.network/hill"
        assertEquals(longer, redaction.text(longer))
    }

    @Test
    fun `credentials are replaced as the server replaces them`() {
        val hex = "0123456789abcdef".repeat(4)

        assertEquals("Authorization: Bearer [removed]", redaction.text("Authorization: Bearer short-one"))
        assertEquals("token [removed] ok", redaction.text("token $hex ok"))
        assertEquals("stored [removed]", redaction.text("stored $STORED_TOKEN"))

        // Long words without digits, and digits after a line break, are not credentials.
        val plain = "AnkiquestLeaderboardScopesFragmentActivity Bearer\n12"
        assertEquals(plain, redaction.text(plain))
    }

    @Test
    fun `a trace keeps its frames`() {
        val frame = "\tat com.ichi2.anki.ankiquest.AnkiquestSettingsFragmentVeryLong2Name.onCreate(A.kt:1)"
        val trace = "java.io.IOException: Bearer abc\n$frame\nCaused by: x: quest.example.net\n"

        assertEquals("java.io.IOException: Bearer [removed]\n$frame\nCaused by: x: [server]\n", redaction.trace(trace))
    }

    companion object {
        /** A stored token too short for the token rule, removed because the device holds it. */
        private const val STORED_TOKEN = "old-device-key"
    }
}
