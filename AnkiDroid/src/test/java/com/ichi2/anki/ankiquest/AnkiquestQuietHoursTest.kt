// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(application = EmptyApplication::class)
@Category(EmptyApplicationCategory::class)
class AnkiquestQuietHoursTest : RobolectricTest() {
    @Test
    fun `shared quiet-hour boundaries`() {
        val source = assertNotNull(javaClass.classLoader?.getResourceAsStream("ankiquest/quiet_hours.json"))
        val cases = JSONObject(source.bufferedReader().use { it.readText() }).getJSONArray("cases")
        assertTrue(cases.length() > 0)
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val reminders =
                AnkiquestCommunity.Reminders.parse(
                    JSONObject().put("quiet_start", case.getInt("start")).put("quiet_end", case.getInt("end")),
                )
            assertEquals(case.getBoolean("quiet"), reminders.holds(case.getInt("hour")), case.getString("name"))
        }
    }

    @Test
    fun `unknown hour stays quiet`() {
        val enabled = AnkiquestCommunity.Reminders.parse(JSONObject())
        val disabled = enabled.copy(quietEnd = enabled.quietStart)
        for (hour in listOf(-1, 24)) {
            assertTrue(enabled.holds(hour))
            assertFalse(disabled.holds(hour))
        }
    }
}
