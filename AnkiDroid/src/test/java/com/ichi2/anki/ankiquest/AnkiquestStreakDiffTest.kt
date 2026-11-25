// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.applyCanvas
import androidx.core.graphics.createBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.screenshotOptions
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.test.assertTrue

/**
 * A changed screenshot must not change the screenshots taken after it in the same test JVM:
 * the streak widget screenshots changed after any other screenshot changed (#364).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], qualifiers = "w411dp-h914dp-normal-long-notround-any-420dpi-keyshidden-nonav")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AnkiquestStreakDiffTest : RobolectricTest() {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    @OptIn(ExperimentalRoborazziApi::class)
    fun `a changed screenshot leaves the widget unchanged`() {
        val before = grids()

        // A changed screenshot: Roborazzi draws a comparison image for it.
        val reference = File(folder.root, "reference.png")
        solid(Color.WHITE).captureRoboImage(reference, screenshotOptions(folder.root.path).copy(taskType = RoborazziTaskType.Record))
        val compare = screenshotOptions(folder.newFolder("diffs").path).copy(taskType = RoborazziTaskType.Compare)
        solid(Color.BLACK).captureRoboImage(reference, compare)
        assertTrue(folder.root.walk().any { it.name.endsWith("_compare.png") }, "no comparison image was drawn")

        val after = grids()
        for ((size, drawn) in before) {
            assertTrue(drawn.sameAs(after.getValue(size)), "$size widgets differ after a comparison image")
        }
    }

    private fun grids(): Map<AnkiquestStreakWidget.Size, Bitmap> =
        AnkiquestStreakTiers.sizes.associate { (size, width, height) ->
            size to AnkiquestStreakTiers.grid(targetContext, size, width, height)
        }

    private fun solid(color: Int): Bitmap = createBitmap(SOLID_EDGE, SOLID_EDGE).applyCanvas { drawColor(color) }

    private companion object {
        const val SOLID_EDGE = 32
    }
}
