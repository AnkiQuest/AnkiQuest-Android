// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.ichi2.anki.IntroductionActivity
import com.ichi2.anki.R
import com.ichi2.anki.common.storage.CollectionHelper
import com.ichi2.anki.deckpicker.DeckPickerViewModel
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * What the old app hands to the new one: its default preferences, which hold every AnkiQuest
 * setting with the server, user and token ([AnkiquestPreferences]), AnkiDroid's own settings and
 * the AnkiWeb login (`username`, `hkey`, `currentSyncUri`). Keys that describe this install rather
 * than the player ([Exclusions]) stay behind.
 *
 * The payload carries the token and the AnkiWeb host key: it only travels through the provider's
 * binder call, is never written to a file, logged, or put into an exception message.
 */
internal object AnkiquestMovePayload {
    /** The payload version. A reader refuses any other, so a later format cannot be misread. */
    const val VERSION = 1

    private const val FIELD_VERSION = "version"
    private const val FIELD_SETTINGS = "settings"
    private const val FIELD_TYPE = "type"
    private const val FIELD_VALUE = "value"

    /** The SharedPreferences value types, as written in the payload. */
    private enum class Type(
        val tag: String,
    ) {
        STRING("string"),
        INT("int"),
        LONG("long"),
        FLOAT("float"),
        BOOLEAN("boolean"),
        STRING_SET("string_set"),
        ;

        companion object {
            fun of(value: Any?): Type? =
                when (value) {
                    is String -> STRING
                    is Int -> INT
                    is Long -> LONG
                    is Float -> FLOAT
                    is Boolean -> BOOLEAN
                    is Set<*> -> if (value.all { it is String }) STRING_SET else null
                    else -> null
                }

            fun named(tag: String): Type? = entries.firstOrNull { it.tag == tag }
        }
    }

    /** A payload as read: the settings, or why it cannot be used. */
    sealed interface Decoded {
        data class Read(
            val settings: Map<String, Any>,
        ) : Decoded

        data class UnknownVersion(
            val version: Int,
        ) : Decoded

        data object Malformed : Decoded
    }

    /**
     * Keys that stay with the install: the collection path into the old app's storage, version
     * and first-launch markers, permission prompts already shown, the last sync time of a
     * collection the new app does not have yet, review reminder ids (the reminders and their
     * alarms do not move), and widget settings keyed by widget ids of the old app.
     */
    class Exclusions(
        private val keys: Set<String>,
    ) {
        fun movable(
            key: String,
            value: Any?,
        ): Boolean {
            if (key in keys || EXCLUDED_PREFIXES.any { key.startsWith(it) }) {
                return false
            }
            // Paths and URIs point into the old app's storage or at grants only it holds.
            if (value is String && LOCAL_VALUE.containsMatchIn(value)) {
                return false
            }
            return Type.of(value) != null
        }

        companion object {
            private val EXCLUDED_PREFIXES = listOf("ankiquestWidget")
            private val LOCAL_VALUE = Regex("^(/|file:|content:)")

            /** "lastVersion" is a literal in [com.ichi2.anki.InitialActivity] too. */
            private const val LAST_VERSION = "lastVersion"

            private val RESOURCE_KEYS =
                listOf(
                    R.string.last_sync_time_key,
                    R.string.sync_notifs_request_shown_key,
                    R.string.reminder_notifs_request_shown_key,
                    R.string.notifications_bottom_sheet_below_33,
                    R.string.notifications_permission_requested_key,
                    R.string.record_audio_permission_requested_key,
                    R.string.review_reminders_next_free_id,
                    R.string.review_reminder_deserialization_errors_key,
                )

            fun of(context: Context): Exclusions =
                Exclusions(
                    setOf(
                        CollectionHelper.PREF_COLLECTION_PATH,
                        LAST_VERSION,
                        DeckPickerViewModel.PREF_LAST_UPGRADE_VERSION,
                        IntroductionActivity.PREF_INTRODUCTION_SLIDES_SHOWN,
                    ) + RESOURCE_KEYS.map(context::getString),
                )
        }
    }

    /** [settings] without the excluded keys, as payload text. */
    fun encode(
        settings: Map<String, *>,
        exclusions: Exclusions,
    ): String {
        val encoded = JSONObject()
        for ((key, value) in settings) {
            if (!exclusions.movable(key, value)) {
                continue
            }
            val type = Type.of(value) ?: continue
            encoded.put(key, JSONObject().put(FIELD_TYPE, type.tag).put(FIELD_VALUE, written(type, value)))
        }
        return JSONObject().put(FIELD_VERSION, VERSION).put(FIELD_SETTINGS, encoded).toString()
    }

    private fun written(
        type: Type,
        value: Any?,
    ): Any =
        when (type) {
            // A float as text, so it reads back exactly and NaN is allowed.
            Type.FLOAT -> value.toString()
            Type.STRING_SET -> JSONArray((value as Set<*>).toList())
            else -> requireNotNull(value)
        }

    /**
     * Reads payload text. Nothing of [text] reaches an exception or a log: org.json quotes its
     * input in its messages, so its exceptions end here.
     */
    fun decode(text: String): Decoded {
        val root =
            try {
                JSONObject(text)
            } catch (e: JSONException) {
                return Decoded.Malformed
            }
        if (!root.has(FIELD_VERSION)) {
            return Decoded.Malformed
        }
        val version = root.optInt(FIELD_VERSION, -1)
        if (version != VERSION) {
            return Decoded.UnknownVersion(version)
        }
        val encoded = root.optJSONObject(FIELD_SETTINGS) ?: return Decoded.Malformed

        // Every entry must be well formed; a payload with one bad entry is not applied at all.
        val settings = mutableMapOf<String, Any>()
        for (key in encoded.keys()) {
            val entry = encoded.optJSONObject(key) ?: return Decoded.Malformed
            settings[key] = read(entry) ?: return Decoded.Malformed
        }
        return Decoded.Read(settings)
    }

    private fun read(entry: JSONObject): Any? {
        val type = Type.named(entry.optString(FIELD_TYPE)) ?: return null
        val value = entry.opt(FIELD_VALUE) ?: return null
        return when (type) {
            Type.STRING -> value as? String
            Type.INT -> whole(value)?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
            Type.LONG -> whole(value)
            Type.FLOAT -> (value as? String)?.toFloatOrNull()
            Type.BOOLEAN -> value as? Boolean
            Type.STRING_SET -> strings(value as? JSONArray)
        }
    }

    /** A whole number as org.json reads it: Int when it fits, else Long. Doubles are refused. */
    private fun whole(value: Any): Long? =
        when (value) {
            is Int -> value.toLong()
            is Long -> value
            else -> null
        }

    private fun strings(array: JSONArray?): Set<String>? {
        if (array == null) {
            return null
        }
        val strings = (0 until array.length()).map { array.opt(it) as? String ?: return null }
        return strings.toSet()
    }

    /**
     * Writes [settings] into [prefs], filtered again: the new app does not trust the old one to
     * have left out its install's keys. Keys the payload does not name are kept.
     */
    fun apply(
        prefs: SharedPreferences,
        settings: Map<String, Any>,
        exclusions: Exclusions,
    ) {
        prefs.edit(commit = true) {
            for ((key, value) in settings) {
                if (!exclusions.movable(key, value)) {
                    continue
                }
                put(key, value)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun SharedPreferences.Editor.put(
        key: String,
        value: Any,
    ) {
        when (value) {
            is String -> putString(key, value)
            is Int -> putInt(key, value)
            is Long -> putLong(key, value)
            is Float -> putFloat(key, value)
            is Boolean -> putBoolean(key, value)
            is Set<*> -> putStringSet(key, value as Set<String>)
        }
    }
}
