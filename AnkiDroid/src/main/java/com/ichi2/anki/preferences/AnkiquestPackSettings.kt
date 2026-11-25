// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.preferences

import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import com.ichi2.anki.R
import com.ichi2.anki.ankiquest.AnkiquestPacks
import com.ichi2.anki.ankiquest.PackRefused
import com.ichi2.anki.ankiquest.PackUpdate
import com.ichi2.utils.openInputStreamSafe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * The companion screen's pack entries: "Download companions" fetches the packs of the latest
 * AnkiQuest-Art release, "Import companion" adds a pack the player picks. Create it while
 * [fragment] is being created, since it registers for the file picker's result. [changed] runs
 * after a download or import, so the screen shows the companions now installed.
 */
internal class AnkiquestPackSettings(
    private val fragment: SettingsFragment,
    private val changed: () -> Unit,
) {
    /** The state of the download entry. */
    private enum class Download { IDLE, RUNNING }

    private var download = Download.IDLE

    private val picker =
        fragment.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { import(it) }
        }

    /** Hooks up both entries; call from `initSubscreen`. */
    fun attach() {
        downloadEntry().setOnPreferenceClickListener {
            update()
            true
        }
        importEntry().setOnPreferenceClickListener {
            picker.launch(arrayOf(ZIP, ANY_ZIP))
            true
        }
        showInstalled()
    }

    private fun downloadEntry() = fragment.requirePreference<Preference>(R.string.ankiquest_packs_download_key)

    private fun importEntry() = fragment.requirePreference<Preference>(R.string.ankiquest_packs_import_key)

    /** How many companions are installed, under the download entry. */
    private fun showInstalled() {
        val count = AnkiquestPacks.library(fragment.requireContext()).companions().size
        downloadEntry().summary =
            when (count) {
                0 -> fragment.getString(R.string.ankiquest_packs_none)
                else -> fragment.resources.getQuantityString(R.plurals.ankiquest_packs_installed, count, count)
            }
    }

    /** Downloads newer packs, one check at a time. */
    private fun update() {
        if (download == Download.RUNNING) {
            return
        }
        download = Download.RUNNING
        downloadEntry().summary = fragment.getString(R.string.ankiquest_packs_downloading)
        val context = fragment.requireContext().applicationContext
        fragment.lifecycleScope.launch {
            val message =
                try {
                    when (val result = withContext(Dispatchers.IO) { AnkiquestPacks.download(context).update() }) {
                        PackUpdate.UpToDate -> fragment.getString(R.string.ankiquest_packs_up_to_date)
                        is PackUpdate.Updated -> fragment.getString(R.string.ankiquest_packs_downloaded, names(result.ids))
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: PackRefused) {
                    fragment.getString(R.string.ankiquest_packs_download_failed, e.message)
                } catch (e: IOException) {
                    fragment.getString(R.string.ankiquest_packs_download_failed, e.message ?: e.javaClass.simpleName)
                } finally {
                    download = Download.IDLE
                }
            if (fragment.isAdded) {
                showInstalled()
                changed()
                tell(message)
            }
        }
    }

    /** Checks and installs the pack at [uri]. */
    private fun import(uri: Uri) {
        val context = fragment.requireContext().applicationContext
        fragment.lifecycleScope.launch {
            val message =
                try {
                    val info =
                        withContext(Dispatchers.IO) {
                            val input = context.contentResolver.openInputStreamSafe(uri) ?: throw IOException("cannot open $uri")
                            input.use { AnkiquestPacks.library(context).import(it) }
                        }
                    fragment.getString(R.string.ankiquest_packs_imported, info.name)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: PackRefused) {
                    fragment.getString(R.string.ankiquest_packs_refused, e.message)
                } catch (e: IOException) {
                    fragment.getString(R.string.ankiquest_packs_refused, e.message ?: e.javaClass.simpleName)
                } catch (e: SecurityException) {
                    // openInputStreamSafe refuses files inside app data; the picker should never offer them.
                    fragment.getString(R.string.ankiquest_packs_refused, e.message ?: e.javaClass.simpleName)
                }
            if (fragment.isAdded) {
                showInstalled()
                changed()
                tell(message)
            }
        }
    }

    /** The shown names of the companions [ids]. */
    private fun names(ids: List<String>): String {
        val library = AnkiquestPacks.library(fragment.requireContext())
        return ids.joinToString(", ") { library.companion(it)?.info?.name ?: it }
    }

    private fun tell(message: String) {
        AlertDialog
            .Builder(fragment.requireContext())
            .setTitle(R.string.ankiquest_companion_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    companion object {
        /** MIME types the picker offers: zip, and the generic type some file managers give zips. */
        private const val ZIP = "application/zip"
        private const val ANY_ZIP = "application/octet-stream"
    }
}
