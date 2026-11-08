package com.evanaronson.linguize.ui.gaps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.evanaronson.linguize.App
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.core.Selection
import com.evanaronson.linguize.history.Origin
import com.evanaronson.linguize.ui.card.CardActions
import com.evanaronson.linguize.ui.card.CheckViewModel
import com.evanaronson.linguize.ui.card.ResultCard
import com.evanaronson.linguize.ui.components.ButtonKind
import com.evanaronson.linguize.ui.components.LanguagePicker
import com.evanaronson.linguize.ui.components.LinguizeButton
import com.evanaronson.linguize.ui.components.LinguizeTextField
import com.evanaronson.linguize.ui.components.ListCard
import com.evanaronson.linguize.ui.components.ListRow
import com.evanaronson.linguize.ui.components.Quiet
import com.evanaronson.linguize.ui.components.ScreenColumn
import com.evanaronson.linguize.ui.components.SectionCard
import com.evanaronson.linguize.ui.copyToClipboard

/** Home's entry: its own card below Try it. */
@Composable
fun GapFillerEntry(onOpen: () -> Unit) {
    ListCard({
        ListRow(
            stringResource(R.string.lab_gap_filler_title),
            stringResource(R.string.lab_gap_filler_home_summary),
            onClick = onOpen,
        )
    }, modifier = Modifier.padding(top = 12.dp))
}

/**
 * Write in the target language with [brackets] where you got stuck, fill the gaps, then use
 * the result, check it with the normal card, or copy it. Like Try it, nothing here is saved
 * to History.
 */
@Composable
fun GapFillScreen(gaps: GapFillViewModel, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    // Its own check card, so Home's Try it card is left as it was.
    val check: CheckViewModel = viewModel(key = CHECK_KEY, factory = CheckViewModel.factory(App.of(context)))
    val sample = stringResource(R.string.lab_gap_filler_sample)
    val draft = gaps.draft ?: sample
    var blank by rememberSaveable { mutableStateOf(false) }
    var code by rememberSaveable { mutableStateOf<String?>(null) }

    fun fill(language: Language?) {
        if (draft.isBlank()) {
            blank = true
        } else {
            blank = false
            check.dismiss(applied = false)
            gaps.start(draft, language)
        }
    }

    fun use(text: String) {
        gaps.draft = text
        gaps.close()
    }

    ScreenColumn {
        SectionCard(
            modifier = Modifier.padding(top = 8.dp),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        ) {
            LinguizeTextField(
                value = draft,
                onValueChange = {
                    gaps.draft = it
                    blank = false
                    gaps.close()
                    check.dismiss(applied = false)
                },
                placeholder = stringResource(R.string.lab_gap_filler_placeholder),
                accessibleLabel = stringResource(R.string.lab_gap_filler_your_text),
                supporting = if (blank) stringResource(R.string.lab_gap_filler_no_text) else null,
                isError = blank,
                large = true,
                minLines = 3,
            )
            FlowRow(
                Modifier.padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LanguagePicker(Language.forCode(code), onSelect = {
                    code = it?.code
                    fill(it)
                })
                LinguizeButton(stringResource(R.string.lab_gap_filler_fill), { fill(Language.forCode(code)) }, ButtonKind.Filled)
            }
            gaps.state?.let { state ->
                GapFillCard(
                    state,
                    GapActions(
                        onChoose = gaps::choose,
                        onRetry = gaps::retry,
                        onCopy = { context.copyToClipboard(it) },
                        onUse = { gaps.result?.let(::use) },
                        onCheck = {
                            gaps.result?.let { filled ->
                                use(filled)
                                check.check(filled, Language.forCode(code), Origin.Tester)
                            }
                        },
                        onOpenSettings = onOpenSettings,
                    ),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    scrollable = false,
                )
            }
            check.state?.let { state ->
                ResultCard(
                    state = state,
                    actions = CardActions.of(check, context, onClose = {
                        check.workingText?.let { gaps.draft = Selection.of(draft).with(it) }
                        check.dismiss()
                    }, onOpenSettings = onOpenSettings),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    scrollable = false,
                )
            }
            Quiet(stringResource(R.string.lab_gap_filler_note), Modifier.padding(top = 12.dp))
        }
    }
}

private const val CHECK_KEY = "lab_gap_filler_check"
