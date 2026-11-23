// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.deviablo.email.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.deviablo.email.R
import io.github.deviablo.email.mail.imap.Security

/** Interim manual setup. The guided wizard with provider detection replaces it in the setup milestone. */
@Composable
fun AccountFormScreen(busy: Boolean, error: Int?, canCancel: Boolean, onSubmit: (AccountForm) -> Unit, onCancel: () -> Unit) {
    var accountName by rememberSaveable { mutableStateOf("") }
    var displayName by rememberSaveable { mutableStateOf("") }
    var address by rememberSaveable { mutableStateOf("") }
    var host by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("993") }
    var security by rememberSaveable { mutableStateOf(Security.TLS) }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 560.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.form_title), style = MaterialTheme.typography.headlineMedium)
                Text(stringResource(R.string.form_interim), color = MaterialTheme.colorScheme.onSurfaceVariant)
                field(address, { address = it }, R.string.form_address, KeyboardType.Email)
                field(displayName, { displayName = it }, R.string.form_display_name)
                field(accountName, { accountName = it }, R.string.form_account_name)
                field(host, { host = it }, R.string.form_host, KeyboardType.Uri)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, Modifier.weight(1f), label = { Text(stringResource(R.string.form_port)) },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    SingleChoiceSegmentedButtonRow(Modifier.weight(2f)) {
                        listOf(Security.TLS to R.string.form_security_tls, Security.STARTTLS to R.string.form_security_starttls).forEachIndexed { i, (value, label) ->
                            SegmentedButton(
                                selected = security == value,
                                onClick = {
                                    security = value
                                    // Switch to the usual port, unless the user typed a special one.
                                    if (port == "993" || port == "143") port = if (value == Security.TLS) "993" else "143"
                                },
                                shape = SegmentedButtonDefaults.itemShape(i, 2),
                            ) { Text(stringResource(label)) }
                        }
                    }
                }
                field(username, { username = it }, R.string.form_username)
                OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.form_password)) },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                Text(stringResource(R.string.form_local_only), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (canCancel) TextButton(onClick = onCancel, enabled = !busy) { Text(stringResource(R.string.form_cancel)) }
                    Spacer(Modifier.weight(1f))
                    if (busy) CircularProgressIndicator(Modifier.padding(end = 16.dp).size(24.dp))
                    Button(
                        onClick = { onSubmit(AccountForm(accountName, displayName, address, host, port, security, username, password)) },
                        enabled = !busy,
                    ) { Text(stringResource(R.string.form_connect)) }
                }
            }
        }
    }
}

@Composable
private fun field(value: String, onChange: (String) -> Unit, label: Int, type: KeyboardType = KeyboardType.Text) {
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), label = { Text(stringResource(label)) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = type))
}
