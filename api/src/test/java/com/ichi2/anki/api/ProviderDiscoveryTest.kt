// SPDX-FileCopyrightText: Copyright (c) 2026 hill <hill@hilll.dev>
// SPDX-License-Identifier: LGPL-3.0-or-later
package com.ichi2.anki.api

import android.content.ComponentName
import android.content.IntentFilter
import android.content.pm.ProviderInfo
import android.os.Bundle
import com.ichi2.anki.FlashCardsContract
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pinned to the pre-Tiramisu branch: Robolectric does not shadow the Tiramisu+ overloads,
 * see [AddContentApiTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
internal class ProviderDiscoveryTest {
    private val context get() = RuntimeEnvironment.getApplication()

    private val providers get() = AddContentApi.getProviders(context)

    @Test
    fun noProviderInstalled() {
        assertEquals(emptyList<String>(), providers.map { it.packageName })
        assertNull(AddContentApi.getAnkiDroidPackageName(context))
    }

    /** AnkiDroid releases before the intent filter are still found by their authority */
    @Test
    fun stockWithoutIntentFilter() {
        install(STOCK_PACKAGE, FlashCardsContract.AUTHORITY, IntentFilterDeclared.NO)

        assertEquals(listOf(STOCK_PACKAGE), providers.map { it.packageName })
        assertEquals(STOCK_PACKAGE, AddContentApi.getAnkiDroidPackageName(context))
    }

    @Test
    fun stockIsListedOnce() {
        install(STOCK_PACKAGE, FlashCardsContract.AUTHORITY, IntentFilterDeclared.YES)

        assertEquals(listOf(FlashCardsContract.AUTHORITY), providers.map { it.authority })
    }

    @Test
    fun forkOnly() {
        install(FORK_PACKAGE, FORK_AUTHORITY, IntentFilterDeclared.YES, spec = 3)

        val fork = providers.single()
        assertEquals(FORK_PACKAGE, fork.packageName)
        assertEquals(FORK_AUTHORITY, fork.authority)
        assertEquals("$FORK_PACKAGE.permission.READ_WRITE_DATABASE", fork.permission)
        assertEquals(FORK_PACKAGE, AddContentApi.getAnkiDroidPackageName(context))

        // The default AddContentApi talks to the fork: the spec version comes from its provider
        assertEquals(3, AddContentApi(context).apiHostSpecVersion)
    }

    @Test
    fun stockIsPreferredOverFork() {
        install(FORK_PACKAGE, FORK_AUTHORITY, IntentFilterDeclared.YES, spec = 3)
        install(STOCK_PACKAGE, FlashCardsContract.AUTHORITY, IntentFilterDeclared.YES, spec = 2)

        assertEquals(listOf(STOCK_PACKAGE, FORK_PACKAGE), providers.map { it.packageName })
        assertEquals(STOCK_PACKAGE, AddContentApi.getAnkiDroidPackageName(context))
        assertEquals(2, AddContentApi(context).apiHostSpecVersion)

        // A client can still pick the fork
        assertEquals(3, AddContentApi(context, providers[1]).apiHostSpecVersion)
    }

    private enum class IntentFilterDeclared { YES, NO }

    private fun install(
        packageName: String,
        authority: String,
        filter: IntentFilterDeclared,
        spec: Int = 2,
    ) {
        val manager = shadowOf(context.packageManager)
        val info =
            ProviderInfo().apply {
                this.authority = authority
                this.packageName = packageName
                name = PROVIDER_CLASS
                metaData = Bundle().apply { putInt(SPEC_META_DATA_KEY, spec) }
            }
        manager.addOrUpdateProvider(info)

        // Current builds declare FlashCardsContract.PROVIDER_INTERFACE on the provider
        if (filter == IntentFilterDeclared.NO) {
            return
        }
        manager.addIntentFilterForProvider(
            ComponentName(packageName, PROVIDER_CLASS),
            IntentFilter(FlashCardsContract.PROVIDER_INTERFACE),
        )
    }

    companion object {
        private val STOCK_PACKAGE = BuildConfig.ANKI_PACKAGE_NAME
        private const val FORK_PACKAGE = "org.example.ankifork"
        private const val FORK_AUTHORITY = "$FORK_PACKAGE.flashcards"
        private const val PROVIDER_CLASS = "com.ichi2.anki.provider.CardContentProvider"
        private const val SPEC_META_DATA_KEY = "com.ichi2.anki.provider.spec"
    }
}
