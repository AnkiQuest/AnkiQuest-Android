// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: Copyright (c) 2026 Ashish Yadav <mailtoashish693@gmail.com>

package com.ichi2.anki.baselineprofile

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.uiAutomator
import org.junit.After
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class ReleaseSmokeTest {
    private val arguments = InstrumentationRegistry.getArguments()
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val targetPackage =
        arguments.getString("targetAppId")
            ?: throw IllegalStateException("targetAppId not passed as instrumentation runner arg")
    private val startedAt = System.currentTimeMillis() / 1000

    @Before
    fun resetApp() {
        assumeTrue("Clears the app's data, pass -e releaseSmokeTest true to run it", arguments.getString("releaseSmokeTest") == "true")
        shell("pm clear $targetPackage")
    }

    @After
    fun assertNoCrash() {
        val errors =
            shell("logcat -d -v brief -T $startedAt.000 AndroidRuntime:E ACRA:E *:S")
                .lines()
                .filter { it.startsWith("E/") }
        if (errors.any { targetPackage in it }) {
            fail("$targetPackage crashed:\n${errors.joinToString("\n")}")
        }
    }

    @Test
    fun opensDeckPicker() =
        uiAutomator {
            startApp(targetPackage)
            onElement { viewIdResourceName == "$targetPackage:id/get_started" }.click()

            // AnkiQuest: Aki greets a fresh install once, over the deck list; "Later" closes it.
            onElementOrNull(GREETING_WAIT_MS) { viewIdResourceName == "$targetPackage:id/ankiquest_greeting_image" }
                ?.let { onElement { viewIdResourceName == "android:id/button2" }.click() }

            onElement { viewIdResourceName == "$targetPackage:id/fab_main" }
        }

    private fun shell(command: String): String = device.executeShellCommand(command)

    private companion object {
        /** How long the deck list may take to show AnkiQuest's first-open greeting. */
        const val GREETING_WAIT_MS = 5_000L
    }
}
