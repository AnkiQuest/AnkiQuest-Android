// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.libanki.backend.BackendUtils
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class AnkiquestRwkvPresetsTest : RobolectricTest() {
    private val defaultPreset = 1L

    @Test
    fun `each mode is one pair of the backend's two fields`() {
        assertEquals(RwkvPresetMode.entries.size, RwkvPresetMode.entries.map { it.curve to it.instant }.toSet().size)
        for (mode in RwkvPresetMode.entries) {
            assertEquals(mode, RwkvPresetMode.of(mode.curve, mode.instant))
        }
        assertFalse(RwkvPresetMode.OFF.curve || RwkvPresetMode.OFF.instant)
    }

    @Test
    fun `a preset without RWKV fields is off`() {
        assertEquals(RwkvPresetMode.OFF, RwkvPresetJson.mode(JSONObject()))
        // The backend leaves false fields out.
        val instant = JSONObject().put(RwkvPresetJson.FORK_KEY, JSONObject().put(RwkvPresetJson.INSTANT_KEY, true))
        assertEquals(RwkvPresetMode.INSTANT, RwkvPresetJson.mode(instant))
    }

    @Test
    fun `setting a mode keeps the preset's other RWKV fields`() {
        val fields = JSONObject().put("rwkv_review_batch_size", 50).put(RwkvPresetJson.CURVE_KEY, true)
        val config = JSONObject().put(RwkvPresetJson.FORK_KEY, fields)

        RwkvPresetJson.setMode(config, RwkvPresetMode.INSTANT)

        assertEquals(RwkvPresetMode.INSTANT, RwkvPresetJson.mode(config))
        assertEquals(50, config.getJSONObject(RwkvPresetJson.FORK_KEY).getInt("rwkv_review_batch_size"))
    }

    @Test
    fun `presets start off`() =
        runTest {
            assertEquals(listOf(RwkvPresetMode.OFF), AnkiquestRwkvPresets.all().map { it.mode })
        }

    @Test
    fun `a saved mode reaches the backend's fields and reads back`() =
        runTest {
            for (mode in listOf(RwkvPresetMode.INSTANT, RwkvPresetMode.INSTANT_AND_CURVE, RwkvPresetMode.CURVE, RwkvPresetMode.OFF)) {
                AnkiquestRwkvPresets.save(defaultPreset, mode)
                assertEquals(mode, AnkiquestRwkvPresets.all().single { it.id == defaultPreset }.mode, "$mode")
            }
        }

    @Test
    fun `the backend stores the mode where the desktop fork reads it`() =
        runTest {
            AnkiquestRwkvPresets.save(defaultPreset, RwkvPresetMode.INSTANT)
            val stored = BackendUtils.fromJsonBytes(col.backend.getDeckConfigLegacy(defaultPreset))
            val fields = stored.getJSONObject(RwkvPresetJson.FORK_KEY)
            assertTrue(fields.getBoolean(RwkvPresetJson.INSTANT_KEY))
            assertFalse(fields.optBoolean(RwkvPresetJson.CURVE_KEY))
        }
}
