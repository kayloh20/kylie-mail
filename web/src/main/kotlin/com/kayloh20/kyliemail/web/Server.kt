package com.kayloh20.kyliemail.web

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import java.nio.file.Path
import com.kayloh20.kyliemail.data.mail.ImapConnector
import com.kayloh20.kyliemail.data.mail.ServerConfig
import io.ktor.server.application.ApplicationStopped
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The real services for [config], with data kept in its data folder. */
fun Services.Companion.create(
    config: WebConfig,
    mailServer: ServerConfig = ServerConfig.ICLOUD,
    mailLogin: MailLogin = ImapMailLogin(ImapConnector(mailServer)),
): Services {
    val dataDir = Path.of(config.dataDir)
    return Services(
        config = config,
        vault = Vault(AccountStore(dataDir.resolve("account.json"))),
        passkeys = Passkeys(config),
        sessions = Sessions(),
        setupCode = SetupCode(dataDir.resolve("setup-code")),
        mailLogin = mailLogin,
        mailServer = mailServer,
    )
}

/** Everything the server does, set up for [config]. Tests call this with their own [services]. */
fun Application.webModule(config: WebConfig, services: Services = Services.create(config)) {
    install(SecurityHeadersPlugin)
    // Close the iCloud connections of sessions that have expired.
    val pruner = launch {
        while (true) {
            delay(60_000)
            services.sessions.prune()
        }
    }
    monitor.subscribe(ApplicationStopped) { pruner.cancel() }
    install(SameOriginPlugin) { origin = config.origin }
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
    }
    install(StatusPages) {
        status(HttpStatusCode.NotFound) { call, status ->
            call.respondText("Not found", ContentType.Text.Plain, status)
        }
        exception<ApiError> { call, cause ->
            call.respond(cause.status, ErrorReply(cause.message ?: "Something went wrong"))
        }
        exception<PasskeyException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ErrorReply(cause.message ?: "The passkey couldn't be checked"))
        }
        exception<BadRequestException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ErrorReply("That request didn't make sense."))
        }
        exception<Throwable> { call, cause ->
            // Never show the details: they can include bits of mail.
            call.application.environment.log.error("Request failed: ${cause::class.simpleName}")
            call.respond(HttpStatusCode.InternalServerError, ErrorReply("Something went wrong"))
        }
    }

    routing {
        get("/healthz") { call.respondText("ok", ContentType.Text.Plain) }
        authRoutes(services)
        mailRoutes(services)
        staticResources("/assets", "static/assets")
        staticResources("/", "static", index = "index.html")
    }
}
