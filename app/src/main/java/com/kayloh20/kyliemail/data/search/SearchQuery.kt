package com.kayloh20.kyliemail.data.search

/**
 * What someone typed in the search box, understood two ways: as an SQLite full-text query for the
 * on-device index, and as plain words for asking the iCloud server.
 *
 * Supports `from:`, `to:`, `subject:` and `body:` filters and "quoted phrases". Every other word
 * matches anywhere, and the last characters typed count as the start of a word, so "madi" finds
 * Madison while typing.
 */
data class SearchQuery(
    /** For `MATCH` on the index, or null when there's nothing to search for. */
    val fts: String?,
    /** Each word or phrase the server must find, with the field it is limited to, if any. */
    val serverTerms: List<ServerTerm>,
) {
    data class ServerTerm(val text: String, val field: Field?)

    enum class Field(val column: String) { FROM("sender"), TO("recipients"), SUBJECT("subject"), BODY("body") }

    companion object {
        private val fieldNames = mapOf(
            "from" to Field.FROM, "to" to Field.TO, "cc" to Field.TO,
            "subject" to Field.SUBJECT, "body" to Field.BODY,
        )

        // A token is an optional field prefix followed by a quoted phrase or a run of non-spaces.
        private val token = Regex("(?:(\\w+):)?(?:\"([^\"]*)\"?|(\\S+))")
        private val wordChars = Regex("[\\p{L}\\p{N}]+")

        fun parse(input: String): SearchQuery {
            val parts = mutableListOf<String>()
            val server = mutableListOf<ServerTerm>()
            for (match in token.findAll(input)) {
                val prefix = match.groupValues[1].lowercase()
                val field = fieldNames[prefix]
                val phrase = match.groups[2]?.value
                // An unknown "word:" isn't a filter, so it's searched as written.
                val raw = phrase ?: (if (prefix.isNotEmpty() && field == null) "$prefix ${match.groupValues[3]}" else match.groupValues[3])
                val words = wordChars.findAll(raw).map { it.value.lowercase() }.toList()
                if (words.isEmpty()) continue
                val column = field?.let { "${it.column}:" }.orEmpty()
                if (phrase != null) {
                    parts += "$column\"${words.joinToString(" ")}\""
                    server += ServerTerm(words.joinToString(" "), field)
                } else {
                    // Punctuation splits words ("o'brien" is "o" and "brien"); each part must appear.
                    words.forEach { word ->
                        parts += "$column$word*"
                        server += ServerTerm(word, field)
                    }
                }
            }
            return SearchQuery(parts.joinToString(" ").ifEmpty { null }, server)
        }
    }
}
