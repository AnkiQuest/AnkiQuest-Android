// SPDX-License-Identifier: AGPL-3.0-or-later

// Writes the companions' drawables from the pinned AnkiQuest-Art release
// (ankiquest-art.json) for every variant: no submodule, no exporter build.
// Apply after the Android app or library plugin, in the module that owns the companion art.
import com.android.build.api.variant.AndroidComponentsExtension
import com.ichi2.anki.gradle.ExportAnkiquestArt

val pinFile = isolated.rootProject.projectDirectory.file("ankiquest-art.json")

extensions.getByType(AndroidComponentsExtension::class.java).onVariants { variant ->
    val name = variant.name.replaceFirstChar { it.uppercase() }
    val export =
        tasks.register<ExportAnkiquestArt>("export${name}AnkiquestArt") {
            group = "build"
            description = "Writes the companion drawables of $name from the pinned AnkiQuest-Art release."
            pin.set(pinFile)
            downloadDir.set(layout.buildDirectory.dir("ankiquest-art/download"))
        }
    variant.sources.res?.addGeneratedSourceDirectory(export, ExportAnkiquestArt::outputDir)
}
