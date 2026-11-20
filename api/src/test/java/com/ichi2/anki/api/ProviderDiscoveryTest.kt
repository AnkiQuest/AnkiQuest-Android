// SPDX-FileCopyrightText: Copyright (c) 2026 hill <hill@hilll.dev>
// SPDX-License-Identifier: LGPL-3.0-or-later
package com.ichi2.anki.api

import android.content.pm.ApplicationInfo
import android.content.pm.Signature
import android.os.Build
import android.os.Bundle
import com.ichi2.anki.FlashCardsContract
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** Robolectric doesn't shadow the Tiramisu+ overloads, see [AddContentApiTest] */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.S_V2])
internal class ProviderDiscoveryTest {
    private val context get() = RuntimeEnvironment.getApplication()

    private val providers get() = AddContentApi.getProviders(context)

    @Before
    fun setUp() {
        val info = context.applicationInfo
        info.flags = info.flags and ApplicationInfo.FLAG_DEBUGGABLE.inv()
    }

    @Test
    fun noProviderInstalled() {
        assertEquals(emptyList(), providers)
    }

    /** AnkiDroid releases before the intent filter are still found by their authority */
    @Test
    fun ankiDroidWithoutIntentFilter() {
        installAnkiDroid(ProviderInterface.NOT_DECLARED)

        assertEquals(listOf(STOCK_PACKAGE), providers.map { it.packageName })
    }

    @Test
    fun ankiDroidIsListedOnce() {
        installAnkiDroid(ProviderInterface.DECLARED)

        assertEquals(listOf(FlashCardsContract.AUTHORITY), providers.map { it.authority })
    }

    /** An app found only by its intent filter talks through its own authority */
    @Test
    fun forkIsFoundByIntentFilter() {
        installFork(FORK_PACKAGE, spec = 3)

        assertEquals(listOf(FORK_AUTHORITY), providers.map { it.authority })
        assertEquals(3, AddContentApi(context).apiHostSpecVersion)
    }

    @Test
    fun ankiDroidFirstThenByPackage() {
        installFork(LATER_FORK_PACKAGE)
        installFork(FORK_PACKAGE)
        installAnkiDroid(ProviderInterface.DECLARED)

        assertEquals(listOf(STOCK_PACKAGE, FORK_PACKAGE, LATER_FORK_PACKAGE), providers.map { it.packageName })
    }

    private fun installAnkiDroid(filter: ProviderInterface) {
        AnkiDroidPackages.install(STOCK_PACKAGE, Signing.Current(AnkiDroidPackages.RELEASE), filter = filter)
    }

    private fun installFork(
        packageName: String,
        spec: Int = 2,
    ) {
        AnkiDroidPackages.install(
            packageName,
            Signing.Current(FORK_KEY),
            Bundle().apply { putInt("com.ichi2.anki.provider.spec", spec) },
            authority = "$packageName.flashcards",
            filter = ProviderInterface.DECLARED,
        )
    }

    companion object {
        private val STOCK_PACKAGE = BuildConfig.ANKI_PACKAGE_NAME
        private const val FORK_PACKAGE = "org.example.ankifork"
        private const val LATER_FORK_PACKAGE = "org.example.zfork"
        private const val FORK_AUTHORITY = "$FORK_PACKAGE.flashcards"
        private val FORK_KEY = Signature(byteArrayOf(7, 8, 9))
    }
}
