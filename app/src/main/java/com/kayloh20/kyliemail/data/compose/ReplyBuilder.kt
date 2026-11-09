package com.kayloh20.kyliemail.data.compose

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class ReplyMode { REPLY, REPLY_ALL, FORWARD }

/** The parts of the original message a reply or forward needs. */
data class OriginalMessage(
    val fromName: String,
    val fromAddress: String,
    val toList: String,
    val ccList: String,
    val subject: String,
    val sentAt: Long,
    val bodyText: String,
    val messageIdHeader: String?,
)

/** What a new reply or forward starts with. */
data class ReplyDraft(
    val to: String,
    val cc: String,
    val subject: String,
    val body: String,
    val inReplyTo: String?,
    val references: String?,
)

/** Builds replies and forwards as plain text, the way classic mail clients do. Pure, for testing. */
object ReplyBuilder {
    private val addressPattern = Regex("[^\\s<>,;\"']+@[^\\s<>,;\"']+")
    private val replyPrefix = Regex("^(re|aw|sv|antw)\\s*:\\s*", RegexOption.IGNORE_CASE)
    private val forwardPrefix = Regex("^(fwd?|wg|tr)\\s*:\\s*", RegexOption.IGNORE_CASE)

    fun build(
        original: OriginalMessage,
        mode: ReplyMode,
        ownAddresses: Set<String>,
        signature: String = "",
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): ReplyDraft {
        val own = ownAddresses.map { it.lowercase() }.toSet()
        val fromSelf = original.fromAddress.lowercase() in own
        val signatureBlock = if (signature.isBlank()) "" else "\n-- \n${signature.trimEnd()}"

        if (mode == ReplyMode.FORWARD) {
            val header = buildString {
                append("---------- Forwarded message ----------\n")
                append("From: ").append(display(original.fromName, original.fromAddress)).append('\n')
                append("Date: ").append(formatDate(original.sentAt, zone, locale)).append('\n')
                append("Subject: ").append(original.subject).append('\n')
                if (original.toList.isNotBlank()) append("To: ").append(original.toList).append('\n')
                if (original.ccList.isNotBlank()) append("Cc: ").append(original.ccList).append('\n')
            }
            return ReplyDraft(
                to = "",
                cc = "",
                subject = prefixed("Fwd: ", forwardPrefix, original.subject),
                body = "\n$signatureBlock\n\n$header\n${original.bodyText.trimEnd()}\n",
                inReplyTo = null,
                references = null,
            )
        }

        // Replying to your own sent mail goes back to the people you wrote to, as Apple Mail does.
        val primary = if (fromSelf) addresses(original.toList).filter { it.lowercase() !in own } else listOf(original.fromAddress)
        val to = primary.distinctBy { it.lowercase() }
        val cc = if (mode == ReplyMode.REPLY_ALL) {
            val already = to.map { it.lowercase() }.toSet()
            (addresses(original.toList) + addresses(original.ccList))
                .filter { it.lowercase() !in own && it.lowercase() !in already }
                .distinctBy { it.lowercase() }
        } else {
            emptyList()
        }

        val attribution = "On ${formatDate(original.sentAt, zone, locale)}, ${display(original.fromName, original.fromAddress)} wrote:"
        val quote = quote(original.bodyText)
        val references = listOfNotNull(original.messageIdHeader).joinToString(" ").ifBlank { null }

        return ReplyDraft(
            to = to.joinToString(", "),
            cc = cc.joinToString(", "),
            subject = prefixed("Re: ", replyPrefix, original.subject),
            body = "\n$signatureBlock\n\n$attribution\n$quote\n",
            inReplyTo = original.messageIdHeader,
            references = references,
        )
    }

    /** Adds the prefix unless it is already there, so subjects don't grow "Re: Re: Re:". */
    fun prefixed(prefix: String, existing: Regex, subject: String): String {
        val trimmed = subject.trim()
        return if (existing.find(trimmed)?.range?.first == 0) trimmed else prefix + trimmed
    }

    fun quote(text: String): String =
        text.trimEnd().lines().joinToString("\n") { line -> if (line.startsWith(">")) ">$line" else if (line.isEmpty()) ">" else "> $line" }

    fun addresses(list: String): List<String> = addressPattern.findAll(list).map { it.value }.toList()

    private fun display(name: String, address: String) =
        if (name.isBlank() || name == address) address else "$name <$address>"

    private fun formatDate(epochMillis: Long, zone: ZoneId, locale: Locale): String =
        DateTimeFormatter.ofPattern("EEE, MMM d, yyyy 'at' h:mm a", locale).format(Instant.ofEpochMilli(epochMillis).atZone(zone))
}
