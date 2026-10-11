package com.evanaronson.linguize.ui.history

import com.evanaronson.linguize.core.CheckResult
import com.evanaronson.linguize.history.Replayed
import com.evanaronson.linguize.ui.card.CardState

/**
 * The card a session ended with, for reading. [replayed] is that card as history rebuilt
 * it (`replay`: the decided attempt's answer, with what the writer took as recorded, and
 * the writer's answers to assumptions it was made with); this only shows assumptions
 * without the way to change them, since a past check can't be asked again.
 */
internal fun readOnlyCard(replayed: Replayed): CardState.Done {
    val shown = when (val result = replayed.result) {
        is CheckResult.Reviewed -> result.copy(assumptions = result.assumptions.map { it.copy(alternatives = emptyList()) })
        else -> result
    }
    return CardState.Done(shown, replayed.settled)
}
