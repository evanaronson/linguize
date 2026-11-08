package com.evanaronson.linguize.ui.chat

import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.evanaronson.linguize.App
import com.evanaronson.linguize.core.CheckResult
import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.core.Revision
import com.evanaronson.linguize.core.chat.SendState
import com.evanaronson.linguize.history.Origin
import com.evanaronson.linguize.ui.card.CardState
import com.evanaronson.linguize.ui.card.CheckViewModel
import kotlinx.coroutines.launch

/** Lab Chat: a conversation another screen (History) asked the chat to open next. */
object ChatRequests {
    var conversation by mutableStateOf<String?>(null)
}

/**
 * Lab Chat: the screen the app opens to. A conversation with a partner who only talks; Send
 * checks the message first and shows any fixes over the composer. Each sent message is saved
 * in History (Origin.Chat) when History is on. Its own top bar holds the conversations, the
 * language and Settings; the app's bottom bar sits below it.
 */
@Composable
fun ChatScreen(onOpenSettings: () -> Unit) {
    val app = LocalContext.current.applicationContext as? App ?: return
    val chat: ChatViewModel = viewModel(factory = ChatViewModel.factory(app))
    // The chat's own card engine, apart from Try it's; it records as Origin.Chat.
    val check: CheckViewModel = viewModel(key = CHECK_KEY, factory = CheckViewModel.factory(app))

    DisposableEffect(chat, check) {
        chat.draftCheck = CardDraftCheck(check)
        onDispose { chat.draftCheck = null }
    }
    val requested = ChatRequests.conversation
    LaunchedEffect(chat, requested) {
        ChatRequests.conversation = null
        chat.start(requested)
        // Back from Settings with the review open: a check that failed for want of a key runs again.
        check.recheckIfStale()
    }
    LaunchedEffect(chat, check) { snapshotFlow { check.state }.collect(chat::onCardState) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, chat) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> chat.refreshSettings()
                Lifecycle.Event.ON_STOP -> chat.leave()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            chat.leave()
        }
    }

    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }
    BackHandler(enabled = !drawer.isOpen && chat.send != SendState.Composing) { chat.keepEditing() }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ConversationDrawer(
                conversations = chat.conversations,
                currentId = chat.thread?.conversation?.id,
                people = chat.knownPeople,
                onLoadPeople = chat::loadPeople,
                onNew = { persona -> chat.newConversation(persona); scope.launch { drawer.close() } },
                onOpen = { chat.open(it); scope.launch { drawer.close() } },
                onRename = chat::rename,
                onDelete = chat::delete,
            )
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            ChatTopBar(
                title = chat.thread?.conversation?.title.orEmpty(),
                language = chat.thread?.conversation?.language,
                onOpenConversations = { scope.launch { drawer.open() } },
                onSelectLanguage = chat::setLanguage,
                onOpenSettings = onOpenSettings,
            )
            Box(Modifier.weight(1f)) { ChatBody(chat, check, onOpenSettings) }
        }
    }
}

/** The pre-send check on the shared card engine; nothing of it is saved unless a message goes. */
private class CardDraftCheck(private val check: CheckViewModel) : DraftCheck {
    override fun start(draft: String, language: Language) = check.check(draft, language, Origin.Chat)

    override fun stop() = check.discard()

    override fun keepFixes(skipped: Set<Int>): Revision? {
        val revision = revision() ?: return null
        revision.remaining(EditKind.Fix).filter { it.id !in skipped }.forEach { check.accept(it.id) }
        return revision()
    }

    override fun clearAccepted() {
        var guard = 0
        while (revision()?.canUndo == true && guard++ < MAX_UNDO) check.undo()
    }

    override fun finish(sent: String, record: Boolean): String? =
        if (record) check.dismissSent(sent) else null.also { check.discard() }

    private fun revision(): Revision? = ((check.state as? CardState.Done)?.result as? CheckResult.Reviewed)?.revision

    companion object {
        const val MAX_UNDO = 200
    }
}

@Composable
private fun ChatBody(chat: ChatViewModel, check: CheckViewModel, onOpenSettings: () -> Unit) {
    val thread = chat.thread
    val messages = thread?.messages.orEmpty()
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
                        translation = chat.translation(message.id),
                        translationOpen = chat.translationOpen(message.id),
                        onTranslate = { chat.toggleTranslation(message) },
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
