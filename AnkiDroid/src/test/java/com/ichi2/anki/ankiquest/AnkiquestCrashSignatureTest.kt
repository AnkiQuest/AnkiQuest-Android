// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import org.json.JSONObject
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Checks [AnkiquestCrashSignature] against the server's `tests/fixtures/crash_signatures.json`.
 *
 * Until the server snapshot (`ankiquest-server.sha`) includes that file, a copy from AnkiQuest-Server
 * commit e6b5b8a5debf8f823b87a9807ba6e9f63cabf3a8 lives in `ankiquest-server-pending/`. Once the
 * bump brings it into `ankiquest/`, the test reads that one; delete the pending copy then.
 */
class AnkiquestCrashSignatureTest {
    @Test
    fun `signatures match the server's cases`() {
        val cases = fixture().getJSONArray("cases")
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val name = case.getString("name")
            val trace = Trace.parse(case.getString("stack_trace"))

            val canonical = AnkiquestCrashSignature.canonical(trace.type, trace.message, trace.frames)

            assertEquals(case.getString("canonical"), canonical, name)
            assertEquals(case.getString("signature"), AnkiquestCrashSignature.hash(canonical), name)
        }
    }

    /** The device reads the class, the localized message and the frames of the outer exception. */
    @Test
    fun `a thrown exception gets the server's signature`() {
        val error =
            IllegalStateException("Deck list not loaded", NullPointerException("cause")).apply {
                stackTrace =
                    arrayOf(
                        StackTraceElement("com.ichi2.anki.DeckPicker", "onResume", "DeckPicker.kt", 812),
                        StackTraceElement("android.app.Activity", "performResume", "Activity.java", 8936),
                    )
            }

        assertEquals(signature("example_report"), AnkiquestCrashSignature.of(error))
    }

    private fun signature(name: String): String {
        val cases = fixture().getJSONArray("cases")
        val case = (0 until cases.length()).map(cases::getJSONObject).single { it.getString("name") == name }
        return case.getString("signature")
    }

    private fun fixture(): JSONObject {
        val loader = checkNotNull(javaClass.classLoader)
        val resource = loader.getResource(PINNED) ?: checkNotNull(loader.getResource(PENDING)) { "missing crash signatures" }
        return JSONObject(resource.readText())
    }

    /** A Java stack trace as text, read the way the server reads it: causes and suppressed exceptions are left out. */
    private class Trace(
        val type: String,
        val message: String?,
        val frames: List<StackTraceElement>,
    ) {
        companion object {
            private const val FRAME = "\tat "
            private const val SEPARATOR = ": "

            fun parse(text: String): Trace {
                val lines = text.replace("\r\n", "\n").split("\n").dropLastWhile { it.isEmpty() }

                // The header runs until the first frame; a message may span several lines.
                val header = lines.takeWhile { !it.startsWith(FRAME) }.joinToString("\n")
                val frames =
                    lines
                        .dropWhile { !it.startsWith(FRAME) }
                        .takeWhile { it.startsWith(FRAME) }
                        .map(::frame)

                if (SEPARATOR !in header) {
                    return Trace(header, null, frames)
                }
                return Trace(header.substringBefore(SEPARATOR), header.substringAfter(SEPARATOR), frames)
            }

            /** `\tat a.B.method(B.kt:1)` without the file and line. */
            private fun frame(line: String): StackTraceElement {
                val call = line.removePrefix(FRAME).substringBefore("(")
                return StackTraceElement(call.substringBeforeLast("."), call.substringAfterLast("."), null, -1)
            }
        }
    }

    companion object {
        private const val PINNED = "ankiquest/crash_signatures.json"
        private const val PENDING = "ankiquest-server-pending/crash_signatures.json"
    }
}
