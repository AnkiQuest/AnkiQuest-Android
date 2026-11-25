// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

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
 * The final old-package release exposes its settings and logins. This app verifies that release
 * before [AnkiquestMoveImport] offers to read them on first launch. The apps can open the same
 * collection in the shared AnkiDroid folder. Separate folders need sync or export and import.
 */
internal object AnkiquestMove {
    const val OLD_PACKAGE = "com.ichi2.anki.quest"
    const val NEW_PACKAGE = "org.ankiquest.ankidroid"

    /** The authority declared by the last old-package release. */
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
        /** `com.ichi2.anki.quest`: the original package. */
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

    /** Whether the new app can import: the old app is installed, serves the move and is signed like this app. */
    enum class OldApp {
        MISSING,
        READY,
    }

    fun oldApp(context: Context): OldApp {
        // Android 7 and 8 see the public fallback key on the old package. They cannot verify a move.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return OldApp.MISSING
        }
        val packages = context.packageManager
        val provider = packages.resolveContentProvider(AUTHORITY, 0) ?: return OldApp.MISSING
        if (provider.packageName != OLD_PACKAGE) {
            return OldApp.MISSING
        }
        if (!hasReleaseSigner(packages, OLD_PACKAGE) || !hasReleaseSigner(packages, context.packageName)) {
            return OldApp.MISSING
        }
        if (context.checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            return OldApp.MISSING
        }
        return OldApp.READY
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
