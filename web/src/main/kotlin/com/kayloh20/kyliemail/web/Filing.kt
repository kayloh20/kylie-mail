package com.kayloh20.kyliemail.web

import com.kayloh20.kyliemail.data.filing.Evidence
import com.kayloh20.kyliemail.data.filing.FilingExample
import com.kayloh20.kyliemail.data.filing.FilingFeatures
import com.kayloh20.kyliemail.data.filing.FilingModel
import com.kayloh20.kyliemail.data.filing.Suggestion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** What smart filing knows about one email: who sent it, to whom, and its subject. Never the body. */
@Serializable
data class StoredExample(
    val id: Long,
    val folder: String,
    /** Set for examples read from a trained folder, so new mail there can be picked up later. */
    val uid: Long? = null,
    val fromAddress: String,
    val fromName: String,
    val recipients: List<String>,
    val subject: String,
)

@Serializable
data class TrainedFolder(val fullName: String, val validity: Long, val highestUid: Long, val trainedAt: Long)

@Serializable
data class FilingData(
    val trained: List<TrainedFolder> = emptyList(),
    val examples: List<StoredExample> = emptyList(),
    val nextId: Long = 1,
)

/** An email's sender, recipients and subject, as read from iCloud. */
data class MessageFeatures(val uid: Long, val fromAddress: String, val fromName: String, val recipients: List<String>, val subject: String)

/** What a folder sample read for training holds. */
class FolderSample(val validity: Long, val highestUid: Long, val messages: List<MessageFeatures>)

@Serializable
data class FilingSuggestion(val folder: String, val name: String, val score: Double, val reason: String, val confident: Boolean)

@Serializable
data class TrainingStatus(
    /** "idle", "running", "finished" or "failed". */
    val state: String,
    val folder: String? = null,
    val index: Int = 0,
    val total: Int = 0,
    val learned: Int = 0,
    val message: String? = null,
)

/**
 * Smart filing's memory, kept in one file sealed with the account key: senders, recipients and
 * subjects are about your mail, so they get the same protection as the password.
 */
class FilingStore(private val file: Path) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Synchronized
    fun load(accountKey: ByteArray): FilingData {
        if (!Files.exists(file)) return FilingData()
        val opened = Crypto.open(accountKey, Files.readAllBytes(file), AD) ?: return FilingData()
        return json.decodeFromString(FilingData.serializer(), opened.toString(Charsets.UTF_8))
    }

    @Synchronized
    fun save(accountKey: ByteArray, data: FilingData) {
        Files.createDirectories(file.parent)
        val temp = file.resolveSibling(file.fileName.toString() + ".tmp")
        Files.write(temp, Crypto.seal(accountKey, json.encodeToString(FilingData.serializer(), data).toByteArray(Charsets.UTF_8), AD))
        AccountStore.ownerOnly(temp)
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private companion object {
        const val AD = "filing"
    }
}

/**
 * Smart filing for one signed-in browser, as in the app: it learns from folders you train it on and
 * from the moves you make, and suggests where mail goes. Its memory is opened when the session
 * first needs it and forgotten when the session ends.
 */
class Filing(
    private val store: FilingStore,
    private val accountKey: () -> ByteArray,
    private val ownAddresses: Set<String>,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var data: FilingData = store.load(accountKey())
    private var model: FilingModel? = null
    private var training: Job? = null
    private var keeping: Job? = null

    @Volatile var status: TrainingStatus = TrainingStatus("idle")
        private set

    /** Moves learned from each pending removal, so an undo can forget them. */
    private val learnedByRemoval = HashMap<String, List<Long>>()

    @Synchronized fun snapshot(): FilingData = data

    val running: Boolean get() = training?.isActive == true

    /**
     * Learns from [folders] (full names) and forgets any trained folder not among them. Folders
     * trained before only read mail that arrived since.
     */
    fun train(folders: List<Pair<String, String>>, mail: MailClient) {
        if (running) return
        keeping?.cancel()
        status = TrainingStatus("running", total = folders.size)
        training = scope.launch {
            var learned = 0
            try {
                val chosen = folders.map { it.first }.toSet()
                update { d -> d.copy(trained = d.trained.filter { it.fullName in chosen }, examples = d.examples.filter { it.uid == null || it.folder in chosen }) }
                for ((index, folder) in folders.withIndex()) {
                    status = TrainingStatus("running", folder.second, index + 1, folders.size, learned)
                    learned += learnFolder(folder.first, mail)
                }
                status = TrainingStatus("finished", total = folders.size, learned = learned)
            } catch (e: CancellationException) {
                status = TrainingStatus("idle")
                throw e
            } catch (e: Exception) {
                status = TrainingStatus("failed", message = (e as? ApiError)?.message ?: "Couldn't reach iCloud.")
            }
        }
    }

    fun stopTraining() {
        training?.cancel()
    }

    private var keptLearning = false

    /** [keepLearning], the first time it's asked for in this session. */
    @Synchronized
    fun keepLearningOnce(mail: MailClient) {
        if (keptLearning) return
        keptLearning = true
        keepLearning(mail)
    }

    /** Picks up mail filed into trained folders since last time, including from other devices. */
    fun keepLearning(mail: MailClient) {
        if (running || data.trained.isEmpty()) return
        keeping = scope.launch {
            for (folder in snapshot().trained) runCatching { learnFolder(folder.fullName, mail) }
        }
    }

    private suspend fun learnFolder(fullName: String, mail: MailClient): Int {
        val previous = snapshot().trained.find { it.fullName == fullName }
        val sample = mail.collectExamples(fullName, previous?.highestUid ?: 0, previous?.validity ?: 0, TRAINING_DEPTH) ?: return 0
        var added = 0
        update { d ->
            val restart = previous != null && previous.validity != sample.validity
            val kept = if (restart) d.examples.filter { it.folder != fullName || it.uid == null } else d.examples
            val known = kept.filter { it.folder == fullName }.mapNotNull { it.uid }.toSet()
            var id = d.nextId
            val fresh = sample.messages.filter { it.uid !in known }.map { example(id++, fullName, it, uid = it.uid) }
            added = fresh.size
            val highest = maxOf(if (restart) 0 else previous?.highestUid ?: 0, sample.highestUid)
            d.copy(
                trained = d.trained.filter { it.fullName != fullName } + TrainedFolder(fullName, sample.validity, highest, now()),
                examples = kept + fresh,
                nextId = id,
            )
        }
        return added
    }

    /** Moves you make are lessons too. [removalId] lets an undo take them back. */
    fun recordMoves(removalId: String, target: String, messages: List<MessageFeatures>) {
        if (messages.isEmpty()) return
        var ids = emptyList<Long>()
        update { d ->
            var id = d.nextId
            val added = messages.map { example(id++, target, it, uid = null) }
            ids = added.map { it.id }
            d.copy(examples = d.examples + added, nextId = id)
        }
        synchronized(learnedByRemoval) { learnedByRemoval[removalId] = ids }
    }

    /** An undone move isn't a lesson. */
    fun forgetMoves(removalId: String) {
        val ids = synchronized(learnedByRemoval) { learnedByRemoval.remove(removalId) }?.toSet() ?: return
        update { d -> d.copy(examples = d.examples.filter { it.id !in ids }) }
    }

    /**
     * Suggestions for [messages], best first, among [targets] (full name to display name). One
     * message gets reasons; several get the folders that suit them all.
     */
    fun suggest(messages: List<MessageFeatures>, currentFolder: String, targets: Map<String, String>, limit: Int = 3): List<FilingSuggestion> {
        if (messages.isEmpty() || targets.isEmpty()) return emptyList()
        val model = model(targets.keys)
        val perMessage = messages.map { m ->
            model.suggest(features(m), exclude = currentFolder, limit = 5).filter { it.folder in targets }
        }
        if (messages.size == 1) {
            return perMessage.single().take(limit).map { s ->
                FilingSuggestion(s.folder, targets.getValue(s.folder), s.score, reason(s), s.score >= FilingModel.CONFIDENT)
            }
        }
        return perMessage.flatten()
            .groupBy { it.folder }
            .mapValues { (_, list) -> list.sumOf { it.score } / messages.size }
            .entries.sortedByDescending { it.value }
            .take(limit)
            .map { (folder, score) ->
                FilingSuggestion(folder, targets.getValue(folder), score, "Best match for the selected emails", score >= FilingModel.CONFIDENT)
            }
    }

    @Synchronized
    private fun model(targets: Set<String>): FilingModel = model ?: FilingModel.train(
        data.examples.filter { it.folder in targets }.map { e ->
            FilingExample(e.folder, FilingFeatures(e.fromAddress, e.fromName, e.recipients - ownAddresses, e.subject))
        },
    ).also { model = it }

    private fun features(m: MessageFeatures) =
        FilingFeatures(m.fromAddress, m.fromName, m.recipients.filter { it !in ownAddresses }, m.subject)

    private fun example(id: Long, folder: String, m: MessageFeatures, uid: Long?) =
        StoredExample(id, folder, uid, m.fromAddress.lowercase(), m.fromName, m.recipients, m.subject)

    @Synchronized
    private fun update(change: (FilingData) -> FilingData) {
        data = change(data)
        model = null
        val key = accountKey()
        // A session that has ended wipes its key; never seal the file with that.
        if (key.all { it == 0.toByte() }) return
        store.save(key, data)
    }

    fun close() {
        scope.cancel()
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
        /** How many recent emails training reads from each folder the first time, as in the app. */
        const val TRAINING_DEPTH = 1000

        /** The account's own addresses, which say nothing about where mail goes. */
        fun ownAddresses(email: String, username: String): Set<String> {
            val lower = email.lowercase()
            val local = lower.substringBefore('@')
            return setOf(lower, username.lowercase(), "$local@icloud.com", "$local@me.com", "$local@mac.com")
        }
    }
}
