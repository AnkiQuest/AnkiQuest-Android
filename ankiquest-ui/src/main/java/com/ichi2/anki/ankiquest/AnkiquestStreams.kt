// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankiquest

import android.content.ContentResolver
import android.net.Uri
import java.io.FileNotFoundException
import java.io.InputStream
import java.nio.file.FileSystems

/**
 * A copy of the app's `com.ichi2.utils.openInputStreamSafe`, which this module cannot reach.
 * It stays here, not in `:common:android`, because that upstream module has no desugaring for
 * `java.nio.file` below API 26.
 *
 * Opens a stream on to the content of [uri].
 *
 * @return [InputStream] or `null` if the provider recently crashed.
 * @throws FileNotFoundException if [uri] could not be opened.
 * @throws SecurityException if [uri] points into `/data`.
 *
 * Fix for [java/android/unsafe-content-uri-resolution][https://codeql.github.com/codeql-query-help/java/java-android-unsafe-content-uri-resolution/]
 */
internal fun ContentResolver.openInputStreamSafe(uri: Uri): InputStream? {
    val normalized = FileSystems.getDefault().getPath(uri.path).normalize()
    if (normalized.startsWith("/data")) {
        throw SecurityException("java/android/unsafe-content-uri-resolution")
    }
    return openInputStream(uri)
}
