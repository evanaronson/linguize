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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.evanaronson.linguize.App
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.core.chat.SendState
import com.evanaronson.linguize.history.Origin
import com.evanaronson.linguize.ui.card.CheckViewModel
import kotlinx.coroutines.launch

/**
 * Lab Chat: the screen the app opens to. A conversation with a partner who only talks; Send
 * checks the message first and opens the card's review when there is something to decide.
 * Its own top bar holds the conversations, the language, Home and Settings.
 */
@Composable
fun ChatScreen(onOpenHome: () -> Unit, onOpenSettings: () -> Unit) {
    val app = LocalContext.current.applicationContext as? App ?: return
    val chat: ChatViewModel = viewModel(factory = ChatViewModel.factory(app))
    // The chat's own card, apart from Try it's; checks made with it are never recorded in History.
    val check: CheckViewModel = viewModel(key = CHECK_KEY, factory = CheckViewModel.factory(app))

    DisposableEffect(chat, check) {
        chat.draftCheck = object : DraftCheck {
            override fun start(draft: String, language: Language) = check.check(draft, language, Origin.Tester)
            override fun stop() = check.discard()
        }
        onDispose { chat.draftCheck = null }
    }
    LaunchedEffect(chat) {
        chat.start()
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
    val snackbar = remember { SnackbarHostState() }
    val deleted = stringResource(R.string.lab_chat_deleted)
    val undo = stringResource(R.string.undo)
    BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }
    BackHandler(enabled = !drawer.isOpen && chat.send != SendState.Composing) { chat.keepEditing() }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ConversationDrawer(
                conversations = chat.conversations,
                currentId = chat.thread?.conversation?.id,
                onNew = { chat.newConversation(); scope.launch { drawer.close() } },
                onOpen = { chat.open(it); scope.launch { drawer.close() } },
                onRename = chat::rename,
                onDelete = { id ->
                    chat.delete(id) { removed ->
                        scope.launch {
                            if (snackbar.showSnackbar(deleted, undo, withDismissAction = false) == SnackbarResult.ActionPerformed) {
                                chat.restore(removed)
                            }
                        }
                    }
                },
            )
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            ChatTopBar(
                title = chat.thread?.conversation?.title.orEmpty(),
                language = chat.thread?.conversation?.language,
                onOpenConversations = { scope.launch { drawer.open() } },
                onSelectLanguage = chat::setLanguage,
                onOpenHome = onOpenHome,
                onOpenSettings = onOpenSettings,
            )
            Box(Modifier.weight(1f)) {
                ChatBody(chat, check, onOpenSettings)
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).imePadding())
            }
        }
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
                    MessageBubble(message, expanded = chat.expanded == message.id, onToggle = { chat.toggleChanges(message.id) })
                }
            }
            when (chat.send) {
                SendState.Composing -> {
                    chat.live?.let { live ->
                        if (live.focused.isNotEmpty()) {
                            LiveChangePanel(live.focused, chat::acceptLive, chat::dismissLive, chat::closeLive)
                        }
                    }
                    Composer(chat)
                }
                is SendState.Checking, is SendState.Reviewing -> ReviewPanel(chat, check, reviewMax, onOpenSettings)
            }
        }
    }
}

private const val CHECK_KEY = "lab_chat_check"
