// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.util.LruCache
import androidx.annotation.DrawableRes
import androidx.annotation.VisibleForTesting
import androidx.core.graphics.createBitmap
import com.ichi2.anki.R

/**
 * The mascot art for each moment the server tells the app about, decoded no larger than it is
 * shown. The pictures are far larger than any place they appear, so decoding them whole would cost
 * several megabytes each time.
 */
internal object AnkiquestArt {
    /** Room for one 1024 px streak widget pose (4 MiB) next to the smaller art. */
    private const val CACHE_BYTES = 8 * 1024 * 1024

    private val cache =
        object : LruCache<String, Bitmap>(CACHE_BYTES) {
            override fun sizeOf(
                key: String,
                value: Bitmap,
            ): Int = value.allocationByteCount
        }

    /**
     * Companion [id]'s picture for the Art name [name] from its installed pack, downsampled by a power
     * of two to the smallest size that still covers [size] pixels on its shorter side; null when no
     * pack shows [id] or the picture cannot be decoded. The pack resolves fallbacks. The bitmap is
     * shared and must not be changed.
     */
    fun packed(
        context: Context,
        id: String,
        name: String,
        size: Int,
    ): Bitmap? {
        if (size <= 0) return null
        val library = AnkiquestPacks.library(context)
        val pack = library.companion(id) ?: return null

        // Keyed by the pack's origin and version, so a newly downloaded pack is decoded afresh.
        val key = "pack:${pack.origin}:$id:${pack.info.version}:$name:$size"
        cache.get(key)?.let { return it }
        val bytes = library.picture(id, name) ?: return null
        return decoded(key, bytes, size)
    }

    /**
     * [friend]'s picture for [name] from the pack they show friends (AnkiquestPackSharing), at
     * least [size] pixels; null when they show none, so they are drawn as without packs.
     */
    fun friend(
        context: Context,
        friend: String,
        name: String,
        size: Int,
    ): Bitmap? {
        if (size <= 0) return null
        val shown = AnkiquestPackSharing.shownBy(friend) ?: return null

        // Keyed by the pack's hash: a friend's new pack is decoded afresh.
        val key = "friend:${shown.sha256}:$name:$size"
        cache.get(key)?.let { return it }
        val bytes = AnkiquestPackSharing.picture(context, friend, name) ?: return null
        return decoded(key, bytes, size)
    }

    /** [bytes] decoded with sampling for [size] and cached under [key]. */
    private fun decoded(
        key: String,
        bytes: ByteArray,
        size: Int,
    ): Bitmap? {
        val bounds =
            BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val edge = minOf(bounds.outWidth, bounds.outHeight)
        val options =
            BitmapFactory.Options().apply {
                inSampleSize = if (edge > 0) sampleSize(edge, size) else 1
            }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        cache.put(key, decoded)
        return decoded
    }

    /** Forgets every decoded picture, as when the installed packs were swapped. */
    @VisibleForTesting
    fun clear() = cache.evictAll()

    /**
     * [plain] drawn through [filter], cached under [key]. For art recoloured on the phone;
     * RemoteViews cannot apply a filter, so the colours are drawn in.
     */
    fun filtered(
        plain: Bitmap,
        key: String,
        filter: ColorFilter,
    ): Bitmap {
        val cached = "$key:${plain.width}"
        cache.get(cached)?.let { return it }
        val drawn = createBitmap(plain.width, plain.height)
        Canvas(drawn).drawBitmap(plain, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = filter })
        cache.put(cached, drawn)
        return drawn
    }

    /**
     * Art for an inbox notification of [kind]. [topic] is what a celebration or a friend's shared win
     * is about ("streak", "goal", "secured", "conquered", "achievement", …) or "place:N" for a weekly recap; older
     * servers send none.
     */
    fun forNotice(
        kind: String,
        topic: String?,
    ): AnkiquestPose =
        when (kind) {
            "celebration", "share" -> forTopic(topic)
            "nudge" -> AnkiquestPose.NUDGE
            "challenge_invite", "challenge_accepted" -> AnkiquestPose.CHALLENGE
            "challenge_complete" -> AnkiquestPose.CHALLENGE_WON
            // The server sends these only to a streak not studied today; the streak's length is not in the notice.
            // They show the player's own day, so the debug menu's overrides apply.
            "reminder_urgent", "reminder_milestone" -> AnkiquestMood.todayPose(AnkiquestMood.Mood.SAD, 1)
            "reminder_freeze_used" -> AnkiquestMood.todayPose(AnkiquestMood.Mood.FROZEN_1, 1)
            "reminder_weekly_recap" -> place(topic?.removePrefix("place:")?.toIntOrNull()) ?: AnkiquestPose.FACE
            else -> AnkiquestPose.FACE
        }

    private fun forTopic(topic: String?): AnkiquestPose =
        when {
            topic == "streak" -> AnkiquestPose.STREAK_MILESTONE
            // A notification does not say how long the streak is: the first fire pose.
            topic == "secured" -> AnkiquestMood.pose(AnkiquestMood.Mood.FIRE, 1)
            topic == "goal" -> AnkiquestPose.GOAL
            topic == "conquered" -> AnkiquestPose.DECK_CONQUERED
            topic == "companion" -> AnkiquestPose.HAPPY
            topic?.startsWith("place:") == true -> place(topic.removePrefix("place:").toIntOrNull()) ?: AnkiquestPose.ACHIEVEMENT
            else -> AnkiquestPose.ACHIEVEMENT
        }

    /** Art for finishing or climbing to [place] (1-based) on the weekly leaderboard, for the top three. */
    fun place(place: Int?): AnkiquestPose? =
        when (place) {
            1 -> AnkiquestPose.PLACE_1
            2 -> AnkiquestPose.PLACE_2
            3 -> AnkiquestPose.PLACE_3
            else -> null
        }

    /** The medal shown beside the top three leaderboard rows. */
    @DrawableRes
    fun medal(place: Int): Int? =
        when (place) {
            1 -> R.drawable.icon_place_1
            2 -> R.drawable.icon_place_2
            3 -> R.drawable.icon_place_3
            else -> null
        }
}

/** The largest power of two that keeps an [edge] of pixels at least [size] after sampling. */
internal fun sampleSize(
    edge: Int,
    size: Int,
): Int {
    var sample = 1
    while (edge / (sample * 2) >= size) sample *= 2
    return sample
}
