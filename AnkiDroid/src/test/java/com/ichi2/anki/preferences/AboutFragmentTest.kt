// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.preferences

import android.os.Looper
import android.text.Spanned
import android.text.style.URLSpan
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.BuildConfig
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import kotlin.test.assertContains

@RunWith(AndroidJUnit4::class)
class AboutFragmentTest : RobolectricTest() {
    @Test
    fun `About links to the source of this AnkiQuest build`() {
        val license = licence()
        val urls = license.getSpans(0, license.length, URLSpan::class.java).map(URLSpan::getURL)
        assertContains(urls, "https://github.com/AnkiQuest/AnkiQuest-Android/tree/${BuildConfig.GIT_COMMIT_HASH}")
    }

    @Test
    fun `About credits Shigeyuki for Anki-tan`() {
        val license = licence()
        assertContains(license.toString(), "Anki-tan by Shigeyuki, CC BY-SA 4.0.")
        val urls = license.getSpans(0, license.length, URLSpan::class.java).map(URLSpan::getURL)
        assertContains(urls, "https://shigeyukey.github.io/shige-addons-wiki/Anki-tan.html")
        assertContains(urls, "https://www.patreon.com/Shigeyuki")
        assertContains(urls, "https://creativecommons.org/licenses/by-sa/4.0/")
    }

    /** The About screen's licence text. */
    private fun licence(): Spanned {
        val intent = PreferencesActivity.getIntent(targetContext, AboutFragment::class)
        val controller = Robolectric.buildActivity(PreferencesActivity::class.java, intent).setup()
        saveControllerForCleanup(controller)
        shadowOf(Looper.getMainLooper()).idle()
        val parent = controller.get().fragment as PreferencesFragment
        val about = parent.childFragmentManager.findFragmentById(R.id.settings_container) as AboutFragment
        return about.binding.licenseDescription.text as Spanned
    }
}
