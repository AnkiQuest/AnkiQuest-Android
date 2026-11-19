// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.app.role.RoleManager
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.ankiquest.AnkiquestMove.Role
import com.ichi2.anki.ankiquest.AnkiquestMove.Verdict
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class AnkiquestMoveTest : RobolectricTest() {
    @Test
    fun `the running package decides the role`() {
        assertEquals(Role.OLD, Role.of("com.ichi2.anki.quest"))
        assertEquals(Role.NEW, Role.of("org.ankiquest.anki"))
        for (other in listOf("com.ichi2.anki", "com.ichi2.anki.debug", "org.ankiquest.anki.debug", "dev.hilll.anki", "")) {
            assertEquals(Role.NONE, Role.of(other), other)
        }
    }

    @Test
    fun `the new app signed with the same key is served`() {
        assertEquals(Verdict.ALLOWED, verdict())
    }

    @Test
    fun `a caller other than the new package is refused`() {
        for (caller in listOf(null, "com.ichi2.anki", "com.ichi2.anki.quest", "org.ankiquest.anki.debug", "com.example.thief")) {
            assertEquals(Verdict.WRONG_PACKAGE, verdict(caller = caller), caller.toString())
        }
    }

    @Test
    fun `the new package signed with another key is refused`() {
        val mismatches = listOf(PackageManager.SIGNATURE_NO_MATCH, PackageManager.SIGNATURE_UNKNOWN_PACKAGE)
        for (answer in mismatches) {
            assertEquals(Verdict.WRONG_SIGNATURE, verdict(signature = answer), answer.toString())
        }
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
        assertEquals("${AnkiquestMove.OLD_PACKAGE}.ankiquestmove", AnkiquestMove.AUTHORITY)
    }

    private fun verdict(
        role: Role = Role.OLD,
        caller: String? = AnkiquestMove.NEW_PACKAGE,
        signature: Int = PackageManager.SIGNATURE_MATCH,
        permission: Int = PackageManager.PERMISSION_GRANTED,
        roles: Set<String> = emptySet(),
    ): Verdict = AnkiquestMove.verdict(role, caller, { signature }, { permission }, { roles })
}
