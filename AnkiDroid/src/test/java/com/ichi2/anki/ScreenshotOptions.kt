// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki

import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.provideRoborazziContext

/** The Roborazzi options of every screenshot test, writing comparison images to [diffDirectory]. */
@OptIn(ExperimentalRoborazziApi::class)
fun screenshotOptions(diffDirectory: String): RoborazziOptions {
    val options = provideRoborazziContext().options
    return options.copy(compareOptions = options.compareOptions.copy(outputDirectoryPath = diffDirectory))
}
