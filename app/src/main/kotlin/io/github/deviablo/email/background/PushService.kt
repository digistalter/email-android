// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.deviablo.email.background

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.github.deviablo.email.MainActivity
import io.github.deviablo.email.R
import io.github.deviablo.email.data.Account
import io.github.deviablo.email.data.MailRepository
import io.github.deviablo.email.data.imapSettings
import io.github.deviablo.email.mail.imap.ImapClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import android.app.PendingIntent

/**
 * "Notify immediately": keeps one IMAP IDLE connection per account to its inbox, so new mail is
 * reported within seconds instead of every 15 minutes. Runs as a foreground service with a quiet,
 * permanent notification, as Android requires.
 */
class PushService : Service() {
    companion object {
        private const val TAG = "PushService"
        private const val NOTIFICATION_ID = 1
        private const val IDLE_MS = 24 * 60_000 // below the 29 minutes servers allow (RFC 2177)

        /** Starts or stops the service to match the setting and the accounts. */
        fun update(context: Context) {
            val repo = MailRepository.get(context)
            val wanted = repo.settings.instantPush && repo.settings.notifications && repo.db.accounts().any { it.notify }
            val intent = Intent(context, PushService::class.java)
            if (wanted) runCatching { context.startForegroundService(intent) }.onFailure { Log.i(TAG, "not started: ${it.javaClass.simpleName}") }
            else context.stopService(intent)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val watchers = mutableMapOf<Long, Job>()
    @Volatile private var networkBack = CompletableDeferred<Unit>()
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            networkBack.complete(Unit)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifier.ensureChannels(this, MailRepository.get(this).db.accounts())
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, Notifier.CHANNEL_PUSH)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.push_running))
            .setContentIntent(open)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        // The "special use" type exists from Android 14 on; before that the service needs no type.
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        scope.launch { refreshWatchers() }
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback) }
        scope.cancel()
        super.onDestroy()
    }

    private fun refreshWatchers() = synchronized(watchers) {
        val repo = MailRepository.get(this)
        val accounts = repo.db.accounts().filter { it.notify }
        if (accounts.isEmpty() || !repo.settings.instantPush) { stopSelf(); return }
        val ids = accounts.map { it.id }.toSet()
        watchers.keys.filter { it !in ids }.forEach { watchers.remove(it)?.cancel() }
        for (a in accounts) if (watchers[a.id]?.isActive != true) watchers[a.id] = scope.launch { watch(a) }
    }

    /** One account: wait in IDLE, sync when the inbox changes, reconnect with a growing pause after errors. */
    private suspend fun watch(account: Account) {
        val repo = MailRepository.get(this)
        var pause = 30_000L
        while (scope.isActive) {
            try {
                ImapClient.connect(account.imapSettings()).use { client ->
                    client.login(account.username, repo.password(account.id))
                    if ("IDLE" !in client.capabilities) {
                        Log.i(TAG, "account ${account.id}: no IDLE, the 15-minute check stays")
                        return
                    }
                    client.select("INBOX")
                    pause = 30_000L
                    syncAndNotify(account)
                    while (scope.isActive) {
                        val changed = client.idle(IDLE_MS) { !scope.isActive }
                        if (changed) syncAndNotify(account)
                    }
                }
            } catch (e: Exception) {
                Log.i(TAG, "account ${account.id}: ${e.javaClass.simpleName}, retrying")
            }
            // Wait, but come back at once when the network returns.
            networkBack = CompletableDeferred()
            withTimeoutOrNull(pause) { networkBack.await() }
            pause = (pause * 2).coerceAtMost(10 * 60_000L)
        }
    }

    private fun syncAndNotify(account: Account) {
        val repo = MailRepository.get(this)
        val fresh = repo.db.accounts().firstOrNull { it.id == account.id } ?: return
        runCatching { repo.syncAccount(fresh) }.getOrNull()?.let { Notifier.newMail(this, fresh, it.newInboxMail) }
    }
}

/** After a restart or an update: the 15-minute check and, if chosen, instant notifications. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        SyncWorker.schedulePeriodic(context)
        PushService.update(context)
    }
}
