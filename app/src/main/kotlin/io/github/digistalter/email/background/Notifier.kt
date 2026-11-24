// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.digistalter.email.background

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.digistalter.email.MainActivity
import io.github.digistalter.email.R
import io.github.digistalter.email.data.Account
import io.github.digistalter.email.data.MailRepository
import io.github.digistalter.email.data.StoredMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** What the app shows right now, so the open conversation does not also notify (Linux app). */
object OnScreen {
    @Volatile var threadKey: String? = null
    @Volatile var visible: Boolean = false
}

/**
 * New mail through the system only: one notification per mail, grouped per account, with
 * "Mark as read" and "Delete". Each account has its own channel, so sound and importance can be
 * set per account in the system settings.
 */
object Notifier {
    const val CHANNEL_PUSH = "push"
    private const val GROUP_ACCOUNTS = "accounts"
    const val EXTRA_MESSAGE = "open_message"
    const val EXTRA_ACCOUNT = "open_account"
    private const val TAG_MAIL = "mail"
    private const val TAG_SUMMARY = "summary"
    private const val NEW_FOR_MS = 2L * 24 * 3600 * 1000

    fun channelId(accountId: Long) = "mail-$accountId"

    /** Channels for the current accounts; channels of removed accounts go away. */
    fun ensureChannels(context: Context, accounts: List<Account>) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannelGroup(NotificationChannelGroup(GROUP_ACCOUNTS, context.getString(R.string.channel_group_mail)))
        for (a in accounts) {
            val ch = NotificationChannel(channelId(a.id), a.name.ifBlank { a.address }, NotificationManager.IMPORTANCE_DEFAULT)
            ch.description = context.getString(R.string.channel_mail_description, a.address)
            ch.group = GROUP_ACCOUNTS
            nm.createNotificationChannel(ch)
        }
        val keep = accounts.map { channelId(it.id) }.toSet() + CHANNEL_PUSH
        nm.notificationChannels.filter { it.id !in keep }.forEach { nm.deleteNotificationChannel(it.id) }
        val push = NotificationChannel(CHANNEL_PUSH, context.getString(R.string.channel_push), NotificationManager.IMPORTANCE_MIN)
        push.description = context.getString(R.string.channel_push_description)
        push.setShowBadge(false)
        nm.createNotificationChannel(push)
    }

    fun newMail(context: Context, account: Account, messages: List<StoredMessage>) {
        val repo = MailRepository.get(context)
        if (!repo.settings.notifications || !account.notify) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val now = System.currentTimeMillis()
        val fresh = messages.filter { m ->
            !m.seen && now - m.dateMillis < NEW_FOR_MS && !(OnScreen.visible && OnScreen.threadKey == m.threadKey)
        }
        if (fresh.isEmpty()) return
        ensureChannels(context, repo.db.accounts())
        val nm = NotificationManagerCompat.from(context)
        val group = "account-${account.id}"
        for (m in fresh) {
            val sender = m.fromName.ifBlank { m.fromAddress }
            val subject = m.subject.ifBlank { context.getString(R.string.no_subject) }
            val n = NotificationCompat.Builder(context, channelId(account.id))
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(sender)
                .setContentText(subject)
                .setSubText(account.name)
                .setStyle(NotificationCompat.BigTextStyle().bigText(subject))
                .setWhen(m.dateMillis)
                .setShowWhen(true)
                .setCategory(NotificationCompat.CATEGORY_EMAIL)
                .setGroup(group)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openIntent(context, account.id, m.id))
                .addAction(0, context.getString(R.string.mark_read), actionIntent(context, ActionReceiver.ACTION_READ, m.id))
                .addAction(0, context.getString(R.string.delete), actionIntent(context, ActionReceiver.ACTION_DELETE, m.id))
                .build()
            runCatching { nm.notify(TAG_MAIL, m.id.toInt(), n) }
        }
        // Summary for the group (shown on its own when several mails arrive).
        val active = context.getSystemService(NotificationManager::class.java).activeNotifications
            .count { it.tag == TAG_MAIL && it.notification.group == group }
        val summary = NotificationCompat.Builder(context, channelId(account.id))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.resources.getQuantityString(R.plurals.new_mail_count, active, active))
            .setSubText(account.name)
            .setStyle(NotificationCompat.InboxStyle().also { s -> fresh.take(5).forEach { s.addLine("${it.fromName.ifBlank { it.fromAddress }}: ${it.subject}") } })
            .setGroup(group)
            .setGroupSummary(true)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent(context, account.id, null))
            .build()
        runCatching { nm.notify(TAG_SUMMARY, account.id.toInt(), summary) }
    }

    /** Mails that were read or deleted (in the app or elsewhere) leave the notification shade. */
    fun withdraw(context: Context, messageIds: Collection<Long>) {
        val nm = NotificationManagerCompat.from(context)
        messageIds.forEach { nm.cancel(TAG_MAIL, it.toInt()) }
        // A summary without mails left would stay on its own.
        val manager = context.getSystemService(NotificationManager::class.java)
        val groupsLeft = manager.activeNotifications.filter { it.tag == TAG_MAIL }.map { it.notification.group }.toSet()
        manager.activeNotifications.filter { it.tag == TAG_SUMMARY && it.notification.group !in groupsLeft }.forEach { nm.cancel(TAG_SUMMARY, it.id) }
    }

    private fun openIntent(context: Context, accountId: Long, messageId: Long?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_ACCOUNT, accountId)
        if (messageId != null) intent.putExtra(EXTRA_MESSAGE, messageId)
        val request = (messageId ?: -accountId).toInt()
        return PendingIntent.getActivity(context, request, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun actionIntent(context: Context, action: String, messageId: Long): PendingIntent {
        val intent = Intent(context, ActionReceiver::class.java).setAction(action).putExtra(EXTRA_MESSAGE, messageId)
        return PendingIntent.getBroadcast(context, messageId.toInt(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}

/** "Mark as read" and "Delete" from a notification, without opening the app. */
class ActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_READ = "io.github.digistalter.email.action.READ"
        const val ACTION_DELETE = "io.github.digistalter.email.action.DELETE"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(Notifier.EXTRA_MESSAGE, -1)
        if (id < 0) return
        val pending = goAsync()
        scope.launch {
            try {
                val repo = MailRepository.get(context)
                val m = repo.db.message(id)
                if (m != null) {
                    when (intent.action) {
                        ACTION_READ -> repo.setRead(listOf(m), true)
                        ACTION_DELETE -> repo.delete(listOf(m))
                    }
                    // A delete waits out the undo time of the app before it reaches the server.
                    SyncWorker.soon(context, m.accountId, if (intent.action == ACTION_DELETE) MailRepository.UNDO_MS + 2_000 else 0)
                }
                Notifier.withdraw(context, listOf(id))
            } finally {
                pending.finish()
            }
        }
    }
}
