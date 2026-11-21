// SPDX-License-Identifier: GPL-3.0-or-later

// The `kotlin-dsl` plugin pulls the Kotlin Gradle plugin and stdlib onto the buildSrc
// classpath. Without this block Gradle looks for them only on the Gradle Plugin Portal,
// whose redirects to Maven Central sometimes fail; builds without a warm Gradle cache
// (the Nix dev shell job) then stop here. Fetch them from Maven Central directly.
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

// Expose the root project's version catalog so buildSrc can reference
// `libs.plugins.*` / `libs.versions.*` the same way the rest of the build does.
dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}
