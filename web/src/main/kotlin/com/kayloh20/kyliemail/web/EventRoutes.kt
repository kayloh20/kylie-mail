package com.kayloh20.kyliemail.web

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.header
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A live stream (Server-Sent Events) telling the page the moment the inbox changes, so new mail
 * appears without waiting for the next check. It says only which folder changed, never anything
 * about the mail itself; the page then asks for the list as usual. Like the page's own checks, it
 * doesn't count as using the page, and it ends when the session does.
 */
fun Route.eventRoutes(services: Services) {
    get("/api/events") {
        val token = call.request.cookies[SESSION_COOKIE]
        val session = services.sessions.get(token, touch = false) ?: throw ApiError(HttpStatusCode.Unauthorized, "Sign in first.")
        val watcher = services.watcher(session)
        // Tells Caddy, and any other proxy, not to hold events back.
        call.response.header("X-Accel-Buffering", "no")
        call.respondBytesWriter(ContentType.Text.EventStream) {
            val stopListening = watcher.listen()
            try {
                coroutineScope {
                    val changed = Channel<String>(Channel.CONFLATED)
                    val collector = launch { watcher.changes.collect { changed.send(it) } }
                    writeStringUtf8("retry: 10000\n: connected\n\n")
                    flush()
                    while (true) {
                        val folder = withTimeoutOrNull(services.eventHeartbeatMs) { changed.receive() }
                        // Stop as soon as the session has ended or the watcher gave up.
                        if (services.sessions.get(token, touch = false) == null || !watcher.running) break
                        if (folder == null) {
                            writeStringUtf8(": still here\n\n")
                        } else {
                            writeStringUtf8("event: changed\ndata: ${Json.encodeToString(ChangedEvent.serializer(), ChangedEvent(folder))}\n\n")
                        }
                        flush()
                    }
                    collector.cancel()
                }
            } finally {
                stopListening()
            }
        }
    }
}

@kotlinx.serialization.Serializable
data class ChangedEvent(val folder: String)

private val Json = kotlinx.serialization.json.Json

/** Often enough that proxies and browsers never think the stream has died. */
const val EVENT_HEARTBEAT_MS = 25_000L
