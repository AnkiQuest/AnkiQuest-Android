// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.graphics.applyCanvas
import androidx.core.graphics.createBitmap
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import com.ichi2.testutils.EmptyApplication
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.test.assertTrue

/** A changed screenshot must not change the screenshots taken after it in the same test JVM (#364). */
@RunWith(RobolectricTestRunner::class)
@Config(application = EmptyApplication::class, sdk = [33])
@Category(EmptyApplicationCategory::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenshotOptionsTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    @OptIn(ExperimentalRoborazziApi::class)
    fun `a comparison image leaves later text unchanged`() {
        val before = text()

        // A changed screenshot: Roborazzi draws a comparison image for it.
        val reference = File(folder.root, "reference.png")
        solid(Color.WHITE).captureRoboImage(reference, screenshotOptions(folder.root.path).copy(taskType = RoborazziTaskType.Record))
        val compare = screenshotOptions(folder.newFolder("diffs").path).copy(taskType = RoborazziTaskType.Compare)
        solid(Color.BLACK).captureRoboImage(reference, compare)
        assertTrue(folder.root.walk().any { it.name.endsWith("_compare.png") }, "no comparison image was drawn")

        assertTrue(before.sameAs(text()), "text drawn after a comparison image differs")
    }

    /** Digits and words in the streak widget's number font, where #364's differences showed. */
    private fun text(): Bitmap =
        createBitmap(TEXT_WIDTH, TEXT_HEIGHT).applyCanvas {
            drawColor(Color.WHITE)
            val paint =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
                    textSize = TEXT_SIZE
                    color = Color.DKGRAY
                }
            drawText("365 1,000 days", 8f, TEXT_SIZE, paint)
            paint.typeface = Typeface.DEFAULT
            drawText("Streak safe today", 8f, TEXT_SIZE * 2, paint)
        }

    private fun solid(color: Int): Bitmap = createBitmap(SOLID_EDGE, SOLID_EDGE).applyCanvas { drawColor(color) }

    private companion object {
        const val TEXT_WIDTH = 600
        const val TEXT_HEIGHT = 160
        const val TEXT_SIZE = 56f
        const val SOLID_EDGE = 32
    }
}
