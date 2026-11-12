package com.evanaronson.linguize.ui.memory

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.evanaronson.linguize.App
import com.evanaronson.linguize.R
import com.evanaronson.linguize.ui.components.ListCard
import com.evanaronson.linguize.ui.components.ListRow
import com.evanaronson.linguize.ui.components.SectionHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Settings' way into "What Linguize knows", with how many notes there are or that it's off. */
@Composable
fun MemorySettingsRow(onOpen: () -> Unit) {
    val store = App.of(LocalContext.current).memoryStore
    val state by store.state.collectAsState()
    LaunchedEffect(store) { withContext(Dispatchers.IO) { store.load() } }
    val current = state
    SectionHeader(stringResource(R.string.lab_memory_settings_header))
    ListCard({
        ListRow(
            stringResource(R.string.lab_memory_title),
            when {
                current == null -> null
                !current.enabled -> stringResource(R.string.lab_memory_settings_off)
                current.facts.isEmpty() -> stringResource(R.string.lab_memory_settings_empty)
                else -> pluralStringResource(R.plurals.lab_memory_count, current.facts.size, current.facts.size)
            },
            onClick = onOpen,
        )
    })
}
