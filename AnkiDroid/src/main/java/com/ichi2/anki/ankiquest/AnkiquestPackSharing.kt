// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.io.IOException

/** The server's pack API for the signed-in account, and the cache of friends' packs. */
internal object AnkiquestPackSharing {
    /** The capability of a server that keeps packs. */
    const val FEATURE = "companion_packs"

    /** Where friends' packs are cached, inside the app's cache directory. */
    private const val CACHE = "ankiquest/friend-packs"

    private val zipType = "application/zip".toMediaType()

    @Volatile
    private var shown: Map<String, SharedPack> = emptyMap()

    fun cache(context: Context) = FriendPackCache(File(context.cacheDir, CACHE))

    /** The player's uploaded packs. Blocking. */
    fun mine(account: HomeAccount): List<SharedPack> =
        SharedPack.mine(AnkiquestApi.json(account, AnkiquestApi.request(account, "api/packs/${account.user}").build()))

    /** Uploads [pack] and makes it [visibility]. Blocking. */
    fun upload(
        account: HomeAccount,
        pack: InstalledPack,
        visibility: PackVisibility,
    ): SharedPack {
        val body = zipOf(pack).toRequestBody(zipType)
        val request = AnkiquestApi.request(account, "api/packs/${account.user}").post(body).build()
        val uploaded =
            SharedPack.parse(AnkiquestApi.json(account, request, AnkiquestApi.Timeout.TRANSFER))
                ?: throw IOException("unreadable upload answer")
        return if (uploaded.visibility == visibility) uploaded else change(account, uploaded.sha256, visibility)
    }

    /** Makes the player's pack [sha256] [visibility]. Blocking. */
    fun change(
        account: HomeAccount,
        sha256: String,
        visibility: PackVisibility,
    ): SharedPack {
        val body = AnkiquestApi.body(JSONObject().put("visibility", visibility.wire))
        val request = AnkiquestApi.request(account, "api/packs/${account.user}/$sha256").put(body).build()
        return SharedPack.parse(AnkiquestApi.json(account, request)) ?: throw IOException("unreadable answer")
    }

    /** Reports a friend's pack, which hides it for this player. Blocking. */
    fun report(
        account: HomeAccount,
        sha256: String,
        reason: PackReport,
    ) {
        val body = AnkiquestApi.body(JSONObject().put("reason", reason.wire))
        AnkiquestApi.json(account, AnkiquestApi.request(account, "api/packs/${account.user}/reports/$sha256").post(body).build())
        shown = shown.filterValues { it.sha256 != sha256 }
    }

    /**
     * Reads which pack each friend shows and fetches the ones not cached; drops cached packs no
     * friend shows any more. A pack that cannot be fetched or read leaves that friend without one.
     * Blocking.
     */
    fun refresh(
        context: Context,
        account: HomeAccount,
    ) {
        if (!AnkiquestCapabilities.has(account, FEATURE)) {
            shown = emptyMap()
            return
        }
        val friends =
            SharedPack.friends(
                AnkiquestApi.json(account, AnkiquestApi.request(account, "api/packs/${account.user}/friends").build()),
            )
        val cache = cache(context)
        cache.retain(friends.values.map { it.sha256 }.toSet())
        val names = AnkiquestPacks.library(context).names()
        val readable =
            friends.filterValues { pack ->
                if (pack.sha256 in cache.hashes()) {
                    return@filterValues true
                }
                try {
                    cache.put(pack.sha256, fetch(account, pack.sha256), names)
                    true
                } catch (e: PackRefused) {
                    Timber.w(e, "AnkiQuest friend pack refused")
                    false
                } catch (e: IOException) {
                    Timber.w(e, "AnkiQuest friend pack not fetched")
                    false
                }
            }
        shown = readable
    }

    private fun fetch(
        account: HomeAccount,
        sha256: String,
    ): ByteArray {
        val request = AnkiquestApi.request(account, "api/packs/${account.user}/file/$sha256").build()
        return AnkiquestApi.call(account, request, AnkiquestApi.Timeout.TRANSFER) { response ->
            BoundedStream(response.body.byteStream(), AnkiquestPackReader.MAX_ARCHIVE).readBytes()
        }
    }

    /** The pack [friend] shows, as last refreshed, or null: draw them as without packs. */
    fun shownBy(friend: String): SharedPack? = shown[friend]

    /** [friend]'s picture for [name] from their pack, following its fallbacks; null without one. */
    fun picture(
        context: Context,
        friend: String,
        name: String,
    ): ByteArray? {
        val names = AnkiquestPacks.library(context).names()
        val pack = shown[friend]?.let { cache(context).get(it.sha256, names) } ?: return null
        val file = names.chain(name).firstNotNullOfOrNull { pack.info.poses[it] } ?: return null
        return pack.file(file)
    }
}
