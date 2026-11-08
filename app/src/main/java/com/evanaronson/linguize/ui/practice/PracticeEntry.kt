package com.evanaronson.linguize.ui.practice

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.evanaronson.linguize.R
import com.evanaronson.linguize.ui.components.ListCard
import com.evanaronson.linguize.ui.components.ListRow
import com.evanaronson.linguize.ui.components.SectionHeader

/** Home's Practice row, shown only while items are due: not nagging is a feature. */
@Composable
fun PracticeEntry(model: PracticeViewModel, onOpen: () -> Unit) {
    val state by model.state.collectAsState()
    // Time passes while Home is away; due items are counted against the clock.
    LaunchedEffect(Unit) { model.tick() }
    val due = state.due
    if (due == 0) return
    SectionHeader(stringResource(R.string.lab_practice_title))
    ListCard({
        ListRow(
            title = pluralStringResource(R.plurals.lab_practice_due, due, due),
            summary = stringResource(R.string.lab_practice_due_summary),
            onClick = onOpen,
            onClickLabel = stringResource(R.string.lab_practice_open),
        )
    })
}
