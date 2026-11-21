// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.os.Build
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.BuildConfig
import com.ichi2.anki.DeckPicker
import com.ichi2.anki.R
import com.ichi2.anki.common.time.TimeManager
import com.ichi2.anki.compat.PackageInfoFlagsCompat
import com.ichi2.anki.compat.getPackageArchiveInfoCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.TimeUnit

/**
 * Offers new AnkiQuest builds from the GitHub releases of the fork.
 *
 * Releases are tagged `quest-<n>` and CI bakes `<n>` into [BuildConfig.ANKIQUEST_RELEASE].
 * Every push to the branch also replaces the `nightly` pre-release, whose builds carry the
 * last release number plus [BuildConfig.ANKIQUEST_NIGHTLY] commits. Local builds carry 0 and never check.
 */
object AnkiquestUpdater {
    enum class Channel { STABLE, NIGHTLY }

    private const val LATEST_URL = "https://api.github.com/repos/AnkiQuest/AnkiQuest-Android/releases/latest"
    private const val NIGHTLY_URL = "https://github.com/AnkiQuest/AnkiQuest-Android/releases/download/nightly/"
    private const val TAG_PREFIX = "quest-"
    const val PREF_CHANNEL = "ankiquestUpdateChannel"
    private const val PREF_CHECKED_AT = "ankiquestUpdateCheckedAt"
    private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L

    /** Runs on every device; released self-updaters download this name. */
    private const val UNIVERSAL_APK = "AnkiQuest.apk"

    /** An ABI APK adds its ABI's number times this to the universal version code (build.gradle.kts). */
    private const val ABI_CODE_STEP = 100_000_000L

    /** The ABI digit of each ABI build's version code, as AnkiDroid/build.gradle.kts sets it. */
    private val ABI_DIGITS = mapOf("armeabi-v7a" to 1L, "x86" to 2L, "arm64-v8a" to 3L, "x86_64" to 4L)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client =
        AnkiquestHttp.client
            .newBuilder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

    private val downloadFiles = Any()
    private var state: UpdateState = UpdateState.Idle

    private sealed interface UpdateState {
        data object Idle : UpdateState

        class Checking : UpdateState

        class Downloading(
            val release: Release,
            var percent: Int = 0,
        ) : UpdateState
    }

    private fun beginCheck(check: UpdateState.Checking): UpdateState =
        synchronized(downloadFiles) {
            if (state === UpdateState.Idle) {
                state = check
            }
            state
        }

    private fun endCheck(check: UpdateState.Checking) {
        synchronized(downloadFiles) {
            if (state === check) {
                state = UpdateState.Idle
            }
        }
    }

    internal data class Release(
        val number: Int,
        val name: String,
        val apkUrl: String,
        val apkSize: Long,
        val versionCode: Long = 0,
        val nightly: Boolean = false,
    )

    /** Makes the next deck list visit check again, e.g. after switching channel. */
    fun checkSoon() = AnkiDroidApp.sharedPrefs().edit { remove(PREF_CHECKED_AT) }

    fun channel(): Channel = if (AnkiDroidApp.sharedPrefs().getString(PREF_CHANNEL, null) == "nightly") Channel.NIGHTLY else Channel.STABLE

    /** A stable release is newer by its number, a nightly by the version code Android compares on install. */
    internal fun isNewer(
        release: Release,
        installedRelease: Int = BuildConfig.ANKIQUEST_RELEASE,
        installedCode: Long = BuildConfig.VERSION_CODE.toLong(),
    ): Boolean =
        if (release.nightly) {
            baseCode(release.versionCode) > baseCode(installedCode)
        } else {
            release.number > installedRelease
        }

    /** The version code without the ABI digit, so universal and ABI APKs of one build compare equal. */
    private fun baseCode(code: Long): Long = code % ABI_CODE_STEP

    /**
     * The APK to download from [names]: the one built for the first of [abis] (the device's
     * ABIs, most preferred first) that has one, else the universal one. An ABI install never
     * gets an APK with a lower ABI digit, the universal one included: Android would refuse it
     * as a downgrade.
     */
    internal fun pickApk(
        names: Collection<String>,
        abis: List<String> = Build.SUPPORTED_ABIS.toList(),
        installedCode: Long = BuildConfig.VERSION_CODE.toLong(),
    ): String? {
        // An installed ABI build carries its ABI digit; a universal install has digit 0.
        val installedDigit = installedCode / ABI_CODE_STEP
        val abi =
            abis.firstOrNull {
                "AnkiQuest-$it.apk" in names && (ABI_DIGITS[it] ?: 0) >= installedDigit
            }
        if (abi != null) {
            return "AnkiQuest-$abi.apk"
        }

        // Only a universal install can take the universal APK.
        if (installedDigit > 0) {
            return null
        }
        return UNIVERSAL_APK.takeIf { it in names }
    }

    fun maybeCheck(activity: Activity) {
        if (activity !is DeckPicker) return
        cleanOnce(activity)
        checkOnResume(activity, BuildConfig.ANKIQUEST_RELEASE)
    }

    private fun checkOnResume(
        activity: Activity,
        installedRelease: Int,
    ) {
        if (installedRelease <= 0) {
            return
        }
        val prefs = AnkiDroidApp.sharedPrefs()
        val now = TimeManager.time.intTimeMS()
        if (now - prefs.getLong(PREF_CHECKED_AT, 0L) < CHECK_INTERVAL_MS) return
        val check = UpdateState.Checking()
        if (beginCheck(check) !== check) {
            return
        }
        prefs.edit { putLong(PREF_CHECKED_AT, now) }
        val host = WeakReference(activity)
        scope.launch {
            try {
                val release = available()
                if (release != null && isNewer(release, installedRelease)) {
                    withContext(Dispatchers.Main) {
                        if (synchronized(downloadFiles) { state === check }) {
                            host.get()?.let { offer(it, release) }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "ankiquest update check failed")
            } finally {
                endCheck(check)
            }
        }
    }

    /** The installed release, e.g. `quest-3` or `quest-3 nightly 2 (1a2b3c4)`, or null for a local build. */
    fun installed(): String? =
        BuildConfig.ANKIQUEST_RELEASE.takeIf { it > 0 }?.let {
            if (BuildConfig.ANKIQUEST_NIGHTLY > 0) {
                "$TAG_PREFIX$it nightly ${BuildConfig.ANKIQUEST_NIGHTLY} (${BuildConfig.GIT_COMMIT_HASH.take(7)})"
            } else {
                "$TAG_PREFIX$it"
            }
        }

    /**
     * Checks for a newer release right away and offers it.
     *
     * @return a message to show when nothing is offered, or null when the update dialog is shown
     */
    suspend fun checkNow(activity: Activity): String? {
        val check = UpdateState.Checking()
        val active = beginCheck(check)
        if (active is UpdateState.Downloading) {
            withContext(Dispatchers.Main) { showProgress(activity, active) }
            return null
        }
        if (active !== check) {
            return activity.getString(R.string.ankiquest_check_running)
        }

        try {
            val release =
                try {
                    withContext(Dispatchers.IO) { available() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "ankiquest update check failed")
                    null
                }
            val current = synchronized(downloadFiles) { state }
            if (current is UpdateState.Downloading) {
                withContext(Dispatchers.Main) { showProgress(activity, current) }
                return null
            }
            if (release == null) {
                return activity.getString(R.string.ankiquest_update_check_failed)
            }
            if (!isNewer(release)) {
                return activity.getString(R.string.ankiquest_update_none, installed() ?: release.name)
            }
            withContext(Dispatchers.Main) {
                if (synchronized(downloadFiles) { state === check }) {
                    offer(activity, release)
                }
            }
            return null
        } finally {
            endCheck(check)
        }
    }

    /** The nightly channel follows whichever build is newest, so a fresh stable release is offered too. */
    private fun available(): Release? = if (channel() == Channel.NIGHTLY) newest(nightly(), latest()) else latest()

    /** The build Android would install as the bigger version; a stable release wins a tie with its own nightly. */
    internal fun newest(
        nightly: Release?,
        stable: Release?,
    ): Release? =
        when {
            nightly == null -> stable
            stable == null -> nightly
            nightly.versionCode > stableCode(stable.number) -> nightly
            else -> stable
        }

    /** Matches the version code CI gives `quest-<n>` in build.gradle.kts. */
    private fun stableCode(number: Int): Long = 50_000_000L + number * 1000L

    private fun nightly(): Release? {
        val request = Request.Builder().url(NIGHTLY_URL + "nightly.json").build()
        val json =
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                JSONObject(response.body.string())
            }
        return parseNightly(json)
    }

    internal fun parseNightly(
        json: JSONObject,
        abis: List<String> = Build.SUPPORTED_ABIS.toList(),
        installedCode: Long = BuildConfig.VERSION_CODE.toLong(),
    ): Release? {
        val number = json.optInt("release")
        val versionCode = json.optLong("version_code")
        if (number <= 0 || versionCode <= 0) return null

        // Nightlies before the ABI APKs list no assets and carry only the universal APK.
        val sizes =
            json
                .optJSONArray("assets")
                ?.objects()
                ?.associate { it.getString("name") to it.optLong("size") }
                ?: mapOf(UNIVERSAL_APK to json.optLong("size"))
        val apk = pickApk(sizes.keys, abis, installedCode) ?: return null
        return Release(
            number = number,
            name = "Nightly $TAG_PREFIX$number+${json.optInt("nightly")} (${json.optString("commit").take(7)})",
            apkUrl = NIGHTLY_URL + apk,
            apkSize = sizes.getValue(apk),
            versionCode = versionCode,
            nightly = true,
        )
    }

    private fun latest(): Release? {
        val request =
            Request
                .Builder()
                .url(LATEST_URL)
                .header("Accept", "application/vnd.github+json")
                .build()
        val json =
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                JSONObject(response.body.string())
            }
        return parseLatest(json)
    }

    internal fun parseLatest(
        json: JSONObject,
        abis: List<String> = Build.SUPPORTED_ABIS.toList(),
        installedCode: Long = BuildConfig.VERSION_CODE.toLong(),
    ): Release? {
        val tag = json.getString("tag_name")
        val number = tag.removePrefix(TAG_PREFIX).toIntOrNull() ?: return null
        val assets = json.getJSONArray("assets").objects().associateBy { it.getString("name") }
        val apk = pickApk(assets.keys, abis, installedCode)?.let(assets::getValue) ?: return null
        return Release(
            number = number,
            name = json.optString("name").ifEmpty { tag },
            apkUrl = apk.getString("browser_download_url"),
            apkSize = apk.optLong("size"),
        )
    }

    private fun offer(
        activity: Activity,
        release: Release,
    ) {
        if (!AnkiquestPostStudyPrompts.ready(activity)) {
            return
        }
        val dialog =
            AlertDialog
                .Builder(activity)
                .setTitle(activity.getString(R.string.ankiquest_update_title, release.name))
                .setMessage(
                    activity.getString(
                        if (release.nightly) R.string.ankiquest_update_nightly_message else R.string.ankiquest_update_message,
                        release.apkSize / (1024 * 1024),
                    ),
                ).setPositiveButton(R.string.ankiquest_update_install) { _, _ -> download(activity, release) }
                .setNegativeButton(R.string.ankiquest_update_later, null)
                .show()
        AnkiquestPostStudyPrompts.track(dialog)
    }

    private class Progress(
        val host: WeakReference<Activity>,
        val dialog: AlertDialog,
    ) {
        fun attached(): Activity? = host.get()?.takeIf { !it.isFinishing && !it.isDestroyed }

        fun update(message: String) {
            if (attached() != null && dialog.isShowing) dialog.setMessage(message)
        }

        fun dismiss() {
            if (host.get()?.isDestroyed == false && dialog.isShowing) dialog.dismiss()
        }
    }

    private var progress: Progress? = null

    private var cleaned = false

    private fun showProgress(
        activity: Activity,
        download: UpdateState.Downloading,
    ) {
        if (activity.isFinishing || activity.isDestroyed) {
            return
        }
        val percent = synchronized(downloadFiles) { download.percent.takeIf { state === download } } ?: return
        val message = activity.getString(R.string.ankiquest_update_downloading, percent)
        if (progress?.attached() === activity && progress?.dialog?.isShowing == true) {
            progress?.update(message)
            return
        }
        progress?.dismiss()
        val dialog =
            AlertDialog
                .Builder(activity)
                .setTitle(activity.getString(R.string.ankiquest_update_title, download.release.name))
                .setMessage(message)
                .setCancelable(false)
                .show()
        (activity as? LifecycleOwner)?.let { owner ->
            owner.lifecycle.addObserver(
                LifecycleEventObserver { _, event ->
                    if (event != Lifecycle.Event.ON_DESTROY) return@LifecycleEventObserver
                    if (dialog.isShowing) dialog.dismiss()
                    if (progress?.dialog === dialog) progress = null
                },
            )
        }
        progress = Progress(WeakReference(activity), dialog)
    }

    /**
     * Downloads [release] in the process scope, so the download survives the activity that asked for it.
     * Asking again while it runs shows its progress on the new activity instead of starting over.
     */
    private fun download(
        activity: Activity,
        release: Release,
    ) {
        if (activity.isFinishing || activity.isDestroyed) return
        val attempt = UpdateState.Downloading(release)
        val active =
            synchronized(downloadFiles) {
                (state as? UpdateState.Downloading) ?: attempt.also { state = it }
            }
        showProgress(activity, active)
        if (active !== attempt) {
            return
        }

        val app = activity.applicationContext
        scope.launch {
            var output: File? = null
            var installed = false
            var failed = false
            try {
                val file = apkFile(app).also { output = it }
                // The file guard serializes cleanup and the download claim.
                synchronized(downloadFiles) { deleteDownloads(file) }
                fetch(release, file) { done ->
                    synchronized(downloadFiles) { attempt.percent = done }
                    withContext(Dispatchers.Main) {
                        progress?.update(app.getString(R.string.ankiquest_update_downloading, done))
                    }
                }

                // Another package would install next to this app instead of updating it.
                if (!isOwnApk(app, file)) {
                    throw IllegalStateException("ankiquest update is not ${app.packageName}")
                }
                withContext(Dispatchers.Main) {
                    val host = progress?.attached()
                    val uri = FileProvider.getUriForFile(app, AnkiquestGallery.authority(app), file)
                    val install =
                        Intent(Intent.ACTION_VIEW)
                            .setDataAndType(uri, "application/vnd.android.package-archive")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    (host ?: app).startActivity(install)
                    installed = true
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "ankiquest update download failed")
                failed = true
            } finally {
                if (!installed) {
                    synchronized(downloadFiles) { output?.let(::deleteDownloads) }
                }
                withContext(NonCancellable + Dispatchers.Main) {
                    val host = progress?.attached()
                    finishProgress()
                    synchronized(downloadFiles) { state = UpdateState.Idle }
                    if (failed && host != null) {
                        AlertDialog
                            .Builder(host)
                            .setMessage(R.string.ankiquest_update_failed)
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                }
            }
        }
    }

    private fun finishProgress() {
        progress?.dismiss()
        progress = null
    }

    private fun apkFile(context: Context) = File(File(context.cacheDir, "ankiquest").apply { mkdirs() }, "AnkiQuest.apk")

    private fun deleteDownloads(file: File) {
        file.delete()
        File(file.path + ".part").delete()
    }

    private fun cleanOnce(context: Context) {
        synchronized(downloadFiles) {
            if (cleaned) {
                return
            }
            cleaned = true
        }
        val app = context.applicationContext
        scope.launch {
            try {
                cleanStale(app)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "ankiquest update cleanup failed")
            }
        }
    }

    /**
     * Deletes a leftover partial download, and the downloaded APK unless it is still newer than the
     * installed build and so may yet be installed.
     */
    internal fun cleanStale(
        context: Context,
        installedCode: Long = BuildConfig.VERSION_CODE.toLong(),
        versionOf: (File) -> Long? = { archiveVersionCode(context, it) },
    ) {
        synchronized(downloadFiles) {
            if (state is UpdateState.Downloading) {
                return
            }
            val file = apkFile(context)
            File(file.path + ".part").delete()
            if (!file.exists()) {
                return
            }
            val downloaded = versionOf(file)
            if (downloaded == null || baseCode(downloaded) <= baseCode(installedCode)) {
                file.delete()
            }
        }
    }

    /** Whether [file] is an APK of this app's package, so installing it updates this app. */
    internal fun isOwnApk(
        context: Context,
        file: File,
        packageOf: (File) -> String? = { archiveInfo(context, it)?.packageName },
    ): Boolean = packageOf(file) == context.packageName

    private fun archiveVersionCode(
        context: Context,
        file: File,
    ): Long? = archiveInfo(context, file)?.let { PackageInfoCompat.getLongVersionCode(it) }

    private fun archiveInfo(
        context: Context,
        file: File,
    ): PackageInfo? =
        runCatching {
            context.packageManager.getPackageArchiveInfoCompat(file.path, PackageInfoFlagsCompat.EMPTY)
        }.getOrNull()

    private suspend fun fetch(
        release: Release,
        file: File,
        onProgress: suspend (Int) -> Unit,
    ) {
        val partial = File(file.path + ".part")
        client.newCall(Request.Builder().url(release.apkUrl).build()).execute().use { response ->
            check(response.isSuccessful) { "download returned ${response.code}" }
            val body = response.body
            val total = body.contentLength().takeIf { it > 0 } ?: release.apkSize
            var done = 0L
            var shown = -1
            body.byteStream().use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        val percent = if (total > 0) (done * 100 / total).toInt() else 0
                        if (percent != shown) {
                            shown = percent
                            onProgress(percent)
                        }
                    }
                }
            }
            check(total <= 0 || done == total) { "download incomplete: $done of $total" }
        }
        file.delete()
        check(partial.renameTo(file)) { "cannot move the download into place" }
    }
}
