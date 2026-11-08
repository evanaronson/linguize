package com.evanaronson.linguize.ui.chat

import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.evanaronson.linguize.App
import com.evanaronson.linguize.ChatPartner
import com.evanaronson.linguize.Checker
import com.evanaronson.linguize.core.CheckResult
import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.core.Revision
import com.evanaronson.linguize.core.chat.ChatMessage
import com.evanaronson.linguize.core.chat.ChatStart
import com.evanaronson.linguize.core.chat.Conversation
import com.evanaronson.linguize.core.chat.ConversationSummary
import com.evanaronson.linguize.core.chat.Effect
import com.evanaronson.linguize.core.chat.LiveRecord
import com.evanaronson.linguize.core.chat.Outgoing
import com.evanaronson.linguize.core.chat.SendEvent
import com.evanaronson.linguize.core.chat.SendState
import com.evanaronson.linguize.core.chat.deriveTitle
import com.evanaronson.linguize.core.chat.on
import com.evanaronson.linguize.core.memory.MemoryMessage
import com.evanaronson.linguize.core.memory.UserMemory
import com.evanaronson.linguize.core.writingpad.CheckSchedule
import com.evanaronson.linguize.core.writingpad.LiveSuggestion
import com.evanaronson.linguize.core.writingpad.PadEngine
import com.evanaronson.linguize.data.chat.ChatPreferences
import com.evanaronson.linguize.data.chat.ChatStore
import com.evanaronson.linguize.llm.CheckFailure
import com.evanaronson.linguize.llm.chat.Turn
import com.evanaronson.linguize.ui.card.CardState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

/** Live checking's underlines and the changes under the cursor. */
data class LiveChecks(
    val suggestions: List<LiveSuggestion>,
    val focused: List<LiveSuggestion>,
    val checking: Boolean,
)

/** The pre-send check, run by the screen's card (a [com.evanaronson.linguize.ui.card.CheckViewModel]). */
interface DraftCheck {
    fun start(draft: String, language: Language)
    fun stop()
}

/**
 * Lab Chat: conversations with a partner who only talks, while Linguize checks each message
 * before it goes ([SendState]). Messages and conversations live in [store]; memory learns from
 * a conversation once it goes quiet or is left, and forgets it when it is deleted.
 */
class ChatViewModel(
    private val store: ChatStore,
    private val partner: ChatPartner,
    private val preferences: ChatPreferences,
    private val memory: () -> UserMemory,
    /** The language a new conversation starts in. */
    private val startLanguage: suspend () -> String,
    /** Live checking's checks; never recorded in History. */
    private val liveCheck: suspend (String, Language) -> CheckResult,
    /** Outlives the screen: learning and forgetting finish even after it closes. */
    private val appScope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val uptime: () -> Long = SystemClock::uptimeMillis,
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
    var checkAsYouType by mutableStateOf(false)
        private set
    var live by mutableStateOf<LiveChecks?>(null)
        private set

    /** The id of the message whose changes are shown, if any. */
    var expanded by mutableStateOf<String?>(null)
        private set

    /** Set by the screen that hosts the review card. */
    var draftCheck: DraftCheck? = null

    private var replyJob: Job? = null
    private var engine: PadEngine? = null
    private val record = LiveRecord()
    private var showingLive = false
    private var timer: Job? = null
    private var running: Job? = null
    private var started = false

    /** The chat is showing: opens the current conversation, or a new one, once. */
    fun start() {
        refreshSettings()
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

    /** Back from Settings or the background: the key and Lab choices may have changed. */
    fun refreshSettings() {
        viewModelScope.launch {
            val (key, live) = withContext(Dispatchers.IO) { partner.hasKey() to preferences.checkAsYouType }
            needsKey = !key
            if (live != checkAsYouType) {
                checkAsYouType = live
                if (live) startEngine() else stopEngine()
            }
            val current = thread
            // A reply that failed for want of a key can go now.
            if (key && current != null && current.awaitingReply && replyFailure?.reason == CheckFailure.Reason.NoKey) retryReply()
        }
    }

    fun newConversation() {
        leave()
        viewModelScope.launch {
            val now = clock()
            val language = startLanguage()
            val conversation = Conversation(UUID.randomUUID().toString(), "", language, now, now)
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
        reset()
        thread = next
        preferences.lastLanguage = next.conversation.language
        conversations = visible(conversations, next.conversation.id)
    }

    /** The conversation's language; an untouched opener is written again in the new one. */
    fun setLanguage(code: String) {
        val current = thread ?: return
        if (current.conversation.language == code || Language.forCode(code) == null) return
        val conversation = current.conversation.copy(language = code)
        thread = current.copy(conversation = conversation)
        preferences.lastLanguage = code
        startEngine()
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
     * Deletes conversation [id] and forgets what memory learned from it; [onDeleted] gets what
     * was removed so [restore] can bring it back.
     */
    fun delete(id: String, onDeleted: (Pair<Conversation, List<ChatMessage>>) -> Unit) {
        val wasOpen = thread?.conversation?.id == id
        if (wasOpen) {
            replyJob?.cancel()
            reset()
        }
        viewModelScope.launch {
            val removed = store.delete(id) ?: return@launch
            appScope.launch { runCatching { memory().forget(source(id)) } }
            reloadList()
            if (wasOpen) {
                val next = store.conversations().firstOrNull { it.writerMessages > 0 }
                if (next != null) {
                    thread = null
                    open(next.conversation.id)
                } else {
                    thread = null
                    newConversation()
                }
            }
            onDeleted(removed)
        }
    }

    /** Undoes [delete]. Memory learns from it again once it is quiet. */
    fun restore(removed: Pair<Conversation, List<ChatMessage>>) {
        viewModelScope.launch {
            store.restore(removed.first.copy(learnedCount = 0), removed.second)
            reloadList()
        }
    }

    fun toggleChanges(messageId: String) {
        expanded = if (expanded == messageId) null else messageId
    }

    /** The composer changed: typing, or the cursor moving. */
    fun onDraftChange(value: TextFieldValue) {
        val edited = value.text != draft.text
        draft = value
        if (!edited) {
            showingLive = value.selection.collapsed && (engine?.at(value.selection.start)?.isNotEmpty() == true)
            publishLive()
            return
        }
        if (send != SendState.Composing) handle(SendEvent.Edit)
        engine?.let { engine ->
            engine.edit(value.text, uptime())
            record.edited(value.text)
            showingLive = false
            reschedule()
        }
        publishLive()
    }

    /** Send tapped: checks first, unless live checking already did. */
    fun send() {
        val text = draft.text
        val liveMode = engine != null
        handle(SendEvent.Send(text, checkFirst = !liveMode, live = if (liveMode) record.outgoing(text) else null))
    }

    /** What the check of the draft did; the screen reports the card's state. */
    fun onCardState(state: CardState?) {
        if (send !is SendState.Checking) return
        when (state) {
            is CardState.Done -> handle(SendEvent.Checked(state.result))
            is CardState.Failed -> handle(SendEvent.CheckFailed)
            else -> Unit
        }
    }

    /** Send with the changes accepted on the card; [revision] is null when the answer had none. */
    fun sendAccepted(revision: Revision?) =
        handle(if (revision != null) SendEvent.SendAccepted(revision) else SendEvent.SendAsWritten)

    fun sendAsWritten() = handle(SendEvent.SendAsWritten)

    /** Closes the review and goes back to the draft. */
    fun keepEditing() = handle(SendEvent.Edit)

    private fun handle(event: SendEvent) {
        val transition = send.on(event)
        send = transition.state
        when (val effect = transition.effect) {
            is Effect.StartCheck -> {
                val language = Language.forCode(thread?.conversation?.language) ?: Language.Spanish
                draftCheck?.start(effect.draft, language) ?: handle(SendEvent.CheckFailed)
            }
            Effect.CancelCheck -> draftCheck?.stop()
            is Effect.Deliver -> deliver(effect.outgoing)
            null -> Unit
        }
    }

    private fun deliver(outgoing: Outgoing) {
        val current = thread ?: return
        draftCheck?.stop()
        val message = outgoing.message(UUID.randomUUID().toString(), current.conversation.id, clock())
        draft = TextFieldValue()
        reset()
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
                val answer = partner.reply(language, history, needTitle)
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
            partner.open(language)
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

    // Live checking: the Writing pad's engine, with a record of which parts were accepted.

    fun acceptLive(key: Long) {
        val engine = engine ?: return
        val suggestion = engine.suggestions.firstOrNull { it.key == key } ?: return
        val before = engine.text
        val cursor = engine.accept(key, uptime()) ?: return
        record.accepted(before, engine.text, suggestion)
        draft = TextFieldValue(engine.text, TextRange(cursor))
        showingLive = false
        reschedule()
        publishLive()
    }

    fun dismissLive(key: Long) {
        engine?.dismiss(key)
        showingLive = false
        publishLive()
    }

    /** Shows the change [key], as a screen reader's action on the text does. */
    fun focusLive(key: Long) {
        val suggestion = engine?.suggestions?.firstOrNull { it.key == key } ?: return
        draft = draft.copy(selection = TextRange(suggestion.start))
        showingLive = true
        publishLive()
    }

    fun closeLive() {
        showingLive = false
        publishLive()
    }

    private fun startEngine() {
        stopEngine()
        if (!checkAsYouType) return
        engine = PadEngine(draft.text, CheckSchedule.DEFAULT_IDLE_MILLIS, Checker.MAX_CHARS)
        record.reset()
        record.edited(draft.text)
        reschedule()
        publishLive()
    }

    private fun stopEngine() {
        timer?.cancel()
        running?.cancel()
        engine?.stop()
        engine = null
        showingLive = false
        live = null
    }

    private fun reset() {
        send = SendState.Composing
        record.reset()
        if (engine != null) startEngine()
    }

    private fun reschedule() {
        timer?.cancel()
        val engine = engine ?: return
        val wake = engine.schedule.wakeAt() ?: return
        timer = viewModelScope.launch {
            delay((wake - uptime()).coerceAtLeast(0))
            if (this@ChatViewModel.engine === engine) engine.due(uptime())?.let(::startLive)
            publishLive()
        }
    }

    private fun startLive(start: CheckSchedule.Start) {
        val engine = engine ?: return
        if (start.cancelled != null) running?.cancel()
        val request = start.request
        val language = Language.forCode(thread?.conversation?.language) ?: Language.Spanish
        running = viewModelScope.launch {
            try {
                engine.received(request, liveCheck(request.text, language))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                engine.failed(request)
                if ((e as? CheckFailure)?.reason == CheckFailure.Reason.NoKey) needsKey = true
            }
            if (this@ChatViewModel.engine !== engine) return@launch
            reschedule()
            publishLive()
        }
        publishLive()
    }

    private fun publishLive() {
        val engine = engine
        if (engine == null) {
            live = null
            return
        }
        // The engine follows the composer exactly.
        if (engine.text != draft.text) engine.edit(draft.text, uptime())
        val cursor = draft.selection
        live = LiveChecks(
            suggestions = engine.suggestions,
            focused = if (showingLive && cursor.collapsed) engine.at(cursor.start) else emptyList(),
            checking = engine.schedule.running != null,
        )
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
                val messages = store.messages(conversation.id)
                learnNow(conversation, messages, left = false)
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
        stopEngine()
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
                    // Live checks aren't recorded in History, like checks in Try it.
                    liveCheck = { text, language -> withContext(Dispatchers.Default) { app.checker }.check(text, language).result },
                    appScope = app.appScope,
                )
            }
        }

        private const val HISTORY_SAMPLE = 100
    }
}
