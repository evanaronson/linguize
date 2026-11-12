package com.evanaronson.linguize.ui.tonecheck

import com.evanaronson.linguize.core.CheckResult
import com.evanaronson.linguize.history.Replayed
import com.evanaronson.linguize.history.SessionDetail
import com.evanaronson.linguize.llm.lab.ToneInCheck

/**
 * Lab, How it lands on a saved check: the decided attempt's raw answer, as History already
 * keeps it, read for its optional `landing`. Older answers have none and are returned as they are.
 * The saved check shows it in its read-only Meaning section, as the card does.
 */
fun Replayed.withLanding(detail: SessionDetail): Replayed {
    val reviewed = result as? CheckResult.Reviewed ?: return this
    val raw = detail.decidedAttempt()?.let { detail.session.attempts.getOrNull(it)?.raw } ?: return this
    val landing = ToneInCheck.landing(raw, detail.session.text) ?: return this
    return copy(result = reviewed.copy(landing = landing))
}
