package com.evanaronson.linguize.ui.vocabgaps

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.R
import com.evanaronson.linguize.ui.components.ListRow
import com.evanaronson.linguize.ui.components.SectionCard

/** Home's way in, with the other learning features. Static, so Home never counts words. */
@Composable
fun VocabGapsHomeEntry(onOpen: () -> Unit) {
    Spacer(Modifier.height(12.dp))
    SectionCard {
        ListRow(
            stringResource(R.string.lab_vocab_gaps_title),
            stringResource(R.string.lab_vocab_gaps_home_summary),
            onClick = onOpen,
            onClickLabel = stringResource(R.string.lab_vocab_gaps_open),
        )
    }
}
