// SPDX-FileCopyrightText: Copyright (c) 2026 hill <hill@hilll.dev>
// SPDX-License-Identifier: LGPL-3.0-or-later
package com.ichi2.anki.api

import android.app.Activity
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** [ProviderChooserActivity] across its lifecycle. Robolectric doesn't shadow the Tiramisu+ overloads */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.S_V2])
internal class ProviderChooserActivityTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        // A debuggable caller accepts any certificate, see AnkiDroidProvider
        val info = context.applicationInfo
        info.flags = info.flags and ApplicationInfo.FLAG_DEBUGGABLE.inv()

        AnkiDroidPackages.install(
            KnownProviders.ANKIDROID.packageName,
            Signing.Current(AnkiDroidPackages.RELEASE),
            filter = ProviderInterface.DECLARED,
        )
        AnkiDroidPackages.install(
            FORK_PACKAGE,
            Signing.Current(FORK_KEY),
            authority = "$FORK_PACKAGE.flashcards",
            filter = ProviderInterface.DECLARED,
        )
    }

    /** Android delivers the permission result to the activity it recreated while the dialog was open */
    @Test
    fun grantSurvivesRecreation() {
        val controller = Robolectric.buildActivity(ProviderChooserActivity::class.java).setup()

        // Pick the fork, whose permission is not granted yet
        shadowOf(ShadowAlertDialog.getLatestAlertDialog()).clickOnItem(FORK_ROW)
        val request = assertNotNull(shadowOf(controller.get()).lastRequestedPermission)
        val permission = request.requestedPermissions.single()

        // Recreated while Android's permission dialog is shown, then granted
        controller.recreate()
        shadowOf(context).grantPermissions(permission)
        controller.get().onRequestPermissionsResult(
            request.requestCode,
            arrayOf(permission),
            intArrayOf(PackageManager.PERMISSION_GRANTED),
        )

        assertEquals(FORK_PACKAGE, ChoiceStore(context).load()?.packageName)
        assertEquals(Activity.RESULT_OK, shadowOf(controller.get()).resultCode)
        assertTrue(controller.get().isFinishing)
    }

    private companion object {
        const val FORK_PACKAGE = "org.example.ankifork"
        val FORK_KEY = Signature(byteArrayOf(7, 8, 9))

        /** AnkiDroid is listed first, see ProviderDiscovery.find */
        const val FORK_ROW = 1
    }
}
