// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.ichi2.anki.ankiquest.AnkiquestCrashRecord.Verdict
import com.ichi2.anki.exception.UserSubmittedException
import org.acra.ReportField
import org.acra.builder.ReportBuilder
import org.acra.config.CoreConfiguration
import org.acra.config.ReportingAdministrator
import org.acra.data.CrashReportData
import org.json.JSONObject

/**
 * Sends each distinct crash at most twice per device and adds its signature to the report as
 * [SIGNATURE_FIELD], so the server can group reports without knowing who sent them.
 * ACRA loads it through `META-INF/services/org.acra.config.ReportingAdministrator`.
 */
class AnkiquestCrashAdministrator : ReportingAdministrator {
    override fun shouldStartCollecting(
        context: Context,
        config: CoreConfiguration,
        reportBuilder: ReportBuilder,
    ): Boolean {
        val error = reportBuilder.exception ?: return true
        val signature = AnkiquestCrashSignature.of(error)
        reportBuilder.customData(SIGNATURE_FIELD, signature)

        // The player asked to send this report.
        if (error is UserSubmittedException) {
            return true
        }

        // Counted before collection, so a skipped report reads no logs.
        val record = AnkiquestCrashRecord(PreferencesStore(context))
        return record.admit(signature) == Verdict.SEND
    }

    /** A preference file of its own, outside the default preferences that reports include. */
    private class PreferencesStore(
        context: Context,
    ) : AnkiquestCrashRecord.Store {
        private val prefs: SharedPreferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

        override fun load(): String? = prefs.getString(PREF_SENT, null)

        // Written synchronously: the crashing process may end right after.
        override fun save(text: String) = prefs.edit(commit = true) { putString(PREF_SENT, text) }
    }

    companion object {
        internal const val SIGNATURE_FIELD = "ANKIQUEST_SIGNATURE"
        private const val FILE = "ankiquest_crash_signatures"
        private const val PREF_SENT = "sent"

        /** Lets every crash be sent again, as ACRA's limiter data is cleared with it. */
        internal fun forget(context: Context) = AnkiquestCrashRecord(PreferencesStore(context)).forget()

        /** Takes back the send of [report], which the player cancelled, so its crash may be sent again. */
        internal fun cancelled(
            context: Context,
            report: CrashReportData,
        ) {
            val custom = report[ReportField.CUSTOM_DATA.name] as? JSONObject ?: return
            val signature = custom.optString(SIGNATURE_FIELD).ifEmpty { return }
            AnkiquestCrashRecord(PreferencesStore(context)).retract(signature)
        }
    }
}
