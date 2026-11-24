// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.deviablo.email

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import androidx.core.content.ContextCompat
import io.github.deviablo.email.background.Notifier
import io.github.deviablo.email.background.OnScreen
import io.github.deviablo.email.background.PushService
import io.github.deviablo.email.background.SyncWorker
import io.github.deviablo.email.data.MailRepository
import io.github.deviablo.email.ui.AccountForm
import io.github.deviablo.email.ui.MailViewModel
import io.github.deviablo.email.ui.AppRoot
import io.github.deviablo.email.ui.theme.EMailTheme

class MainActivity : ComponentActivity() {
    private val vm: MailViewModel by viewModels()
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) PushService.update(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EMailTheme {
                AppRoot(vm, debugAccount = debugAccount())
            }
        }
        SyncWorker.schedulePeriodic(this)
        PushService.update(this)
        if (savedInstanceState == null) handleOpen(intent)
        lifecycleScope.launch {
            vm.state.map { it.accounts.isNotEmpty() && !it.showAccountForm }.distinctUntilChanged().collect { ready ->
                if (ready) {
                    Notifier.ensureChannels(this@MainActivity, vm.state.value.accounts)
                    askForNotificationsOnce()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleOpen(intent)
    }

    override fun onResume() {
        super.onResume()
        OnScreen.visible = true
        vm.onResume()
        askForNotificationsOnce()
    }

    /** Asked once, when the first account is ready; afterwards only the system settings change it. */
    private fun askForNotificationsOnce() {
        val repo = MailRepository.get(this)
        if (!repo.settings.notifications || repo.settings.askedForNotifications) return
        if (vm.state.value.accounts.isEmpty() || vm.state.value.showAccountForm) return
        // Before Android 13 notifications need no permission.
        if (Build.VERSION.SDK_INT < 33) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        repo.settings.askedForNotifications = true
        askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onPause() {
        OnScreen.visible = false
        super.onPause()
    }

    private fun handleOpen(intent: Intent?) {
        val account = intent?.getLongExtra(Notifier.EXTRA_ACCOUNT, -1) ?: -1
        if (account < 0) return
        val message = intent!!.getLongExtra(Notifier.EXTRA_MESSAGE, -1).takeIf { it >= 0 }
        vm.openMessage(account, message)
    }

    /** Debug builds only: lets the emulator tests add the local test account in one adb command. */
    private fun debugAccount(): AccountForm? {
        if (!BuildConfig.DEBUG) return null
        val spec = intent?.getStringExtra("debug_account")?.split('|') ?: return null
        if (spec.size < 6) return null
        return AccountForm(
            address = spec[0], displayName = spec[1], accountName = spec[2], host = spec[3], port = spec[4],
            password = spec[5], smtpHost = spec[3], smtpPort = spec.getOrElse(6) { "3465" },
        )
    }
}
