// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.view.View
import androidx.core.view.isVisible
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.Info
import com.ichi2.anki.MoreFragment
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import com.ichi2.testutils.launchFragmentInContainer
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import kotlin.test.assertFalse

/** AnkiQuest offers Rate AnkiQuest on About and its changelog only, and never Rate AnkiDroid. */
@RunWith(AndroidJUnit4::class)
class AnkiquestNoRateTest : RobolectricTest() {
    // A store app is installed, so upstream AnkiDroid would offer Rate AnkiDroid
    @Before
    fun installStore() {
        val store =
            ResolveInfo().apply {
                activityInfo =
                    ActivityInfo().apply {
                        packageName = "com.android.vending"
                        name = "Store"
                    }
            }
        shadowOf(targetContext.packageManager).addResolveInfoForIntent(AnkiDroidApp.getMarketIntent(targetContext), store)
    }

    @Test
    fun `AnkiDroid's changelog has no Rate button`() {
        val info = startActivityNormallyOpenCollectionWithIntent(Info::class.java, Intent(targetContext, Info::class.java))

        assertFalse(info.findViewById<View>(R.id.left_button).isVisible)
    }

    @Test
    fun `More has no Rate entry`() {
        launchFragmentInContainer<MoreFragment>().use { scenario ->
            scenario.onFragment { more ->
                assertFalse(more.requireView().findViewById<View>(R.id.more_support_rate).isVisible)
            }
        }
    }
}
