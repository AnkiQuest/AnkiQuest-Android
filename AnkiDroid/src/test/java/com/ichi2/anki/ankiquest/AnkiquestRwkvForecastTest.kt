// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import com.ichi2.anki.ankiquest.AnkiquestRwkvForecast.Band
import com.ichi2.anki.ankiquest.AnkiquestRwkvForecast.Forecast
import org.junit.Test
import kotlin.test.assertEquals

/** The RWKV forecast and recall chart, with anki-rwkv-forecast's definitions. */
class AnkiquestRwkvForecastTest {
    /** A card under [target] with recall [now], then in 1 h, in 3 h and at the rollover. */
    private fun card(
        id: Long,
        now: Float,
        in1h: Float = now,
        in3h: Float = in1h,
        tomorrow: Float = in3h,
        target: Float = 0.9f,
    ) = RwkvRecall(id, target, listOf(now, in1h, in3h, tomorrow))

    @Test
    fun `cards are due below the target, near within three points, safe above`() {
        assertEquals(Band.DUE, AnkiquestRwkvForecast.band(0.899f, 0.9f))
        assertEquals(Band.NEAR, AnkiquestRwkvForecast.band(0.9f, 0.9f))
        assertEquals(Band.NEAR, AnkiquestRwkvForecast.band(0.929f, 0.9f))
        assertEquals(Band.SAFE, AnkiquestRwkvForecast.band(0.93f, 0.9f))
    }

    @Test
    fun `the forecast counts later cards only when not due now`() {
        val cards =
            listOf(
                // Due now, and still due later: counted in "due" and "by tomorrow" only.
                card(1, now = 0.8f),
                // Near now, due in an hour.
                card(2, now = 0.91f, in1h = 0.89f),
                // Safe now, due in three hours.
                card(3, now = 0.95f, in1h = 0.92f, in3h = 0.88f),
                // Safe until the rollover.
                card(4, now = 0.97f, in1h = 0.96f, in3h = 0.95f, tomorrow = 0.85f),
                // Safe all along.
                card(5, now = 0.99f),
            )
        assertEquals(
            Forecast(due = 1, near = 1, safe = 3, moreIn1h = 1, moreIn3h = 2, byTomorrow = 4),
            AnkiquestRwkvForecast.forecast(cards),
        )
    }

    @Test
    fun `each card is due against its own target`() {
        val cards = listOf(card(1, now = 0.86f, target = 0.85f), card(2, now = 0.86f, target = 0.9f))
        val forecast = AnkiquestRwkvForecast.forecast(cards)
        assertEquals(1, forecast.due)
        assertEquals(1, forecast.near)
    }

    @Test
    fun `recall falls into whole-percent buckets from 50 and below to 100`() {
        assertEquals(90, AnkiquestRwkvForecast.percent(0.9f), "0.9f is 0.8999999 as a float")
        assertEquals(89, AnkiquestRwkvForecast.percent(0.8999f))
        assertEquals(50, AnkiquestRwkvForecast.percent(0.2f))
        assertEquals(50, AnkiquestRwkvForecast.percent(0.505f))
        assertEquals(51, AnkiquestRwkvForecast.percent(0.51f))
        assertEquals(100, AnkiquestRwkvForecast.percent(1f))
    }

    @Test
    fun `the chart stacks each bucket by band and counts the cards below their target`() {
        val cards =
            listOf(
                card(1, now = 0.3f),
                card(2, now = 0.895f),
                card(3, now = 0.905f),
                card(4, now = 0.905f, target = 0.95f),
                card(5, now = 1f),
            )
        val chart = AnkiquestRwkvForecast.chart(cards)

        assertEquals(51, chart.buckets.size)
        assertEquals((50..100).toList(), chart.buckets.map { it.percent })
        assertEquals(1, chart.buckets.first().due, "everything at 50% or below is in the first bucket")
        val ninety = chart.buckets.single { it.percent == 90 }
        assertEquals(1, ninety.near)
        assertEquals(1, ninety.due, "the same recall is due under a higher target")
        assertEquals(1, chart.buckets.last().safe)

        assertEquals(3, chart.below)
        assertEquals(5, chart.total)
        assertEquals(0.6f, chart.belowShare)
        assertEquals(setOf(0.9f, 0.95f), chart.targets)
    }

    @Test
    fun `no cards give an empty chart`() {
        val chart = AnkiquestRwkvForecast.chart(emptyList())
        assertEquals(0, chart.total)
        assertEquals(0f, chart.belowShare)
        assertEquals(0, chart.buckets.sumOf { it.total })
    }
}
