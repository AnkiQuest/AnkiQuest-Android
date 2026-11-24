// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * unpacks the companions' drawables from the pinned AnkiQuest-Art release:
 * `ankiquest-art.json` names the release tag and the SHA-256 of `android-drawables.zip`,
 * whose `drawable-nodpi/<name>.webp` entries and licences become the generated res
 * directory. Art is never a submodule of this repository.
 *
 * The pin selects the bytes, so the same pin gives the same drawables on every run.
 */
abstract class ExportAnkiquestArt : DefaultTask() {
    /** `ankiquest-art.json`, with the release tag and the zip hashes. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val pin: RegularFileProperty

    /** Where the download is kept between runs. */
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val downloadDir: DirectoryProperty

    @TaskAction
    fun export() {
        val pinText = pin.get().asFile.readText()
        val tag = pinText.regexGroup("\"tag\"\\s*:\\s*\"([^\"]+)\"") ?: throw GradleException("ankiquest-art.json has no tag")
        val sha = pinText.regexGroup("\"android-drawables.zip\"\\s*:\\s*\"([0-9a-f]{64})\"")
            ?: throw GradleException("ankiquest-art.json has no android-drawables.zip hash")
        val url = "https://github.com/AnkiQuest/AnkiQuest-Art/releases/download/$tag/android-drawables.zip"

        val downloads = downloadDir.get().asFile.apply { mkdirs() }
        val zip = downloads.resolve("android-drawables.zip")
        if (!zip.isFile || zip.sha256() != sha) {
            downloads.listFiles()?.forEach { it.delete() }
            val request = HttpRequest.newBuilder(URI.create(url)).GET().build()
            val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofFile(zip.toPath()))
            if (response.statusCode() != 200) {
                throw GradleException("Downloading $url answered ${response.statusCode()}")
            }
            if (zip.sha256() != sha) {
                zip.delete()
                throw GradleException("android-drawables.zip of $tag: SHA-256 ${zip.sha256()}, the pin expects $sha")
            }
        }

        // Start from an empty directory, so a removed drawable leaves no stale entry.
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        ZipInputStream(zip.inputStream().buffered()).use { stream ->
            while (true) {
                val entry = stream.nextEntry ?: break
                if (entry.isDirectory) continue
                if (entry.name.contains("..")) throw GradleException("bad entry in android-drawables.zip: ${entry.name}")
                val target = out.resolve(entry.name)
                if (!target.canonicalFile.startsWith(out.canonicalFile)) {
                    throw GradleException("bad entry in android-drawables.zip: ${entry.name}")
                }
                target.parentFile?.mkdirs()
                target.outputStream().use { stream.copyTo(it) }
            }
        }
    }

    private fun String.regexGroup(pattern: String): String? =
        Regex(pattern).find(this)?.groupValues?.get(1)

    private fun java.io.File.sha256(): String =
        MessageDigest.getInstance("SHA-256").digest(readBytes()).joinToString("") { "%02x".format(it) }
}
