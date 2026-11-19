// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.icu.lang.UCharacter
import androidx.core.content.edit
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.CollectionManager
import com.ichi2.anki.libanki.Collection
import com.ichi2.anki.libanki.CollectionFiles
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.text.Normalizer

/** Collection and account -> first noticed home deck -> local sharing choice. No names are uploaded. */
internal object AnkiquestDeckNames {
    private const val KEY = "ankiquestConquestDecks:"
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

    data class Source(
        val account: HomeAccount,
        val collection: String,
    )

    /** The save closure retains its collection and account, even if a picker outlives either. */
    data class Deck(
        val name: String,
        val picks: List<String>,
        val save: suspend (String) -> Unit,
    )

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

    private fun parts(path: String): List<String> =
        buildList {
            for (raw in path.split("::")) {
                val part = line(PREFIX.replace(raw.trim(), ""))
                if (part.isNotEmpty() && fold(part) !in FILING && lastOrNull()?.let(::fold) != fold(part)) {
                    add(part)
                }
            }
        }

    suspend fun source(account: HomeAccount): Source =
        CollectionManager.withCol {
            AnkiquestApi.requireCurrent(account)
            Source(account, identity())
        }

    /** Capture every successful batch; a later failure must not discard a noticed conquest. */
    suspend fun capture(
        source: Source,
        conquests: JSONArray?,
    ) {
        if (conquests == null || conquests.length() == 0) {
            return
        }
        try {
            CollectionManager.withCol {
                if (!owns(source)) {
                    return@withCol
                }
                val state = read(source)
                val known = state.optJSONObject("conquests") ?: JSONObject().also { state.put("conquests", it) }
                for (index in 0 until conquests.length()) {
                    val at = conquests.optJSONObject(index)?.optLong("at") ?: continue
                    if (at > 0 && !known.has(at.toString())) {
                        try {
                            record(at)?.let { known.put(at.toString(), it) }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            // A removed card must not discard the other snapshots in this upload.
                            Timber.w(error, "AnkiQuest conquest home deck unavailable")
                        }
                    }
                }
                write(source, state)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Timber.w(error, "AnkiQuest conquest deck snapshot unavailable")
        }
    }

    suspend fun find(at: Long): Deck? {
        val account = AnkiquestHomeData.account()
        return CollectionManager.withCol {
            if (AnkiquestHomeData.account()?.scope != account?.scope) {
                return@withCol null
            }
            val source = account?.let { Source(it, identity()) }
            val state = source?.let(::read) ?: JSONObject()
            val known = state.optJSONObject("conquests") ?: JSONObject().also { state.put("conquests", it) }
            val record =
                known.optJSONObject(at.toString()) ?: record(at)?.also {
                    known.put(at.toString(), it)
                    source?.let { owner -> write(owner, state) }
                } ?: return@withCol null
            val id = record.getString("id")
            val path = record.getString("path")
            val choices = state.optJSONObject("names")
            val name = if (choices?.has(id) == true) choices.getString(id) else clean(path)
            Deck(name, (listOf(name, clean(path)) + parts(path) + "").distinct()) { text ->
                source?.let { save(it, id, text) }
            }
        }
    }

    private suspend fun save(
        source: Source,
        id: String,
        text: String,
    ) = CollectionManager.withCol {
        if (!owns(source)) {
            throw HomeAccountChanged()
        }
        val state = read(source)
        val names = state.optJSONObject("names") ?: JSONObject().also { state.put("names", it) }
        names.put(id, limit(line(text)))
        write(source, state)
    }

    private fun Collection.record(at: Long): JSONObject? {
        val cid = db.queryLongScalar("select cid from revlog where id = ?", at)
        if (cid == 0L) {
            return null
        }
        val did = getCard(cid).currentDeckId()
        val path = decks.nameIfExists(did) ?: return null
        return JSONObject().put("id", did.toString()).put("path", path)
    }

    private fun Collection.identity(): String =
        when (val files = collectionFiles) {
            is CollectionFiles.FolderBasedCollection -> files.colDb.absolutePath
            is CollectionFiles.InMemoryWithMedia -> files.mediaFolder.absolutePath
            CollectionFiles.InMemory -> "memory:${System.identityHashCode(this)}"
        }

    private fun Collection.owns(source: Source): Boolean =
        AnkiquestHomeData.account()?.scope == source.account.scope && identity() == source.collection

    private fun key(source: Source): String = KEY + sha256(source.collection, source.account.notificationAccount)

    private fun read(source: Source): JSONObject =
        try {
            JSONObject(AnkiDroidApp.sharedPrefs().getString(key(source), "{}") ?: "{}")
        } catch (error: Exception) {
            Timber.w(error, "AnkiQuest local deck choices unavailable")
            JSONObject()
        }

    private fun write(
        source: Source,
        state: JSONObject,
    ) {
        AnkiDroidApp.sharedPrefs().edit { putString(key(source), state.toString()) }
    }
}
