// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.deviablo.email.setup

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.deviablo.email.R
import io.github.deviablo.email.mail.imap.Security
import io.github.deviablo.email.ui.Avatar
import io.github.deviablo.email.ui.MailViewModel
import io.github.deviablo.email.ui.Symbols
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Step(val number: Int) {
    ADDRESS(1), PREPARE(2), MICROSOFT(1), PASSWORD(2), CONNECTING(3), NOT_FOUND(3), CONFIRM(3), MANUAL(3), NAMES(4), DONE(5)
}

private class ManualFields(s: DiscoveredSettings) {
    var imapHost by mutableStateOf(s.imap.host)
    var imapPort by mutableStateOf(s.imap.port.toString())
    var imapSecurity by mutableStateOf(s.imap.security)
    var smtpHost by mutableStateOf(s.smtp.host)
    var smtpPort by mutableStateOf(s.smtp.port.toString())
    var smtpSecurity by mutableStateOf(s.smtp.security)
    var username by mutableStateOf(s.username)

    fun settings(): DiscoveredSettings? {
        val ip = imapPort.toIntOrNull() ?: return null
        val sp = smtpPort.toIntOrNull() ?: return null
        if (imapHost.isBlank() || smtpHost.isBlank() || username.isBlank()) return null
        return DiscoveredSettings(ServerConfig(imapHost.trim(), ip, imapSecurity), ServerConfig(smtpHost.trim(), sp, smtpSecurity), username.trim())
    }
}

/**
 * Guided account setup in four steps (wireframe, Linux app): address → (preparation) → password →
 * connecting → names → done. Server settings are found automatically; entering them by hand is
 * the fallback.
 */
@Composable
fun SetupScreen(vm: MailViewModel, canCancel: Boolean, onFinished: () -> Unit) {
    val context = LocalContext.current
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(Step.ADDRESS) }
    var address by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val scroll = rememberScrollState()
    // The message sits below the fields; bring it into view so a failed check is noticed.
    LaunchedEffect(error) { if (error != null) { withFrameNanos { }; scroll.animateScrollTo(scroll.maxValue) } }
    var settings by remember { mutableStateOf<DiscoveredSettings?>(null) }
    var foundProvider by remember { mutableStateOf<Provider?>(null) }
    var stageFind by remember { mutableStateOf(false) }
    var stageSignIn by remember { mutableStateOf(false) }
    var stageReach by remember { mutableStateOf(false) }
    var manual by remember { mutableStateOf<ManualFields?>(null) }
    var name by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var job by remember { mutableStateOf<Job?>(null) }
    var cameFromManual by remember { mutableStateOf(false) }

    val provider = Providers.forAddress(address)
    val providerName = provider?.name ?: address.substringAfterLast('@')

    fun defaults(): DiscoveredSettings {
        val domain = Providers.splitAddress(address)?.second.orEmpty()
        return DiscoveredSettings(ServerConfig("imap.$domain", 993, Security.TLS), ServerConfig("smtp.$domain", 465, Security.TLS), address.trim())
    }

    fun adviceFor(e: CheckError, s: DiscoveredSettings): String = when (e) {
        CheckError.IMAP_LOGIN, CheckError.SMTP_LOGIN -> when (provider?.preparation) {
            Preparation.APP_PASSWORD -> res.getString(R.string.advice_app_password, provider.name)
            Preparation.ENABLE_IMAP -> res.getString(R.string.advice_access, provider.name)
            else -> res.getString(R.string.advice_check_password)
        }
        CheckError.IMAP_CONNECTION -> res.getString(R.string.advice_imap_unreachable, s.imap.host)
        CheckError.SMTP_CONNECTION -> res.getString(R.string.advice_smtp_unreachable, s.smtp.host)
        CheckError.MAILBOX -> res.getString(R.string.advice_mailbox)
    }

    fun check(s: DiscoveredSettings) {
        step = Step.CONNECTING
        stageFind = true
        job = scope.launch {
            val result = withContext(Dispatchers.IO) {
                AccountCheck.run(s, password) { st ->
                    scope.launch {
                        if (st == CheckStage.REACH) stageSignIn = true
                    }
                }
            }
            if (result == null) {
                stageSignIn = true; stageReach = true
                settings = s
                name = Providers.guessName(address)
                label = Providers.guessLabel(address, foundProvider ?: provider)
                step = Step.NAMES
            } else {
                error = adviceFor(result, s)
                step = if (cameFromManual) Step.MANUAL else Step.PASSWORD
            }
        }
    }

    fun signIn() {
        if (password.isEmpty()) return
        error = null
        stageFind = false; stageSignIn = false; stageReach = false
        step = Step.CONNECTING
        cameFromManual = false
        job = scope.launch {
            val found = settings?.let { Discovery.Found(it, Source.BUILTIN, foundProvider) }
                ?: ServerDiscovery.discover(address, AndroidNetwork())
            when (found) {
                Discovery.NotFound -> step = Step.NOT_FOUND
                Discovery.Unsupported -> step = Step.MICROSOFT
                is Discovery.Found -> {
                    foundProvider = found.provider
                    stageFind = true
                    if (settings == null && ServerDiscovery.needsConfirmation(address, found.settings, found.source, found.provider)) {
                        settings = found.settings
                        step = Step.CONFIRM
                    } else {
                        settings = found.settings
                        check(found.settings)
                    }
                }
            }
        }
    }

    fun continueFromAddress() {
        val a = address.trim()
        error = when {
            !Providers.isValidAddress(a) -> res.getString(R.string.setup_address_invalid)
            vm.state.value.accounts.any { it.address.equals(a, ignoreCase = true) } -> res.getString(R.string.setup_already_added)
            else -> null
        }
        if (error != null) return
        settings = null
        foundProvider = null
        step = when (provider?.preparation) {
            Preparation.UNSUPPORTED -> Step.MICROSOFT
            Preparation.APP_PASSWORD, Preparation.ENABLE_IMAP -> Step.PREPARE
            else -> Step.PASSWORD
        }
    }

    fun back() {
        error = null
        when (step) {
            Step.ADDRESS -> if (canCancel) onFinished()
            Step.PREPARE, Step.MICROSOFT, Step.NOT_FOUND -> step = Step.ADDRESS
            Step.PASSWORD -> step = if (provider?.preparation in setOf(Preparation.APP_PASSWORD, Preparation.ENABLE_IMAP)) Step.PREPARE else Step.ADDRESS
            Step.CONNECTING -> { job?.cancel(); step = if (cameFromManual) Step.MANUAL else Step.PASSWORD }
            Step.CONFIRM -> { settings = null; step = Step.PASSWORD }
            Step.MANUAL -> step = Step.PASSWORD
            Step.NAMES -> step = Step.PASSWORD
            Step.DONE -> onFinished()
        }
    }

    fun openManual() {
        manual = ManualFields(settings ?: defaults())
        error = null
        step = Step.MANUAL
    }

    BackHandler(enabled = step != Step.ADDRESS || canCancel) { back() }

    val isAppPassword = (foundProvider ?: provider)?.preparation == Preparation.APP_PASSWORD
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
            Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (step != Step.DONE && (step != Step.ADDRESS || canCancel)) {
                    IconButton(onClick = ::back) { Icon(Symbols.Back, stringResource(R.string.back)) }
                } else Spacer(Modifier.size(48.dp))
                Text(stringResource(R.string.setup_title), Modifier.padding(start = 8.dp), style = MaterialTheme.typography.titleLarge)
            }
            // Progress as four bars (wireframe) instead of the Linux side bar.
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (i in 1..4) {
                    Box(
                        Modifier.weight(1f).height(4.dp).background(
                            if (i <= step.number) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                            RoundedCornerShape(2.dp),
                        ),
                    )
                }
            }
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(horizontal = 24.dp, vertical = 24.dp)
                    .widthIn(max = 560.dp),
            ) {
                val eyebrow = when (step) {
                    Step.PREPARE -> stringResource(R.string.setup_preparation)
                    Step.MICROSOFT -> stringResource(R.string.setup_note)
                    Step.DONE -> stringResource(R.string.setup_finished)
                    else -> stringResource(R.string.setup_step, step.number)
                }
                Text(eyebrow, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))
                when (step) {
                    Step.ADDRESS -> {
                        Title(stringResource(R.string.setup_address_title), stringResource(R.string.setup_address_lead))
                        OutlinedTextField(
                            address, { address = it.trim(); error = null }, Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.form_address)) }, singleLine = true, isError = error != null,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                            keyboardActions = KeyboardActions(onNext = { continueFromAddress() }),
                        )
                        if (Providers.isValidAddress(address)) {
                            val sub = when (provider?.preparation) {
                                null -> R.string.setup_lookup_next
                                Preparation.NONE -> R.string.setup_known
                                Preparation.UNSUPPORTED -> R.string.setup_unsupported_short
                                else -> R.string.setup_known_extra
                            }
                            Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Avatar(provider?.name ?: providerName, size = 40)
                                Column(Modifier.padding(start = 12.dp)) {
                                    Text(provider?.name ?: stringResource(R.string.setup_own_provider), fontWeight = FontWeight.Medium)
                                    Text(stringResource(sub), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        } else {
                            Text(stringResource(R.string.setup_address_hint), Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Step.PREPARE -> {
                        val p = provider!!
                        val app = p.preparation == Preparation.APP_PASSWORD
                        Title(
                            if (app) stringResource(R.string.setup_app_password_title) else stringResource(R.string.setup_enable_imap_title, p.name),
                            if (app) stringResource(R.string.setup_app_password_lead, p.name) else stringResource(R.string.setup_enable_imap_lead, p.name),
                        )
                        preparationSteps(p.id).forEachIndexed { i, step ->
                            Row(Modifier.padding(vertical = 6.dp)) {
                                Text("${i + 1}.", Modifier.widthIn(min = 24.dp), fontWeight = FontWeight.Medium)
                                Text(stringResource(step))
                            }
                        }
                        p.helpUrl?.let { url ->
                            OutlinedButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }, Modifier.padding(top = 16.dp)) {
                                Text(stringResource(R.string.setup_open_instructions, p.name))
                            }
                        }
                    }
                    Step.MICROSOFT -> {
                        Title(stringResource(R.string.setup_microsoft_title), stringResource(R.string.setup_microsoft_body))
                        Text(stringResource(R.string.setup_microsoft_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Step.PASSWORD -> {
                        Title(
                            stringResource(if (isAppPassword) R.string.setup_app_password_enter else R.string.setup_password_title),
                            if (isAppPassword) stringResource(R.string.setup_app_password_enter_lead, (foundProvider ?: provider)!!.name) else stringResource(R.string.setup_password_lead),
                        )
                        Text(address, Modifier.padding(bottom = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(
                            password, { password = it; error = null }, Modifier.fillMaxWidth(),
                            label = { Text(stringResource(if (isAppPassword) R.string.setup_app_password else R.string.setup_password)) },
                            singleLine = true, isError = error != null, visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { signIn() }),
                        )
                        TextButton(onClick = ::openManual, Modifier.padding(top = 8.dp)) { Text(stringResource(R.string.setup_manual)) }
                    }
                    Step.CONNECTING -> {
                        Title(stringResource(R.string.setup_connecting_title), stringResource(R.string.setup_connecting_lead))
                        val p = foundProvider ?: provider
                        StageRow(if (p != null) stringResource(R.string.setup_find_settings, p.name) else stringResource(R.string.setup_find_settings_own), stageFind, !stageFind)
                        StageRow(stringResource(R.string.setup_stage_sign_in), stageSignIn, stageFind && !stageSignIn)
                        StageRow(stringResource(R.string.setup_stage_reach), stageReach, stageSignIn && !stageReach)
                    }
                    Step.NOT_FOUND -> {
                        Title(stringResource(R.string.setup_not_found_title), stringResource(R.string.setup_not_found_body, address))
                        ChoiceCard(stringResource(R.string.setup_check_address), stringResource(R.string.setup_check_address_hint)) { step = Step.ADDRESS }
                        ChoiceCard(stringResource(R.string.setup_manual), stringResource(R.string.setup_manual_hint)) { openManual() }
                    }
                    Step.CONFIRM -> {
                        Title(stringResource(R.string.setup_confirm_title), stringResource(R.string.setup_confirm_body, address.substringAfterLast('@')))
                        settings?.let { s ->
                            Text(stringResource(R.string.setup_incoming), fontWeight = FontWeight.Medium)
                            Text("${s.imap.host}:${s.imap.port}", Modifier.padding(bottom = 12.dp))
                            Text(stringResource(R.string.setup_outgoing), fontWeight = FontWeight.Medium)
                            Text("${s.smtp.host}:${s.smtp.port}")
                        }
                        TextButton(onClick = ::openManual, Modifier.padding(top = 8.dp)) { Text(stringResource(R.string.setup_manual)) }
                    }
                    Step.MANUAL -> {
                        val m = manual!!
                        Title(stringResource(R.string.setup_manual), stringResource(R.string.setup_manual_lead))
                        ServerFields(stringResource(R.string.setup_incoming), m.imapHost, { m.imapHost = it }, m.imapPort, { m.imapPort = it }, m.imapSecurity) {
                            m.imapSecurity = it
                            m.imapPort = if (it == Security.TLS) "993" else "143"
                        }
                        ServerFields(stringResource(R.string.setup_outgoing), m.smtpHost, { m.smtpHost = it }, m.smtpPort, { m.smtpPort = it }, m.smtpSecurity) {
                            m.smtpSecurity = it
                            m.smtpPort = if (it == Security.TLS) "465" else "587"
                        }
                        OutlinedTextField(m.username, { m.username = it.trim() }, Modifier.fillMaxWidth().padding(top = 16.dp), label = { Text(stringResource(R.string.setup_username)) }, singleLine = true)
                        OutlinedTextField(
                            password, { password = it; error = null }, Modifier.fillMaxWidth().padding(top = 8.dp),
                            label = { Text(stringResource(R.string.setup_password)) }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        )
                    }
                    Step.NAMES -> {
                        Title(stringResource(R.string.setup_names_title), stringResource(R.string.setup_names_lead))
                        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.setup_your_name)) },
                            supportingText = { Text(stringResource(R.string.setup_your_name_hint)) }, singleLine = true)
                        OutlinedTextField(label, { label = it }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text(stringResource(R.string.setup_account_name)) },
                            supportingText = { Text(stringResource(R.string.setup_account_name_hint)) }, singleLine = true)
                        Text(stringResource(R.string.setup_preview), Modifier.padding(top = 16.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Avatar(name.ifBlank { address }, size = 40)
                            Column(Modifier.padding(start = 12.dp)) {
                                Text(name.ifBlank { address }, fontWeight = FontWeight.Medium)
                                Text(address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    Step.DONE -> {
                        Title(stringResource(R.string.setup_all_set), stringResource(R.string.setup_all_set_lead))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Avatar(label, size = 40)
                            Column(Modifier.padding(start = 12.dp)) {
                                Text(label, fontWeight = FontWeight.Medium)
                                Text(address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                error?.let { Text(it, Modifier.padding(top = 16.dp), color = MaterialTheme.colorScheme.error) }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Symbols.Lock, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.setup_local_only), Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                when (step) {
                    Step.ADDRESS -> Button(onClick = ::continueFromAddress, enabled = address.isNotBlank()) { Text(stringResource(R.string.setup_continue)) }
                    Step.PREPARE -> Button(onClick = { step = Step.PASSWORD }) {
                        Text(stringResource(if (provider?.preparation == Preparation.APP_PASSWORD) R.string.setup_have_app_password else R.string.setup_done_continue))
                    }
                    Step.MICROSOFT -> Button(onClick = { address = ""; step = Step.ADDRESS }) { Text(stringResource(R.string.setup_other_address)) }
                    Step.PASSWORD -> Button(onClick = ::signIn, enabled = password.isNotEmpty()) { Text(stringResource(R.string.setup_sign_in)) }
                    Step.CONNECTING -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    Step.NOT_FOUND -> Unit
                    Step.CONFIRM -> Button(onClick = { settings?.let(::check) }) { Text(stringResource(R.string.setup_continue)) }
                    Step.MANUAL -> Button(onClick = {
                        val s = manual?.settings()
                        if (s == null || password.isEmpty()) error = res.getString(R.string.setup_manual_incomplete)
                        else { error = null; cameFromManual = true; foundProvider = null; check(s) }
                    }) { Text(stringResource(R.string.setup_check_server)) }
                    Step.NAMES -> Button(onClick = {
                        val s = settings ?: return@Button
                        scope.launch {
                            val ok = vm.createAccount(address.trim(), name.trim(), label.trim().ifBlank { Providers.guessLabel(address, provider) }, s, password)
                            if (ok) step = Step.DONE
                        }
                    }) { Text(stringResource(R.string.setup_done)) }
                    Step.DONE -> {
                        TextButton(onClick = {
                            address = ""; password = ""; settings = null; foundProvider = null; name = ""; label = ""; step = Step.ADDRESS
                        }) { Text(stringResource(R.string.setup_another)) }
                        Spacer(Modifier.size(8.dp))
                        Button(onClick = onFinished) { Text(stringResource(R.string.setup_open_inbox)) }
                    }
                }
            }
        }
    }
}

private fun preparationSteps(id: ProviderId): List<Int> = when (id) {
    ProviderId.GMAIL -> listOf(R.string.prep_gmail_1, R.string.prep_gmail_2, R.string.prep_gmail_3)
    ProviderId.ICLOUD -> listOf(R.string.prep_icloud_1, R.string.prep_icloud_2, R.string.prep_icloud_3)
    ProviderId.YAHOO -> listOf(R.string.prep_yahoo_1, R.string.prep_yahoo_2, R.string.prep_yahoo_3)
    ProviderId.GMX -> listOf(R.string.prep_gmx_1, R.string.prep_gmx_2, R.string.prep_allow_imap)
    ProviderId.WEBDE -> listOf(R.string.prep_webde_1, R.string.prep_webde_2, R.string.prep_allow_imap)
    else -> emptyList()
}

@Composable
private fun Title(title: String, lead: String) {
    Text(title, style = MaterialTheme.typography.headlineSmall)
    Text(lead, Modifier.padding(top = 8.dp, bottom = 20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun StageRow(text: String, done: Boolean, running: Boolean) {
    Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            when {
                done -> Icon(Symbols.Check, null, tint = MaterialTheme.colorScheme.primary)
                running -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else -> Box(Modifier.size(12.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(6.dp)))
            }
        }
        Text(text, Modifier.padding(start = 12.dp), color = if (done || running) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ChoiceCard(title: String, subtitle: String, onClick: () -> Unit) {
    Surface(onClick = onClick, Modifier.fillMaxWidth().padding(vertical = 6.dp), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Medium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Symbols.ChevronRight, null)
        }
    }
}

@Composable
internal fun ServerFields(title: String, host: String, onHost: (String) -> Unit, port: String, onPort: (String) -> Unit, security: Security, onSecurity: (Security) -> Unit) {
    Text(title, Modifier.padding(top = 16.dp, bottom = 4.dp), style = MaterialTheme.typography.titleSmall)
    OutlinedTextField(host, { onHost(it.trim()) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.setup_server)) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(port, { v -> onPort(v.filter { it.isDigit() }.take(5)) }, Modifier.widthIn(max = 100.dp), label = { Text(stringResource(R.string.setup_port)) },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        SingleChoiceSegmentedButtonRow(Modifier.padding(start = 12.dp).weight(1f)) {
            listOf(Security.TLS to R.string.security_tls, Security.STARTTLS to R.string.security_starttls).forEachIndexed { i, (sec, res) ->
                SegmentedButton(security == sec, { onSecurity(sec) }, SegmentedButtonDefaults.itemShape(i, 2)) { Text(stringResource(res), maxLines = 1) }
            }
        }
    }
}
