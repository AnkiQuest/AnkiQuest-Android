// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.AppBarLayout
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.R
import com.ichi2.anki.ankiquest.AnkiquestRwkvForecast.Band
import com.ichi2.anki.ankiquest.AnkiquestRwkvForecast.Chart
import com.ichi2.anki.ankiquest.AnkiquestRwkvForecast.Forecast
import com.ichi2.anki.ankiquest.AnkiquestRwkvForecast.Offset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import timber.log.Timber
import java.text.NumberFormat

/** Every review card's RWKV recall; null while RWKV is not running on this collection. */
internal fun interface RwkvRecallSource {
    suspend fun load(): List<RwkvRecall>?
}

/**
 * The backend's `RwkvOfflineForecast` over every review card ("is:review -is:learn -is:suspended
 * -is:buried"). Its `available` stays false until `RwkvPrepareOffline` has loaded a model, which
 * this app does not do yet (#433), so until then there is nothing to show.
 */
internal object BackendRwkvRecall : RwkvRecallSource {
    private const val EVERY_REVIEW_CARD = ""

    override suspend fun load(): List<RwkvRecall>? {
        val offsets = Offset.entries.map { it.seconds }
        val response = withCol { backend.rwkvOfflineForecast(search = EVERY_REVIEW_CARD, offsetsSecs = offsets) }
        if (!response.available) {
            return null
        }
        return response.cardsList
            .filter { it.recallCount == offsets.size }
            .map { RwkvRecall(it.cardId, it.targetRetention, it.recallList) }
    }
}

/**
 * RWKV statistics on the statistics screen, shown only while the RWKV scheduler is on: the forecast
 * line and the chart of recall now, as Pedrubik2000's anki-rwkv-forecast add-on defines them.
 * They cover every review card, as the add-on's do, whatever deck the statistics show.
 */
internal object AnkiquestRwkvStats {
    /** Adds the hidden panel under [appBar]'s toolbar and fills it once the recall is loaded. */
    fun attach(
        fragment: Fragment,
        appBar: AppBarLayout,
        source: RwkvRecallSource = BackendRwkvRecall,
    ) {
        if (AnkiquestRwkv.switch() != RwkvSwitch.ON) {
            return
        }
        val panel = panel(appBar.context)
        appBar.addView(panel)
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val cards = load(source) ?: return@launch
            fill(panel, cards)
        }
    }

    private suspend fun load(source: RwkvRecallSource): List<RwkvRecall>? =
        try {
            source.load()?.takeIf { it.isNotEmpty() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A backend without the call or a failed forecast hides the panel; the statistics stay.
            Timber.w(e, "RWKV forecast failed")
            null
        }

    private fun panel(context: Context): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            val padding = dp(context, PADDING_DP)
            setPadding(padding, padding / 2, padding, padding / 2)
            // The panel scrolls away with the toolbar, so the graphs keep the whole screen.
            layoutParams =
                AppBarLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    scrollFlags = AppBarLayout.LayoutParams.SCROLL_FLAG_SCROLL or AppBarLayout.LayoutParams.SCROLL_FLAG_ENTER_ALWAYS
                }
        }

    private fun fill(
        panel: LinearLayout,
        cards: List<RwkvRecall>,
    ) {
        val context = panel.context
        panel.addView(text(context, forecastHead(context), bold = true))
        panel.addView(text(context, forecastLines(context, AnkiquestRwkvForecast.forecast(cards))))

        // The chart, then the share of cards below their target.
        val chart = AnkiquestRwkvForecast.chart(cards)
        val below = belowLine(context, chart)
        panel.addView(text(context, context.getString(R.string.ankiquest_rwkv_recall_title), bold = true))
        panel.addView(
            RwkvRecallChart(context, chart).apply { contentDescription = below },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, CHART_HEIGHT_DP)),
        )
        panel.addView(text(context, below))
        panel.visibility = View.VISIBLE
    }

    private fun forecastHead(context: Context): String =
        context.getString(R.string.ankiquest_rwkv_forecast_title) + "  " + context.getString(R.string.ankiquest_rwkv_forecast_note)

    /** `due 62 | near 4 | safe 294`, then `due 62 · +3 in 1 h · +8 in 3 h · 70 by tomorrow`, as the add-on. */
    private fun forecastLines(
        context: Context,
        forecast: Forecast,
    ): String =
        context.getString(R.string.ankiquest_rwkv_forecast_now, forecast.due, forecast.near, forecast.safe) + "\n" +
            context.getString(
                R.string.ankiquest_rwkv_forecast_later,
                forecast.due,
                forecast.moreIn1h,
                forecast.moreIn3h,
                forecast.byTomorrow,
            )

    /** "X of Y cards (Z%) below 90%", or "below their desired retention" with several targets. */
    private fun belowLine(
        context: Context,
        chart: Chart,
    ): String {
        val share = percent().format(chart.belowShare)
        val target = chart.targets.singleOrNull()
        val plurals = context.resources
        if (target == null) {
            return plurals.getQuantityString(R.plurals.ankiquest_rwkv_recall_below_targets, chart.total, chart.below, chart.total, share)
        }
        return plurals.getQuantityString(
            R.plurals.ankiquest_rwkv_recall_below,
            chart.total,
            chart.below,
            chart.total,
            share,
            percent().format(target),
        )
    }

    private fun percent(): NumberFormat =
        NumberFormat.getPercentInstance(AnkiquestLanguage.locale()).apply { maximumFractionDigits = 1 }

    private fun text(
        context: Context,
        value: String,
        bold: Boolean = false,
    ): TextView =
        TextView(context).apply {
            text = value
            if (bold) {
                setTypeface(typeface, Typeface.BOLD)
            }
        }

    private fun dp(
        context: Context,
        value: Int,
    ): Int = (value * context.resources.displayMetrics.density).toInt()

    private const val PADDING_DP = 16
    private const val CHART_HEIGHT_DP = 120
}

/**
 * Bars of the cards at each whole percent of recall now, from 50% and below to 100%: red below the
 * card's desired retention, orange near it, green above, with a vertical line at each desired
 * retention. Values come from [AnkiquestRwkvForecast.chart].
 */
private class RwkvRecallChart(
    context: Context,
    private val chart: Chart,
) : View(context) {
    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = context.getColor(R.color.aq_rwkv_axis)
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, AXIS_TEXT_SP, resources.displayMetrics)
            textAlign = Paint.Align.CENTER
        }
    private val line =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = context.getColor(R.color.aq_rwkv_due)
            strokeWidth = LINE_DP * density
        }

    private val buckets = AnkiquestRwkvForecast.HIGHEST_PERCENT - AnkiquestRwkvForecast.LOWEST_PERCENT + 1

    override fun onDraw(canvas: Canvas) {
        val axis = label.textSize + AXIS_GAP_DP * density
        val plot = height - axis
        val slot = width.toFloat() / buckets
        val tallest = chart.buckets.maxOf { it.total }.coerceAtLeast(1)

        // Each bucket stacks its due, near and safe cards from the bottom.
        for ((index, bucket) in chart.buckets.withIndex()) {
            var bottom = plot
            for ((band, count) in listOf(Band.DUE to bucket.due, Band.NEAR to bucket.near, Band.SAFE to bucket.safe)) {
                if (count == 0) {
                    continue
                }
                val top = bottom - plot * count / tallest
                fill.color = context.getColor(colour(band))
                canvas.drawRect(index * slot + GAP_PX, top, (index + 1) * slot - GAP_PX, bottom, fill)
                bottom = top
            }
        }

        // A line at each desired retention, where it falls inside its bucket.
        for (target in chart.targets) {
            val x = (target * PERCENT - AnkiquestRwkvForecast.LOWEST_PERCENT).coerceAtLeast(0f) * slot
            canvas.drawLine(x, 0f, x, plot, line)
        }

        // Axis labels every 10 points; the first bucket also holds everything below it.
        for (percent in AnkiquestRwkvForecast.LOWEST_PERCENT..AnkiquestRwkvForecast.HIGHEST_PERCENT step AXIS_STEP) {
            val text = if (percent == AnkiquestRwkvForecast.LOWEST_PERCENT) "≤$percent%" else "$percent%"
            val x = (x(percent, slot) + slot / 2).coerceIn(label.measureText(text) / 2, width - label.measureText(text) / 2)
            canvas.drawText(text, x, height.toFloat() - AXIS_GAP_DP * density / 2, label)
        }
    }

    private fun x(
        percent: Int,
        slot: Float,
    ): Float = (percent - AnkiquestRwkvForecast.LOWEST_PERCENT) * slot

    @ColorRes
    private fun colour(band: Band): Int =
        when (band) {
            Band.DUE -> R.color.aq_rwkv_due
            Band.NEAR -> R.color.aq_rwkv_near
            Band.SAFE -> R.color.aq_rwkv_safe
        }

    private companion object {
        const val AXIS_STEP = 10
        const val PERCENT = 100
        const val AXIS_TEXT_SP = 10f
        const val AXIS_GAP_DP = 4
        const val LINE_DP = 2
        const val GAP_PX = 0.5f
    }
}
