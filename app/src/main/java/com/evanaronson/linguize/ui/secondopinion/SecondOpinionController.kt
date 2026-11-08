package com.evanaronson.linguize.ui.secondopinion

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.evanaronson.linguize.App
import com.evanaronson.linguize.CheckContext
import com.evanaronson.linguize.Checker
import com.evanaronson.linguize.core.CheckResult
import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.core.Settled
import com.evanaronson.linguize.core.opinion.Opinions
import com.evanaronson.linguize.core.opinion.SecondAnswer
import com.evanaronson.linguize.data.SecondOpinionPlan
import com.evanaronson.linguize.data.SecondOpinionPreferences
import com.evanaronson.linguize.data.SecondOpinionTarget
import com.evanaronson.linguize.data.planSecondOpinion
import com.evanaronson.linguize.llm.CheckFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The check on the card, as a second opinion repeats it: the same text, language, answers and settings. */
data class SecondOpinionRequest(
    val text: String,
    val language: Language?,
    val settled: List<Settled>,
    val context: CheckContext,
    val first: CheckResult.Reviewed,
) {
    /** Identifies the answer being compared; accepting or undoing its changes doesn't change it. */
    val key: List<Any?>
        get() = listOf(text, language, settled, context, first.copy(revision = first.revision.copy(steps = emptyList())))

    /** The service and model the check itself ran with. */
    val checkedWith: SecondOpinionTarget get() = SecondOpinionTarget(context.provider, context.model)
}

/** Where a second opinion stands for one check. */
sealed interface SecondOpinionState {
    val key: List<Any?>

    /** No second opinion can run for this check; [plan] says why. */
    data class Unavailable(override val key: List<Any?>, val plan: SecondOpinionPlan) : SecondOpinionState

    /** One can run; nothing has been asked yet. */
    data class Ready(override val key: List<Any?>, val checkedWith: SecondOpinionTarget, val target: SecondOpinionTarget) :
        SecondOpinionState

    data class Asking(override val key: List<Any?>, val checkedWith: SecondOpinionTarget, val target: SecondOpinionTarget) :
        SecondOpinionState

    data class Answered(
        override val key: List<Any?>,
        val checkedWith: SecondOpinionTarget,
        val target: SecondOpinionTarget,
        val answer: SecondAnswer,
    ) : SecondOpinionState

    data class Failed(
        override val key: List<Any?>,
        val checkedWith: SecondOpinionTarget,
        val target: SecondOpinionTarget,
        val reason: CheckFailure.Reason,
        val detail: String?,
        val userMessage: CheckFailure.Message?,
    ) : SecondOpinionState
}

/**
 * Lab: asks a second model for the same check the card shows and compares the two answers
 * ([Opinions]). It only reads the card's check through [request]; it never changes the card,
 * and second opinions go nowhere near history, so recording rules stay as they are.
 * A result belongs to the check it was asked for: once the card shows another answer (a
 * re-check, another language, a new check), [state] is null until [prepare] runs for it.
 */
class SecondOpinionController(
    private val scope: CoroutineScope,
    private val checker: () -> Checker,
    /** Reads preferences and keys; runs off the main thread. */
    private val plan: (CheckContext) -> SecondOpinionPlan,
    /** The reviewed check on the card now, or null when it shows none. */
    private val request: () -> SecondOpinionRequest?,
) {
    private var current by mutableStateOf<SecondOpinionState?>(null)
    private var job: Job? = null

    /** The key of the check on the card now; [prepare] again when it changes. */
    val requestKey: List<Any?>? get() = request()?.key

    /** Where the second opinion stands for the check on the card; null before [prepare] finishes. */
    val state: SecondOpinionState?
        get() {
            val key = requestKey ?: return null
            return current?.takeIf { it.key == key }
        }

    /** Works out, off the main thread, whether a second opinion can run for the check on the card. */
    fun prepare() {
        val request = request() ?: return
        val key = request.key
        if (current?.key == key) return
        job?.cancel()
        current = null
        job = scope.launch {
            val plan = withContext(Dispatchers.IO) {
                try {
                    plan(request.context)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    SecondOpinionPlan.Off
                }
            }
            if (requestKey != key) return@launch
            current = when (plan) {
                is SecondOpinionPlan.Ready -> SecondOpinionState.Ready(key, request.checkedWith, plan.target)
                else -> SecondOpinionState.Unavailable(key, plan)
            }
        }
    }

    /** Asks the second model, or asks again after a failure. Does nothing while it isn't ready. */
    fun ask() {
        val request = request() ?: return
        val key = request.key
        val target = when (val now = state) {
            is SecondOpinionState.Ready -> now.target
            is SecondOpinionState.Failed -> now.target
            else -> return
        }
        job?.cancel()
        val asking = SecondOpinionState.Asking(key, request.checkedWith, target)
        current = asking
        job = scope.launch {
            val next = try {
                val answer = secondOpinion(withContext(Dispatchers.Default) { checker() }, request, target)
                SecondOpinionState.Answered(key, request.checkedWith, target, answer)
            } catch (failure: CheckFailure) {
                SecondOpinionState.Failed(key, request.checkedWith, target, failure.reason, failure.detail, failure.userMessage)
            }
            if (current === asking) current = next
        }
    }

    companion object {
        /** The app's own choice of second model, read from the Lab's preferences, settings and keys. */
        fun plan(context: Context): (CheckContext) -> SecondOpinionPlan {
            val app = App.of(context)
            val preferences by lazy { SecondOpinionPreferences(app) }
            return { check ->
                planSecondOpinion(
                    preferences.choice, check.provider, check.model,
                    modelFor = { app.settings.model(it) ?: it.recommendedModel },
                    hasKey = { !app.keys.get(it).isNullOrBlank() },
                )
            }
        }
    }
}

/**
 * Runs [request]'s check again with [target]'s service and model, and every other setting as
 * it was, then compares the answer with the first. Throws [CheckFailure].
 */
suspend fun secondOpinion(checker: Checker, request: SecondOpinionRequest, target: SecondOpinionTarget): SecondAnswer {
    val context = request.context.copy(provider = target.provider, model = target.model)
    val checked = checker.check(request.text, request.language, request.settled, context)
    return withContext(Dispatchers.Default) { Opinions.compare(request.first, checked.result) }
}
