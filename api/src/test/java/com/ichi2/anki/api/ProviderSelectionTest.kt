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
import org.robolectric.annotation.Config
import java.security.MessageDigest
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Each transition of [Resolution]. Robolectric doesn't shadow the Tiramisu+
 * `resolveContentProvider`, see [AddContentApiTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.S_V2])
internal class ProviderSelectionTest {
    private val context get() = RuntimeEnvironment.getApplication()

    private fun selection() = ProviderSelection(context, { ProviderDiscovery.find(context, listOf(ANKIDROID, LISTED)) })

    private val stored get() = ChoiceStore(context).load()

    @Before
    fun setUp() {
        // A debuggable caller accepts any certificate, see AnkiDroidProvider
        val info = context.applicationInfo
        info.flags = info.flags and ApplicationInfo.FLAG_DEBUGGABLE.inv()
    }

    @Test
    fun noneInstalled() {
        assertEquals(Resolution.NotInstalled, selection().resolve())
        assertEquals(emptyList(), selection().installed())
        assertNull(selection().inUse())
    }

    @Test
    fun ankiDroidOnlyIsUsed() {
        installAnkiDroid()

        assertEquals(Resolution.Only(ANKIDROID), selection().resolve())
        assertEquals(ANKIDROID, selection().inUse())
    }

    /** Any app declaring FlashCardsContract.ACTION_FLASHCARDS_PROVIDER is a candidate */
    @Test
    fun unlistedOnlyIsUsed() {
        installUnlisted()

        val resolution = selection().resolve()
        assertEquals(Resolution.Only(UNLISTED), resolution)
        val provider = (resolution as Resolution.Only).provider
        assertEquals(UNLISTED_AUTHORITY, provider.authority)
        assertEquals("$UNLISTED_PACKAGE.permission.READ_WRITE_DATABASE", provider.permission)
    }

    /** Android never grants a permission the client app does not declare */
    @Test
    fun undeclaredPermissionIsIgnored() {
        installUnlisted(permission = PermissionRequest.NOT_REQUESTED)

        assertEquals(Resolution.NotInstalled, selection().resolve())
    }

    @Test
    fun severalNeedChoice() {
        installUnlisted()
        installAnkiDroid()

        assertEquals(Resolution.NeedsChoice(listOf(ANKIDROID, UNLISTED)), selection().resolve())
        assertNull(selection().inUse())
    }

    @Test
    fun choiceIsUsed() {
        installAnkiDroid()
        installUnlisted()

        selection().choose(UNLISTED)

        assertEquals(Resolution.Chosen(UNLISTED), selection().resolve())
        assertEquals(UNLISTED, selection().inUse())
    }

    @Test
    fun choosingAnkiDroidIsPinned() {
        installAnkiDroid()
        installUnlisted()

        selection().choose(ANKIDROID)

        assertEquals(Resolution.Chosen(ANKIDROID), selection().resolve())
        assertEquals(ANKIDROID.packageName, assertNotNull(stored).packageName)
    }

    /** A new candidate does not ask again */
    @Test
    fun choiceHoldsWhenAnotherIsInstalled() {
        installAnkiDroid()
        installUnlisted()
        selection().choose(UNLISTED)

        installListed(Signing.Current(LISTED_KEY))

        assertEquals(Resolution.Chosen(UNLISTED), selection().resolve())
    }

    @Test
    fun uninstalledChoiceIsDropped() {
        installAnkiDroid()
        installUnlisted()
        selection().choose(UNLISTED)

        AnkiDroidPackages.uninstall(UNLISTED_PACKAGE)

        assertEquals(Resolution.Only(ANKIDROID), selection().resolve())
        assertNull(stored)
    }

    /** Re-signed with another key: the user has to choose again */
    @Test
    fun changedCertificateDropsChoice() {
        installAnkiDroid()
        installUnlisted()
        selection().choose(UNLISTED)

        installUnlisted(Signing.Rotated(past = UNLISTED_KEY, current = UNLISTED_NEW_KEY))

        assertEquals(Resolution.NeedsChoice(listOf(ANKIDROID, UNLISTED)), selection().resolve())
        assertNull(stored)
    }

    @Test
    fun takenAuthorityDropsChoice() {
        installAnkiDroid()
        installUnlisted()
        selection().choose(UNLISTED)

        AnkiDroidPackages.uninstall(UNLISTED_PACKAGE)
        installUnlisted(packageName = SQUATTER)

        val resolution = selection().resolve()
        assertEquals(listOf(ANKIDROID.packageName, SQUATTER), (resolution as Resolution.NeedsChoice).providers.map { it.packageName })
        assertNull(stored)
    }

    @Test
    fun listedWithUnknownKeyIsRejected() {
        installListed(Signing.Current(UNKNOWN))

        assertEquals(Resolution.NotInstalled, selection().resolve())
    }

    @Test
    fun listedWithItsKeyIsUsed() {
        installListed(Signing.Current(LISTED_KEY))

        assertEquals(Resolution.Only(LISTED), selection().resolve())
    }

    /** Another package holds a listed authority, signed with the listed key */
    @Test
    fun squattedListedAuthorityIsRejected() {
        AnkiDroidPackages.install(SQUATTER, Signing.Current(LISTED_KEY), authority = LISTED.authority, filter = ProviderInterface.DECLARED)

        assertEquals(Resolution.NotInstalled, selection().resolve())
    }

    @Test
    fun ankiDroidWithUnknownKeyIsRejected() {
        AnkiDroidPackages.install(ANKIDROID.packageName, Signing.Current(UNKNOWN), filter = ProviderInterface.DECLARED)

        assertEquals(Resolution.NotInstalled, selection().resolve())
    }

    private fun installAnkiDroid() {
        AnkiDroidPackages.install(ANKIDROID.packageName, Signing.Current(AnkiDroidPackages.RELEASE), filter = ProviderInterface.DECLARED)
    }

    private fun installListed(signing: Signing) {
        AnkiDroidPackages.install(LISTED.packageName, signing, authority = LISTED.authority)
    }

    private fun installUnlisted(
        signing: Signing = Signing.Current(UNLISTED_KEY),
        packageName: String = UNLISTED_PACKAGE,
        permission: PermissionRequest = PermissionRequest.REQUESTED,
    ) {
        AnkiDroidPackages.install(
            packageName,
            signing,
            authority = UNLISTED_AUTHORITY,
            filter = ProviderInterface.DECLARED,
            permission = permission,
        )
    }

    private companion object {
        const val SQUATTER = "com.example.squatter"
        const val UNLISTED_PACKAGE = "org.example.ankifork"
        const val UNLISTED_AUTHORITY = "$UNLISTED_PACKAGE.flashcards"
        val LISTED_KEY = Signature(byteArrayOf(7, 8, 9))
        val UNLISTED_KEY = Signature(byteArrayOf(13, 14, 15))
        val UNLISTED_NEW_KEY = Signature(byteArrayOf(10, 11, 12))
        val UNKNOWN = Signature(byteArrayOf(1, 2, 3))

        val ANKIDROID = KnownProviders.ANKIDROID
        val LISTED =
            FlashCardsProvider(
                packageName = "com.example.listed",
                authority = "com.example.listed.flashcards",
                permission = "com.example.listed.permission.READ_WRITE_DATABASE",
                certificates = setOf(digest(LISTED_KEY)),
            )

        /** Equal to any provider of [UNLISTED_PACKAGE], see [FlashCardsProvider.equals] */
        val UNLISTED =
            FlashCardsProvider(
                packageName = UNLISTED_PACKAGE,
                authority = UNLISTED_AUTHORITY,
                permission = "$UNLISTED_PACKAGE.permission.READ_WRITE_DATABASE",
                certificates = emptySet(),
            )

        fun digest(signature: Signature) =
            MessageDigest
                .getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { "%02X".format(Locale.US, it) }
    }
}
