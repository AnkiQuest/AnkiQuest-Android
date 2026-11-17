// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.Context
import org.acra.ReportField
import org.acra.builder.ReportBuilder
import org.acra.collector.Collector
import org.acra.config.CoreConfiguration
import org.acra.data.CrashReportData

/**
 * Keeps a report's logcat to its newest [LIMIT_BYTES], so the report stays under the AnkiQuest
 * server's 256 KiB limit; the server rejects a larger one and ACRA does not retry it. The developer
 * configuration reads 1500 long-format entries, which can exceed that alone.
 * ACRA loads it through `META-INF/services/org.acra.collector.Collector`.
 */
class AnkiquestLogcatCap : Collector {
    // LogCatCollector runs first; this trims what it collected.
    override val order = Collector.Order.LAST

    override fun collect(
        context: Context,
        config: CoreConfiguration,
        reportBuilder: ReportBuilder,
        crashReportData: CrashReportData,
    ) {
        val logcat = crashReportData.getString(ReportField.LOGCAT) ?: return
        crashReportData.put(ReportField.LOGCAT, tail(logcat, LIMIT_BYTES))
    }

    companion object {
        /** Half the server's limit: room for the rest of the report and for JSON escapes. */
        const val LIMIT_BYTES = 128 * 1024

        /** The last whole lines of [text] that fit in [limit] UTF-8 bytes, newline included. */
        fun tail(
            text: String,
            limit: Int,
        ): String {
            if (text.toByteArray(Charsets.UTF_8).size <= limit) {
                return text
            }

            // Newest lines are last: keep lines from the end while they fit.
            val kept = ArrayDeque<String>()
            var size = 0
            for (line in text.split('\n').asReversed()) {
                size += line.toByteArray(Charsets.UTF_8).size + 1
                if (size > limit) {
                    break
                }
                kept.addFirst(line)
            }
            return kept.joinToString("\n")
        }
    }
}
