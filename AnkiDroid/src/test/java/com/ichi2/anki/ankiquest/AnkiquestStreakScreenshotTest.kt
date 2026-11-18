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
        val directory = "build/outputs/roborazzi/${javaClass.simpleName}"
        for ((size, width, height) in AnkiquestStreakTiers.sizes) {
            AnkiquestStreakTiers.grid(targetContext, size, width, height).captureRoboImage(
                filePath = "$directory/${fileNamePrefix}${size.name.lowercase()}.png",
                roborazziOptions = screenshotOptions("$directory/diffs"),
            )
        }
    }
}
