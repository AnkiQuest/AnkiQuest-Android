// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.graphics.Rect
import android.widget.TextView
import com.ichi2.anki.R
import com.ichi2.anki.ScreenshotTest
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The changelog's notes and both buttons stay readable on a narrow phone and at 200% text. */
class AnkiquestChangelogScreenshotTest : ScreenshotTest() {
    private enum class TextSize { NORMAL, LARGE }

    override fun applyDeviceConfig() {
        super.applyDeviceConfig()
        if (device == DeviceConfig.PHONE) {
            RuntimeEnvironment.setQualifiers("w320dp-h640dp-port-mdpi")
        }
    }

    // Fixed notes, so a new release's notes do not change the captures.
    @Before
    fun fixNotes() {
        mockkObject(AnkiquestReleaseNotes)
        every { AnkiquestReleaseNotes.read(any()) } returns NOTES
    }

    @After
    fun cleanup() {
        unmockkObject(AnkiquestReleaseNotes)
        RuntimeEnvironment.setFontScale(1f)
    }

    @Test
    fun changelog() = capture(TextSize.NORMAL)

    @Test
    fun changelogLargeText() = capture(TextSize.LARGE)

    private fun capture(size: TextSize) {
        RuntimeEnvironment.setFontScale(if (size == TextSize.LARGE) 2f else 1f)
        val screen = startRegularActivity<AnkiquestChangelogActivity>(AnkiquestChangelogActivity.intent(targetContext))
        advanceRobolectricLooper()
        captureScreen("changelog_${size.name.lowercase()}")

        // Both buttons show on screen with their whole label.
        for (id in listOf(R.id.aq_changelog_releases, R.id.aq_changelog_rate)) {
            val button = screen.findViewById<TextView>(id)
            val bounds = Rect()
            assertTrue(button.getGlobalVisibleRect(bounds), "${button.text} must be on screen")
            assertEquals(button.width, bounds.width(), "${button.text} must not be cut off")
            assertFullLabel(button)
        }
    }

    private fun assertFullLabel(label: TextView) {
        val layout = assertNotNull(label.layout)
        val width = label.width - label.compoundPaddingLeft - label.compoundPaddingRight
        for (line in 0 until layout.lineCount) {
            assertEquals(0, layout.getEllipsisCount(line), "${label.text} must not be ellipsized")

            // Wrapping can leave an invisible space past the line edge; measure the visible text.
            val visibleWidth = layout.getLineMax(line)
            assertTrue(visibleWidth <= width + 1, "${label.text} must fit: visible $visibleWidth, available $width")
        }
        assertEquals(label.text.length, layout.getLineEnd(layout.lineCount - 1))
    }

    private companion object {
        const val NOTES =
            "AnkiDroid with [AnkiQuest](https://github.com/AnkiQuest/AnkiQuest-Desktop): study, shared goals, " +
                "and progress in one place.\n\n" +
                "**Three tabs.** The bottom bar now has Decks, Today and Friends. Tap your avatar on Today to open Me, " +
                "which holds Records, Study history and streak protection."
    }
}
