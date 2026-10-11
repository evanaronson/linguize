package com.evanaronson.linguize.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.data.KeyStatus
import com.evanaronson.linguize.llm.Provider
import com.evanaronson.linguize.ui.components.Dropdown
import com.evanaronson.linguize.ui.components.RadioRow
import com.evanaronson.linguize.ui.components.SectionTitle

@Composable
internal fun ModelSection(state: SettingsState, settings: SettingsViewModel) {
    SectionTitle("AI service")
    if (!state.hasKey) {
        Text(
            "Linguize sends the text you check to Gemini or OpenAI. You need your own API key from one of them.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Column(Modifier.selectableGroup()) {
        Provider.entries.forEach { option ->
            RadioRow(option.label, selected = option == state.provider, onClick = { settings.selectProvider(option) }) {
                KeyBadge(state.keys[option])
            }
        }
    }

    val provider = state.provider
    val recommended = "${provider.recommendedModel} (recommended)"
    val available = (state.models as? ModelList.Loaded)?.models.orEmpty().filter { it != provider.recommendedModel }
    Dropdown(
        label = when (state.models) {
            ModelList.Loading -> "Model · loading…"
            ModelList.Failed -> "Model · couldn't load others"
            is ModelList.Loaded -> if (state.hasKey) "Model" else "Model · add a key to see more"
        },
        selected = state.model ?: recommended,
        options = listOf<String?>(null) + available,
        optionLabel = { it ?: recommended },
        onSelect = settings::selectModel,
    )
    if (state.hasKey) ModelTestRow(state.modelTest, onTest = settings::testModel)

    when (val status = state.keys[provider]) {
        is KeyStatus.Saved -> SavedKey(provider, status.lastFour, onRemove = settings::removeKey)
        else -> KeyEntry(provider, unreadable = status == KeyStatus.Unreadable, saveFailed = state.keySaveFailed, onSave = settings::saveKey)
    }
}

@Composable
private fun KeyBadge(status: KeyStatus?) {
    when (status) {
        is KeyStatus.Saved -> Text(
            "Key saved",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        KeyStatus.Unreadable -> Text(
            "Key lost",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.error,
        )
        KeyStatus.None, null -> Unit
    }
}

@Composable
private fun ModelTestRow(test: ModelTest?, onTest: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                when (test) {
                    null -> "Not tested yet"
                    ModelTest.Testing -> "Testing…"
                    is ModelTest.Works -> "Works · %.1f s".format(test.millis / 1000.0)
                    is ModelTest.Failed -> "Didn't work: ${test.title}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (test is ModelTest.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            // The AI service's own words, when it gave any, under the title.
            if (test is ModelTest.Failed && test.detail != null) {
                Text(
                    test.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        TextButton(enabled = test != ModelTest.Testing, onClick = onTest) { Text("Test") }
    }
}

@Composable
private fun KeyEntry(provider: Provider, unreadable: Boolean, saveFailed: Boolean, onSave: (String) -> Unit) {
    val context = LocalContext.current
    // Not saveable: the key shouldn't end up in saved instance state.
    var key by remember(provider) { mutableStateOf("") }
    OutlinedTextField(
        value = key,
        onValueChange = { key = it },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text("${provider.label} API key") },
        placeholder = { Text("Paste key") },
        isError = unreadable || saveFailed,
        supportingText = {
            Text(
                when {
                    saveFailed -> "This phone couldn't save the key. Try again."
                    unreadable -> "This phone can no longer read the saved key. Paste it again."
                    else -> "Saved only on this phone, and sent only to ${provider.label}."
                },
            )
        },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            enabled = key.isNotBlank(),
            // Kept in the field until saved, so a failed save can be retried. A saved key replaces this field.
            onClick = { onSave(key) },
        ) { Text("Save") }
        TextButton(onClick = { context.openFromSettings(Intent(Intent.ACTION_VIEW, Uri.parse(provider.keyUrl))) }) {
            Text("Get a key")
        }
    }
}

/** Shown instead of the key field once a key is saved. */
@Composable
private fun SavedKey(provider: Provider, lastFour: String, onRemove: () -> Unit) {
    val onContainer = MaterialTheme.colorScheme.onPrimaryContainer
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // "key saved" says it; screen readers skip the tick.
            Text("✓", style = MaterialTheme.typography.titleMedium, color = onContainer, modifier = Modifier.clearAndSetSemantics {})
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("${provider.label} key saved", style = MaterialTheme.typography.titleSmall, color = onContainer)
                Text("Ends in $lastFour", style = MaterialTheme.typography.bodySmall, color = onContainer)
            }
            TextButton(onClick = onRemove) { Text("Delete key") }
        }
    }
}
