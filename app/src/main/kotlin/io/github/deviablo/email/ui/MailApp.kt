// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.deviablo.email.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import io.github.deviablo.email.R
import kotlinx.coroutines.launch

/** From this width on, list and conversation sit side by side (KONZEPT 4a). */
private val TwoPaneMinWidth = 720.dp
private val ListPaneWidth = 320.dp

@Composable
fun MailApp() {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var openThreadId by rememberSaveable { mutableStateOf<Int?>(null) }
    val openThread = SampleData.threads.firstOrNull { it.id == openThreadId }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = { MailDrawer(onClose = { scope.launch { drawerState.close() } }) },
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            val openMenu: () -> Unit = { scope.launch { drawerState.open() } }
            if (maxWidth >= TwoPaneMinWidth) {
                Row(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Pane(Modifier.width(ListPaneWidth)) {
                        ThreadList(openThreadId, onOpen = { openThreadId = it }, onMenu = openMenu)
                    }
                    Pane(Modifier.weight(1f)) {
                        if (openThread == null) NothingSelected() else Conversation(openThread, showBack = false, onBack = {})
                    }
                }
            } else {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    if (openThread == null) {
                        ThreadList(null, onOpen = { openThreadId = it }, onMenu = openMenu)
                    } else {
                        BackHandler { openThreadId = null }
                        Conversation(openThread, showBack = true, onBack = { openThreadId = null })
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
private fun ThreadList(selectedId: Int?, onOpen: (Int) -> Unit, onMenu: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Surface(
                Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp).fillMaxWidth().height(56.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onMenu) { Icon(Symbols.Menu, stringResource(R.string.open_menu)) }
                    Text(
                        stringResource(R.string.search_hint),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                "${stringResource(R.string.folder_inbox)} · ${SampleData.accounts[0].name}",
                Modifier.padding(start = 16.dp, top = 14.dp, bottom = 2.dp),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.sample_notice),
                Modifier.padding(start = 16.dp, bottom = 6.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            LazyColumn(Modifier.fillMaxSize()) {
                items(SampleData.threads, key = { it.id }) { thread ->
                    ThreadRow(thread, selected = thread.id == selectedId, onClick = { onOpen(thread.id) })
                }
                item { Spacer(Modifier.height(88.dp)) }
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

@Composable
private fun ThreadRow(thread: MailThread, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val strong = if (thread.unread) FontWeight.Bold else FontWeight.Normal
    val textColor = if (thread.unread) colors.onSurface else colors.onSurfaceVariant
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) colors.secondaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(thread.senders, unreadDot = thread.unread)
        Column(Modifier.padding(start = 16.dp).weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text(thread.senders, style = MaterialTheme.typography.bodyLarge, fontWeight = strong, color = textColor, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (thread.messages.size > 1) {
                        Text(" · ${thread.messages.size}", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    }
                }
                Text(thread.time, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelMedium, fontWeight = strong, color = textColor)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(thread.subject, style = MaterialTheme.typography.bodyMedium, fontWeight = strong, color = textColor, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (thread.hasAttachment) Icon(Symbols.Attach, null, Modifier.size(18.dp), tint = colors.onSurfaceVariant)
                if (thread.starred) Icon(Symbols.StarFilled, null, Modifier.size(18.dp), tint = Color(0xFFE3A008))
            }
        }
    }
}

private val AvatarColors = listOf(0xFF7A6FF0, 0xFF2E9D76, 0xFFD9783B, 0xFFC2457A, 0xFF3B82C4, 0xFF8A8A85, 0xFFB3952B)

@Composable
private fun Avatar(name: String, unreadDot: Boolean = false, size: Int = 40) {
    val initials = name.split(Regex("[\\s,]+")).filter { it.isNotEmpty() }.take(2).joinToString("") { it.first().uppercase() }
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
private fun NothingSelected() {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(96.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Symbols.Mail, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(stringResource(R.string.nothing_selected_title), Modifier.padding(top = 16.dp), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.nothing_selected_body), Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Conversation(thread: MailThread, showBack: Boolean, onBack: () -> Unit) {
    // Exactly one message is open at a time (KONZEPT: wie im Wireframe); it starts with the newest.
    var openIndex by remember(thread.id) { mutableIntStateOf(thread.messages.lastIndex) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (showBack) IconButton(onClick = onBack) { Icon(Symbols.Back, stringResource(R.string.back)) }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = {}) { Icon(Symbols.Delete, stringResource(R.string.delete)) }
            IconButton(onClick = {}) { Icon(Symbols.MoreVert, stringResource(R.string.more)) }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(thread.subject, style = MaterialTheme.typography.headlineSmall)
            Surface(shape = RoundedCornerShape(8.dp), border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Text(stringResource(R.string.folder_inbox), Modifier.padding(horizontal = 8.dp, vertical = 3.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            thread.messages.forEachIndexed { index, message ->
                MessageCard(message, isNewest = index == thread.messages.lastIndex, open = index == openIndex, onOpen = { openIndex = index })
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun MessageCard(message: Message, isNewest: Boolean, open: Boolean, onOpen: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    val border = when {
        open && isNewest -> androidx.compose.foundation.BorderStroke(2.dp, colors.primary)
        open -> androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant)
        else -> null
    }
    Surface(
        Modifier.fillMaxWidth().clickable(enabled = !open, onClick = onOpen),
        shape = shape,
        color = if (open) colors.surface else colors.surfaceContainer,
        border = border,
    ) {
        Column {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(message.from, size = 32)
                if (open) {
                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                        Text(message.from, fontWeight = FontWeight.Medium)
                        Text(stringResource(R.string.to_me, message.time), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                    if (isNewest) {
                        Surface(shape = CircleShape, color = colors.primary) {
                            Text(stringResource(R.string.newest), Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = colors.onPrimary)
                        }
                    }
                } else {
                    Text(message.from, Modifier.padding(start = 12.dp), fontWeight = FontWeight.Medium)
                    Text(message.preview, Modifier.padding(start = 8.dp).weight(1f), color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(message.time, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                }
            }
            AnimatedVisibility(open) {
                Column {
                    Text(message.preview, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                    HorizontalDivider(Modifier.padding(top = 8.dp), color = colors.outlineVariant)
                    Row(Modifier.padding(horizontal = 4.dp)) {
                        IconButton(onClick = {}) { Icon(Symbols.Reply, null) }
                        IconButton(onClick = {}) { Icon(Symbols.ReplyAll, null) }
                        IconButton(onClick = {}) { Icon(Symbols.Forward, null) }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = {}) { Icon(Symbols.Star, null) }
                        IconButton(onClick = {}) { Icon(Symbols.Mail, null) }
                        IconButton(onClick = {}) { Icon(Symbols.Delete, stringResource(R.string.delete)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun MailDrawer(onClose: () -> Unit) {
    var accountsOpen by remember { mutableStateOf(false) }
    val active = SampleData.accounts[0]
    val folders: List<Triple<ImageVector, Int, String?>> = listOf(
        Triple(Symbols.Inbox, R.string.folder_inbox, "2"),
        Triple(Symbols.Drafts, R.string.folder_drafts, "1"),
        Triple(Symbols.Sent, R.string.folder_sent, null),
        Triple(Symbols.Archive, R.string.folder_archive, null),
        Triple(Symbols.Spam, R.string.folder_spam, null),
        Triple(Symbols.Delete, R.string.folder_trash, null),
    )
    ModalDrawerSheet {
        Column(Modifier.fillMaxHeight().padding(horizontal = 12.dp)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.app_name), Modifier.padding(start = 16.dp, top = 20.dp, bottom = 12.dp), style = MaterialTheme.typography.titleLarge)
                folders.forEachIndexed { index, (icon, label, badge) ->
                    NavigationDrawerItem(
                        label = { Text(stringResource(label)) },
                        selected = index == 0,
                        onClick = onClose,
                        icon = { Icon(icon, null) },
                        badge = badge?.let { { Text(it) } },
                    )
                }
                Text(stringResource(R.string.own_folders), Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                NavigationDrawerItem(label = { Text("Verein") }, selected = false, onClick = onClose, icon = { Icon(Symbols.Folder, null) })
                NavigationDrawerItem(label = { Text(stringResource(R.string.settings)) }, selected = false, onClick = onClose, icon = { Icon(Symbols.Settings, null) })
            }
            AnimatedVisibility(accountsOpen) {
                Surface(Modifier.padding(bottom = 6.dp), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Column(Modifier.padding(8.dp)) {
                        SampleData.accounts.forEach { account ->
                            NavigationDrawerItem(
                                label = { Column { Text(account.name); Text(account.address, style = MaterialTheme.typography.bodySmall) } },
                                selected = account == active,
                                onClick = { accountsOpen = false },
                                icon = { Avatar(account.name, size = 32) },
                                badge = {
                                    if (account == active) Icon(Symbols.Check, null)
                                    else if (account.unread > 0) UnreadBadge(account.unread)
                                },
                            )
                        }
                        HorizontalDivider(Modifier.padding(vertical = 4.dp, horizontal = 8.dp))
                        NavigationDrawerItem(label = { Text(stringResource(R.string.add_account)) }, selected = false, onClick = onClose, icon = { Icon(Symbols.Add, null) })
                    }
                }
            }
            val othersUnread = SampleData.accounts.any { it != active && it.unread > 0 }
            Surface(
                Modifier.padding(bottom = 12.dp).fillMaxWidth().clickable { accountsOpen = !accountsOpen },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
            ) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(active.name, unreadDot = othersUnread, size = 32)
                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                        Text(active.name, fontWeight = FontWeight.Medium)
                        Text(active.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(if (accountsOpen) Symbols.ExpandMore else Symbols.ExpandLess, stringResource(R.string.switch_account))
                }
            }
        }
    }
}

@Composable
private fun UnreadBadge(count: Int) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
        Text("$count", Modifier.padding(horizontal = 7.dp, vertical = 1.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary)
    }
}
