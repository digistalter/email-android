// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.digistalter.email.ui

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.print.PrintAttributes
import android.print.PrintManager
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import io.github.digistalter.email.R
import io.github.digistalter.email.data.Conversation
import io.github.digistalter.email.data.FolderRole
import io.github.digistalter.email.data.StoredMessage
import io.github.digistalter.email.data.Threading
import io.github.digistalter.email.mail.Attachment
import io.github.digistalter.email.mail.HtmlSafety
import io.github.digistalter.email.mail.ParsedMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

enum class ReplyMode { REPLY, REPLY_ALL, FORWARD }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderPane(
    c: Conversation,
    state: MailUiState,
    vm: MailViewModel,
    dark: Boolean,
    showBack: Boolean,
    onBack: () -> Unit,
    onReply: (StoredMessage, ReplyMode) -> Unit,
    onMoveRequest: (List<Conversation>) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val folder = state.currentFolder
    // Exactly one message is open (wireframe); it starts with the oldest unread, else the newest.
    var openId by remember(c.key) { mutableStateOf((c.messages.firstOrNull { !it.seen } ?: c.newest).id) }
    var sheet by remember { mutableStateOf(false) }
    var source by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val inSpam = folder?.role == FolderRole.SPAM

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (showBack) IconButton(onClick = onBack) { Icon(Symbols.Back, stringResource(R.string.back)) }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { vm.delete(listOf(c)) }) { Icon(Symbols.Delete, stringResource(R.string.delete)) }
            IconButton(onClick = { sheet = true }) { Icon(Symbols.MoreVert, stringResource(R.string.more)) }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(Threading.displaySubject(c.subject).ifBlank { stringResource(R.string.no_subject) }, style = MaterialTheme.typography.headlineSmall)
            Surface(shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, colors.outlineVariant)) {
                Text(folderLabel(state.folder(c.inFolder.first().folderId) ?: folder), Modifier.padding(horizontal = 8.dp, vertical = 3.dp), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            }
            // Only this account counts as "me" (strict separation of accounts).
            val own = state.accounts.firstOrNull { it.id == c.newest.accountId }?.address?.lowercase()
            val me = stringResource(R.string.me_sender)
            fun nameOf(m: StoredMessage) = if (m.fromAddress.lowercase() == own) me else m.fromName.ifBlank { m.fromAddress }
            c.messages.forEach { m ->
                val isNewest = m.id == c.newest.id
                if (m.id == openId) {
                    OpenMessage(m, nameOf(m), isNewest, c.messages.size > 1, vm, dark, onHeader = { if (!isNewest) openId = c.newest.id }, onReply = onReply)
                } else {
                    FoldedMessage(m, nameOf(m), onClick = { openId = m.id })
                }
            }
            val replyTarget = c.messages.lastOrNull { it.fromAddress.lowercase() != own } ?: c.newest
            Surface(
                Modifier.fillMaxWidth().padding(top = 4.dp).clickable { onReply(replyTarget, ReplyMode.REPLY) },
                shape = CircleShape, color = colors.surfaceContainerHigh,
            ) {
                Text(stringResource(R.string.reply_to, replyTarget.fromName.ifBlank { replyTarget.fromAddress }), Modifier.padding(horizontal = 20.dp, vertical = 14.dp), color = colors.onSurfaceVariant)
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (sheet) {
        ModalBottomSheet(onDismissRequest = { sheet = false }) {
            val m = c.newest
            val item: @Composable (ImageVector, Int, () -> Unit) -> Unit = { icon, label, action ->
                Row(
                    Modifier.fillMaxWidth().clickable { sheet = false; action() }.padding(horizontal = 24.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(icon, null, tint = colors.onSurfaceVariant)
                    Text(stringResource(label), Modifier.padding(start = 16.dp), style = MaterialTheme.typography.bodyLarge)
                }
            }
            item(Symbols.Reply, R.string.reply) { onReply(m, ReplyMode.REPLY) }
            item(Symbols.ReplyAll, R.string.reply_all) { onReply(m, ReplyMode.REPLY_ALL) }
            item(Symbols.Forward, R.string.forward) { onReply(m, ReplyMode.FORWARD) }
            HorizontalDivider()
            item(Symbols.Mail, R.string.mark_unread) { vm.setRead(listOf(c), false); onBack() }
            item(if (c.flagged) Symbols.StarFilled else Symbols.Star, if (c.flagged) R.string.unstar else R.string.star) { vm.setStar(c, !c.flagged) }
            HorizontalDivider()
            item(Symbols.Folder, R.string.move_to) { onMoveRequest(listOf(c)) }
            item(Symbols.Spam, if (inSpam) R.string.not_spam else R.string.mark_spam) { vm.spam(listOf(c), !inSpam) }
            item(Symbols.Code, R.string.show_source) {
                scope.launch { source = withContext(Dispatchers.IO) { vm.repo.bodies.get(m.id)?.toString(Charsets.UTF_8) } ?: "" }
            }
            item(Symbols.Print, R.string.print) { scope.launch { printConversation(context, vm, c) } }
            HorizontalDivider()
            item(Symbols.Delete, R.string.delete) { vm.delete(listOf(c)) }
            Spacer(Modifier.height(16.dp))
        }
    }
    source?.let { text ->
        AlertDialog(
            onDismissRequest = { source = null },
            title = { Text(stringResource(R.string.source_title)) },
            text = { Text(text.ifEmpty { stringResource(R.string.message_unavailable) }, Modifier.verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) },
            confirmButton = { TextButton(onClick = { source = null }) { Text(stringResource(R.string.form_cancel)) } },
        )
    }
}

@Composable
private fun FoldedMessage(m: StoredMessage, name: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxWidth().clickable(onClick = onClick), shape = RoundedCornerShape(16.dp), color = colors.surfaceContainer) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(m.fromName.ifBlank { m.fromAddress }, size = 32, unreadDot = !m.seen)
            // Name and date only, as in the Linux app; the subject is already the title above.
            Text(name, Modifier.padding(start = 12.dp).weight(1f), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(Dates.short(m.dateMillis, stringResource(R.string.yesterday)), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun OpenMessage(m: StoredMessage, name: String, isNewest: Boolean, showNewestBadge: Boolean, vm: MailViewModel, dark: Boolean, onHeader: () -> Unit, onReply: (StoredMessage, ReplyMode) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    var parsed by remember(m.id) { mutableStateOf<ParsedMessage?>(null) }
    var failed by remember(m.id) { mutableStateOf(false) }
    var showImagesOnce by remember(m.id) { mutableStateOf(false) }
    var allowedForDomain by remember(m.id) { mutableStateOf(false) }
    val domain = HtmlSafety.senderDomain(m.fromAddress)
    LaunchedEffect(m.id) {
        val result = withContext(Dispatchers.IO) {
            allowedForDomain = vm.repo.imagesAllowed(m.accountId, domain)
            runCatching { vm.repo.loadMessage(m) }.onFailure { android.util.Log.i("Reader", "message ${m.id}: ${it.javaClass.simpleName}") }.getOrNull()
        }
        parsed = result
        failed = result == null
    }
    val me = stringResource(R.string.me)
    val accounts by remember(vm) { vm.state.map { it.accounts }.distinctUntilChanged() }.collectAsState(emptyList())
    val ownAddresses = accounts.filter { it.id == m.accountId }.map { it.address.lowercase() }
    val recipients = m.recipients.split(", ").filter { it.isNotBlank() }.joinToString(", ") { r ->
        if (ownAddresses.any { r.lowercase().contains(it) }) me else r.substringBefore(" <")
    }.ifBlank { me }
    val fullDate = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(m.dateMillis))

    Surface(
        Modifier.fillMaxWidth().animateContentSize(),
        shape = RoundedCornerShape(16.dp),
        color = colors.surface,
        border = if (isNewest) BorderStroke(2.dp, colors.primary) else BorderStroke(1.dp, colors.outlineVariant),
    ) {
        Column {
            Row(Modifier.clickable(onClick = onHeader).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(m.fromName.ifBlank { m.fromAddress }, size = 32)
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    Text(name, fontWeight = FontWeight.Medium)
                    Text(stringResource(R.string.to_line, recipients, fullDate), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (isNewest && showNewestBadge) {
                    Surface(shape = CircleShape, color = colors.primary) {
                        Text(stringResource(R.string.newest), Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = colors.onPrimary)
                    }
                }
            }
            val p = parsed
            when {
                p != null -> {
                    val allow = showImagesOnce || allowedForDomain
                    val prepared = remember(p, allow, dark) {
                        p.html?.let { HtmlSafety.prepare(it, allow, dark, p.inlineResolver()) } ?: HtmlSafety.Result(HtmlSafety.fromText(p.text.orEmpty(), dark), false)
                    }
                    if (prepared.blockedImages) {
                        Surface(Modifier.padding(horizontal = 12.dp).fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = colors.surfaceContainer) {
                            Column(Modifier.padding(12.dp)) {
                                Text(stringResource(R.string.images_blocked), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                                FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = { showImagesOnce = true }) { Text(stringResource(R.string.show_once)) }
                                    OutlinedButton(onClick = {
                                        allowedForDomain = true
                                        vm.viewModelScopeLaunchIo { vm.repo.allowImages(m.accountId, domain) }
                                    }) { Text(stringResource(R.string.always_for, domain)) }
                                }
                            }
                        }
                    }
                    MailWebView(prepared.html, p, allowNetwork = allow, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp))
                    if (p.attachments.isNotEmpty()) AttachmentChips(m, p, vm)
                }
                failed -> Text(stringResource(R.string.message_unavailable), Modifier.padding(16.dp), color = colors.onSurfaceVariant)
                else -> Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.loading_message), Modifier.padding(start = 12.dp), color = colors.onSurfaceVariant)
                }
            }
            HorizontalDivider(Modifier.padding(top = 8.dp), color = colors.outlineVariant)
            Row(Modifier.padding(horizontal = 4.dp)) {
                IconButton(onClick = { onReply(m, ReplyMode.REPLY) }) { Icon(Symbols.Reply, stringResource(R.string.reply)) }
                IconButton(onClick = { onReply(m, ReplyMode.REPLY_ALL) }) { Icon(Symbols.ReplyAll, stringResource(R.string.reply_all)) }
                IconButton(onClick = { onReply(m, ReplyMode.FORWARD) }) { Icon(Symbols.Forward, stringResource(R.string.forward)) }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { vm.setStar(m, !m.flagged) }) { Icon(if (m.flagged) Symbols.StarFilled else Symbols.Star, stringResource(if (m.flagged) R.string.unstar else R.string.star)) }
                IconButton(onClick = { vm.setReadMessage(m, !m.seen) }) { Icon(if (m.seen) Symbols.Mail else Symbols.MarkRead, stringResource(if (m.seen) R.string.mark_unread else R.string.mark_read)) }
                IconButton(onClick = { vm.deleteMessage(m) }) { Icon(Symbols.Delete, stringResource(R.string.delete)) }
            }
        }
    }
    @Suppress("UNUSED_VARIABLE") val unused = context
}

@Composable
private fun AttachmentChips(m: StoredMessage, p: ParsedMessage, vm: MailViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingSave by remember { mutableStateOf<Attachment?>(null) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val a = pendingSave ?: return@rememberLauncherForActivityResult
        if (uri != null) scope.launch(Dispatchers.IO) {
            context.contentResolver.openOutputStream(uri)?.use { it.write(p.part(a.path)?.bytes() ?: ByteArray(0)) }
        }
        pendingSave = null
    }
    Row(Modifier.padding(horizontal = 12.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        p.attachments.forEach { a ->
            AssistChip(
                onClick = { scope.launch { openAttachment(context, vm, m, p, a) } },
                label = { Text("${a.filename} · ${formatSize(a.size)}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Icon(if (a.mimeType.startsWith("image/")) Symbols.Image else Symbols.File, null, Modifier.size(18.dp)) },
                trailingIcon = {
                    Icon(Symbols.Download, stringResource(R.string.save), Modifier.size(18.dp).clickable { pendingSave = a; saver.launch(a.filename) })
                },
            )
        }
    }
}

private fun formatSize(bytes: Int): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
}

/** Opens with a fitting app; the file goes to a private cache folder that is cleared after a day. */
private suspend fun openAttachment(context: Context, vm: MailViewModel, m: StoredMessage, p: ParsedMessage, a: Attachment) {
    val file = withContext(Dispatchers.IO) {
        val dir = File(vm.repo.bodies.attachmentDir, m.id.toString()).apply { mkdirs() }
        val safeName = a.filename.replace(Regex("[/\\\\:*?\"<>|\\x00-\\x1f]"), "_").ifBlank { "attachment" }
        File(dir, safeName).apply { writeBytes(p.part(a.path)?.bytes() ?: ByteArray(0)) }
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, a.mimeType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.no_app_for_file, Toast.LENGTH_SHORT).show()
    }
}

/**
 * Shows prepared mail HTML. JavaScript is off, nothing is loaded from the net unless images were
 * allowed, inline images (cid:) come from the message itself, links open in the browser.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MailWebView(html: String, parsed: ParsedMessage?, allowNetwork: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var warnLink by remember { mutableStateOf<Pair<String, String>?>(null) }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                settings.javaScriptEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.domStorageEnabled = false
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = false
                settings.textZoom = 100
                isVerticalScrollBarEnabled = false
            }
        },
        update = { web ->
            web.settings.blockNetworkLoads = !allowNetwork
            web.settings.blockNetworkImage = !allowNetwork
            web.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val url = request.url
                    if (url.scheme == "cid") {
                        val part = parsed?.inlineImage(url.schemeSpecificPart) ?: return WebResourceResponse("text/plain", "utf-8", null)
                        return WebResourceResponse(part.mimeType, null, part.bytes().inputStream())
                    }
                    if (!allowNetwork && (url.scheme == "http" || url.scheme == "https")) return WebResourceResponse("text/plain", "utf-8", null)
                    return null
                }

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val href = request.url.toString()
                    val warning = HtmlSafety.linkWarning(href)
                    if (warning != null) warnLink = href to warning else openLink(context, href)
                    return true
                }
            }
            if (web.tag != html) {
                web.tag = html
                web.loadDataWithBaseURL("about:blank", html, "text/html", "utf-8", null)
            }
        },
    )
    warnLink?.let { (href, host) ->
        AlertDialog(
            onDismissRequest = { warnLink = null },
            icon = { Icon(Symbols.Warning, null) },
            title = { Text(stringResource(R.string.link_warning_title)) },
            text = { Text(stringResource(R.string.link_warning_body, host)) },
            confirmButton = { TextButton(onClick = { warnLink = null; openLink(context, href) }) { Text(stringResource(R.string.open_link)) } },
            dismissButton = { TextButton(onClick = { warnLink = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

private fun openLink(context: Context, href: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(href)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** The whole conversation as one document, without external images, via the system print dialog. */
private suspend fun printConversation(context: Context, vm: MailViewModel, c: Conversation) {
    val parts = withContext(Dispatchers.IO) {
        c.messages.map { m ->
            val p = runCatching { vm.repo.loadMessage(m) }.getOrNull()
            val body = p?.html?.let { HtmlSafety.prepare(it, false, false, p.inlineResolver()).html.substringAfter("<body>").substringBeforeLast("</body>") }
                ?: (p?.text ?: "").replace("&", "&amp;").replace("<", "&lt;").let { "<pre style=\"white-space:pre-wrap\">$it</pre>" }
            val head = "<p><b>${esc(m.fromName.ifBlank { m.fromAddress })}</b> &lt;${esc(m.fromAddress)}&gt;<br>${esc(DateFormat.getDateTimeInstance().format(Date(m.dateMillis)))}</p>"
            head + body
        }
    }
    val html = "<html><head><meta charset=\"utf-8\"></head><body style=\"font-family:sans-serif\"><h2>${esc(c.subject)}</h2>${parts.joinToString("<hr>")}</body></html>"
    val web = WebView(context)
    web.settings.blockNetworkLoads = true
    web.settings.javaScriptEnabled = false
    web.webViewClient = object : WebViewClient() {
        override fun onPageFinished(view: WebView, url: String?) {
            val pm = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
            pm.print(c.subject.ifBlank { "E-Mail" }, view.createPrintDocumentAdapter(c.subject), PrintAttributes.Builder().build())
        }
    }
    web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
}

private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

private fun ParsedMessage.inlineResolver(): (String) -> Pair<String, ByteArray>? = { cid ->
    inlineImage(cid)?.let { it.mimeType to it.bytes() }
}
