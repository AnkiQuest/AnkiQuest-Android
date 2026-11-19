// SPDX-FileCopyrightText: Copyright (c) 2026 hill <hill@hilll.dev>
// SPDX-License-Identifier: LGPL-3.0-or-later
package com.ichi2.anki.api

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.content.pm.Signature
import android.os.Build
import android.util.Log
import com.ichi2.anki.FlashCardsContract
import java.security.MessageDigest
import java.util.Locale

/**
 * Finds the app behind a [FlashCardsProvider]'s authority and checks that it is the listed app:
 * AnkiDroid ([FlashCardsContract.AUTHORITY]) by default, or another of [KnownProviders].
 *
 * Any app can declare the authority while AnkiDroid is not installed, and would then receive
 * the notes that clients add. The provider is trusted only if its package is the listed one and
 * it is signed with one of the listed keys.
 *
 * Self-built and debug builds are signed with other keys. They are trusted only
 * while the calling app is debuggable, so a released client never accepts them.
 */
internal object AnkiDroidProvider {
    private const val TAG = "AnkiDroidApi"
    private const val DIGEST_ALGORITHM = "SHA-256"
    private const val SIGNER_SEPARATOR = ","

    /**
     * SHA-256 digests of the certificates AnkiDroid's releases are signed with.
     *
     * Read from the APK signing block of the releases on
     * https://github.com/ankidroid/Anki-Android/releases (play and full flavors) and of
     * com.ichi2.anki_225033004.apk on https://f-droid.org.
     */
    val RELEASE_CERTIFICATES =
        setOf(
            // Google Play and GitHub releases: CN=Nicolas Raoul, O=http://nrw.free.fr
            "2071534F0F4B5E54AE952DD275D70DA6E3459EE69909D2AB1B4843C4C5B21A45",
            // F-Droid builds AnkiDroid itself and signs it with its own key for this app
            "24A3D4756C62DD69C86DF69AA7FA327B2A6BF55F55C4A1749F99BD1DA75906BB",
        )

    /** Outcome of checking the app that holds a provider's authority */
    private enum class Trust(
        val trusted: Boolean,
    ) {
        /** The listed app, signed with a listed key */
        GENUINE(trusted = true),

        /** The listed package with another key, accepted because the calling app is debuggable */
        DEBUG_BUILD(trusted = true),

        /** Another package holds the authority */
        WRONG_PACKAGE(trusted = false),

        /** The listed package, not signed with a listed key */
        UNKNOWN_CERTIFICATE(trusted = false),
    }

    /**
     * The provider behind [listed]'s authority if its app is the listed one.
     * @param flags [PackageManager] flags for resolving the provider, such as [PackageManager.GET_META_DATA]
     * @return null if the app is not installed, or if another app holds the authority
     */
    fun resolve(
        context: Context,
        flags: Int = 0,
        listed: FlashCardsProvider = KnownProviders.ANKIDROID,
    ): ProviderInfo? {
        val provider = find(context.packageManager, flags, listed.authority) ?: return null
        if (!check(context, listed, provider).trusted) {
            return null
        }
        return provider
    }

    /** Whether an app other than the [listed] one holds its authority */
    fun isImpostor(
        context: Context,
        listed: FlashCardsProvider = KnownProviders.ANKIDROID,
    ): Boolean {
        val provider = find(context.packageManager, 0, listed.authority) ?: return false
        return !check(context, listed, provider).trusted
    }

    /**
     * Digests of the certificates [packageName] is signed with now, without past ones,
     * so that a choice pinned to them is dropped when the key changes.
     * @return null if the package is not installed or unsigned
     */
    @Suppress("DEPRECATION") // GET_SIGNATURES and signatures, used only before API 28
    fun signer(
        context: Context,
        packageName: String,
    ): String? {
        val manager = context.packageManager
        val signers =
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                packageInfo(manager, packageName, PackageManager.GET_SIGNATURES)?.signatures
            } else {
                packageInfo(manager, packageName, PackageManager.GET_SIGNING_CERTIFICATES)?.signingInfo?.apkContentsSigners
            }
        if (signers.isNullOrEmpty()) {
            return null
        }
        return signers.map { digest(it) }.sorted().joinToString(SIGNER_SEPARATOR)
    }

    /** Checks the provider's package name and certificate, and logs why it is not trusted */
    private fun check(
        context: Context,
        listed: FlashCardsProvider,
        provider: ProviderInfo,
    ): Trust {
        val trust = trustOf(context, listed, provider.packageName)

        // Say why the app counts as not installed, so developers can find the cause
        when (trust) {
            Trust.GENUINE -> {}
            Trust.DEBUG_BUILD -> Log.i(TAG, "Accepting ${provider.packageName} without a release key: this app is debuggable")
            Trust.WRONG_PACKAGE ->
                Log.w(TAG, "${provider.packageName} holds ${listed.authority}: treating ${listed.packageName} as not installed")
            Trust.UNKNOWN_CERTIFICATE ->
                Log.w(TAG, "${provider.packageName} is not signed with a listed key: treating it as not installed")
        }
        return trust
    }

    private fun trustOf(
        context: Context,
        listed: FlashCardsProvider,
        packageName: String,
    ): Trust {
        // Only the listed package name can hold the listed authority
        if (packageName != listed.packageName) {
            return Trust.WRONG_PACKAGE
        }

        // A rotated key keeps its past certificates, so any match is the listed app
        val digests = certificates(context.packageManager, packageName).map { digest(it) }
        if (digests.any { it in listed.certificates }) {
            return Trust.GENUINE
        }

        // Developers of a debuggable client may test against their own AnkiDroid build
        if ((context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            return Trust.DEBUG_BUILD
        }
        return Trust.UNKNOWN_CERTIFICATE
    }

    @SuppressLint("WrongConstant") // ComponentInfoFlags bug: GET_META_DATA.toLong() was invalid
    private fun find(
        manager: PackageManager,
        flags: Int,
        authority: String,
    ): ProviderInfo? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return manager.resolveContentProvider(
                authority,
                PackageManager.ComponentInfoFlags.of(flags.toLong()),
            )
        }
        return manager.resolveContentProvider(authority, flags)
    }

    /** The package's signing certificates, including past ones of a rotated key */
    @Suppress("DEPRECATION") // GET_SIGNATURES and signatures, used only before API 28
    private fun certificates(
        manager: PackageManager,
        packageName: String,
    ): Array<Signature> {
        // Before API 28, only the current certificates are available
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return packageInfo(manager, packageName, PackageManager.GET_SIGNATURES)?.signatures ?: emptyArray()
        }

        // An APK signed by several signers has no rotation history
        val info = packageInfo(manager, packageName, PackageManager.GET_SIGNING_CERTIFICATES)?.signingInfo ?: return emptyArray()
        if (info.hasMultipleSigners()) {
            return info.apkContentsSigners ?: emptyArray()
        }
        return info.signingCertificateHistory ?: emptyArray()
    }

    @SuppressLint("WrongConstant") // as in find()
    @Suppress("DEPRECATION") // getPackageInfo(String, Int), used only before API 33
    private fun packageInfo(
        manager: PackageManager,
        packageName: String,
        flags: Int,
    ): PackageInfo? =
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                manager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
            } else {
                manager.getPackageInfo(packageName, flags)
            }
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w(TAG, "$packageName holds a provider but has no package info", e)
            null
        }

    /** Upper-case hex SHA-256 of the certificate, as `apksigner` and `keytool` print it */
    private fun digest(certificate: Signature): String =
        MessageDigest
            .getInstance(DIGEST_ALGORITHM)
            .digest(certificate.toByteArray())
            .joinToString("") { "%02X".format(Locale.US, it) }
}
