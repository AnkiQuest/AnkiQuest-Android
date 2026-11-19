// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.icu.lang.UCharacter
import java.text.Normalizer

/** How a deck's path reads when the player shares it: the rule in `deck_names.json`, shared with the desktop. */
object AnkiquestDeckPaths {
    private const val SHORT_NAME = 3

    // Explicit Unicode classes, not the UNICODE_CHARACTER_CLASS flag: Android's ICU regex rejects it.
    private val PREFIX = Regex("""^(?:#?\p{Nd}+[\s\p{Z}.)_-]+|\[[A-Za-z0-9]+\][\s\p{Z}]*)""")
    private val SPACE = Regex("""[\s\p{Z}]+""")
    private val WORD = Regex("""\p{L}+""")
    private val FILING =
        setOf(
            "archive",
            "archives",
            "inbox",
            "default",
            "custom study session",
            "filtered",
            "old",
            "done",
            "archiv",
            "posteingang",
            "standard",
            "benutzerdefiniertes lernen",
            "gefiltert",
            "alt",
            "erledigt",
            "archivo",
            "archivados",
            "bandeja de entrada",
            "predeterminado",
            "sesión de estudio personalizada",
            "filtrado",
            "antiguo",
            "hecho",
            "boîte de réception",
            "par défaut",
            "séance d'étude personnalisée",
            "filtré",
            "ancien",
            "terminé",
            "arquivo",
            "arquivados",
            "caixa de entrada",
            "padrão",
            "sessão de estudo personalizado",
            "antigo",
            "concluído",
        )
    private val GENERIC =
        setOf(
            "book",
            "lesson",
            "level",
            "unit",
            "vocab",
            "vocabulary",
            "cards",
            "buch",
            "lektion",
            "stufe",
            "einheit",
            "vokabeln",
            "karten",
            "libro",
            "lección",
            "nivel",
            "unidad",
            "vocabulario",
            "tarjetas",
            "livre",
            "leçon",
            "niveau",
            "unité",
            "vocabulaire",
            "cartes",
            "livro",
            "lição",
            "nível",
            "unidade",
            "vocabulário",
            "cartões",
        )

    /** How [path] reads when shared: its last deck, with the one above when the last alone says little. */
    fun clean(path: String): String {
        val parts = parts(path)
        val leaf = parts.lastOrNull() ?: return ""
        val words = words(leaf)
        val parent = parts.getOrNull(parts.lastIndex - 1)?.let(::words).orEmpty()
        val generic =
            leaf.codePointCount(0, leaf.length) <= SHORT_NAME ||
                GENERIC.containsAll(words) ||
                (words.isNotEmpty() && words != parent && parent.containsAll(words))
        return limit((if (generic) parts.takeLast(2) else listOf(leaf)).joinToString(" › "))
    }

    private fun fold(value: String): String = UCharacter.foldCase(Normalizer.normalize(value, Normalizer.Form.NFC), true)

    private fun words(value: String): Set<String> = WORD.findAll(fold(value)).map { it.value }.toSet()

    private fun line(value: String): String = value.trim().replace(SPACE, " ")

    // Match Python's character limit without splitting a supplementary character.
    private fun limit(value: String): String = AnkiquestTextLimit.take(value, AnkiquestCardLabels.MAX_LABEL)

    /** A deck name the player typed, on one line and within the desktop's limit. */
    fun typed(value: String): String = limit(line(value))

    /** [path]'s decks, parent first, without numbering, filing decks such as Archive, or a repeat of the one above. */
    fun parts(path: String): List<String> =
        buildList {
            for (raw in path.split("::")) {
                val part = line(PREFIX.replace(raw.trim(), ""))
                if (part.isNotEmpty() && fold(part) !in FILING && lastOrNull()?.let(::fold) != fold(part)) {
                    add(part)
                }
            }
        }
}
