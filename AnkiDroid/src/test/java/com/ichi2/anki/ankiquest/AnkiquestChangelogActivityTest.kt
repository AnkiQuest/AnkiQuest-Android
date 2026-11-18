// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.ClipboardManager
import android.content.Intent
import android.text.Spanned
import android.text.style.ClickableSpan
import android.view.View
import android.widget.TextView
import androidx.core.content.getSystemService
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class AnkiquestChangelogActivityTest : RobolectricTest() {
    @Test
    fun `Shows the release notes bundled with this build`() {
        val notes = changelog().findViewById<TextView>(R.id.aq_changelog_notes).text.toString()

        // The first line of the notes, without its markdown link and bold syntax
        val first =
            AnkiquestReleaseNotes
                .read(targetContext)
                .lineSequence()
                .first()
                .replace(Regex("""\[([^\]]+)]\([^)]+\)"""), "$1")
                .replace("**", "")
        assertTrue(first.isNotBlank())
        assertTrue(notes.startsWith(first), notes)
    }

    @Test
    fun `Rate AnkiQuest opens the repository while there is no store listing`() {
        assertEquals("https://github.com/AnkiQuest/AnkiQuest-Android", clicked(R.id.aq_changelog_rate))
    }

    @Test
    fun `All releases opens the releases page`() {
        assertEquals("https://github.com/AnkiQuest/AnkiQuest-Android/releases", clicked(R.id.aq_changelog_releases))
    }

    @Test
    fun `A link in the notes opens in a browser like the buttons`() {
        linkedNotes()
        val activity = changelog()
        link(activity).onClick(activity.findViewById(R.id.aq_changelog_notes))

        val started = assertNotNull(shadowOf(activity).nextStartedActivity)
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals(NOTES_LINK, started.dataString)
        assertContains(started.categories.orEmpty(), Intent.CATEGORY_BROWSABLE)
    }

    @Test
    fun `A link in the notes is copied when no app opens it`() {
        linkedNotes()
        shadowOf(RuntimeEnvironment.getApplication()).checkActivities(true)
        val activity = changelog()
        link(activity).onClick(activity.findViewById(R.id.aq_changelog_notes))

        assertNull(shadowOf(activity).nextStartedActivity)
        val clipboard = assertNotNull(targetContext.getSystemService<ClipboardManager>())
        assertEquals(
            NOTES_LINK,
            clipboard.primaryClip
                ?.getItemAt(0)
                ?.text
                ?.toString(),
        )
    }

    /** The first link in the release notes, the one to [NOTES_LINK]. */
    private fun link(activity: AnkiquestChangelogActivity): ClickableSpan {
        val notes = activity.findViewById<TextView>(R.id.aq_changelog_notes).text as Spanned
        return notes.getSpans(0, notes.length, ClickableSpan::class.java).first()
    }

    /** The link the button [id] opens. */
    private fun clicked(id: Int): String? {
        val activity = changelog()
        activity.findViewById<View>(id).performClick()
        return shadowOf(activity).nextStartedActivity?.dataString
    }

    /** Notes with one link, to [NOTES_LINK], instead of this build's. */
    private fun linkedNotes() {
        mockkObject(AnkiquestReleaseNotes)
        every { AnkiquestReleaseNotes.read(any()) } returns "Read [the notes]($NOTES_LINK) first."
    }

    private fun changelog(): AnkiquestChangelogActivity {
        val controller = Robolectric.buildActivity(AnkiquestChangelogActivity::class.java).setup()
        saveControllerForCleanup(controller)
        return controller.get()
    }

    @After
    fun unmockNotes() {
        unmockkObject(AnkiquestReleaseNotes)
    }

    private companion object {
        const val NOTES_LINK = "https://github.com/AnkiQuest/AnkiQuest-Android/releases/tag/quest-1"
    }
}
