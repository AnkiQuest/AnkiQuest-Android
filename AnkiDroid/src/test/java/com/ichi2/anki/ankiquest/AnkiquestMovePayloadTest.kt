// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import android.annotation.SuppressLint
import android.content.Context
import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.ankiquest.AnkiquestMovePayload.Decoded
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertIs

@RunWith(AndroidJUnit4::class)
class AnkiquestMovePayloadTest : RobolectricTest() {
    private val exclusions get() = AnkiquestMovePayload.Exclusions.of(targetContext)

    private val settings: Map<String, Any> =
        mapOf(
            Ankiquest.PREF_URL to "https://ankiquest.example",
            Ankiquest.PREF_USER to "cerro",
            Ankiquest.PREF_TOKEN to "synthetic-token",
            "ankiquestUploadedThrough:scope" to 1_750_000_000_123L,
            "ankiquestGoalMinutes" to 20,
            "ankiquestMoodIcon" to true,
            "username" to "synthetic@example.com",
            "hkey" to "synthetic-host-key",
            "currentSyncUri" to "https://sync.example/",
            "theme" to "2",
            "cardZoom" to 0.1f,
            "browser_columns" to setOf("question", "answer"),
            "count" to Int.MIN_VALUE,
            "big" to Long.MAX_VALUE,
        )

    @Test
    fun `settings survive a round trip with their types`() {
        val decoded = AnkiquestMovePayload.decode(AnkiquestMovePayload.encode(settings, exclusions))
        assertEquals(Decoded.Read(settings), decoded)
    }

    @Test
    // The old app's own paths are the data under test.
    @SuppressLint("SdCardPath")
    fun `install-specific settings stay behind`() {
        val local =
            mapOf(
                "deckPath" to "/storage/emulated/0/Android/data/com.ichi2.anki.quest/files/AnkiDroid",
                "lastVersion" to "2.24quest",
                "lastUpgradeVersion" to 22400L,
                "IntroductionSlidesShown" to true,
                "lastSyncTime" to 1_750_000_000_000L,
                "notificationsPermissionRequested" to true,
                "recordAudioPermissionRequested" to true,
                "reviewRemindersNextFreeId" to 3,
                "ankiquestWidgetSettings:12" to "{}",
                "deckPickerBackground" to "content://com.ichi2.anki.quest.provider/background.png",
                "exportDirectory" to "/data/user/0/com.ichi2.anki.quest/files",
                "unsupported" to 1.5,
            )
        val decoded = AnkiquestMovePayload.decode(AnkiquestMovePayload.encode(settings + local, exclusions))
        assertEquals(Decoded.Read(settings), decoded)
    }

    @Test
    // The new app's private path is fixture data, independent of the test host.
    @SuppressLint("SdCardPath")
    fun `applying fills the new app and keeps its own install settings`() {
        val prefs = targetContext.getSharedPreferences("ankiquest-move-target", Context.MODE_PRIVATE)
        prefs.edit {
            putString("deckPath", "/data/user/0/org.ankiquest/files/AnkiDroid")
            putString("lastVersion", "2.25")
            putString("theme", "0")
        }
        val payload = settings + ("deckPath" to "/storage/old") + ("lastVersion" to "2.24")

        AnkiquestMovePayload.apply(prefs, payload, exclusions)

        assertEquals("/data/user/0/org.ankiquest/files/AnkiDroid", prefs.getString("deckPath", null))
        assertEquals("2.25", prefs.getString("lastVersion", null))
        assertEquals(settings, prefs.all - setOf("deckPath", "lastVersion"))
    }

    @Test
    fun `an unknown payload version is refused`() {
        for (version in listOf(0, 2, 99)) {
            val text = JSONObject().put("version", version).put("settings", JSONObject()).toString()
            assertEquals(Decoded.UnknownVersion(version), AnkiquestMovePayload.decode(text))
        }
    }

    @Test
    fun `a malformed payload is refused as a whole`() {
        val good = JSONObject(AnkiquestMovePayload.encode(settings, exclusions))
        val badEntry = JSONObject(good.toString())
        badEntry.getJSONObject("settings").put("theme", JSONObject().put("type", "double").put("value", 1.5))
        val wrongType = JSONObject(good.toString())
        wrongType.getJSONObject("settings").put("ankiquestGoalMinutes", JSONObject().put("type", "int").put("value", "20"))
        val texts =
            listOf(
                "",
                "not json synthetic-token",
                JSONObject().put("settings", good.getJSONObject("settings")).toString(),
                JSONObject().put("version", 1).toString(),
                badEntry.toString(),
                wrongType.toString(),
            )
        for (text in texts) {
            assertIs<Decoded.Malformed>(AnkiquestMovePayload.decode(text), text)
        }
    }
}
