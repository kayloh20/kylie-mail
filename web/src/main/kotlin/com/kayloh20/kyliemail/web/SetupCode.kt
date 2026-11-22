package com.kayloh20.kyliemail.web

import java.nio.file.Files
import java.nio.file.Path

/**
 * The one-time code that lets the first visitor set up the account, so a stranger who finds the
 * server first can't claim it. It's printed in the server's log and removed once the account exists.
 */
class SetupCode(private val file: Path) {
    @Synchronized
    fun current(): String {
        if (Files.exists(file)) Files.readString(file).trim().takeIf { it.length >= 24 }?.let { return it }
        Files.createDirectories(file.parent)
        val code = Crypto.b64url(Crypto.randomBytes(18))
        Files.writeString(file, code)
        AccountStore.ownerOnly(file)
        return code
    }

    fun matches(attempt: String): Boolean =
        Crypto.constantTimeEquals(attempt.trim().toByteArray(Charsets.UTF_8), current().toByteArray(Charsets.UTF_8))

    @Synchronized
    fun remove() {
        Files.deleteIfExists(file)
    }
}
