// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.provider

import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.BuildConfig
import com.ichi2.anki.FlashCardsContract
import com.ichi2.anki.RobolectricTest
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertContains

@RunWith(AndroidJUnit4::class)
class CardContentProviderPermissionTest : RobolectricTest() {
    /**
     * CardContentProvider rejects callers that do not request its permission. The instrumented
     * ContentProviderTest calls it from the app's own process, so the app must request it,
     * whatever its application id.
     */
    @Test
    fun `the app requests its own provider permission`() {
        val info = targetContext.packageManager.getPackageInfo(BuildConfig.APPLICATION_ID, PackageManager.GET_PERMISSIONS)
        val requested = info.requestedPermissions.orEmpty().toList()

        assertContains(requested, FlashCardsContract.getPermission(BuildConfig.APPLICATION_ID))
    }
}
