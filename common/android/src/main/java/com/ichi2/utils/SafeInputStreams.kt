// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.utils

import android.content.ContentResolver
import android.net.Uri
import java.io.FileNotFoundException
import java.io.InputStream
import java.nio.file.FileSystems

/**
 * Open a stream on to the content associated with a content URI.
 * If there is no data associated with the URI, [FileNotFoundException] is thrown.
 * If the path is to the internal `/data` directory, a [SecurityException] is thrown.
 *
 * ##### Accepts the following URI schemes:
 *
 * * content ({@link #SCHEME_CONTENT})
 * * android.resource ({@link #SCHEME_ANDROID_RESOURCE})
 * * file ({@link #SCHEME_FILE})
 *
 * See [openAssetFileDescriptor][ContentResolver.openAssetFileDescriptor] for more information
 * on these schemes.
 *
 * @param uri The desired URI.
 * @return [InputStream] or `null` if the provider recently crashed.
 *
 * @throws FileNotFoundException if the provided URI could not be opened.
 * @throws SecurityException if a `/data` path is accessed
 *
 * @see ContentResolver.openAssetFileDescriptor
 *
 * Fix for [java/android/unsafe-content-uri-resolution][https://codeql.github.com/codeql-query-help/java/java-android-unsafe-content-uri-resolution/]
 */
fun ContentResolver.openInputStreamSafe(uri: Uri): InputStream? {
    val normalized = FileSystems.getDefault().getPath(uri.path).normalize()
    if (normalized.startsWith("/data")) {
        throw SecurityException("java/android/unsafe-content-uri-resolution")
    }
    return openInputStream(uri)
}
