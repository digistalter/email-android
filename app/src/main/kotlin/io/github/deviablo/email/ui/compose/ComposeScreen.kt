// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.deviablo.email.ui.compose

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.OpenableColumns
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.deviablo.email.R
import io.github.deviablo.email.background.SyncWorker
import io.github.deviablo.email.data.Account
import io.github.deviablo.email.mail.Compose
import io.github.deviablo.email.mail.Draft
import io.github.deviablo.email.mail.FileAttachment
import io.github.deviablo.email.mail.Mailbox
import io.github.deviablo.email.mail.Original
import io.github.deviablo.email.mail.QuoteTexts
import io.github.deviablo.email.mail.ReplyKind
import io.github.deviablo.email.ui.ComposeRequest
import io.github.deviablo.email.ui.MailViewModel
import io.github.deviablo.email.ui.ReplyMode
import io.github.deviablo.email.ui.Snack
import io.github.deviablo.email.ui.Symbols
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.text.DateFormat
import java.util.Date

private const val MAX_ATTACHMENT_BYTES = 25L * 1024 * 1024
private const val AUTOSAVE_MS = 30_000L

/** What is compared to decide whether anything changed since the composer opened or last saved. */
private data class Snapshot(val to: List<Mailbox>, val cc: List<Mailbox>, val bcc: List<Mailbox>, val subject: String, val html: String, val files: List<String>)

private class ComposeModel(val account: Account, draft: Draft, val original: Original?, var kind: ReplyKind?, val savedBefore: Boolean) {
    val messageId = draft.messageId
    var to by mutableStateOf(draft.to)
    var cc by mutableStateOf(draft.cc)
    var bcc by mutableStateOf(draft.bcc)
    var toText by mutableStateOf("")
    var ccText by mutableStateOf("")
    var bccText by mutableStateOf("")
    var showCcBcc by mutableStateOf(draft.cc.isNotEmpty() || draft.bcc.isNotEmpty())
    var subject by mutableStateOf(draft.subject)
    var attachments by mutableStateOf(draft.attachments)
    var inReplyTo = draft.inReplyTo
    var references = draft.references
    var editorHtml by mutableStateOf(draft.html)
    var editorKey by mutableIntStateOf(0)
    var baseline: Snapshot? = null
    var lastSaved: Snapshot? = null
    var savedOnce = savedBefore

    fun commitTyped() {
        fun commit(text: String, list: List<Mailbox>): Pair<List<Mailbox>, String> {
            val (good, bad) = Compose.parseAddresses(text)
            return (list + good).distinctBy { it.email.lowercase() } to bad.joinToString(", ")
        }
        commit(toText, to).let { to = it.first; toText = it.second }
        commit(ccText, cc).let { cc = it.first; ccText = it.second }
        commit(bccText, bcc).let { bcc = it.first; bccText = it.second }
    }

    fun draft(html: String) = Draft(messageId, to, cc, bcc, subject, html, inReplyTo, references, attachments)
    fun snapshot(html: String) = Snapshot(to, cc, bcc, subject.trim(), html, attachments.map { it.name + it.data.size })
}

/**
 * Writing a new mail, a reply or forward, or continuing a draft. "From" is always the active
 * account. Saved as draft every 30 s and when leaving; ✕ in a reply discards it (wireframe).
 */
@Composable
fun ComposeScreen(vm: MailViewModel, request: ComposeRequest, onClose: () -> Unit, modifier: Modifier = Modifier) {
    // A new request (e.g. "Undo" reopening a draft) gets a fresh composer.
    androidx.compose.runtime.key(request) { ComposeContent(vm, request, onClose, modifier) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ComposeContent(vm: MailViewModel, request: ComposeRequest, onClose: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val controller = remember { EditorController() }
    val account = remember { vm.state.value.activeAccount }
    var model by remember { mutableStateOf<ComposeModel?>(null) }
    var failed by remember { mutableStateOf(false) }
    var known by remember { mutableStateOf<List<Mailbox>>(emptyList()) }
    var askNoSubject by remember { mutableStateOf(false) }
    var linkDialog by remember { mutableStateOf<String?>(null) }
    var savedHint by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    val isReply = request.reply != null || request.restoreKind != null

    val texts = QuoteTexts(
        wrote = stringResource(R.string.quote_wrote), forwarded = stringResource(R.string.forwarded_message),
        from = stringResource(R.string.header_from), date = stringResource(R.string.header_date),
        subject = stringResource(R.string.header_subject), to = stringResource(R.string.header_to),
        formatDate = { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it)) },
    )

    LaunchedEffect(request) {
        if (account == null) { onClose(); return@LaunchedEffect }
        val loaded = withContext(Dispatchers.IO) {
            known = runCatching { vm.repo.knownAddresses(account) }.getOrDefault(emptyList())
            runCatching {
                val kind = request.restoreKind ?: request.mode?.toKind()
                when {
                    request.restore != null -> ComposeModel(account, request.restore, request.restoreOriginal, kind, savedBefore = true)
                    request.draft != null -> vm.repo.draftFrom(request.draft, account)?.let { ComposeModel(account, it, null, null, savedBefore = true) }
                    request.reply != null -> vm.repo.original(request.reply)?.let { o ->
                        ComposeModel(account, Compose.replyDraft(o, kind ?: ReplyKind.REPLY, account.address, account.signature, texts), o, kind, savedBefore = false)
                    }
                    else -> ComposeModel(account, Compose.newDraft(account.address, account.signature), null, null, savedBefore = false)
                }
            }.getOrNull()
        }
        if (loaded == null) failed = true else model = loaded
    }

    val m = model

    suspend fun current(): Pair<Draft, Snapshot>? {
        val mm = model ?: return null
        val html = controller.html()
        return mm.draft(html) to mm.snapshot(html)
    }

    fun changed(s: Snapshot): Boolean {
        val mm = model ?: return false
        // Before the editor has rendered nothing can have been typed.
        val reference = mm.lastSaved ?: mm.baseline ?: return false
        return s != reference
    }

    suspend fun saveIfChanged(): Boolean {
        val mm = model ?: return false
        val (d, s) = current() ?: return false
        if (!changed(s)) return false
        withContext(Dispatchers.IO) { vm.repo.saveDraft(mm.account, d) }
        SyncWorker.now(context, mm.account.id)
        mm.lastSaved = s
        mm.savedOnce = true
        return true
    }

    fun reopen(d: Draft, mm: ComposeModel) = ComposeRequest(restore = d, restoreKind = mm.kind, restoreOriginal = mm.original)

    /** Back, or ✕ in a new mail: keep what was written as a draft. */
    fun closeKeeping() {
        if (closing) return
        closing = true
        scope.launch {
            val mm = model
            if (mm == null) { onClose(); return@launch }
            mm.commitTyped()
            val (d, _) = current() ?: run { onClose(); return@launch }
            if (saveIfChanged()) vm.snack(Snack(R.string.saved_as_draft, reopen = reopen(d, mm)))
            onClose()
        }
    }

    /** ✕ in a reply: throw it away (with undo, which reopens it). */
    fun discard() {
        if (closing) return
        closing = true
        scope.launch {
            val mm = model
            if (mm == null) { onClose(); return@launch }
            val (d, s) = current() ?: run { onClose(); return@launch }
            if (mm.savedOnce) withContext(Dispatchers.IO) { vm.repo.discardDraft(mm.account, mm.messageId) }
            if (mm.savedOnce || changed(s)) {
                SyncWorker.now(context, mm.account.id)
                vm.snack(Snack(R.string.discarded, reopen = reopen(d, mm)))
            }
            onClose()
        }
    }

    fun send(confirmedNoSubject: Boolean = false) {
        val mm = model ?: return
        mm.commitTyped()
        val leftovers = listOf(mm.toText, mm.ccText, mm.bccText).filter { it.isNotBlank() }
        if (leftovers.isNotEmpty()) {
            scope.launch { snackbar.showSnackbar(res.getString(R.string.invalid_recipients, leftovers.joinToString(", "))) }
            return
        }
        if (mm.to.isEmpty() && mm.cc.isEmpty() && mm.bcc.isEmpty()) {
            scope.launch { snackbar.showSnackbar(res.getString(R.string.no_recipients)) }
            return
        }
        if (mm.subject.isBlank() && !confirmedNoSubject) { askNoSubject = true; return }
        if (closing) return
        closing = true
        scope.launch {
            val (d, _) = current() ?: return@launch
            withContext(Dispatchers.IO) { vm.repo.send(mm.account, d) }
            SyncWorker.now(context, mm.account.id)
            vm.snack(Snack(R.string.sending))
            onClose()
        }
    }

    BackHandler { closeKeeping() }

    // Baseline once the editor has rendered (the browser normalises the HTML), then autosave.
    LaunchedEffect(m, m?.editorKey) {
        val mm = m ?: return@LaunchedEffect
        delay(700)
        if (mm.baseline == null || mm.lastSaved == null) {
            val html = controller.html()
            if (mm.baseline == null) mm.baseline = mm.snapshot(html)
        }
        if (isReply && mm.editorKey == 0 && request.restore == null) controller.focusStart()
        while (true) {
            delay(AUTOSAVE_MS)
            if (!closing && saveIfChanged()) {
                savedHint = true
                delay(2_000)
                savedHint = false
            }
        }
    }

    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val mm = model ?: return@rememberLauncherForActivityResult
        scope.launch {
            val files = withContext(Dispatchers.IO) { uris.map { readAttachment(context, it) } }
            if (files.any { it == null }) snackbar.showSnackbar(res.getString(R.string.file_unreadable))
            mm.attachments = mm.attachments + files.filterNotNull()
        }
    }
    val pickPicture = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val data = withContext(Dispatchers.IO) { pictureDataUri(context, uri) }
            if (data == null) snackbar.showSnackbar(res.getString(R.string.file_unreadable)) else controller.insertImage(data)
        }
    }

    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
            // App bar: ✕ left, attach and send right (Android convention, wireframe).
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (isReply) IconButton(onClick = ::discard) { Icon(Symbols.Close, stringResource(R.string.discard)) }
                else IconButton(onClick = ::closeKeeping) { Icon(Symbols.Close, stringResource(R.string.close_save_draft)) }
                Text(
                    when { m?.kind == ReplyKind.FORWARD -> stringResource(R.string.forward_title); isReply -> stringResource(R.string.reply_title); else -> "" },
                    Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.titleLarge, maxLines = 1,
                )
                if (savedHint) Text(stringResource(R.string.autosaved), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                IconButton(onClick = { pickFiles.launch(arrayOf("*/*")) }, enabled = m != null) { Icon(Symbols.Attach, stringResource(R.string.attach_file)) }
                IconButton(onClick = { send() }, enabled = m != null) { Icon(Symbols.Send, stringResource(R.string.send), tint = MaterialTheme.colorScheme.primary) }
            }
            when {
                failed -> Text(stringResource(R.string.message_unavailable), Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                m == null -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else -> {
                    Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                        if (m.original != null) {
                            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                for ((kind, label) in listOf(ReplyKind.REPLY to R.string.reply, ReplyKind.REPLY_ALL to R.string.reply_all, ReplyKind.FORWARD to R.string.forward)) {
                                    FilterChip(
                                        selected = m.kind == kind,
                                        onClick = { scope.launch { switchKind(m, kind, controller, texts) } },
                                        label = { Text(stringResource(label)) },
                                        leadingIcon = if (m.kind == kind) ({ Icon(Symbols.Check, null, Modifier.size(18.dp)) }) else null,
                                    )
                                }
                            }
                        }
                        if (!isReply) {
                            FieldRow(stringResource(R.string.compose_from)) {
                                Text(Mailbox(m.account.displayName, m.account.address).display(), Modifier.padding(vertical = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        RecipientRow(stringResource(R.string.compose_to), m.to, { m.to = it }, m.toText, { m.toText = it }, known) {
                            if (!m.showCcBcc) Text(
                                stringResource(R.string.show_cc_bcc), Modifier.clickable { m.showCcBcc = true }.padding(8.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        if (m.showCcBcc) {
                            val hide: @Composable () -> Unit = {
                                if (m.cc.isEmpty() && m.bcc.isEmpty() && m.ccText.isBlank() && m.bccText.isBlank()) {
                                    IconButton(onClick = { m.showCcBcc = false }) { Icon(Symbols.Close, stringResource(R.string.hide_field), Modifier.size(18.dp)) }
                                }
                            }
                            RecipientRow(stringResource(R.string.compose_cc), m.cc, { m.cc = it }, m.ccText, { m.ccText = it }, known, hide)
                            RecipientRow(stringResource(R.string.compose_bcc), m.bcc, { m.bcc = it }, m.bccText, { m.bccText = it }, known)
                        }
                        if (!isReply || m.kind == ReplyKind.FORWARD) {
                            FieldRow(stringResource(R.string.compose_subject)) {
                                BasicTextField(
                                    m.subject, { m.subject = it.replace('\n', ' ') }, Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary), singleLine = true,
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                                )
                            }
                        }
                        if (m.attachments.isNotEmpty()) {
                            FlowRow(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                m.attachments.forEach { a ->
                                    InputChip(
                                        selected = false, onClick = {},
                                        label = { Text("${a.name} · ${Formatter.formatShortFileSize(context, a.data.size.toLong())}", maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 240.dp)) },
                                        leadingIcon = { Icon(Symbols.File, null, Modifier.size(18.dp)) },
                                        trailingIcon = {
                                            Icon(Symbols.Close, stringResource(R.string.remove), Modifier.size(InputChipDefaults.IconSize).clickable { m.attachments = m.attachments - a })
                                        },
                                    )
                                }
                            }
                        }
                    }
                    HorizontalDivider()
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        androidx.compose.runtime.key(m.editorKey) {
                            RichEditor(m.editorHtml, controller, Modifier.fillMaxSize())
                        }
                    }
                    HorizontalDivider()
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { controller.command("bold") }) { Icon(Symbols.Bold, stringResource(R.string.format_bold)) }
                        IconButton(onClick = { controller.command("italic") }) { Icon(Symbols.Italic, stringResource(R.string.format_italic)) }
                        IconButton(onClick = { scope.launch { linkDialog = controller.selection().takeIf { it.startsWith("http") } ?: "https://" } }) {
                            Icon(Symbols.Link, stringResource(R.string.format_link))
                        }
                        IconButton(onClick = { controller.command("insertUnorderedList") }) { Icon(Symbols.BulletList, stringResource(R.string.format_list)) }
                        IconButton(onClick = { pickPicture.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                            Icon(Symbols.Image, stringResource(R.string.insert_image))
                        }
                    }
                }
            }
        }
        Box(Modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.BottomCenter) {
            SnackbarHost(snackbar, Modifier.padding(bottom = 56.dp))
        }
    }

    if (askNoSubject) {
        AlertDialog(
            onDismissRequest = { askNoSubject = false },
            title = { Text(stringResource(R.string.no_subject_title)) },
            text = { Text(stringResource(R.string.no_subject_body)) },
            confirmButton = { TextButton(onClick = { askNoSubject = false; send(confirmedNoSubject = true) }) { Text(stringResource(R.string.send)) } },
            dismissButton = { TextButton(onClick = { askNoSubject = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    linkDialog?.let { initial ->
        var url by remember(initial) { mutableStateOf(initial) }
        AlertDialog(
            onDismissRequest = { linkDialog = null },
            title = { Text(stringResource(R.string.link_title)) },
            text = {
                OutlinedTextField(url, { url = it.trim() }, label = { Text(stringResource(R.string.link_url)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            },
            confirmButton = {
                TextButton(onClick = {
                    linkDialog = null
                    if (url.startsWith("https://") || url.startsWith("http://") || url.startsWith("mailto:")) controller.command("createLink", url)
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { linkDialog = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

private fun ReplyMode.toKind() = when (this) {
    ReplyMode.REPLY -> ReplyKind.REPLY
    ReplyMode.REPLY_ALL -> ReplyKind.REPLY_ALL
    ReplyMode.FORWARD -> ReplyKind.FORWARD
}

/** Reply ↔ reply all ↔ forward: recipients and subject follow; the text is rebuilt only if untouched. */
private suspend fun switchKind(m: ComposeModel, kind: ReplyKind, controller: EditorController, texts: QuoteTexts) {
    val original = m.original ?: return
    if (m.kind == kind) return
    val fresh = Compose.replyDraft(original, kind, m.account.address, m.account.signature, texts, m.messageId)
    val untouched = m.baseline?.html == controller.html()
    m.kind = kind
    m.to = fresh.to; m.cc = fresh.cc; m.subject = fresh.subject
    m.inReplyTo = fresh.inReplyTo; m.references = fresh.references
    m.showCcBcc = m.showCcBcc || fresh.cc.isNotEmpty()
    val own = m.attachments.filter { a -> original.attachments.none { it === a } }
    m.attachments = if (kind == ReplyKind.FORWARD) original.attachments + own else own
    if (untouched) {
        m.editorHtml = fresh.html
        m.baseline = null
        m.editorKey++
    }
}

@Composable
private fun FieldRow(label: String, trailing: (@Composable () -> Unit)? = null, content: @Composable () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.width(64.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
            Box(Modifier.weight(1f)) { content() }
            trailing?.invoke()
        }
        HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** Recipients as chips; typed text becomes a chip at comma, space or "Next", with suggestions from known mail. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecipientRow(
    label: String,
    list: List<Mailbox>,
    onList: (List<Mailbox>) -> Unit,
    text: String,
    onText: (String) -> Unit,
    known: List<Mailbox>,
    trailing: (@Composable () -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    fun commit(value: String) {
        val (good, bad) = Compose.parseAddresses(value)
        if (good.isNotEmpty()) onList((list + good).distinctBy { it.email.lowercase() })
        onText(bad.joinToString(", "))
    }
    FieldRow(label, trailing) {
        FlowRow(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.Center) {
            list.forEach { mb ->
                InputChip(
                    selected = false, onClick = {},
                    label = { Text(mb.name.ifBlank { mb.email }, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp)) },
                    trailingIcon = { Icon(Symbols.Close, stringResource(R.string.remove), Modifier.size(InputChipDefaults.IconSize).clickable { onList(list - mb) }) },
                )
            }
            BasicTextField(
                text,
                { value ->
                    if (value.endsWith(",") || value.endsWith(";") || (value.endsWith(" ") && Compose.parseMailbox(value.trim()) != null)) commit(value.trimEnd(',', ';', ' '))
                    else onText(value)
                },
                // Takes the rest of the line next to the chips; wraps only when less than 80 dp are left.
                Modifier.widthIn(min = 80.dp).weight(1f).padding(vertical = 10.dp).onFocusChanged { f ->
                    if (focused && !f.isFocused && text.isNotBlank()) commit(text)
                    focused = f.isFocused
                },
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { commit(text) }),
            )
        }
    }
    if (focused && text.isNotBlank()) {
        val q = text.trim().lowercase()
        val hits = known.filter { k -> list.none { it.email.equals(k.email, true) } && matchesStart(k, q) }.take(4)
        Column {
            hits.forEach { k ->
                Column(
                    Modifier.fillMaxWidth().clickable { onList(list + k); onText("") }.padding(start = 80.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                ) {
                    if (k.name.isNotBlank()) Text(k.name, style = MaterialTheme.typography.bodyLarge)
                    Text(k.email, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private fun readAttachment(context: Context, uri: Uri): FileAttachment? = runCatching {
    val resolver = context.contentResolver
    var name = "attachment"
    var size = -1L
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            c.getString(0)?.let { name = it }
            if (!c.isNull(1)) size = c.getLong(1)
        }
    }
    if (size > MAX_ATTACHMENT_BYTES) return null
    val data = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    if (data.size > MAX_ATTACHMENT_BYTES) return null
    val type = resolver.getType(uri)?.takeIf { it.contains('/') } ?: Compose.mimeTypeFor(name)
    FileAttachment(name, type, data)
}.getOrNull()

/** A picture for the text: turned the right way up and at most 1600 px wide or high, as data: URL. */
private fun pictureDataUri(context: Context, uri: Uri): String? = runCatching {
    val source = ImageDecoder.createSource(context.contentResolver, uri)
    var hasAlpha = false
    val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        val w = info.size.width
        val h = info.size.height
        val scale = minOf(1f, 1600f / maxOf(w, h))
        decoder.setTargetSize(maxOf(1, (w * scale).toInt()), maxOf(1, (h * scale).toInt()))
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        hasAlpha = info.mimeType == "image/png" || info.mimeType == "image/webp" || info.mimeType == "image/gif"
    }
    val out = ByteArrayOutputStream()
    val (format, mime) = if (hasAlpha && bitmap.hasAlpha()) Bitmap.CompressFormat.PNG to "image/png" else Bitmap.CompressFormat.JPEG to "image/jpeg"
    bitmap.compress(format, 85, out)
    bitmap.recycle()
    "data:$mime;base64," + java.util.Base64.getEncoder().encodeToString(out.toByteArray())
}.getOrNull()

/** A suggestion fits when a word of the name or a part of the address starts with what was typed, not just any letters. */
private fun matchesStart(k: Mailbox, q: String): Boolean {
    val words = k.name.lowercase().split(' ', '-', '.') + k.email.lowercase().split('@', '.', '-', '_', '+')
    return k.email.lowercase().startsWith(q) || k.name.lowercase().startsWith(q) || words.any { it.startsWith(q) }
}
