// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import androidx.core.text.HtmlCompat

/**
 * The label a conquered card is offered under: a card's front as one short line of plain text.
 * The rule is pinned by `card_labels.json` from AnkiQuest-Server, which the desktop add-on shares.
 */
object AnkiquestCardLabels {
    /** The server keeps labels of conquered cards to one line of this many characters. */
    const val MAX_LABEL = 120

    /**
     * A card's text as one line of plain text, at most [MAX_LABEL] characters, to offer as the
     * conquered card's label. Sounds, pictures, styles, scripts and markup are dropped, and cloze
     * deletions show their text.
     */
    fun cardLabel(field: String): String = oneLine(plainCardText(field))

    private fun plainCardText(field: String): String {
        val text =
            field
                .replace(HIDDEN, " ")
                .replace(SOUND, " ")
                .replace(CLOZE) { it.groupValues[1] }
                .replace(Regex("<[^>]*>"), " ")
        return HtmlCompat.fromHtml(text.replace("<", "&lt;"), HtmlCompat.FROM_HTML_MODE_LEGACY).toString()
    }

    /**
     * Offer the front template's field values, without static prompts or identifiers. Two short
     * values can give a word and its reading. Cloze notes keep their revealed text. If the template
     * cannot be read, clean the rendered [question]; an image-only front falls back to text fields.
     */
    fun suggestedLabel(
        question: String,
        fields: List<String>,
        cloze: Boolean,
        template: String? = null,
        names: List<String> = emptyList(),
    ): String? {
        if (cloze) {
            // Numeric deletions, such as historical years, are intentional card content.
            val front = fields.firstOrNull { CLOZE.containsMatchIn(it) }
            if (front != null) {
                return cleanLabel(front).ifEmpty { null }
            }
            return firstLabel(fields.asSequence())
        }

        val shown = frontValues(template, names.zip(fields).toMap())
        if (shown != null) {
            val labels = shown.map(::labelText).filter(String::isNotEmpty).distinct()
            if (labels.isNotEmpty()) {
                return if (labels.size > 1 && labels.take(2).all { it.length <= SHORT_LABEL }) {
                    labels.take(2).joinToString(" · ")
                } else {
                    labels.first()
                }
            }
            return firstLabel(fields.asSequence())
        }
        return firstLabel(sequenceOf(question).plus(fields))
    }

    private fun firstLabel(values: Sequence<String>): String? = values.map(::labelText).firstOrNull(String::isNotEmpty)

    private fun cleanLabel(value: String): String = oneLine(labelPlain(value))

    private fun labelPlain(value: String): String {
        // Remove placeholders before truncation, then again after decoding escaped HTML.
        val source = PLACEHOLDER.replace(CLOZE.replace(value) { it.groupValues[1] }, " ")
        return PLACEHOLDER.replace(plainCardText(source), " ")
    }

    private fun labelText(value: String): String =
        words(labelPlain(value))
            .filterNot { looksLikeId(it.trim('(', ')', '[', ']', '{', '}', '.', ',', ';')) }
            .joinToString(" ")
            .take(MAX_LABEL)

    /** Unbalanced or unsupported templates use the cleaned rendered-front fallback. */
    private fun frontValues(
        template: String?,
        fields: Map<String, String>,
    ): List<String>? {
        if (template == null) {
            return null
        }
        val source = HIDDEN.replace(template, " ")
        val tags = FIELD_TAG.findAll(source).toList()
        val remainder = FIELD_TAG.replace(source, "")
        if (tags.isEmpty() || remainder.contains("{{") || remainder.contains("}}")) {
            return null
        }

        val active = mutableListOf<Pair<String, Boolean>>()
        val found = mutableListOf<String>()
        for (match in tags) {
            val tag = match.groupValues[1].trim()
            when {
                tag.startsWith('#') || tag.startsWith('^') -> {
                    val name = tag.drop(1).trim()
                    val value = fields[name] ?: return null
                    val present = cardLabel(value).isNotEmpty() || LABEL_MEDIA.containsMatchIn(value)
                    active.add(name to if (tag.startsWith('#')) present else !present)
                }
                tag.startsWith('/') -> {
                    if (active.lastOrNull()?.first != tag.drop(1).trim()) {
                        return null
                    }
                    active.removeAt(active.lastIndex)
                }
                active.all { it.second } -> {
                    val parts = tag.split(':').map(String::trim)
                    if (parts.last() in LABEL_SPECIAL || parts.dropLast(1).any { it == "type" || it == "hint" }) {
                        continue
                    }
                    val name = if (tag in fields) tag else parts.last()
                    found.add(fields[name] ?: return null)
                }
            }
        }
        return found.takeIf { active.isEmpty() }
    }

    /** The label an earlier version offered: the first field, whatever it holds. */
    fun legacyLabel(fields: List<String>): String? = cardLabel(fields.firstOrNull().orEmpty()).ifEmpty { null }

    /** Whether [text] reads as a note's id or code rather than a word: `B1L02`, `0042`, `ID-17`, `2024-05-01`. */
    fun looksLikeId(text: String): Boolean = ID.matches(text) || (HEX_ID.matches(text) && text.any(Char::isDigit))

    private val HIDDEN = Regex("""(?is)<(style|script)\b[^>]*>.*?</\1\s*>|<!--.*?-->""")
    private val SOUND = Regex("""\[(?:sound|anki:play):[^\]]*\]""")
    private val CLOZE = Regex("""\{\{c\d+::(.*?)(?:::[^}]*)?\}\}""", RegexOption.DOT_MATCHES_ALL)
    private val ID = Regex("""[A-Za-z]{0,4}[-_.#]?\d+(?:[-_.:/]?[A-Za-z]{0,2}\d+)*[A-Za-z]?""")
    private val HEX_ID = Regex("""[0-9A-Fa-f]{8,}(?:-[0-9A-Fa-f]{4,})*""")
    private val FIELD_TAG = Regex("""\{\{([^{}]*)\}\}""")
    private val PLACEHOLDER = Regex("""\[\[type:[\s\S]*?(?:\]\]|$)|\{\{[\s\S]*?(?:\}\}|$)""")
    private val LABEL_MEDIA = Regex("""(?i)<(?:img|audio|video)\b|\[sound:""")
    private val LABEL_SPECIAL = setOf("Tags", "Type", "Deck", "Subdeck", "CardFlag", "Card", "FrontSide")
    private const val SHORT_LABEL = 40

    /** The label to share: the player's [text] as one line, only when they chose to [include] the card and wrote something. */
    fun chosenLabel(
        include: Boolean,
        text: String?,
    ): String? = oneLine(text.orEmpty()).takeIf { include && it.isNotEmpty() }

    private fun oneLine(text: String): String = words(text).joinToString(" ").take(MAX_LABEL)

    private fun words(text: String): List<String> =
        text
            .replace(' ', ' ')
            .split(Regex("""\s+"""))
            .filter { it.isNotEmpty() }
}
