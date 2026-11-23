// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.deviablo.email.setup

import android.util.Log
import io.github.deviablo.email.mail.imap.ImapAuthException
import io.github.deviablo.email.mail.imap.ImapClient
import io.github.deviablo.email.mail.imap.ServerSettings
import io.github.deviablo.email.mail.smtp.SmtpAuthException
import io.github.deviablo.email.mail.smtp.SmtpClient

enum class CheckStage { SIGN_IN, REACH }

/** Why an account could not be added; the setup picks its advice from this (Linux app). */
enum class CheckError { IMAP_LOGIN, IMAP_CONNECTION, SMTP_LOGIN, SMTP_CONNECTION, MAILBOX }

/**
 * Signs in to the incoming server, opens the inbox and signs in to the outgoing server, so a
 * mistake shows up during setup and not with the first mail sent. Blocking.
 */
object AccountCheck {
    fun run(settings: DiscoveredSettings, password: String, stage: (CheckStage) -> Unit = {}): CheckError? {
        stage(CheckStage.SIGN_IN)
        val imap = ServerSettings(settings.imap.host, settings.imap.port, settings.imap.security, settings.username)
        val client = try {
            ImapClient.connect(imap).also { it.login(settings.username, password) }
        } catch (e: ImapAuthException) {
            return CheckError.IMAP_LOGIN
        } catch (e: Exception) {
            log("imap", e)
            return CheckError.IMAP_CONNECTION
        }
        stage(CheckStage.REACH)
        try {
            client.select("INBOX")
            client.logout()
        } catch (e: Exception) {
            log("mailbox", e)
            return CheckError.MAILBOX
        } finally {
            client.close()
        }
        val smtp = ServerSettings(settings.smtp.host, settings.smtp.port, settings.smtp.security, settings.username)
        try {
            SmtpClient.connect(smtp).use { it.login(settings.username, password); it.quit() }
        } catch (e: SmtpAuthException) {
            return CheckError.SMTP_LOGIN
        } catch (e: Exception) {
            log("smtp", e)
            return CheckError.SMTP_CONNECTION
        }
        return null
    }

    // Class and message only: they never contain the password.
    private fun log(what: String, e: Exception) = Log.i("AccountCheck", "$what: ${e.javaClass.simpleName}: ${e.message}")
}
