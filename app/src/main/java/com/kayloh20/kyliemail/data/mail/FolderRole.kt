package com.kayloh20.kyliemail.data.mail

/** What a mailbox is for, so the UI can order, name and badge it. */
enum class FolderRole(val sortOrder: Int) {
    INBOX(0),
    DRAFTS(1),
    SENT(2),
    ARCHIVE(3),
    JUNK(4),
    TRASH(5),
    CUSTOM(100);

    companion object {
        private val attributeRoles = mapOf(
            "\\drafts" to DRAFTS,
            "\\sent" to SENT,
            "\\archive" to ARCHIVE,
            "\\junk" to JUNK,
            "\\trash" to TRASH,
        )

        // iCloud does not always advertise SPECIAL-USE attributes, so fall back to its well-known names.
        private val nameRoles = mapOf(
            "drafts" to DRAFTS,
            "sent messages" to SENT,
            "sent" to SENT,
            "sent items" to SENT,
            "archive" to ARCHIVE,
            "junk" to JUNK,
            "spam" to JUNK,
            "deleted messages" to TRASH,
            "trash" to TRASH,
            "deleted items" to TRASH,
        )

        fun detect(fullName: String, attributes: Collection<String>): FolderRole {
            if (fullName.equals("INBOX", ignoreCase = true)) return INBOX
            attributes.firstNotNullOfOrNull { attributeRoles[it.lowercase()] }?.let { return it }
            return nameRoles[fullName.lowercase()] ?: CUSTOM
        }

        fun displayName(role: FolderRole, serverName: String): String = when (role) {
            INBOX -> "Inbox"
            DRAFTS -> "Drafts"
            SENT -> "Sent"
            ARCHIVE -> "Archive"
            JUNK -> "Junk"
            TRASH -> "Trash"
            CUSTOM -> serverName
        }
    }
}
