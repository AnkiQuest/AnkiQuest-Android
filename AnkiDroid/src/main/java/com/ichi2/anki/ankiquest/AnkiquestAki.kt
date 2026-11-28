// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import android.content.Context
import android.graphics.Bitmap
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import com.ichi2.anki.R
import com.ichi2.anki.ankiquest.AnkiquestMood.Goal
import com.ichi2.anki.common.time.TimeManager
import org.json.JSONObject

/** Aki encourages real progress without changing study, scoring or notification rules. */
internal object AnkiquestAki {
    /** What the Home card says, chosen to fit Aki's mood. */
    enum class Message { WELCOME, REVIEW, CELEBRATE, STREAK, FREEZE, WAITING }

    /** The Home card: Aki's [mood] for a [streak] of that many days, and a [message] that fits it. */
    data class Card(
        val mood: AnkiquestMood.Mood,
        val streak: Int,
        val message: Message,
    ) {
        /** The pose [AnkiquestMood] gives the mood, as on the streak widget, or the debug menu's. */
        @get:DrawableRes
        val image: Int get() = AnkiquestMood.todayPose(mood, streak)

        /** The line beside the pose, or null for none: cards due need none, the next-step card says what to do. */
        fun text(context: Context): String? =
            when (message) {
                Message.WELCOME -> context.getString(R.string.aki_welcome)
                Message.REVIEW -> null
                Message.CELEBRATE -> context.getString(R.string.aki_complete)
                Message.STREAK -> context.getString(R.string.aki_streak)
                Message.FREEZE -> context.getString(R.string.aki_protected)
                Message.WAITING -> context.resources.getQuantityString(R.plurals.aki_streak_waiting, streak, streak)
            }
    }

    /** Aki's mood for today; the collection, not the server, says whether cards are still due. */
    fun mood(
        local: HomeLocal?,
        profile: JSONObject?,
        now: Long = TimeManager.time.intTimeMS(),
    ): Card {
        val goal = local?.goal
        val day =
            if (goal == null) {
                AnkiquestDay.judge(AnkiquestDay.Server.profile(profile), emptyList(), now)
            } else {
                goal.withServerDay(profile, now).streakDay
            }

        // The pose only uses facts from the profile for today's study day.
        val current = profile?.takeIf { day != null && it.optLong("day_ends_at") > now }
        val reached = if (current?.optJSONObject("goal")?.optBoolean("reached") == true) Goal.REACHED else Goal.OPEN
        val mood = AnkiquestMood.today(day, AnkiquestMood.history(current, now), reached)
        return Card(mood, day?.streak ?: 0, message(mood, local, day))
    }

    private fun message(
        mood: AnkiquestMood.Mood,
        local: HomeLocal?,
        day: AnkiquestDay.Verdict?,
    ): Message {
        // Before any study, a streak waiting for today, or kept by a freeze, comes before anything
        // about the decks. The mood stays sad or frozen for a while after study starts; the words don't.
        val reviewed = (day?.reviews ?: 0) > 0
        if (!reviewed && mood == AnkiquestMood.Mood.SAD) {
            return Message.WAITING
        }
        if (!reviewed && mood in frozen) {
            return Message.FREEZE
        }

        // Otherwise the decks decide: all done after real study, or cards still due.
        if (reviewed && local?.decks?.isNotEmpty() == true && local.decks.all { it.due == 0 }) {
            return Message.CELEBRATE
        }
        if ((local?.focus?.due ?: 0) > 0) {
            return Message.REVIEW
        }
        if (mood == AnkiquestMood.Mood.FIRE) {
            return Message.STREAK
        }
        return Message.WELCOME
    }

    private val frozen =
        setOf(AnkiquestMood.Mood.FREEZE, AnkiquestMood.Mood.FROZEN_1, AnkiquestMood.Mood.FROZEN_2, AnkiquestMood.Mood.FROZEN_3)

    fun image(
        context: Context,
        @DrawableRes resource: Int,
        size: Int = 80,
    ): ImageView =
        ImageView(context).apply {
            AnkiquestCompanion.show(this, resource, dp(context, size))
            scaleType = ImageView.ScaleType.FIT_CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = LinearLayout.LayoutParams(dp(context, size), dp(context, size))
        }

    fun encouragement(
        context: Context,
        card: Card,
        picture: AnkiquestPose? = null,
    ): LinearLayout =
        LinearLayout(context).apply {
            visibility = if (AnkiquestCompanion.visible()) View.VISIBLE else View.GONE
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(context, 8), 0, dp(context, 8))
            addView(image(context, picture?.let { AnkiquestCompanion.art(it) } ?: card.image))

            // A mood without a line shows the pose alone; the debug menu's overrides always say so.
            val line =
                listOfNotNull(card.text(context), AnkiquestDebug.marker(context))
                    .joinToString("\n")
                    .ifEmpty { return@apply }
            addView(
                TextView(context).apply {
                    text = line
                    textSize = 16f
                    setTextColor(context.getColor(R.color.aq_home_text))
                    setPadding(dp(context, 12), 0, 0, 0)
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
        }

    fun notificationIcon(
        context: Context,
        @DrawableRes art: Int = R.drawable.aki_face,
    ): Bitmap? =
        if (!AnkiquestCompanion.visible()) {
            null
        } else {
            AnkiquestCompanion.bitmap(
                context,
                art,
                context.resources.getDimensionPixelSize(android.R.dimen.notification_large_icon_width),
            )
        }

    private fun dp(
        context: Context,
        value: Int,
    ) = (value * context.resources.displayMetrics.density).toInt()
}
