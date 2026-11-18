package com.kayloh20.kyliemail.relay

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.InetSocketAddress
import java.util.concurrent.Executors

@Serializable data class DeviceBody(val token: String, val name: String = "")
@Serializable data class RegisterBody(val setupCode: String, val username: String, val password: String, val device: DeviceBody)
@Serializable data class RegisterReply(val accountId: String, val key: String)
@Serializable data class CheckInBody(val device: DeviceBody)
@Serializable data class StatusReply(val status: String)
@Serializable data class UnregisterBody(val token: String)
@Serializable data class ErrorReply(val error: String)

/**
 * The relay's small HTTP API. It listens on plain HTTP behind Caddy, which handles HTTPS.
 *
 * POST /v1/register    setup code + iCloud login + device → account id and key
 * POST /v1/checkin     Authorization: Bearer <accountId>.<key>, device → watching or not
 * POST /v1/unregister  Authorization: Bearer <accountId>.<key>, token
 * GET  /healthz
 */
class Http(private val relay: Relay, port: Int, host: String = "0.0.0.0") {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val server = HttpServer.create(InetSocketAddress(host, port), 0).apply {
        executor = Executors.newFixedThreadPool(4)
        createContext("/") { exchange -> handle(exchange) }
    }

    val port: Int get() = server.address.port

    fun start() = server.start()
    fun stop() = server.stop(0)

    private fun handle(exchange: HttpExchange) = exchange.use {
        try {
            val path = exchange.requestURI.path
            when {
                path == "/healthz" && exchange.requestMethod == "GET" -> reply(exchange, 200, "ok", "text/plain")
                exchange.requestMethod != "POST" -> error(exchange, 405, "method not allowed")
                path == "/v1/register" -> register(exchange)
                path == "/v1/checkin" -> checkIn(exchange)
                path == "/v1/unregister" -> unregister(exchange)
                else -> error(exchange, 404, "not found")
            }
        } catch (e: BadRequest) {
            error(exchange, 400, e.message ?: "bad request")
        } catch (e: Exception) {
            error(exchange, 500, "something went wrong")
        }
    }

    private fun register(exchange: HttpExchange) {
        val body = read(exchange, RegisterBody.serializer())
        if (body.username.isBlank() || body.password.isBlank() || body.device.token.isBlank()) throw BadRequest("missing fields")
        when (val result = relay.register(body.setupCode, body.username.trim(), body.password, body.device.info())) {
            is RegisterResult.Registered -> send(exchange, 200, RegisterReply.serializer(), RegisterReply(result.accountId, result.key))
            RegisterResult.BadSetupCode -> {
                // Slows down anyone guessing.
                Thread.sleep(1_000)
                error(exchange, 403, "wrong setup code")
            }
            RegisterResult.PasswordRejected -> error(exchange, 401, "iCloud rejected the password")
            RegisterResult.Unreachable -> error(exchange, 502, "couldn't reach iCloud")
        }
    }

    private fun checkIn(exchange: HttpExchange) {
        val (id, key) = credentials(exchange) ?: return error(exchange, 401, "unknown key")
        val body = read(exchange, CheckInBody.serializer())
        when (relay.checkIn(id, key, body.device.info())) {
            CheckInResult.WATCHING -> send(exchange, 200, StatusReply.serializer(), StatusReply("watching"))
            CheckInResult.PASSWORD_REJECTED -> send(exchange, 200, StatusReply.serializer(), StatusReply("password_rejected"))
            CheckInResult.UNKNOWN_KEY -> error(exchange, 401, "unknown key")
        }
    }

    private fun unregister(exchange: HttpExchange) {
        val (id, key) = credentials(exchange) ?: return error(exchange, 401, "unknown key")
        val body = read(exchange, UnregisterBody.serializer())
        if (relay.unregister(id, key, body.token)) {
            send(exchange, 200, StatusReply.serializer(), StatusReply("removed"))
        } else {
            error(exchange, 401, "unknown key")
        }
    }

    private fun credentials(exchange: HttpExchange): Pair<String, String>? {
        val header = exchange.requestHeaders.getFirst("Authorization") ?: return null
        if (!header.startsWith("Bearer ")) return null
        val parts = header.removePrefix("Bearer ").trim().split('.', limit = 2)
        return if (parts.size == 2 && parts.all { it.isNotEmpty() }) parts[0] to parts[1] else null
    }

    private fun DeviceBody.info() = DeviceInfo(token.trim(), name.trim())

    private fun <T> read(exchange: HttpExchange, serializer: kotlinx.serialization.KSerializer<T>): T {
        val bytes = exchange.requestBody.readNBytes(MAX_BODY + 1)
        if (bytes.size > MAX_BODY) throw BadRequest("too large")
        return try {
            json.decodeFromString(serializer, String(bytes))
        } catch (e: Exception) {
            throw BadRequest("couldn't read the request")
        }
    }

    private fun <T> send(exchange: HttpExchange, status: Int, serializer: kotlinx.serialization.KSerializer<T>, value: T) =
        reply(exchange, status, json.encodeToString(serializer, value), "application/json")

    private fun error(exchange: HttpExchange, status: Int, message: String) =
        send(exchange, status, ErrorReply.serializer(), ErrorReply(message))

    private fun reply(exchange: HttpExchange, status: Int, body: String, type: String) {
        val bytes = body.toByteArray()
        exchange.responseHeaders.add("Content-Type", "$type; charset=utf-8")
        exchange.responseHeaders.add("Cache-Control", "no-store")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private class BadRequest(message: String) : Exception(message)

    private companion object {
        const val MAX_BODY = 16 * 1024
    }
}
