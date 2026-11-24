// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.digistalter.email.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.digistalter.email.setup.SetupScreen
import io.github.digistalter.email.ui.settings.SettingsScreen
import io.github.digistalter.email.ui.compose.ComposeScreen

private sealed interface Screen {
    data object Main : Screen
    data class Compose(val request: ComposeRequest) : Screen
    data object Settings : Screen
}

private val ScreenSaver = Saver<Screen, Int>(save = { if (it == Screen.Settings) 1 else 0 }, restore = { if (it == 1) Screen.Settings else Screen.Main })

/** Top level: mail, composer, settings and account setup. Plain state instead of a navigation library. */
@Composable
fun AppRoot(vm: MailViewModel = viewModel(), initialCompose: ComposeRequest? = null, debugAccount: AccountForm? = null) {
    val state by vm.state.collectAsState()
    // Settings survive a restart of the screen (e.g. after switching the app language); a composer
    // keeps its text in the drafts instead.
    var screen by rememberSaveable(stateSaver = ScreenSaver) { mutableStateOf<Screen>(initialCompose?.let { Screen.Compose(it) } ?: Screen.Main) }
    if (!state.loaded) return
    LaunchedEffect(debugAccount) {
        if (debugAccount != null && state.accounts.none { it.address == debugAccount.address }) vm.addAccount(debugAccount)
    }
    if (state.accounts.isEmpty() || state.showAccountForm) {
        // After the setup the inbox opens, not the page the setup was started from.
        LaunchedEffect(Unit) { screen = Screen.Main }
        // Keyed by whether accounts exist, so the first account's "Done" page stays until it is left.
        SetupScreen(vm, canCancel = state.accounts.isNotEmpty() && state.showAccountForm, onFinished = { vm.showAccountForm(false) })
        return
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= TwoPaneMinWidth
        val compose = (screen as? Screen.Compose)?.request
        // Composer key: a new request (e.g. "Undo" reopening) starts a fresh composer.
        val closeCompose = { screen = Screen.Main }
        when {
            screen == Screen.Settings -> {
                SettingsScreen(vm, onBack = { screen = Screen.Main }, onAddAccount = { screen = Screen.Main; vm.showAccountForm(true) })
            }
            compose != null && !wide -> ComposeScreen(vm, compose, onClose = closeCompose)
            else -> {
                MailApp(
                    vm,
                    onCompose = { screen = Screen.Compose(it) },
                    onSettings = { screen = Screen.Settings },
                    onAddAccount = { vm.showAccountForm(true) },
                    replyPane = if (compose != null && compose.isReply) ({ ComposeScreen(vm, compose, onClose = closeCompose) }) else null,
                )
                if (compose != null && !compose.isReply) {
                    // Tablet: a large window over the app; tapping beside it does not close it (wireframe).
                    Dialog(
                        onDismissRequest = {},
                        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false, dismissOnBackPress = false, decorFitsSystemWindows = false),
                    ) {
                        Surface(Modifier.width(560.dp).fillMaxHeight(0.92f), shape = RoundedCornerShape(28.dp)) {
                            ComposeScreen(vm, compose, onClose = closeCompose)
                        }
                    }
                }
            }
        }
    }
}
