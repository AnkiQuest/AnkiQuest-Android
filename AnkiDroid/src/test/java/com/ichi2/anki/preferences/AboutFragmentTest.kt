// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.preferences

import android.content.ClipboardManager
import android.content.Intent
import android.os.Looper
import android.text.Spanned
import android.text.style.URLSpan
import android.view.View
import androidx.core.content.getSystemService
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.BuildConfig
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.ankiquest.AnkiquestChangelogActivity
import com.ichi2.anki.databinding.FragmentAboutBinding
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@RunWith(AndroidJUnit4::class)
class AboutFragmentTest : RobolectricTest() {
    @Test
    fun `About links to the source of this AnkiQuest build`() {
        val license = licence()
        val urls = license.getSpans(0, license.length, URLSpan::class.java).map(URLSpan::getURL)
        assertContains(urls, "https://github.com/AnkiQuest/AnkiQuest-Android/tree/${BuildConfig.GIT_COMMIT_HASH}")
    }

    @Test
    fun `About credits link the server and the desktop add-on separately`() {
        val credits = about().binding.contributorsDescription.text as Spanned
        val links =
            credits.getSpans(0, credits.length, URLSpan::class.java).associate { span ->
                credits.subSequence(credits.getSpanStart(span), credits.getSpanEnd(span)).toString() to span.url
            }
        assertEquals("https://github.com/AnkiQuest/AnkiQuest-Server", links["AnkiQuest server"])
        assertEquals("https://github.com/AnkiQuest/AnkiQuest-Desktop", links["AnkiQuest desktop add-on"])
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

    // Regression: GrapheneOS showed "No suitable app found" because the
    // button only started the browser after a resolveActivity() check.
    @Test
    fun `Report a bug opens the AnkiQuest issue form in a browser`() {
        val about = about()
        about.binding.reportBug.performClick()

        val started = assertNotNull(shadowOf(about.requireActivity()).nextStartedActivity)
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals(BUG_FORM, started.dataString)
        assertContains(started.categories.orEmpty(), Intent.CATEGORY_BROWSABLE)
    }

    @Test
    fun `Report a bug copies the link when no app opens it`() {
        shadowOf(RuntimeEnvironment.getApplication()).checkActivities(true)
        val about = about()
        about.binding.reportBug.performClick()

        assertNull(shadowOf(about.requireActivity()).nextStartedActivity)
        val clipboard = assertNotNull(targetContext.getSystemService<ClipboardManager>())
        assertEquals(
            BUG_FORM,
            clipboard.primaryClip
                ?.getItemAt(0)
                ?.text
                ?.toString(),
        )
    }

    @Test
    fun `Rate AnkiQuest opens the repository while there is no store listing`() {
        assertEquals("https://github.com/AnkiQuest/AnkiQuest-Android", clicked { rateAnkiquest })
    }

    @Test
    fun `AnkiQuest changelog opens the in-app changelog`() {
        val about = about()
        about.binding.ankiquestChangelog.performClick()

        val started = assertNotNull(shadowOf(about.requireActivity()).nextStartedActivity)
        assertEquals(AnkiquestChangelogActivity::class.java.name, started.component?.className)
    }

    /** The link the About button [button] opens. */
    private fun clicked(button: FragmentAboutBinding.() -> View): String? {
        val about = about()
        about.binding.button().performClick()
        return shadowOf(about.requireActivity()).nextStartedActivity?.dataString
    }

    /** The About screen's licence text. */
    private fun licence(): Spanned = about().binding.licenseDescription.text as Spanned

    /** The About screen, shown in settings. */
    private fun about(): AboutFragment {
        val intent = PreferencesActivity.getIntent(targetContext, AboutFragment::class)
        val controller = Robolectric.buildActivity(PreferencesActivity::class.java, intent).setup()
        saveControllerForCleanup(controller)
        shadowOf(Looper.getMainLooper()).idle()
        val parent = controller.get().fragment as PreferencesFragment
        return parent.childFragmentManager.findFragmentById(R.id.settings_container) as AboutFragment
    }

    private companion object {
        const val BUG_FORM = "https://github.com/AnkiQuest/AnkiQuest-Android/issues/new?template=bug_report_form.yml"
    }
}
