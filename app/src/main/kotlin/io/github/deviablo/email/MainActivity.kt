// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.deviablo.email

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.deviablo.email.ui.AppRoot
import io.github.deviablo.email.ui.theme.EMailTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EMailTheme {
                AppRoot()
            }
        }
    }
}
