package com.kayloh20.kyliemail.data.offline

import android.content.Context
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.kayloh20.kyliemail.KylieMailApp
import com.kayloh20.kyliemail.data.db.FolderEntity
import com.kayloh20.kyliemail.data.db.MailDatabase
import com.kayloh20.kyliemail.data.mail.FolderRole
import com.kayloh20.kyliemail.data.sync.SyncEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.io.File
import java.util.concurrent.TimeUnit

/** How far "keep all mail" has got. */
data class KeepAllProgress(
    val searchable: Int,
    /** Emails on iCloud in the included folders. */
    val total: Int,
    val running: Boolean,
    val bytesOnDevice: Long,
)

/**
 * "Keep all mail on this device": downloads the headers and text (not attachments) of every email,
 * in the background and in resumable chunks, so search covers everything, even offline.
 */
class KeepAllMail(
    private val context: Context,
    private val db: MailDatabase,
    private val syncEngine: SyncEngine,
) {
    private val prefs = context.getSharedPreferences("keep_all_mail", Context.MODE_PRIVATE)
    private val workManager get() = WorkManager.getInstance(context)

    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, false))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _wifiOnly = MutableStateFlow(prefs.getBoolean(KEY_WIFI_ONLY, true))
    val wifiOnly: StateFlow<Boolean> = _wifiOnly.asStateFlow()

    fun setEnabled(on: Boolean) {
        prefs.edit { putBoolean(KEY_ENABLED, on) }
        _enabled.value = on
        if (on) schedule() else cancel()
    }

    fun setWifiOnly(on: Boolean) {
        prefs.edit { putBoolean(KEY_WIFI_ONLY, on) }
        _wifiOnly.value = on
        if (_enabled.value) {
            // New network rules apply to the next run.
            cancel()
            schedule()
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val progress: Flow<KeepAllProgress> = db.folders().observeAll().flatMapLatest { folders ->
        val included = included(folders)
        val running = workManager.getWorkInfosForUniqueWorkFlow(WORK)
            .map { infos -> infos.any { it.state == WorkInfo.State.RUNNING } }
        if (included.isEmpty()) {
            flowOf(KeepAllProgress(0, 0, false, databaseBytes()))
        } else {
            combine(db.messages().observeSearchableCount(included.map { it.id }), running) { searchable, isRunning ->
                KeepAllProgress(searchable, included.sumOf { it.totalCount }, isRunning, databaseBytes())
            }
        }
    }

    /** Starts (or continues) downloading when the network and battery allow. */
    fun schedule() {
        workManager.enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP, request())
        // A daily check picks up anything filed or deleted elsewhere.
        workManager.enqueueUniquePeriodicWork(
            DAILY,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<KeepAllMailWorker>(1, TimeUnit.DAYS).setConstraints(constraints()).build(),
        )
    }

    fun cancel() {
        workManager.cancelUniqueWork(WORK)
        workManager.cancelUniqueWork(DAILY)
    }

    /** Turns it off and frees the space: keeps only each folder's newest emails, as a normal sync would. */
    suspend fun removeDownloadedMail() {
        setEnabled(false)
        for (folder in db.folders().getAll()) db.messages().trimFolder(folder.id, KEEP_WHEN_OFF)
        db.query(androidx.sqlite.db.SimpleSQLiteQuery("PRAGMA wal_checkpoint(TRUNCATE)")).close()
    }

    /** One background run. Returns true when there's more to do. */
    suspend fun runFor(millis: Long): Boolean {
        if (!_enabled.value) return false
        val folders = included(db.folders().getAll())
        val now = System.currentTimeMillis()
        return syncEngine.keepAllPass(
            folders,
            deadline = now + millis,
            reconcileDue = { folder -> now - prefs.getLong(reconciledKey(folder), 0) > RECONCILE_EVERY_MS },
            onReconciled = { folder -> prefs.edit { putLong(reconciledKey(folder), System.currentTimeMillis()) } },
        )
    }

    /** Continues in a fresh run, so no single run outstays what Android allows. */
    fun continueLater() {
        workManager.enqueueUniqueWork(WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request())
    }

    private fun request() = OneTimeWorkRequestBuilder<KeepAllMailWorker>().setConstraints(constraints()).build()

    private fun constraints() = Constraints.Builder()
        .setRequiredNetworkType(if (_wifiOnly.value) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(true)
        .build()

    private fun reconciledKey(folder: FolderEntity) = "reconciled:${folder.fullName}"

    private fun databaseBytes(): Long {
        val main = context.getDatabasePath("mail.db")
        return listOf(main, File(main.path + "-wal"), File(main.path + "-shm")).sumOf { if (it.exists()) it.length() else 0L }
    }

    companion object {
        private const val WORK = "keep-all-mail"
        private const val DAILY = "keep-all-mail-daily"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_WIFI_ONLY = "wifi_only"
        private const val RECONCILE_EVERY_MS = 24 * 60 * 60 * 1000L
        private const val KEEP_WHEN_OFF = 200

        /** Every folder except Junk, Inbox first. */
        fun included(folders: List<FolderEntity>) =
            folders.filter { it.selectable && it.role != FolderRole.JUNK }.sortedBy { it.sortOrder }
    }
}

/** Runs "keep all mail" in the background in chunks of a few minutes. */
class KeepAllMailWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val keepAll = (applicationContext as KylieMailApp).container.keepAllMail
        return try {
            if (keepAll.runFor(RUN_MILLIS)) keepAll.continueLater()
            Result.success()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Result.retry()
        }
    }

    private companion object {
        // Well under the ten minutes Android gives a background run.
        const val RUN_MILLIS = 7 * 60 * 1000L
    }
}
