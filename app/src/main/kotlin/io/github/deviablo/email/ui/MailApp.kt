// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.deviablo.email.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.deviablo.email.R
import io.github.deviablo.email.data.Account
import io.github.deviablo.email.data.Conversation
import io.github.deviablo.email.data.FolderRole
import io.github.deviablo.email.data.StoredMessage
import io.github.deviablo.email.data.SendEvent
import io.github.deviablo.email.background.OnScreen
import io.github.deviablo.email.mail.Draft
import io.github.deviablo.email.mail.Original
import io.github.deviablo.email.mail.ReplyKind
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import io.github.deviablo.email.data.MailRepository

/** From this width on, list and conversation sit side by side (KONZEPT 4a). */
internal val TwoPaneMinWidth = 720.dp
private val ListPaneWidth = 320.dp

/** What the composer is asked to open. */
data class ComposeRequest(
    val reply: StoredMessage? = null,
    val mode: ReplyMode? = null,
    val draft: StoredMessage? = null,
    /** Reopening after "Undo": the text as it was, and for replies the original. */
    val restore: Draft? = null,
    val restoreKind: ReplyKind? = null,
    val restoreOriginal: Original? = null,
) {
    val isReply get() = reply != null || restoreKind != null
}

@Composable
fun MailApp(
    vm: MailViewModel = viewModel(),
    onCompose: (ComposeRequest) -> Unit,
    onSettings: () -> Unit,
    onAddAccount: () -> Unit,
    /** On wide screens a reply is written in the right pane, next to the list (wireframe). */
    replyPane: (@Composable () -> Unit)? = null,
) {
    val state by vm.state.collectAsState()
    if (!state.loaded) return

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val res = LocalResources.current
    var moveRequest by remember { mutableStateOf<List<Conversation>?>(null) }
    val closeDrawer: () -> Unit = { scope.launch { drawerState.close() } }
    val dark = isSystemInDarkTheme()

    LaunchedEffect(Unit) {
        vm.snacks.collect { s ->
            val text = if (s.arg != null) res.getString(s.text, s.arg) else res.getString(s.text)
            if (s.reopen != null) {
                val result = snackbar.showSnackbar(text, actionLabel = res.getString(R.string.undo), duration = SnackbarDuration.Long)
                if (result == SnackbarResult.ActionPerformed) onCompose(s.reopen)
            } else if (s.undo == null) {
                snackbar.showSnackbar(text, duration = SnackbarDuration.Short)
            } else {
                // Visible exactly as long as the change can still be taken back.
                val result = withTimeoutOrNull(MailRepository.UNDO_MS - 500) {
                    snackbar.showSnackbar(text, actionLabel = res.getString(R.string.undo), duration = SnackbarDuration.Indefinite)
                }
                if (result == SnackbarResult.ActionPerformed) vm.undo(s)
            }
        }
    }

    LaunchedEffect(Unit) {
        vm.repo.sendEvents.collect { e ->
            when (e) {
                is SendEvent.Sent -> vm.snack(Snack(R.string.sent))
                is SendEvent.Failed -> vm.snack(
                    when {
                        e.permanent -> Snack(R.string.send_failed_permanent, e.reason)
                        e.login -> Snack(R.string.send_failed_login)
                        else -> Snack(R.string.send_failed_retry)
                    },
                )
            }
        }
    }

    LaunchedEffect(state.openKey) { OnScreen.threadKey = state.openKey }

    val open: (Conversation) -> Unit = { c ->
        val draftFolder = state.currentFolder?.role == FolderRole.DRAFTS
        if (draftFolder) onCompose(ComposeRequest(draft = c.newest)) else vm.openConversation(c)
    }
    val reply: (StoredMessage, ReplyMode) -> Unit = { m, mode -> onCompose(ComposeRequest(reply = m, mode = mode)) }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            MailDrawer(
                state = state,
                onFolder = { vm.openFolder(it); closeDrawer() },
                onAccount = { vm.switchAccount(it); closeDrawer() },
                onAddAccount = { closeDrawer(); onAddAccount() },
                onSettings = { closeDrawer(); onSettings() },
            )
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            BoxWithConstraints(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .windowInsetsPadding(WindowInsets.safeDrawing),
            ) {
                val openMenu: () -> Unit = { scope.launch { drawerState.open() } }
                val conversation = state.openConversation
                if (maxWidth >= TwoPaneMinWidth) {
                    Row(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Pane(Modifier.width(ListPaneWidth)) {
                            ListPane(state, vm, conversation?.key, onOpen = open, onMenu = openMenu, onCompose = { onCompose(ComposeRequest()) }, onMoveRequest = { moveRequest = it })
                        }
                        Pane(Modifier.weight(1f)) {
                            if (replyPane != null) replyPane()
                            else if (conversation == null) EmptyState(stringResource(R.string.nothing_selected_title), stringResource(R.string.nothing_selected_body))
                            else ReaderPane(conversation, state, vm, dark, showBack = false, onBack = { vm.openConversation(null) }, onReply = reply, onMoveRequest = { moveRequest = it })
                        }
                    }
                } else {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                        if (conversation == null) {
                            ListPane(state, vm, null, onOpen = open, onMenu = openMenu, onCompose = { onCompose(ComposeRequest()) }, onMoveRequest = { moveRequest = it })
                        } else {
                            BackHandler { vm.openConversation(null) }
                            ReaderPane(conversation, state, vm, dark, showBack = true, onBack = { vm.openConversation(null) }, onReply = reply, onMoveRequest = { moveRequest = it })
                        }
                    }
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(bottom = 80.dp))
        }
    }

    // Registered last, so it wins: back first closes the drawer, then leaves a folder for the inbox (Google apps).
    val inboxId = state.folders.firstOrNull { it.role == FolderRole.INBOX }?.id
    BackHandler(enabled = !drawerState.isClosed || (state.openConversation == null && state.search == null && state.selection.isEmpty() && inboxId != null && state.currentFolderId != inboxId)) {
        if (!drawerState.isClosed) closeDrawer() else vm.openFolder(inboxId!!)
    }

    moveRequest?.let { convs ->
        val currentId = state.currentFolderId
        AlertDialog(
            onDismissRequest = { moveRequest = null },
            title = { Text(stringResource(R.string.choose_folder)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    state.folders.filter { it.id != currentId && it.role != FolderRole.DRAFTS }.forEach { f ->
                        Row(
                            Modifier.fillMaxWidth().clickable { moveRequest = null; vm.move(convs, f) }.padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(roleIcon(f.role), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(folderLabel(f), Modifier.padding(start = 16.dp), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { moveRequest = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun Pane(modifier: Modifier, content: @Composable () -> Unit) {
    Surface(modifier.fillMaxHeight(), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface, content = content)
}

@Composable
private fun MailDrawer(
    state: MailUiState,
    onFolder: (Long) -> Unit,
    onAccount: (Long) -> Unit,
    onAddAccount: () -> Unit,
    onSettings: () -> Unit,
) {
    var accountsOpen by remember { mutableStateOf(false) }
    val active = state.activeAccount ?: return
    val standard = state.folders.filter { it.role != FolderRole.OTHER }
    val own = state.folders.filter { it.role == FolderRole.OTHER }
    var ownOpen by remember { mutableStateOf(true) }
    ModalDrawerSheet {
        Column(Modifier.fillMaxHeight().padding(horizontal = 12.dp)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.app_name), Modifier.padding(start = 16.dp, top = 20.dp, bottom = 12.dp), style = MaterialTheme.typography.titleLarge)
                if (state.syncing && state.folders.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
                standard.forEach { folder ->
                    // Counters like the Linux app: inbox and own folders count unread, drafts count all, spam/trash none.
                    val count = when (folder.role) {
                        FolderRole.DRAFTS -> folder.total
                        FolderRole.SPAM, FolderRole.TRASH, FolderRole.SENT, FolderRole.ARCHIVE -> 0
                        else -> folder.unread
                    }
                    NavigationDrawerItem(
                        label = { Text(folderLabel(folder)) },
                        selected = folder.id == state.currentFolderId,
                        onClick = { onFolder(folder.id) },
                        icon = { Icon(roleIcon(folder.role), null) },
                        badge = if (count > 0) { { Text("$count") } } else null,
                    )
                }
                if (own.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().clickable { ownOpen = !ownOpen }.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.own_folders), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Icon(if (ownOpen) Symbols.ExpandLess else Symbols.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (ownOpen) own.forEach { folder ->
                        NavigationDrawerItem(
                            label = { Text(folder.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            selected = folder.id == state.currentFolderId,
                            onClick = { onFolder(folder.id) },
                            icon = { Icon(Symbols.Folder, null) },
                            badge = if (folder.unread > 0) { { Text("${folder.unread}") } } else null,
                        )
                    }
                }
                NavigationDrawerItem(label = { Text(stringResource(R.string.settings)) }, selected = false, onClick = onSettings, icon = { Icon(Symbols.Settings, null) })
            }
            AnimatedVisibility(accountsOpen) {
                Surface(Modifier.padding(bottom = 6.dp), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Column(Modifier.padding(8.dp)) {
                        state.accounts.forEach { account ->
                            val unread = state.unreadByAccount[account.id] ?: 0
                            NavigationDrawerItem(
                                label = { Column { Text(account.name); Text(account.address, style = MaterialTheme.typography.bodySmall) } },
                                selected = account.id == active.id,
                                onClick = { accountsOpen = false; if (account.id != active.id) onAccount(account.id) },
                                icon = { Avatar(account.name, size = 32) },
                                badge = {
                                    if (account.id == active.id) Icon(Symbols.Check, null)
                                    else if (unread > 0) Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
                                        Text("$unread", Modifier.padding(horizontal = 7.dp, vertical = 1.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary)
                                    }
                                },
                            )
                        }
                        HorizontalDivider(Modifier.padding(vertical = 4.dp, horizontal = 8.dp))
                        NavigationDrawerItem(label = { Text(stringResource(R.string.add_account)) }, selected = false, onClick = { accountsOpen = false; onAddAccount() }, icon = { Icon(Symbols.Add, null) })
                    }
                }
            }
            val othersUnread = state.accounts.any { it.id != active.id && (state.unreadByAccount[it.id] ?: 0) > 0 }
            AccountSwitcher(active, othersUnread, open = accountsOpen, onToggle = { accountsOpen = !accountsOpen })
        }
    }
}

@Composable
private fun AccountSwitcher(active: Account, othersUnread: Boolean, open: Boolean, onToggle: () -> Unit) {
    Surface(
        Modifier.padding(bottom = 12.dp).fillMaxWidth().clickable(onClick = onToggle),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(active.name, size = 32, unreadDot = othersUnread)
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(active.name, fontWeight = FontWeight.Medium)
                Text(active.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(if (open) Symbols.ExpandMore else Symbols.ExpandLess, stringResource(R.string.switch_account))
        }
    }
}
