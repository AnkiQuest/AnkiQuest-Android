// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.navigation.NavigationBarItemView
import com.ichi2.anki.EmptyApplicationCategory
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import com.ichi2.testutils.EmptyApplication
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertTrue
import com.ichi2.anki.common.android.R as CommonR

/**
 * Checks the accessibility of every `ankiquest` layout (#207): every image says what it shows or
 * is decorative, and every image button is a touch target at least 48dp wide and tall.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = EmptyApplication::class, sdk = [33])
@Category(EmptyApplicationCategory::class)
class AnkiquestLayoutAccessibilityTest : RobolectricTest(loadBackend = false) {
    @Test
    fun `every image says what it shows or is decorative`() {
        for ((layout, image) in images()) {
            val described = !image.contentDescription.isNullOrBlank()
            val decorative = image.importantForAccessibility == View.IMPORTANT_FOR_ACCESSIBILITY_NO
            // A decorative group hides its children. A Material tab names the icon and label together.
            val handledByParent =
                generateSequence(image.parent) { it.parent }
                    .filterIsInstance<View>()
                    .any { parent ->
                        parent.importantForAccessibility == View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS ||
                            (parent is NavigationBarItemView && !parent.contentDescription.isNullOrBlank())
                    }
            assertTrue(described || decorative || handledByParent, "$layout has an undescribed image (id=${image.id})")
        }
    }

    @Test
    fun `every image button is a wide enough touch target`() {
        val minimum = MIN_TOUCH_TARGET_DP * targetContext.resources.displayMetrics.density
        for ((layout, image) in images()) {
            // A button the launcher wires by its id counts too, even in a widget.
            val button = image is ImageButton || image.id == R.id.ankiquest_widget_refresh
            if (!button) {
                continue
            }
            // wrap_content and match_parent are decided by what the view measures to, so the
            // inflated size is what counts, with minWidth and minHeight as the floor a layout
            // may set instead.
            val params = image.layoutParams
            image.measure(sizeSpec(params.width), sizeSpec(params.height))
            assertTrue(
                maxOf(image.measuredWidth, image.minimumWidth) >= minimum,
                "$layout is not ${MIN_TOUCH_TARGET_DP}dp wide: ${image.measuredWidth} px",
            )
            assertTrue(
                maxOf(image.measuredHeight, image.minimumHeight) >= minimum,
                "$layout is not ${MIN_TOUCH_TARGET_DP}dp tall: ${image.measuredHeight} px",
            )
        }
    }

    private fun sizeSpec(size: Int): Int =
        if (size >= 0) {
            View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
        } else {
            View.MeasureSpec.UNSPECIFIED
        }

    /** Every image in every layout AnkiQuest defines, with the layout's resource entry name. */
    private fun images(): List<Pair<String, ImageView>> {
        val context = ContextThemeWrapper(targetContext, CommonR.style.Theme_AnkiDroid)
        val inflater = LayoutInflater.from(context)
        val resources = context.resources
        return R.layout::class.java.fields
            .map { it.getInt(null) }
            .filter { id -> resources.getResourceEntryName(id).contains("ankiquest") }
            .flatMap { id ->
                val layout = resources.getResourceEntryName(id)
                val root = inflater.inflate(id, LinearLayout(context), true)
                views(root)
                    .filterIsInstance<ImageView>()
                    .map { layout to it }
            }
    }

    private fun views(view: View): List<View> {
        val found = mutableListOf(view)
        (view as? ViewGroup)?.let { group ->
            for (index in 0 until group.childCount) {
                found.addAll(views(group.getChildAt(index)))
            }
        }
        return found
    }

    private companion object {
        const val MIN_TOUCH_TARGET_DP = 48
    }
}
