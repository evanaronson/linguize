package com.evanaronson.linguize.ui.words

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.R
import com.evanaronson.linguize.ui.components.ListRow
import com.evanaronson.linguize.ui.components.SectionCard

/** Home's way in: shown once the writer has typed a word that counts, gone when nothing is left. */
@Composable
fun WordsHomeCard(words: WordsViewModel, onOpen: () -> Unit) {
    // History writes already rebuild the index; Home doesn't ask again on every visit.
    val state by words.state.collectAsState()
    val top = state.index?.languages?.firstOrNull()?.takeIf { it.distinctWords > 0 } ?: return
    Spacer(Modifier.height(12.dp))
    SectionCard {
        ListRow(
            stringResource(R.string.lab_words_title),
            pluralStringResource(R.plurals.lab_words_home_summary, top.distinctWords, top.distinctWords, languageLabel(top.language)),
            onClick = onOpen,
            onClickLabel = stringResource(R.string.lab_words_open),
        )
    }
}

/** The History page's way in, above the list. */
@Composable
fun WordsHistoryRow(onOpen: () -> Unit) {
    SectionCard(Modifier.padding(bottom = 12.dp)) {
        ListRow(
            stringResource(R.string.lab_words_title),
            stringResource(R.string.lab_words_history_summary),
            onClick = onOpen,
            onClickLabel = stringResource(R.string.lab_words_open),
        )
    }
}
