// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.testutils.ext

import anki.collection.OpChangesWithCount
import com.ichi2.anki.libanki.Consts
import com.ichi2.anki.libanki.Note
import com.ichi2.anki.libanki.NotetypeJson
import com.ichi2.anki.libanki.QueueType
import com.ichi2.anki.libanki.testutils.AnkiTest
import com.ichi2.anki.observability.undoableOp

/** Corrupts the deck hierarchy: deletes A::B but keeps A::B::C and its new card, then selects A. */
fun AnkiTest.orphanSubdeck() {
    addDeck("A::B::C").withNote(QueueType.New)
    val parentDeckId = col.decks.idForName("A::B")!!
    col.db.execute("delete from decks where id = ?", parentDeckId)
    col.decks.select(col.decks.idForName("A")!!)
}

suspend fun AnkiTest.addBasicNoteWithOp(
    fields: List<String> = listOf("foo", "bar"),
    noteType: NotetypeJson = col.notetypes.byName("Basic")!!,
): Note =
    col.newNote(noteType).also { note ->
        for ((i, field) in fields.withIndex()) {
            note.setField(i, field)
        }
        undoableOp<OpChangesWithCount> { col.addNote(note, Consts.DEFAULT_DECK_ID) }
    }
