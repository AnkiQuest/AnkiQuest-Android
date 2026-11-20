// SPDX-FileCopyrightText: Copyright (c) 2026 hill <hill@hilll.dev>
// SPDX-License-Identifier: LGPL-3.0-or-later
package com.ichi2.anki.api

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.os.Build
import android.util.Log
import com.ichi2.anki.FlashCardsContract

/**
 * Finds installed apps offering the [FlashCardsContract] content provider: any app whose
 * provider declares [FlashCardsContract.ACTION_FLASHCARDS_PROVIDER], and the [KnownProviders] by
 * their authority.
 *
 * A known provider is a candidate only if its app passes [AnkiDroidProvider]'s certificate
 * check. Any other app is a candidate whose trust comes from the user's choice, which
 * [ProviderSelection] pins to its certificate.
 */
internal object ProviderDiscovery {
    private const val TAG = "AnkiDroidApi"

    /** Separates the authorities in [ProviderInfo.authority] */
    private const val AUTHORITY_SEPARATOR = ';'

    /**
     * Installed candidates: the [known] ones first, in their order, then the others by package name.
     * Apps whose permission the client app does not declare are left out, since Android
     * never grants an undeclared permission.
     */
    fun find(
        context: Context,
        known: List<FlashCardsProvider> = KnownProviders.ALL,
    ): List<FlashCardsProvider> {
        val manager = context.packageManager

        // Releases older than FlashCardsContract.ACTION_FLASHCARDS_PROVIDER only answer to their authority
        val listed = known.mapNotNull { manager.resolve(it.authority) }

        // Current AnkiDroid builds, parallel builds and forks declare the intent filter
        val declared = manager.queryDeclared()

        val requested = requestedPermissions(context)
        return (listed + declared)
            .distinctBy { it.packageName to it.firstAuthority }
            .mapNotNull { candidate(context, known, it) }
            .distinct()
            .filter { isRequestable(it, requested) }
            .sortedBy { it.packageName }
            .sortedBy { known.indexOf(it).takeIf { index -> index >= 0 } ?: known.size }
    }

    /** The provider [info] offers, or null if it claims to be a known provider and is not */
    private fun candidate(
        context: Context,
        known: List<FlashCardsProvider>,
        info: ProviderInfo,
    ): FlashCardsProvider? {
        val authority = info.firstAuthority

        // A known authority or package must be the known app, signed with its key
        val listed = known.firstOrNull { it.authority == authority || it.packageName == info.packageName }
        if (listed != null) {
            if (listed.authority != authority || !AnkiDroidProvider.trusts(context, listed, info)) {
                return null
            }
            return listed
        }

        return FlashCardsProvider(
            packageName = info.packageName,
            authority = authority,
            permission = FlashCardsContract.getPermission(info.packageName),
            certificates = emptySet(),
        )
    }

    private fun isRequestable(
        provider: FlashCardsProvider,
        requested: Set<String>,
    ): Boolean {
        if (provider.permission in requested) {
            return true
        }
        Log.w(TAG, "Ignoring ${provider.packageName}: declare <uses-permission android:name=\"${provider.permission}\"/> to use it")
        return false
    }

    @Suppress("DEPRECATION") // getPackageInfo(String, Int), used only before API 33
    private fun requestedPermissions(context: Context): Set<String> {
        val manager = context.packageManager
        val info =
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    manager.getPackageInfo(
                        context.packageName,
                        PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
                    )
                } else {
                    manager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
                }
            } catch (e: PackageManager.NameNotFoundException) {
                Log.w(TAG, "No package info for the client app", e)
                return emptySet()
            }
        return info.requestedPermissions?.toSet() ?: emptySet()
    }

    private val ProviderInfo.firstAuthority: String
        get() = authority.substringBefore(AUTHORITY_SEPARATOR)

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

        val intent = Intent(FlashCardsContract.ACTION_FLASHCARDS_PROVIDER)
        val matches =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                queryIntentContentProviders(intent, PackageManager.ResolveInfoFlags.of(0L))
            } else {
                queryIntentContentProviders(intent, 0)
            }
        return matches.mapNotNull { it.providerInfo }
    }
}
