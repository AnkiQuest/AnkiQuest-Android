// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.libanki.backend.BackendUtils
import org.json.JSONObject

/**
 * Which RWKV scheduler a deck preset uses, as the desktop fork's Deck Options set it. Saved in the
 * preset, so it syncs: the JSchoreels desktop fork and kuma3 read the same fields.
 */
internal enum class RwkvPresetMode(
    /** RWKV-Curve: answer intervals from the predicted curve. */
    val curve: Boolean,
    /** RWKV-Instant: review cards picked by predicted recall; due dates still come from FSRS or Curve. */
    val instant: Boolean,
) {
    OFF(curve = false, instant = false),
    INSTANT(curve = false, instant = true),
    CURVE(curve = true, instant = false),
    INSTANT_AND_CURVE(curve = true, instant = true),
    ;

    companion object {
        fun of(
            curve: Boolean,
            instant: Boolean,
        ): RwkvPresetMode = entries.first { it.curve == curve && it.instant == instant }
    }
}

/** A deck preset and its RWKV mode. */
internal data class RwkvPreset(
    val id: Long,
    val name: String,
    val mode: RwkvPresetMode,
)

/**
 * The RWKV fields of a preset in its legacy JSON: the object the backend keeps under
 * [FORK_KEY] (AnkiQuest-Anki `rslib/src/deckconfig/fork_fields.rs`). The backend leaves a false
 * field out, so a missing one is false.
 */
internal object RwkvPresetJson {
    const val FORK_KEY = "jschoreels.rwkv"
    const val CURVE_KEY = "rwkv_review_enabled"
    const val INSTANT_KEY = "rwkv_review_instant_order_enabled"

    fun mode(config: JSONObject): RwkvPresetMode {
        val fields = config.optJSONObject(FORK_KEY) ?: return RwkvPresetMode.OFF
        return RwkvPresetMode.of(fields.optBoolean(CURVE_KEY), fields.optBoolean(INSTANT_KEY))
    }

    /** Sets [mode] in [config], keeping the preset's other RWKV fields. */
    fun setMode(
        config: JSONObject,
        mode: RwkvPresetMode,
    ) {
        val fields = config.optJSONObject(FORK_KEY) ?: JSONObject().also { config.put(FORK_KEY, it) }
        fields.put(CURVE_KEY, mode.curve)
        fields.put(INSTANT_KEY, mode.instant)
    }
}

/** Reads and saves presets' RWKV modes through the backend's legacy deck config calls. */
internal object AnkiquestRwkvPresets {
    private const val ID_KEY = "id"
    private const val NAME_KEY = "name"

    suspend fun all(): List<RwkvPreset> =
        withCol {
            val configs = BackendUtils.jsonToArray(backend.allDeckConfigLegacy())
            (0 until configs.length()).map { index ->
                val config = configs.getJSONObject(index)
                RwkvPreset(config.getLong(ID_KEY), config.getString(NAME_KEY), RwkvPresetJson.mode(config))
            }
        }

    suspend fun save(
        id: Long,
        mode: RwkvPresetMode,
    ) {
        withCol {
            val config = BackendUtils.fromJsonBytes(backend.getDeckConfigLegacy(id))
            RwkvPresetJson.setMode(config, mode)
            backend.addOrUpdateDeckConfigLegacy(json = BackendUtils.toJsonBytes(config))
        }
    }
}
