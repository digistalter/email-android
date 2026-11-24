// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.deviablo.email.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.deviablo.email.R
import io.github.deviablo.email.mail.Compose
import io.github.deviablo.email.data.Conversation
import io.github.deviablo.email.data.FolderRole
import io.github.deviablo.email.data.SearchFilter
import io.github.deviablo.email.data.Threading

@Composable
fun ListPane(
    state: MailUiState,
    vm: MailViewModel,
    selectedKey: String?,
    onOpen: (Conversation) -> Unit,
    onMenu: () -> Unit,
    onCompose: () -> Unit,
    onMoveRequest: (List<Conversation>) -> Unit,
) {
    val selecting = state.selection.isNotEmpty()
    val search = state.search
    if (selecting) BackHandler { vm.clearSelection() } else if (search != null) BackHandler { vm.closeSearch() }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            when {
                selecting -> SelectionBar(state, vm, onMoveRequest)
                search != null -> SearchHeader(search, vm)
                else -> FolderHeader(state, vm, onMenu)
            }
            state.syncError?.takeIf { search == null }?.let {
                Text(stringResource(it), Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            PullToRefreshBox(isRefreshing = state.syncing && search == null, onRefresh = vm::syncActive, modifier = Modifier.fillMaxSize()) {
                val items = state.visibleConversations
                if (items.isEmpty()) {
                    when {
                        search != null && (search.query.isNotBlank() || search.filters.isNotEmpty()) -> EmptyState(stringResource(R.string.no_results), icon = Symbols.Search, scrollable = true)
                        search != null -> Spacer(Modifier.fillMaxSize())
                        state.syncing -> EmptyState(stringResource(R.string.loading_inbox), scrollable = true)
                        else -> EmptyFolder(state.currentFolder)
                    }
                } else {
                    val listState = rememberLazyListState()
                    LaunchedEffect(search?.query) { if (search != null) listState.scrollToItem(0) }
                    LazyColumn(Modifier.fillMaxSize(), state = listState) {
                        items(items, key = { it.key }) { c ->
                            SwipeableRow(
                                c, state, selected = c.key == selectedKey || c.key in state.selection, selecting = selecting,
                                highlight = search?.query.orEmpty(),
                                onClick = { if (selecting) vm.toggleSelection(c) else onOpen(c) },
                                onLongClick = { vm.toggleSelection(c) },
                                onSwipeRead = { vm.setRead(listOf(c), c.unread) },
                                onSwipeDelete = { vm.delete(listOf(c)) },
                            )
                        }
                        item { Spacer(Modifier.height(88.dp)) }
                    }
                }
            }
        }
        if (!selecting && search == null) {
            ExtendedFloatingActionButton(
                onClick = onCompose,
                icon = { Icon(Symbols.Edit, null) },
                text = { Text(stringResource(R.string.compose)) },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            )
        }
    }
}

@Composable
private fun FolderHeader(state: MailUiState, vm: MailViewModel, onMenu: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    val folder = state.currentFolder
    val emptyable = folder?.role == FolderRole.TRASH || folder?.role == FolderRole.SPAM
    Surface(
        Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp).fillMaxWidth().height(56.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onMenu) { Icon(Symbols.Menu, stringResource(R.string.open_menu)) }
            Text(
                stringResource(R.string.search_hint),
                Modifier.weight(1f).clickable { vm.openSearch() }.padding(vertical = 16.dp),
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (emptyable) {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Symbols.MoreVert, stringResource(R.string.more)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(if (folder?.role == FolderRole.TRASH) R.string.empty_trash_action else R.string.empty_spam_action)) },
                            onClick = { menu = false; confirm = true },
                            enabled = (folder?.total ?: 0) > 0,
                        )
                    }
                }
            }
        }
    }
    Text(
        "${folderLabel(folder)} · ${state.activeAccount?.name.orEmpty()}",
        Modifier.padding(start = 16.dp, top = 14.dp, bottom = 6.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (confirm && folder != null) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(if (folder.role == FolderRole.TRASH) R.string.empty_trash_q else R.string.empty_spam_q)) },
            text = { Text(pluralStringResource(R.plurals.empty_confirm_body, folder.total, folder.total)) },
            confirmButton = { TextButton(onClick = { confirm = false; vm.emptyCurrentFolder() }) { Text(stringResource(if (folder.role == FolderRole.TRASH) R.string.empty_trash_action else R.string.empty_spam_action)) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun SearchHeader(search: SearchState, vm: MailViewModel) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Row(Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = vm::closeSearch) { Icon(Symbols.Back, stringResource(R.string.back)) }
        Box(Modifier.weight(1f)) {
            if (search.query.isEmpty()) Text(stringResource(R.string.search_hint), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            BasicTextField(
                search.query, { vm.updateSearch(query = it) },
                Modifier.fillMaxWidth().focusRequester(focus),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            )
        }
        if (search.query.isNotEmpty()) IconButton(onClick = { vm.updateSearch(query = "") }) { Icon(Symbols.Close, stringResource(R.string.clear)) }
    }
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            SearchFilter.UNREAD to R.string.filter_unread, SearchFilter.STARRED to R.string.filter_starred,
            SearchFilter.ATTACHMENT to R.string.filter_attachment, SearchFilter.THIS_FOLDER to R.string.filter_this_folder,
        ).forEach { (f, label) ->
            val on = f in search.filters
            FilterChip(selected = on, onClick = { vm.updateSearch(toggle = f) }, label = { Text(stringResource(label)) },
                leadingIcon = if (on) { { Icon(Symbols.Check, null, Modifier.size(18.dp)) } } else null)
        }
    }
    if (search.query.isNotBlank() || search.filters.isNotEmpty()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                pluralStringResource(if (SearchFilter.THIS_FOLDER in search.filters) R.plurals.results_count_folder else R.plurals.results_count, search.results.size, search.results.size),
                Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (search.serverBusy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun SelectionBar(state: MailUiState, vm: MailViewModel, onMoveRequest: (List<Conversation>) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val selected = vm.selectedConversations()
    val inSpam = state.currentFolder?.role == FolderRole.SPAM
    Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = vm::clearSelection) { Icon(Symbols.Close, stringResource(R.string.end_selection)) }
        Text(stringResource(R.string.selected_count, state.selection.size), Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.titleLarge)
        val anyUnread = selected.any { it.unread }
        IconButton(onClick = { vm.setRead(selected, anyUnread); vm.clearSelection() }) {
            Icon(if (anyUnread) Symbols.MarkRead else Symbols.Mail, stringResource(if (anyUnread) R.string.mark_read else R.string.mark_unread))
        }
        val allStarred = selected.isNotEmpty() && selected.all { it.flagged }
        IconButton(onClick = { selected.forEach { vm.setStar(it, !allStarred) }; vm.clearSelection() }) {
            Icon(if (allStarred) Symbols.StarFilled else Symbols.Star, stringResource(if (allStarred) R.string.unstar else R.string.star))
        }
        IconButton(onClick = { vm.delete(selected) }) { Icon(Symbols.Delete, stringResource(R.string.delete)) }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Symbols.MoreVert, stringResource(R.string.more)) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.move_to)) }, onClick = { menu = false; onMoveRequest(selected) })
                DropdownMenuItem(text = { Text(stringResource(if (inSpam) R.string.not_spam else R.string.mark_spam)) }, onClick = { menu = false; vm.spam(selected, !inSpam) })
                DropdownMenuItem(text = { Text(stringResource(R.string.select_all)) }, onClick = { menu = false; vm.selectAll() })
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SwipeableRow(
    c: Conversation, state: MailUiState, selected: Boolean, selecting: Boolean, highlight: String,
    onClick: () -> Unit, onLongClick: () -> Unit, onSwipeRead: () -> Unit, onSwipeDelete: () -> Unit,
) {
    val swipe = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> { onSwipeRead(); false } // row stays, only the state flips
                SwipeToDismissBoxValue.EndToStart -> { onSwipeDelete(); true }
                else -> false
            }
        },
    )
    SwipeToDismissBox(
        state = swipe,
        enableDismissFromStartToEnd = !selecting,
        enableDismissFromEndToStart = !selecting,
        backgroundContent = {
            val toDelete = swipe.dismissDirection == SwipeToDismissBoxValue.EndToStart
            Row(
                Modifier.fillMaxSize()
                    .background(if (toDelete) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primaryContainer)
                    .padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (toDelete) Arrangement.End else Arrangement.Start,
            ) {
                val tint = if (toDelete) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimaryContainer
                if (toDelete) {
                    Text(stringResource(R.string.swipe_delete), color = tint, fontWeight = FontWeight.Medium)
                    Icon(Symbols.Delete, null, Modifier.padding(start = 8.dp), tint = tint)
                } else {
                    Icon(if (c.unread) Symbols.MarkRead else Symbols.Mail, null, tint = tint)
                    Text(stringResource(if (c.unread) R.string.swipe_read else R.string.mark_unread), Modifier.padding(start = 8.dp), color = tint, fontWeight = FontWeight.Medium)
                }
            }
        },
    ) {
        ConversationRow(c, state, selected, selecting, highlight, onClick, onLongClick)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(c: Conversation, state: MailUiState, selected: Boolean, selecting: Boolean, highlight: String, onClick: () -> Unit, onLongClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val strong = if (c.unread) FontWeight.Bold else FontWeight.Normal
    val textColor = if (c.unread) colors.onSurface else colors.onSurfaceVariant
    val own = state.accounts.map { it.address.lowercase() }.toSet()
    // In "Sent" and "Drafts" the people it goes to matter, not oneself (as in Google's apps).
    val outgoing = state.currentFolder?.role.let { it == FolderRole.SENT || it == FolderRole.DRAFTS } && state.search == null
    val recipients = if (outgoing) Compose.parseAddresses(c.newest.recipients).first else emptyList()
    val senders = if (outgoing) stringResource(R.string.to_recipients, recipients.joinToString(", ") { it.short() }.ifEmpty { "…" })
    else Threading.senders(c, own, stringResource(R.string.me_sender))
    val yesterday = stringResource(R.string.yesterday)
    val folderTag = if (state.search != null) state.folder(c.inFolder.first().folderId) else null
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) colors.secondaryContainer else colors.surface)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selecting) {
            Box(
                Modifier.size(40.dp).background(if (selected) colors.primary else Color.Transparent, CircleShape)
                    .then(if (selected) Modifier else Modifier.background(colors.surfaceContainerHighest, CircleShape)),
                contentAlignment = Alignment.Center,
            ) { if (selected) Icon(Symbols.Check, null, tint = colors.onPrimary) }
        } else {
            // The other person in the conversation, even if the last word was one's own.
            val other = c.messages.lastOrNull { it.fromAddress.lowercase() !in own } ?: c.newest
            Avatar(recipients.firstOrNull()?.short() ?: other.fromName.ifBlank { other.fromAddress }, unreadDot = c.unread)
        }
        Column(Modifier.padding(start = 16.dp).weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text(senders, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyLarge, fontWeight = strong, color = textColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (c.messages.size > 1) Text(" · ${c.messages.size}", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                }
                Text(Dates.short(c.dateMillis, yesterday), Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelMedium, fontWeight = strong, color = textColor)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    highlighted(c.subject.ifBlank { stringResource(R.string.no_subject) }, highlight),
                    Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = strong, color = textColor, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (folderTag != null) {
                    Surface(shape = MaterialTheme.shapes.small, color = colors.surfaceContainerHigh, modifier = Modifier.padding(start = 6.dp)) {
                        Text(folderLabel(folderTag), Modifier.padding(horizontal = 6.dp, vertical = 1.dp), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    }
                }
                if (c.hasAttachment) Icon(Symbols.Attach, null, Modifier.padding(start = 4.dp).size(18.dp), tint = colors.onSurfaceVariant)
                if (c.flagged) Icon(Symbols.StarFilled, null, Modifier.padding(start = 2.dp).size(18.dp), tint = Color(0xFFE3A008))
            }
        }
    }
}

/** Search words are highlighted in yellow (Linux app). */
@Composable
private fun highlighted(text: String, query: String) = buildAnnotatedString {
    val words = query.split(Regex("\\s+")).filter { it.isNotBlank() }
    if (words.isEmpty()) { append(text); return@buildAnnotatedString }
    val regex = Regex(words.joinToString("|") { Regex.escape(it) }, RegexOption.IGNORE_CASE)
    var last = 0
    regex.findAll(text).forEach { m ->
        append(text.substring(last, m.range.first))
        withStyle(SpanStyle(background = Color(0xFFFDE293), color = Color(0xFF1F1F1F))) { append(m.value) }
        last = m.range.last + 1
    }
    append(text.substring(last))
}
