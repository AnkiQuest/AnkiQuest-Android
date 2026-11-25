// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import java.io.IOException
import java.security.MessageDigest

/**
 * Downloads one size's models from the official anki-rwkv release ([RwkvLock.DOWNLOAD_URL]) over
 * [fetch], GitHub only, as the companion packs are fetched. A zip is checked against the lock's
 * size and SHA-256 before [store] reads it, and [store] keeps only a `.bin` the lock names.
 */
internal class RwkvDownload(
    private val store: RwkvModelStore,
    private val fetch: PackFetch,
    private val lock: List<RwkvLockEntry> = RwkvLock.ENTRIES,
) {
    /** The assets [download] fetches for [size], two-head first: it is the one the engine needs. */
    fun assets(size: RwkvSize): List<RwkvLockEntry> = lock.filter { it.size == size }.sortedBy { it.head.ordinal }

    /** Bytes [download] fetches for [size], for the consent dialog. */
    fun bytes(size: RwkvSize): Long = assets(size).sumOf { it.zipBytes }

    /** Downloads [size]'s assets one by one; [progress] gets the number done and the total before each. Blocking. */
    fun download(
        size: RwkvSize,
        progress: (done: Int, total: Int) -> Unit,
    ): List<RwkvImport> {
        val assets = assets(size)
        return assets.mapIndexed { index, entry ->
            progress(index, assets.size)
            fetchOne(entry)
        }
    }

    private fun fetchOne(entry: RwkvLockEntry): RwkvImport {
        val bytes =
            try {
                fetch.get(RwkvLock.DOWNLOAD_URL + entry.asset, entry.zipBytes)
            } catch (e: PackRefused) {
                return RwkvImport.Rejected(RwkvImportError.DOWNLOAD_MISMATCH)
            } catch (e: IOException) {
                return RwkvImport.Rejected(RwkvImportError.NETWORK_FAILED)
            }
        if (bytes.size.toLong() != entry.zipBytes || sha256(bytes) != entry.zipSha256) {
            return RwkvImport.Rejected(RwkvImportError.DOWNLOAD_MISMATCH)
        }
        return store.import(bytes.inputStream())
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
