// SPDX-License-Identifier: AGPL-3.0-or-later

import com.android.build.api.dsl.LibraryExtension
import com.ichi2.anki.gradle.AnkiquestRules
import com.ichi2.anki.gradle.AnkiquestRulesHostTask
import com.ichi2.anki.gradle.AnkiquestRulesSource
import com.ichi2.anki.gradle.AnkiquestRulesTask
import com.ichi2.anki.gradle.rulesAttestationClasspath

// AnkiQuest: the shared rules (AnkiQuest-Server's crates/rules) through their UniFFI bindings. Only
// this module sees the bindings (org.ankiquest.rules); the app calls AnkiquestSharedRules.
plugins {
    id("ankidroid.android.library")
}

configure<LibraryExtension> {
    namespace = "com.ichi2.anki.ankiquest.rules"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }
}

// Every build takes the Kotlin bindings and the Android libraries from the newest rules-<N> release
// of AnkiQuest-Server, checked against its SHA256SUMS and its build provenance attestation, and
// records the tag in the assets; the unit tests that use this module load this machine's desktop
// library from the same release. -PankiquestRulesTag=<tag> / ANKIQUEST_RULES_TAG rebuilds with a
// given release, -PankiquestRulesDir=<dir> / ANKIQUEST_RULES_DIR takes a folder with the assets and
// their SHA256SUMS (the F-Droid build, which compiles them from source). Cached by SHA-256 in the
// Gradle user home or -PankiquestRulesCache=<dir> / ANKIQUEST_RULES_CACHE. See buildSrc
// AnkiquestRules.kt.
fun ankiquestSetting(
    property: String,
    variable: String,
) = providers.gradleProperty(property).orElse(providers.environmentVariable(variable))

val ankiquestRulesCache =
    ankiquestSetting("ankiquestRulesCache", "ANKIQUEST_RULES_CACHE")
        .map { rootProject.file(it) }
        .getOrElse(File(gradle.gradleUserHomeDir, "caches/ankiquest-rules"))

// A local value, so the mapping holds no reference to this script (configuration cache).
val ankiquestRulesDir =
    rootProject.layout.projectDirectory.let { root ->
        ankiquestSetting("ankiquestRulesDir", "ANKIQUEST_RULES_DIR").map { root.dir(it) }
    }

// The verifier runs in a class loader of its own; see buildSrc AnkiquestAttestation.kt.
val ankiquestAttestation = rulesAttestationClasspath()

fun AnkiquestRulesSource.rulesFrom() {
    localDir.set(ankiquestRulesDir)
    tag.set(ankiquestSetting("ankiquestRulesTag", "ANKIQUEST_RULES_TAG"))
    cacheDir.set(ankiquestRulesCache)
    attestationClasspath.from(ankiquestAttestation)
}

// The bindings, the library per ABI and the release record, in every variant's generated sources.
androidComponents.onVariants { variant ->
    val rules =
        tasks.register<AnkiquestRulesTask>("ankiquestRules${variant.name.replaceFirstChar { it.uppercase() }}") {
            rulesFrom()
        }
    variant.sources.kotlin?.addGeneratedSourceDirectory(rules, AnkiquestRulesTask::kotlinDir)
    variant.sources.jniLibs?.addGeneratedSourceDirectory(rules, AnkiquestRulesTask::jniLibsDir)
    variant.sources.assets?.addGeneratedSourceDirectory(rules, AnkiquestRulesTask::assetsDir)
}

// This machine's desktop library, for the unit tests of modules that use this one: they resolve
// it by the attribute below and pass its folder to JNA (AnkiquestRulesLibraryPath).
val ankiquestRulesHost =
    tasks.register<AnkiquestRulesHostTask>("ankiquestRulesHost") {
        rulesFrom()
        hostDir.set(layout.buildDirectory.dir("generated/ankiquest/rules-host"))
    }
configurations.consumable("ankiquestRulesHostElements") {
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(AnkiquestRules.HOST_USAGE))
    }
    outgoing.artifact(ankiquestRulesHost.flatMap { it.hostDir })
}

dependencies {
    // The bindings call their library through JNA: the AAR, with JNA's library per ABI.
    implementation(libs.jna) {
        artifact {
            name = "jna"
            type = "aar"
            extension = "aar"
        }
    }
}
