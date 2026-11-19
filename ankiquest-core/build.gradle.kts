// SPDX-License-Identifier: AGPL-3.0-only

import com.android.build.api.dsl.LibraryExtension

plugins {
    id("ankidroid.android.library")
}

// AnkiQuest's server client and the rules every AnkiQuest client shares. It knows nothing of
// AnkiDroid: study data comes in through StudySource.
configure<LibraryExtension> {
    namespace = "com.ichi2.anki.ankiquest.core"
}

dependencies {
    implementation(project(":common"))
    implementation(project(":common:android"))

    // Requests, answers and addresses appear in the client's public calls.
    api(libs.okhttp)
    api(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.core)
    implementation(libs.jakewharton.timber)

    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.junit)
    testImplementation(libs.json)
    testImplementation(libs.junit.platform.launcher)
    testImplementation(libs.junit.vintage.engine)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.robolectric)
}
