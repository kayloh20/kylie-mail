package com.kayloh20.kyliemail.data.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.kayloh20.kyliemail.KylieMailApp
import com.kayloh20.kyliemail.data.push.MailPushService
import java.util.concurrent.TimeUnit

/** Background safety net: checks for mail even when the app is closed. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as KylieMailApp).container
        // Bring the instant-notification connection back if Android stopped it (allowed or not, the sync below runs).
        if (container.credentials.account.value != null) MailPushService.start(applicationContext)
        val outcome = container.syncEngine.sync()
        // Learn from mail filed into trained folders elsewhere, such as on an iPhone.
        if (outcome == SyncOutcome.Success) container.filing.keepLearning()
        return when (outcome) {
            SyncOutcome.Success, SyncOutcome.NotSignedIn -> Result.success()
            SyncOutcome.AuthFailed -> Result.failure()
            is SyncOutcome.Failed -> Result.retry()
        }
    }
}

class SyncScheduler(context: Context) {
    private val workManager = WorkManager.getInstance(context)

    fun schedulePeriodic() {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        workManager.enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun cancel() {
        workManager.cancelUniqueWork(PERIODIC_WORK)
    }

    private companion object {
        const val PERIODIC_WORK = "periodic-sync"
    }
}
