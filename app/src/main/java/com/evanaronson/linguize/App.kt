package com.evanaronson.linguize

import android.app.Application
import android.content.Context
import com.evanaronson.linguize.data.ApiKeys
import com.evanaronson.linguize.data.SelectionMenu
import com.evanaronson.linguize.data.Settings
import com.evanaronson.linguize.history.HistoryEnvironment
import com.evanaronson.linguize.history.HistoryStore
import com.evanaronson.linguize.history.SqliteHistoryStore
import com.evanaronson.linguize.llm.GeminiClient
import com.evanaronson.linguize.llm.OpenAIClient
import com.evanaronson.linguize.llm.Prompt
import com.evanaronson.linguize.llm.Provider
import com.evanaronson.linguize.llm.lab.ReplyPrompt
import com.evanaronson.linguize.data.Exports
import com.evanaronson.linguize.data.lab.PatternNotes
import com.evanaronson.linguize.llm.lab.PatternsPrompt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Creates the app's long-lived objects once, on first use. */
class App : Application() {
    val settings by lazy { Settings(this) }
    val keys by lazy { ApiKeys(this) }
    val menu by lazy { SelectionMenu(this) }

    /** Eligible recorded checks and their outcomes. Opens its database off the main thread. */
    val history: HistoryStore by lazy { SqliteHistoryStore(this) }

    /** One owner serializes exports from Settings and saved checks. */
    val exports by lazy { Exports(cacheDir) }

    /** This build's version name. */
    @Suppress("DEPRECATION") // The PackageInfoFlags overload needs API 33.
    val appVersion: String by lazy { packageManager.getPackageInfo(packageName, 0).versionName.orEmpty() }

    /**
     * What history reads from the app. A check reads it off the main thread when its session
     * opens (the first read loads preferences and may make the device id); [onCreate] also
     * warms it up.
     */
    val historyEnvironment = object : HistoryEnvironment {
        override val enabled get() = settings.historyEnabled
        override val recordingEpoch get() = settings.historyEpoch
        override val deviceId get() = settings.deviceId
        override val appVersion get() = this@App.appVersion
    }

    /**
     * Lives as long as the process. For writes that must outlive a screen, like recording a
     * closing card after its view model's scope is cancelled; nothing else belongs here.
     */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The contract with the model, read from assets on first use (so not on the main thread). */
    val prompt by lazy { Prompt.load { name -> assets.open(name).bufferedReader().use { it.readText() } } }

    /** One connection pool and provider option negotiation for both jobs. */
    private val clients by lazy {
        val http = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
        mapOf(
            Provider.Gemini to GeminiClient(http, prompt),
            Provider.OpenAI to OpenAIClient(http, prompt),
        )
    }

    /** Created off the main thread by the view models. */
    val checker by lazy {
        Checker(settings, keys, prompt, clients, replyPrompt = { replyPrompt }, gapsInCheck = { gapsInCheck },
            toneInCheck = { com.evanaronson.linguize.llm.lab.ToneInCheck.load { name -> assets.open(name).bufferedReader().use { it.readText() } } })
    }

    /** Lab: the reply rules added to a chat check, with the partner's last message as context. */
    val replyPrompt by lazy { ReplyPrompt.load { name -> assets.open(name).bufferedReader().use { it.readText() } } }

    /** Lab: the gap-filling rules added to every check. */
    val gapsInCheck by lazy {
        com.evanaronson.linguize.llm.lab.GapsInCheck.load { name -> assets.open(name).bufferedReader().use { it.readText() } }
    }
    val understander by lazy { Understander(settings, keys, clients) }

    /** Lab: "Teach me" lessons, saved in their own file. */
    val teacher by lazy {
        Teacher(settings, keys, clients, com.evanaronson.linguize.data.teachme.LessonStore(java.io.File(filesDir, com.evanaronson.linguize.data.teachme.LessonStore.FILE_NAME))) {
            com.evanaronson.linguize.llm.teachme.LessonPrompt.load { name -> assets.open(name).bufferedReader().use { it.readText() } }
        }
    }

    /**
     * Lab: everything the writer typed themselves, before corrections (docs/lab/corpus.md).
     * Each feature that holds writer input adds its source here, one per line.
     */
    val corpus by lazy {
        com.evanaronson.linguize.core.corpus.Corpus(listOf<com.evanaronson.linguize.core.corpus.CorpusSource>(
            // Chat messages are History records too (Origin.Chat), so this one source covers them.
            com.evanaronson.linguize.history.corpus.CheckCorpusSource(history),
        ))
    }

    /** Lab: word forms to dictionary forms (lexemes), from the Spanish and Catalan morphology. */
    val lemmatizer: com.evanaronson.linguize.core.corpus.Lemmatizer by lazy {
        com.evanaronson.linguize.core.morphology.MorphologyLemmatizer(morphology)
    }

    /** Lab: Spanish and Catalan morphology; its tables are built on first use, off the main thread. */
    val morphology by lazy { com.evanaronson.linguize.core.morphology.Morphology() }

    /** Lab: what Linguize remembers about the writer (docs/lab/memory.md). */
    val userMemory: com.evanaronson.linguize.core.memory.UserMemory by lazy {
        LabUserMemory(settings, keys, clients, memoryStore) {
            com.evanaronson.linguize.llm.memory.MemoryPrompt.load { name -> assets.open(name).bufferedReader().use { it.readText() } }
        }
    }

    /** Lab: the facts behind [userMemory], in `lab_memory.json`. Nothing is read until first use. */
    val memoryStore by lazy {
        com.evanaronson.linguize.data.memory.MemoryStore(java.io.File(filesDir, com.evanaronson.linguize.data.memory.MemoryStore.FILE_NAME))
    }

    /** Lab Chat: conversations in their own `lab_chat` database, and the partner that answers. */
    val chatStore by lazy { com.evanaronson.linguize.data.chat.ChatStore(this) }
    val chatPartner by lazy {
        ChatPartner(settings, keys, clients, { userMemory }, targets = { chatTargets.block(it) }) {
            com.evanaronson.linguize.llm.chat.ChatPrompt.load { name -> assets.open(name).bufferedReader().use { it.readText() } }
        }
    }

    /** Lab Chat: what the partner steers toward, from the corpus, conjugations and vocabulary gaps. */
    val chatTargets by lazy {
        ChatTargets({ corpus.utterances() }, { morphology }, { lemmatizer },
            com.evanaronson.linguize.data.vocabgaps.BundledDecks { name -> assets.open(name).bufferedReader().use { it.readText() } },
            appScope)
    }

    /** Lab: Your patterns, derived from History and kept in its own `lab_patterns` store. */
    val patterns by lazy {
        PatternsLab(history, PatternNotes(this), settings, keys, clients, {
            PatternsPrompt.load { name -> assets.open(name).bufferedReader().use { it.readText() } }
        })
    }

    /** Lab, Time spent: measured writing and review time, in its own `lab_time_spent` database. */
    val timeStore by lazy { com.evanaronson.linguize.data.time.TimeStore(this, appScope) }

    /** Durable cleanup of removed chats, retried on startup and when Chat is used. */
    val chatDeletions by lazy {
        ChatDeletions(
            chatStore,
            forgetMemory = { userMemory.forget(it) },
            deleteHistory = { history.delete(it) },
            deleteTiming = { timeStore.deleteAcknowledged(it) },
            forgetExports = { withContext(Dispatchers.IO) { exports.clearAcknowledged() } },
        )
    }

    override fun onCreate() {
        super.onCreate()
        // Lab, lang-selector: every language label in the app comes from the language catalog.
        com.evanaronson.linguize.data.language.CatalogLanguageNames.install(this)
        // onCreate runs once per process, so this does too. A session open in the database
        // that this process didn't open was open when an earlier process ended.
        appScope.launch {
            // Made (and committed) once, here, rather than racing on the first checks.
            runCatching { settings.deviceId }
            runCatching { settings.historyEnabled }
            runCatching { appVersion }
            // Moves keys saved under provider names by earlier builds to their tokens.
            runCatching { keys }
            // Housekeeping; the store logs its own failures and never throws.
            history.markAbandoned(System.currentTimeMillis())
        }
        // Lab: a saved patterns explanation is deleted as soon as a check it used is.
        appScope.launch { patterns.watch() }
        appScope.launch { chatDeletions.drain() }
    }

    companion object {
        /** The app, from any of its components. */
        fun of(context: Context): App = context.applicationContext as App
    }
}
