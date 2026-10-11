package com.evanaronson.linguize.ui.settings

import android.net.Uri
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.evanaronson.linguize.App
import com.evanaronson.linguize.core.Judgments
import com.evanaronson.linguize.core.Punctuation
import com.evanaronson.linguize.data.KeyStatus
import com.evanaronson.linguize.data.MenuEntry
import com.evanaronson.linguize.llm.CheckFailure
import com.evanaronson.linguize.llm.Provider
import com.evanaronson.linguize.ui.Exports
import com.evanaronson.linguize.ui.card.title
import com.evanaronson.linguize.ui.forgetExports
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.LocalDate

data class SettingsState(
    /** The entries shown in the text-selection menu; never empty. */
    val menu: Set<MenuEntry> = setOf(MenuEntry.Auto),
    val punctuation: Punctuation = Punctuation.Moderate,
    val judgments: Judgments = Judgments.Both,
    val provider: Provider = Provider.Gemini,
    val keys: Map<Provider, KeyStatus> = emptyMap(),
    /** The chosen model, or null for the provider's recommended one. */
    val model: String? = null,
    val models: ModelList = ModelList.Loaded(emptyList()),
    val modelTest: ModelTest? = null,
    /** Whether checks are kept in history. */
    val historyEnabled: Boolean = true,
    /** The last attempt to save a key failed: the Keystore couldn't encrypt it. */
    val keySaveFailed: Boolean = false,
) {
    val hasKey get() = keys[provider] is KeyStatus.Saved
}

/** The provider's models: loading, loaded, or failed to load. */
sealed interface ModelList {
    data object Loading : ModelList
    data class Loaded(val models: List<String>) : ModelList
    data object Failed : ModelList
}

/** How much history is kept: [count] checks, the oldest from [since]. */
data class KeptHistory(val count: Int, val since: Long?)

/** A finished export, waiting for the screen to offer it. */
sealed interface Export {
    /** Written; [uri] is a shareable link to the file. */
    data class Ready(val uri: Uri) : Export

    data object Failed : Export
}

/** The result of trying the chosen model with a short check. */
sealed interface ModelTest {
    data object Testing : ModelTest
    data class Works(val millis: Long) : ModelTest
    /** [title] says what went wrong; [detail] is the AI service's own words, when it gave any. */
    data class Failed(val title: String, val detail: String?) : ModelTest
}

class SettingsViewModel(private val app: App) : ViewModel() {
    private var modelsJob: Job? = null
    private var testJob: Job? = null
    private var modelsRequested = false
    private val exports = Exports(app.cacheDir)

    /** Selection-menu writes, one at a time (see [toggleMenuEntry]). */
    private val menuWrites = Mutex()

    /** Null until the saved settings have been read. */
    var state by mutableStateOf<SettingsState?>(null)
        private set

    /** Null until first counted. */
    val history: StateFlow<KeptHistory?> = combine(app.history.count(), app.history.since()) { count, since -> KeptHistory(count, since) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** An export is being written. */
    var exporting by mutableStateOf(false)
        private set

    /**
     * The export just finished, until the screen showing settings takes it with [takeExport].
     * State rather than a callback, so the share sheet opens from the activity on screen even
     * when the one that asked was recreated meanwhile.
     */
    var exported by mutableStateOf<Export?>(null)
        private set

    /** When [exported] finished, on the uptime clock. */
    private var exportedAt = 0L

    init {
        // Exports left from earlier visits, once whatever they were shared with has had its time.
        app.appScope.launch(Dispatchers.IO) { exports.prune() }
        viewModelScope.launch {
            state = withContext(Dispatchers.IO) {
                val settings = app.settings
                SettingsState(
                    menu = app.menu.enabled().ifEmpty { setOf(MenuEntry.Auto) },
                    punctuation = settings.punctuation,
                    judgments = settings.judgments,
                    provider = settings.provider,
                    keys = Provider.entries.associateWith { app.keys.status(it) },
                    model = settings.model(settings.provider),
                    historyEnabled = settings.historyEnabled,
                )
            }
        }
    }

    /**
     * The settings screen is showing: reads the provider's model list the first time. Not
     * before, so that Home, which only reads a setting or two, doesn't send the key to the
     * provider on every launch.
     */
    fun showModels() {
        if (!modelsRequested) loadModels()
    }

    /** Adds or removes a selection-menu entry; the last one can't be removed. */
    fun toggleMenuEntry(entry: MenuEntry) {
        val menu = state?.menu ?: return
        val on = entry !in menu
        if (!on && menu.size == 1) return
        change { it.copy(menu = if (on) it.menu + entry else it.menu - entry) }
        // A call to the package manager, so off the main thread. Quick toggles start writes that
        // could land in any order, so they run one at a time and each writes the switch's state
        // as it is then: the last one to run leaves the menu as the screen shows it.
        viewModelScope.launch(Dispatchers.IO) {
            menuWrites.withLock {
                val menu = state?.menu ?: return@withLock
                app.menu.setEnabled(entry, entry in menu)
            }
        }
    }

    fun setPunctuation(punctuation: Punctuation) = change { state ->
        app.settings.punctuation = punctuation
        state.copy(punctuation = punctuation)
    }

    fun setJudgments(judgments: Judgments) = change { state ->
        app.settings.judgments = judgments
        state.copy(judgments = judgments)
    }

    fun selectProvider(provider: Provider) {
        testJob?.cancel()
        change { state ->
            app.settings.provider = provider
            state.copy(provider = provider, model = app.settings.model(provider), modelTest = null, keySaveFailed = false)
        }
        loadModels()
    }

    /** Null selects the recommended model. Picking a model tests it straight away. */
    fun selectModel(model: String?) {
        val current = change { state ->
            app.settings.setModel(state.provider, model)
            state.copy(model = model)
        }
        if (current?.hasKey == true) testModel()
    }

    fun testModel() {
        val current = state ?: return
        val provider = current.provider
        val model = current.model ?: provider.recommendedModel
        testJob?.cancel()
        change { it.copy(modelTest = ModelTest.Testing) }
        testJob = viewModelScope.launch {
            val result = try {
                ModelTest.Works(checker().testModel(provider, model))
            } catch (failure: CheckFailure) {
                ModelTest.Failed(failure.reason.title, failure.detail)
            }
            change { if (it.provider == provider) it.copy(modelTest = result) else it }
        }
    }

    /**
     * Saves the key for the current provider; a blank value removes it. A key that was saved is
     * tested straight away. If the Keystore couldn't encrypt it, [SettingsState.keySaveFailed] is set.
     */
    fun saveKey(value: String) {
        val provider = state?.provider ?: return
        viewModelScope.launch {
            val (saved, status) = withContext(Dispatchers.IO) {
                app.keys.set(provider, value) to app.keys.status(provider)
            }
            // A test still running used the key just replaced; its answer says nothing about this one.
            testJob?.cancel()
            change { it.copy(keys = it.keys + (provider to status), modelTest = null, keySaveFailed = !saved) }
            if (state?.provider != provider) return@launch
            loadModels()
            if (saved && status is KeyStatus.Saved) testModel()
        }
    }

    fun removeKey() = saveKey("")

    /** Off stops recording; it doesn't delete what's kept. */
    fun setHistoryEnabled(enabled: Boolean) = change { state ->
        app.settings.historyEnabled = enabled
        state.copy(historyEnabled = enabled)
    }

    /**
     * Deletes all history, and any export of it. On the app's scope, so leaving settings
     * doesn't stop it halfway.
     */
    fun clearHistory() {
        app.appScope.launch { app.history.clear() }
        app.forgetExports()
    }

    /**
     * Writes all history to a JSON Lines file in the cache, replacing earlier exports, and
     * sets [exported] to a shareable link to it, or to [Export.Failed]. The file is removed
     * again a few minutes later (see [Exports]).
     */
    fun exportHistory() {
        if (exporting) return
        exporting = true
        exported = null
        viewModelScope.launch {
            val uri = withContext(Dispatchers.IO) {
                val shared = try {
                    val file = exports.create(LocalDate.now())
                    val written = file.outputStream().buffered().use { app.history.export(it) }
                    // A partial file isn't worth sharing.
                    if (written) FileProvider.getUriForFile(app, "${app.packageName}.files", file) else null
                } catch (_: IOException) {
                    null
                } catch (_: IllegalArgumentException) {
                    // FileProvider: the folder isn't one res/xml/file_paths.xml shares.
                    null
                }
                // Nothing of a failed export is left behind.
                if (shared == null) exports.clear()
                shared
            }
            exporting = false
            exportedAt = SystemClock.elapsedRealtime()
            exported = uri?.let { Export.Ready(it) } ?: Export.Failed
            if (uri != null) {
                // Long enough for the app it's shared with to read it; if the process ends first,
                // the next visit to settings removes it.
                app.appScope.launch {
                    delay(Exports.KEEP_MILLIS + PRUNE_SLACK_MILLIS)
                    withContext(Dispatchers.IO) { exports.prune() }
                }
            }
        }
    }

    /**
     * Takes [exported] for the screen to offer, which it does only while settings is showing.
     * Null when there's nothing to offer, or when the export finished a while ago: settings was
     * left as it was being written, and a share sheet opening by itself later would be a
     * surprise. Such an export is dropped, and its file removed.
     */
    fun takeExport(): Export? {
        val export = exported ?: return null
        exported = null
        if (SystemClock.elapsedRealtime() - exportedAt <= OFFER_WITHIN_MILLIS) return export
        if (export is Export.Ready) app.appScope.launch(Dispatchers.IO) { exports.clear() }
        return null
    }

    /** The app's checker; making it reads the prompt from assets, so not on the main thread. */
    private suspend fun checker() = withContext(Dispatchers.Default) { app.checker }

    private fun loadModels() {
        val current = state ?: return
        modelsRequested = true
        val provider = current.provider
        modelsJob?.cancel()
        if (!current.hasKey) {
            change { it.copy(models = ModelList.Loaded(emptyList())) }
            return
        }
        change { it.copy(models = ModelList.Loading) }
        modelsJob = viewModelScope.launch {
            val models = try {
                ModelList.Loaded(checker().models(provider))
            } catch (_: CheckFailure) {
                ModelList.Failed
            }
            change { if (it.provider == provider) it.copy(models = models) else it }
        }
    }

    /** Applies [update] once the settings have loaded; returns the new state. Preference writes are asynchronous. */
    private fun change(update: (SettingsState) -> SettingsState): SettingsState? {
        val current = state ?: return null
        return update(current).also { state = it }
    }

    companion object {
        fun factory(app: App): ViewModelProvider.Factory = viewModelFactory { initializer { SettingsViewModel(app) } }

        /** How long a finished export may wait for settings to show again and offer it: a rotation, not a later visit. */
        private const val OFFER_WITHIN_MILLIS = 10_000L

        /** So the file is past [Exports.KEEP_MILLIS] when the delayed prune looks at it. */
        private const val PRUNE_SLACK_MILLIS = 1_000L
    }
}
