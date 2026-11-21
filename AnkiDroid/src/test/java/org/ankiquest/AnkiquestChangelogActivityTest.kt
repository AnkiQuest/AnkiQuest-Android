// SPDX-License-Identifier: AGPL-3.0-only

package org.ankiquest

import android.view.View
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import kotlin.test.assertEquals
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

    /** The link the button [id] opens. */
    private fun clicked(id: Int): String? {
        val activity = changelog()
        activity.findViewById<View>(id).performClick()
        return shadowOf(activity).nextStartedActivity?.dataString
    }

    private fun changelog(): AnkiquestChangelogActivity {
        val controller = Robolectric.buildActivity(AnkiquestChangelogActivity::class.java).setup()
        saveControllerForCleanup(controller)
        return controller.get()
    }
}
