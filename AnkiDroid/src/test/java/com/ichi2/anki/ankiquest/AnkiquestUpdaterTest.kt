// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.app.Activity
import android.content.Intent
import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.BuildConfig
import com.ichi2.anki.DeckPicker
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.common.time.TimeManager
import com.ichi2.anki.setIntroductionSlidesShown
import com.sun.net.httpserver.HttpServer
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
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
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val receiving = CountDownLatch(1)
        val finishDownload = CountDownLatch(1)
        val checking = CountDownLatch(1)
        val finishCheck = CountDownLatch(1)
        val checks = AtomicInteger()
        server.createContext("/apk") { exchange ->
            exchange.use {
                receiving.countDown()
                finishDownload.await(30, TimeUnit.SECONDS)
                exchange.sendResponseHeaders(HttpURLConnection.HTTP_UNAVAILABLE, -1)
            }
        }
        server.start()
        mockkObject(AnkiquestUpdater)
        try {
            every { AnkiquestUpdater.channel() } answers {
                checks.incrementAndGet()
                checking.countDown()
                check(finishCheck.await(30, TimeUnit.SECONDS))
                throw IOException("offline")
            }
            val check = CoroutineScope(Dispatchers.Main).async { AnkiquestUpdater.checkNow(host) }
            advanceRobolectricLooperUntil { checking.count == 0L }
            val release = AnkiquestUpdater.Release(41, "Test release", "http://127.0.0.1:${server.address.port}/apk", 1)
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
            server.stop(0)
            unmockkObject(AnkiquestUpdater)
        }
    }

    @Test
    fun `an interrupted manual check propagates cancellation`() {
        val activity = mockk<Activity>(relaxed = true)
        mockkObject(AnkiquestUpdater)
        try {
            every { AnkiquestUpdater.channel() } throws CancellationException("check cancelled")
            runBlocking {
                assertFailsWith<CancellationException> { AnkiquestUpdater.checkNow(activity) }
            }
        } finally {
            unmockkObject(AnkiquestUpdater)
        }
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
}
