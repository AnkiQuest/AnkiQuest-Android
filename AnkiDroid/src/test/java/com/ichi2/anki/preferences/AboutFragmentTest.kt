// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.preferences

import android.content.Intent
import android.os.Looper
import androidx.core.net.toUri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@RunWith(AndroidJUnit4::class)
class AboutFragmentTest : RobolectricTest() {
    // Regression: with package visibility, resolveActivity() can return null although
    // an app handles the intent, and 'Rate AnkiDroid' showed 'No suitable app found'.
    @Test
    fun `Rate opens the store page when no handler resolves`() {
        val about = about()
        assertNull(
            Intent(Intent.ACTION_VIEW, marketUrl).resolveActivity(targetContext.packageManager),
            "precondition: no handler resolves",
        )

        about.binding.rateAnkiDroid.performClick()

        val started = assertNotNull(shadowOf(about.requireActivity()).nextStartedActivity)
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals(marketUrl, started.data)
    }

    @Test
    fun `Rate starts nothing when no app opens the store page`() {
        shadowOf(RuntimeEnvironment.getApplication()).checkActivities(true)
        val about = about()

        about.binding.rateAnkiDroid.performClick()

        assertNull(shadowOf(about.requireActivity()).nextStartedActivity)
    }

    /** The About screen, shown in settings. */
    private fun about(): AboutFragment {
        val intent = PreferencesActivity.getIntent(targetContext, AboutFragment::class)
        val controller = Robolectric.buildActivity(PreferencesActivity::class.java, intent).setup()
        saveControllerForCleanup(controller)
        shadowOf(Looper.getMainLooper()).idle()
        val parent = controller.get().fragment as PreferencesFragment
        return parent.childFragmentManager.findFragmentById(R.id.settings_container) as AboutFragment
    }

    /** The store page 'Rate AnkiDroid' opens. */
    private val marketUrl get() = targetContext.getString(R.string.link_market).toUri()
}
