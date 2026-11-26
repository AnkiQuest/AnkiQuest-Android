// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import android.content.Context
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import com.ichi2.anki.CollectionManager.TR
import com.ichi2.anki.R
import java.text.NumberFormat

/** The words of one Download row: the model and its download size, and what it needs or why it cannot be picked. */
internal data class RwkvOfferText(
    val title: String,
    val detail: String,
)

/** Writes the Download list's rows. */
internal class RwkvOfferWords(
    private val context: Context,
) {
    fun text(
        offer: RwkvOffer,
        collection: RwkvCollection,
    ): RwkvOfferText {
        val name = context.getString(R.string.ankiquest_rwkv_offer, name(offer.entry), bytes(offer.entry.zipBytes))
        val title =
            when (offer.pick) {
                RwkvPick.RECOMMENDED -> {
                    val mark = context.getString(R.string.ankiquest_rwkv_recommended)
                    context.getString(R.string.ankiquest_rwkv_offer_marked, name, mark)
                }
                RwkvPick.OTHER -> name
            }

        // The reason a recommendation was made comes first; then what the row's state says.
        val lines = mutableListOf<String>()
        if (offer.pick == RwkvPick.RECOMMENDED) {
            lines.add(context.getString(R.string.ankiquest_rwkv_recommended_reason, "%.4f".format(offer.entry.logLoss)))
        }
        lines.add(detail(offer, collection))
        return RwkvOfferText(title, lines.joinToString("\n"))
    }

    /** The model's name, as the models row lists it: size, variant and id. */
    fun name(entry: RwkvLockEntry): String {
        val head =
            when (entry.head) {
                RwkvHead.TWO_HEAD -> context.getString(R.string.ankiquest_rwkv_two_head)
                RwkvHead.INSTANT -> context.getString(R.string.ankiquest_rwkv_instant_only)
            }
        return context.getString(R.string.ankiquest_rwkv_model_name, size(entry.size), head, entry.id.uppercase())
    }

    private fun detail(
        offer: RwkvOffer,
        collection: RwkvCollection,
    ): String {
        val cards = collection.cards.coerceAtLeast(0)
        val count = NumberFormat.getIntegerInstance().format(cards)
        val needs =
            when (offer.entry.head) {
                RwkvHead.TWO_HEAD -> R.plurals.ankiquest_rwkv_needs
                RwkvHead.INSTANT -> R.plurals.ankiquest_rwkv_needs_beside
            }
        val need = context.resources.getQuantityString(needs, cards, bytes(offer.neededBytes), count)
        return when (val state = offer.state) {
            RwkvOfferState.Fits -> need
            RwkvOfferState.Downloaded -> context.getString(R.string.ankiquest_rwkv_downloaded, need)
            RwkvOfferState.Verifying -> context.getString(R.string.ankiquest_rwkv_verifying)
            is RwkvOfferState.TooBig ->
                context.resources.getQuantityString(
                    R.plurals.ankiquest_rwkv_too_big,
                    cards,
                    bytes(state.neededBytes),
                    count,
                    bytes(state.allowedBytes),
                )
            is RwkvOfferState.Failed -> {
                val error = context.getString(AnkiquestRwkv.importError(state.error))
                context.getString(R.string.ankiquest_rwkv_failed, error)
            }
        }
    }

    private fun size(size: RwkvSize): String =
        when (size) {
            RwkvSize.BIG -> TR.deckConfigRwkvModelBig()
            RwkvSize.SMALL -> TR.deckConfigRwkvModelSmall()
            RwkvSize.TINY -> TR.deckConfigRwkvModelTiny()
        }

    private fun bytes(bytes: Long) = Formatter.formatShortFileSize(context, bytes)
}

/** The Download list's rows: two lines each; a row that cannot be picked is greyed out and ignores taps. */
internal class RwkvOfferAdapter(
    private val context: Context,
    private val list: RwkvOfferList,
) : BaseAdapter() {
    private val words = RwkvOfferWords(context)

    override fun getCount() = list.offers.size

    override fun getItem(position: Int): RwkvOffer = list.offers[position]

    override fun getItemId(position: Int) = position.toLong()

    override fun areAllItemsEnabled() = false

    override fun isEnabled(position: Int) = getItem(position).tappable

    override fun getView(
        position: Int,
        convertView: View?,
        parent: ViewGroup,
    ): View {
        val view = convertView ?: LayoutInflater.from(context).inflate(android.R.layout.simple_list_item_2, parent, false)
        val offer = getItem(position)
        val text = words.text(offer, list.device.collection)
        view.findViewById<TextView>(android.R.id.text1).text = text.title
        view.findViewById<TextView>(android.R.id.text2).text = text.detail

        // Greyed out, as a disabled control: the reason stays readable in the second line.
        view.isEnabled = offer.tappable
        view.alpha = if (offer.tappable) ENABLED_ALPHA else DISABLED_ALPHA
        return view
    }

    private companion object {
        const val ENABLED_ALPHA = 1f

        /** Material's disabled-content emphasis. */
        const val DISABLED_ALPHA = 0.38f
    }
}
