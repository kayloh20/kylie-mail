package com.kayloh20.kyliemail.data.filing

import com.kayloh20.kyliemail.data.account.CredentialStore
import com.kayloh20.kyliemail.data.db.ExampleSource
import com.kayloh20.kyliemail.data.db.FilingExampleEntity
import com.kayloh20.kyliemail.data.db.FolderEntity
import com.kayloh20.kyliemail.data.db.MailDatabase
import com.kayloh20.kyliemail.data.db.MessageEntity
import com.kayloh20.kyliemail.data.db.TrainedFolder
import com.kayloh20.kyliemail.data.mail.FolderRole
import com.kayloh20.kyliemail.data.sync.SyncEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.mail.MessagingException

/** A folder to suggest for the selected mail, with a sentence saying why. */
data class FolderSuggestion(
    val folderId: Long,
    val displayName: String,
    val fullName: String,
    val score: Double,
    val reason: String,
) {
    /** Confident enough for a one-click button, not just a place at the top of the picker. */
    val confident: Boolean get() = score >= FilingModel.CONFIDENT
}

sealed interface TrainingState {
    data object Idle : TrainingState
    data class Running(val folder: String, val index: Int, val total: Int, val learned: Int) : TrainingState
    data class Finished(val learned: Int, val folders: Int) : TrainingState
    data class Failed(val message: String) : TrainingState
}

/**
 * Smart filing: learns where mail goes from folders the user trains on and from the moves they
 * make, then suggests folders. Everything stays on the device.
 */
class FilingEngine(
    private val db: MailDatabase,
    private val credentials: CredentialStore,
    private val syncEngine: SyncEngine,
    private val scope: CoroutineScope,
) {
    private val dao = db.filing()
    private val modelLock = Mutex()
    private var model: FilingModel? = null

    private val _version = MutableStateFlow(0)

    /** Bumps whenever what the model knows changes, so suggestions can be refreshed. */
    val version: StateFlow<Int> = _version.asStateFlow()

    private val _training = MutableStateFlow<TrainingState>(TrainingState.Idle)
    val training: StateFlow<TrainingState> = _training.asStateFlow()
    private var trainingJob: Job? = null

    val exampleCount: Flow<Int> = dao.observeExampleCount()
    val trainedFolders: Flow<List<TrainedFolder>> = dao.observeTrainedFolders()

    /**
     * Learns from [folders] and forgets any previously trained folder not in the list. Folders trained
     * before only read mail that arrived since. Progress is reported through [training].
     */
    fun train(folders: List<FolderEntity>) {
        if (trainingJob?.isActive == true) return
        trainingJob = scope.launch {
            var learned = 0
            try {
                val chosen = folders.map { it.fullName }.toSet()
                val previous = dao.trainedFolders().associateBy { it.fullName }
                for (name in previous.keys - chosen) {
                    dao.deleteTrainingExamples(name)
                    dao.forgetTrainedFolder(name)
                }
                for ((index, folder) in folders.withIndex()) {
                    _training.value = TrainingState.Running(folder.displayName, index + 1, folders.size, learned)
                    learned += learnFolder(folder.fullName, previous[folder.fullName])
                }
                _training.value = TrainingState.Finished(learned, folders.size)
            } catch (e: CancellationException) {
                _training.value = TrainingState.Idle
                throw e
            } catch (e: MessagingException) {
                _training.value = TrainingState.Failed(e.message ?: "Couldn't reach iCloud.")
            } catch (e: IOException) {
                _training.value = TrainingState.Failed(e.message ?: "Couldn't reach iCloud.")
            } finally {
                invalidate()
            }
        }
    }

    fun cancelTraining() {
        trainingJob?.cancel()
    }

    /** Clears a finished or failed result so the next training starts fresh. */
    fun acknowledgeTraining() {
        if (_training.value !is TrainingState.Running) _training.value = TrainingState.Idle
    }

    /** Picks up mail filed into trained folders since last time, including from other devices. */
    suspend fun keepLearning() {
        if (trainingJob?.isActive == true) return
        val trained = dao.trainedFolders()
        if (trained.isEmpty()) return
        var changed = false
        for (folder in trained) {
            val added = runCatching { learnFolder(folder.fullName, folder) }.getOrDefault(0)
            if (added > 0) changed = true
        }
        if (changed) invalidate()
    }

    private suspend fun learnFolder(fullName: String, previous: TrainedFolder?): Int {
        val sample = syncEngine.collectFilingExamples(
            folderName = fullName,
            afterUid = previous?.highestUid ?: 0,
            knownValidity = previous?.uidValidity ?: 0,
            limit = TRAINING_DEPTH,
        ) ?: return 0
        if (previous != null && previous.uidValidity != sample.uidValidity) dao.deleteTrainingExamples(fullName)
        val inserted = dao.insertExamples(sample.examples).count { it != -1L }
        dao.saveTrainedFolder(TrainedFolder(fullName, sample.uidValidity, sample.highestUid, System.currentTimeMillis()))
        return inserted
    }

    /** Records moves made in the app as strong examples. Returns their ids so an undo can remove them. */
    suspend fun recordMoves(messages: List<MessageEntity>, targetFullName: String): List<Long> {
        val now = System.currentTimeMillis()
        val ids = dao.insertExamples(
            messages.map { message ->
                FilingExampleEntity(
                    folderName = targetFullName,
                    uid = null,
                    fromAddress = message.fromAddress.lowercase(),
                    fromName = message.fromName,
                    recipients = FilingModel.parseAddresses(message.toList + "," + message.ccList).joinToString(","),
                    subject = message.subject,
                    source = ExampleSource.MOVE,
                    createdAt = now,
                )
            },
        )
        invalidate()
        return ids.filter { it != -1L }
    }

    suspend fun forgetMoves(exampleIds: List<Long>) {
        if (exampleIds.isEmpty()) return
        dao.deleteExamples(exampleIds)
        invalidate()
    }

    /** Suggestions for one or more messages, best first. Folders they are already in are left out. */
    suspend fun suggest(messageIds: List<Long>, limit: Int = 3): List<FolderSuggestion> = withContext(Dispatchers.Default) {
        if (messageIds.isEmpty()) return@withContext emptyList()
        val folders = db.folders().getAll()
        val byName = folders.filter { it.selectable && it.role in TARGET_ROLES }.associateBy { it.fullName }
        if (byName.isEmpty()) return@withContext emptyList()
        val model = currentModel(byName.keys)
        val messages = db.messages().getByIds(messageIds.take(MAX_BULK))
        if (messages.isEmpty()) return@withContext emptyList()
        val own = ownAddresses()
        val folderById = folders.associateBy { it.id }

        val perMessage = messages.map { message ->
            model.suggest(features(message, own), exclude = folderById[message.folderId]?.fullName, limit = 5)
                .filter { it.folder in byName }
        }
        if (messages.size == 1) {
            return@withContext perMessage.single().take(limit).map { s ->
                val folder = byName.getValue(s.folder)
                FolderSuggestion(folder.id, folder.displayName, folder.fullName, s.score, reason(s))
            }
        }
        // Several messages: average each folder's score across them, so it suits the whole selection.
        perMessage.flatten()
            .groupBy { it.folder }
            .mapValues { (_, list) -> list.sumOf { it.score } / messages.size }
            .entries.sortedByDescending { it.value }
            .take(limit)
            .map { (name, score) ->
                val folder = byName.getValue(name)
                FolderSuggestion(folder.id, folder.displayName, folder.fullName, score, "Best match for the selected emails")
            }
    }

    private suspend fun currentModel(targets: Set<String>): FilingModel = modelLock.withLock {
        model ?: FilingModel.train(
            dao.allExamples()
                .filter { it.folderName in targets }
                .map { example ->
                    FilingExample(
                        example.folderName,
                        FilingFeatures(
                            fromAddress = example.fromAddress,
                            fromName = example.fromName,
                            recipients = example.recipients.split(',').filter { it.isNotBlank() } - ownAddresses(),
                            subject = example.subject,
                        ),
                    )
                },
        ).also { model = it }
    }

    private suspend fun invalidate() {
        modelLock.withLock { model = null }
        _version.value++
    }

    private fun features(message: MessageEntity, own: Set<String>) = FilingFeatures(
        fromAddress = message.fromAddress,
        fromName = message.fromName,
        recipients = FilingModel.parseAddresses(message.toList + "," + message.ccList).filter { it !in own },
        subject = message.subject,
    )

    /** The account's own addresses, which say nothing about where mail goes. */
    private fun ownAddresses(): Set<String> {
        val account = credentials.account.value ?: return emptySet()
        val email = account.email.lowercase()
        val local = email.substringBefore('@')
        return setOf(email, account.username.lowercase(), "$local@icloud.com", "$local@me.com", "$local@mac.com")
    }

    private fun reason(s: Suggestion): String {
        val n = s.supportingCount
        val emails = if (n == 1) "1 email" else "$n emails"
        return when (s.evidence) {
            Evidence.SENDER -> if (n > 0) "You've filed $emails from ${s.detail} here" else "Mail from ${s.detail} goes here"
            Evidence.RECIPIENT -> "$emails sent to ${s.detail} are here"
            Evidence.DOMAIN -> "$emails from ${s.detail} are here"
            Evidence.SUBJECT -> "Emails with similar subjects are here"
        }
    }

    companion object {
        /** How many recent emails training reads from each folder the first time. */
        const val TRAINING_DEPTH = 1000

        private const val MAX_BULK = 50

        /** Folders mail can be filed to. */
        val TARGET_ROLES = setOf(FolderRole.CUSTOM, FolderRole.ARCHIVE)
    }
}
