package com.evanaronson.linguize.data.vocabgaps

import com.evanaronson.linguize.core.corpus.Lemmatizer
import com.evanaronson.linguize.core.vocab.DeckEntry
import com.evanaronson.linguize.core.vocab.DeckParse
import com.evanaronson.linguize.core.vocab.DeckParser
import com.evanaronson.linguize.core.vocab.VocabDeck
import com.evanaronson.linguize.core.vocab.VocabLevel
import com.evanaronson.linguize.data.writeAtomically
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.util.UUID

/** A deck the writer imported: its name, language and size. Its words are in its own file. */
@Serializable
data class ImportedDeck(val id: String, val name: String, val language: String, val size: Int, val importedAt: Long)

/** The language and deck the screen showed last. */
@Serializable
data class VocabSelection(val language: String? = null, val deckId: String? = null, val level: VocabLevel = VocabLevel.Lexeme)

/**
 * Imported decks and Words' last selection, in the existing `lab_vocab_gaps` folder under
 * the app's files. Call off the main thread. Coverage is derived in memory; reports left
 * by the former Vocabulary gaps screen are only removed when their deck is deleted.
 * Missing files have empty defaults; unreadable files and failed writes throw [IOException].
 */
class VocabGapsStore(private val dir: File) {
    private val decksDir get() = File(dir, "decks")
    private val reportsDir get() = File(dir, "reports")
    private val index get() = File(decksDir, "index.json")
    private val selectionFile get() = File(dir, "selection.json")

    fun imported(): List<ImportedDeck> = synchronized(lock) {
        read(index) { json.decodeFromString(ListSerializer(ImportedDeck.serializer()), it) }.orEmpty()
    }

    fun importedDeck(id: String): VocabDeck? = synchronized(lock) {
        val info = imported().firstOrNull { it.id == id } ?: return@synchronized null
        val text = File(decksDir, "${info.id}.csv").readText()
        val parsed = DeckParser.parse(text, info.language) as? DeckParse.Parsed
            ?: throw IOException("Couldn't read imported deck ${info.id}")
        VocabDeck(info.id, info.name, info.language, parsed.entries, bundled = false)
    }

    /** Saves [entries] as a new deck. Its words are written as plain CSV, readable by the same parser. */
    fun import(name: String, language: String, entries: List<DeckEntry>, now: Long = System.currentTimeMillis()): ImportedDeck = synchronized(lock) {
        val before = imported()
        val deck = ImportedDeck("$IMPORTED_PREFIX${UUID.randomUUID()}", name.trim(), language, entries.size, now)
        val words = File(decksDir, "${deck.id}.csv")
        writeAtomically(words, csv(entries))
        try {
            writeAtomically(index, json.encodeToString(ListSerializer(ImportedDeck.serializer()), before + deck))
        } catch (failure: IOException) {
            // The index is the commit point. Do not leave a failed import looking like a saved deck.
            try { Files.deleteIfExists(words.toPath()) } catch (cleanup: IOException) { failure.addSuppressed(cleanup) }
            throw failure
        }
        deck
    }

    /** Deletes an imported deck, its words and any cached reports left by older builds. */
    fun delete(id: String) = synchronized(lock) {
        val remaining = imported().filterNot { it.id == id }
        writeAtomically(index, json.encodeToString(ListSerializer(ImportedDeck.serializer()), remaining))
        deleteFile(File(decksDir, "$id.csv"))
        VocabLevel.entries.forEach { deleteFile(File(reportsDir, fileName(id, it))) }
    }

    fun selection(): VocabSelection = synchronized(lock) {
        read(selectionFile) { json.decodeFromString(VocabSelection.serializer(), it) } ?: VocabSelection()
    }

    fun saveSelection(selection: VocabSelection) = synchronized(lock) {
        // Automatic selection writes must not replace an existing file that could not be read.
        if (selection() == selection) return@synchronized
        writeAtomically(selectionFile, json.encodeToString(VocabSelection.serializer(), selection))
    }

    private fun <T> read(file: File, decode: (String) -> T): T? {
        return try {
            decode(Files.readAllBytes(file.toPath()).toString(Charsets.UTF_8))
        } catch (_: NoSuchFileException) {
            null
        } catch (failure: IllegalArgumentException) {
            throw IOException("Couldn't read ${file.name}", failure)
        }
    }

    private fun deleteFile(file: File) {
        Files.deleteIfExists(file.toPath())
    }

    private fun fileName(deckId: String, level: VocabLevel) =
        deckId.replace(Regex("[^A-Za-z0-9_-]"), "_") + "." + level.name.lowercase() + ".json"

    companion object {
        const val DIR = "lab_vocab_gaps"
        const val IMPORTED_PREFIX = "user-"
        private val json = Json { ignoreUnknownKeys = true }
        // Each Words view model owns a store, but they share the same index and temporary files.
        private val lock = Any()

        fun csv(entries: List<DeckEntry>): String = buildString {
            append("rank,lemma,pos,gloss_en,forms\n")
            for (entry in entries) {
                append(entry.rank).append(',')
                append(quote(entry.lemma)).append(',')
                append(entry.pos.orEmpty()).append(',')
                append(quote(entry.gloss.orEmpty())).append(',')
                append(quote(entry.forms.joinToString(";"))).append('\n')
            }
        }

        private fun quote(value: String) =
            if (value.any { it == ',' || it == '"' || it == '\n' }) "\"" + value.replace("\"", "\"\"") + "\"" else value
    }
}

/**
 * The decks that ship with Lab, read from assets on first use: Spanish lexemes with their
 * forms, and Catalan word forms grouped into lexemes by the lemmatizer it is given (each form
 * stays its own row until real morphology is available), each cut to its top [SIZE] rows.
 * Attribution: lab_vocab_SOURCES.md.
 */
class BundledDecks(private val load: (String) -> String) {
    private val parsed = HashMap<String, List<DeckEntry>?>()
    private val decks = HashMap<String, VocabDeck?>()

    @Synchronized
    fun deck(language: String, lemmatizer: Lemmatizer = Lemmatizer.Identity): VocabDeck? =
        decks.getOrPut(language + ":" + lemmatizer.javaClass.name) {
            val asset = ASSETS[language] ?: return@getOrPut null
            val entries = parsed.getOrPut(language) {
                (runCatching { DeckParser.parse(load(asset), language) }.getOrNull() as? DeckParse.Parsed)?.entries
            } ?: return@getOrPut null
            if (language in FORM_LISTS) {
                VocabDeck.fromForms(idFor(language), "", language, entries, bundled = true) { lemmatizer.lexemes(it, language) }
            } else {
                VocabDeck(idFor(language), "", language, entries, bundled = true)
            }.top(SIZE)
        }

    companion object {
        val ASSETS = mapOf("es" to "lab_vocab_es.csv", "ca" to "lab_vocab_ca.csv")
        const val SOURCES = "lab_vocab_SOURCES.md"

        /**
         * Rows a bundled deck shows, at the lexeme and at the lemma level. The Catalan asset
         * ships more word forms than this (scripts/build_vocab_decks.py) so that grouping them
         * still leaves this many lexemes and lemmas; the rest stay in [VocabDeck.reserve].
         */
        const val SIZE = 5_000

        /** Lists of word forms rather than lemmas. */
        private val FORM_LISTS = setOf("ca")
        val languages: List<String> get() = ASSETS.keys.toList()
        fun idFor(language: String) = "bundled-$language"
    }
}
