// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.SharedPreferences
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URLEncoder

/** A captured identity: queued work must never borrow credentials from changed preferences. */
data class HomeAccount(
    val server: String,
    val user: String,
    val token: String,
    val language: String = AnkiquestLanguage.tag(null),
) {
    val encodedUser: String = URLEncoder.encode(user, "UTF-8").replace("+", "%20")
    val notificationAccount: String = "$server/$encodedUser"
    val scope: String by lazy { sha256(server, user, token) }

    fun url(path: String): HttpUrl = requireNotNull("$server/$path".toHttpUrlOrNull())

    override fun toString(): String = "HomeAccount(server=$server, user=$user)"

    /** The preferences an account is made of, as read together. */
    private data class Stored(
        val server: String?,
        val user: String?,
        val token: String?,
        val fallbackUser: String?,
        val language: String?,
    )

    companion object {
        private const val MAX_READS = 5

        @Volatile
        private var resolved: Pair<Stored, HomeAccount?>? = null

        /**
         * The configured account. Its parts are read until two reads agree, so they never come from
         * different settings, and an unchanged configuration answers the same instance, whose [scope]
         * is then already known.
         */
        fun current(preferences: SharedPreferences = AnkiquestStore.preferences()): HomeAccount? {
            var stored = read(preferences)
            repeat(MAX_READS) {
                val again = read(preferences)
                if (again == stored) return resolve(stored)
                stored = again
            }
            return null
        }

        private fun read(preferences: SharedPreferences) =
            Stored(
                preferences.text(AnkiquestPreferences.URL),
                preferences.text(AnkiquestPreferences.USER),
                preferences.text(AnkiquestPreferences.TOKEN),
                preferences.text("username"),
                preferences.text("language"),
            )

        private fun SharedPreferences.text(key: String): String? =
            try {
                getString(key, null)
            } catch (_: ClassCastException) {
                null
            }

        private fun resolve(stored: Stored): HomeAccount? {
            resolved?.let { (key, account) ->
                if (key == stored && account?.language == AnkiquestLanguage.tag(stored.language)) return account
            }
            return from(stored.server, stored.user, stored.token, stored.fallbackUser.orEmpty(), stored.language).also {
                resolved = stored to it
            }
        }

        /** A credential change invalidates private presentation state, including a same-user token change. */
        fun fingerprint(settings: Map<String, *> = AnkiquestStore.preferences().all): String {
            val player =
                (settings[AnkiquestPreferences.USER] as? String).orEmpty().trim().ifEmpty {
                    (settings["username"] as? String).orEmpty().trim()
                }
            return sha256(
                settings[AnkiquestPreferences.URL].toString(),
                settings[AnkiquestPreferences.USER].toString(),
                player,
                settings[AnkiquestPreferences.TOKEN].toString(),
            )
        }

        fun from(
            settings: Map<String, *>,
            fallbackUser: String,
        ): HomeAccount? =
            from(
                settings[AnkiquestPreferences.URL] as? String,
                settings[AnkiquestPreferences.USER] as? String,
                settings[AnkiquestPreferences.TOKEN] as? String,
                fallbackUser,
                settings["language"] as? String,
            )

        private fun from(
            url: String?,
            player: String?,
            credential: String?,
            fallbackUser: String,
            language: String?,
        ): HomeAccount? {
            val server = url.orEmpty().trim().trimEnd('/')
            val user = player.orEmpty().trim().ifEmpty { fallbackUser.trim() }
            val token = credential.orEmpty().trim()
            val parsed = server.toHttpUrlOrNull() ?: return null
            if (user.isEmpty() || parsed.username.isNotEmpty() || parsed.password.isNotEmpty() || parsed.query != null ||
                parsed.fragment != null || !AnkiquestHosts.secure(parsed)
            ) {
                return null
            }
            return HomeAccount(server, user, token, AnkiquestLanguage.tag(language))
        }
    }
}
