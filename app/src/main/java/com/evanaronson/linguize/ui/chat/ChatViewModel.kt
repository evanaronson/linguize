package com.evanaronson.linguize.ui.chat

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.evanaronson.linguize.App
import com.evanaronson.linguize.ChatPartner
import com.evanaronson.linguize.core.CheckResult
import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.core.Revision
import com.evanaronson.linguize.core.Understood
import com.evanaronson.linguize.core.chat.ChatMessage
import com.evanaronson.linguize.core.chat.ChatStart
import com.evanaronson.linguize.core.chat.Conversation
import com.evanaronson.linguize.core.chat.ConversationSummary
import com.evanaronson.linguize.core.chat.Effect
import com.evanaronson.linguize.core.chat.Outgoing
import com.evanaronson.linguize.core.chat.SendEvent
import com.evanaronson.linguize.core.chat.SendState
import com.evanaronson.linguize.core.chat.deriveTitle
import com.evanaronson.linguize.core.chat.on
import com.evanaronson.linguize.core.memory.FactCategory
import com.evanaronson.linguize.core.memory.MemoryMessage
import com.evanaronson.linguize.core.memory.UserMemory
import com.evanaronson.linguize.data.chat.ChatPreferences
import com.evanaronson.linguize.data.chat.ChatStore
import com.evanaronson.linguize.llm.CheckFailure
import com.evanaronson.linguize.llm.chat.ChatPrompt
import com.evanaronson.linguize.llm.chat.Turn
import com.evanaronson.linguize.ui.card.CardState
import com.evanaronson.linguize.ui.forgetExports
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** The open conversation and its messages, oldest first. */
data class ChatThread(val conversation: Conversation, val messages: List<ChatMessage>) {
    val writerMessages get() = messages.count { it.fromWriter }

    /** The writer spoke last and no answer came: offer a retry. */
    val awaitingReply get() = messages.lastOrNull()?.fromWriter == true
}

/** The pre-send check, run by the screen's card engine (a [com.evanaronson.linguize.ui.card.CheckViewModel]). */
interface DraftCheck {
    fun start(draft: String, language: Language)

    /** Drops the check without saving anything. */
    fun stop()

    /** Accepts every fix on offer except [skipped]; the revision to send, or null when the answer has none. */
    fun keepFixes(skipped: Set<Int>): Revision?

    /** Takes back everything accepted, for Send as written. */
    fun clearAccepted()

    /**
     * [sent] was sent. With [record], the check is saved in History (Origin.Chat) with what was
     * sent as its final text; returns that session's id, or null when nothing is saved.
     */
    fun finish(sent: String, record: Boolean): String?
}

/**
 * Lab Chat: conversations with a partner who only talks, while Linguize checks each message
 * before it goes ([SendState]). Each checked message is saved in History like an applied check
 * (Origin.Chat) when History is on; the conversations themselves live in [store], which notes
 * the History session of each message. Memory learns from a conversation once it goes quiet or
 * is left, and forgets it when it is deleted, with its History sessions.
 */
class ChatViewModel(
    private val store: ChatStore,
    private val partner: ChatPartner,
    private val preferences: ChatPreferences,
    private val memory: () -> UserMemory,
    /** The language a new conversation starts in. */
    private val startLanguage: suspend () -> String,
    /** Deletes saved checks from History. */
    private val deleteSessions: suspend (List<String>) -> Unit,
    /** People memory knows the writer writes to, for "Practise with…". */
    private val people: suspend () -> List<String>,
    /** Translates a partner message into the writer's own language (the Understand path). */
    translate: suspend (String) -> Understood,
    /** Starts building the practice targets for a language before the first reply. */
    private val warmTargets: (Language) -> Unit,
    /** Outlives the screen: learning, forgetting and deleting finish even after it closes. */
    private val appScope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    var conversations by mutableStateOf<List<ConversationSummary>>(emptyList())
        private set
    var thread by mutableStateOf<ChatThread?>(null)
        private set
    var draft by mutableStateOf(TextFieldValue())
        private set
    var send by mutableStateOf<SendState>(SendState.Composing)
        private set

    /** The partner is writing (the opener or a reply). */
    var replying by mutableStateOf(false)
        private set

    /** Why the last reply didn't come; cleared by a retry. */
    var replyFailure by mutableStateOf<CheckFailure?>(null)
        private set
    var needsKey by mutableStateOf(false)
        private set

    /** The id of the message whose changes are shown, if any. */
    var expanded by mutableStateOf<String?>(null)
        private set

    /** Fixes on show that the writer chose to skip, by edit id. */
    var skipped by mutableStateOf<Set<Int>>(emptySet())
        private set

    /** The fix whose reason is open, by edit id. */
    var focusedFix by mutableStateOf<Int?>(null)
        private set

    /** Whether the full card (Meaning, assumptions, rewordings) is open over the review. */
    var details by mutableStateOf(false)
        private set

    /** People to practise with, read when the writer opens "Practise with…". */
    var knownPeople by mutableStateOf<List<String>>(emptyList())
        private set

    private val translations = Translations(translate)
    private var translationVersion by mutableIntStateOf(0)

    /** Set by the screen that hosts the card engine. */
    var draftCheck: DraftCheck? = null

    /** Whether the current check answered with a review (not unclear, another language or a failure). */
    private var reviewed = false
    private var replyJob: Job? = null
    private var started = false

    /**
     * The chat is showing: opens [requested] when History asked for a conversation, else the
     * current one, or a new one, once.
     */
    fun start(requested: String? = null) {
        refreshSettings()
        if (requested != null) {
            started = true
            open(requested)
            return
        }
        if (started) return
        started = true
        viewModelScope.launch {
            val all = store.conversations()
            conversations = visible(all, null)
            learnQuiet(all)
            when (val opening = ChatStart.opening(all.firstOrNull(), clock())) {
                is ChatStart.Opening.Resume -> open(opening.id)
                ChatStart.Opening.New -> newConversation()
            }
        }
    }

    /** Back from Settings or the background: the key may have changed. */
    fun refreshSettings() {
        viewModelScope.launch {
            val key = withContext(Dispatchers.IO) { partner.hasKey() }
            needsKey = !key
            val current = thread
            // A reply that failed for want of a key can go now.
            if (key && current != null && current.awaitingReply && replyFailure?.reason == CheckFailure.Reason.NoKey) retryReply()
        }
    }

    /** A new conversation; with [persona], the partner plays that person ("Practise with…"). */
    fun newConversation(persona: String? = null) {
        leave()
        val who = persona?.trim()?.take(ChatPrompt.MAX_PERSONA)?.takeIf { it.isNotEmpty() }
        viewModelScope.launch {
            val now = clock()
            val language = startLanguage()
            val conversation = Conversation(UUID.randomUUID().toString(), who?.let { deriveTitle(it) }.orEmpty(), language, now, now,
                persona = who)
            if (!store.create(conversation)) return@launch
            // An unanswered opener holds nothing the writer typed: one is enough.
            store.conversations().filter { it.writerMessages == 0 && it.conversation.id != conversation.id }
                .forEach { store.delete(it.conversation.id) }
            show(ChatThread(conversation, emptyList()))
            writeOpener(conversation)
            reloadList()
        }
    }

    fun open(id: String) {
        if (thread?.conversation?.id == id) return
        leave()
        viewModelScope.launch {
            val conversation = store.conversation(id) ?: return@launch newConversation()
            show(ChatThread(conversation, store.messages(id)))
            if (thread?.messages?.isEmpty() == true) writeOpener(conversation)
        }
    }

    private fun show(next: ChatThread) {
        replyJob?.cancel()
        replying = false
        replyFailure = null
        expanded = null
        draft = TextFieldValue()
        draftCheck?.stop()
        resetReview()
        send = SendState.Composing
        thread = next
        preferences.lastLanguage = next.conversation.language
        conversations = visible(conversations, next.conversation.id)
        Language.forCode(next.conversation.language)?.let(warmTargets)
    }

    /** Reads who memory knows the writer writes to, for "Practise with…". */
    fun loadPeople() {
        viewModelScope.launch { knownPeople = runCatching { people() }.getOrDefault(emptyList()) }
    }

    /** The conversation's language; an untouched opener is written again in the new one. */
    fun setLanguage(code: String) {
        val current = thread ?: return
        if (current.conversation.language == code || Language.forCode(code) == null) return
        val conversation = current.conversation.copy(language = code)
        thread = current.copy(conversation = conversation)
        preferences.lastLanguage = code
        Language.forCode(code)?.let(warmTargets)
        viewModelScope.launch {
            store.setLanguage(conversation.id, code)
            if (current.writerMessages == 0) {
                store.replaceMessages(conversation.id, emptyList())
                thread = thread?.takeIf { it.conversation.id == conversation.id }?.copy(messages = emptyList())
                writeOpener(conversation)
            }
            reloadList()
        }
    }

    fun rename(id: String, title: String) {
        val trimmed = title.trim().take(MAX_TITLE)
        viewModelScope.launch {
            store.rename(id, trimmed, custom = trimmed.isNotEmpty())
            thread?.takeIf { it.conversation.id == id }?.let {
                thread = it.copy(conversation = it.conversation.copy(title = trimmed, titleIsCustom = trimmed.isNotEmpty()))
            }
            reloadList()
        }
    }

    /**
     * Deletes conversation [id] for good, after the writer confirmed: its messages, its saved
     * checks in History, and what memory learned from it. There is no Undo: History deletes
     * for good, so the conversation does too.
     */
    fun delete(id: String) {
        val wasOpen = thread?.conversation?.id == id
        if (wasOpen) {
            replyJob?.cancel()
            draftCheck?.stop()
            resetReview()
            send = SendState.Composing
        }
        viewModelScope.launch {
            val sessions = store.sessionIds(id)
            store.delete(id) ?: return@launch
            appScope.launch {
                runCatching { deleteSessions(sessions) }.onFailure { Log.w(TAG, "Couldn't delete a conversation's checks", it) }
                runCatching { memory().forget(source(id)) }
            }
            reloadList()
            if (wasOpen) {
                thread = null
                val next = store.conversations().firstOrNull { it.writerMessages > 0 }
                if (next != null) open(next.conversation.id) else newConversation()
            }
        }
    }

    fun toggleChanges(messageId: String) {
        expanded = if (expanded == messageId) null else messageId
    }

    // Translations of partner messages.

    /** What partner message [id] shows under it; read in composition so it updates. */
    fun translation(id: String): Translation? {
        translationVersion
        return translations.shown(id)
    }

    fun translationOpen(id: String): Boolean {
        translationVersion
        return translations.isOpen(id)
    }

    /** Translate (or Hide) on a partner message; a failed one is fetched again. */
    fun toggleTranslation(message: ChatMessage) {
        if (message.fromWriter) return
        val fetch = translations.toggle(message.id)
        translationVersion++
        if (!fetch) return
        viewModelScope.launch {
            translations.load(message.id, message.text)
            translationVersion++
        }
    }

    // Composing and sending.

    fun onDraftChange(value: TextFieldValue) {
        draft = value
    }

    /** Send tapped: the draft is checked first. */
    fun send() = handle(SendEvent.Send(draft.text))

    /** What the check of the draft did; the screen reports the card's state. */
    fun onCardState(state: CardState?) {
        if (send !is SendState.Checking) return
        when (state) {
            is CardState.Done -> {
                reviewed = state.result is CheckResult.Reviewed
                handle(SendEvent.Checked(state.result))
            }
            is CardState.Failed -> {
                reviewed = false
                handle(SendEvent.CheckFailed)
            }
            else -> Unit
        }
    }

    /** Tapping a fix (in the text or the list) opens its reason; tapping it again closes it. */
    fun focusFix(id: Int?) {
        focusedFix = if (focusedFix == id) null else id
    }

    /** Keeps or skips fix [id] for Send with fixes. */
    fun toggleSkip(id: Int) {
        skipped = if (id in skipped) skipped - id else skipped + id
    }

    fun showDetails(show: Boolean) {
        details = show
    }

    /** Send with the fixes the writer kept (and anything accepted on the full card). */
    fun sendWithFixes() {
        val revision = draftCheck?.keepFixes(skipped)
        if (revision == null) sendAsWritten() else handle(SendEvent.SendWithFixes(revision))
    }

    fun sendAsWritten() {
        draftCheck?.clearAccepted()
        handle(SendEvent.SendAsWritten)
    }

    /** Closes the review and goes back to the draft. */
    fun keepEditing() = handle(SendEvent.Edit)

    private fun handle(event: SendEvent) {
        val transition = send.on(event)
        send = transition.state
        when (val effect = transition.effect) {
            is Effect.StartCheck -> {
                resetReview()
                val language = Language.forCode(thread?.conversation?.language) ?: Language.Spanish
                draftCheck?.start(effect.draft, language) ?: handle(SendEvent.CheckFailed)
            }
            Effect.CancelCheck -> {
                draftCheck?.stop()
                resetReview()
            }
            is Effect.Deliver -> deliver(effect.outgoing, effect.record && reviewed)
            null -> Unit
        }
    }

    private fun resetReview() {
        skipped = emptySet()
        focusedFix = null
        details = false
        reviewed = false
    }

    private fun deliver(outgoing: Outgoing, record: Boolean) {
        val current = thread ?: return
        val sessionId = draftCheck?.finish(outgoing.text, record)
        resetReview()
        val message = outgoing.message(UUID.randomUUID().toString(), current.conversation.id, clock(), sessionId)
        draft = TextFieldValue()
        val withMessage = current.copy(
            conversation = current.conversation.copy(updatedAt = message.at),
            messages = current.messages + message,
        )
        thread = withMessage
        viewModelScope.launch {
            store.add(message)
            reloadList()
        }
        requestReply(withMessage)
    }

    fun retryReply() {
        val current = thread ?: return
        if (current.awaitingReply && !replying) requestReply(current)
    }

    private fun requestReply(current: ChatThread) {
        replyJob?.cancel()
        replying = true
        replyFailure = null
        val conversation = current.conversation
        val language = Language.forCode(conversation.language) ?: Language.Spanish
        val history = current.messages.map { Turn(it.fromWriter, it.text) }
        val needTitle = conversation.title.isEmpty() && !conversation.titleIsCustom
        replyJob = viewModelScope.launch {
            try {
                val answer = partner.reply(language, history, needTitle, conversation.persona)
                val reply = ChatMessage.partner(UUID.randomUUID().toString(), conversation.id, answer.reply, clock())
                store.add(reply)
                val title = if (needTitle) {
                    (answer.title ?: deriveTitle(current.messages.firstOrNull { it.fromWriter }?.text.orEmpty()))
                        .take(MAX_TITLE).also { if (it.isNotEmpty()) store.rename(conversation.id, it, custom = false) }
                } else null
                val now = thread
                if (now != null && now.conversation.id == conversation.id) {
                    thread = now.copy(
                        conversation = now.conversation.copy(
                            updatedAt = reply.at,
                            title = title?.takeIf { it.isNotEmpty() && !now.conversation.titleIsCustom } ?: now.conversation.title,
                        ),
                        messages = now.messages + reply,
                    )
                }
                reloadList()
            } catch (e: CancellationException) {
                throw e
            } catch (e: CheckFailure) {
                replyFailure = e
                if (e.reason == CheckFailure.Reason.NoKey) needsKey = true
            } finally {
                if (thread?.conversation?.id == conversation.id) replying = false
            }
        }
    }

    private suspend fun writeOpener(conversation: Conversation) {
        val language = Language.forCode(conversation.language) ?: Language.Spanish
        replying = true
        val text = try {
            partner.open(language, conversation.persona)
        } catch (e: CancellationException) {
            throw e
        } catch (e: CheckFailure) {
            if (e.reason == CheckFailure.Reason.NoKey) needsKey = true
            fallbackOpener(language)
        } finally {
            replying = false
        }
        val now = thread ?: return
        // Another conversation, an opener already there, or a language changed while this one was written.
        if (now.conversation.id != conversation.id || now.messages.isNotEmpty() || now.conversation.language != conversation.language) return
        val opener = ChatMessage.partner(UUID.randomUUID().toString(), conversation.id, text, clock())
        store.add(opener)
        thread = now.copy(messages = listOf(opener))
    }

    // Memory and the list.

    /** The writer left the conversation (switched, closed the chat, or the app went away). */
    fun leave() {
        val current = thread ?: return
        learn(current.conversation, current.messages, left = true)
    }

    private fun learnQuiet(all: List<ConversationSummary>) {
        appScope.launch {
            for (summary in all) {
                val conversation = summary.conversation
                if (summary.writerMessages == 0) continue
                if (clock() - conversation.updatedAt < ChatStart.QUIET_MILLIS) continue
                learnNow(conversation, store.messages(conversation.id), left = false)
            }
        }
    }

    private fun learn(conversation: Conversation, messages: List<ChatMessage>, left: Boolean) {
        appScope.launch { learnNow(conversation, messages, left) }
    }

    private suspend fun learnNow(conversation: Conversation, messages: List<ChatMessage>, left: Boolean) {
        val stored = store.conversation(conversation.id) ?: return
        if (!ChatStart.shouldLearn(stored, messages.size, messages.count { it.fromWriter }, clock(), left)) return
        try {
            memory().learnFrom(source(conversation.id), messages.map { MemoryMessage(it.fromWriter, it.text) })
            store.setLearned(conversation.id, messages.size)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Memory couldn't learn from a conversation", e)
        }
    }

    private suspend fun reloadList() {
        conversations = visible(store.conversations(), thread?.conversation?.id)
    }

    /** Conversations worth listing: any the writer spoke in, and the open one. */
    private fun visible(all: List<ConversationSummary>, openId: String?) =
        all.filter { it.writerMessages > 0 || it.conversation.id == openId }

    override fun onCleared() {
        leave()
    }

    companion object {
        private const val TAG = "LabChat"
        const val MAX_TITLE = 60

        /** What memory calls a conversation. */
        fun source(id: String) = "chat:$id"

        /** A natural opener when the partner can't write one (no key, offline). */
        fun fallbackOpener(language: Language): String = when (language.code) {
            Language.Catalan.code -> "Hola! Què tal el dia?"
            else -> "¡Hola! ¿Qué tal tu día?"
        }

        fun factory(app: App): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val preferences = ChatPreferences(app)
                ChatViewModel(
                    store = app.chatStore,
                    partner = app.chatPartner,
                    preferences = preferences,
                    memory = { app.userMemory },
                    startLanguage = {
                        withContext(Dispatchers.IO) {
                            val history = runCatching { app.history.recent(HISTORY_SAMPLE).first() }.getOrDefault(emptyList())
                            ChatStart.language(
                                lastUsed = preferences.lastLanguage,
                                menuLanguages = runCatching { app.menu.enabled() }.getOrDefault(emptySet()).mapNotNull { it.language?.code },
                                historyMostUsedFirst = ChatStart.mostUsed(history.map { it.targetLanguage }),
                                supported = Language.all.map { it.code },
                            )
                        }
                    },
                    deleteSessions = { ids ->
                        ids.forEach { app.history.delete(it) }
                        if (ids.isNotEmpty()) app.forgetExports()
                    },
                    people = {
                        withContext(Dispatchers.IO) {
                            val state = app.memoryStore.load()
                            if (!state.enabled) emptyList()
                            else state.facts.filter { it.category == FactCategory.People }.map { it.text }
                        }
                    },
                    translate = { text -> app.understander.understand(text) },
                    warmTargets = { app.chatTargets.warm(it) },
                    appScope = app.appScope,
                )
            }
        }

        private const val HISTORY_SAMPLE = 100
    }
}
