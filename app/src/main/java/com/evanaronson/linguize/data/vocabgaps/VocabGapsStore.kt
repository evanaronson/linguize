package com.evanaronson.linguize.data.vocabgaps

import com.evanaronson.linguize.core.corpus.Lemmatizer
import com.evanaronson.linguize.core.vocab.DeckEntry
import com.evanaronson.linguize.core.vocab.DeckParse
import com.evanaronson.linguize.core.vocab.DeckParser
import com.evanaronson.linguize.core.vocab.VocabDeck
import com.evanaronson.linguize.core.vocab.VocabLevel
import com.evanaronson.linguize.core.vocab.VocabReport
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** A deck the writer imported: its name, language and size. Its words are in its own file. */
@Serializable
data class ImportedDeck(val id: String, val name: String, val language: String, val size: Int, val importedAt: Long)

/** The language and deck the screen showed last. */
@Serializable
data class VocabSelection(val language: String? = null, val deckId: String? = null, val level: VocabLevel = VocabLevel.Lexeme)

/**
 * Lab-local storage for Vocabulary gaps, in its own folder `lab_vocab_gaps` (under the app's
 * files). It holds imported decks, the last selection and reports derived from the corpus.
 * A report is only a cache: it carries the fingerprint of its evidence and is ignored, then
 * replaced, as soon as the corpus or the deck changes. Call off the main thread.
 */
class VocabGapsStore(private val dir: File) {
    private val decksDir get() = File(dir, "decks")
    private val reportsDir get() = File(dir, "reports")
    private val index get() = File(decksDir, "index.json")
    private val selectionFile get() = File(dir, "selection.json")

    @Synchronized
    fun imported(): List<ImportedDeck> = read(index) { json.decodeFromString(ListSerializer(ImportedDeck.serializer()), it) }.orEmpty()

    @Synchronized
    fun importedDeck(id: String): VocabDeck? {
        val info = imported().firstOrNull { it.id == id } ?: return null
        val text = runCatching { File(decksDir, "${info.id}.csv").readText() }.getOrNull() ?: return null
        val parsed = DeckParser.parse(text, info.language) as? DeckParse.Parsed ?: return null
        return VocabDeck(info.id, info.name, info.language, parsed.entries, bundled = false)
    }

    /** Saves [entries] as a new deck. Its words are written as plain CSV, readable by the same parser. */
    @Synchronized
    fun import(name: String, language: String, entries: List<DeckEntry>, now: Long = System.currentTimeMillis()): ImportedDeck {
        val deck = ImportedDeck("$IMPORTED_PREFIX${UUID.randomUUID()}", name.trim(), language, entries.size, now)
        decksDir.mkdirs()
        writeAtomically(File(decksDir, "${deck.id}.csv"), csv(entries))
        writeAtomically(index, json.encodeToString(ListSerializer(ImportedDeck.serializer()), imported() + deck))
        return deck
    }

    /** Deletes an imported deck, its words and any report built on it. */
    @Synchronized
    fun delete(id: String) {
        val remaining = imported().filterNot { it.id == id }
        writeAtomically(index, json.encodeToString(ListSerializer(ImportedDeck.serializer()), remaining))
        File(decksDir, "$id.csv").delete()
        VocabLevel.entries.forEach { File(reportsDir, fileName(id, it)).delete() }
    }

    /** The saved report for [deckId] at [level], only if it was built from exactly this evidence. */
    @Synchronized
    fun report(deckId: String, level: VocabLevel, fingerprint: String): VocabReport? =
        read(File(reportsDir, fileName(deckId, level))) { json.decodeFromString(VocabReport.serializer(), it) }
            ?.takeIf { it.fingerprint == fingerprint && it.deckId == deckId && it.level == level }

    @Synchronized
    fun saveReport(report: VocabReport) {
        reportsDir.mkdirs()
        writeAtomically(File(reportsDir, fileName(report.deckId, report.level)), json.encodeToString(VocabReport.serializer(), report))
    }

    @Synchronized
    fun selection(): VocabSelection = read(selectionFile) { json.decodeFromString(VocabSelection.serializer(), it) } ?: VocabSelection()

    @Synchronized
    fun saveSelection(selection: VocabSelection) {
        dir.mkdirs()
        writeAtomically(selectionFile, json.encodeToString(VocabSelection.serializer(), selection))
    }

    private fun <T> read(file: File, decode: (String) -> T): T? =
        if (!file.exists()) null else runCatching { decode(file.readText()) }.getOrNull()

    private fun writeAtomically(file: File, text: String) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(text)
        if (!temp.renameTo(file)) {
            file.delete()
            temp.renameTo(file)
        }
    }

    private fun fileName(deckId: String, level: VocabLevel) =
        deckId.replace(Regex("[^A-Za-z0-9_-]"), "_") + "." + level.name.lowercase() + ".json"

    companion object {
        const val DIR = "lab_vocab_gaps"
        const val IMPORTED_PREFIX = "user-"
        private val json = Json { ignoreUnknownKeys = true }

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
 * stays its own row until real morphology is available). Attribution: lab_vocab_SOURCES.md.
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
            }
        }

    companion object {
        val ASSETS = mapOf("es" to "lab_vocab_es.csv", "ca" to "lab_vocab_ca.csv")
        const val SOURCES = "lab_vocab_SOURCES.md"

        /** Lists of word forms rather than lemmas. */
        private val FORM_LISTS = setOf("ca")
        val languages: List<String> get() = ASSETS.keys.toList()
        fun idFor(language: String) = "bundled-$language"
    }
}
