package com.kayloh20.kyliemail.data.sync

import com.kayloh20.kyliemail.data.db.LocalFlags

data class ServerFlags(val isSeen: Boolean, val isFlagged: Boolean, val isAnswered: Boolean)

/** The decisions behind a folder sync, kept free of IMAP and Room so they can be tested directly. */
object SyncPlanner {

    /** Sequence numbers (1-based, inclusive) of the newest [window] messages in a folder of [count]. */
    fun initialSequenceRange(count: Int, window: Int): IntRange? {
        if (count <= 0 || window <= 0) return null
        return maxOf(1, count - window + 1)..count
    }

    /**
     * A UID fetch of "highest+1:*" returns the newest message even when nothing is new,
     * because IMAP reads the range backwards. Only UIDs above what we have count as new.
     */
    fun newUids(fetched: Collection<Long>, highestKnownUid: Long): List<Long> =
        fetched.filter { it > highestKnownUid }.distinct().sorted()

    /** Local UIDs the server no longer has. */
    fun vanished(local: Collection<Long>, server: Set<Long>): List<Long> =
        local.filterNot { it in server }

    /** Local messages whose flags differ from the server's, with the server's values. */
    fun changedFlags(local: List<LocalFlags>, server: Map<Long, ServerFlags>): Map<Long, ServerFlags> =
        local.mapNotNull { flags ->
            val remote = server[flags.uid] ?: return@mapNotNull null
            val same = remote.isSeen == flags.isSeen &&
                remote.isFlagged == flags.isFlagged &&
                remote.isAnswered == flags.isAnswered
            if (same) null else flags.uid to remote
        }.toMap()
}
