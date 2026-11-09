package com.evanaronson.linguize.ui.tonecheck

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.CheckResult
import com.evanaronson.linguize.core.lab.Landing
import com.evanaronson.linguize.history.Replayed
import com.evanaronson.linguize.history.SessionDetail
import com.evanaronson.linguize.llm.lab.ToneInCheck
import com.evanaronson.linguize.ui.components.SectionCard
import com.evanaronson.linguize.ui.components.SectionHeader

/**
 * Lab, How it lands on a saved check: the decided attempt's raw answer, as History already
 * keeps it, read for its optional `landing`. Older answers have none and are returned as they are.
 */
fun Replayed.withLanding(detail: SessionDetail): Replayed {
    val reviewed = result as? CheckResult.Reviewed ?: return this
    val raw = detail.decidedAttempt()?.let { detail.session.attempts.getOrNull(it)?.raw } ?: return this
    val landing = ToneInCheck.landing(raw, detail.session.text) ?: return this
    return copy(result = reviewed.copy(landing = landing))
}

/** The saved check's How it lands section, read-only; draws nothing without a reading. */
@Composable
fun SavedLandingSection(landing: Landing?) {
    landing ?: return
    SectionHeader(stringResource(R.string.lab_tone_check_title))
    SectionCard(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)) {
        Text(
            stringResource(R.string.lab_tone_check_row_subtitle, stringResource(overallTitle(landing.overall)), landing.summary),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        LandingDetails(landing)
    }
}
