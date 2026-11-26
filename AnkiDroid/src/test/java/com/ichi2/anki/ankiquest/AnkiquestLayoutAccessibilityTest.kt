// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.EmptyApplicationCategory
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import com.ichi2.testutils.EmptyApplication
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

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
            val described = image.contentDescription != null
            val decorative = image.importantForAccessibility == View.IMPORTANT_FOR_ACCESSIBILITY_NO
            assertTrue(described || decorative, "$layout shows something undescribed")
        }
    }

    @Test
    fun `every image button is a wide enough touch target`() {
        val minimum = MIN_TOUCH_TARGET_DP * targetContext.resources.displayMetrics.density
        for ((layout, image) in images()) {
            // A button the launcher wires by its id counts too, even in a widget.
            val button = image is ImageButton || image.id == R.id.ankiquest_widget_refresh
            if (!button) continue
            for (name in listOf("wide", "tall")) {
                val size = if (name == "wide") image.layoutParams.width else image.layoutParams.height
                assertTrue(size <= 0 || size >= minimum, "$layout is not ${MIN_TOUCH_TARGET_DP}dp $name: $size px")
            }
        }
    }

    /** Every image in every layout AnkiQuest defines, with the layout's resource entry name. */
    private fun images(): List<Pair<String, ImageView>> {
        val context = targetContext
        val inflater = LayoutInflater.from(context)
        return R.layout::class.java.fields
            .map { it.getInt(null) }
            .filter { id -> context.resources.getResourceName(id).substringAfterLast('/').contains("ankiquest") }
            .flatMap { id ->
                val layout = context.resources.getResourceEntryName(id)
                val root = inflater.inflate(id, LinearLayout(context), false)
                views(root).filterIsInstance<ImageView>().map { layout to it }
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
