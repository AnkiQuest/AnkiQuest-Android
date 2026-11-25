// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import android.annotation.SuppressLint
import android.app.role.RoleManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.ankiquest.AnkiquestMove.Role
import com.ichi2.anki.ankiquest.AnkiquestMove.Verdict
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class AnkiquestMoveTest : RobolectricTest() {
    @Test
    fun `the running package decides the role`() {
        assertEquals(Role.OLD, Role.of("com.ichi2.anki.quest"))
        assertEquals(Role.NEW, Role.of("org.ankiquest.ankidroid"))
        for (other in listOf(
            "com.ichi2.anki",
            "com.ichi2.anki.debug",
            "org.ankiquest.ankidroid.debug",
            "org.ankiquest",
            "org.ankiquest.anki",
            "dev.hilll.anki",
            "",
        )) {
            assertEquals(Role.NONE, Role.of(other), other)
        }
    }

    @Test
    fun `the new app signed with the same key is served`() {
        assertEquals(Verdict.ALLOWED, verdict())
    }

    @Test
    @Config(sdk = [27])
    fun `matching legacy signatures do not allow a settings transfer on Android 8`() {
        val packages = mockk<PackageManager>()
        every { packages.checkSignatures(AnkiquestMove.NEW_PACKAGE, AnkiquestMove.OLD_PACKAGE) } returns PackageManager.SIGNATURE_MATCH
        val bridge = mockk<Context>()
        every { bridge.packageName } returns AnkiquestMove.OLD_PACKAGE
        every { bridge.packageManager } returns packages
        every { bridge.checkCallingPermission(AnkiquestMove.PERMISSION) } returns PackageManager.PERMISSION_GRANTED

        assertEquals(Verdict.WRONG_SIGNATURE, AnkiquestMove.verdict(bridge, AnkiquestMove.NEW_PACKAGE))
    }

    @Test
    fun `a caller other than the new package is refused`() {
        for (caller in listOf(
            null,
            "com.ichi2.anki",
            "com.ichi2.anki.quest",
            "org.ankiquest.ankidroid.debug",
            "org.ankiquest",
            "org.ankiquest.anki",
            "com.example.thief",
        )) {
            assertEquals(Verdict.WRONG_PACKAGE, verdict(caller = caller), caller.toString())
        }
    }

    @Test
    fun `the new package signed with another key is refused`() {
        assertEquals(Verdict.WRONG_SIGNATURE, verdict(trustedSigners = false))
    }

    @Test
    fun `only the old app serves`() {
        for (role in listOf(Role.NEW, Role.NONE)) {
            assertEquals(Verdict.NOT_OLD_APP, verdict(role = role), role.name)
        }
    }

    @Test
    fun `a caller without the permission is refused`() {
        assertEquals(Verdict.NO_PERMISSION, verdict(permission = PackageManager.PERMISSION_DENIED))
    }

    @Test
    // A string constant compiled into the test, which runs on the JVM.
    @SuppressLint("InlinedApi")
    fun `the old app does not serve while it holds a sensitive role`() {
        assertEquals(Verdict.SENSITIVE_ROLE, verdict(roles = setOf(RoleManager.ROLE_SMS)))
    }

    @Test
    fun `a test build is not the old app`() {
        assertEquals(Verdict.NOT_OLD_APP, AnkiquestMove.verdict(targetContext, AnkiquestMove.NEW_PACKAGE))
    }

    @Test
    fun `the quest manifest guards the provider with a signature permission`() {
        val manifest = File("src/quest/AndroidManifest.xml").readText()
        val permission = Regex.escape(AnkiquestMove.PERMISSION)
        assertTrue(Regex("""<permission\s+android:name="$permission"\s+android:protectionLevel="signature"""").containsMatchIn(manifest))
        assertTrue(Regex("""<uses-permission android:name="$permission"""").containsMatchIn(manifest))
        val provider =
            Regex("""<provider[^>]*AnkiquestMoveProvider[^>]*/>""").find(manifest)?.value
                ?: error("the quest manifest declares no AnkiquestMoveProvider")
        assertTrue("""android:authorities="${'$'}{applicationId}.ankiquestmove"""" in provider, provider)
        assertTrue("""android:exported="true"""" in provider, provider)
        assertTrue("""android:permission="${AnkiquestMove.PERMISSION}"""" in provider, provider)
        assertTrue("""<package android:name="${AnkiquestMove.OLD_PACKAGE}" />""" in manifest, "the new app must see the old package")
        assertTrue("""<package android:name="${AnkiquestMove.NEW_PACKAGE}" />""" in manifest, "the old app checks the new signer")
        assertEquals("${AnkiquestMove.OLD_PACKAGE}.ankiquestmove", AnkiquestMove.AUTHORITY)
    }

    private fun verdict(
        role: Role = Role.OLD,
        caller: String? = AnkiquestMove.NEW_PACKAGE,
        trustedSigners: Boolean = true,
        permission: Int = PackageManager.PERMISSION_GRANTED,
        roles: Set<String> = emptySet(),
    ): Verdict = AnkiquestMove.verdict(role, caller, { trustedSigners }, { permission }, { roles })
}
