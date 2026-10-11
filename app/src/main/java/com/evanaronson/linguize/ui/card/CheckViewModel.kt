package com.evanaronson.linguize.ui.card

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.evanaronson.linguize.App
import com.evanaronson.linguize.CheckContext
import com.evanaronson.linguize.Checked
import com.evanaronson.linguize.Checker
import com.evanaronson.linguize.core.CheckResult
import com.evanaronson.linguize.core.Edit
import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.core.Revision
import com.evanaronson.linguize.core.Settled
import com.evanaronson.linguize.history.Answer
import com.evanaronson.linguize.history.CheckHistory
import com.evanaronson.linguize.history.Kept
import com.evanaronson.linguize.history.Opening
import com.evanaronson.linguize.history.Origin
import com.evanaronson.linguize.llm.CheckFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface CardState {
    data class Loading(val text: String) : CardState
    data class Failed(val reason: CheckFailure.Reason, val detail: String?) : CardState
    data class Done(
        val result: CheckResult,
        /** The writer's answers to assumptions, kept until the card closes. */
        val settled: List<Settled> = emptyList(),
    ) : CardState
}

/**
 * One check and the changes the writer has accepted from it. Accepted changes
 * survive checking again (after an answer to an assumption, a retry, or new
 * settings) wherever the new suggestions are the same.
 *
 * History is [CheckHistory]'s job; this only tells it what happens, and it decides what's
 * recorded (nothing for the app's own "Try it" card, see [Origin.recorded]). A session is
 * opened with each fresh [check], gets an attempt per answer, whatever changed in between
 * (other settings for a [recheck], another language with [changeLanguage]: a card is one
 * session), and is closed by [dismiss], the next [check] or the view model going away.
 * [onCleared] closes it as not applied: a host that hands the text back calls
 * `dismiss(applied = true)` itself before finishing.
 *
 * [checker] is called on first use, off the main thread (making it can read assets).
 * Hosts get one from [factory].
 */
class CheckViewModel(
    private val checker: () -> Checker,
    private val history: CheckHistory,
) : ViewModel() {
    private var text = ""
    private var language: Language? = null
    private var origin = Origin.Tester
    private var hostApp: String? = null
    private var settled: List<Settled> = emptyList()

    /** Changes accepted before the current check started, kept while it runs. */
    private var carried: List<Edit> = emptyList()
    private var job: Job? = null

    /** The settings the latest check ran (or is running) with; null before it read them. */
    private var ranWith: CheckContext? = null

    /** Null before the first check and after [dismiss]. */
    var state by mutableStateOf<CardState?>(null)
        private set

    /** The text with accepted changes, or null when nothing has been accepted. */
    val workingText: String?
        get() {
            val revision = reviewed?.revision ?: Revision(text, carried, listOf(carried.map { it.id }))
            return revision.takeIf { it.acceptedCount > 0 }?.workingText
        }

    /**
     * Checks [text] as [language], or detects the language when it's null, starting afresh.
     * [origin] and [hostApp] (the app the text came from, when known) go into history.
     */
    fun check(text: String, language: Language?, origin: Origin, hostApp: String? = null) {
        // A new check replaces the card; whatever it had accepted was never applied.
        history.close(finalText = null)
        this.text = text
        this.language = language
        this.origin = origin
        this.hostApp = hostApp
        settled = emptyList()
        run(Run.Fresh)
    }

    /**
     * The writer picked another [language] (null to detect it) on the open card: checks the
     * same text again in it. A different language is a different review, so nothing
     * accepted is carried over and the answers to assumptions start afresh, as with a new
     * [check]; but it's the same card, so history keeps one session for it, which ends with
     * the last language picked. Does nothing when no card is open.
     */
    fun changeLanguage(language: Language?) {
        if (state == null) return
        this.language = language
        settled = emptyList()
        run(Run.NewLanguage)
    }

    /** Checks the same text again (a retry, or after settings changed), keeping answers and accepted changes. */
    fun recheck() = run(Run.Again)

    /**
     * Back from settings with the card still up: checks again, as [recheck] does, when the
     * check failed or the settings that affect it ([CheckContext]) are no longer the ones it
     * ran with. Does nothing when no card is open.
     */
    fun recheckIfStale() {
        if (state == null) return
        viewModelScope.launch {
            val now = try {
                withContext(Dispatchers.Default) { checker() }.context()
            } catch (_: CheckFailure) {
                null
            }
            if (state != null && (state is CardState.Failed || now == null || now != ranWith)) recheck()
        }
    }

    /** Overrides an assumption with the writer's [answer] and checks again. */
    fun settle(about: String, answer: String) {
        settled = settled.filterNot { it.about == about } + Settled(about, answer)
        run(Run.Again)
    }

    /** What a [run] is: how it starts in history and what it keeps of the card. */
    private enum class Run {
        /** A new card: a new session, nothing kept. */
        Fresh,

        /** The same card in another language: the same session, nothing kept. */
        NewLanguage,

        /** The same card checked again (maybe with new settings): the same session, accepted changes kept. */
        Again,
    }

    private fun run(kind: Run) {
        if (kind == Run.Again) {
            reviewed?.let { carried = it.revision.acceptedEdits }
        } else {
            carried = emptyList()
        }
        val text = text
        val language = language
        val origin = origin
        val hostApp = hostApp
        val keep = carried
        job?.cancel()
        state = CardState.Loading(text)
        job = viewModelScope.launch {
            var answers = settled
            val shown = try {
                // Creating the checker reads its prompt from assets, so not on the main thread.
                val checker = withContext(Dispatchers.Default) { checker() }
                val context = checker.context()
                ranWith = context
                val ask: suspend () -> Checked = { checker.check(text, language, answers, context) }
                val opening = Opening(origin, hostApp, language?.code, context.stored)
                val reuse: suspend (Kept) -> Checked = { kept -> checker.reuse(text, language, kept.raw, context) }
                val answer = when (kind) {
                    Run.Fresh -> history.firstAttempt(text, opening, ask, reuse)
                    Run.NewLanguage -> history.languageChanged(text, opening, ask, reuse)
                    Run.Again -> {
                        // Settings may have changed while the card was up: the attempt records the new ones.
                        history.recheck(opening)
                        Answer(ask(), null)
                    }
                }
                answer.reused?.let {
                    answers = it.settled
                    settled = it.settled
                }
                val result = answer.value.result
                val kept = if (result is CheckResult.Reviewed) {
                    result.copy(revision = result.revision.acceptMatching(keep))
                } else {
                    result
                }
                history.succeeded(answers, answer.value.raw, kept, reusedFrom = answer.reused?.sessionId)
                CardState.Done(kept, answers)
            } catch (failure: CheckFailure) {
                history.failed(answers, failure.reason.token, failure.detail, failure.raw)
                CardState.Failed(failure.reason, failure.detail)
            }
            state = shown
        }
    }

    fun accept(id: Int) = update { it.accept(id) }

    /** Accepts every remaining change of [kind]; returns true when nothing is left to decide. */
    fun acceptAll(kind: EditKind): Boolean {
        update { it.acceptAll(kind) }
        return reviewed?.isResolved == true
    }

    fun undo() = update { it.undo() }

    /** The writer copied the version shown for [kind]; only history needs to know. */
    fun copied(kind: EditKind) = history.copied(kind)

    /**
     * Closes the card. [applied] says whether its accepted changes went back to the text:
     * pass true only when the host really hands [workingText] back. A host whose process
     * may end as soon as it finishes passes [waitMs], to block that long at most until the
     * session's close is written (see [CheckHistory.awaitWrites]).
     */
    fun dismiss(applied: Boolean = true, waitMs: Long = 0) {
        history.close(finalText = if (applied) workingText else null)
        job?.cancel()
        carried = emptyList()
        state = null
        if (waitMs > 0) history.awaitWrites(waitMs)
    }

    /** Gone without [dismiss]: nothing was handed back. */
    override fun onCleared() = history.close(finalText = null)

    private val reviewed get() = (state as? CardState.Done)?.result as? CheckResult.Reviewed

    private fun update(change: (Revision) -> Revision) {
        val done = state as? CardState.Done ?: return
        val current = done.result as? CheckResult.Reviewed ?: return
        val revision = change(current.revision)
        state = done.copy(result = current.copy(revision = revision))
        history.changed(revision)
    }

    companion object {
        /** Makes a card's view model with the app's checker and history. */
        fun factory(app: App): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                CheckViewModel(
                    checker = { app.checker },
                    history = CheckHistory(
                        store = app.history,
                        environment = app.historyEnvironment,
                        scope = app.appScope,
                        log = { message, e -> Log.w(TAG, message, e) },
                        maxChars = Checker.MAX_CHARS,
                    ),
                )
            }
        }
    }
}

private const val TAG = "CheckViewModel"
