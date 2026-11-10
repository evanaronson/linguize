package com.evanaronson.linguize.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.evanaronson.linguize.App
import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.core.chat.ChatStart
import com.evanaronson.linguize.core.chat.SendState
import com.evanaronson.linguize.core.time.DraftTiming
import com.evanaronson.linguize.history.Origin
import com.evanaronson.linguize.ui.card.CheckViewModel

/**
 * Lab Chat, the Chat tab's top level: the conversations in the app's current [language]
 * (every language under Linguize, null), newest first, and New conversation. [onOpen] opens
 * one (also a new one, once started). The app's top bar (the wordmark, the language selector,
 * Settings) and bottom bar sit around it.
 */
@Composable
fun ChatListScreen(language: String?, onOpenSettings: () -> Unit = {}, onOpen: (String) -> Unit) {
    val app = LocalContext.current.applicationContext as? App ?: return
    val chat: ChatViewModel = viewModel(factory = ChatViewModel.factory(app))
    val options by com.evanaronson.linguize.ui.language.AppLanguage.of(app).options.collectAsState()
    LaunchedEffect(chat, language) { chat.showList() }
    ConversationList(
        conversations = ChatStart.inLanguage(chat.conversations, language),
        language = language,
        languageOptions = options,
        people = chat.knownPeople,
        onLoadPeople = chat::loadPeople,
        onNew = { persona, picked -> onOpen(chat.newConversation(persona, picked)) },
        onOpen = onOpen,
        onRename = chat::rename,
        onDelete = chat::delete,
        needsKey = chat.needsKey,
        onOpenSettings = onOpenSettings,
    )
}

/**
 * Lab Chat: one conversation with a partner who only talks. Send runs the same check as a
 * message in another app, forced, on the same card. Each sent message is saved in History
 * (Origin.Chat) when History is on. [id] null is "the current conversation" in [language]
 * (what the app opens to): [onResolved] gets the one found, or null when there is none and
 * the list should show. Its bar is the app's page bar: Back to the list and the title (and who
 * the partner plays). There is no language selector: a conversation's language is fixed.
 */
@Composable
fun ChatThreadScreen(
    id: String?,
    language: String?,
    onBack: () -> Unit,
    onResolved: (String?) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as? App ?: return
    val chat: ChatViewModel = viewModel(factory = ChatViewModel.factory(app))
    // The chat's own card engine, apart from Notes'; it records as Origin.Chat.
    val check: CheckViewModel = viewModel(key = CHECK_KEY, factory = CheckViewModel.factory(app))

    DisposableEffect(chat, check) {
        chat.draftCheck = CardDraftCheck(check)
        onDispose { chat.draftCheck = null }
    }
    val resolved by rememberUpdatedState(onResolved)
    val back by rememberUpdatedState(onBack)
    LaunchedEffect(chat, id) {
        if (id == null) chat.openCurrent(language) { resolved(it) }
        else {
            chat.refreshSettings()
            chat.open(id, onMissing = { back() })
        }
        // Back from Settings with the review open: a check that failed for want of a key runs again.
        check.recheckIfStale()
    }
    LaunchedEffect(chat, check) { snapshotFlow { check.state }.collect(chat::onCardState) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, chat, check) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> chat.refreshSettings()
                Lifecycle.Event.ON_STOP -> chat.leave()
                else -> Unit
            }
            // Lab, Time spent: nothing counts while the chat isn't on screen.
            if (event == Lifecycle.Event.ON_START || event == Lifecycle.Event.ON_STOP) {
                (event == Lifecycle.Event.ON_START).let { chat.visible(it); check.timing.visible(it) }
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            chat.leave()
            chat.visible(false)
            check.timing.visible(false)
        }
    }

    com.evanaronson.linguize.ui.components.NavigationBackHandler(enabled = chat.send != SendState.Composing) { chat.keepEditing() }

    // Until the requested conversation is open, show nothing of another one.
    val thread = chat.thread?.takeIf { id == null || it.conversation.id == id }
    Column(Modifier.fillMaxSize()) {
        ChatTopBar(
            title = thread?.conversation?.title.orEmpty(),
            persona = thread?.conversation?.persona,
            onBack = onBack,
        )
        // Lab, Consistency: the card's wrong-language hint can't point at a selector here.
        androidx.compose.runtime.CompositionLocalProvider(
            com.evanaronson.linguize.ui.card.LocalLanguageHint provides com.evanaronson.linguize.ui.card.LanguageHint.Fixed,
        ) {
            Box(Modifier.weight(1f)) { if (thread != null) ChatBody(chat, check, onOpenSettings) }
        }
    }
}

/** The check before sending, on the same card as everywhere else; nothing of it is saved unless a message goes. */
private class CardDraftCheck(private val check: CheckViewModel) : DraftCheck {
    override fun start(draft: String, language: Language, replyingTo: String?) =
        check.check(draft, language, Origin.Chat, replyingTo = replyingTo)

    override fun stop() = check.discard()

    override fun finish(sent: String, timing: DraftTiming?): String? = check.dismissSent(sent, timing)

    override fun reviewMs(): Long = check.timing.reviewMs()
}

@Composable
private fun ChatBody(chat: ChatViewModel, check: CheckViewModel, onOpenSettings: () -> Unit) {
    val thread = chat.thread
    val messages = thread?.messages.orEmpty()
    val lastReply = messages.lastOrNull { !it.fromWriter }?.id
    val list = rememberLazyListState()
    // Newest at the bottom, and the list anchored there, so the keyboard opening keeps it in view.
    LaunchedEffect(messages.size, chat.replying, chat.send) {
        if (list.layoutInfo.totalItemsCount > 0) list.animateScrollToItem(0)
    }
    BoxWithConstraints(Modifier.fillMaxSize().imePadding()) {
        val reviewMax = maxHeight * 0.62f
        Column(Modifier.fillMaxSize()) {
            if (chat.needsKey) NoKeyStrip(onOpenSettings)
            LazyColumn(
                state = list,
                reverseLayout = true,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            ) {
                when {
                    chat.replying -> item(key = "typing") { TypingBubble() }
                    thread?.awaitingReply == true -> item(key = "failed") {
                        ReplyMissing(chat.replyFailure, onRetry = chat::retryReply, onOpenSettings = onOpenSettings)
                    }
                }
                items(messages.asReversed(), key = { it.id }) { message ->
                    MessageBubble(
                        message,
                        expanded = chat.expanded == message.id,
                        onToggle = { chat.toggleChanges(message.id) },
                        flipped = chat.flippedView(message),
                        onFlip = { chat.flip(message) },
                        hint = chat.translateHint && message.id == lastReply,
                    )
                }
            }
            when (chat.send) {
                SendState.Composing -> Composer(chat)
                is SendState.Checking, is SendState.Reviewing -> ReviewPanel(chat, check, reviewMax, onOpenSettings)
            }
        }
    }
}

private const val CHECK_KEY = "lab_chat_check"
