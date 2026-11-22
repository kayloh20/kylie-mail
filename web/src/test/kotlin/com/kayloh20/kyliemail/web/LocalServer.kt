package com.kayloh20.kyliemail.web

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import java.nio.file.Files
import java.nio.file.Path

/**
 * Runs Kylie Mail on http://localhost:8080 against a pretend iCloud: a local IMAP server holding a
 * few sample messages, for trying the pages without touching real mail. Sign up with
 * kylie@icloud.com and the password "test". Data goes to build/local-data.
 * Start it with: ./gradlew -p web runLocal
 */
fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val dataDir = Path.of(System.getenv("DATA_DIR") ?: "build/local-data")
    Files.createDirectories(dataDir)
    val mail = TestMail().apply { seed() }
    val config = WebConfig(origin = "http://localhost:$port", dataDir = dataDir.toString())
    val services = Services.create(config, mailServer = mail.server)
    println("Kylie Mail with a pretend iCloud (${mail.email}, password \"${mail.password}\") on ${config.origin}")
    if (services.vault.account() == null) println("Setup code: ${services.setupCode.current()}")
    embeddedServer(CIO, port = port, host = "127.0.0.1") { webModule(config, services) }.start(wait = true)
}
