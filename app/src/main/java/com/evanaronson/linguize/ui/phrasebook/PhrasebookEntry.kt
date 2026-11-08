package com.evanaronson.linguize.ui.phrasebook

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.evanaronson.linguize.R
import com.evanaronson.linguize.data.phrasebook.PhrasebookStore
import com.evanaronson.linguize.ui.components.ListCard
import com.evanaronson.linguize.ui.components.ListRow
import com.evanaronson.linguize.ui.components.SectionHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Home's way into the Phrasebook, with how many phrases are saved. */
@Composable
fun PhrasebookHomeSection(onOpen: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { PhrasebookStore.of(context) }
    val phrases by store.phrases.collectAsState()
    LaunchedEffect(store) { withContext(Dispatchers.IO) { store.load() } }
    val count = phrases?.size
    SectionHeader(stringResource(R.string.lab_phrasebook_title))
    ListCard({
        ListRow(
            stringResource(R.string.lab_phrasebook_home_row),
            when (count) {
                null -> null
                0 -> stringResource(R.string.lab_phrasebook_home_empty)
                else -> pluralStringResource(R.plurals.lab_phrasebook_count, count, count)
            },
            onClick = onOpen,
        )
    })
}
