package com.evanaronson.linguize.ui.settings

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.stringResource
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.Judgments
import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.core.Punctuation
import com.evanaronson.linguize.core.WritingRegister
import com.evanaronson.linguize.data.MenuEntry
import com.evanaronson.linguize.data.PopupPosition
import com.evanaronson.linguize.llm.Provider
import com.evanaronson.linguize.ui.components.ChoicePage
import com.evanaronson.linguize.ui.components.ListCard
import com.evanaronson.linguize.ui.components.ListRow
import com.evanaronson.linguize.ui.components.ScreenColumn
import com.evanaronson.linguize.ui.components.SectionHeader
import com.evanaronson.linguize.ui.text.descriptionResource
import com.evanaronson.linguize.ui.text.displayName
import com.evanaronson.linguize.ui.text.labelResource
import com.evanaronson.linguize.ui.secondopinion.SecondOpinionPage
import com.evanaronson.linguize.ui.secondopinion.SecondOpinionSettingsRow

/** The Settings choices that open a page of their own; the row and the page share a title. */
enum class SettingsPage(val token: String, @param:StringRes val title: Int) {
    Service("service", R.string.service),
    Model("model", R.string.model),
    Menu("menu", R.string.selection_menu_label),
    Suggest("suggest", R.string.what_to_suggest),
    WritingIn("writing", R.string.writing_language),
    Register("register", R.string.writing_register),
    Punctuation("punctuation", R.string.punctuation),
    Position("position", R.string.popup_position),
    SecondOpinion("second_opinion", R.string.lab_second_opinion_title),
    ;

    companion object {
        fun read(token: String) = entries.firstOrNull { it.token == token }
    }
}

/**
 * Settings: one card per group, each row showing its current value. A row with more than
 * a few options opens a [SettingsPage]. The activity owns the navigation bar and insets.
 */
@Composable
fun SettingsScreen(
    settings: SettingsViewModel,
    onOpen: (SettingsPage) -> Unit,
    appVoiceRow: @Composable () -> Unit = {},
) {
    // The saved settings load in a moment; showing defaults first would flash the wrong choices.
    val state = settings.state ?: return
    LaunchedEffect(Unit) { settings.showModels() }
    ScreenColumn {
        SectionHeader(stringResource(R.string.ai_service))
        ModelCard(state, settings, onOpen)
        SecondOpinionSettingsRow(onOpen = { onOpen(SettingsPage.SecondOpinion) })

        SectionHeader(stringResource(R.string.checking))
        val separator = stringResource(R.string.list_separator)
        val menu = MenuEntry.entries.filter { it in state.menu }.map { stringResource(it.label) }.joinToString(separator)
        val suggest = stringResource(state.judgments.labelResource)
        ListCard(
            { ListRow(stringResource(R.string.selection_menu_label), menu, onClick = { onOpen(SettingsPage.Menu) }) },
            { ListRow(stringResource(R.string.what_to_suggest), suggest, onClick = { onOpen(SettingsPage.Suggest) }) },
        )

        SectionHeader(stringResource(R.string.writing_preferences))
        val profile = state.writingProfile
        val language = Language.forCode(state.writingLanguage) ?: Language.Catalan
        val register = stringResource(profile.register.labelResource)
        val punctuation = stringResource(profile.punctuation.labelResource)
        ListCard(
            { ListRow(stringResource(R.string.writing_language), language.displayName(), onClick = { onOpen(SettingsPage.WritingIn) }) },
            { ListRow(stringResource(R.string.writing_register), register, onClick = { onOpen(SettingsPage.Register) }) },
            { ListRow(stringResource(R.string.punctuation), punctuation, onClick = { onOpen(SettingsPage.Punctuation) }) },
        )
        appVoiceRow()

        SectionHeader(stringResource(R.string.popup))
        val position = stringResource(state.popupPosition.labelResource)
        ListCard({ ListRow(stringResource(R.string.popup_position), position, onClick = { onOpen(SettingsPage.Position) }) })

        SectionHeader(stringResource(R.string.history))
        HistorySwitch(state, settings)
    }
}

/** One page per [SettingsPage]: the options, each with what it does. */
@Composable
fun SettingsChoiceScreen(settings: SettingsViewModel, page: SettingsPage) {
    val state = settings.state ?: return
    when (page) {
        SettingsPage.Service -> ChoicePage(
            Provider.entries, { it == state.provider }, { stringResource(it.labelResource) },
            { stringResource(it.descriptionResource) }, settings::selectProvider,
        )
        SettingsPage.Model -> ModelPage(state, settings)
        SettingsPage.Menu -> ChoicePage(
            MenuEntry.entries, { it in state.menu }, { stringResource(it.label) },
            { it.language?.displayName() ?: stringResource(R.string.detect_language) },
            settings::toggleMenuEntry, note = stringResource(R.string.menu_note), multiple = true,
        )
        SettingsPage.Suggest -> ChoicePage(
            Judgments.entries, { it == state.judgments }, { stringResource(it.labelResource) },
            { if (it == Judgments.Both) stringResource(R.string.suggestions_description) else null },
            settings::setJudgments,
        )
        SettingsPage.WritingIn -> ChoicePage(
            Language.all, { it.code == state.writingLanguage }, { it.displayName() },
            { language ->
                stringResource(
                    if (language.code == state.writingLanguage) R.string.writing_language_current else R.string.writing_language_other,
                    language.displayName(),
                )
            },
            settings::selectWritingLanguage,
        )
        SettingsPage.Register -> ChoicePage(
            WritingRegister.entries, { it == state.writingProfile.register }, { stringResource(it.labelResource) },
            { stringResource(it.descriptionResource) }, settings::setWritingRegister,
        )
        SettingsPage.Punctuation -> ChoicePage(
            Punctuation.entries, { it == state.writingProfile.punctuation }, { stringResource(it.labelResource) },
            { stringResource(it.descriptionResource) }, settings::setWritingPunctuation,
        )
        SettingsPage.Position -> ChoicePage(
            PopupPosition.entries, { it == state.popupPosition }, { stringResource(it.labelResource) },
            { it.descriptionResource?.let { id -> stringResource(id) } }, settings::setPopupPosition,
        )
        SettingsPage.SecondOpinion -> SecondOpinionPage()
    }
}
