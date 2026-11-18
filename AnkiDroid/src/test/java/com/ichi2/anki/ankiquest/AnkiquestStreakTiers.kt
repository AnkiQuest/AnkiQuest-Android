// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.Context
import android.graphics.Bitmap
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.view.drawToBitmap
import com.ichi2.anki.ankiquest.AnkiquestStreakWidget.Size
import org.json.JSONArray
import org.json.JSONObject

/** The streak widget at each streak tier, two per row, as `AnkiquestStreakScreenshotTest` records it. */
internal object AnkiquestStreakTiers {
    /** Fixed study day keeps the rotating companion pose stable across baselines. */
    private const val NOW = 1_750_000_000_000L
    private const val HOUR_MS = 3_600_000L
    private const val COLUMNS = 2
    private val streaks = listOf(1, 7, 30, 100, 365, 1000)

    /** Each size with the width and height in dp it is drawn at. */
    val sizes =
        listOf(Triple(Size.SMALL, 57, 57), Triple(Size.MEDIUM, 100, 90), Triple(Size.LARGE, 200, 220), Triple(Size.HUGE, 300, 380))

    /** Every tier of [size], each widget [widthDp] x [heightDp]. */
    fun grid(
        context: Context,
        size: Size,
        widthDp: Int,
        heightDp: Int,
    ): Bitmap {
        val grid =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutDirection = View.LAYOUT_DIRECTION_LTR
            }
        val density = grid.resources.displayMetrics.density
        val widthPx = (widthDp * density).toInt()
        val heightPx = (heightDp * density).toInt()
        val rows = streaks.chunked(COLUMNS)
        for (row in rows) {
            val line = LinearLayout(context)
            for (streak in row) {
                val widget =
                    AnkiquestStreakWidget
                        .views(context, board(streak), "hill", size, NOW)
                        .apply(context, FrameLayout(context))
                line.addView(widget, LinearLayout.LayoutParams(widthPx, heightPx))
            }
            grid.addView(line)
        }

        // Lay the grid out off screen at its exact size.
        grid.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx * COLUMNS, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightPx * rows.size, View.MeasureSpec.EXACTLY),
        )
        grid.layout(0, 0, widthPx * COLUMNS, heightPx * rows.size)
        return grid.drawToBitmap()
    }

    private fun board(streak: Int): JSONArray =
        JSONArray().put(
            JSONObject()
                .put("user", "hill")
                .put("streak", streak)
                .put("streak_state", "studied")
                .put("today_reviews", 20)
                .put("today_minutes", 12)
                .put("streak_minutes", 10)
                .put("day_ends_at", NOW + HOUR_MS),
        )
}
