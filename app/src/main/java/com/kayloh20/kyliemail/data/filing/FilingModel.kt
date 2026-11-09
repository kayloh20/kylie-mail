package com.kayloh20.kyliemail.data.filing

import kotlin.math.exp
import kotlin.math.ln

/** What the model sees of a message. */
data class FilingFeatures(
    val fromAddress: String,
    val fromName: String,
    /** Recipient addresses other than the account's own, such as Hide My Email aliases. */
    val recipients: List<String>,
    val subject: String,
)

/** A folder a message was filed to, used for learning. */
data class FilingExample(val folder: String, val features: FilingFeatures)

enum class Evidence { SENDER, RECIPIENT, DOMAIN, SUBJECT }

/** A suggested folder (by full name) with a 0..1 score and the evidence that mattered most. */
data class Suggestion(
    val folder: String,
    val score: Double,
    val evidence: Evidence,
    /** How many filed emails back up [evidence]. */
    val supportingCount: Int,
    /** The sender, recipient or domain behind [evidence], when there is one. */
    val detail: String?,
)

/**
 * Suggests folders the way a person would guess: who sent it, which address it went to, the
 * sender's company, and words in the subject, each compared with mail already filed. Pure Kotlin
 * and on-device; nothing about the user's mail leaves the phone or laptop.
 *
 * Each signal gives a share of its evidence to each folder, shrunk toward "unknown" when there are
 * few examples, and the shares are blended with fixed weights. A sender seen once or twice gives a
 * modest score; one filed to the same place many times gives a confident one.
 */
class FilingModel private constructor(
    private val senders: Map<String, Map<String, Int>>,
    private val recipients: Map<String, Map<String, Int>>,
    private val domains: Map<String, Map<String, Int>>,
    private val tokenCounts: Map<String, Map<String, Int>>,
    private val tokensPerFolder: Map<String, Int>,
    private val vocabularySize: Int,
) {
    fun suggest(message: FilingFeatures, exclude: String? = null, self: String? = null, limit: Int = 3): List<Suggestion> {
        val sender = normalize(message.fromAddress)
        val senderShares = shares(senders[sender], self)
        // A recipient only counts when it points somewhere specific, like a Hide My Email alias used by
        // one company. An address that gets all kinds of mail would just favor the biggest folders.
        val recipientShares = message.recipients.map(::normalize).distinct()
            .map { it to shares(recipients[it], self) }
            .filter { (_, s) -> (s.values.maxOrNull() ?: 0.0) >= RECIPIENT_FOCUS }
            .maxByOrNull { (_, s) -> s.values.maxOrNull() ?: 0.0 }
        val domain = registrableDomain(sender)?.takeIf { it !in genericDomains }
        val domainShares = domain?.let { shares(domains[it], self) }.orEmpty()
        val subjectShares = subjectShares(tokenize(message.subject), self)

        val folders = (senderShares.keys + recipientShares?.second?.keys.orEmpty() + domainShares.keys + subjectShares.keys)
            .filter { it != exclude }
        return folders.map { folder ->
            val parts = mapOf(
                Evidence.SENDER to W_SENDER * (senderShares[folder] ?: 0.0),
                Evidence.RECIPIENT to W_RECIPIENT * (recipientShares?.second?.get(folder) ?: 0.0),
                Evidence.DOMAIN to W_DOMAIN * (domainShares[folder] ?: 0.0),
                Evidence.SUBJECT to W_SUBJECT * (subjectShares[folder] ?: 0.0),
            )
            val strongest = parts.maxBy { it.value }.key
            val (count, detail) = when (strongest) {
                Evidence.SENDER -> countFor(senders[sender], folder, self) to message.fromName.ifBlank { sender }
                Evidence.RECIPIENT -> countFor(recipients[recipientShares?.first], folder, self) to recipientShares?.first
                Evidence.DOMAIN -> countFor(domains[domain], folder, self) to domain
                Evidence.SUBJECT -> 0 to null
            }
            Suggestion(folder, parts.values.sum(), strongest, count, detail)
        }
            .filter { it.score >= MIN_SCORE }
            .sortedByDescending { it.score }
            .take(limit)
    }

    /** Each folder's share of the evidence, with one imaginary "somewhere else" example to stay humble. */
    private fun shares(counts: Map<String, Int>?, self: String?): Map<String, Double> {
        if (counts == null) return emptyMap()
        val adjusted = counts.mapValues { (folder, c) -> if (folder == self) (c - 1).coerceAtLeast(0) else c }
        val total = adjusted.values.sum()
        if (total == 0) return emptyMap()
        return adjusted.filterValues { it > 0 }.mapValues { (_, c) -> c.toDouble() / (total + 1) }
    }

    private fun countFor(counts: Map<String, Int>?, folder: String, self: String?): Int {
        val c = counts?.get(folder) ?: 0
        return if (folder == self) (c - 1).coerceAtLeast(0) else c
    }

    /** Naive Bayes over subject words, scaled down when only a few words matched anything. */
    private fun subjectShares(tokens: Set<String>, self: String?): Map<String, Double> {
        val known = tokens.filter { token -> tokenCounts[token]?.any { (folder, c) -> c - (if (folder == self) 1 else 0) > 0 } == true }
        if (known.isEmpty()) return emptyMap()
        val candidates = known.flatMap { token -> tokenCounts.getValue(token).keys }.toSet()
        val logScores = candidates.associateWith { folder ->
            val folderTotal = (tokensPerFolder[folder] ?: 0) - (if (folder == self) tokens.size else 0)
            known.sumOf { token ->
                val c = (tokenCounts.getValue(token)[folder] ?: 0) - (if (folder == self) 1 else 0)
                ln((c.coerceAtLeast(0) + 1.0) / (folderTotal.coerceAtLeast(0) + vocabularySize))
            } / known.size
        }
        // Softmax, sharpened so a clear winner stands out, then damped by how much of the subject matched.
        val max = logScores.values.max()
        val weights = logScores.mapValues { exp(SHARPNESS * (it.value - max)) }
        val sum = weights.values.sum()
        val coverage = (known.size.toDouble() / 3).coerceAtMost(1.0)
        return weights.mapValues { it.value / sum * coverage }
    }

    companion object {
        const val W_SENDER = 0.4
        const val W_RECIPIENT = 0.25
        const val W_DOMAIN = 0.2
        const val W_SUBJECT = 0.15

        /** Below this, a folder isn't suggested at all. */
        const val MIN_SCORE = 0.1

        private const val RECIPIENT_FOCUS = 0.6

        /** At or above this, a suggestion is shown as a one-click button. */
        const val CONFIDENT = 0.35

        private const val SHARPNESS = 3.0

        fun train(examples: List<FilingExample>): FilingModel {
            val senders = HashMap<String, HashMap<String, Int>>()
            val recipients = HashMap<String, HashMap<String, Int>>()
            val domains = HashMap<String, HashMap<String, Int>>()
            val tokens = HashMap<String, HashMap<String, Int>>()
            val tokensPerFolder = HashMap<String, Int>()

            fun bump(map: HashMap<String, HashMap<String, Int>>, key: String, folder: String) {
                val counts = map.getOrPut(key) { HashMap() }
                counts[folder] = (counts[folder] ?: 0) + 1
            }

            for ((folder, features) in examples) {
                val sender = normalize(features.fromAddress)
                if (sender.isNotEmpty()) {
                    bump(senders, sender, folder)
                    registrableDomain(sender)?.takeIf { it !in genericDomains }?.let { bump(domains, it, folder) }
                }
                features.recipients.map(::normalize).filter { it.isNotEmpty() }.distinct().forEach { bump(recipients, it, folder) }
                val words = tokenize(features.subject)
                words.forEach { bump(tokens, it, folder) }
                tokensPerFolder[folder] = (tokensPerFolder[folder] ?: 0) + words.size
            }
            return FilingModel(senders, recipients, domains, tokens, tokensPerFolder, tokens.size)
        }

        private val wordPattern = Regex("[\\p{L}]{3,}")
        private val prefixes = Regex("^((re|fwd?|aw|wg)\\s*:\\s*)+", RegexOption.IGNORE_CASE)
        private val stopWords = setOf(
            "the", "and", "for", "you", "your", "with", "from", "this", "that", "are", "was", "has", "have",
            "our", "new", "now", "get", "all", "not", "can", "will", "out", "off", "its", "just", "here",
            "about", "more", "what", "who", "how", "why", "when", "been", "into", "than", "then", "them",
        )

        fun tokenize(subject: String): Set<String> =
            wordPattern.findAll(prefixes.replace(subject.trim(), "").lowercase())
                .map { it.value }
                .filter { it !in stopWords }
                .toSet()

        /** Personal mail providers, where the domain says nothing about what the email is. */
        val genericDomains = setOf(
            "icloud.com", "me.com", "mac.com", "gmail.com", "googlemail.com", "yahoo.com", "ymail.com",
            "outlook.com", "hotmail.com", "live.com", "msn.com", "aol.com", "proton.me", "protonmail.com",
            "pm.me", "gmx.com", "gmx.de", "mail.com", "fastmail.com", "hey.com", "zoho.com", "yandex.com",
            "privaterelay.appleid.com",
        )

        private val twoPartSuffixes = setOf(
            "co.uk", "org.uk", "ac.uk", "com.au", "net.au", "co.jp", "co.nz", "com.br", "co.in", "com.mx",
            "co.za", "com.sg", "com.hk", "co.kr", "com.tr", "com.cn",
        )

        /** "news.email.sephora.com" becomes "sephora.com", so all of a company's senders count together. */
        fun registrableDomain(address: String): String? {
            val host = address.substringAfterLast('@', "").trim('.').lowercase()
            val labels = host.split('.').filter { it.isNotEmpty() }
            if (labels.size < 2) return null
            val lastTwo = labels.takeLast(2).joinToString(".")
            return if (lastTwo in twoPartSuffixes && labels.size >= 3) labels.takeLast(3).joinToString(".") else lastTwo
        }

        private fun normalize(address: String) = address.trim().lowercase()

        private val addressPattern = Regex("[^\\s<>,;\"']+@[^\\s<>,;\"']+")

        /** Pulls the addresses out of a formatted list like "Kylie <k@icloud.com>, shop@hide.icloud.com". */
        fun parseAddresses(list: String): List<String> = addressPattern.findAll(list).map { it.value.lowercase() }.toList()
    }
}
