// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.provider

import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.BuildConfig
import com.ichi2.anki.FlashCardsContract
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.compat.CompatHelper.Companion.getPackageInfoCompat
import com.ichi2.anki.compat.PackageInfoFlagsCompat
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertContains
import kotlin.test.assertNotNull

@RunWith(AndroidJUnit4::class)
class CardContentProviderPermissionTest : RobolectricTest() {
    /**
     * CardContentProvider rejects callers that do not request its permission. The instrumented
     * ContentProviderTest calls it from the app's own process, so the app must request it,
     * whatever its application id.
     */
    @Test
    fun `the app requests its own provider permission`() {
        val flags = PackageInfoFlagsCompat.of(PackageManager.GET_PERMISSIONS.toLong())
        val info = assertNotNull(targetContext.getPackageInfoCompat(BuildConfig.APPLICATION_ID, flags))
        val requested = info.requestedPermissions.orEmpty().toList()

        assertContains(requested, FlashCardsContract.getPermission(BuildConfig.APPLICATION_ID))
    }
}
