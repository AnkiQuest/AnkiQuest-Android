// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.BuildConfig
import com.ichi2.anki.DeckPicker
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.common.time.TimeManager
import com.ichi2.anki.dialogs.utils.message
import com.ichi2.anki.dialogs.utils.title
import com.ichi2.anki.setIntroductionSlidesShown
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.shadows.ShadowDialog
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class AnkiquestUpdaterTest : RobolectricTest() {
    @Test
    fun `an update response cannot attach to a stopped deck picker`() {
        setIntroductionSlidesShown(true)
        val requested = CountDownLatch(1)
        val response = CountDownLatch(1)
        val intent = Intent().putExtra(AnkiquestHomeActivity.EXTRA_SKIP_HOME, true)
        val controller = startActivityControllerNormallyOpenCollectionWithIntent(DeckPicker::class.java, intent)
        val host = controller.get()
        assertTrue(AnkiquestPostStudyPrompts.ready(host))
        val release = AnkiquestUpdater.Release(BuildConfig.ANKIQUEST_RELEASE + 1, "New release", "", 1)
        mockkObject(AnkiquestUpdater, recordPrivateCalls = true)
        try {
            every { AnkiquestUpdater.source() } returns AnkiquestUpdater.Source.GITHUB
            every { AnkiquestUpdater["available"]() } answers {
                requested.countDown()
                check(response.await(10, TimeUnit.SECONDS))
                release
            }
            val before = ShadowDialog.getLatestDialog()
            val check = CoroutineScope(Dispatchers.Main).async { AnkiquestUpdater.checkNow(host) }
            advanceRobolectricLooperUntil { requested.count == 0L }
            controller.pause().stop()
            response.countDown()
            advanceRobolectricLooperUntil { check.isCompleted }
            assertNull(runBlocking { check.await() })
            assertSame(before, ShadowDialog.getLatestDialog())

            controller.start().resume()
            val retry = CoroutineScope(Dispatchers.Main).async { AnkiquestUpdater.checkNow(host) }
            advanceRobolectricLooperUntil { retry.isCompleted }
            assertNull(runBlocking { retry.await() })
            assertTrue(assertNotNull(ShadowDialog.getLatestDialog()).isShowing)
            ShadowDialog.getLatestDialog().dismiss()
        } finally {
            response.countDown()
            ShadowDialog.getLatestDialog()?.dismiss()
            unmockkObject(AnkiquestUpdater)
        }
    }

    @Test
    fun `check now shares the in-flight check`() {
        val activity = mockk<Activity>(relaxed = true)
        val checking = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val calls = AtomicInteger()
        every { activity.getString(R.string.ankiquest_check_running) } returns "Checking"
        mockkObject(AnkiquestUpdater)
        try {
            every { AnkiquestUpdater.source() } returns AnkiquestUpdater.Source.GITHUB
            every { AnkiquestUpdater.channel() } answers {
                if (calls.incrementAndGet() == 1) {
                    checking.countDown()
                    check(finish.await(10, TimeUnit.SECONDS))
                }
                throw IOException("offline")
            }
            runBlocking {
                val first = async(Dispatchers.Default) { AnkiquestUpdater.checkNow(activity) }
                try {
                    assertTrue(checking.await(10, TimeUnit.SECONDS))
                    assertEquals("Checking", AnkiquestUpdater.checkNow(activity))
                    assertEquals(1, calls.get())
                } finally {
                    finish.countDown()
                }
                first.await()
            }
        } finally {
            unmockkObject(AnkiquestUpdater)
        }
    }

    @Test
    fun `a check that finishes during a download leaves the download claimed`() {
        val controller = Robolectric.buildActivity(Activity::class.java)
        controller.get().setTheme(androidx.appcompat.R.style.Theme_AppCompat)
        val host = controller.setup().get()
        saveControllerForCleanup(controller)
        val receiving = CountDownLatch(1)
        val finishDownload = CountDownLatch(1)
        val checking = CountDownLatch(1)
        val finishCheck = CountDownLatch(1)
        val checks = AtomicInteger()
        val server =
            AnkiquestTestServer { request ->
                if (request.path != "/apk") {
                    return@AnkiquestTestServer null
                }
                receiving.countDown()
                finishDownload.await(30, TimeUnit.SECONDS)
                TestResponse(HttpURLConnection.HTTP_UNAVAILABLE)
            }
        mockkObject(AnkiquestUpdater)
        try {
            every { AnkiquestUpdater.source() } returns AnkiquestUpdater.Source.GITHUB
            every { AnkiquestUpdater.channel() } answers {
                checks.incrementAndGet()
                checking.countDown()
                check(finishCheck.await(30, TimeUnit.SECONDS))
                throw IOException("offline")
            }
            val check = CoroutineScope(Dispatchers.Main).async { AnkiquestUpdater.checkNow(host) }
            advanceRobolectricLooperUntil { checking.count == 0L }
            val release = AnkiquestUpdater.Release(41, "Test release", "${server.url}/apk", 1)
            ReflectionHelpers.callInstanceMethod<Unit>(
                AnkiquestUpdater,
                "download",
                ClassParameter.from(Activity::class.java, host),
                ClassParameter.from(AnkiquestUpdater.Release::class.java, release),
            )
            val progress = assertNotNull(ShadowDialog.getLatestDialog())
            advanceRobolectricLooperUntil { receiving.count == 0L }

            finishCheck.countDown()
            advanceRobolectricLooperUntil { check.isCompleted }
            assertNull(runBlocking { check.await() })
            val probe = CoroutineScope(Dispatchers.Main).async { AnkiquestUpdater.checkNow(host) }
            advanceRobolectricLooperUntil { probe.isCompleted }
            assertNull(runBlocking { probe.await() })
            assertEquals(1, checks.get())
            assertTrue(progress.isShowing)

            val prefs = AnkiDroidApp.sharedPrefs()
            val checkedAt = "ankiquestUpdateCheckedAt"
            val lastCheck = TimeManager.time.intTimeMS() - TimeUnit.DAYS.toMillis(1)
            prefs.edit { putLong(checkedAt, lastCheck) }
            ReflectionHelpers.callInstanceMethod<Unit>(
                AnkiquestUpdater,
                "checkOnResume",
                ClassParameter.from(Activity::class.java, host),
                ClassParameter.from(Integer.TYPE, 40),
            )
            assertEquals(lastCheck, prefs.getLong(checkedAt, 0))
            assertTrue(progress.isShowing)

            val (apk, part) = downloads()
            AnkiquestUpdater.cleanStale(targetContext) { error("The download still owns its files") }
            assertTrue(apk.exists())
            assertTrue(part.exists())
            finishDownload.countDown()
            advanceRobolectricLooperUntil { !progress.isShowing }
            assertFalse(apk.exists())
            assertFalse(part.exists())
        } finally {
            finishCheck.countDown()
            finishDownload.countDown()
            server.close()
            unmockkObject(AnkiquestUpdater)
        }
    }

    @Test
    fun `an interrupted manual check propagates cancellation`() {
        val activity = mockk<Activity>(relaxed = true)
        mockkObject(AnkiquestUpdater)
        try {
            every { AnkiquestUpdater.source() } returns AnkiquestUpdater.Source.GITHUB
            every { AnkiquestUpdater.channel() } throws CancellationException("check cancelled")
            runBlocking {
                assertFailsWith<CancellationException> { AnkiquestUpdater.checkNow(activity) }
            }
        } finally {
            unmockkObject(AnkiquestUpdater)
        }
    }

    @Test
    fun `a build without updates never checks`() {
        val activity = mockk<Activity>(relaxed = true)
        every { activity.getString(R.string.ankiquest_updates_off) } returns "Off"
        mockkObject(AnkiquestUpdater, recordPrivateCalls = true)
        try {
            every { AnkiquestUpdater.source() } returns AnkiquestUpdater.Source.NONE
            every { AnkiquestUpdater["available"]() } answers { error("checked for updates") }
            assertEquals("Off", runBlocking { AnkiquestUpdater.checkNow(activity) })
            AnkiquestUpdater.maybeCheck(mockk<DeckPicker>(relaxed = true))
            verify(exactly = 0) { AnkiquestUpdater["available"]() }
            verify(exactly = 0) { AnkiquestUpdater["cleanOnce"](any<Context>()) }
        } finally {
            unmockkObject(AnkiquestUpdater)
        }
    }

    @Test
    fun `the build names a known update source`() {
        assertTrue(AnkiquestUpdater.Source.entries.any { it.property == BuildConfig.ANKIQUEST_UPDATES })
    }

    // CI runs this class with -PankiquestUpdates=github too, so both manifests are checked.
    @Test
    fun `only a self-updating build requests the install permission`() {
        val info = targetContext.packageManager.getPackageInfo(targetContext.packageName, PackageManager.GET_PERMISSIONS)
        val requested = info.requestedPermissions.orEmpty().contains(Manifest.permission.REQUEST_INSTALL_PACKAGES)
        assertEquals(AnkiquestUpdater.source() == AnkiquestUpdater.Source.GITHUB, requested)
    }

    private val nightlyJson =
        JSONObject()
            .put("release", 17)
            .put("nightly", 3)
            .put("version_code", 50_017_003)
            .put("commit", "1a2b3c4d5e6f")
            .put("size", 90_000_000)

    @Test
    fun `nightly metadata points at the rolling pre-release`() {
        val nightly = AnkiquestUpdater.parseNightly(nightlyJson)!!
        assertTrue(nightly.nightly)
        assertEquals(50_017_003, nightly.versionCode)
        assertEquals("Nightly quest-17+3 (1a2b3c4)", nightly.name)
        assertEquals("https://github.com/AnkiQuest/AnkiQuest-Android/releases/download/nightly/AnkiQuest.apk", nightly.apkUrl)
        assertNull(AnkiquestUpdater.parseNightly(JSONObject(nightlyJson.toString()).put("version_code", 0)))
    }

    @Test
    fun `a nightly is offered only when Android would install it as an upgrade`() {
        val nightly = AnkiquestUpdater.parseNightly(nightlyJson)!!
        assertTrue(AnkiquestUpdater.isNewer(nightly, installedRelease = 17, installedCode = 50_017_000))
        assertFalse(AnkiquestUpdater.isNewer(nightly, installedRelease = 17, installedCode = 50_017_003))
        assertFalse(AnkiquestUpdater.isNewer(nightly, installedRelease = 18, installedCode = 50_018_000))
    }

    @Test
    fun `a stable release is offered over any nightly built before it`() {
        val stable = AnkiquestUpdater.Release(number = 18, name = "AnkiQuest 18", apkUrl = "", apkSize = 0)
        assertTrue(AnkiquestUpdater.isNewer(stable, installedRelease = 17, installedCode = 50_017_009))
        assertFalse(AnkiquestUpdater.isNewer(stable, installedRelease = 18, installedCode = 50_018_000))
    }

    @Test
    fun `the nightly channel offers a stable release newer than the nightly`() {
        val nightly = AnkiquestUpdater.parseNightly(nightlyJson)!!
        val stable = AnkiquestUpdater.Release(number = 18, name = "AnkiQuest 18", apkUrl = "", apkSize = 0)
        assertEquals(stable, AnkiquestUpdater.newest(nightly, stable))
        val older = stable.copy(number = 17, name = "AnkiQuest 17")
        assertEquals(nightly, AnkiquestUpdater.newest(nightly, older))
        val sameRelease =
            AnkiquestUpdater.parseNightly(
                JSONObject(nightlyJson.toString()).put("nightly", 0).put("version_code", 50_017_000),
            )!!
        assertEquals(older, AnkiquestUpdater.newest(sameRelease, older))
        assertEquals(stable, AnkiquestUpdater.newest(null, stable))
        assertEquals(nightly, AnkiquestUpdater.newest(nightly, null))
    }

    private val abiAssets = listOf("AnkiQuest-arm64-v8a.apk", "AnkiQuest.apk", "AnkiQuest-x86_64.apk", "AnkiQuest.aab")

    @Test
    fun `the apk for the primary abi is picked, else the universal one`() {
        assertEquals("AnkiQuest-arm64-v8a.apk", AnkiquestUpdater.pickApk(abiAssets, listOf("arm64-v8a")))
        assertEquals("AnkiQuest-x86_64.apk", AnkiquestUpdater.pickApk(abiAssets, listOf("x86_64")))
        assertEquals("AnkiQuest.apk", AnkiquestUpdater.pickApk(abiAssets, listOf("armeabi-v7a")))
        assertEquals("AnkiQuest.apk", AnkiquestUpdater.pickApk(abiAssets, emptyList()))
        assertNull(AnkiquestUpdater.pickApk(listOf("AnkiQuest.aab", "AnkiQuest-x86.apk"), listOf("arm64-v8a")))
    }

    @Test
    fun `a nightly with assets offers the apk for this device`() {
        val assets =
            JSONArray(
                abiAssets.mapIndexed { i, name -> JSONObject().put("name", name).put("size", 1000 + i) },
            )
        val json = JSONObject(nightlyJson.toString()).put("assets", assets)
        val arm = AnkiquestUpdater.parseNightly(json, listOf("arm64-v8a"))!!
        assertEquals("https://github.com/AnkiQuest/AnkiQuest-Android/releases/download/nightly/AnkiQuest-arm64-v8a.apk", arm.apkUrl)
        assertEquals(1000, arm.apkSize)
        val other = AnkiquestUpdater.parseNightly(json, listOf("riscv64"))!!
        assertEquals("https://github.com/AnkiQuest/AnkiQuest-Android/releases/download/nightly/AnkiQuest.apk", other.apkUrl)
        assertEquals(1001, other.apkSize)
    }

    @Test
    fun `a nightly without assets offers the universal apk`() {
        val nightly = AnkiquestUpdater.parseNightly(nightlyJson, listOf("arm64-v8a"))!!
        assertEquals("https://github.com/AnkiQuest/AnkiQuest-Android/releases/download/nightly/AnkiQuest.apk", nightly.apkUrl)
        assertEquals(90_000_000, nightly.apkSize)
    }

    @Test
    fun `a release offers the apk for this device`() {
        fun release(names: List<String>) =
            JSONObject()
                .put("tag_name", "quest-18")
                .put("name", "AnkiQuest 18")
                .put(
                    "assets",
                    JSONArray(
                        names.map { name ->
                            val url = "https://example.org/$name"
                            JSONObject().put("name", name).put("size", name.length).put("browser_download_url", url)
                        },
                    ),
                )
        val arm = AnkiquestUpdater.parseLatest(release(abiAssets), listOf("arm64-v8a"))!!
        assertEquals(18, arm.number)
        assertEquals("https://example.org/AnkiQuest-arm64-v8a.apk", arm.apkUrl)
        assertEquals("AnkiQuest-arm64-v8a.apk".length.toLong(), arm.apkSize)
        assertEquals("https://example.org/AnkiQuest.apk", AnkiquestUpdater.parseLatest(release(abiAssets), listOf("x86"))!!.apkUrl)
        assertNull(AnkiquestUpdater.parseLatest(release(listOf("AnkiQuest.aab")), listOf("arm64-v8a")))
    }

    @Test
    fun `the first supported abi with an apk is picked`() {
        // Build.SUPPORTED_ABIS lists the device's ABIs, most preferred first.
        val assets = listOf("AnkiQuest-armeabi-v7a.apk", "AnkiQuest.apk")
        val universalInstall = 50_017_000L
        assertEquals(
            "AnkiQuest-armeabi-v7a.apk",
            AnkiquestUpdater.pickApk(assets, listOf("arm64-v8a", "armeabi-v7a"), universalInstall),
        )
        assertEquals("AnkiQuest.apk", AnkiquestUpdater.pickApk(assets, listOf("riscv64"), universalInstall))
    }

    @Test
    fun `an abi install never moves to an abi with a lower version code`() {
        // arm64-v8a builds carry ABI digit 3, armeabi-v7a builds digit 1: Android refuses the step down.
        val armInstall = 350_017_000L
        val assets = listOf("AnkiQuest-armeabi-v7a.apk", "AnkiQuest.apk")
        assertNull(AnkiquestUpdater.pickApk(assets, listOf("arm64-v8a", "armeabi-v7a"), armInstall))

        // A step up, from armeabi-v7a to arm64-v8a, installs.
        val armv7Install = 150_017_000L
        assertEquals(
            "AnkiQuest-arm64-v8a.apk",
            AnkiquestUpdater.pickApk(listOf("AnkiQuest-arm64-v8a.apk"), listOf("arm64-v8a", "armeabi-v7a"), armv7Install),
        )
    }

    @Test
    fun `an abi install is never offered the universal apk`() {
        // Android refuses the universal APK on an ABI install: its version code lacks the ABI digit.
        val abiInstall = 350_017_000L
        assertEquals("AnkiQuest-arm64-v8a.apk", AnkiquestUpdater.pickApk(abiAssets, listOf("arm64-v8a"), abiInstall))
        assertNull(AnkiquestUpdater.pickApk(abiAssets, listOf("armeabi-v7a"), abiInstall))
        assertNull(AnkiquestUpdater.pickApk(listOf("AnkiQuest.apk"), listOf("arm64-v8a"), abiInstall))

        // A universal install still takes the universal APK, or moves to its ABI one.
        assertEquals("AnkiQuest.apk", AnkiquestUpdater.pickApk(listOf("AnkiQuest.apk"), listOf("arm64-v8a"), 50_017_000L))
        assertEquals("AnkiQuest-arm64-v8a.apk", AnkiquestUpdater.pickApk(abiAssets, listOf("arm64-v8a"), 50_017_000L))
    }

    @Test
    fun `a universal-only nightly is not offered to an abi install`() {
        assertNull(AnkiquestUpdater.parseNightly(nightlyJson, listOf("arm64-v8a"), installedCode = 350_017_000L))
        assertEquals(
            "https://github.com/AnkiQuest/AnkiQuest-Android/releases/download/nightly/AnkiQuest.apk",
            AnkiquestUpdater.parseNightly(nightlyJson, listOf("arm64-v8a"), installedCode = 50_017_000L)!!.apkUrl,
        )
    }

    @Test
    fun `a universal-only release is not offered to an abi install`() {
        val release =
            JSONObject()
                .put("tag_name", "quest-18")
                .put("name", "AnkiQuest 18")
                .put(
                    "assets",
                    JSONArray(
                        listOf(
                            JSONObject()
                                .put("name", "AnkiQuest.apk")
                                .put("size", 1)
                                .put("browser_download_url", "https://example.org/AnkiQuest.apk"),
                        ),
                    ),
                )
        assertNull(AnkiquestUpdater.parseLatest(release, listOf("arm64-v8a"), installedCode = 350_017_000L))
        val universal = AnkiquestUpdater.parseLatest(release, listOf("arm64-v8a"), installedCode = 50_017_000L)!!
        assertEquals("https://example.org/AnkiQuest.apk", universal.apkUrl)
    }

    @Test
    fun `an abi build compares to a nightly without its abi digit`() {
        val nightly = AnkiquestUpdater.parseNightly(nightlyJson)!!
        assertTrue(AnkiquestUpdater.isNewer(nightly, installedRelease = 17, installedCode = 350_017_000))
        assertFalse(AnkiquestUpdater.isNewer(nightly, installedRelease = 17, installedCode = 350_017_003))
        assertFalse(AnkiquestUpdater.isNewer(nightly.copy(versionCode = 350_017_003), installedRelease = 17, installedCode = 50_017_003))
    }

    @Test
    fun `the channel follows the setting and defaults to stable`() {
        assertEquals(AnkiquestUpdater.Channel.STABLE, AnkiquestUpdater.channel())
        AnkiDroidApp.sharedPrefs().edit { putString(AnkiquestUpdater.PREF_CHANNEL, "nightly") }
        assertEquals(AnkiquestUpdater.Channel.NIGHTLY, AnkiquestUpdater.channel())
    }

    private fun downloads(): Pair<File, File> {
        val dir = File(targetContext.cacheDir, "ankiquest").apply { mkdirs() }
        val apk = File(dir, "AnkiQuest.apk").apply { writeText("apk") }
        val part = File(dir, "AnkiQuest.apk.part").apply { writeText("part") }
        return apk to part
    }

    @Test
    fun `a downloaded apk that is already installed is deleted`() {
        val (apk, part) = downloads()
        AnkiquestUpdater.cleanStale(targetContext, installedCode = 100) { 100 }
        assertFalse(apk.exists())
        assertFalse(part.exists())
    }

    @Test
    fun `a downloaded apk newer than the installed build is kept`() {
        val (apk, part) = downloads()
        AnkiquestUpdater.cleanStale(targetContext, installedCode = 100) { 101 }
        assertTrue(apk.exists())
        assertFalse(part.exists())
    }

    @Test
    fun `a downloaded abi apk of the installed build is deleted`() {
        val (apk, _) = downloads()
        AnkiquestUpdater.cleanStale(targetContext, installedCode = 100) { 300_000_100 }
        assertFalse(apk.exists())
    }

    @Test
    fun `a downloaded abi apk newer than the installed build is kept`() {
        val (apk, _) = downloads()
        AnkiquestUpdater.cleanStale(targetContext, installedCode = 100) { 300_000_101 }
        assertTrue(apk.exists())
    }

    @Test
    fun `an unreadable downloaded apk is deleted`() {
        val (apk, _) = downloads()
        AnkiquestUpdater.cleanStale(targetContext, installedCode = 100)
        assertFalse(apk.exists())
    }

    @Test
    fun `only an apk of this package is installed`() {
        val own = targetContext.packageName
        assertEquals(AnkiquestUpdater.Download.OWN, AnkiquestUpdater.classify(own, own))
        assertEquals(AnkiquestUpdater.Download.MOVED, AnkiquestUpdater.classify(own, "$own.other"))
        assertEquals(AnkiquestUpdater.Download.UNREADABLE, AnkiquestUpdater.classify(own, null))
    }

    @Test
    fun `an apk of another package shows the move instead of installing`() {
        val shown = download(packageName = "dev.hilll.anki.other")
        assertEquals(targetContext.getString(R.string.ankiquest_moved_title), shown.title)
        val message = assertNotNull(shown.message)
        assertContains(message, "shared AnkiDroid folder")
        assertContains(message, "force-stop this app")
        assertContains(message, "separate folders, sync with AnkiWeb or export")
    }

    @Test
    fun `an unreadable apk is reported as a failed download`() {
        val shown = download(packageName = null)
        assertEquals(targetContext.getString(R.string.ankiquest_update_failed), shown.message)
    }

    /**
     * Downloads a release whose APK reads as [packageName] and returns the dialog shown afterwards.
     * The download is deleted, so it is not handed to the installer.
     */
    private fun download(packageName: String?): AlertDialog {
        val controller = Robolectric.buildActivity(Activity::class.java)
        controller.get().setTheme(androidx.appcompat.R.style.Theme_AppCompat)
        val host = controller.setup().get()
        saveControllerForCleanup(controller)
        val body = "apk".toByteArray()
        val server =
            AnkiquestTestServer { request ->
                if (request.path != "/apk") {
                    return@AnkiquestTestServer null
                }
                TestResponse(bytes = body)
            }
        mockkObject(AnkiquestUpdater, recordPrivateCalls = true)
        try {
            every { AnkiquestUpdater["archiveInfo"](any<Context>(), any<File>()) } returns
                packageName?.let { name -> PackageInfo().apply { this.packageName = name } }
            val release = AnkiquestUpdater.Release(41, "Test release", "${server.url}/apk", 3)
            ReflectionHelpers.callInstanceMethod<Unit>(
                AnkiquestUpdater,
                "download",
                ClassParameter.from(Activity::class.java, host),
                ClassParameter.from(AnkiquestUpdater.Release::class.java, release),
            )
            val progress = assertNotNull(ShadowDialog.getLatestDialog())
            advanceRobolectricLooperUntil { !progress.isShowing }
            advanceRobolectricLooperUntil { ShadowDialog.getLatestDialog() !== progress }
            assertFalse(File(File(targetContext.cacheDir, "ankiquest"), "AnkiQuest.apk").exists())
            return ShadowDialog.getLatestDialog() as AlertDialog
        } finally {
            server.close()
            unmockkObject(AnkiquestUpdater)
        }
    }
}
