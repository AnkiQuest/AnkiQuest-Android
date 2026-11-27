// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

/**
 * How often each crash signature was sent from this device, so the same crash reaches the server
 * at most [SENDS] times per player without the report carrying anything that identifies them.
 * Holds the [CAPACITY] most recently seen signatures; older ones are dropped.
 */
internal class AnkiquestCrashRecord(
    private val store: Store,
) {
    /** Where the record is kept between crashes. */
    interface Store {
        fun load(): String?

        fun save(text: String)
    }

    enum class Verdict { SEND, SKIP }

    /** Whether a report with [signature] may be sent; counts it as sent if so. */
    fun admit(signature: String): Verdict {
        val counts = read()

        // Re-inserting moves the signature to the end, so the least recently seen is dropped first.
        val sent = counts.remove(signature) ?: 0
        val verdict = if (sent < SENDS) Verdict.SEND else Verdict.SKIP
        counts[signature] = if (verdict == Verdict.SEND) sent + 1 else sent
        while (counts.size > CAPACITY) {
            counts.remove(counts.keys.first())
        }

        write(counts)
        return verdict
    }

    /** Takes back one send of [signature], whose report the player cancelled; other crashes keep their counts. */
    fun retract(signature: String) {
        val counts = read()
        val sent = counts[signature] ?: return
        if (sent <= 1) {
            counts.remove(signature)
        } else {
            counts[signature] = sent - 1
        }
        write(counts)
    }

    /** Forgets every signature, so each crash may be sent again. */
    fun forget() = store.save("")

    private fun write(counts: Map<String, Int>) = store.save(counts.entries.joinToString(LINE) { "${it.key}$FIELD${it.value}" })

    /** One `signature count` line per signature, oldest first. A damaged record starts over. */
    private fun read(): LinkedHashMap<String, Int> {
        val counts = LinkedHashMap<String, Int>()
        val text = store.load()
        if (text.isNullOrEmpty()) {
            return counts
        }
        for (line in text.split(LINE)) {
            val fields = line.split(FIELD)
            val sent = fields.getOrNull(1)?.toIntOrNull()
            if (fields.size != 2 || sent == null) {
                return LinkedHashMap()
            }
            counts[fields[0]] = sent
        }
        return counts
    }

    companion object {
        const val CAPACITY = 200
        private const val SENDS = 2
        private const val LINE = "\n"
        private const val FIELD = " "
    }
}
