// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import android.app.role.RoleManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.net.toUri
import com.ichi2.anki.compat.CompatHelper.Companion.getPackageInfoCompat
import com.ichi2.anki.compat.GET_SIGNING_CERTIFICATES_L
import com.ichi2.anki.compat.PackageInfoFlagsCompat
import java.security.MessageDigest

/**
 * Moving from the old package, `com.ichi2.anki.quest`, to the new one, `org.ankiquest.ankidroid`.
 * Android cannot update an app into another package, so the same code runs as both apps, and the
 * package it runs as decides its [Role]: the old app serves its settings and logins through
 * [AnkiquestMoveProvider], the new one offers to import them on first launch
 * ([AnkiquestMoveImport]). The collection does not move; see [AnkiquestMoveImport.Next].
 */
internal object AnkiquestMove {
    const val OLD_PACKAGE = "com.ichi2.anki.quest"
    const val NEW_PACKAGE = "org.ankiquest.ankidroid"

    /** The old app's provider: `${applicationId}.ankiquestmove` in src/quest/AndroidManifest.xml. */
    const val AUTHORITY = "$OLD_PACKAGE.ankiquestmove"

    /** The old app declares this signature permission; the new app requests it. */
    const val PERMISSION = "$OLD_PACKAGE.permission.ANKIQUEST_MOVE"

    /** The current project signer, as documented in the README's APK verification section. */
    private const val RELEASE_SHA256 = "b397a61ecfaf0fe354da611772515e683ccb6fd0e952d477b8b3bca49e6c490d"

    /** The provider's only `call` method; its answer holds the payload under [KEY_PAYLOAD]. */
    const val METHOD_EXPORT = "export"
    const val KEY_PAYLOAD = "payload"

    val uri: Uri = "content://$AUTHORITY".toUri()

    /** Which side of the move the running package is on. */
    enum class Role {
        /** `com.ichi2.anki.quest`: serves its settings to the new app. */
        OLD,

        /** `org.ankiquest.ankidroid`: offers to import them. */
        NEW,

        /** Every other build (AnkiDroid, debug builds, tests): takes no part. */
        NONE,
        ;

        companion object {
            fun of(packageName: String): Role =
                when (packageName) {
                    OLD_PACKAGE -> OLD
                    NEW_PACKAGE -> NEW
                    else -> NONE
                }
        }
    }

    /** Whether the provider serves a call, and if not, why. Only the name is ever logged. */
    enum class Verdict {
        ALLOWED,
        NOT_OLD_APP,
        WRONG_PACKAGE,
        WRONG_SIGNATURE,
        NO_PERMISSION,
        SENSITIVE_ROLE,
    }

    /**
     * Checks a call to the provider. The manifest permission already keeps other signers out;
     * this repeats the check in code, names the one package allowed to ask, and stops serving
     * while the old app holds a sensitive role, which no AnkiQuest build asks for.
     *
     * @param trustedSigners whether both apps have the current release signer
     * @param permission [Context.checkCallingPermission] of [PERMISSION]
     * @param roles the sensitive roles the running app holds
     */
    fun verdict(
        role: Role,
        caller: String?,
        trustedSigners: (String) -> Boolean,
        permission: () -> Int,
        roles: () -> Set<String>,
    ): Verdict {
        if (role != Role.OLD) {
            return Verdict.NOT_OLD_APP
        }
        if (caller != NEW_PACKAGE) {
            return Verdict.WRONG_PACKAGE
        }
        if (!trustedSigners(caller)) {
            return Verdict.WRONG_SIGNATURE
        }
        if (permission() != PackageManager.PERMISSION_GRANTED) {
            return Verdict.NO_PERMISSION
        }
        if (roles().isNotEmpty()) {
            return Verdict.SENSITIVE_ROLE
        }
        return Verdict.ALLOWED
    }

    /** [verdict] for a call reaching the running app from [caller]. */
    fun verdict(
        context: Context,
        caller: String?,
    ): Verdict =
        verdict(
            role = Role.of(context.packageName),
            caller = caller,
            trustedSigners = { trustedPair(context, it) },
            permission = { context.checkCallingPermission(PERMISSION) },
            roles = { heldRoles(context) },
        )

    /** The sensitive roles the running app holds; roles exist from Android 10. */
    fun heldRoles(context: Context): Set<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return emptySet()
        }
        val manager = context.getSystemService(RoleManager::class.java) ?: return emptySet()
        return sensitiveRoles().filter { manager.isRoleAvailable(it) && manager.isRoleHeld(it) }.toSet()
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun sensitiveRoles(): List<String> =
        listOf(
            RoleManager.ROLE_ASSISTANT,
            RoleManager.ROLE_BROWSER,
            RoleManager.ROLE_CALL_REDIRECTION,
            RoleManager.ROLE_CALL_SCREENING,
            RoleManager.ROLE_DIALER,
            RoleManager.ROLE_EMERGENCY,
            RoleManager.ROLE_HOME,
            RoleManager.ROLE_SMS,
        )

    /** Whether the new app can import: the old app is installed, serves the move and is signed like this app. */
    enum class OldApp {
        MISSING,
        READY,
    }

    fun oldApp(context: Context): OldApp {
        val packages = context.packageManager
        val provider = packages.resolveContentProvider(AUTHORITY, 0) ?: return OldApp.MISSING
        if (provider.packageName != OLD_PACKAGE) {
            return OldApp.MISSING
        }
        if (!trustedPair(context, OLD_PACKAGE) || context.checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            return OldApp.MISSING
        }
        return OldApp.READY
    }

    private fun trustedPair(
        context: Context,
        other: String,
    ): Boolean {
        // Android 7 and 8 see the public fallback key on the old package. They cannot verify a move.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return false
        }
        val packages = context.packageManager
        return hasReleaseSigner(packages, context.packageName) && hasReleaseSigner(packages, other)
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun hasReleaseSigner(
        packages: PackageManager,
        name: String,
    ): Boolean {
        val info =
            try {
                packages.getPackageInfoCompat(name, PackageInfoFlagsCompat.of(GET_SIGNING_CERTIFICATES_L))
            } catch (_: PackageManager.NameNotFoundException) {
                return false
            }
        // The old APK includes the fallback key in its history. Only the current signer counts.
        val signer = info?.signingInfo?.apkContentsSigners?.singleOrNull() ?: return false
        return MessageDigest.getInstance("SHA-256").digest(signer.toByteArray()).toHexString() == RELEASE_SHA256
    }
}
