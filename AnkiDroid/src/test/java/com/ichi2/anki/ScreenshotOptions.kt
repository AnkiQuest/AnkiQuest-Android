// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki

import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziOptions.CompareOptions.ComparisonStyle
import com.github.takahirom.roborazzi.provideRoborazziContext

/**
 * The Roborazzi options of every screenshot test, writing comparison images to [diffDirectory].
 *
 * Comparison images carry no "Reference", "Diff" and "New" labels: Roborazzi draws them with
 * Java2D fonts, and loading those changes how Robolectric draws text for the rest of the test JVM.
 * One changed screenshot then changed the text in every later screenshot of the same fork (#364).
 */
@OptIn(ExperimentalRoborazziApi::class)
fun screenshotOptions(diffDirectory: String): RoborazziOptions {
    val options = provideRoborazziContext().options
    val compare =
        options.compareOptions.copy(
            outputDirectoryPath = diffDirectory,
            comparisonStyle = ComparisonStyle.Grid(hasLabel = false),
        )
    return options.copy(compareOptions = compare)
}
