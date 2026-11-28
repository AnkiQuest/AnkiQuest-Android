// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import java.io.IOException
import java.security.MessageDigest

/** Where a download is: fetching the asset, or checking its size and SHA-256 and reading it. */
internal enum class RwkvPhase {
    DOWNLOADING,
    VERIFYING,
}

/**
 * Downloads one lock entry's asset from the official anki-rwkv release ([RwkvLock.DOWNLOAD_URL])
 * over [fetch], GitHub only, as the companion packs are fetched. A zip is checked against the lock's
 * size and SHA-256 before [store] reads it, and [store] keeps only a `.bin` the lock names.
 */
internal class RwkvDownload(
    private val store: RwkvModelStore,
    private val fetch: PackFetch,
) {
    /** Downloads [entry]'s asset; [phase] hears when the check starts. Blocking. */
    fun download(
        entry: RwkvLockEntry,
        phase: (RwkvPhase) -> Unit,
    ): RwkvImport {
        phase(RwkvPhase.DOWNLOADING)
        val bytes =
            try {
                fetch.get(RwkvLock.DOWNLOAD_URL + entry.asset, entry.zipBytes)
            } catch (e: PackRefused) {
                return RwkvImport.Rejected(RwkvImportError.DOWNLOAD_MISMATCH)
            } catch (e: IOException) {
                return RwkvImport.Rejected(RwkvImportError.NETWORK_FAILED)
            }

        phase(RwkvPhase.VERIFYING)
        if (bytes.size.toLong() != entry.zipBytes || sha256(bytes) != entry.zipSha256) {
            return RwkvImport.Rejected(RwkvImportError.DOWNLOAD_MISMATCH)
        }
        return store.import(bytes.inputStream())
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
