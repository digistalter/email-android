// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.deviablo.email.background

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.deviablo.email.data.MailRepository
import io.github.deviablo.email.data.OutboxProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Syncs one account or all of them: sends the outbox, fetches new mail and notifies. Runs through
 * WorkManager so a mail that was sent while the app closes still goes out.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object {
        private const val TAG = "SyncWorker"
        const val KEY_ACCOUNT = "account"

        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** Sync now, e.g. after "Send" or for a waiting draft. */
        fun now(context: Context, accountId: Long) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(network)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setInputData(workDataOf(KEY_ACCOUNT to accountId))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("sync-$accountId", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }

        /** Another try for mail that could not go out yet. */
        private fun retryLater(context: Context, accountId: Long) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(network)
                .setInitialDelay(OutboxProcessor.RETRY_MS, TimeUnit.MILLISECONDS)
                .setInputData(workDataOf(KEY_ACCOUNT to accountId))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("outbox-$accountId", ExistingWorkPolicy.REPLACE, request)
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val repo = MailRepository.get(applicationContext)
        val only = inputData.getLong(KEY_ACCOUNT, -1)
        val accounts = repo.db.accounts().filter { only < 0 || it.id == only }
        for (account in accounts) {
            val result = runCatching { repo.syncAccount(account) }
            result.exceptionOrNull()?.let { Log.i(TAG, "account ${account.id}: sync failed (${it.javaClass.simpleName})") }
            result.getOrNull()?.let { Notifier.newMail(applicationContext, account, it.newInboxMail) }
            if (repo.db.hasOutbox(account.id)) retryLater(applicationContext, account.id)
        }
        Result.success()
    }
}
