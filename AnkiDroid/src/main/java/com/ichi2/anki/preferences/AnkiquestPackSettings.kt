// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.preferences

import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import com.ichi2.anki.R
import com.ichi2.anki.ankiquest.AnkiquestCapabilities
import com.ichi2.anki.ankiquest.AnkiquestPackSharing
import com.ichi2.anki.ankiquest.AnkiquestPacks
import com.ichi2.anki.ankiquest.HomeAccount
import com.ichi2.anki.ankiquest.InstalledPack
import com.ichi2.anki.ankiquest.PackOrigin
import com.ichi2.anki.ankiquest.PackRefused
import com.ichi2.anki.ankiquest.PackUpdate
import com.ichi2.anki.ankiquest.PackVisibility
import com.ichi2.anki.ankiquest.SharedPack
import com.ichi2.utils.openInputStreamSafe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * The companion screen's pack entries: "Download companions" fetches the packs of the latest
 * AnkiQuest-Art release, "Import companion" adds a pack the player picks, and "Show a companion to
 * friends" uploads an imported one (AnkiQuest-Server docs/companion-packs.md). Create it while
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
        shareEntry().setOnPreferenceClickListener {
            chooseShared()
            true
        }
        showInstalled()
        showShared()
    }

    private fun shareEntry() = fragment.requirePreference<Preference>(R.string.ankiquest_packs_share_key)

    /** The pack friends see now, as last read from the server. */
    private var shown: SharedPack? = null

    /** Shows the share entry on servers that keep packs, with the pack friends see. */
    private fun showShared() {
        val account = HomeAccount.current() ?: return
        if (!AnkiquestCapabilities.has(account, AnkiquestPackSharing.FEATURE)) {
            return
        }
        shareEntry().isVisible = true
        fragment.lifecycleScope.launch {
            val mine =
                try {
                    withContext(Dispatchers.IO) { AnkiquestPackSharing.mine(account) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    return@launch
                }
            shown = mine.firstOrNull { it.visibility == PackVisibility.FRIENDS }
            if (fragment.isAdded) {
                shareEntry().summary =
                    shown?.let { fragment.getString(R.string.ankiquest_packs_share_shown, it.name) }
                        ?: fragment.getString(R.string.ankiquest_packs_share_none)
            }
        }
    }

    /** Lets the player pick an imported companion for friends, or none. */
    private fun chooseShared() {
        val imported =
            AnkiquestPacks
                .library(fragment.requireContext())
                .companions()
                .filter { it.origin == PackOrigin.IMPORTED }
        if (imported.isEmpty()) {
            tell(fragment.getString(R.string.ankiquest_packs_share_no_imports))
            return
        }
        val labels = imported.map { it.info.name } + fragment.getString(R.string.ankiquest_packs_share_stop)
        AlertDialog
            .Builder(fragment.requireContext())
            .setTitle(R.string.ankiquest_packs_share)
            .setItems(labels.toTypedArray()) { _, which ->
                imported.getOrNull(which)?.let(::confirmShare) ?: stopSharing()
            }.setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Names the licence and credit before anything leaves the device. */
    private fun confirmShare(pack: InstalledPack) {
        val info = pack.info
        AlertDialog
            .Builder(fragment.requireContext())
            .setTitle(R.string.ankiquest_packs_share)
            .setMessage(fragment.getString(R.string.ankiquest_packs_share_confirm, info.name, info.licence, info.credit))
            .setPositiveButton(R.string.ankiquest_packs_share_upload) { _, _ ->
                changeShared { account -> AnkiquestPackSharing.upload(account, pack, PackVisibility.FRIENDS) }
            }.setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun stopSharing() {
        val current = shown ?: return
        changeShared { account -> AnkiquestPackSharing.change(account, current.sha256, PackVisibility.OWNER) }
    }

    /** Runs [change] for the signed-in account off the main thread, then shows the result. */
    private fun changeShared(change: (HomeAccount) -> SharedPack) {
        val account = HomeAccount.current() ?: return
        fragment.lifecycleScope.launch {
            val failure =
                try {
                    withContext(Dispatchers.IO) { change(account) }
                    null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    e.message ?: e.javaClass.simpleName
                }
            if (!fragment.isAdded) {
                return@launch
            }
            failure?.let { tell(fragment.getString(R.string.ankiquest_packs_share_failed, it)) }
            showShared()
        }
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
