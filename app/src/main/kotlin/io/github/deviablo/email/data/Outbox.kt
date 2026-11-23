// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.deviablo.email.data

import android.util.Log
import io.github.deviablo.email.mail.Compose
import io.github.deviablo.email.mail.imap.ImapClient
import io.github.deviablo.email.mail.imap.ImapCommandException
import io.github.deviablo.email.mail.imap.ServerSettings
import io.github.deviablo.email.mail.smtp.SmtpAuthException
import io.github.deviablo.email.mail.smtp.SmtpClient
import io.github.deviablo.email.mail.smtp.SmtpRejectedException

/** What happened to a mail on its way out, for the snackbar or a notification. */
sealed interface SendEvent {
    val accountId: Long
    data class Sent(override val accountId: Long) : SendEvent
    /** [permanent]: the server refused it; the mail is back in "Drafts". Otherwise it is retried. */
    data class Failed(override val accountId: Long, val reason: String, val permanent: Boolean, val login: Boolean) : SendEvent
}

/**
 * Works through the outbox of one account during a sync (Linux app): saves drafts, sends, files
 * the copy in "Sent" (not with Gmail, which does that itself) and removes drafts that were sent
 * or discarded. The new draft version is saved before the old one is removed, so it is never lost.
 */
class OutboxProcessor(
    private val db: MailDatabase,
    private val events: (SendEvent) -> Unit,
    private val connectSmtp: (ServerSettings) -> SmtpClient = { SmtpClient.connect(it) },
) {
    companion object {
        private const val TAG = "Outbox"
        const val RETRY_MS = 2 * 60_000L
        const val LOGIN_RETRY_MS = 15 * 60_000L
    }

    fun process(account: Account, password: String, imap: ImapClient) {
        val items = db.outbox(account.id, System.currentTimeMillis())
        if (items.isEmpty()) return
        var smtp: SmtpClient? = null
        try {
            for (item in items) {
                when (item.kind) {
                    "draft" -> saveDraft(account, imap, item)
                    "discard" -> { removeDrafts(account, imap, item.messageId); db.outboxDone(item.id) }
                    "sent" -> fileSent(account, imap, item)
                    "send" -> {
                        try {
                            val client = smtp ?: connectSmtp(account.smtpSettings()).also {
                                it.login(account.smtpSettings().username, password)
                                smtp = it
                            }
                            client.send(account.address, item.recipients, Compose.stripBcc(item.raw))
                        } catch (e: SmtpRejectedException) {
                            Log.w(TAG, "account ${account.id}: message refused (${e.message})")
                            db.outboxKind(item.id, "draft")
                            saveDraft(account, imap, item)
                            events(SendEvent.Failed(account.id, e.message.orEmpty(), permanent = true, login = false))
                            continue
                        } catch (e: Exception) {
                            val login = e is SmtpAuthException
                            val reason = e.message ?: e.javaClass.simpleName
                            Log.i(TAG, "account ${account.id}: sending later (${e.javaClass.simpleName})")
                            db.outboxRetry(item.id, System.currentTimeMillis() + if (login) LOGIN_RETRY_MS else RETRY_MS, reason)
                            if (item.lastError != reason) events(SendEvent.Failed(account.id, reason, permanent = false, login = login))
                            // Connection trouble affects the rest of the queue too; keep the order.
                            runCatching { smtp?.close() }
                            smtp = null
                            continue
                        }
                        db.outboxKind(item.id, "sent")
                        events(SendEvent.Sent(account.id))
                        fileSent(account, imap, item)
                    }
                    else -> db.outboxDone(item.id)
                }
            }
        } finally {
            smtp?.let { it.quit(); it.close() }
        }
    }

    private fun folder(account: Account, role: FolderRole) = db.folders(account.id).firstOrNull { it.role == role }

    private fun saveDraft(account: Account, imap: ImapClient, item: MailDatabase.OutboxItem) {
        val drafts = folder(account, FolderRole.DRAFTS)
        if (drafts == null) {
            db.outboxRetry(item.id, System.currentTimeMillis() + LOGIN_RETRY_MS, "no drafts folder")
            return
        }
        try {
            imap.select(drafts.rawName, readOnly = false)
            val older = imap.uidSearch(messageIdCriteria(item.messageId))
            imap.append(drafts.rawName, "\\Seen \\Draft", item.raw)
            if (older.isNotEmpty()) {
                imap.select(drafts.rawName, readOnly = false)
                imap.uidDelete(older)
            }
            db.outboxDone(item.id)
        } catch (e: ImapCommandException) {
            Log.w(TAG, "could not save a draft (${e.message})")
            db.outboxRetry(item.id, System.currentTimeMillis() + LOGIN_RETRY_MS, e.message)
        }
    }

    private fun fileSent(account: Account, imap: ImapClient, item: MailDatabase.OutboxItem) {
        if (!imap.isGmail) {
            folder(account, FolderRole.SENT)?.let { sent ->
                try {
                    imap.append(sent.rawName, "\\Seen", item.raw)
                } catch (e: ImapCommandException) {
                    Log.w(TAG, "could not file the sent message (${e.message})")
                }
            }
        }
        removeDrafts(account, imap, item.messageId)
        db.outboxDone(item.id)
    }

    private fun removeDrafts(account: Account, imap: ImapClient, messageId: String) {
        val drafts = folder(account, FolderRole.DRAFTS) ?: return
        try {
            imap.select(drafts.rawName, readOnly = false)
            val uids = imap.uidSearch(messageIdCriteria(messageId))
            if (uids.isNotEmpty()) imap.uidDelete(uids)
            db.hideDrafts(account.id, messageId)
        } catch (e: ImapCommandException) {
            Log.w(TAG, "could not remove a draft (${e.message})")
        }
    }

    /** Message-IDs come from our own builder (no quotes, spaces or brackets), so quoting is safe. */
    private fun messageIdCriteria(messageId: String) =
        "HEADER Message-ID \"<" + messageId.filter { it != '"' && it != '\\' && !it.isWhitespace() } + ">\""
}
