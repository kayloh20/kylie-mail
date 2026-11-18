package com.kayloh20.kyliemail.relay

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

@Serializable
data class Device(val token: String, val name: String, val addedAt: Long, val lastSeen: Long)

@Serializable
data class StoredAccount(
    val id: String,
    val username: String,
    /** Recognises the account key; can't be turned back into it. */
    val keyHash: String,
    /** The app-specific password, sealed with the account key. */
    val sealedPassword: String,
    val devices: List<Device> = emptyList(),
    /** The inbox's next UID when last seen, to tell what arrived while the relay wasn't watching. */
    val lastUidNext: Long = 0,
    val uidValidity: Long = 0,
)

@Serializable
data class StoreFile(val accounts: List<StoredAccount> = emptyList())

/** Everything the relay keeps, in one small file only it can read. Writes replace it whole. */
class Store(private val file: Path) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val lock = Any()
    private var data: StoreFile = load()

    private fun load(): StoreFile =
        if (Files.exists(file)) json.decodeFromString(StoreFile.serializer(), Files.readString(file)) else StoreFile()

    fun accounts(): List<StoredAccount> = synchronized(lock) { data.accounts }

    fun get(id: String): StoredAccount? = synchronized(lock) { data.accounts.firstOrNull { it.id == id } }

    fun byUsername(username: String): StoredAccount? =
        synchronized(lock) { data.accounts.firstOrNull { it.username.equals(username, ignoreCase = true) } }

    fun put(account: StoredAccount) = edit { accounts -> accounts.filter { it.id != account.id } + account }

    fun update(id: String, change: (StoredAccount) -> StoredAccount) =
        edit { accounts -> accounts.map { if (it.id == id) change(it) else it } }

    fun remove(id: String) = edit { accounts -> accounts.filter { it.id != id } }

    private fun edit(change: (List<StoredAccount>) -> List<StoredAccount>) = synchronized(lock) {
        data = StoreFile(change(data.accounts))
        save()
    }

    private fun save() {
        Files.createDirectories(file.parent)
        val temp = file.resolveSibling(file.fileName.toString() + ".tmp")
        Files.writeString(temp, json.encodeToString(StoreFile.serializer(), data))
        runCatching { Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------")) }
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
