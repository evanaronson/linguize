package com.evanaronson.linguize.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.R
import com.evanaronson.linguize.data.KeyStatus
import com.evanaronson.linguize.llm.Provider
import com.evanaronson.linguize.ui.components.ButtonKind
import com.evanaronson.linguize.ui.components.CardNote
import com.evanaronson.linguize.ui.components.ChoiceRow
import com.evanaronson.linguize.ui.components.LinguizeButton
import com.evanaronson.linguize.ui.components.LinguizeTextField
import com.evanaronson.linguize.ui.components.ListRow
import com.evanaronson.linguize.ui.components.RowDivider
import com.evanaronson.linguize.ui.components.SectionCard
import com.evanaronson.linguize.ui.components.ScreenColumn
import com.evanaronson.linguize.ui.openExternal
import com.evanaronson.linguize.ui.text.labelResource
import com.evanaronson.linguize.ui.text.localizedText
import com.evanaronson.linguize.ui.text.titleResource

/**
 * The Model group. With a key: the key (and Delete key), Service, Model and Connection
 * (and Test). Without one, the key field sits right under Service, so it is the first
 * thing a new user meets.
 */
@Composable
internal fun ModelCard(state: SettingsState, settings: SettingsViewModel, onOpen: (SettingsPage) -> Unit) {
    val provider = state.provider
    val providerName = stringResource(provider.labelResource)
    val status = state.keys[provider]
    val recommended = stringResource(R.string.model_recommended, provider.recommendedModel)
    val model = state.model ?: recommended
    SectionCard {
        if (status is KeyStatus.Saved) {
            ListRow(stringResource(R.string.api_key), stringResource(R.string.key_saved_summary, status.lastFour)) {
                LinguizeButton(stringResource(R.string.delete_key), settings::removeKey, ButtonKind.Destructive)
            }
            RowDivider()
        }
        ListRow(stringResource(R.string.service), providerName, onClick = { onOpen(SettingsPage.Service) })
        RowDivider()
        if (status !is KeyStatus.Saved) {
            KeyEntry(provider, key = settings.keyDraft(provider), onKeyChange = { settings.setKeyDraft(provider, it) },
                unreadable = status == KeyStatus.Unreadable, saveFailed = state.keySaveFailed,
                onSave = settings::saveKey, onDelete = settings::removeKey)
        }
        ListRow(
            stringResource(R.string.model),
            if (state.hasKey) model else stringResource(R.string.model_more_with_key, model),
            onClick = { onOpen(SettingsPage.Model) },
        )
        if (state.hasKey) {
            RowDivider()
            ConnectionRow(state.modelTest, onTest = settings::testModel)
        }
    }
}

/** The models this key can use, the recommended one first. */
@Composable
internal fun ModelPage(state: SettingsState, settings: SettingsViewModel) {
    LaunchedEffect(Unit) { settings.showModels() }
    val provider = state.provider
    val providerName = stringResource(provider.labelResource)
    val recommended = stringResource(R.string.model_recommended, provider.recommendedModel)
    val available = (state.models as? ModelList.Loaded)?.models.orEmpty().filter { it != provider.recommendedModel }
    ScreenColumn {
        Spacer(Modifier.height(8.dp))
        SectionCard(Modifier.selectableGroup()) {
            (listOf<String?>(null) + available).forEachIndexed { index, model ->
                if (index > 0) RowDivider()
                ChoiceRow(model ?: recommended, null, model == state.model, { settings.selectModel(model) })
            }
        }
        CardNote(when (state.models) {
            ModelList.Loading -> stringResource(R.string.models_loading_note, providerName)
            ModelList.Failed -> stringResource(R.string.models_failed_note, providerName)
            is ModelList.Loaded -> stringResource(R.string.models_note, providerName)
        })
        if (state.hasKey) {
            LinguizeButton(
                stringResource(if (state.models == ModelList.Failed) R.string.retry else R.string.models_refresh),
                settings::refreshModels, ButtonKind.Text,
                enabled = state.models != ModelList.Loading,
                modifier = Modifier.padding(start = 8.dp, top = 4.dp),
            )
        }
    }
}

@Composable
private fun ConnectionRow(test: ModelTest?, onTest: () -> Unit) {
    val summary = when (test) {
        null -> stringResource(R.string.model_not_tested)
        ModelTest.Testing -> stringResource(R.string.model_testing)
        is ModelTest.Works -> stringResource(R.string.model_test_works, test.millis / 1000.0)
        is ModelTest.Failed -> listOfNotNull(
            stringResource(R.string.model_test_failed, stringResource(test.reason.titleResource)),
            test.userMessage?.localizedText(),
            // The AI service's own words, when it gave any.
            test.detail,
        ).joinToString("\n")
    }
    ListRow(
        stringResource(R.string.connection), summary,
        summaryColor = if (test is ModelTest.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        LinguizeButton(stringResource(R.string.test), onTest, ButtonKind.Text, enabled = test != ModelTest.Testing)
    }
}

@Composable
private fun KeyEntry(
    provider: Provider,
    key: String,
    onKeyChange: (String) -> Unit,
    unreadable: Boolean,
    saveFailed: Boolean,
    onSave: (String) -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val name = stringResource(provider.labelResource)
    Column(Modifier.padding(top = 12.dp, bottom = 8.dp)) {
        LinguizeTextField(
            value = key,
            onValueChange = onKeyChange,
            label = stringResource(R.string.provider_api_key, name),
            placeholder = stringResource(R.string.paste_key),
            supporting = when {
                saveFailed -> stringResource(R.string.key_save_failed)
                unreadable -> stringResource(R.string.key_unreadable)
                else -> stringResource(R.string.key_privacy, name)
            },
            isError = unreadable || saveFailed,
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        FlowRow(
            Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Kept in the field until saved, so a failed save can be retried. A saved key replaces this field.
            LinguizeButton(stringResource(R.string.save), { onSave(key) }, ButtonKind.Filled, enabled = key.isNotBlank())
            LinguizeButton(stringResource(R.string.get_key), {
                context.openExternal(Intent(Intent.ACTION_VIEW, Uri.parse(provider.keyUrl)))
            }, ButtonKind.Text)
            if (unreadable) LinguizeButton(stringResource(R.string.delete_key), onDelete, ButtonKind.Destructive)
        }
    }
}
