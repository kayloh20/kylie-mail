package com.kayloh20.kyliemail.web

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json

/** Everything the server does, set up for [config]. Tests call this directly. */
fun Application.webModule(config: WebConfig) {
    install(SecurityHeadersPlugin)
    install(SameOriginPlugin) { origin = config.origin }
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
    }
    install(StatusPages) {
        status(HttpStatusCode.NotFound) { call, status ->
            call.respondText("Not found", ContentType.Text.Plain, status)
        }
        exception<Throwable> { call, cause ->
            // Never show the details: they can include bits of mail.
            call.application.environment.log.error("Request failed: ${cause::class.simpleName}")
            call.respondText("Something went wrong", ContentType.Text.Plain, HttpStatusCode.InternalServerError)
        }
    }

    routing {
        get("/healthz") { call.respondText("ok", ContentType.Text.Plain) }
        staticResources("/assets", "static/assets")
        staticResources("/", "static", index = "index.html")
    }
}
