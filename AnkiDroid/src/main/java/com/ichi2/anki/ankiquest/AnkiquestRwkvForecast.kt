// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import kotlin.math.floor

/** One review card's RWKV recall, one value per [AnkiquestRwkvForecast.Offset], and the recall it is due under. */
internal data class RwkvRecall(
    val cardId: Long,
    val target: Float,
    val recall: List<Float>,
) {
    fun at(offset: AnkiquestRwkvForecast.Offset): Float = recall[offset.ordinal]
}

/**
 * The RWKV statistics, with the definitions of Pedrubik2000's desktop add-on anki-rwkv-forecast
 * (`views.py`): every number means "if you stop reviewing now", since each answer moves every
 * card's recall. A card is due when its recall is below its target, near within [NEAR_MARGIN]
 * above it, and safe otherwise.
 */
internal object AnkiquestRwkvForecast {
    /** The add-on's `near_margin`: "near" is recall from the target up to 3 points above it. */
    const val NEAR_MARGIN = 0.03f

    /** The lowest bucket of the recall chart holds every card at this percent or below. */
    const val LOWEST_PERCENT = 50
    const val HIGHEST_PERCENT = 100

    private const val PERCENT = 100

    /** Float recall such as 0.9f is 0.8999999; this keeps it in the 90% bucket. */
    private const val ROUNDING = 1e-4

    /** The times the backend projects recall to, in the order of [RwkvRecall.recall]. */
    enum class Offset(
        val seconds: Long,
    ) {
        NOW(0),
        ONE_HOUR(3_600),
        THREE_HOURS(3 * 3_600L),

        /** The backend's code for the next day rollover. */
        TOMORROW(-1),
    }

    enum class Band { DUE, NEAR, SAFE }

    /** The forecast line: due, near and safe now, and the due cards later. */
    data class Forecast(
        val due: Int,
        val near: Int,
        val safe: Int,
        /** Not due now, due in an hour. */
        val moreIn1h: Int,
        /** Not due now, due in three hours. */
        val moreIn3h: Int,
        /** Due at the next rollover, those due now included. */
        val byTomorrow: Int,
    )

    /** One 1% bucket of the recall chart: [percent] is its floor ([LOWEST_PERCENT] holds everything below too). */
    data class Bucket(
        val percent: Int,
        val due: Int,
        val near: Int,
        val safe: Int,
    ) {
        val total: Int get() = due + near + safe
    }

    /** The recall chart: [Bucket]s from [LOWEST_PERCENT] to [HIGHEST_PERCENT], and the cards below their target. */
    data class Chart(
        val buckets: List<Bucket>,
        val below: Int,
        val total: Int,
        /** Each desired retention among the cards, for its vertical line. */
        val targets: Set<Float>,
    ) {
        /** The share of cards below their target, from 0 to 1. */
        val belowShare: Float get() = if (total == 0) 0f else below.toFloat() / total
    }

    fun band(
        recall: Float,
        target: Float,
    ): Band =
        when {
            recall < target -> Band.DUE
            recall < target + NEAR_MARGIN -> Band.NEAR
            else -> Band.SAFE
        }

    private fun RwkvRecall.band(offset: Offset): Band = band(at(offset), target)

    fun forecast(cards: List<RwkvRecall>): Forecast {
        val now = cards.groupingBy { it.band(Offset.NOW) }.eachCount()

        // "+N in 1 h" counts only cards not due now; "by tomorrow" counts every card due then.
        val later = cards.filter { it.band(Offset.NOW) != Band.DUE }
        return Forecast(
            due = now[Band.DUE] ?: 0,
            near = now[Band.NEAR] ?: 0,
            safe = now[Band.SAFE] ?: 0,
            moreIn1h = later.count { it.band(Offset.ONE_HOUR) == Band.DUE },
            moreIn3h = later.count { it.band(Offset.THREE_HOURS) == Band.DUE },
            byTomorrow = cards.count { it.band(Offset.TOMORROW) == Band.DUE },
        )
    }

    /** The bucket percent for [recall]: its floor in whole percent, at least [LOWEST_PERCENT]. */
    fun percent(recall: Float): Int = floor(recall.toDouble() * PERCENT + ROUNDING).toInt().coerceIn(LOWEST_PERCENT, HIGHEST_PERCENT)

    fun chart(cards: List<RwkvRecall>): Chart {
        // Each bucket counts its cards by band, so cards under different targets keep their own colour.
        val byPercent = cards.groupBy { percent(it.at(Offset.NOW)) }
        val buckets =
            (LOWEST_PERCENT..HIGHEST_PERCENT).map { percent ->
                val bands = byPercent[percent].orEmpty().groupingBy { it.band(Offset.NOW) }.eachCount()
                Bucket(percent, bands[Band.DUE] ?: 0, bands[Band.NEAR] ?: 0, bands[Band.SAFE] ?: 0)
            }
        return Chart(
            buckets = buckets,
            below = buckets.sumOf { it.due },
            total = cards.size,
            targets = cards.mapTo(sortedSetOf()) { it.target },
        )
    }
}
