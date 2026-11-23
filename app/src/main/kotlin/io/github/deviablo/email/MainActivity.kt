// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.deviablo.email

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.deviablo.email.ui.AccountForm
import io.github.deviablo.email.ui.AppRoot
import io.github.deviablo.email.ui.theme.EMailTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EMailTheme {
                AppRoot(debugAccount = debugAccount())
            }
        }
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
