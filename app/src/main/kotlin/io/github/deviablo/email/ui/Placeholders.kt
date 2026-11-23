// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.deviablo.email.ui

import androidx.compose.runtime.Composable

// Filled in by the next milestones (Schreiben, Einstellungen).

@Composable
fun ComposeScreen(vm: MailViewModel, request: ComposeRequest, onClose: () -> Unit) = EmptyState("…")

@Composable
fun SettingsScreen(vm: MailViewModel, onBack: () -> Unit) = EmptyState("…")
