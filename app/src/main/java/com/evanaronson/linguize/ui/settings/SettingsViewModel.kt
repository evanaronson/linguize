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
import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.core.WritingProfile
import com.evanaronson.linguize.core.WritingRegister
import com.evanaronson.linguize.data.KeyStatus
import com.evanaronson.linguize.data.PopupPosition
import com.evanaronson.linguize.data.MenuEntry
import com.evanaronson.linguize.llm.CheckFailure
import com.evanaronson.linguize.llm.Provider
import com.evanaronson.linguize.data.Exports
import kotlinx.coroutines.CoroutineDispatcher
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
import java.io.File
import java.time.LocalDate

data class SettingsState(
    /** The entries shown in the text-selection menu; never empty. */
    val menu: Set<MenuEntry> = setOf(MenuEntry.Auto),
    val popupPosition: PopupPosition = PopupPosition.Bottom,
    val punctuation: Punctuation = Punctuation.Moderate,
    val judgments: Judgments = Judgments.Both,
    val writingProfiles: List<WritingProfile> = Language.all.map { WritingProfile.defaultFor(it) },
    val writingLanguage: String = Language.Catalan.code,
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
    val writingProfile: WritingProfile get() = writingProfiles.first { it.language == writingLanguage }
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
    data class Ready(val uri: Uri, internal val file: File) : Export

    data object Failed : Export
}

/** The result of trying the chosen model with a short check. */
sealed interface ModelTest {
    data object Testing : ModelTest
    data class Works(val millis: Long) : ModelTest
    /** [reason] is localized by the UI; [detail] is the AI service's own words, when it gave any. */
    data class Failed(
        val reason: CheckFailure.Reason,
        val detail: String?,
        val userMessage: CheckFailure.Message? = null,
    ) : ModelTest
}

class SettingsViewModel(
    private val app: App,
    private val settingsIo: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private var refreshJob: Job? = null
    private var modelsJob: Job? = null
    private var testJob: Job? = null
    private var modelsRequested = false
    private val exports = app.exports

    /** Selection-menu writes, one at a time (see [toggleMenuEntry]). */
    private val menuWrites = Mutex()
    private var menuRequest = 0L
    private var completedMenuRequest = 0L
    private val keyWrites = Mutex()

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
    private var exportRequest = 0L

    init {
        // Exports left from earlier visits, once whatever they were shared with has had its time.
        app.appScope.launch(Dispatchers.IO) { exports.prune() }
        refresh()
    }

    /** Another host may have changed settings while this popup or screen was hidden. */
    fun refresh() = refresh {}

    private fun refresh(afterRead: () -> Unit) {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            menuWrites.withLock {
                val fresh = withContext(settingsIo) {
                    val settings = app.settings
                    val provider = settings.provider
                    SettingsState(
                        menu = app.menu.enabled(),
                        punctuation = settings.punctuation,
                        writingProfiles = settings.writingProfiles,
                        judgments = settings.judgments,
                        provider = provider,
                        keys = Provider.entries.associateWith { app.keys.status(it) },
                        model = settings.model(provider),
                        historyEnabled = settings.historyEnabled,
                        popupPosition = settings.popupPosition,
                    )
                }
                val previous = state
                val selectedLanguage = previous?.writingLanguage ?: Language.Catalan.code
                val refreshed = fresh.copy(
                    writingLanguage = selectedLanguage,
                    // An action accepted during this read already has an ordered writer waiting.
                    menu = if (menuRequest != completedMenuRequest) previous?.menu ?: fresh.menu else fresh.menu,
                )
                val sameService = previous != null && previous.provider == fresh.provider &&
                    previous.model == fresh.model && previous.keys == fresh.keys
                val reloadModels = modelsRequested && !sameService
                state = if (sameService) refreshed.copy(models = previous!!.models,
                    modelTest = previous.modelTest, keySaveFailed = previous.keySaveFailed) else refreshed
                refreshJob = null
                if (!sameService) {
                    modelsRequested = false
                    modelsJob?.cancel()
                    testJob?.cancel()
                    if (reloadModels) loadModels()
                }
                afterRead()
            }
        }
    }

    /**
     * The settings screen is showing: refreshes the provider's model list on each visit. Not
     * before, so that Home, which only reads a setting or two, doesn't send the key to the
     * provider on every launch.
     */
    fun showModels() {
        if (state?.models != ModelList.Loading) loadModels()
    }

    /** Adds or removes a selection-menu entry; the last one can't be removed. */
    fun toggleMenuEntry(entry: MenuEntry) {
        val menu = state?.menu ?: return
        val on = entry !in menu
        if (!on && menu.size == 1) return
        change { it.copy(menu = if (on) it.menu + entry else it.menu - entry) }
        val request = ++menuRequest
        viewModelScope.launch {
            // Queue on main before dispatching IO, preserving the caller's entry/on intent.
            menuWrites.withLock {
                val actual = withContext(settingsIo) { app.menu.setEnabled(entry, on) }
                completedMenuRequest = request
                // Keep later optimistic actions visible until their own writes finish.
                if (request == menuRequest) updateState { it.copy(menu = actual) }
            }
        }
    }

    fun setPopupPosition(position: PopupPosition) = change { state ->
        app.settings.popupPosition = position
        state.copy(popupPosition = position)
    }

    fun selectWritingLanguage(language: Language) = change { it.copy(writingLanguage = language.code) }

    fun setWritingRegister(register: WritingRegister) = updateWritingProfile { it.copy(register = register) }

    fun setWritingPunctuation(punctuation: Punctuation) = updateWritingProfile { it.copy(punctuation = punctuation) }

    private fun updateWritingProfile(update: (WritingProfile) -> WritingProfile) = change { state ->
        // Read the latest complete profile so another host's unrelated fields are preserved.
        val profile = app.settings.writingProfiles.first { it.language == state.writingLanguage }
        app.settings.updateWritingProfile(update(profile))
        state.copy(writingProfiles = app.settings.writingProfiles)
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
        updateState { it.copy(modelTest = ModelTest.Testing) }
        testJob = viewModelScope.launch {
            val result = try {
                ModelTest.Works(checker().testModel(provider, model))
            } catch (failure: CheckFailure) {
                ModelTest.Failed(failure.reason, failure.detail, failure.userMessage)
            }
            updateState {
                if (it.provider == provider && (it.model ?: provider.recommendedModel) == model) it.copy(modelTest = result) else it
            }
        }
    }

    /**
     * Saves the key for the current provider; a blank value removes it. A key that was saved is
     * tested straight away. If the Keystore couldn't encrypt it, [SettingsState.keySaveFailed] is set.
     */
    fun saveKey(value: String) {
        val provider = state?.provider ?: return
        viewModelScope.launch {
            // Enter the mutex on the main thread, before dispatching the write: rapid Save /
            // Remove actions must persist in the order the writer asked for them.
            keyWrites.withLock {
                val (saved, status) = withContext(Dispatchers.IO) {
                    app.keys.set(provider, value) to app.keys.status(provider)
                }
                val stillSelected = state?.provider == provider
                // An older provider's write must not cancel the current provider's test.
                if (stillSelected) testJob?.cancel()
                updateState {
                    val updated = it.copy(keys = it.keys + (provider to status))
                    if (it.provider == provider) updated.copy(modelTest = null, keySaveFailed = !saved) else updated
                }
                // Reconcile other hosts' changes and the completed key write in one fresh
                // snapshot. A late key result must not cancel refresh and retain old settings.
                refresh {
                    if (state?.provider == provider) {
                        loadModels()
                        if (saved && status is KeyStatus.Saved) testModel()
                    }
                }
            }
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
        exportRequest++
        exported = null
        app.appScope.launch(Dispatchers.IO) {
            app.history.clear()
            exports.clear()
        }
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
        val request = ++exportRequest
        viewModelScope.launch {
            var created: File? = null
            var offered = false
            try {
                val ready = withContext(Dispatchers.IO) {
                    try {
                        val file = exports.write(LocalDate.now()) { app.history.export(it) }
                        created = file
                        file?.let {
                            Export.Ready(FileProvider.getUriForFile(app, "${app.packageName}.files", it), it)
                        }
                    } catch (_: IOException) {
                        null
                    } catch (_: IllegalArgumentException) {
                        // FileProvider: the folder isn't one res/xml/file_paths.xml shares.
                        null
                    }
                }
                // Clear may have been pressed while the file was still being written.
                if (request != exportRequest) return@launch
                exportedAt = SystemClock.elapsedRealtime()
                exported = ready ?: Export.Failed
                offered = ready != null
                if (ready != null) {
                    // Long enough for the receiving app to read it. A later export has a different
                    // name, so this cleanup can never remove another host's newer result.
                    app.appScope.launch {
                        delay(Exports.KEEP_MILLIS + PRUNE_SLACK_MILLIS)
                        withContext(Dispatchers.IO) { exports.discard(ready.file) }
                    }
                }
            } finally {
                exporting = false
                // Handles cancellation too, including after the file was written but before
                // withContext could return it to a screen that has since gone away.
                if (!offered) created?.let { file ->
                    app.appScope.launch(Dispatchers.IO) { exports.discard(file) }
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
        if (SystemClock.elapsedRealtime() - exportedAt <= OFFER_WITHIN_MILLIS) {
            // Another host may have replaced it, or Clear may have retired it meanwhile.
            return if (export is Export.Ready && !exports.isCurrent(export.file)) null else export
        }
        if (export is Export.Ready) app.appScope.launch(Dispatchers.IO) { exports.discard(export.file) }
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
            updateState { it.copy(models = ModelList.Loaded(emptyList())) }
            return
        }
        updateState { it.copy(models = ModelList.Loading) }
        modelsJob = viewModelScope.launch {
            val models = try {
                ModelList.Loaded(checker().models(provider))
            } catch (_: CheckFailure) {
                ModelList.Failed
            }
            updateState { if (it.provider == provider) it.copy(models = models) else it }
        }
    }

    /** Applies [update] once the settings have loaded; returns the new state. Preference writes are asynchronous. */
    private fun change(update: (SettingsState) -> SettingsState): SettingsState? {
        refreshJob?.cancel()
        return updateState(update)
    }

    /** Async presentation results must not cancel a read of changes made by another host. */
    private fun updateState(update: (SettingsState) -> SettingsState): SettingsState? {
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
