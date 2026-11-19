package com.kayloh20.kyliemail.data.relay

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/** What a device needs to talk to its account on the relay. */
data class RelayCredentials(val accountId: String, val key: String)

sealed interface RelayReply<out T> {
    data class Ok<T>(val value: T) : RelayReply<T>
    data object BadSetupCode : RelayReply<Nothing>
    data object PasswordRejected : RelayReply<Nothing>
    /** The relay doesn't recognise this device's key, for example after it restarted. */
    data object UnknownKey : RelayReply<Nothing>
    data class Failed(val message: String) : RelayReply<Nothing>
}

/** The relay's HTTP API (see relay/README.md). An interface so tests can stand in for it. */
interface RelayApi {
    suspend fun register(server: String, setupCode: String, username: String, password: String, token: String, deviceName: String): RelayReply<RelayCredentials>
    /** Returns "watching" or "password_rejected". */
    suspend fun checkIn(server: String, credentials: RelayCredentials, token: String, deviceName: String): RelayReply<String>
    suspend fun unregister(server: String, credentials: RelayCredentials, token: String): RelayReply<Unit>

    companion object {
        /**
         * Turns what someone typed ("mail-push.example.org", with or without https://) into the
         * relay's address. HTTPS only, except to this device itself, for tests.
         */
        fun normalize(input: String): String? {
            val trimmed = input.trim().trimEnd('/')
            if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null
            val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
            val uri = runCatching { URI(withScheme) }.getOrNull() ?: return null
            val host = uri.host ?: return null
            val local = host == "127.0.0.1" || host == "localhost"
            if (uri.scheme != "https" && !(uri.scheme == "http" && local)) return null
            if (!local && '.' !in host) return null
            return withScheme
        }
    }
}

class HttpRelayApi(private val timeoutMs: Int = 30_000) : RelayApi {
    override suspend fun register(
        server: String, setupCode: String, username: String, password: String, token: String, deviceName: String,
    ): RelayReply<RelayCredentials> {
        val body = JSONObject()
            .put("setupCode", setupCode)
            .put("username", username)
            .put("password", password)
            .put("device", device(token, deviceName))
        return when (val reply = post(server, "/v1/register", body, null)) {
            is Http.Ok -> RelayReply.Ok(RelayCredentials(reply.json.getString("accountId"), reply.json.getString("key")))
            is Http.Status -> when (reply.code) {
                403 -> RelayReply.BadSetupCode
                401 -> RelayReply.PasswordRejected
                502 -> RelayReply.Failed("The relay couldn't reach iCloud.")
                else -> RelayReply.Failed("The relay answered with an error (${reply.code}).")
            }
            is Http.Error -> RelayReply.Failed(reply.message)
        }
    }

    override suspend fun checkIn(server: String, credentials: RelayCredentials, token: String, deviceName: String): RelayReply<String> =
        when (val reply = post(server, "/v1/checkin", JSONObject().put("device", device(token, deviceName)), credentials)) {
            is Http.Ok -> RelayReply.Ok(reply.json.optString("status"))
            is Http.Status -> if (reply.code == 401) RelayReply.UnknownKey else RelayReply.Failed("The relay answered with an error (${reply.code}).")
            is Http.Error -> RelayReply.Failed(reply.message)
        }

    override suspend fun unregister(server: String, credentials: RelayCredentials, token: String): RelayReply<Unit> =
        when (val reply = post(server, "/v1/unregister", JSONObject().put("token", token), credentials)) {
            is Http.Ok -> RelayReply.Ok(Unit)
            is Http.Status -> if (reply.code == 401) RelayReply.UnknownKey else RelayReply.Failed("Error ${reply.code}")
            is Http.Error -> RelayReply.Failed(reply.message)
        }

    private fun device(token: String, name: String) = JSONObject().put("token", token).put("name", name)

    private sealed interface Http {
        data class Ok(val json: JSONObject) : Http
        data class Status(val code: Int) : Http
        data class Error(val message: String) : Http
    }

    private suspend fun post(server: String, path: String, body: JSONObject, credentials: RelayCredentials?): Http =
        withContext(Dispatchers.IO) {
            try {
                val connection = URL(server + path).openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "POST"
                    connection.connectTimeout = timeoutMs
                    connection.readTimeout = timeoutMs
                    connection.doOutput = true
                    connection.instanceFollowRedirects = false
                    connection.setRequestProperty("Content-Type", "application/json")
                    credentials?.let { connection.setRequestProperty("Authorization", "Bearer ${it.accountId}.${it.key}") }
                    connection.outputStream.use { it.write(body.toString().toByteArray()) }
                    val code = connection.responseCode
                    if (code in 200..299) {
                        Http.Ok(JSONObject(connection.inputStream.bufferedReader().use { it.readText() }))
                    } else {
                        Http.Status(code)
                    }
                } finally {
                    connection.disconnect()
                }
            } catch (e: IOException) {
                Http.Error("Couldn't reach the relay. Check the address and your connection.")
            } catch (e: org.json.JSONException) {
                Http.Error("That doesn't look like a Kylie Mail relay.")
            }
        }
}
