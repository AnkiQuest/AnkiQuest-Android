// SPDX-FileCopyrightText: Copyright (c) 2026 hill <hill@hilll.dev>
// SPDX-License-Identifier: LGPL-3.0-or-later
package com.ichi2.anki.api

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.os.Build
import com.ichi2.anki.FlashCardsContract

/**
 * Finds installed apps offering the [FlashCardsContract] content provider.
 */
internal object ProviderDiscovery {
    /** Separates the authorities in [ProviderInfo.authority] */
    private const val AUTHORITY_SEPARATOR = ';'

    /**
     * Installed providers: AnkiDroid (the default [FlashCardsContract.AUTHORITY]) first,
     * then the others by package name.
     */
    fun find(context: Context): List<FlashCardsProvider> {
        val manager = context.packageManager

        // AnkiDroid releases older than FlashCardsContract.PROVIDER_INTERFACE only answer to their authority
        val stock = manager.resolve(FlashCardsContract.AUTHORITY)

        // Current AnkiDroid builds, parallel builds and forks declare the intent filter
        val declared = manager.queryDeclared()

        return (listOfNotNull(stock) + declared)
            .map { it.toProvider() }
            .distinctBy { it.authority }
            .sortedBy { it.packageName }
            .sortedBy { it.authority != FlashCardsContract.AUTHORITY }
    }

    /** The provider [AddContentApi] uses when none is given: AnkiDroid if installed */
    fun preferred(context: Context): FlashCardsProvider =
        find(context).firstOrNull()
            ?: FlashCardsProvider(BuildConfig.ANKI_PACKAGE_NAME, FlashCardsContract.AUTHORITY)

    private fun PackageManager.resolve(authority: String): ProviderInfo? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return resolveContentProvider(authority, PackageManager.ComponentInfoFlags.of(0L))
        }
        return resolveContentProvider(authority, 0)
    }

    private fun PackageManager.queryDeclared(): List<ProviderInfo> {
        // queryIntentContentProviders was added in API 19
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
            return emptyList()
        }

        val intent = Intent(FlashCardsContract.PROVIDER_INTERFACE)
        val matches =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                queryIntentContentProviders(intent, PackageManager.ResolveInfoFlags.of(0L))
            } else {
                queryIntentContentProviders(intent, 0)
            }
        return matches.mapNotNull { it.providerInfo }
    }

    private fun ProviderInfo.toProvider(): FlashCardsProvider =
        FlashCardsProvider(packageName, authority.substringBefore(AUTHORITY_SEPARATOR))
}
