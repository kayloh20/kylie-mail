package com.kayloh20.kyliemail.ui.mail

import com.kayloh20.kyliemail.data.sync.SkippedFolder

/**
 * What to say after searching iCloud. A folder that couldn't be searched is never reported as
 * "nothing found": the summary says which folders were missed.
 */
fun serverSearchSummary(found: Int, skipped: List<SkippedFolder>, total: Int): String {
    val matches = if (found == 1) "1 match" else "$found matches"
    if (skipped.isEmpty()) {
        return if (found == 0) "iCloud has nothing more for this search." else "Included $matches from iCloud."
    }
    if (total > 0 && skipped.size >= total) {
        // Nothing was searched: say so, never "nothing found".
        val reasons = skipped.map { it.reason }.distinct()
        return when {
            skipped.size == 1 -> "Couldn't search ${skipped.single().name}: ${skipped.single().reason}."
            reasons.size == 1 -> "Couldn't search any folder: ${reasons.single()}."
            else -> "Couldn't search any folder on iCloud."
        }
    }
    val names = skipped.map { it.name }
    val listed = if (names.size <= 3) names.joinToString(", ") else names.take(3).joinToString(", ") + " and ${names.size - 3} more"
    val start = if (found == 0) "No matches in the folders iCloud searched" else "Included $matches from iCloud"
    val folders = if (skipped.size == 1) "1 folder" else "${skipped.size} folders"
    return "$start, but couldn't search $folders: $listed."
}
