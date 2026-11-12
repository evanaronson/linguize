package com.evanaronson.linguize.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.evanaronson.linguize.R

/** A failed evidence read keeps existing results visible without presenting them as current. */
@Composable
internal fun EvidenceFailureNotice(retained: Boolean, onRetry: () -> Unit) {
    Column {
        CardNote(stringResource(if (retained) R.string.lab_evidence_refresh_failed else R.string.lab_evidence_load_failed))
        LinguizeButton(stringResource(R.string.lab_evidence_retry), onRetry, ButtonKind.Text)
    }
}
