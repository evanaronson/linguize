package com.evanaronson.linguize.ui.conjugations

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.evanaronson.linguize.R
import com.evanaronson.linguize.ui.components.ListCard
import com.evanaronson.linguize.ui.components.ListRow
import com.evanaronson.linguize.ui.components.SectionHeader

/** Home's row for Your conjugations, beside the other learning features. */
@Composable
fun ConjugationsHomeEntry(onOpen: () -> Unit) {
    SectionHeader(stringResource(R.string.lab_conjugations_title))
    ListCard({
        ListRow(
            title = stringResource(R.string.lab_conjugations_title),
            summary = stringResource(R.string.lab_conjugations_home_summary),
            onClick = onOpen,
            onClickLabel = stringResource(R.string.lab_conjugations_open),
        )
    })
}
