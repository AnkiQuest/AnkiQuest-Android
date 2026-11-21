// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.app.AlertDialog
import android.content.Intent
import android.widget.TextView
import com.ichi2.anki.R
import com.ichi2.anki.ScreenshotTest
import com.ichi2.anki.acraCoreConfigBuilder
import com.ichi2.anki.analytics.AnkiDroidCrashReportDialog
import com.ichi2.anki.common.crashreporting.CrashReportService
import org.acra.interaction.DialogInteraction
import org.junit.After
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowDialog
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The "Ask me" crash report dialog carries the AnkiQuest title, icon and disclaimer (#330). */
class AnkiquestCrashDialogScreenshotTest : ScreenshotTest() {
    private enum class TextSize { NORMAL, LARGE }

    @After
    fun resetFontScale() = RuntimeEnvironment.setFontScale(1f)

    @Test
    fun dialog() = capture(TextSize.NORMAL)

    @Test
    fun dialogLarge() = capture(TextSize.LARGE)

    private fun capture(size: TextSize) {
        RuntimeEnvironment.setFontScale(if (size == TextSize.LARGE) 2f else 1f)

        // ACRA starts the dialog with its configuration and the saved report; the report is read only on send or cancel.
        val report = File(targetContext.cacheDir, "screenshot.stacktrace").apply { writeText("{}") }
        val intent =
            Intent(targetContext, AnkiDroidCrashReportDialog::class.java)
                .putExtra(DialogInteraction.EXTRA_REPORT_CONFIG, CrashReportService.acraCoreConfigBuilder.build())
                .putExtra(DialogInteraction.EXTRA_REPORT_FILE, report)
        startRegularActivity<AnkiDroidCrashReportDialog>(intent)
        advanceRobolectricLooper()

        // The dialog shown last is the one the player sees.
        val dialog = assertNotNull(ShadowDialog.getLatestDialog() as? AlertDialog)
        assertTrue(dialog.isShowing)
        val disclaimer = assertNotNull(dialog.findViewById<TextView>(R.id.tvFeedbackDisclaimer))
        assertEquals(targetContext.getString(R.string.ankiquest_feedback_disclaimer), disclaimer.text.toString())

        // The whole dialog: a screen capture places its window above the top edge under Robolectric.
        captureView("feedback_dialog_${size.name.lowercase()}", dialog.window!!.decorView)
    }
}
