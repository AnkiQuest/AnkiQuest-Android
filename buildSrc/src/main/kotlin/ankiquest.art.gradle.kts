// SPDX-License-Identifier: AGPL-3.0-or-later

// Generates the companions' drawables from the AnkiQuest-Art submodule (`art/`) for every variant.
// Apply after the Android app or library plugin, in the module that owns the companion art.
import com.android.build.api.variant.AndroidComponentsExtension
import com.ichi2.anki.gradle.ExportAnkiquestArt

val artRoot = isolated.rootProject.projectDirectory.dir("art")

// The masters (never archive/) and the exporter's sources; not the reviews in docs/ or cargo output.
val artSources =
    files(
        fileTree(artRoot) {
            include(ExportAnkiquestArt.MANIFEST, "**/*.png", "tools/**/*.rs", "tools/**/Cargo.toml", "tools/**/Cargo.lock")
            exclude("archive/**", "docs/**", "**/target/**")
        },
    )

extensions.getByType(AndroidComponentsExtension::class.java).onVariants { variant ->
    val name = variant.name.replaceFirstChar { it.uppercase() }
    val export =
        tasks.register<ExportAnkiquestArt>("export${name}AnkiquestArt") {
            group = "build"
            description = "Writes the companion drawables of $name from the AnkiQuest-Art masters."
            art.set(artRoot)
            sources.from(artSources)
            cargo.convention("cargo")
            cargoTarget.set(layout.buildDirectory.dir("ankiquest-art/cargo"))
        }
    variant.sources.res?.addGeneratedSourceDirectory(export, ExportAnkiquestArt::outputDir)
}
