// SPDX-FileCopyrightText: Copyright (c) 2026 hill <hill@hilll.dev>
// SPDX-License-Identifier: LGPL-3.0-or-later
package com.ichi2.anki.api

import android.content.pm.ApplicationInfo
import android.content.pm.Signature
import android.os.Build
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.security.MessageDigest
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Robolectric doesn't shadow the Tiramisu+ `resolveContentProvider`, see [AddContentApiTest] */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.S_V2])
internal class ProviderSelectionTest {
    private val context get() = RuntimeEnvironment.getApplication()

    private fun selection() = ProviderSelection(context, listOf(ANKIDROID, FORK))

    private val stored get() = ChoiceStore(context).load()

    @Before
    fun setUp() {
        // A debuggable caller accepts any certificate, see AnkiDroidProvider
        val info = context.applicationInfo
        info.flags = info.flags and ApplicationInfo.FLAG_DEBUGGABLE.inv()
    }

    @Test
    fun ankiDroidOnlyNeedsNoChoice() {
        installAnkiDroid()

        assertEquals(Resolution.Ready(ANKIDROID), selection().resolve())
    }

    @Test
    fun noneInstalled() {
        assertEquals(Resolution.NotInstalled, selection().resolve())
        assertEquals(emptyList(), selection().installed())
        assertNull(selection().inUse())
    }

    @Test
    fun forkOnlyNeedsChoice() {
        installFork(Signing.Current(FORK_KEY))

        assertEquals(Resolution.NeedsChoice(listOf(FORK)), selection().resolve())
        assertNull(selection().inUse())
    }

    @Test
    fun ankiDroidIsDefaultWithBoth() {
        installFork(Signing.Current(FORK_KEY))
        installAnkiDroid()

        assertEquals(listOf(ANKIDROID, FORK), selection().installed())
        assertEquals(Resolution.Ready(ANKIDROID), selection().resolve())
    }

    @Test
    fun chosenForkIsUsed() {
        installAnkiDroid()
        installFork(Signing.Current(FORK_KEY))

        selection().choose(FORK)

        assertEquals(Resolution.Ready(FORK), selection().resolve())
    }

    @Test
    fun choosingAnkiDroidClearsChoice() {
        installAnkiDroid()
        installFork(Signing.Current(FORK_KEY))
        selection().choose(FORK)

        selection().choose(ANKIDROID)

        assertNull(stored)
        assertEquals(Resolution.Ready(ANKIDROID), selection().resolve())
    }

    @Test
    fun uninstalledForkFallsBack() {
        installAnkiDroid()
        installFork(Signing.Current(FORK_KEY))
        selection().choose(FORK)

        shadowOf(context.packageManager).removePackage(FORK.packageName)

        assertEquals(Resolution.Ready(ANKIDROID), selection().resolve())
        assertNull(stored)
    }

    /** The fork is re-signed with another listed key: still trusted, but the user has to choose again */
    @Test
    fun changedCertificateDropsChoice() {
        installFork(Signing.Current(FORK_KEY))
        selection().choose(FORK)

        installFork(Signing.Rotated(past = FORK_KEY, current = FORK_NEW_KEY))

        assertEquals(Resolution.NeedsChoice(listOf(FORK)), selection().resolve())
        assertNull(stored)
    }

    @Test
    fun unlistedCertificateIsRejected() {
        installFork(Signing.Current(UNKNOWN))

        assertEquals(Resolution.NotInstalled, selection().resolve())
    }

    @Test
    fun squattedAuthorityIsRejected() {
        // Another package holds the fork's authority, signed with the fork's listed key
        AnkiDroidPackages.install(SQUATTER, Signing.Current(FORK_KEY), authority = FORK.authority)

        assertEquals(Resolution.NotInstalled, selection().resolve())
    }

    @Test
    fun choiceOfSquattedForkIsDropped() {
        installFork(Signing.Current(FORK_KEY))
        selection().choose(FORK)

        shadowOf(context.packageManager).removePackage(FORK.packageName)
        AnkiDroidPackages.install(SQUATTER, Signing.Current(FORK_KEY), authority = FORK.authority)

        assertEquals(Resolution.NotInstalled, selection().resolve())
        assertNull(stored)
    }

    private fun installAnkiDroid() {
        AnkiDroidPackages.install(ANKIDROID.packageName, Signing.Current(AnkiDroidPackages.RELEASE))
    }

    private fun installFork(signing: Signing) {
        AnkiDroidPackages.install(FORK.packageName, signing, authority = FORK.authority)
    }

    private companion object {
        const val SQUATTER = "com.example.squatter"
        val FORK_KEY = Signature(byteArrayOf(7, 8, 9))
        val FORK_NEW_KEY = Signature(byteArrayOf(10, 11, 12))
        val UNKNOWN = Signature(byteArrayOf(1, 2, 3))

        val ANKIDROID = KnownProviders.ANKIDROID
        val FORK =
            FlashCardsProvider(
                packageName = "com.example.fork",
                authority = "com.example.fork.flashcards",
                permission = "com.example.fork.permission.READ_WRITE_DATABASE",
                certificates = setOf(digest(FORK_KEY), digest(FORK_NEW_KEY)),
            )

        fun digest(signature: Signature) =
            MessageDigest
                .getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { "%02X".format(Locale.US, it) }
    }
}
