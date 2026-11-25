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
import com.ichi2.anki.ankiquest.ui.R

/** Aki encourages real progress without changing study, scoring or notification rules. */
object AnkiquestAki {
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
    ): LinearLayout =
        LinearLayout(context).apply {
            visibility = if (AnkiquestCompanion.visible()) View.VISIBLE else View.GONE
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(context, 8), 0, dp(context, 8))
            addView(image(context, card.image))

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
