// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.gradle

import org.gradle.api.GradleException
import org.gradle.api.Project

// Upstream AnkiDroid's backend group. AnkiQuest-Android builds only on org.ankiquest's backend
// artifacts, published from AnkiQuest-Android-Backend (hill, 2026-11-21).
private const val UPSTREAM_BACKEND_GROUP = "io.github.david-allison"

/**
 * Fails dependency resolution in every configuration of this project when an upstream backend
 * artifact is requested, directly or transitively. `.github/tests/upstream-backend.test.cjs`
 * checks the committed files.
 */
fun Project.rejectUpstreamBackend() {
    configurations.configureEach {
        val configuration = name
        resolutionStrategy.eachDependency {
            if (requested.group != UPSTREAM_BACKEND_GROUP) return@eachDependency
            throw GradleException(
                "$configuration requests ${requested.group}:${requested.name}; " +
                    "use the org.ankiquest backend artifacts (ankiBackend in gradle/libs.versions.toml)",
            )
        }
    }
}
