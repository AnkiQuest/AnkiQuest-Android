// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.app.role.RoleManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.net.toUri

/**
 * Moving from the old package, `com.ichi2.anki.quest`, to the new one, `org.ankiquest`.
 * Android cannot update an app into another package, so the same code runs as both apps, and the
 * package it runs as decides its [Role]: the old app serves its settings and logins through
 * [AnkiquestMoveProvider], the new one offers to import them on first launch
 * ([AnkiquestMoveImport]). The collection does not move; see [AnkiquestMoveImport.Next].
 */
internal object AnkiquestMove {
    const val OLD_PACKAGE = "com.ichi2.anki.quest"
    const val NEW_PACKAGE = "org.ankiquest"

    /** The old app's provider: `${applicationId}.ankiquestmove` in src/quest/AndroidManifest.xml. */
    const val AUTHORITY = "$OLD_PACKAGE.ankiquestmove"

    /** Both apps declare this signature permission, so only apps signed with the AnkiQuest key hold it. */
    const val PERMISSION = "$OLD_PACKAGE.permission.ANKIQUEST_MOVE"

    /** The provider's only `call` method; its answer holds the payload under [KEY_PAYLOAD]. */
    const val METHOD_EXPORT = "export"
    const val KEY_PAYLOAD = "payload"

    val uri: Uri = "content://$AUTHORITY".toUri()

    /** Which side of the move the running package is on. */
    enum class Role {
        /** `com.ichi2.anki.quest`: serves its settings to the new app. */
        OLD,

        /** `org.ankiquest`: offers to import them. */
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
     * @param signatures [PackageManager.checkSignatures] of a package against the running app
     * @param permission [Context.checkCallingPermission] of [PERMISSION]
     * @param roles the sensitive roles the running app holds
     */
    fun verdict(
        role: Role,
        caller: String?,
        signatures: (String) -> Int,
        permission: () -> Int,
        roles: () -> Set<String>,
    ): Verdict {
        if (role != Role.OLD) {
            return Verdict.NOT_OLD_APP
        }
        if (caller != NEW_PACKAGE) {
            return Verdict.WRONG_PACKAGE
        }
        if (signatures(caller) != PackageManager.SIGNATURE_MATCH) {
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
            signatures = { context.packageManager.checkSignatures(it, context.packageName) },
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
        // An app of another signer could hold the old package name; its settings are not taken.
        if (packages.checkSignatures(OLD_PACKAGE, context.packageName) != PackageManager.SIGNATURE_MATCH) {
            return OldApp.MISSING
        }
        return OldApp.READY
    }
}
