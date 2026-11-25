// SPDX-License-Identifier: GPL-3.0-or-later

// Apply after the Android app or library plugin:
// plugins {
//     id("ankidroid.android.library") // or "ankidroid.android.app"
//     id("ankidroid.android.compose")
// }

import com.android.build.api.dsl.CommonExtension
import com.android.build.api.dsl.TestedExtension
import com.android.build.api.variant.AndroidComponentsExtension
import com.ichi2.anki.gradle.libsLibrary
import com.ichi2.anki.gradle.sortComposeMapping

plugins {
    id("org.jetbrains.kotlin.plugin.compose")
}

extensions.configure<CommonExtension> {
    buildFeatures.compose = true
}

dependencies {
    "implementation"(platform(libsLibrary("androidx-compose-bom")))
    "implementation"(libsLibrary("androidx-compose-ui"))
    "implementation"(libsLibrary("androidx-compose-ui-tooling-preview"))
    "implementation"(libsLibrary("androidx-compose-material3"))
    "debugImplementation"(libsLibrary("androidx-compose-ui-tooling"))
    "testImplementation"(libsLibrary("androidx-compose-ui-test-junit4"))
    "androidTestImplementation"(libsLibrary("androidx-compose-ui-test-junit4"))
    // Needed for createComposeRule(), but not for createAndroidComposeRule<YourActivity>().
    "debugImplementation"(libsLibrary("androidx-compose-ui-test-manifest"))
}

// The Compose Gradle plugin's produce<Variant>ComposeMapping task reads class directories in
// file system order, so two builds of one commit write the ComposeStackTrace block of
// mapping.txt and the AAB's proguard.map in different orders
// (https://youtrack.jetbrains.com/issue/KT-91927). Sort the block once its worker has finished,
// before merge<Variant>ComposeMapping hashes and appends it. The lines stay the same, so retrace
// is unaffected. Remove this when a Kotlin release fixes KT-91927.
tasks.named { it.startsWith("produce") && it.endsWith("ComposeMapping") }.configureEach {
    val mappings = outputs.files.filter { it.name == "compose-mapping.txt" }
    doLast {
        mappings.forEach { it.writeText(sortComposeMapping(it.readText())) }
    }
}

// The Compose compiler also runs on test fixtures without @Composable code.
// Wait until the module has configured whether it enables test fixtures, and keep
// the runtime compile-only so it is not shipped in the test fixtures output.
extensions.getByType(AndroidComponentsExtension::class.java).finalizeDsl { androidDsl ->
    if (androidDsl is TestedExtension && androidDsl.testFixtures.enable) {
        dependencies {
            "testFixturesCompileOnly"(platform(libsLibrary("androidx-compose-bom")))
            "testFixturesCompileOnly"(libsLibrary("androidx-compose-runtime"))
        }
    }
}
