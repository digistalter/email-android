// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.deviablo.email.ui.settings

import android.app.LocaleManager
import android.content.Intent
import android.os.Build
import android.os.LocaleList
import android.provider.Settings as SystemSettings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import io.github.deviablo.email.R
import io.github.deviablo.email.background.PushService
import io.github.deviablo.email.data.Account
import io.github.deviablo.email.data.AutoEmpty
import io.github.deviablo.email.mail.imap.Security
import io.github.deviablo.email.setup.AccountCheck
import io.github.deviablo.email.setup.DiscoveredSettings
import io.github.deviablo.email.setup.ServerConfig
import io.github.deviablo.email.setup.ServerFields
import io.github.deviablo.email.ui.Avatar
import io.github.deviablo.email.ui.MailViewModel
import io.github.deviablo.email.ui.Symbols
import io.github.deviablo.email.ui.compose.EditorController
import io.github.deviablo.email.ui.compose.RichEditor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

private sealed interface Page {
    data object Main : Page
    data class EditAccount(val id: Long) : Page
    data class Servers(val id: Long) : Page
    data class Signature(val id: Long) : Page
}

/** One long list (wireframe): accounts, general, signature. Account editing is a sub-page. */
@Composable
fun SettingsScreen(vm: MailViewModel, onBack: () -> Unit, onAddAccount: () -> Unit) {
    var page by remember { mutableStateOf<Page>(Page.Main) }
    val state by vm.state.collectAsState()
    BackHandler { if (page == Page.Main) onBack() else page = Page.Main }
    val account = when (val p = page) {
        is Page.EditAccount -> state.accounts.firstOrNull { it.id == p.id }
        is Page.Servers -> state.accounts.firstOrNull { it.id == p.id }
        is Page.Signature -> state.accounts.firstOrNull { it.id == p.id }
        Page.Main -> null
    }
    when (val p = page) {
        Page.Main -> MainPage(vm, onBack, onAddAccount, onAccount = { page = Page.EditAccount(it) }, onSignature = { page = Page.Signature(it) })
        is Page.EditAccount -> account?.let { AccountPage(vm, it, onBack = { page = Page.Main }, onServers = { page = Page.Servers(p.id) }) } ?: run { page = Page.Main }
        is Page.Servers -> account?.let { ServersPage(vm, it, onBack = { page = Page.EditAccount(p.id) }) } ?: run { page = Page.Main }
        is Page.Signature -> account?.let { SignaturePage(vm, it, onBack = { page = Page.Main }) } ?: run { page = Page.Main }
    }
}

@Composable
private fun PageFrame(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
            Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Symbols.Back, stringResource(R.string.back)) }
                Text(title, Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                actions()
            }
            content()
        }
    }
}

@Composable
private fun Group(title: String) {
    Text(title, Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun PrefRow(title: String, subtitle: String? = null, enabled: Boolean = true, leading: (@Composable () -> Unit)? = null, trailing: (@Composable () -> Unit)? = null, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).then(if (onClick != null && enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) { leading(); Box(Modifier.size(16.dp)) }
        Column(Modifier.weight(1f).padding(end = if (trailing != null) 16.dp else 0.dp)) {
            val alpha = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            Text(title, style = MaterialTheme.typography.bodyLarge, color = alpha)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing?.invoke()
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) =
    PrefRow(title, subtitle, enabled, trailing = { Switch(checked, onChange, enabled = enabled) }, onClick = { onChange(!checked) })

@Composable
private fun <T> ChoiceDialog(title: String, options: List<Pair<T, String>>, selected: T, onPick: (T) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { (value, label) ->
                    Row(
                        Modifier.fillMaxWidth().selectable(value == selected, onClick = { onPick(value); onDismiss() }).padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(value == selected, null)
                        Text(label, Modifier.padding(start = 16.dp))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun MainPage(vm: MailViewModel, onBack: () -> Unit, onAddAccount: () -> Unit, onAccount: (Long) -> Unit, onSignature: (Long) -> Unit) {
    val context = LocalContext.current
    val state by vm.state.collectAsState()
    val settings = vm.repo.settings
    // Re-read on every change so the switches follow.
    var tick by remember { mutableIntStateOf(0) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var permitted by remember { mutableStateOf(true) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        // Coming back from the system settings: the permission may have changed.
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                permitted = NotificationManagerCompat.from(context).areNotificationsEnabled()
                tick++
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    fun changed() { tick++; vm.settingsChanged() }

    PageFrame(stringResource(R.string.settings), onBack) {
        Column(Modifier.verticalScroll(rememberScrollState())) { androidx.compose.runtime.key(tick) {
            Group(stringResource(R.string.prefs_accounts))
            state.accounts.forEach { a ->
                PrefRow(
                    a.name, if (a.id == state.activeAccountId) stringResource(R.string.prefs_active, a.address) else a.address,
                    leading = { Avatar(a.name, size = 40) }, trailing = { Icon(Symbols.ChevronRight, null) }, onClick = { onAccount(a.id) },
                )
            }
            PrefRow(stringResource(R.string.add_account), leading = { Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { Icon(Symbols.Add, null) } }, onClick = onAddAccount)
            HorizontalDivider(Modifier.padding(top = 8.dp))

            Group(stringResource(R.string.prefs_general))
            if (Build.VERSION.SDK_INT >= 33) {
                val lm = context.getSystemService(LocaleManager::class.java)
                val current = lm.applicationLocales.takeIf { !it.isEmpty }?.get(0)?.language ?: ""
                val labels = mapOf("" to stringResource(R.string.prefs_language_system), "en" to stringResource(R.string.prefs_language_en), "de" to stringResource(R.string.prefs_language_de))
                PrefRow(stringResource(R.string.prefs_language), labels[current] ?: labels.getValue(""), onClick = { dialog = "language" })
                if (dialog == "language") {
                    ChoiceDialog(stringResource(R.string.prefs_language), labels.toList(), current, { tag ->
                        lm.applicationLocales = if (tag.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
                    }, { dialog = null })
                }
            }
            SwitchRow(stringResource(R.string.prefs_group), null, settings.groupConversations) { settings.groupConversations = it; changed() }
            SwitchRow(
                stringResource(R.string.prefs_notify),
                stringResource(if (permitted || !settings.notifications) R.string.prefs_notify_sub else R.string.prefs_notify_blocked),
                settings.notifications && permitted,
            ) { on ->
                if (on && !permitted) {
                    context.startActivity(Intent(SystemSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(SystemSettings.EXTRA_APP_PACKAGE, context.packageName))
                }
                settings.notifications = on
                PushService.update(context)
                changed()
            }
            SwitchRow(stringResource(R.string.prefs_push), stringResource(R.string.prefs_push_sub), settings.instantPush, enabled = settings.notifications && permitted) {
                settings.instantPush = it
                PushService.update(context)
                changed()
            }
            val images = listOf(false to stringResource(R.string.prefs_images_ask), true to stringResource(R.string.prefs_images_show))
            PrefRow(stringResource(R.string.prefs_images), images.first { it.first == settings.alwaysShowImages }.second, onClick = { dialog = "images" })
            if (dialog == "images") ChoiceDialog(stringResource(R.string.prefs_images), images, settings.alwaysShowImages, { settings.alwaysShowImages = it; changed() }, { dialog = null })
            val empty = listOf(AutoEmpty.NEVER to stringResource(R.string.prefs_never), AutoEmpty.AFTER_30_DAYS to stringResource(R.string.prefs_30_days))
            PrefRow(stringResource(R.string.prefs_trash), empty.first { it.first == settings.emptyTrash }.second, onClick = { dialog = "trash" })
            if (dialog == "trash") ChoiceDialog(stringResource(R.string.prefs_trash), empty, settings.emptyTrash, { settings.emptyTrash = it; changed() }, { dialog = null })
            PrefRow(stringResource(R.string.prefs_spam), empty.first { it.first == settings.emptySpam }.second, onClick = { dialog = "spam" })
            if (dialog == "spam") ChoiceDialog(stringResource(R.string.prefs_spam), empty, settings.emptySpam, { settings.emptySpam = it; changed() }, { dialog = null })
            HorizontalDivider(Modifier.padding(top = 8.dp))

            Group(stringResource(R.string.prefs_signature))
            state.accounts.forEach { a ->
                val preview = Jsoup.parse(a.signature).text().take(80)
                PrefRow(stringResource(R.string.prefs_signature_for, a.name), preview.ifBlank { stringResource(R.string.prefs_signature_none) },
                    trailing = { Icon(Symbols.ChevronRight, null) }, onClick = { onSignature(a.id) })
            }
            Box(Modifier.height(32.dp))
        } }
    }
}

@Composable
private fun AccountPage(vm: MailViewModel, account: Account, onBack: () -> Unit, onServers: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val res = LocalResources.current
    var name by remember(account.id) { mutableStateOf(account.displayName) }
    var label by remember(account.id) { mutableStateOf(account.name) }
    var askRemove by remember { mutableStateOf(false) }
    var passwordDialog by remember { mutableStateOf(false) }
    // Names are saved when leaving the page, like the system settings do.
    fun save() {
        if (name != account.displayName || label != account.name) {
            vm.updateAccount(account.copy(displayName = name.trim(), name = label.trim().ifBlank { account.name }))
        }
    }
    BackHandler { save(); onBack() }
    PageFrame(stringResource(R.string.account_edit), onBack = { save(); onBack() }) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(top = 8.dp)) {
            Text(account.address, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), label = { Text(stringResource(R.string.account_display_name)) }, singleLine = true)
            OutlinedTextField(label, { label = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp), label = { Text(stringResource(R.string.account_label)) }, singleLine = true)
            Box(Modifier.height(8.dp))
            SwitchRow(stringResource(R.string.account_notify), null, account.notify) {
                vm.updateAccount(account.copy(notify = it))
                PushService.update(context)
            }
            PrefRow(stringResource(R.string.account_password), stringResource(R.string.account_password_sub), onClick = { passwordDialog = true })
            PrefRow(stringResource(R.string.account_servers), "${account.imapHost} · ${account.smtpHost}", trailing = { Icon(Symbols.ChevronRight, null) }, onClick = { save(); onServers() })
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            PrefRow(stringResource(R.string.account_remove), leading = { Icon(Symbols.Delete, null, tint = MaterialTheme.colorScheme.error) }, onClick = { askRemove = true })
        }
    }
    if (askRemove) {
        AlertDialog(
            onDismissRequest = { askRemove = false },
            title = { Text(stringResource(R.string.account_remove_q, account.name)) },
            text = { Text(stringResource(R.string.account_remove_body)) },
            confirmButton = { TextButton(onClick = { askRemove = false; scope.launch { vm.removeAccount(account.id); onBack() } }) { Text(stringResource(R.string.account_remove)) } },
            dismissButton = { TextButton(onClick = { askRemove = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (passwordDialog) {
        var password by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { if (!busy) passwordDialog = false },
            title = { Text(stringResource(R.string.account_password)) },
            text = {
                Column {
                    OutlinedTextField(password, { password = it; error = null }, label = { Text(stringResource(R.string.account_new_password)) }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), isError = error != null)
                    if (busy) Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.account_checking), Modifier.padding(start = 12.dp))
                    }
                    error?.let { Text(it, Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(enabled = password.isNotEmpty() && !busy, onClick = {
                    busy = true
                    scope.launch {
                        val failed = withContext(Dispatchers.IO) { AccountCheck.run(account.discovered(), password) }
                        busy = false
                        if (failed == null) { vm.updateAccount(account, password); passwordDialog = false } else error = res.getString(R.string.advice_check_password)
                    }
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { passwordDialog = false }, enabled = !busy) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

private fun Account.discovered() = DiscoveredSettings(ServerConfig(imapHost, imapPort, imapSecurity), ServerConfig(smtpHost, smtpPort, smtpSecurity), username)

@Composable
private fun ServersPage(vm: MailViewModel, account: Account, onBack: () -> Unit) {
    val context = LocalContext.current
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    var imapHost by remember { mutableStateOf(account.imapHost) }
    var imapPort by remember { mutableStateOf(account.imapPort.toString()) }
    var imapSecurity by remember { mutableStateOf(account.imapSecurity) }
    var smtpHost by remember { mutableStateOf(account.smtpHost) }
    var smtpPort by remember { mutableStateOf(account.smtpPort.toString()) }
    var smtpSecurity by remember { mutableStateOf(account.smtpSecurity) }
    var username by remember { mutableStateOf(account.username) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    PageFrame(stringResource(R.string.account_servers), onBack) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            ServerFields(stringResource(R.string.setup_incoming), imapHost, { imapHost = it }, imapPort, { imapPort = it }, imapSecurity) {
                imapSecurity = it; imapPort = if (it == Security.TLS) "993" else "143"
            }
            ServerFields(stringResource(R.string.setup_outgoing), smtpHost, { smtpHost = it }, smtpPort, { smtpPort = it }, smtpSecurity) {
                smtpSecurity = it; smtpPort = if (it == Security.TLS) "465" else "587"
            }
            OutlinedTextField(username, { username = it.trim() }, Modifier.fillMaxWidth().padding(top = 16.dp), label = { Text(stringResource(R.string.setup_username)) }, singleLine = true)
            message?.let { Text(it, Modifier.padding(top = 16.dp), color = MaterialTheme.colorScheme.error) }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            if (busy) CircularProgressIndicator(Modifier.size(24.dp).padding(end = 8.dp), strokeWidth = 2.dp)
            Button(enabled = !busy, onClick = {
                val ip = imapPort.toIntOrNull()
                val sp = smtpPort.toIntOrNull()
                if (ip == null || sp == null || imapHost.isBlank() || smtpHost.isBlank() || username.isBlank()) {
                    message = res.getString(R.string.setup_manual_incomplete); return@Button
                }
                val updated = account.copy(imapHost = imapHost.trim(), imapPort = ip, imapSecurity = imapSecurity, smtpHost = smtpHost.trim(), smtpPort = sp, smtpSecurity = smtpSecurity, username = username)
                busy = true; message = null
                scope.launch {
                    // Only settings that work are stored (Linux app: "Mailserver prüfen").
                    val failed = withContext(Dispatchers.IO) { AccountCheck.run(updated.discovered(), vm.repo.password(account.id)) }
                    busy = false
                    if (failed == null) { vm.updateAccount(updated); onBack() }
                    else message = res.getString(
                        when (failed) {
                            io.github.deviablo.email.setup.CheckError.IMAP_CONNECTION -> R.string.advice_imap_unreachable
                            io.github.deviablo.email.setup.CheckError.SMTP_CONNECTION -> R.string.advice_smtp_unreachable
                            io.github.deviablo.email.setup.CheckError.MAILBOX -> R.string.advice_mailbox
                            else -> R.string.advice_check_password
                        },
                        if (failed == io.github.deviablo.email.setup.CheckError.SMTP_CONNECTION) updated.smtpHost else updated.imapHost,
                    )
                }
            }) { Text(stringResource(R.string.setup_check_server)) }
        }
    }
}

@Composable
private fun SignaturePage(vm: MailViewModel, account: Account, onBack: () -> Unit) {
    val controller = remember { EditorController() }
    val scope = rememberCoroutineScope()
    fun saveAndClose() {
        scope.launch {
            val html = controller.html().trim()
            // An editor that only holds an empty line counts as no signature.
            val clean = if (Jsoup.parse(html).text().isBlank() && !html.contains("<img")) "" else html
            if (clean != account.signature) vm.updateAccount(account.copy(signature = clean))
            onBack()
        }
    }
    BackHandler { saveAndClose() }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(500); controller.focusStart() }
    PageFrame(stringResource(R.string.prefs_signature_for, account.name), onBack = ::saveAndClose) {
        Column(Modifier.weight(1f)) {
            Box(Modifier.weight(1f).fillMaxWidth()) { RichEditor(account.signature.ifBlank { "<div><br></div>" }, controller, Modifier.fillMaxSize()) }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { controller.command("bold") }) { Icon(Symbols.Bold, stringResource(R.string.format_bold)) }
                IconButton(onClick = { controller.command("italic") }) { Icon(Symbols.Italic, stringResource(R.string.format_italic)) }
            }
        }
    }
}
