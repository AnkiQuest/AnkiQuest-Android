// SPDX-FileCopyrightText: Copyright (c) 2026 hill <hill@hilll.dev>
// SPDX-License-Identifier: LGPL-3.0-or-later
package com.ichi2.anki.api

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ApplicationInfo
import android.content.pm.Signature
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Records the URIs it is queried with */
internal class RecordingProvider : ContentProvider() {
    val queried = mutableListOf<Uri>()

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        queried += uri
        return null
    }

    override fun getType(uri: Uri): String? = null

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = null

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}

/** [AddContentApi] with [KnownProviders.ANKIQUEST], signed with its release key */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.S_V2])
internal class ProviderChoiceApiTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        // A released client, which trusts only the listed certificates
        val info = context.applicationInfo
        info.flags = info.flags and ApplicationInfo.FLAG_DEBUGGABLE.inv()
    }

    /** The only provider installed is used without a choice */
    @Test
    fun onlyForkIsUsed() {
        installFork()

        assertEquals(listOf(FORK), AddContentApi.getProviders(context))
        assertEquals(FORK.packageName, AddContentApi.getAnkiDroidPackageName(context))
        assertEquals(FORK.permission, AddContentApi.getProvider(context)?.permission)
        assertEquals(FORK_SPEC, AddContentApi(context).apiHostSpecVersion)
        assertNull(shadowOf(context).nextStartedActivity)
    }

    @Test
    fun chosenForkIsUsed() {
        installFork()
        AnkiDroidPackages.install(ANKIDROID.packageName, Signing.Current(AnkiDroidPackages.RELEASE))
        val recorder = Robolectric.buildContentProvider(RecordingProvider::class.java).create(FORK.authority).get()

        ProviderSelection(context).choose(FORK)

        assertEquals(FORK.packageName, AddContentApi.getAnkiDroidPackageName(context))
        assertEquals(FORK.authority, AddContentApi.getProvider(context)?.authority)
        assertEquals(FORK_SPEC, AddContentApi(context).apiHostSpecVersion)

        // Every URI is built from the fork's authority
        assertNull(AddContentApi(context).deckList)
        assertEquals(listOf(FORK.authority), recorder.queried.map { it.authority })
    }

    @Test
    fun forkWithUnknownKeyIsRejected() {
        installFork(Signature(byteArrayOf(1, 2, 3)))

        ProviderSelection(context).choose(FORK)

        assertEquals(emptyList(), AddContentApi.getProviders(context))
        assertNull(AddContentApi.getAnkiDroidPackageName(context))
    }

    /** With several providers, nothing is used until the user chooses */
    @Test
    fun severalNeedChoice() {
        installFork()
        AnkiDroidPackages.install(ANKIDROID.packageName, Signing.Current(AnkiDroidPackages.RELEASE))

        assertEquals(listOf(ANKIDROID, FORK), AddContentApi.getProviders(context))
        assertNull(AddContentApi.getProvider(context))
        assertEquals(-1, AddContentApi(context).apiHostSpecVersion)
    }

    /** The chooser opens by itself, once */
    @Test
    fun chooserOpensOnce() {
        installFork()
        val launcher = ChooserLauncher()

        launcher.offer(context)
        launcher.offer(context)

        val app = shadowOf(context)
        assertEquals(ProviderChooserActivity::class.java.name, app.nextStartedActivity?.component?.className)
        assertNull(app.nextStartedActivity)
    }

    @Test
    fun chooserOpensFromApi() {
        AddContentApi.chooseProvider(context)

        assertEquals(ProviderChooserActivity::class.java.name, shadowOf(context).nextStartedActivity?.component?.className)
    }

    private fun installFork(key: Signature = AnkiDroidPackages.ANKIQUEST_RELEASE) {
        AnkiDroidPackages.install(
            FORK.packageName,
            Signing.Current(key),
            Bundle().apply { putInt("com.ichi2.anki.provider.spec", FORK_SPEC) },
            authority = FORK.authority,
        )
    }

    private companion object {
        const val FORK_SPEC = 3
        val ANKIDROID = KnownProviders.ANKIDROID
        val FORK = KnownProviders.ANKIQUEST
    }
}
