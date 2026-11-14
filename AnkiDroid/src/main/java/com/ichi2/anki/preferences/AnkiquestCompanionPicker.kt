// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.preferences

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
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

/**
 * One card per companion: its preview poses ([AnkiquestCompanion.preview]) with its name
 * under them, the chosen one outlined. Tapping a card reports it; the screen decides what follows.
 */
internal class AnkiquestCompanionPicker(
    context: Context,
    attrs: AttributeSet?,
) : Preference(context, attrs) {
    private var choices: List<Choice> = emptyList()
    private var chosen: Choice? = null
    private var onTap: (Choice) -> Unit = {}

    init {
        layoutResource = R.layout.preference_ankiquest_companion_picker
        isSelectable = false
        isPersistent = false
    }

    /** Shows [choices] with [chosen] marked. */
    fun show(
        choices: List<Choice>,
        chosen: Choice,
    ) {
        if (this.choices == choices && this.chosen == chosen) {
            return
        }
        this.choices = choices
        this.chosen = chosen
        notifyChanged()
    }

    fun setOnTap(listener: (Choice) -> Unit) {
        onTap = listener
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val cards = holder.findViewById(R.id.ankiquest_companion_cards) as LinearLayout
        cards.removeAllViews()
        for (choice in choices) {
            cards.addView(card(choice), cardParams())
        }
    }

    private fun card(choice: Choice): View {
        val selected = choice == chosen
        val name = context.getString(choice.title)
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(CARD_PADDING_DP), dp(CARD_PADDING_DP), dp(CARD_PADDING_DP), dp(CARD_PADDING_DP))
            minimumHeight = dp(MIN_TOUCH_DP)
            background = outline(choice)
            foreground = ripple()
            isEnabled = this@AnkiquestCompanionPicker.isEnabled
            alpha = if (isEnabled) 1f else DISABLED_ALPHA
            isClickable = true
            isFocusable = true
            contentDescription = name
            setOnClickListener { onTap(choice) }
            describeAsRadio(this, choice)

            // No companion has no poses to show.
            if (choice != Choice.NONE) {
                addView(poses(choice))
            }
            addView(
                TextView(context).apply {
                    text = name
                    gravity = Gravity.CENTER
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, NAME_SP)
                    if (selected) {
                        setTypeface(typeface, Typeface.BOLD)
                    }
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
            )
        }
    }

    private fun poses(choice: Choice): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            val edge = dp(POSE_DP)
            for (pose in AnkiquestCompanion.preview(choice)) {
                val bitmap = AnkiquestCompanion.bitmap(context, pose, edge, choice) ?: continue
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
        const val DISABLED_ALPHA = 0.5f
    }
}
