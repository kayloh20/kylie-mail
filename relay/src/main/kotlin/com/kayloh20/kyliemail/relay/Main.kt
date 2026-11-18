package com.kayloh20.kyliemail.relay

import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * Settings come from the environment:
 *   SETUP_CODE_FILE   file holding the code a device needs to register (required)
 *   FIREBASE_KEY_FILE Firebase service account key (required)
 *   DATA_DIR          where accounts are kept (default /data)
 *   PORT              HTTP port (default 8080)
 */
fun main() {
    fun setting(name: String, default: String? = null): String =
        System.getenv(name)?.takeIf { it.isNotBlank() } ?: default ?: run {
            System.err.println("$name isn't set")
            exitProcess(1)
        }

    val setupCode = Files.readString(Path.of(setting("SETUP_CODE_FILE"))).trim()
    if (setupCode.length < 16) {
        System.err.println("The setup code is too short")
        exitProcess(1)
    }
    val pusher = FcmPusher(Path.of(setting("FIREBASE_KEY_FILE")))
    val store = Store(Path.of(setting("DATA_DIR", "/data")).resolve("accounts.json"))
    val relay = Relay(store, Imap(ImapServer.ICLOUD), pusher, setupCode)
    val http = Http(relay, setting("PORT", "8080").toInt())

    Runtime.getRuntime().addShutdownHook(Thread {
        http.stop()
        relay.shutdown()
    })
    http.start()
    val accounts = store.accounts().size
    println("Mail push relay listening on ${http.port}. $accounts account(s) waiting for a device to check in.")
}
