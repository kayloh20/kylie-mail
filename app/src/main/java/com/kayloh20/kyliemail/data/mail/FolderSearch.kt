package com.kayloh20.kyliemail.data.mail

import com.kayloh20.kyliemail.data.db.FolderEntity

/** Type-to-search over folders, for the move and go-to pickers. */
object FolderSearch {
    private val wordBoundary = Regex("[\\s/._\\-]+")

    /** Folder path as people read it, for example "Work / Receipts". */
    fun path(folder: FolderEntity): String =
        if (folder.role == FolderRole.CUSTOM) folder.fullName.replace("/", " / ") else folder.displayName

    /**
     * Best matches first. With no query, recently used folders come first, then the rest in folder order.
     * Matches rank as: exact name, name prefix, a word in the name starting with the query, name contains,
     * path contains, then letters in order (so "rcpt" finds "Receipts").
     */
    fun rank(
        folders: List<FolderEntity>,
        query: String,
        recentFullNames: List<String> = emptyList(),
        excludeId: Long? = null,
    ): List<FolderEntity> {
        val candidates = folders.filter { it.selectable && it.id != excludeId }
        val recency = recentFullNames.withIndex().associate { (i, name) -> name to i }
        val q = query.trim().lowercase()

        if (q.isEmpty()) {
            return candidates.sortedWith(
                compareBy<FolderEntity> { recency[it.fullName] ?: Int.MAX_VALUE }
                    .thenBy { it.sortOrder }
                    .thenBy { path(it).lowercase() },
            )
        }

        return candidates
            .mapNotNull { folder -> score(folder, q)?.let { folder to it } }
            .sortedWith(
                compareBy<Pair<FolderEntity, Int>> { it.second }
                    .thenBy { recency[it.first.fullName] ?: Int.MAX_VALUE }
                    .thenBy { it.first.displayName.length }
                    .thenBy { path(it.first).lowercase() },
            )
            .map { it.first }
    }

    private fun score(folder: FolderEntity, q: String): Int? {
        val name = folder.displayName.lowercase()
        val fullPath = path(folder).lowercase()
        return when {
            name == q -> 0
            name.startsWith(q) -> 1
            name.split(wordBoundary).any { it.startsWith(q) } -> 2
            name.contains(q) -> 3
            fullPath.contains(q) -> 4
            isSubsequence(q, name) -> 5
            else -> null
        }
    }

    private fun isSubsequence(needle: String, haystack: String): Boolean {
        var i = 0
        for (c in haystack) {
            if (i < needle.length && needle[i] == c) i++
        }
        return i == needle.length
    }
}
