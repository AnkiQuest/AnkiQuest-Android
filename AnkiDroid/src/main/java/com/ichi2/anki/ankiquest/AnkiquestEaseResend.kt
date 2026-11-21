// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

/**
 * One resend of the whole review history with the answer button (`ease`), so a server that counts
 * a lapse by the button corrects the reviews it stored without one (hill, 2026-11-19).
 *
 * The resend goes only to a server whose cached `/api/capabilities` name [FEATURE]; the upload does
 * not ask the server again. An older server drops the button, and resending to it would use up the
 * one resend. The state is kept per account next to the upload mark. The desktop add-on does the
 * same in `addon/ease_resend.py`.
 */
internal object AnkiquestEaseResend {
    /** The capability of a server that stores the button of each review. */
    const val FEATURE = "review_ease"

    /** Where this account stands with the resend. */
    enum class State(
        val stored: String,
    ) {
        /** Every review this server has from here came with the button. */
        NOT_NEEDED("not_needed"),

        /** Owed: earlier uploads left the button out, or the server could not store it yet. */
        PENDING("pending"),

        /** An upload that resends the history is running. */
        SENDING("sending"),

        /** The whole history was resent with the button. */
        DONE("done"),
    }

    /** What an upload that owed the resend found. */
    enum class Check {
        /** The server does not store the button yet. */
        UNSUPPORTED,

        /** The upload itself sent the whole history with the button. */
        COVERED,

        /** The upload resent the whole history. */
        RESENT,

        /** Asking the server or resending failed; the reviews themselves were uploaded. */
        FAILED,
    }

    /** The stored state; a missing or unknown one is owed, and an interrupted one is owed again. */
    fun of(stored: String?): State {
        val state = State.entries.firstOrNull { it.stored == stored } ?: return State.PENDING
        if (state == State.SENDING) {
            return State.PENDING
        }
        return state
    }

    /** An upload that may resend begins. */
    fun start(state: State): State {
        if (state == State.PENDING) {
            return State.SENDING
        }
        return state
    }

    /** The upload that began with [start] was accepted. */
    fun finish(
        state: State,
        check: Check,
    ): State {
        if (state != State.SENDING) {
            return state
        }
        return when (check) {
            Check.RESENT -> State.DONE
            Check.COVERED -> State.NOT_NEEDED
            Check.UNSUPPORTED, Check.FAILED -> State.PENDING
        }
    }

    /** The upload that began with [start] failed or was cut short. */
    fun fail(state: State): State {
        if (state == State.SENDING) {
            return State.PENDING
        }
        return state
    }

    /**
     * Sends every row [read] returns, oldest first, in batches of at most [limit] rows (the server's
     * limit per upload). [read] returns the rows after an id, at most [limit] of them. Returns how
     * many rows went.
     */
    suspend fun <T> sendHistory(
        limit: Int,
        read: suspend (after: Long) -> List<T>,
        id: (T) -> Long,
        send: suspend (List<T>) -> Unit,
    ): Int {
        var after = 0L
        var count = 0
        while (true) {
            val rows = read(after)
            if (rows.isEmpty()) {
                return count
            }
            send(rows)
            count += rows.size
            after = id(rows.last())
            if (rows.size < limit) {
                return count
            }
        }
    }
}
