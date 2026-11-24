// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.digistalter.email.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.digistalter.email.R
import io.github.digistalter.email.data.Folder
import io.github.digistalter.email.data.FolderRole

private val AvatarColors = listOf(0xFF7A6FF0, 0xFF2E9D76, 0xFFD9783B, 0xFFC2457A, 0xFF3B82C4, 0xFF8A8A85, 0xFFB3952B)

/** Circle with initials; the colour follows from the name (like libadwaita in the Linux app). */
@Composable
fun Avatar(name: String, modifier: Modifier = Modifier, unreadDot: Boolean = false, size: Int = 40) {
    val initials = name.split(Regex("[\\s,.@]+")).filter { it.isNotEmpty() && it.first().isLetterOrDigit() }.take(2)
        .joinToString("") { it.first().uppercase() }.ifEmpty { "?" }
    val color = Color(AvatarColors[(name.sumOf { it.code }) % AvatarColors.size])
    Box(modifier.size(size.dp)) {
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
fun EmptyState(title: String, body: String = "", icon: ImageVector = Symbols.Mail, scrollable: Boolean = false) {
    Column(
        Modifier.fillMaxSize().then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(96.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(title, Modifier.padding(top = 16.dp), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        if (body.isNotEmpty()) Text(body, Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
fun folderLabel(folder: Folder?): String = when (folder?.role) {
    null, FolderRole.INBOX -> stringResource(R.string.folder_inbox)
    FolderRole.DRAFTS -> stringResource(R.string.folder_drafts)
    FolderRole.SENT -> stringResource(R.string.folder_sent)
    FolderRole.ARCHIVE -> stringResource(R.string.folder_archive)
    FolderRole.SPAM -> stringResource(R.string.folder_spam)
    FolderRole.TRASH -> stringResource(R.string.folder_trash)
    FolderRole.OTHER -> folder.name
}

fun roleIcon(role: FolderRole): ImageVector = when (role) {
    FolderRole.INBOX -> Symbols.Inbox
    FolderRole.DRAFTS -> Symbols.Drafts
    FolderRole.SENT -> Symbols.Sent
    FolderRole.ARCHIVE -> Symbols.Archive
    FolderRole.SPAM -> Symbols.Spam
    FolderRole.TRASH -> Symbols.Delete
    FolderRole.OTHER -> Symbols.Folder
}

/** Empty folder texts from the Linux app, one per folder kind. */
@Composable
fun EmptyFolder(folder: Folder?) {
    val (title, body) = when (folder?.role) {
        null, FolderRole.INBOX -> R.string.empty_inbox to R.string.empty_inbox_body
        FolderRole.DRAFTS -> R.string.empty_drafts to R.string.empty_drafts_body
        FolderRole.SENT -> R.string.empty_sent to null
        FolderRole.ARCHIVE -> R.string.empty_archive to null
        FolderRole.SPAM -> R.string.empty_spam to R.string.empty_spam_body
        FolderRole.TRASH -> R.string.empty_trash to null
        FolderRole.OTHER -> R.string.empty_other to null
    }
    EmptyState(stringResource(title), body?.let { stringResource(it) }.orEmpty(), roleIcon(folder?.role ?: FolderRole.INBOX), scrollable = true)
}
