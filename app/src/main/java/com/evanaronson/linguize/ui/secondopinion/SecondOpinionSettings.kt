package com.evanaronson.linguize.ui.secondopinion

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.evanaronson.linguize.App
import com.evanaronson.linguize.R
import com.evanaronson.linguize.data.SecondOpinionChoice
import com.evanaronson.linguize.data.SecondOpinionPlan
import com.evanaronson.linguize.data.SecondOpinionPreferences
import com.evanaronson.linguize.data.planSecondOpinion
import com.evanaronson.linguize.llm.CheckFailure
import com.evanaronson.linguize.llm.Provider
import com.evanaronson.linguize.ui.components.ButtonKind
import com.evanaronson.linguize.ui.components.CardNote
import com.evanaronson.linguize.ui.components.ChoiceRow
import com.evanaronson.linguize.ui.components.LinguizeButton
import com.evanaronson.linguize.ui.components.ListCard
import com.evanaronson.linguize.ui.components.ListRow
import com.evanaronson.linguize.ui.components.RowDivider
import com.evanaronson.linguize.ui.components.ScreenColumn
import com.evanaronson.linguize.ui.components.SectionCard
import com.evanaronson.linguize.ui.components.SectionHeader
import com.evanaronson.linguize.ui.text.labelResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the second-opinion settings show. */
data class SecondOpinionSettingsState(
    val choice: SecondOpinionChoice,
    /** Where the choice leads for checks with the current settings. */
    val plan: SecondOpinionPlan,
    /** The service checks use now. */
    val checkProvider: Provider,
    /** The model settings use for each service. */
    val settingsModels: Map<Provider, String>,
    val hasKey: Map<Provider, Boolean>,
    /** Models offered for a picked service; null while loading, empty when they couldn't load. */
    val models: List<String>? = emptyList(),
)

/** Reads and changes the Lab's second-opinion choice; preferences and keys are read off the main thread. */
class SecondOpinionSettingsViewModel(private val app: App) : ViewModel() {
    private val preferences by lazy { SecondOpinionPreferences(app) }

    var state by mutableStateOf<SecondOpinionSettingsState?>(null)
        private set

    private var refreshJob: Job? = null
    private var modelsJob: Job? = null

    /** Reads the choice, settings and keys again (back from another settings page). */
    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            val read = withContext(Dispatchers.IO) { read(preferences.choice) }
            state = read.copy(models = state?.models ?: emptyList())
            loadModels()
        }
    }

    fun select(choice: SecondOpinionChoice) {
        val now = state ?: return
        if (choice == now.choice) return
        val providerChanged = (choice as? SecondOpinionChoice.Picked)?.provider != (now.choice as? SecondOpinionChoice.Picked)?.provider
        state = now.copy(choice = choice, models = if (providerChanged) emptyList() else now.models)
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            val read = withContext(Dispatchers.IO) {
                preferences.choice = choice
                read(choice)
            }
            state = read.copy(models = state?.models ?: emptyList())
            if (providerChanged) loadModels()
        }
    }

    /** Lists the models a picked service's key can use. */
    fun loadModels() {
        val now = state ?: return
        val provider = (now.choice as? SecondOpinionChoice.Picked)?.provider ?: return
        if (now.hasKey[provider] != true) return
        modelsJob?.cancel()
        state = now.copy(models = null)
        modelsJob = viewModelScope.launch {
            val models = try {
                // Making the checker reads its prompt from assets, so not on the main thread.
                withContext(Dispatchers.Default) { app.checker }.models(provider)
            } catch (_: CheckFailure) {
                emptyList()
            }
            val current = state ?: return@launch
            if ((current.choice as? SecondOpinionChoice.Picked)?.provider == provider) state = current.copy(models = models)
        }
    }

    private fun read(choice: SecondOpinionChoice): SecondOpinionSettingsState {
        val settings = app.settings
        val models = Provider.entries.associateWith { settings.model(it) ?: it.recommendedModel }
        val keys = Provider.entries.associateWith { !app.keys.get(it).isNullOrBlank() }
        val provider = settings.provider
        return SecondOpinionSettingsState(
            choice = choice,
            plan = planSecondOpinion(choice, provider, models.getValue(provider), models::getValue) { keys.getValue(it) },
            checkProvider = provider,
            settingsModels = models,
            hasKey = keys,
        )
    }

    companion object {
        fun factory(app: App): ViewModelProvider.Factory = viewModelFactory {
            initializer { SecondOpinionSettingsViewModel(app) }
        }
    }
}

@Composable
private fun secondOpinionSettings(): SecondOpinionSettingsViewModel? {
    // Only the app itself has preferences and keys to read; previews and plain hosts show nothing.
    val app = LocalContext.current.applicationContext as? App ?: return null
    return viewModel(factory = SecondOpinionSettingsViewModel.factory(app))
}

/** The Settings row for second opinions, under the model card; opens [SecondOpinionPage]. */
@Composable
fun SecondOpinionSettingsRow(onOpen: () -> Unit) {
    val settings = secondOpinionSettings() ?: return
    LaunchedEffect(Unit) { settings.refresh() }
    val state = settings.state ?: return
    Spacer(Modifier.height(12.dp))
    ListCard({
        ListRow(stringResource(R.string.lab_second_opinion_title), choiceSummary(state), onClick = onOpen)
    })
}

@Composable
private fun choiceSummary(state: SecondOpinionSettingsState): String = when (val choice = state.choice) {
    SecondOpinionChoice.Off -> stringResource(R.string.lab_second_opinion_off)
    SecondOpinionChoice.Automatic -> stringResource(R.string.lab_second_opinion_automatic)
    is SecondOpinionChoice.Picked -> stringResource(
        R.string.lab_second_opinion_summary_picked,
        stringResource(choice.provider.labelResource),
        choice.model ?: state.settingsModels.getValue(choice.provider),
    )
}

/** The page: which service gives the second opinion, then, for a chosen service, which model. */
@Composable
fun SecondOpinionPage() {
    val settings = secondOpinionSettings() ?: return
    LaunchedEffect(Unit) { settings.refresh() }
    val state = settings.state ?: return
    val picked = state.choice as? SecondOpinionChoice.Picked
    ScreenColumn {
        Spacer(Modifier.height(8.dp))
        SectionCard(Modifier.selectableGroup()) {
            val options = listOf(SecondOpinionChoice.Automatic) + Provider.entries.map { SecondOpinionChoice.Picked(it) } +
                SecondOpinionChoice.Off
            options.forEachIndexed { index, option ->
                if (index > 0) RowDivider()
                val selected = when (option) {
                    is SecondOpinionChoice.Picked -> picked?.provider == option.provider
                    else -> option == state.choice
                }
                val (title, description) = when (option) {
                    SecondOpinionChoice.Automatic -> stringResource(R.string.lab_second_opinion_automatic) to
                        stringResource(R.string.lab_second_opinion_automatic_description)
                    SecondOpinionChoice.Off -> stringResource(R.string.lab_second_opinion_off) to
                        stringResource(R.string.lab_second_opinion_off_description)
                    is SecondOpinionChoice.Picked -> stringResource(option.provider.labelResource) to
                        if (state.hasKey[option.provider] == true) null
                        else stringResource(R.string.lab_second_opinion_needs_key)
                }
                ChoiceRow(title, description, selected, onClick = { settings.select(option) })
            }
        }
        CardNote(planNote(state))

        if (picked != null && state.hasKey[picked.provider] == true) {
            val providerName = stringResource(picked.provider.labelResource)
            SectionHeader(stringResource(R.string.lab_second_opinion_model_for, providerName))
            val fromSettings = state.settingsModels.getValue(picked.provider)
            val models = listOf<String?>(null) + state.models.orEmpty().filter { it != fromSettings }
            SectionCard(Modifier.selectableGroup()) {
                models.forEachIndexed { index, model ->
                    if (index > 0) RowDivider()
                    ChoiceRow(
                        model ?: stringResource(R.string.lab_second_opinion_model_settings, fromSettings),
                        null,
                        selected = picked.model == model,
                        onClick = { settings.select(SecondOpinionChoice.Picked(picked.provider, model)) },
                    )
                }
            }
            when (state.models) {
                null -> CardNote(stringResource(R.string.models_loading_note, providerName))
                else -> LinguizeButton(
                    stringResource(R.string.models_refresh), settings::loadModels, ButtonKind.Text,
                    modifier = Modifier.padding(start = 8.dp, top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun planNote(state: SecondOpinionSettingsState): String = when (val plan = state.plan) {
    is SecondOpinionPlan.Ready -> stringResource(
        R.string.lab_second_opinion_ready_note,
        stringResource(plan.target.provider.labelResource),
        plan.target.model,
    )
    SecondOpinionPlan.Off -> stringResource(R.string.lab_second_opinion_off_note)
    is SecondOpinionPlan.NoKey -> stringResource(R.string.lab_second_opinion_no_key, stringResource(plan.provider.labelResource))
    SecondOpinionPlan.SameAsCheck -> stringResource(R.string.lab_second_opinion_same)
}
