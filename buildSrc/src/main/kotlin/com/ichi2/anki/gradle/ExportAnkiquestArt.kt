// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import javax.inject.Inject

/**
 * Writes the companions' drawables from the masters in the AnkiQuest-Art submodule, with its
 * `tools/art-export` (`android <res-dir>`): `<res-dir>/drawable-nodpi/<name>.webp`.
 *
 * The same masters give the same bytes on every run; Linux output is canonical.
 */
abstract class ExportAnkiquestArt
    @Inject
    constructor(
        private val exec: ExecOperations,
    ) : DefaultTask() {
        /** The AnkiQuest-Art checkout. */
        @get:Internal
        abstract val art: DirectoryProperty

        /** `manifest.json`, the masters and the exporter's sources. */
        @get:InputFiles
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val sources: ConfigurableFileCollection

        /** The cargo executable. */
        @get:Input
        abstract val cargo: Property<String>

        /** Cargo's target directory, kept out of the submodule and shared by every variant (cargo locks it). */
        @get:Internal
        abstract val cargoTarget: DirectoryProperty

        /** The generated res directory. */
        @get:OutputDirectory
        abstract val outputDir: DirectoryProperty

        @TaskAction
        fun export() {
            val root = art.get().asFile
            if (!root.resolve(MANIFEST).isFile) {
                throw GradleException("$root has no $MANIFEST: run `git submodule update --init`")
            }

            // Start from an empty directory, so a removed export leaves no stale drawable.
            val out = outputDir.get().asFile
            out.deleteRecursively()
            out.mkdirs()

            exec.exec {
                commandLine(
                    cargo.get(),
                    "run",
                    "--locked",
                    "--release",
                    "--quiet",
                    "--manifest-path",
                    root.resolve(EXPORTER).path,
                    "--",
                    "android",
                    out.path,
                )
                environment("CARGO_TARGET_DIR", cargoTarget.get().asFile.path)
            }
        }

        companion object {
            const val MANIFEST = "manifest.json"
            const val EXPORTER = "tools/art-export/Cargo.toml"
        }
    }
