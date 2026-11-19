// SPDX-License-Identifier: AGPL-3.0-only

import com.android.build.api.dsl.LibraryExtension

plugins {
    id("ankidroid.android.library")
}

// AnkiQuest's screens, widgets, notifications and art, for any app that hosts them. It knows
// nothing of AnkiDroid: the app gives it navigation and study data through AnkiquestHost, and the
// views reach the server and the rules only through :ankiquest-core.
configure<LibraryExtension> {
    namespace = "com.ichi2.anki.ankiquest.ui"
    testOptions.unitTests.isIncludeAndroidResources = true
}

dependencies {
    // The views' public calls take core's accounts and rules.
    api(project(":ankiquest-core"))
    implementation(project(":common"))
    implementation(project(":common:android"))

    implementation(libs.androidx.activity)
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core)
    implementation(libs.androidx.core.remoteviews)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.viewpager2)
    implementation(libs.androidx.work.runtime)
    implementation(libs.google.material)
    implementation(libs.jakewharton.timber)

    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.junit)
    testImplementation(libs.junit.platform.launcher)
    testImplementation(libs.junit.vintage.engine)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.robolectric)
}
