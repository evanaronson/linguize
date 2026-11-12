package com.evanaronson.linguize.ui.chat

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.core.Revision
import com.evanaronson.linguize.core.Understood
import com.evanaronson.linguize.core.chat.ChatMessage
import com.evanaronson.linguize.core.chat.ChatSnapshot
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
import com.evanaronson.linguize.core.time.DraftTimer
import com.evanaronson.linguize.core.time.DraftTiming
import com.evanaronson.linguize.data.chat.ChatPreferences
import com.evanaronson.linguize.data.chat.ChatStore
import com.evanaronson.linguize.data.chat.PartnerAppend
import com.evanaronson.linguize.llm.CheckFailure
import com.evanaronson.linguize.llm.chat.ChatPrompt
import com.evanaronson.linguize.llm.chat.PartnerReply
import com.evanaronson.linguize.llm.chat.Turn
import com.evanaronson.linguize.ui.card.CardState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** The exact metadata write that can be retried without losing the writer's input. */
sealed interface ConversationWriteFailure {
    val id: String
    data class Create(val conversation: Conversation) : ConversationWriteFailure {
        override val id get() = conversation.id
    }
    data class Rename(override val id: String, val title: String) : ConversationWriteFailure
    data class Delete(override val id: String) : ConversationWriteFailure

    /** Rewinding conversation [id] to edit the writer's message [messageId]. */
    data class Rewind(override val id: String, val messageId: String) : ConversationWriteFailure
}

/**
 * Editing the writer's message [messageId] would also delete later messages of theirs: [checks]
 * of their messages in all, the edited one included, go from the chat and History.
 */
data class EditConfirmation(val conversationId: String, val messageId: String, val checks: Int)

/** The check before sending, run by the screen's card (a [com.evanaronson.linguize.ui.card.CheckViewModel]). */
interface DraftCheck {
    /** The permitted History link, without closing the check; null when recording is off. */
    val sessionId: String?

    /**
     * Checks [draft]. Lab: [replyingTo] is the partner's last message, sent with the check as
     * context only (see [ChatReplyContext]).
     */
    fun start(draft: String, language: Language, replyingTo: String? = null)

    /** Drops the check without saving anything (Keep editing). */
    fun stop()

    /**
     * [sent] was sent: the check is saved in History (Origin.Chat) with what was sent as its
     * final text and the decisions as made. Returns that session's id, or null when History is off.
     * [timing] is what Lab, Time spent measured for it.
     */
    fun finish(sent: String, timing: DraftTiming? = null): String?

    /** Lab, Time spent: the review time of the open card so far. */
    fun reviewMs(): Long = 0L
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
    /** Retries the durable cleanup queue after a conversation has been deleted. */
    private val drainDeletions: suspend () -> Unit,
    /** People memory knows the writer writes to, for "Practise with…". */
    private val people: suspend () -> List<String>,
    /** Translates a partner message into the writer's own language (the Understand path). */
    translate: suspend (String) -> Understood,
    /** Starts building the practice targets for a language before the first reply. */
    private val warmTargets: (Language) -> Unit,
    /** Outlives the screen: learning, forgetting and deleting finish even after it closes. */
    private val appScope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Lab, Language scope: the app's current language, which new conversations start in; null (Linguize) uses the start language. */
    private val currentLanguage: () -> String? = { null },
    /** Lab, Language scope: a conversation in this language opened; the app follows it unless it is on Linguize (Auto). */
    private val onLanguage: (String) -> Unit = {},
) : ViewModel() {
    var conversations by mutableStateOf<List<ConversationSummary>>(emptyList())
        private set
    var thread by mutableStateOf<ChatSnapshot?>(null)
        private set
    var draft by mutableStateOf(TextFieldValue())
        private set
    var send by mutableStateOf<SendState>(SendState.Composing)
        private set

    /** The checked message is being committed; its draft and check remain intact until then. */
    val savingMessage get() = send is SendState.Saving
    val sendSaveFailed get() = send is SendState.SaveFailed
    val replySaveFailed get() = unsavedPartner != null
    var conversationSaveFailure by mutableStateOf<ConversationWriteFailure?>(null)
        private set
    var creatingConversationId by mutableStateOf<String?>(null)
        private set

    val loadFailed get() = failedRead != null

    /** The partner is writing (the opener or a reply). */
    var replying by mutableStateOf(false)
        private set

    /** Why the last reply didn't come; cleared by a retry. */
    var replyFailure by mutableStateOf<CheckFailure?>(null)
        private set
    var needsKey by mutableStateOf(false)
        private set

    /** The id of the writer message whose corrections are open under it, if any: one at a time. */
    var expanded by mutableStateOf<String?>(null)
        private set

    /** Edit on a sent message is waiting for the writer to confirm deleting later checks too. */
    var editConfirmation by mutableStateOf<EditConfirmation?>(null)
        private set

    /** A sent message is being taken back for editing. */
    var rewinding by mutableStateOf(false)
        private set

    /**
     * Whether the writer can edit a sent message now: only between turns. Not while a message
     * is being checked, reviewed or saved, while the partner writes, while the conversation is
     * still being created, or while another edit is under way.
     */
    val canEdit: Boolean
        get() {
            val current = thread ?: return false
            return send == SendState.Composing && !replying && !rewinding &&
                creatingConversationId != current.conversation.id && selection?.id == current.conversation.id
        }

    /** Back from the review: the composer takes focus and brings the keyboard up once. */
    var focusDraft by mutableStateOf(false)
        private set

    /** People memory knows the writer writes to, suggested in the new-conversation sheet. */
    var knownPeople by mutableStateOf<List<String>>(emptyList())
        private set

    /** Whether to show "Tap to translate" under the latest reply: until the writer first flips one. */
    var translateHint by mutableStateOf(false)
        private set

    /** Bubbles flipped to their translation; an older reply saved without one gets it from Understand when tapped. */
    private val flips = Flips(translate)
    private var flipVersion by mutableIntStateOf(0)

    /** Set by the screen that hosts the card. */
    var draftCheck: DraftCheck? = null

    private var replyJob: Job? = null
    private var sendJob: Job? = null
    private var unsavedPartner by mutableStateOf<PartnerMessage?>(null)
    private var learnedQuiet = false

    /** Revocation is visible to the SQLite dispatcher as well as to Main continuations. */
    private class Permit { @Volatile var active = true }
    private class Selection(val id: String?, val permit: Permit = Permit())
    private class PartnerOperation(val selection: Selection, val permit: Permit = Permit())
    private var selection: Selection? = null
    private var editor = Any()
    private var partnerOperation: PartnerOperation? = null
    private data class CreationResult(val conversation: Conversation, val saved: Boolean)
    private val creations = mutableMapOf<String, CompletableDeferred<CreationResult>>()
    private val renames = mutableMapOf<String, Permit>()
    private val acknowledgedRenames = mutableMapOf<String, Permit>()
    private var listRead: Any? = null
    private var settingsRead: Any? = null
    private var peopleRead: Any? = null
    private var metadataCommand: Any? = null
    private var failedRead by mutableStateOf<(() -> Unit)?>(null)
    private var failedReadIsList = false

    private data class PartnerMessage(val message: ChatMessage, val afterMessageId: String?, val title: String? = null)

    private fun select(id: String?): Selection {
        selection?.permit?.active = false
        cancelPartner()
        listRead = null
        creatingConversationId = null
        failedRead = null
        return Selection(id).also { selection = it }
    }

    private fun owns(owner: Selection) = selection === owner && owner.permit.active
    private fun owns(operation: PartnerOperation) = partnerOperation === operation &&
        operation.permit.active && owns(operation.selection)

    private fun cancelPartner() {
        partnerOperation?.permit?.active = false
        partnerOperation = null
        replyJob?.cancel()
        replying = false
    }

    /** A manual title acknowledged after a transaction's snapshot still wins on screen. */
    private fun ChatSnapshot.afterRename(previous: Permit?): ChatSnapshot {
        val shown = thread?.conversation?.takeIf { it.id == conversation.id }
        return if (shown != null && acknowledgedRenames[conversation.id] !== previous) {
            copy(conversation = conversation.copy(title = shown.title, titleIsCustom = shown.titleIsCustom))
        } else this
    }

    fun retryLoad() { failedRead?.invoke() }

    private fun readFailed(retry: () -> Unit, error: Exception, isList: Boolean = false) {
        Log.w(TAG, "Couldn't load chat", error)
        failedRead = retry
        failedReadIsList = isList
    }

    /** Lab, Time spent: the draft's writing time, measured invisibly. */
    private val drafting = DraftTimer(clock)

    /** The list of conversations is showing: reads it again. Memory learns from quiet ones, once. */
    fun showList() {
        val owner = select(null)
        refreshSettings()
        viewModelScope.launch { if (owns(owner)) readList() }
    }

    /** Resolve the current conversation only while this navigation request still owns selection. */
    fun openCurrent(language: String?, onResolved: (String?) -> Unit) {
        val owner = select(null)
        refreshSettings()
        viewModelScope.launch {
            if (!owns(owner)) return@launch
            val all = readList(requiredBy = owner) { openCurrent(language, onResolved) } ?: return@launch
            if (!owns(owner)) return@launch
            val latest = all.firstOrNull { language == null || it.conversation.language == language }
            when (val opening = ChatStart.opening(latest, clock())) {
                is ChatStart.Opening.Resume -> {
                    open(opening.id)
                    onResolved(opening.id)
                }
                ChatStart.Opening.New -> onResolved(null)
            }
        }
    }

    private suspend fun readList(requiredBy: Selection? = null, retry: () -> Unit = { showList() }): List<ConversationSummary>? {
        val request = Any().also { listRead = it }
        val all = try {
            store.readConversations()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (listRead === request || requiredBy?.let(::owns) == true) readFailed(retry, e, isList = true)
            return null
        }
        if (listRead !== request) return all
        conversations = visible(all, thread?.conversation?.id)
        if (failedReadIsList) {
            failedRead = null
        }
        if (!learnedQuiet) {
            learnedQuiet = true
            learnQuiet(all)
        }
        return all
    }

    fun refreshSettings() {
        // A later Chat visit retries interrupted or failed cleanup, without a polling loop.
        appScope.launch { drainDeletions() }
        val request = Any().also { settingsRead = it }
        viewModelScope.launch {
            val (key, hintSeen) = withContext(Dispatchers.IO) { partner.hasKey() to preferences.translateHintSeen }
            if (settingsRead !== request) return@launch
            needsKey = !key
            translateHint = !hintSeen
            val current = thread
            // A reply that failed for want of a key can go now.
            if (key && current != null && current.awaitingReply && replyFailure?.reason == CheckFailure.Reason.NoKey) retryReply()
        }
    }

    /**
     * A new conversation. [persona] is optional: who the writer is talking to ("my kid's
     * teacher"), which the partner then plays; empty means the usual partner. [language] is
     * the one picked in the sheet, else the usual start language.
     */
    fun newConversation(persona: String? = null, language: String? = null): String {
        leave()
        val who = persona?.trim()?.take(ChatPrompt.MAX_PERSONA)?.takeIf { it.isNotEmpty() }
        val id = UUID.randomUUID().toString()
        val owner = select(id)
        val now = clock()
        val title = who?.let { deriveTitle(it) }.orEmpty()
        val known = (language ?: currentLanguage())?.takeIf { Language.forCode(it) != null }
        creatingConversationId = id
        conversationSaveFailure = null
        if (known != null && !savingMessage) show(ChatSnapshot(Conversation(id, title, known, now, now, persona = who), emptyList()))
        create(owner) { Conversation(id, title, known ?: startLanguage(), now, now, persona = who) }
        return id
    }

    /** Creation may finish durably after navigation, but only its selection can publish or prune. */
    private fun create(owner: Selection, conversation: suspend () -> Conversation) {
        val completion = CompletableDeferred<CreationResult>()
        metadataCommand = completion
        val id = checkNotNull(owner.id)
        creations[id] = completion
        val pendingSend = sendJob
        viewModelScope.launch {
            try {
                pendingSend?.join()
                val record = conversation()
                val created = withContext(NonCancellable) { store.create(record) }
                if (owns(owner)) {
                    creatingConversationId = null
                    if (metadataCommand === completion) {
                        conversationSaveFailure = if (created) null else ConversationWriteFailure.Create(record)
                    }
                    if (created) {
                        if (thread?.conversation?.id != id) show(ChatSnapshot(record, emptyList()))
                        writeOpener(record)
                    }
                }
                completion.complete(CreationResult(record, created))
                if (created && owns(owner)) {
                    // The permission is checked again in delete's transaction, after IO dispatch.
                    val all = readList { open(id) }.orEmpty()
                    for (old in all.filter { it.writerMessages == 0 && it.conversation.id != id }) {
                        if (!owns(owner)) break
                        if (old.conversation.id !in creations) store.delete(old.conversation.id) { owner.permit.active }
                    }
                    appScope.launch { drainDeletions() }
                    reloadList()
                }
            } catch (e: CancellationException) {
                completion.cancel(e)
                throw e
            } finally {
                if (creations[id] === completion) creations.remove(id)
            }
        }
    }

    fun retryConversationWrite() {
        val failed = conversationSaveFailure ?: return
        conversationSaveFailure = null
        when (failed) {
            is ConversationWriteFailure.Create -> if (failed.id !in creations) {
                val owner = selection?.takeIf { it.id == failed.id } ?: select(failed.id)
                creatingConversationId = failed.id
                create(owner) { failed.conversation }
            }
            is ConversationWriteFailure.Rename -> rename(failed.id, failed.title)
            is ConversationWriteFailure.Delete -> delete(failed.id)
            is ConversationWriteFailure.Rewind -> if (canEdit && thread?.conversation?.id == failed.id) rewind(failed.id, failed.messageId)
        }
    }

    /** Opens [id]; a pending local creation is joined, and only a successful missing read navigates back. */
    fun open(id: String, onMissing: () -> Unit = {}) {
        if (selection?.id == id && thread?.conversation?.id == id && !loadFailed) {
            onLanguage(checkNotNull(thread).conversation.language)
            return
        }
        leave()
        val pendingCreate = creations[id]
        val owner = selection?.takeIf { pendingCreate != null && it.id == id && it.permit.active } ?: select(id)
        if (pendingCreate != null) {
            creatingConversationId = id
            metadataCommand = pendingCreate
        }
        val pendingSend = sendJob
        viewModelScope.launch {
            pendingSend?.join()
            if (!owns(owner)) return@launch
            if (pendingCreate != null) {
                val created = pendingCreate.await()
                if (!owns(owner)) return@launch
                creatingConversationId = null
                if (!created.saved) {
                    if (metadataCommand === pendingCreate) {
                        conversationSaveFailure = ConversationWriteFailure.Create(created.conversation)
                    }
                    return@launch
                }
                if (thread?.conversation?.id == id && partnerOperation != null) return@launch
            }
            val loaded = try {
                store.readThread(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (owns(owner)) readFailed({ open(id, onMissing) }, e)
                return@launch
            }
            if (!owns(owner)) return@launch
            failedRead = null
            if (loaded == null) return@launch onMissing()
            if (thread?.conversation?.id == id) {
                thread = loaded
                onLanguage(loaded.conversation.language)
            } else show(loaded)
            if (loaded.messages.isEmpty()) writeOpener(loaded.conversation)
        }
    }

    private fun show(next: ChatSnapshot) {
        editor = Any()
        cancelPartner()
        replyFailure = null
        unsavedPartner = null
        expanded = null
        editConfirmation = null
        focusDraft = false
        draft = TextFieldValue()
        draftCheck?.stop()
        drafting.reset()
        send = SendState.Composing
        thread = next
        preferences.lastLanguage = next.conversation.language
        onLanguage(next.conversation.language)
        conversations = visible(conversations, next.conversation.id)
        Language.forCode(next.conversation.language)?.let(warmTargets)
    }

    /** Reads who memory knows the writer writes to, for the new-conversation sheet. */
    fun loadPeople() {
        val request = Any().also { peopleRead = it }
        viewModelScope.launch {
            val loaded = try {
                people()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
            if (peopleRead === request) knownPeople = loaded
        }
    }

    fun rename(id: String, title: String) {
        val trimmed = title.trim().take(MAX_TITLE)
        renames[id]?.active = false
        val operation = Permit().also { renames[id] = it }
        metadataCommand = operation
        viewModelScope.launch {
            val saved = store.rename(id, trimmed, custom = trimmed.isNotEmpty()) { operation.active }
            if (!operation.active) return@launch
            if (!saved) {
                if (metadataCommand === operation) conversationSaveFailure = ConversationWriteFailure.Rename(id, trimmed)
                return@launch
            }
            acknowledgedRenames[id] = operation
            if (metadataCommand === operation) conversationSaveFailure = null
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
        val command = Any().also { metadataCommand = it }
        val pendingSend = sendJob
        appScope.launch(Dispatchers.Main.immediate) {
            pendingSend?.join()
            if (!store.delete(id)) {
                if (metadataCommand === command) conversationSaveFailure = ConversationWriteFailure.Delete(id)
                return@launch
            }
            if (metadataCommand === command) conversationSaveFailure = null
            if (selection?.id == id) select(null)
            if (thread?.conversation?.id == id) {
                draftCheck?.stop()
                send = SendState.Composing
            }
            appScope.launch(start = CoroutineStart.UNDISPATCHED) {
                // Invalidate queued/in-flight learning before the UI acknowledges removal.
                // A failed disk cleanup remains in the durable queue for the next drain.
                try {
                    memory().forget(source(id))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Memory couldn't forget a deleted conversation", e)
                }
                drainDeletions()
            }
            // Conversations are deleted from the list, so nothing opens in its place.
            if (thread?.conversation?.id == id) thread = null
            reloadList()
        }
    }

    /** A tap on a corrected writer bubble: opens its corrections (closing any other), or closes them. */
    fun toggleCorrections(messageId: String) {
        expanded = if (expanded == messageId) null else messageId
    }

    // Editing a sent message.

    /**
     * Edit on the writer's sent message [messageId]: rewinds the conversation to just before it
     * and puts its text in the composer, to send again through the usual check. When later
     * messages of the writer would go too, [editConfirmation] asks first; editing the last
     * message they sent (only replies after it) goes straight away. Does nothing mid-turn
     * ([canEdit]) or for a partner message.
     */
    fun edit(messageId: String) {
        if (!canEdit) return
        val current = thread ?: return
        val index = current.messages.indexOfFirst { it.id == messageId }
        if (index < 0 || !current.messages[index].fromWriter) return
        val checks = current.messages.drop(index).count { it.fromWriter }
        if (checks > 1) {
            editConfirmation = EditConfirmation(current.conversation.id, messageId, checks)
        } else {
            rewind(current.conversation.id, messageId)
        }
    }

    /** The confirmation's Delete: the edit goes ahead. */
    fun confirmEdit() {
        val pending = editConfirmation ?: return
        editConfirmation = null
        if (canEdit && thread?.conversation?.id == pending.conversationId) rewind(pending.conversationId, pending.messageId)
    }

    /** The confirmation's Cancel (or dismissing it): nothing changes. */
    fun cancelEdit() {
        editConfirmation = null
    }

    /**
     * Removes [messageId] and everything after it, durably queueing each removed writer
     * message's History, time and memory cleanup with the same transaction (as deleting a
     * conversation does), then puts the message's sent text in the composer, focused.
     * There is no Undo: History deletes for good, and the text is still in the composer.
     */
    private fun rewind(conversationId: String, messageId: String) {
        val owner = selection?.takeIf { it.id == conversationId } ?: return
        val text = thread?.messages?.firstOrNull { it.id == messageId }?.text ?: return
        val command = Any().also { metadataCommand = it }
        val renameAtStart = acknowledgedRenames[conversationId]
        val pendingSend = sendJob
        rewinding = true
        cancelPartner()
        viewModelScope.launch {
            try {
                pendingSend?.join()
                val rewound = withContext(NonCancellable) {
                    store.rewind(conversationId, messageId) { owner.permit.active }?.also {
                        appScope.launch(start = CoroutineStart.UNDISPATCHED) {
                            // Invalidate queued/in-flight learning from the removed messages first.
                            try {
                                memory().forget(source(conversationId))
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Log.w(TAG, "Memory couldn't forget a rewound conversation", e)
                            }
                            drainDeletions()
                        }
                    }
                }
                if (!owns(owner)) return@launch
                if (rewound == null) {
                    if (metadataCommand === command) conversationSaveFailure = ConversationWriteFailure.Rewind(conversationId, messageId)
                    return@launch
                }
                if (metadataCommand === command) conversationSaveFailure = null
                editor = Any()
                replyFailure = null
                unsavedPartner = null
                expanded = null
                thread = rewound.afterRename(renameAtStart)
                draft = TextFieldValue(text, TextRange(text.length))
                // Lab, Time spent: the edited message's record went with it; writing counts afresh.
                drafting.reset()
                focusDraft = true
                reloadList()
            } finally {
                rewinding = false
            }
        }
    }

    // Translations of partner messages.

    /** What partner message [message] shows: null for the original, else its translation. Read in composition. */
    fun flippedView(message: ChatMessage): Flipped? {
        flipVersion
        return flips.view(message)
    }

    /** A tap on a partner bubble: flips it to its translation, or back. */
    fun flip(message: ChatMessage) {
        val fetch = flips.flip(message)
        flipVersion++
        if (message.id in flips.flipped && translateHint) {
            translateHint = false
            preferences.translateHintSeen = true
        }
        if (!fetch) return
        viewModelScope.launch {
            flips.load(message)
            flipVersion++
        }
    }

    // Composing and sending.

    fun onDraftChange(value: TextFieldValue) {
        if (savingMessage) return
        if (value.text != draft.text) drafting.typed(value.text)
        draft = value
    }

    /** Send tapped: the draft is checked first. */
    fun send() {
        val currentId = thread?.conversation?.id ?: return
        if (selection?.id != currentId || creatingConversationId == currentId ||
            (conversationSaveFailure as? ConversationWriteFailure.Create)?.id == currentId) return
        handle(SendEvent.Send(draft.text))
        if (send is SendState.Checking) drafting.sendTapped()
    }

    /** Lab, Time spent: the chat is on screen, or isn't. Nothing is counted while it isn't. */
    fun visible(visible: Boolean) = drafting.visible(visible)

    /** What the check of the draft did; the screen reports the card's state. */
    fun onCardState(state: CardState?) {
        if (send !is SendState.Checking) return
        when (state) {
            is CardState.Done -> handle(SendEvent.Checked(state.result))
            is CardState.Failed -> handle(SendEvent.CheckFailed)
            else -> Unit
        }
    }

    /** The card's Send: the text with whatever the writer accepted in [revision], as Apply would put it back. */
    fun sendReviewed(revision: Revision) = handle(SendEvent.SendReviewed(revision))

    /**
     * Edit message, or system Back, on the review: closes the card and goes back to the draft,
     * as it was, with the keyboard.
     */
    fun editDraft() {
        if (savingMessage || send == SendState.Composing) return
        drafting.keptEditing(draftCheck?.reviewMs() ?: 0L)
        handle(SendEvent.Edit)
        focusDraft = true
    }

    /** The composer took focus after [editDraft]. */
    fun draftFocused() {
        focusDraft = false
    }

    private fun handle(event: SendEvent) {
        val transition = send.on(event)
        send = transition.state
        when (val effect = transition.effect) {
            is Effect.StartCheck -> {
                val language = Language.forCode(thread?.conversation?.language) ?: Language.Spanish
                val replyingTo = ChatReplyContext.of(thread?.messages.orEmpty())
                draftCheck?.start(effect.draft, language, replyingTo) ?: handle(SendEvent.CheckFailed)
            }
            Effect.CancelCheck -> draftCheck?.stop()
            is Effect.Deliver -> deliver(effect.outgoing)
            null -> Unit
        }
    }

    private fun deliver(outgoing: Outgoing) {
        val current = thread
        val owner = selection
        if (current == null || owner == null || owner.id != current.conversation.id) {
            send = SendState.Composing
            return
        }
        val writingIn = editor
        val renameAtStart = acknowledgedRenames[current.conversation.id]
        val check = draftCheck
        val id = UUID.randomUUID().toString()
        val message = outgoing.message(id, current.conversation.id, clock(), check?.sessionId)
        cancelPartner()
        sendJob = viewModelScope.launch {
            // The accepted writer command and its History acknowledgement survive navigation.
            // Navigation waits for this commit; generated turns use independently revocable permits.
            withContext(NonCancellable) {
                val saved = store.appendWriter(message)
                if (saved == null) {
                    if (editor === writingIn) handle(SendEvent.SaveFailed)
                    return@withContext
                }
                check?.finish(outgoing.text, drafting.sent(id))
                // Returning from the list may reuse this editor. A committed draft must
                // be retired there too, even though navigation revoked reply ownership.
                if (editor !== writingIn) return@withContext
                draft = TextFieldValue()
                handle(SendEvent.Saved)
                val withMessage = saved.afterRename(renameAtStart)
                thread = withMessage
                if (owns(owner)) requestReply(withMessage)
                reloadList()
            }
        }
    }

    /** Retries the same checked message without discarding accepted changes or rerunning the model. */
    fun retrySend() {
        if (selection?.id == thread?.conversation?.id) handle(SendEvent.RetrySave)
    }

    fun retryReply() {
        val current = thread ?: return
        if (replying || selection?.id != current.conversation.id) return
        val unsaved = unsavedPartner?.takeIf { it.message.conversationId == current.conversation.id }
        if (unsaved != null) {
            launchPartner { operation -> savePartner(unsaved, operation) }
        } else if (current.awaitingReply) requestReply(current)
        else if (current.messages.isEmpty()) writeOpener(current.conversation)
    }

    /** All opener/reply/retry effects, including finally, belong to this exact request. */
    private fun launchPartner(block: suspend (PartnerOperation) -> Unit) {
        val selected = selection ?: return
        cancelPartner()
        val operation = PartnerOperation(selected).also { partnerOperation = it }
        replying = true
        replyFailure = null
        unsavedPartner = null
        replyJob = viewModelScope.launch {
            try {
                block(operation)
            } catch (e: CancellationException) {
                throw e
            } catch (e: CheckFailure) {
                if (owns(operation)) {
                    replyFailure = e
                    if (e.reason == CheckFailure.Reason.NoKey) needsKey = true
                }
            } finally {
                if (owns(operation)) replying = false
            }
        }
    }

    private fun requestReply(current: ChatSnapshot) {
        val conversation = current.conversation
        val language = Language.forCode(conversation.language) ?: Language.Spanish
        val history = current.messages.map { Turn(it.fromWriter, it.text) }
        val needTitle = conversation.title.isEmpty() && !conversation.titleIsCustom
        launchPartner { operation ->
            val answer = partner.reply(language, history, needTitle, conversation.persona)
            val reply = ChatMessage.partner(UUID.randomUUID().toString(), conversation.id, answer.reply, clock(), answer.translation)
            val title = if (needTitle) {
                (answer.title ?: deriveTitle(current.messages.firstOrNull { it.fromWriter }?.text.orEmpty()))
                    .take(MAX_TITLE).takeIf { it.isNotEmpty() }
            } else null
            savePartner(PartnerMessage(reply, current.messages.lastOrNull()?.id, title), operation)
        }
    }

    private fun writeOpener(conversation: Conversation) {
        val language = Language.forCode(conversation.language) ?: Language.Spanish
        launchPartner { operation ->
            val answer = try {
                partner.open(language, conversation.persona)
            } catch (e: CancellationException) {
                throw e
            } catch (e: CheckFailure) {
                if (owns(operation) && e.reason == CheckFailure.Reason.NoKey) needsKey = true
                PartnerReply(fallbackOpener(language), null)
            }
            val opener = ChatMessage.partner(UUID.randomUUID().toString(), conversation.id, answer.reply, clock(), answer.translation)
            savePartner(PartnerMessage(opener, afterMessageId = null), operation)
        }
    }

    /** A generated answer is visible only after its eligible message was saved; retry reuses it. */
    private suspend fun savePartner(pending: PartnerMessage, operation: PartnerOperation) {
        if (!owns(operation)) return
        val renameAtStart = acknowledgedRenames[pending.message.conversationId]
        withContext(NonCancellable) {
            val saved = store.appendPartner(pending.message, pending.afterMessageId, pending.title) {
                operation.permit.active && operation.selection.permit.active
            }
            if (!owns(operation)) return@withContext
            when (saved) {
                PartnerAppend.Failed -> unsavedPartner = pending
                PartnerAppend.Superseded -> {
                    // Another writer/VM may have advanced the durable tail. Retry must use
                    // that acknowledged conversation, while retaining this editor's draft.
                    val refreshed = try {
                        store.readThread(pending.message.conversationId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (owns(operation)) readFailed({ open(pending.message.conversationId) }, e)
                        return@withContext
                    }
                    if (owns(operation)) thread = refreshed?.afterRename(renameAtStart)
                }
                is PartnerAppend.Saved -> {
                    thread = saved.snapshot.afterRename(renameAtStart)
                    reloadList()
                }
            }
        }
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
            if (memory().learnFrom(source(conversation.id), messages.map { MemoryMessage(it.fromWriter, it.text) })) {
                store.setLearned(conversation.id, messages.size)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Memory couldn't learn from a conversation", e)
        }
    }

    private suspend fun reloadList() {
        readList { viewModelScope.launch { reloadList() } }
    }

    /** Conversations worth listing: any the writer spoke in, and the open one. */
    private fun visible(all: List<ConversationSummary>, openId: String?) =
        all.filter { it.writerMessages > 0 || it.conversation.id == openId }

    override fun onCleared() {
        selection?.permit?.active = false
        cancelPartner()
        leave()
    }

    companion object {
        private const val TAG = "LabChat"
        const val MAX_TITLE = 60

        /** What memory calls a conversation. */
        fun source(id: String) = "chat:$id"

        /**
         * A natural opener when the partner can't write one (no key, offline): a short
         * greeting in the conversation's language ([ChatStart.fallbackOpener]).
         */
        fun fallbackOpener(language: Language): String = ChatStart.fallbackOpener(language.code)

        fun factory(app: App): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val preferences = ChatPreferences(app)
                val language = com.evanaronson.linguize.ui.language.AppLanguage.of(app)
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
                                // Lab, language-generic: a new conversation starts in one of the writer's languages.
                                supported = runCatching { app.settings.myLanguages.codes }.getOrDefault(Language.all.map { it.code }),
                            )
                        }
                    },
                    drainDeletions = { app.chatDeletions.drain() },
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
                    currentLanguage = { language.current.value },
                    onLanguage = { language.follow(it) },
                )
            }
        }

        private const val HISTORY_SAMPLE = 100
    }
}
