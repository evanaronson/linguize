package com.evanaronson.linguize

import com.evanaronson.linguize.data.CheckPreferences
import com.evanaronson.linguize.data.ProviderKeys
import com.evanaronson.linguize.data.lab.ExplanationStore
import com.evanaronson.linguize.data.lab.SavedExplanation
import com.evanaronson.linguize.data.lab.SavedTip
import com.evanaronson.linguize.history.HistoryStore
import com.evanaronson.linguize.history.patterns.PatternAnalyser
import com.evanaronson.linguize.history.patterns.PatternKind
import com.evanaronson.linguize.history.patterns.PatternReport
import com.evanaronson.linguize.history.patterns.patternEvidence
import com.evanaronson.linguize.llm.CheckFailure
import com.evanaronson.linguize.llm.Provider
import com.evanaronson.linguize.llm.ProviderClient
import com.evanaronson.linguize.llm.lab.ExampleBrief
import com.evanaronson.linguize.llm.lab.PatternBrief
import com.evanaronson.linguize.llm.lab.PatternsPrompt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/** How a saved explanation relates to the current report. */
enum class ExplanationStatus {
    /** Built from exactly the evidence the report used now. */
    Current,

    /** Its sources all still exist, but newer checks have been saved since. */
    Outdated,
}

/** The report, and the saved explanation when it is still allowed to show. */
data class PatternsSnapshot(
    val report: PatternReport,
    val explanation: SavedExplanation? = null,
    val status: ExplanationStatus? = null,
)

/**
 * The rule that keeps a derived explanation from outliving its evidence. Null means it must
 * be deleted: an analyser change, or a source check that is no longer in History.
 */
internal fun explanationStatus(saved: SavedExplanation, report: PatternReport): ExplanationStatus? {
    if (saved.analyser != report.analyser) return null
    val sessions = report.sources.mapTo(hashSetOf()) { it.sessionId }
    if (saved.sources.any { it !in sessions }) return null
    return if (saved.fingerprint == report.fingerprint) ExplanationStatus.Current else ExplanationStatus.Outdated
}

/**
 * Your patterns, composed from History (read only), the deterministic analyser, Lab-local
 * storage for the optional explanation, and the model clients for that explanation.
 * Nothing here writes History.
 */
class PatternsLab(
    private val history: HistoryStore,
    private val notes: ExplanationStore,
    private val settings: CheckPreferences,
    private val keys: ProviderKeys,
    private val clients: Map<Provider, ProviderClient>,
    private val prompt: () -> PatternsPrompt,
    private val now: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val lock = Mutex()
    private val explanationRequest = AtomicLong()

    /**
     * Reads History and builds the report; null when History couldn't be read. With a
     * [language] (Lab, Language scope) the report counts only checks in it. The saved
     * explanation is still reconciled against every language, so it is deleted only when its
     * evidence is gone, and it shows only for the language it was made from.
     */
    suspend fun load(language: String? = null): PatternsSnapshot? = withContext(io) {
        lock.withLock { loadLocked(language) }
    }

    private suspend fun loadLocked(language: String?): PatternsSnapshot? {
        val details = history.patternEvidence() ?: return null
        currentCoroutineContext().ensureActive()
        // Read evidence and reconcile under the same lock: an older read must never delete
        // an explanation saved from newer evidence while that read was suspended.
        val all = reconcileLocked(PatternAnalyser.analyse(details))
        if (language == null) return all
        val report = PatternAnalyser.analyse(details.filter { it.session.targetLanguage == language })
        val status = all.explanation?.let { saved -> explanationStatus(saved, report)?.takeIf { saved.sources.isNotEmpty() } }
        return PatternsSnapshot(report, all.explanation?.takeIf { status != null }, status)
    }

    /** Deletes a saved explanation whose evidence is gone, and says how the rest relates to [report]. */
    suspend fun reconcile(report: PatternReport): PatternsSnapshot = lock.withLock {
        withContext(io) { reconcileLocked(report) }
    }

    private suspend fun reconcileLocked(report: PatternReport): PatternsSnapshot {
        currentCoroutineContext().ensureActive()
        val saved = notes.read() ?: return PatternsSnapshot(report)
        val status = explanationStatus(saved, report)
        return if (status == null) {
            currentCoroutineContext().ensureActive()
            notes.delete()
            PatternsSnapshot(report)
        } else {
            PatternsSnapshot(report, saved, status)
        }
    }

    /**
     * Keeps the saved explanation in step with History for as long as the process lives:
     * after every History write, including deletes made from other screens, a saved
     * explanation is checked against fresh evidence and deleted when a source is gone.
     * Reads nothing while there is no saved explanation.
     */
    suspend fun watch() {
        history.revisions().collectLatest {
            val saved = try {
                withContext(io) { notes.read() }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (saved != null) load()
        }
    }

    /** Whether there is a key for the chosen service, so Explain can be offered. */
    suspend fun canExplain(): Boolean = withContext(io) {
        try {
            !keys.get(settings.provider).isNullOrBlank()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    /** The service an explanation would be sent to. */
    suspend fun provider(): Provider = withContext(io) { settings.provider }

    /**
     * Asks the chosen model to explain [report]'s patterns, saves the answer with its sources,
     * and returns a fresh snapshot (so a check deleted meanwhile still removes it).
     * Throws [CheckFailure].
     */
    suspend fun explain(report: PatternReport, language: String? = null): PatternsSnapshot {
        val generation = explanationRequest.incrementAndGet()
        return withContext(io) {
            val briefs = briefs(report)
            if (briefs.isEmpty()) throw CheckFailure(CheckFailure.Reason.BadResponse, userMessage = CheckFailure.Message.EmptyReply)
            val provider = settings.provider
            val model = settings.model(provider) ?: provider.recommendedModel
            val key = keys.get(provider)?.takeUnless { it.isBlank() } ?: throw CheckFailure(CheckFailure.Reason.NoKey)
            if (key.any { it !in '!'..'~' }) {
                throw CheckFailure(CheckFailure.Reason.BadKey, userMessage = CheckFailure.Message.InvalidKey)
            }
            val request = prompt()
            val reply = try {
                currentCoroutineContext().ensureActive()
                if (generation != explanationRequest.get()) throw CancellationException("Explanation superseded")
                val raw = clients.getValue(provider).generate(key, model, request.request(briefs, settings.nativeLanguage))
                currentCoroutineContext().ensureActive()
                request.read(raw, briefs.mapTo(hashSetOf()) { it.id })
            } catch (e: CheckFailure) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw CheckFailure(CheckFailure.Reason.BadResponse, cause = e)
            }
            val saved = SavedExplanation(
                summary = reply.summary,
                tips = reply.tips.map { SavedTip(it.pattern, it.tip) },
                fingerprint = report.fingerprint,
                analyser = report.analyser,
                prompt = request.version,
                provider = provider.token,
                model = model,
                createdAt = now(),
                sources = report.evidenceSessions.toList(),
                checks = report.checksCounted,
            )
            lock.withLock {
                currentCoroutineContext().ensureActive()
                if (generation != explanationRequest.get()) throw CancellationException("Explanation superseded")
                notes.write(saved)
                loadLocked(language) ?: reconcileLocked(report)
            }
        }
    }

    /** Deletes the saved explanation; the patterns themselves stay, as they're rebuilt from History. */
    suspend fun deleteExplanation() {
        explanationRequest.incrementAndGet()
        lock.withLock { withContext(io) { notes.delete() } }
    }

    companion object {
        /** What the model is told about each group: groups and a few distinct examples, never whole messages. */
        internal fun briefs(report: PatternReport): List<PatternBrief> = report.patterns
            .take(PatternsPrompt.MAX_GROUPS)
            .map { pattern ->
                PatternBrief(
                    id = pattern.kind.token,
                    name = promptName(pattern.kind),
                    times = pattern.occurrences,
                    checks = pattern.checks,
                    trend = pattern.trend.token,
                    examples = pattern.examples
                        .distinctBy { it.from.trim().lowercase() to it.to.trim().lowercase() }
                        .take(PatternsPrompt.MAX_EXAMPLES)
                        .map { ExampleBrief(it.from, it.to, it.why) },
                )
            }

        internal fun promptName(kind: PatternKind): String = when (kind) {
            PatternKind.Accents -> "Written accents"
            PatternKind.InvertedMarks -> "Opening ¿ and ¡"
            PatternKind.Capitals -> "Capital letters"
            PatternKind.Punctuation -> "Punctuation and spacing"
            PatternKind.Elision -> "Catalan elision (l', d')"
            PatternKind.TenirQue -> "Catalan \"tenir que\" instead of \"haver de\""
            PatternKind.SerEstar -> "Ser and estar"
            PatternKind.PorPara -> "Por and para"
            PatternKind.Gender -> "Gender agreement"
            PatternKind.Number -> "Singular and plural agreement"
            PatternKind.Agreement -> "Agreement"
            PatternKind.Articles -> "Articles"
            PatternKind.Prepositions -> "Prepositions"
            PatternKind.Subjunctive -> "Subjunctive"
            PatternKind.VerbForms -> "Verb forms and tenses"
            PatternKind.Spelling -> "Spelling"
            PatternKind.Other -> "Other fixes"
        }
    }
}
