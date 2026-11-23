// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.deviablo.email.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.deviablo.email.R
import io.github.deviablo.email.data.Account
import io.github.deviablo.email.data.Folder
import io.github.deviablo.email.data.FolderRole
import io.github.deviablo.email.data.StoredMessage
import kotlinx.coroutines.launch

/** From this width on, list and conversation sit side by side (KONZEPT 4a). */
private val TwoPaneMinWidth = 720.dp
private val ListPaneWidth = 320.dp

@Composable
fun MailApp(vm: MailViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    if (!state.loaded) return
    if (state.showAccountForm) {
        AccountFormScreen(
            busy = state.formBusy,
            error = state.formError,
            canCancel = state.accounts.isNotEmpty(),
            onSubmit = vm::addAccount,
            onCancel = { vm.showAccountForm(false) },
        )
        return
    }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var openMessageId by rememberSaveable { mutableStateOf<Long?>(null) }
    val openMessage = state.messages.firstOrNull { it.id == openMessageId }
    val closeDrawer: () -> Unit = { scope.launch { drawerState.close() } }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            MailDrawer(
                state = state,
                onFolder = { vm.openFolder(it); openMessageId = null; closeDrawer() },
                onAccount = { vm.switchAccount(it); openMessageId = null; closeDrawer() },
                onAddAccount = { vm.showAccountForm(true); closeDrawer() },
                onSettings = closeDrawer,
            )
        },
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            val openMenu: () -> Unit = { scope.launch { drawerState.open() } }
            val list: @Composable (Long?) -> Unit = { selected ->
                MessageList(state, selected, onOpen = { openMessageId = it }, onMenu = openMenu, onRefresh = vm::syncActive)
            }
            if (maxWidth >= TwoPaneMinWidth) {
                Row(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Pane(Modifier.width(ListPaneWidth)) { list(openMessageId) }
                    Pane(Modifier.weight(1f)) {
                        if (openMessage == null) NothingSelected() else Conversation(openMessage, state.currentFolder, showBack = false, onBack = {})
                    }
                }
            } else {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    if (openMessage == null) {
                        list(null)
                    } else {
                        BackHandler { openMessageId = null }
                        Conversation(openMessage, state.currentFolder, showBack = true, onBack = { openMessageId = null })
                    }
                }
            }
        }
    }
}

@Composable
private fun Pane(modifier: Modifier, content: @Composable () -> Unit) {
    Surface(modifier.fillMaxHeight(), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface, content = content)
}

@Composable
private fun folderLabel(folder: Folder?): String = when (folder?.role) {
    null, FolderRole.INBOX -> stringResource(R.string.folder_inbox)
    FolderRole.DRAFTS -> stringResource(R.string.folder_drafts)
    FolderRole.SENT -> stringResource(R.string.folder_sent)
    FolderRole.ARCHIVE -> stringResource(R.string.folder_archive)
    FolderRole.SPAM -> stringResource(R.string.folder_spam)
    FolderRole.TRASH -> stringResource(R.string.folder_trash)
    FolderRole.OTHER -> folder.name
}

@Composable
private fun MessageList(state: MailUiState, selectedId: Long?, onOpen: (Long) -> Unit, onMenu: () -> Unit, onRefresh: () -> Unit) {
    val yesterday = stringResource(R.string.yesterday)
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Surface(
                Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp).fillMaxWidth().height(56.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onMenu) { Icon(Symbols.Menu, stringResource(R.string.open_menu)) }
                    Text(stringResource(R.string.search_hint), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(
                "${folderLabel(state.currentFolder)} · ${state.activeAccount?.name.orEmpty()}",
                Modifier.padding(start = 16.dp, top = 14.dp, bottom = 6.dp),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.syncError?.let {
                Text(stringResource(it), Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            PullToRefreshBox(isRefreshing = state.syncing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
                if (state.messages.isEmpty()) {
                    EmptyState(
                        if (state.syncing) stringResource(R.string.loading_inbox) else stringResource(R.string.empty_folder),
                        if (state.syncing) "" else stringResource(R.string.empty_folder_body),
                        scrollable = true,
                    )
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(state.messages, key = { it.id }) { m ->
                            MessageRow(m, Dates.short(m.dateMillis, yesterday), selected = m.id == selectedId, onClick = { onOpen(m.id) })
                        }
                        item { Spacer(Modifier.height(88.dp)) }
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = {},
            icon = { Icon(Symbols.Edit, null) },
            text = { Text(stringResource(R.string.compose)) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }
}

private fun StoredMessage.sender() = fromName.ifBlank { fromAddress }

@Composable
private fun MessageRow(m: StoredMessage, date: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val unread = !m.seen
    val strong = if (unread) FontWeight.Bold else FontWeight.Normal
    val textColor = if (unread) colors.onSurface else colors.onSurfaceVariant
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) colors.secondaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(m.sender(), unreadDot = unread)
        Column(Modifier.padding(start = 16.dp).weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(m.sender(), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, fontWeight = strong, color = textColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(date, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelMedium, fontWeight = strong, color = textColor)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(m.subject.ifBlank { stringResource(R.string.no_subject) }, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = strong, color = textColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (m.hasAttachment) Icon(Symbols.Attach, null, Modifier.size(18.dp), tint = colors.onSurfaceVariant)
                if (m.flagged) Icon(Symbols.StarFilled, null, Modifier.size(18.dp), tint = Color(0xFFE3A008))
            }
        }
    }
}

private val AvatarColors = listOf(0xFF7A6FF0, 0xFF2E9D76, 0xFFD9783B, 0xFFC2457A, 0xFF3B82C4, 0xFF8A8A85, 0xFFB3952B)

@Composable
private fun Avatar(name: String, unreadDot: Boolean = false, size: Int = 40) {
    val initials = name.split(Regex("[\\s,.@]+")).filter { it.isNotEmpty() && it.first().isLetterOrDigit() }.take(2)
        .joinToString("") { it.first().uppercase() }.ifEmpty { "?" }
    val color = Color(AvatarColors[name.sumOf { it.code } % AvatarColors.size])
    Box(Modifier.size(size.dp)) {
        Box(Modifier.fillMaxSize().background(color, CircleShape), contentAlignment = Alignment.Center) {
            Text(initials, color = Color.White, style = if (size >= 40) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge)
        }
        if (unreadDot) {
            Box(
                Modifier.align(Alignment.BottomEnd).size(12.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
                    .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape),
            )
        }
    }
}

@Composable
private fun EmptyState(title: String, body: String, scrollable: Boolean = false) {
    Column(
        Modifier.fillMaxSize().then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(96.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Symbols.Mail, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(title, Modifier.padding(top = 16.dp), style = MaterialTheme.typography.headlineSmall)
        if (body.isNotEmpty()) Text(body, Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun NothingSelected() = EmptyState(stringResource(R.string.nothing_selected_title), stringResource(R.string.nothing_selected_body))

@Composable
private fun Conversation(m: StoredMessage, folder: Folder?, showBack: Boolean, onBack: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (showBack) IconButton(onClick = onBack) { Icon(Symbols.Back, stringResource(R.string.back)) }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = {}) { Icon(Symbols.Delete, stringResource(R.string.delete)) }
            IconButton(onClick = {}) { Icon(Symbols.MoreVert, stringResource(R.string.more)) }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(m.subject.ifBlank { stringResource(R.string.no_subject) }, style = MaterialTheme.typography.headlineSmall)
            Surface(shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, colors.outlineVariant)) {
                Text(folderLabel(folder), Modifier.padding(horizontal = 8.dp, vertical = 3.dp), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            }
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = colors.surface, border = BorderStroke(2.dp, colors.primary)) {
                Column {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(m.sender(), size = 32)
                        Column(Modifier.padding(start = 12.dp).weight(1f)) {
                            Text(m.sender(), fontWeight = FontWeight.Medium)
                            Text(m.fromAddress, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        }
                        Surface(shape = CircleShape, color = colors.primary) {
                            Text(stringResource(R.string.newest), Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = colors.onPrimary)
                        }
                    }
                    Text(stringResource(R.string.body_later), Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = colors.onSurfaceVariant)
                    HorizontalDivider(Modifier.padding(top = 8.dp), color = colors.outlineVariant)
                    Row(Modifier.padding(horizontal = 4.dp)) {
                        IconButton(onClick = {}) { Icon(Symbols.Reply, null) }
                        IconButton(onClick = {}) { Icon(Symbols.ReplyAll, null) }
                        IconButton(onClick = {}) { Icon(Symbols.Forward, null) }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = {}) { Icon(if (m.flagged) Symbols.StarFilled else Symbols.Star, null) }
                        IconButton(onClick = {}) { Icon(Symbols.Mail, null) }
                        IconButton(onClick = {}) { Icon(Symbols.Delete, stringResource(R.string.delete)) }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun roleIcon(role: FolderRole): ImageVector = when (role) {
    FolderRole.INBOX -> Symbols.Inbox
    FolderRole.DRAFTS -> Symbols.Drafts
    FolderRole.SENT -> Symbols.Sent
    FolderRole.ARCHIVE -> Symbols.Archive
    FolderRole.SPAM -> Symbols.Spam
    FolderRole.TRASH -> Symbols.Delete
    FolderRole.OTHER -> Symbols.Folder
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
    ModalDrawerSheet {
        Column(Modifier.fillMaxHeight().padding(horizontal = 12.dp)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.app_name), Modifier.padding(start = 16.dp, top = 20.dp, bottom = 12.dp), style = MaterialTheme.typography.titleLarge)
                if (state.syncing && state.folders.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
                standard.forEach { folder ->
                    NavigationDrawerItem(
                        label = { Text(folderLabel(folder)) },
                        selected = folder.id == state.currentFolderId,
                        onClick = { onFolder(folder.id) },
                        icon = { Icon(roleIcon(folder.role), null) },
                        // Like the Linux app: no unread count for spam and trash.
                        badge = if (folder.unread > 0 && folder.role != FolderRole.SPAM && folder.role != FolderRole.TRASH) {
                            { Text("${folder.unread}") }
                        } else null,
                    )
                }
                if (own.isNotEmpty()) {
                    Text(stringResource(R.string.own_folders), Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    own.forEach { folder ->
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
                            NavigationDrawerItem(
                                label = { Column { Text(account.name); Text(account.address, style = MaterialTheme.typography.bodySmall) } },
                                selected = account.id == active.id,
                                onClick = { accountsOpen = false; if (account.id != active.id) onAccount(account.id) },
                                icon = { Avatar(account.name, size = 32) },
                                badge = { if (account.id == active.id) Icon(Symbols.Check, null) },
                            )
                        }
                        HorizontalDivider(Modifier.padding(vertical = 4.dp, horizontal = 8.dp))
                        NavigationDrawerItem(label = { Text(stringResource(R.string.add_account)) }, selected = false, onClick = { accountsOpen = false; onAddAccount() }, icon = { Icon(Symbols.Add, null) })
                    }
                }
            }
            AccountSwitcher(active, open = accountsOpen, onToggle = { accountsOpen = !accountsOpen })
        }
    }
}

@Composable
private fun AccountSwitcher(active: Account, open: Boolean, onToggle: () -> Unit) {
    Surface(
        Modifier.padding(bottom = 12.dp).fillMaxWidth().clickable(onClick = onToggle),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(active.name, size = 32)
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(active.name, fontWeight = FontWeight.Medium)
                Text(active.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(if (open) Symbols.ExpandMore else Symbols.ExpandLess, stringResource(R.string.switch_account))
        }
    }
}
