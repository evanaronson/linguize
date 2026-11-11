package com.evanaronson.linguize.transfer

// TEMPORARY: lab transfer bridge, see docs/lab/transfer-bridge.md

import android.content.SharedPreferences
import android.util.Log
import com.evanaronson.linguize.data.chat.ChatStore
import com.evanaronson.linguize.data.memory.MemoryStore
import com.evanaronson.linguize.data.phrasebook.PhrasebookStore
import com.evanaronson.linguize.data.teachme.LessonStore
import com.evanaronson.linguize.data.time.TimeStore
import com.evanaronson.linguize.data.vocabgaps.VocabGapsStore
import com.evanaronson.linguize.data.write.WriteDraftStore
import com.evanaronson.linguize.history.SqliteHistoryStore
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.NoSuchFileException

/**
 * What the exporter reads, on the experimental app's side. A store is null when its database
 * doesn't exist, so reading never makes one. [prefs] opens a preferences file by name.
 */
class TransferSources(
    val packageName: String,
    val appVersion: String,
    val history: SqliteHistoryStore?,
    val chat: ChatStore?,
    val time: TimeStore?,
    val filesDir: File,
    val prefs: (String) -> SharedPreferences,
)

/**
 * Writes one read-only snapshot of [sources] as a [TransferBundle]. It only reads: databases
 * through their stores' read snapshots, files as bytes, preferences through `getAll`. No store
 * that could migrate or repair its file on load is used, so the source stays as it is. API keys
 * (`keys`) are never opened. A part that can't be read is marked `unreadable` and the rest
 * still goes.
 */
class TransferExporter(private val sources: TransferSources) {
    suspend fun write(out: OutputStream) {
        val bundle = TransferBundle.Writer(out)
        bundle.header(sources.packageName, sources.appVersion, TransferRules.historyVersion)

        sources.history?.let { history ->
            if (!history.transferRows(bundle::session)) bundle.unreadable(TransferKind.Checks.part)
        }
        // History's own deletions waiting for Undo (ui/history PendingDeletions, "recent").
        part(TransferKind.Checks, bundle) { bundle.excluded(pendingHistoryDeletions(sources.prefs(RECENT_PREFS))) }
        sources.chat?.let { chat -> part(TransferKind.Chats, bundle) { bundle.chat(chat.transferRows()) } }
        sources.time?.let { time -> part(TransferKind.Time, bundle) { bundle.time(time.all()) } }

        for ((kind, names) in FILES) {
            part(kind, bundle) {
                for (name in names) readText(File(sources.filesDir, name))?.let { bundle.file(name, it) }
            }
        }
        part(TransferKind.Decks, bundle) {
            val index = "${VocabGapsStore.DIR}/decks/index.json"
            readText(File(sources.filesDir, index))?.let { text ->
                bundle.file(index, text)
                val decks = File(sources.filesDir, "${VocabGapsStore.DIR}/decks")
                decks.listFiles { file -> file.name.endsWith(".csv") }?.sortedBy { it.name }?.forEach { csv ->
                    readText(csv)?.let { bundle.file("${VocabGapsStore.DIR}/decks/${csv.name}", it) }
                }
            }
        }
        for ((name, rule) in TransferRules.PREFERENCES) {
            part(rule.kind, bundle) {
                val values = sources.prefs(name).all
                    .filterKeys(rule::travels)
                    .mapNotNull { (key, value) -> TransferBundle.Pref.of(value)?.let { key to it } }
                    .toMap()
                if (values.isNotEmpty()) bundle.prefs(name, values)
            }
        }
        bundle.end()
    }

    /**
     * Runs one part; a failure marks it unreadable and the rest still goes. Parts read
     * everything before writing a line, so a failed read leaves no partial line. When the
     * pipe itself fails, writing the mark fails too, and the bundle never reaches its end.
     */
    private inline fun part(kind: TransferKind, bundle: TransferBundle.Writer, block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't read ${kind.part} for transfer", e)
            bundle.unreadable(kind.part)
        }
    }

    private fun readText(file: File): String? = try {
        String(Files.readAllBytes(file.toPath()), Charsets.UTF_8)
    } catch (_: NoSuchFileException) {
        null
    }

    companion object {
        private const val TAG = "LabTransfer"

        /** ui/history/HistoryViewModel's preferences, read only for the ids waiting to be deleted. */
        const val RECENT_PREFS = "recent"

        /** The files under files/ that travel as text, by the kind they fill. */
        val FILES = listOf(
            TransferKind.Notes to listOf(WriteDraftStore.FILE_NAME, WriteDraftStore.PAD_FILE_NAME),
            TransferKind.AboutYou to listOf(MemoryStore.FILE_NAME),
            TransferKind.Phrases to listOf(PhrasebookStore.FILE_NAME),
            TransferKind.Lessons to listOf(LessonStore.FILE_NAME),
        )

        /** Mirrors PendingDeletions' keys: notes per operation, and two older forms. */
        fun pendingHistoryDeletions(prefs: SharedPreferences): Set<String> = buildSet {
            for ((key, value) in prefs.all) {
                when {
                    key.startsWith("pendingOperation:") && value is String -> add(value)
                    key == "pendingDeletes" && value is Set<*> -> value.filterIsInstance<String>().forEach(::add)
                    key == "pendingDelete" && value is String -> add(value)
                }
            }
        }
    }
}
