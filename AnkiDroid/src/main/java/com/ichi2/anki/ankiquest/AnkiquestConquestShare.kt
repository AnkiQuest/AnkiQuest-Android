// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import android.content.Context
import com.ichi2.anki.R
import java.text.NumberFormat

/** Public conquest wording shared by the conquest page and the session card. */
internal object AnkiquestConquestShare {
    /** Only a label saved in the server response belongs in a public export. */
    fun mine(
        context: Context,
        lapses: Int,
        label: String? = null,
    ): String =
        if (label == null) {
            context.resources.getQuantityString(R.plurals.aq_conquest_share_mine, lapses, lapses)
        } else {
            context.resources.getQuantityString(R.plurals.aq_conquest_share_label_mine, lapses, label, lapses)
        }

    fun deck(
        context: Context,
        name: String?,
    ): String? = name?.let { context.getString(R.string.aq_conquest_share_deck, it) }

    fun caption(
        context: Context,
        viewer: HomeAccount,
        card: AnkiquestCommunity.Conquest,
        name: String?,
    ): String =
        listOfNotNull(
            words(context, viewer, card),
            from(context, viewer, card, name),
            AnkiquestCommunity.conquestLink(viewer.server, card.user, card.at),
        ).joinToString("\n")

    /** Public counts accompany the saved label once; only the owner may add a local deck. */
    fun picture(
        context: Context,
        viewer: HomeAccount,
        card: AnkiquestCommunity.Conquest,
        name: String?,
    ): AnkiquestShare.Card {
        val resources = context.resources
        val numbers = NumberFormat.getIntegerInstance(AnkiquestLanguage.locale())
        return AnkiquestShare.Card(
            context.getString(if (card.leech) R.string.aq_profile_leech else R.string.aq_profile_conquest),
            words(context, viewer, card),
            if (card.leech) R.drawable.aki_winner else R.drawable.aki_deck_conquered,
            listOfNotNull(
                from(context, viewer, card, name),
                resources.getQuantityString(R.plurals.aq_conquest_answers, card.answers, numbers.format(card.answers)),
                resources.getQuantityString(
                    R.plurals.aq_conquest_days,
                    card.days.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                    numbers.format(card.days),
                ),
            ),
        )
    }

    private fun words(
        context: Context,
        viewer: HomeAccount,
        card: AnkiquestCommunity.Conquest,
    ): String =
        when {
            card.user == viewer.user -> mine(context, card.lapses, card.label)
            card.label != null ->
                context.resources.getQuantityString(R.plurals.aq_conquest_share_label, card.lapses, card.display, card.label, card.lapses)
            else -> context.resources.getQuantityString(R.plurals.aq_conquest_share, card.lapses, card.display, card.lapses)
        }

    private fun from(
        context: Context,
        viewer: HomeAccount,
        card: AnkiquestCommunity.Conquest,
        name: String?,
    ): String? = deck(context, name.takeIf { owns(viewer, card) })

    /** Whether [card] is [viewer]'s own, so its deck and win may be used. */
    fun owns(
        viewer: HomeAccount,
        card: AnkiquestCommunity.Conquest,
    ): Boolean = card.mine && card.user == viewer.user
}
