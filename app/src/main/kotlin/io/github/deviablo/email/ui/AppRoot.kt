// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.deviablo.email.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel

private sealed interface Screen {
    data object Main : Screen
    data class Compose(val request: ComposeRequest) : Screen
    data object Settings : Screen
    data object AddAccount : Screen
}

/** Top level: mail, composer, settings and account setup. Plain state instead of a navigation library. */
@Composable
fun AppRoot(vm: MailViewModel = viewModel(), initialCompose: ComposeRequest? = null, debugAccount: AccountForm? = null) {
    val state by vm.state.collectAsState()
    var screen by remember { mutableStateOf<Screen>(initialCompose?.let { Screen.Compose(it) } ?: Screen.Main) }
    if (!state.loaded) return
    LaunchedEffect(debugAccount) {
        if (debugAccount != null && state.accounts.none { it.address == debugAccount.address }) vm.addAccount(debugAccount)
    }
    if (state.accounts.isEmpty() || state.showAccountForm) {
        AccountFormScreen(
            busy = state.formBusy, error = state.formError, canCancel = state.accounts.isNotEmpty(),
            onSubmit = vm::addAccount, onCancel = { vm.showAccountForm(false) },
        )
        return
    }
    when (val s = screen) {
        Screen.Main -> MailApp(
            vm,
            onCompose = { screen = Screen.Compose(it) },
            onSettings = { screen = Screen.Settings },
            onAddAccount = { vm.showAccountForm(true) },
        )
        is Screen.Compose -> {
            BackHandler { screen = Screen.Main }
            ComposeScreen(vm, s.request, onClose = { screen = Screen.Main })
        }
        Screen.Settings -> {
            BackHandler { screen = Screen.Main }
            SettingsScreen(vm, onBack = { screen = Screen.Main })
        }
        Screen.AddAccount -> vm.showAccountForm(true)
    }
}
