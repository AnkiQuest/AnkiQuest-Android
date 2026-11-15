// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker.Result
import androidx.work.testing.TestListenableWorkerBuilder
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.tests.InstrumentedTest
import com.ichi2.anki.testutil.GrantStoragePermission
import dalvik.system.DexFile
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs AnkiQuest code on ART, where unit tests do not run it. Robolectric runs on the JVM, whose
 * libraries differ from Android's: a regex flag the JVM accepts and ICU rejects made
 * AnkiquestDeckNames fail to initialise in release builds only, which broke the poll worker.
 */
@RunWith(AndroidJUnit4::class)
class AnkiquestDeviceTest : InstrumentedTest() {
    @get:Rule
    val runtimePermissionRule = GrantStoragePermission.instance

    /** The AnkiQuest preferences before the test, restored after it: null for an absent one. */
    private lateinit var saved: Map<String, String?>

    @Before
    fun saveAccount() {
        val prefs = AnkiDroidApp.sharedPrefs()
        saved = ACCOUNT_KEYS.associateWith { prefs.getString(it, null) }
    }

    @After
    fun restoreAccount() {
        AnkiDroidApp.sharedPrefs().edit {
            saved.forEach { (key, value) ->
                if (value == null) {
                    remove(key)
                } else {
                    putString(key, value)
                }
            }
        }
    }

    /** Every class in the package, as the APK under test holds it, initialises without throwing. */
    @Test
    fun everyClassInitialises() {
        val loader = testContext.classLoader
        val names = ankiquestClasses()

        // proguard-rules.pro keeps these names; without it R8 renames the classes and none are found.
        assertTrue(EXPECTED_CLASSES.all { it in names }, "AnkiQuest classes not found in the APK: $names")

        // Class.forName(name, true, ...) runs static initialisers, as the first use in the app does.
        val failures =
            names.mapNotNull { name ->
                try {
                    Class.forName(name, true, loader)
                    null
                } catch (e: Throwable) {
                    "$name: ${e.javaClass.name}: ${e.cause ?: e.message}"
                }
            }
        assertEquals(emptyList(), failures, "AnkiQuest classes failed to initialise")
    }

    /**
     * One poll with a signed-in account and a server that refuses connections. The upload reads the
     * home deck names before it connects, which is where the release build crashed.
     */
    @Test
    fun pollWorkerRuns() {
        AnkiDroidApp.sharedPrefs().edit {
            putString(Ankiquest.PREF_URL, UNREACHABLE_SERVER)
            putString(Ankiquest.PREF_USER, TEST_PLAYER)
            putString(Ankiquest.PREF_TOKEN, TEST_TOKEN)
        }
        val worker = TestListenableWorkerBuilder<AnkiquestPollWorker>(testContext).build()

        // An unreachable server is a transient failure: retry; anything thrown fails the test.
        val result = runBlocking { worker.doWork() }
        assertTrue(result == Result.retry() || result == Result.success(), "Unexpected poll result: $result")
    }

    /** The names of the AnkiQuest classes in the installed APK's dex files. */
    private fun ankiquestClasses(): List<String> {
        val apk = testContext.applicationInfo.sourceDir

        // DexFile is deprecated since API 26 but still works; OrgJsonNotBundledTest reads the APK the same way.
        @Suppress("DEPRECATION")
        return DexFile(apk)
            .entries()
            .toList()
            .filter { it.startsWith(PACKAGE_PREFIX) }
    }

    private companion object {
        const val PACKAGE_PREFIX = "com.ichi2.anki.ankiquest."

        /** Classes that must be found, so a missing keep rule fails instead of checking nothing. */
        val EXPECTED_CLASSES =
            listOf(
                "com.ichi2.anki.ankiquest.AnkiquestDeckNames",
                "com.ichi2.anki.ankiquest.AnkiquestPollWorker",
            )

        val ACCOUNT_KEYS = listOf(Ankiquest.PREF_URL, Ankiquest.PREF_USER, Ankiquest.PREF_TOKEN)

        /** Port 9 (discard) on this device: nothing listens, so connections fail at once. */
        const val UNREACHABLE_SERVER = "http://127.0.0.1:9"
        const val TEST_PLAYER = "device-test"
        const val TEST_TOKEN = "device-test-token"
    }
}
