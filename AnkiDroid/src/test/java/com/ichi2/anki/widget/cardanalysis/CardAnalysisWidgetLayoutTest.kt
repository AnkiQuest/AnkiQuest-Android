// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.widget.cardanalysis

import android.app.Application
import android.app.PendingIntent
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Region
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.RemoteViews
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.allViews
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.CommonString
import com.ichi2.anki.IntentHandler
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.common.utils.dp
import com.ichi2.anki.libanki.CardType
import com.ichi2.anki.libanki.Consts
import com.ichi2.anki.libanki.DeckId
import com.ichi2.anki.libanki.QueueType
import com.ichi2.widget.AppWidgetId
import com.ichi2.widget.cardanalysis.CardAnalysisWidget
import com.ichi2.widget.cardanalysis.CardAnalysisWidgetPreferences
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import com.ichi2.anki.widgets.R as WidgetR

/** The launcher's bounds must contain readable counts and a deliberate full or ellipsized label. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CardAnalysisWidgetLayoutTest : RobolectricTest() {
    private enum class Delivery { UPDATE, RESIZE }

    @After
    fun restoreFont() = RuntimeEnvironment.setFontScale(1f)

    @Test
    fun `compact widget keeps its deck label inside its bounds`() =
        runTest {
            checkWidget(175, 102)
        }

    @Test
    fun `large text keeps compact counts and its label readable`() =
        runTest {
            checkWidget(175, 102, 2f)
        }

    @Test
    fun `compact widget offers a full-size study target`() =
        runTest {
            checkStudyTarget(checkWidget(146, 76, 2f))
        }

    @Test
    fun `existing widget discards the old deck-name click target`() =
        runTest {
            checkStudyTarget(checkWidget(146, 76, 2f, fromOldLayout = true))
        }

    @Test
    fun `minimum widget keeps its counts and accessible deck identity`() =
        runTest {
            checkWidget(84, 56)
        }

    @Test
    fun `minimum widget remains readable at large text`() =
        runTest {
            checkWidget(84, 56, 2f)
        }

    @Test
    @Config(qualifiers = "night")
    fun `small counts remain readable in the dark theme`() =
        runTest {
            checkWidget(84, 56)
        }

    @Test
    fun `larger widgets keep the complete label at large text`() =
        runTest {
            checkWidget(349, 220, 2f)
        }

    @Test
    fun `simulated pre-O update fits compact widgets without native autosizing`() =
        runTest {
            checkWidget(175, 102, 2f, Build.VERSION_CODES.N)
        }

    @Test
    fun `simulated pre-O resize refits minimum widgets`() =
        runTest {
            checkWidget(84, 56, 2f, Build.VERSION_CODES.N, Delivery.RESIZE)
        }

    @Test
    fun `compact missing-deck widget shows complete instructions`() =
        runTest {
            checkEmptyWidget(null, targetContext.getString(CommonString.empty_widget_state))
        }

    @Test
    fun `compact empty-collection widget shows complete instructions`() =
        runTest {
            checkEmptyWidget(Consts.DEFAULT_DECK_ID, targetContext.getString(CommonString.empty_collection_state_in_widget))
        }

    private fun TestScope.checkEmptyWidget(
        deckId: DeckId?,
        expectedText: String,
    ) {
        val id = AppWidgetId(1)
        CardAnalysisWidgetPreferences(targetContext).saveSelectedDeck(id, deckId)
        val manager = AppWidgetManager.getInstance(targetContext)
        shadowOf(manager).bindAppWidgetId(id.id, ComponentName(targetContext, CardAnalysisWidget::class.java))
        CardAnalysisWidget.updateWidget(targetContext, manager, id)
        advanceUntilIdle()

        val widget = assertNotNull(shadowOf(manager).getViewFor(id.id)) as ViewGroup
        val width = 175.dp.toPx(targetContext)
        val height = 102.dp.toPx(targetContext)
        widget.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        widget.layout(0, 0, width, height)

        val instructions = widget.findViewById<TextView>(WidgetR.id.empty_widget)
        assertTrue(instructions.isVisible)
        assertEquals(expectedText, instructions.text.toString())
        assertTrue(!widget.findViewById<View>(WidgetR.id.cardAnalysisDataHolder).isVisible)
        assertTrue(!widget.findViewById<View>(WidgetR.id.deckNameCardAnalysis).isVisible)
        inside(widget, instructions)
        assertEquals(expectedText.length, instructions.layout.getLineEnd(instructions.layout.lineCount - 1))
        insideOutline(widget, instructions, clipTextPadding = true)
    }

    private fun TestScope.checkWidget(
        widthDp: Int,
        heightDp: Int,
        fontScale: Float = 1f,
        sdk: Int = Build.VERSION.SDK_INT,
        source: Delivery = Delivery.UPDATE,
        fromOldLayout: Boolean = false,
    ): ViewGroup {
        RuntimeEnvironment.setFontScale(fontScale)
        val deck = addDeck("Pharmacology").withNotes(count = 12).withNotes(count = 3, queueType = QueueType.Lrn)
        addNotes(25).forEach { note ->
            note.firstCard().update {
                did = deck
                type = CardType.Rev
                queue = QueueType.Rev
                due = 0
            }
        }
        val id = AppWidgetId(1)
        CardAnalysisWidgetPreferences(targetContext).saveSelectedDeck(id, deck)
        val manager = AppWidgetManager.getInstance(targetContext)
        shadowOf(manager).bindAppWidgetId(id.id, ComponentName(targetContext, CardAnalysisWidget::class.java))
        manager.updateAppWidgetOptions(
            id.id,
            Bundle().apply {
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, widthDp)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, widthDp)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, heightDp)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, heightDp)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && sdk >= Build.VERSION_CODES.S) {
                    putParcelableArrayList(
                        AppWidgetManager.OPTION_APPWIDGET_SIZES,
                        arrayListOf(SizeF(widthDp.toFloat(), heightDp.toFloat())),
                    )
                }
            },
        )
        var rendered: RemoteViews? = null
        val delivery = mockk<AppWidgetManager>()
        every { delivery.getAppWidgetOptions(id.id) } answers { manager.getAppWidgetOptions(id.id) }
        every { delivery.updateAppWidget(id.id, any<RemoteViews>()) } answers {
            rendered = secondArg()
            manager.updateAppWidget(id.id, rendered)
        }
        // Keep the native-font classloader, but exercise the actual pre-O update path when requested.
        val originalSdk = Build.VERSION.SDK_INT
        try {
            ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", sdk)
            when (source) {
                Delivery.UPDATE -> CardAnalysisWidget.updateWidget(targetContext, delivery, id)
                Delivery.RESIZE ->
                    CardAnalysisWidget().onAppWidgetOptionsChanged(
                        targetContext,
                        delivery,
                        id.id,
                        manager.getAppWidgetOptions(id.id),
                    )
            }
            advanceUntilIdle()
        } finally {
            ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", originalSdk)
        }
        val widget =
            if (fromOldLayout) {
                val host = AppWidgetHostView(targetContext)
                host.setAppWidget(
                    id.id,
                    AppWidgetProviderInfo().apply { provider = ComponentName(targetContext, CardAnalysisWidget::class.java) },
                )
                val previous =
                    RemoteViews(targetContext.packageName, WidgetR.layout.widget_card_analysis).apply {
                        setOnClickPendingIntent(
                            WidgetR.id.deckNameCardAnalysis,
                            PendingIntent.getActivity(
                                targetContext,
                                0,
                                Intent(targetContext, IntentHandler::class.java),
                                PendingIntent.FLAG_IMMUTABLE,
                            ),
                        )
                    }
                host.updateAppWidget(previous)
                host.updateAppWidget(assertNotNull(rendered))
                assertNotNull(host.getChildAt(0)) as ViewGroup
            } else {
                assertNotNull(shadowOf(manager).getViewFor(id.id)) as ViewGroup
            }
        if (sdk < Build.VERSION_CODES.O) {
            // Native font metrics require a newer sandbox: disable its autosizing, then replay the
            // actual Android 7 RemoteViews actions so native fitting cannot mask a missing fallback.
            widget.allViews.filterIsInstance<TextView>().forEach {
                TextViewCompat.setAutoSizeTextTypeWithDefaults(it, TextViewCompat.AUTO_SIZE_TEXT_TYPE_NONE)
            }
            assertNotNull(rendered).reapply(targetContext, widget)
        }
        val width = widthDp.dp.toPx(targetContext)
        val height = heightDp.dp.toPx(targetContext)
        widget.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        widget.layout(0, 0, width, height)
        val counts =
            listOf(
                WidgetR.id.deckNew_card_analysis_widget,
                WidgetR.id.deckLearn_card_analysis_widget,
                WidgetR.id.deckDue_card_analysis_widget,
            )
        val background = widget.findViewById<View>(WidgetR.id.cardAnalysisDataHolder).background as GradientDrawable
        val backgroundColor = assertNotNull(background.color).defaultColor
        for ((index, viewId) in counts.withIndex()) {
            val count = widget.findViewById<TextView>(viewId)
            val contrast = ColorUtils.calculateContrast(count.currentTextColor, backgroundColor)
            assertTrue(contrast >= 4.5, "count ${count.text} contrast is $contrast")
            assertEquals(listOf("12", "3", "25")[index], count.text.toString())
            inside(widget, count)
            insideOutline(widget, count)
            val needed = count.paint.measureText(count.text.toString()) + count.compoundPaddingLeft + count.compoundPaddingRight
            assertTrue(needed <= count.width + 1, "count ${count.text} clips horizontally")
            assertTrue(
                count.layout.height <= count.height - count.compoundPaddingTop - count.compoundPaddingBottom,
                "count clips vertically",
            )
        }
        val name = widget.findViewById<TextView>(WidgetR.id.deckNameCardAnalysis)
        if (name.isVisible) {
            inside(widget, name)
            insideOutline(widget, name)
            val line = minOf(name.layout.lineCount, name.maxLines) - 1
            assertTrue(line >= 0 && name.height > name.compoundPaddingTop + name.compoundPaddingBottom, "deck name has no usable space")
            assertTrue(
                name.layout.height <= name.height - name.compoundPaddingTop - name.compoundPaddingBottom,
                "deck name clips vertically",
            )
            assertTrue(
                name.layout.getLineEnd(line) == name.text.length || name.layout.getEllipsisCount(line) > 0,
                "deck name disappears without an ellipsis",
            )
        } else {
            assertTrue(widget.contentDescription.toString().contains("Pharmacology"), "hidden name must remain accessible")
        }
        return widget
    }

    private fun checkStudyTarget(widget: ViewGroup) {
        val targets = widget.allViews.filter { it.isVisible && it.isClickable }.toList()
        assertEquals(1, targets.size, "widget must expose one full-size click target")
        val target = targets.single()
        val minimumTarget = 48.dp.toPx(targetContext)
        assertTrue(target.width >= minimumTarget && target.height >= minimumTarget, "study target is smaller than 48dp")

        target.performClick()
        val opened = assertNotNull(shadowOf(targetContext as Application).nextStartedActivity, "widget opened nothing")
        assertEquals(IntentHandler::class.java.name, opened.component?.className)
        val deck = CardAnalysisWidgetPreferences(targetContext).getSelectedDeckIdFromPreferences(AppWidgetId(1))
        assertEquals(deck, opened.getLongExtra(IntentHandler.EXTRA_DECK_ID, -1))
    }

    /** The frame clips to its rounded outline, so the glyphs must stay inside the rounded corners. */
    private fun insideOutline(
        widget: ViewGroup,
        view: TextView,
        clipTextPadding: Boolean = false,
    ) {
        val frame = widget.findViewById<ViewGroup>(android.R.id.background) ?: widget
        val radius = (frame.background as GradientDrawable).cornerRadius
        val outline =
            Path().apply {
                addRoundRect(0f, 0f, frame.width.toFloat(), frame.height.toFloat(), radius, radius, Path.Direction.CW)
            }
        val visible = Region().apply { setPath(outline, Region(0, 0, frame.width, frame.height)) }

        // Trace each line's glyphs where TextView draws them: after padding and vertical centering.
        val origin = Rect()
        frame.offsetDescendantRectToMyCoords(view, origin)
        if (clipTextPadding) {
            // TextView also clips to its padded content area. Check the glyphs themselves:
            // the Layout can be taller than this area due to unused font padding.
            visible.op(
                Region(
                    origin.left + view.compoundPaddingLeft,
                    origin.top + view.extendedPaddingTop,
                    origin.left + view.width - view.compoundPaddingRight,
                    origin.top + view.height - view.extendedPaddingBottom,
                ),
                Region.Op.INTERSECT,
            )
        }
        val layout = view.layout
        val box = view.height - view.extendedPaddingTop - view.extendedPaddingBottom
        val centered = (view.gravity and Gravity.VERTICAL_GRAVITY_MASK) == Gravity.CENTER_VERTICAL
        val offset = if (centered && layout.height < box) (box - layout.height) / 2 else 0
        val text = layout.text.toString()
        val glyphs = Path()
        for (line in 0 until layout.lineCount) {
            val linePath = Path()
            view.paint.getTextPath(
                text,
                layout.getLineStart(line),
                layout.getLineVisibleEnd(line),
                origin.left + view.compoundPaddingLeft + layout.getLineLeft(line),
                (origin.top + view.extendedPaddingTop + offset + layout.getLineBaseline(line)).toFloat(),
                linePath,
            )
            glyphs.addPath(linePath)
        }

        // Any glyph area outside the rounded outline is cut off on screen.
        val outside = Region().apply { setPath(glyphs, Region(-frame.width, -frame.height, 2 * frame.width, 2 * frame.height)) }
        outside.op(visible, Region.Op.DIFFERENCE)
        assertTrue(outside.isEmpty, "${view.text} is clipped at ${outside.bounds}")
    }

    private fun inside(
        widget: ViewGroup,
        view: View,
    ) {
        val bounds = Rect(0, 0, view.width, view.height)
        widget.offsetDescendantRectToMyCoords(view, bounds)
        assertTrue(
            bounds.left >= 0 && bounds.right <= widget.width && bounds.top >= 0 && bounds.bottom <= widget.height,
            "content $bounds escapes ${widget.width} x ${widget.height}",
        )
    }
}
