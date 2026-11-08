package com.evanaronson.linguize.ui.writingpad

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.core.writingpad.LiveSuggestion
import com.evanaronson.linguize.data.writingpad.Draft
import com.evanaronson.linguize.llm.CheckFailure
import com.evanaronson.linguize.ui.card.WeightMeters
import com.evanaronson.linguize.ui.card.visibleValue
import com.evanaronson.linguize.ui.components.ButtonKind
import com.evanaronson.linguize.ui.components.Chevron
import com.evanaronson.linguize.ui.components.LanguagePicker
import com.evanaronson.linguize.ui.components.LinguizeButton
import com.evanaronson.linguize.ui.components.ListCard
import com.evanaronson.linguize.ui.components.ListRow
import com.evanaronson.linguize.ui.components.Quiet
import com.evanaronson.linguize.ui.components.ScreenColumn
import com.evanaronson.linguize.ui.components.SectionCard
import com.evanaronson.linguize.ui.components.SectionHeader
import com.evanaronson.linguize.ui.components.shortDate
import com.evanaronson.linguize.ui.components.uiLocale
import com.evanaronson.linguize.ui.copyToClipboard
import com.evanaronson.linguize.ui.openExternal
import com.evanaronson.linguize.ui.text.hintResource
import com.evanaronson.linguize.ui.text.languageDisplayName
import com.evanaronson.linguize.ui.text.textResource
import com.evanaronson.linguize.ui.text.titleResource
import com.evanaronson.linguize.ui.theme.Linguize
import kotlinx.coroutines.launch

/**
 * The Writing pad: the drafts list, or the open draft in a full-screen editor.
 * [onExit] leaves the pad; [onOpenSettings] fixes a missing or rejected key.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WritingPadApp(pad: WritingPadViewModel, onExit: () -> Unit, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val editor = pad.editor
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val deleted = stringResource(R.string.lab_writing_pad_draft_deleted)
    val undo = stringResource(R.string.undo)
    fun delete(id: String) = pad.delete(id) { draft ->
        scope.launch {
            if (snackbar.showSnackbar(deleted, undo, withDismissAction = false) == SnackbarResult.ActionPerformed) {
                pad.restore(draft)
            }
        }
    }
    BackHandler(enabled = editor != null) { pad.close() }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = if (editor != null) Linguize.colors.card else Linguize.colors.ground,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.lab_writing_pad_title), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                },
                navigationIcon = {
                    val back = if (editor != null) stringResource(R.string.lab_writing_pad_drafts) else stringResource(R.string.app_name)
                    IconButton(onClick = { if (editor != null) pad.close() else onExit() }) {
                        Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.back_to, back))
                    }
                },
                actions = {
                    if (editor != null) {
                        val text = editor.value.text
                        IconButton(onClick = { context.copyToClipboard(text) }, enabled = text.isNotBlank()) {
                            Icon(painterResource(R.drawable.ic_copy), stringResource(R.string.lab_writing_pad_copy))
                        }
                        IconButton(onClick = { context.share(text) }, enabled = text.isNotBlank()) {
                            Icon(painterResource(R.drawable.lab_writing_pad_ic_share), stringResource(R.string.lab_writing_pad_share))
                        }
                        IconButton(onClick = { delete(editor.draftId) }) {
                            Icon(painterResource(R.drawable.lab_writing_pad_ic_delete), stringResource(R.string.lab_writing_pad_delete_draft),
                                tint = MaterialTheme.colorScheme.error)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = if (editor != null) Linguize.colors.card else Linguize.colors.ground,
                    scrolledContainerColor = if (editor != null) Linguize.colors.card else Linguize.colors.ground,
                ),
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            if (editor == null) {
                DraftList(pad.drafts, onNew = pad::newDraft, onOpen = pad::open, onDelete = ::delete)
            } else {
                PadEditorScreen(pad, editor, onOpenSettings)
            }
        }
    }
}

@Composable
private fun DraftList(drafts: List<Draft>?, onNew: () -> Unit, onOpen: (String) -> Unit, onDelete: (String) -> Unit) {
    ScreenColumn {
        LinguizeButton(stringResource(R.string.lab_writing_pad_new_draft), onNew, ButtonKind.Filled,
            modifier = Modifier.padding(top = 8.dp))
        SectionHeader(stringResource(R.string.lab_writing_pad_drafts))
        when {
            drafts == null -> Unit
            drafts.isEmpty() -> SectionCard(contentPadding = PaddingValues(20.dp)) {
                Text(stringResource(R.string.lab_writing_pad_no_drafts), style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> ListCard(*drafts.map { draft ->
                @Composable {
                    val title = draft.displayTitle() ?: stringResource(R.string.lab_writing_pad_untitled)
                    val date = shortDate(draft.updatedAt, locale = LocalResources.current.uiLocale)
                    val preview = draft.text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }
                        .drop(if (draft.title.isBlank()) 1 else 0).firstOrNull()
                    ListRow(
                        title = title,
                        summary = listOfNotNull(date, preview).joinToString(stringResource(R.string.list_separator)),
                        onClick = { onOpen(draft.id) },
                        onClickLabel = stringResource(R.string.lab_writing_pad_open_draft),
                    ) {
                        IconButton(onClick = { onDelete(draft.id) }) {
                            Icon(painterResource(R.drawable.lab_writing_pad_ic_delete),
                                stringResource(R.string.lab_writing_pad_delete_draft),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }.toTypedArray())
        }
        Quiet(stringResource(R.string.lab_writing_pad_drafts_note), Modifier.padding(top = 12.dp, start = 20.dp, end = 20.dp))
    }
}

@Composable
private fun PadEditorScreen(pad: WritingPadViewModel, editor: PadEditor, onOpenSettings: () -> Unit) {
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxSize().imePadding()) {
        StatusBar(editor, onNext = pad::nextChange, onRetry = pad::retry, onOpenSettings = onOpenSettings,
            onLanguage = { pad.setLanguage(it?.code) })
        HorizontalDivider(color = Linguize.colors.rowDivider)
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scroll)
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            TitleField(editor.title, pad::onTitleChange)
            Spacer(Modifier.size(12.dp))
            LiveTextField(
                value = editor.value,
                suggestions = editor.suggestions,
                focused = editor.focused.map { it.key }.toSet(),
                onValueChange = pad::onValueChange,
                onShow = pad::focus,
                modifier = Modifier.heightIn(min = 240.dp),
            )
        }
        AnimatedVisibility(visible = editor.focused.isNotEmpty()) {
            SuggestionPanel(editor.focused, onAccept = pad::accept, onDismiss = pad::dismiss, onClose = pad::closePanel)
        }
        MeaningFooter(editor)
    }
}

@Composable
private fun TitleField(title: String, onChange: (String) -> Unit) {
    val style = MaterialTheme.typography.titleLarge.copy(color = MaterialTheme.colorScheme.onSurface)
    val label = stringResource(R.string.lab_writing_pad_title_label)
    BasicTextField(
        value = title,
        onValueChange = { onChange(it.replace('\n', ' ')) },
        singleLine = true,
        textStyle = style,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
        decorationBox = { inner ->
            Box {
                if (title.isEmpty()) {
                    Text(stringResource(R.string.lab_writing_pad_title_placeholder), style = style,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                inner()
            }
        },
    )
}

/** The language, what the last check found, and the one next step. */
@Composable
private fun StatusBar(
    editor: PadEditor,
    onNext: () -> Unit,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    onLanguage: (Language?) -> Unit,
) {
    val status = editor.status
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            LanguagePicker(Language.forCode(editor.language), onLanguage)
            Text(
                statusText(status),
                style = MaterialTheme.typography.bodyMedium,
                color = if (status is PadStatus.Found && status.fixes > 0) Linguize.colors.proof
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            if (editor.suggestions.isNotEmpty()) {
                LinguizeButton(stringResource(R.string.lab_writing_pad_next_change), onNext, ButtonKind.Text)
            }
        }
        val hint = when (status) {
            is PadStatus.Failed -> status.failure.userMessage?.textResourceOrNull()?.let { stringResource(it) }
                ?: status.failure.reason.hintResource?.let { stringResource(it) }
            PadStatus.TooLong -> CheckFailure.Reason.TooLong.hintResource?.let { stringResource(it) }
            else -> null
        }
        hint?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp))
        }
        if (status is PadStatus.Failed) {
            when (status.failure.reason) {
                CheckFailure.Reason.NoKey, CheckFailure.Reason.BadKey, CheckFailure.Reason.BadModel ->
                    LinguizeButton(stringResource(R.string.open_settings), onOpenSettings, ButtonKind.Tonal,
                        modifier = Modifier.padding(top = 4.dp))
                CheckFailure.Reason.TooLong, CheckFailure.Reason.TooMany -> Unit
                else -> LinguizeButton(stringResource(R.string.retry), onRetry, ButtonKind.Tonal,
                    modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

private fun CheckFailure.Message.textResourceOrNull(): Int? = when (this) {
    // This one has a live limit in it; the too-long hint says the same without it.
    CheckFailure.Message.InputTooLong -> null
    else -> textResource
}

@Composable
private fun statusText(status: PadStatus): String = when (status) {
    PadStatus.Empty -> stringResource(R.string.lab_writing_pad_status_empty)
    PadStatus.Waiting -> stringResource(R.string.lab_writing_pad_status_waiting)
    PadStatus.Checking -> stringResource(R.string.checking)
    PadStatus.TooLong -> stringResource(CheckFailure.Reason.TooLong.titleResource)
    PadStatus.SoundsNatural -> stringResource(R.string.looks_good_both)
    PadStatus.Unclear -> stringResource(R.string.meaning_unclear)
    is PadStatus.WrongLanguage -> stringResource(R.string.wrong_language, languageDisplayName(status.expected))
    is PadStatus.Failed -> stringResource(status.failure.reason.titleResource)
    is PadStatus.Found -> {
        val fixes = pluralStringResource(R.plurals.fix_count, status.fixes, status.fixes)
        val rewordings = pluralStringResource(R.plurals.rewording_count, status.rewordings, status.rewordings)
        when {
            status.rewordings == 0 -> fixes
            status.fixes == 0 -> rewordings
            else -> stringResource(R.string.lab_writing_pad_counts, fixes, rewordings)
        }
    }
}

/** The tapped change: what it does, why, and Accept or Dismiss, without leaving the text. */
@Composable
private fun SuggestionPanel(
    suggestions: List<LiveSuggestion>,
    onAccept: (Long) -> Unit,
    onDismiss: (Long) -> Unit,
    onClose: () -> Unit,
) {
    // Keep showing the last change while the panel animates away.
    val last = remember { arrayOfNulls<List<LiveSuggestion>>(1) }
    if (suggestions.isNotEmpty()) last[0] = suggestions
    val shown = last[0].orEmpty()
    Column(
        Modifier
            .fillMaxWidth()
            .background(Linguize.colors.popup)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        shown.take(2).forEachIndexed { index, suggestion ->
            ChangeCard(suggestion, onAccept, onDismiss, onClose = if (index == 0) onClose else null)
        }
    }
}

@Composable
private fun ChangeCard(suggestion: LiveSuggestion, onAccept: (Long) -> Unit, onDismiss: (Long) -> Unit, onClose: (() -> Unit)?) {
    val proof = Linguize.colors.proof
    val from = suggestion.from.visibleValue()
    val to = suggestion.replacement.visibleValue()
    val description = when {
        suggestion.from.isEmpty() -> stringResource(R.string.change_add, to)
        suggestion.replacement.isEmpty() -> stringResource(R.string.change_remove, from)
        else -> stringResource(R.string.change_becomes, from, to)
    }
    val display = if (suggestion.from.isEmpty() || suggestion.replacement.isEmpty()) description
        else stringResource(R.string.change_replacement, from, to)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topEnd = 12.dp, bottomEnd = 12.dp))
            .background(Linguize.colors.panel)
            .drawBehind {
                val width = 2.dp.toPx()
                drawLine(proof, Offset(width / 2, 0f), Offset(width / 2, size.height), width)
            }
            .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(if (suggestion.kind == EditKind.Fix) R.string.lab_writing_pad_fix else R.string.lab_writing_pad_rewording),
                style = MaterialTheme.typography.labelLarge,
                color = if (suggestion.kind == EditKind.Fix) proof else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (onClose != null) {
                IconButton(onClick = onClose) {
                    Icon(painterResource(R.drawable.lab_writing_pad_ic_close), stringResource(R.string.lab_writing_pad_close_change),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                }
            } else {
                Spacer(Modifier.size(48.dp))
            }
        }
        Text(
            buildAnnotatedString {
                append(display)
                if (from.isNotEmpty() && to.isNotEmpty() && display != description) {
                    val fromStart = display.indexOf(from)
                    if (fromStart >= 0) addStyle(SpanStyle(textDecoration = TextDecoration.LineThrough), fromStart, fromStart + from.length)
                    val toStart = display.lastIndexOf(to)
                    if (toStart >= 0) addStyle(SpanStyle(fontWeight = FontWeight.SemiBold), toStart, toStart + to.length)
                }
            },
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(end = 10.dp).semantics { contentDescription = description },
        )
        suggestion.why?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, end = 10.dp))
        }
        WeightMeters(suggestion.assessment, Modifier.padding(top = 8.dp, end = 10.dp))
        Row(Modifier.fillMaxWidth().padding(top = 4.dp, end = 6.dp), horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically) {
            LinguizeButton(stringResource(R.string.lab_writing_pad_dismiss), { onDismiss(suggestion.key) }, ButtonKind.Text)
            Spacer(Modifier.width(8.dp))
            LinguizeButton(stringResource(R.string.accept), { onAccept(suggestion.key) }, ButtonKind.Tonal)
        }
    }
}

/** Meaning, live: folded to one line until opened, and marked when the text has changed since. */
@Composable
private fun MeaningFooter(editor: PadEditor) {
    var open by rememberSaveable { mutableStateOf(false) }
    val note = when {
        editor.meaning == null && editor.status == PadStatus.Checking -> stringResource(R.string.checking)
        editor.meaning == null -> stringResource(R.string.lab_writing_pad_meaning_none)
        !editor.meaningCurrent && editor.status == PadStatus.Checking -> stringResource(R.string.checking)
        !editor.meaningCurrent -> stringResource(R.string.lab_writing_pad_meaning_stale)
        else -> null
    }
    Column(Modifier.fillMaxWidth().background(Linguize.colors.panel)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(
                    onClickLabel = stringResource(if (open) R.string.lab_writing_pad_hide_meaning else R.string.lab_writing_pad_show_meaning),
                ) { open = !open }
                .heightIn(min = 48.dp)
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.meaning), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.width(12.dp))
            Text(
                if (open) note.orEmpty() else (editor.meaning ?: note.orEmpty()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Chevron(Modifier.padding(start = 8.dp).rotate(if (open) 270f else 90f))
        }
        AnimatedVisibility(visible = open) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 180.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
            ) {
                editor.meaning?.let {
                    Text(it, style = MaterialTheme.typography.bodyLarge,
                        color = if (editor.meaningCurrent) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant)
                } ?: Text(note.orEmpty(), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun Context.share(text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    openExternal(Intent.createChooser(send, getString(R.string.lab_writing_pad_share)))
}
