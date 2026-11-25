// SPDX-License-Identifier: AGPL-3.0-or-later

package org.ankiquest

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.EmptyApplicationCategory
import com.ichi2.anki.RobolectricTest
import com.ichi2.testutils.EmptyApplication
import org.json.JSONObject
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(AndroidJUnit4::class)
@Config(application = EmptyApplication::class, sdk = [32])
@Category(EmptyApplicationCategory::class)
class AnkiquestLockTest : RobolectricTest() {
    @Test
    fun `only upload_first asks the player to study and sync`() {
        assertEquals(AnkiquestLock.UPLOAD_FIRST, AnkiquestLock.of("upload_first"))

        // A visitor's lock and unknown ones are not this app's to explain; no header is no lock.
        assertNull(AnkiquestLock.of("sign_in"))
        assertNull(AnkiquestLock.of("something_new"))
        assertNull(AnkiquestLock.of(null as String?))
    }

    @Test
    fun `activity and friend goals name the lock in their body`() {
        val locked = JSONObject().put("locked", "upload_first")
        assertEquals(AnkiquestLock.UPLOAD_FIRST, AnkiquestFeed.parsePage(locked).lock)
        assertEquals(AnkiquestLock.UPLOAD_FIRST, AnkiquestChallenges.parse(locked).lock)

        // Older servers and unlocked players send no `locked`.
        assertNull(AnkiquestFeed.parsePage(JSONObject()).lock)
        assertNull(AnkiquestChallenges.parse(JSONObject()).lock)
    }
}
