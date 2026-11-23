// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.deviablo.email.background

import android.content.Context
import io.github.deviablo.email.data.Account
import io.github.deviablo.email.data.StoredMessage

/** System notifications for new mail; filled in with the background milestone. */
object Notifier {
    fun newMail(context: Context, account: Account, messages: List<StoredMessage>) = Unit
}
