// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.ankiquest.AnkiquestMove.OldApp
import com.ichi2.anki.ankiquest.AnkiquestMove.Role
import io.mockk.mockk
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class AnkiquestMoveTest : RobolectricTest() {
    @Test
    fun `the running package decides the role`() {
        assertEquals(Role.OLD, Role.of("com.ichi2.anki.quest"))
        assertEquals(Role.NEW, Role.of("org.ankiquest.ankidroid"))
        for (other in listOf(
            "com.ichi2.anki",
            "com.ichi2.anki.debug",
            "org.ankiquest.ankidroid.debug",
            "org.ankiquest",
            "org.ankiquest.anki",
            "dev.hilll.anki",
            "",
        )) {
            assertEquals(Role.NONE, Role.of(other), other)
        }
    }

    @Test
    @Config(sdk = [27])
    fun `Android 8 uses normal setup without reading the old app`() {
        assertEquals(OldApp.MISSING, AnkiquestMove.oldApp(mockk<Context>()))
    }

    @Test
    fun `the new app only requests the bridge permission`() {
        val manifest = File("src/quest/AndroidManifest.xml").readText()
        val permission = Regex.escape(AnkiquestMove.PERMISSION)
        assertFalse(Regex("""<permission\s+android:name="$permission"""").containsMatchIn(manifest))
        assertTrue(Regex("""<uses-permission android:name="$permission"""").containsMatchIn(manifest))
        assertFalse("AnkiquestMoveProvider" in manifest)
        assertTrue("""<package android:name="${AnkiquestMove.OLD_PACKAGE}" />""" in manifest, "the new app must see the old package")
        assertEquals("${AnkiquestMove.OLD_PACKAGE}.ankiquestmove", AnkiquestMove.AUTHORITY)
    }
}
