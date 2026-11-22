// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.ichi2.anki.ScreenshotTest
import com.ichi2.anki.screenshotOptions
import org.junit.Test
import org.robolectric.annotation.Config

@Config(sdk = [33])
class AnkiquestStreakScreenshotTest : ScreenshotTest() {
    @Test
    @OptIn(ExperimentalRoborazziApi::class)
    fun tiers() {
        // Temporary diagnosis for #364.
        java.io.File("/tmp/diag364-probe.txt").appendText(
            "thread=${Thread.currentThread().name} subnormal=${tiny * half != 0.0}\n",
        )
        val directory = "build/outputs/roborazzi/${javaClass.simpleName}"
        for ((size, width, height) in AnkiquestStreakTiers.sizes) {
            AnkiquestStreakTiers.grid(targetContext, size, width, height).captureRoboImage(
                filePath = "$directory/${fileNamePrefix}${size.name.lowercase()}.png",
                roborazziOptions = screenshotOptions("$directory/diffs"),
            )
        }
    }

    @Volatile private var tiny = java.lang.Double.MIN_NORMAL

    @Volatile private var half = 0.5
}
