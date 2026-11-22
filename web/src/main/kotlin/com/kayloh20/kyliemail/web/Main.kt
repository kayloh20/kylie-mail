package com.kayloh20.kyliemail.web

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlin.system.exitProcess

/**
 * Settings come from the environment:
 *   PUBLIC_ORIGIN  the address people open, such as https://mail.example.org (required)
 *   DATA_DIR       where accounts and cached mail are kept (default /data)
 *   PORT           HTTP port (default 8080). Caddy handles HTTPS in front of it.
 */
fun main() {
    fun setting(name: String, default: String? = null): String =
        System.getenv(name)?.takeIf { it.isNotBlank() } ?: default ?: run {
            System.err.println("$name isn't set")
            exitProcess(1)
        }

    val config = try {
        WebConfig(
            origin = setting("PUBLIC_ORIGIN"),
            dataDir = setting("DATA_DIR", "/data"),
        )
    } catch (e: IllegalArgumentException) {
        System.err.println(e.message)
        exitProcess(1)
    }
    val port = setting("PORT", "8080").toInt()
    val services = Services.create(config)

    println("Kylie Mail web listening on $port for ${config.origin}")
    if (services.vault.account() == null) {
        println("Not set up yet. Open ${config.origin} and enter this setup code: ${services.setupCode.current()}")
    }
    embeddedServer(CIO, port = port, host = "0.0.0.0") { webModule(config, services) }.start(wait = true)
}
