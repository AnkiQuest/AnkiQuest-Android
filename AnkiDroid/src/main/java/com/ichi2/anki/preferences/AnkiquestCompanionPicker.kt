// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.preferences

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.google.android.material.color.MaterialColors
import com.ichi2.anki.R
import com.ichi2.anki.ankiquest.AnkiquestCompanion
import com.ichi2.anki.ankiquest.AnkiquestCompanion.Choice
import com.ichi2.anki.ankiquest.AnkiquestGoalBar
import com.ichi2.anki.ankiquest.AnkiquestUnlocks.Milestone
import com.ichi2.anki.ankiquest.AnkiquestUnlocks.Offer
import com.ichi2.anki.ankiquest.AnkiquestUnlocks.Progress

/**
 * One card per companion: its preview poses ([AnkiquestCompanion.preview]) with its name
 * under them, the chosen one outlined. A locked companion shows as a silhouette with its condition
 * and progress and cannot be tapped. Tapping a card reports it; the screen decides what follows.
 */
internal class AnkiquestCompanionPicker(
    context: Context,
    attrs: AttributeSet?,
) : Preference(context, attrs) {
    private var offers: Map<Choice, Offer> = emptyMap()
    private var chosen: Choice? = null
    private var onTap: (Choice) -> Unit = {}

    /** A companion whose picture grows on the next bind, as it turns into its chibi. */
    private var growing: Choice? = null

    init {
        layoutResource = R.layout.preference_ankiquest_companion_picker
        isSelectable = false
        isPersistent = false
    }

    /** Shows a card per companion in [offers], in order, with [chosen] marked. */
    fun show(
        offers: Map<Choice, Offer>,
        chosen: Choice,
    ) {
        if (this.offers == offers && this.chosen == chosen) {
            return
        }
        this.offers = offers
        this.chosen = chosen
        notifyChanged()
    }

    fun setOnTap(listener: (Choice) -> Unit) {
        onTap = listener
    }

    /** Shows [choice]'s picture growing, unless animations are off. */
    fun grow(choice: Choice) {
        growing = choice
        notifyChanged()
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val cards = holder.findViewById(R.id.ankiquest_companion_cards) as LinearLayout
        cards.removeAllViews()
        for ((choice, offer) in offers) {
            val card = card(choice, offer as? Offer.Locked)
            cards.addView(card, cardParams())
            if (choice == growing && choice != Choice.NONE) {
                grow(card.getChildAt(0))
            }
        }
        growing = null
    }

    /** [pictures] grow from a smaller size, with a small overshoot; nothing moves when animations are off. */
    private fun grow(pictures: View) {
        if (!AnkiquestGoalBar.animationsEnabled(context)) {
            return
        }
        pictures.scaleX = GROW_FROM
        pictures.scaleY = GROW_FROM
        pictures
            .animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(GROW_MS)
            .setInterpolator(OvershootInterpolator())
            .start()
    }

    /** [choice]'s card; a [locked] one is a silhouette with how to earn it, and cannot be tapped. */
    private fun card(
        choice: Choice,
        locked: Offer.Locked?,
    ): LinearLayout {
        val selected = choice == chosen
        val name = context.getString(choice.title)
        val lines = locked?.let { lockedLines(it.progress) }.orEmpty()
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(CARD_PADDING_DP), dp(CARD_PADDING_DP), dp(CARD_PADDING_DP), dp(CARD_PADDING_DP))
            minimumHeight = dp(MIN_TOUCH_DP)
            background = outline(choice)
            foreground = ripple()
            isEnabled = this@AnkiquestCompanionPicker.isEnabled && locked == null
            alpha = if (this@AnkiquestCompanionPicker.isEnabled) 1f else DISABLED_ALPHA
            isClickable = locked == null
            isFocusable = true
            contentDescription = (listOf(name) + lines).joinToString(", ")
            if (locked == null) {
                setOnClickListener { onTap(choice) }
            }
            describeAsRadio(this, choice)

            // No companion has no poses to show.
            if (choice != Choice.NONE) {
                addView(poses(choice, locked))
            }
            addView(label(name, NAME_SP, if (selected) Typeface.BOLD else Typeface.NORMAL))

            // A locked card says how to earn it and how far the player is.
            for (line in lines) {
                addView(label(line, LINE_SP, Typeface.NORMAL))
            }
        }
    }

    private fun label(
        line: String,
        size: Float,
        style: Int,
    ) = TextView(context).apply {
        text = line
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTypeface(typeface, style)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** The condition and progress a locked card shows, leaving out a milestone this app cannot word. */
    private fun lockedLines(progress: Progress): List<String> =
        when (progress.milestone) {
            Milestone.LEVEL ->
                listOf(
                    context.getString(R.string.ankiquest_companion_level_goal, progress.goal),
                    context.getString(R.string.ankiquest_companion_level_progress, progress.value, progress.goal),
                )
            Milestone.STUDY_HOURS ->
                listOf(
                    context.getString(R.string.ankiquest_companion_hours_goal, progress.goal),
                    context.getString(R.string.ankiquest_companion_hours_progress, progress.value, progress.goal),
                )
            null -> emptyList()
        }

    /** [choice]'s preview poses, as silhouettes when [locked]. */
    private fun poses(
        choice: Choice,
        locked: Offer.Locked?,
    ): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            val edge = dp(POSE_DP)
            for (pose in AnkiquestCompanion.preview(choice)) {
                val bitmap =
                    if (locked == null) {
                        AnkiquestCompanion.bitmap(context, pose, edge, choice)
                    } else {
                        AnkiquestCompanion.silhouette(context, pose, edge, choice)
                    } ?: continue
                addView(
                    ImageView(context).apply {
                        setImageBitmap(bitmap)
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    },
                    LinearLayout.LayoutParams(0, edge, 1f),
                )
            }
        }

    /** The chosen card has a thick outline in the accent colour, the others a thin one. */
    private fun outline(choice: Choice): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(CORNER_DP).toFloat()
            if (choice == chosen) {
                setStroke(dp(SELECTED_STROKE_DP), MaterialColors.getColor(context, android.R.attr.colorAccent, 0))
            } else {
                setStroke(dp(STROKE_DP), MaterialColors.getColor(context, android.R.attr.colorControlNormal, 0))
            }
        }

    private fun ripple() =
        TypedValue().let { value ->
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
            AppCompatResources.getDrawable(context, value.resourceId)
        }

    /** TalkBack reads the cards as a group of radio buttons. */
    private fun describeAsRadio(
        view: View,
        choice: Choice,
    ) {
        val selected = choice == chosen
        ViewCompat.setAccessibilityDelegate(
            view,
            object : AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(
                    host: View,
                    info: AccessibilityNodeInfoCompat,
                ) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = RadioButton::class.java.name
                    info.isCheckable = true
                    info.setChecked(
                        if (selected) AccessibilityNodeInfoCompat.CHECKED_STATE_TRUE else AccessibilityNodeInfoCompat.CHECKED_STATE_FALSE,
                    )
                }
            },
        )
    }

    private fun cardParams() =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(CARD_GAP_DP)
        }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    private companion object {
        const val POSE_DP = 72
        const val CARD_PADDING_DP = 12
        const val CARD_GAP_DP = 12
        const val CORNER_DP = 12
        const val STROKE_DP = 1
        const val SELECTED_STROKE_DP = 3
        const val MIN_TOUCH_DP = 48
        const val NAME_SP = 16f
        const val LINE_SP = 14f
        const val DISABLED_ALPHA = 0.5f

        /** The chibi grows from this share of its size, over [GROW_MS]. */
        const val GROW_FROM = 0.6f
        const val GROW_MS = 300L
    }
}
