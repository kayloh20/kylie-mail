package com.kayloh20.kyliemail.relay

import com.google.auth.oauth2.GoogleCredentials
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

enum class PushResult { SENT, TOKEN_GONE, FAILED }

/** Wakes a device. The message says only "new mail": nothing about who it's from or what it says. */
fun interface Pusher {
    fun push(token: String): PushResult
}

/** Firebase Cloud Messaging, with a service account key. */
class FcmPusher(serviceAccount: Path) : Pusher {
    private val credentials = Files.newInputStream(serviceAccount).use {
        GoogleCredentials.fromStream(it).createScoped("https://www.googleapis.com/auth/firebase.messaging")
    }
    private val projectId: String = Json.parseToJsonElement(Files.readString(serviceAccount))
        .jsonObject["project_id"]?.jsonPrimitive?.content
        ?: error("The Firebase key file has no project_id")
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val endpoint = URI("https://fcm.googleapis.com/v1/projects/$projectId/messages:send")

    override fun push(token: String): PushResult {
        val body = buildJsonObject {
            putJsonObject("message") {
                put("token", token)
                putJsonObject("data") { put("t", "mail") }
                putJsonObject("android") {
                    // High priority so a sleeping phone wakes for it; collapsed so a burst is one wake.
                    put("priority", "HIGH")
                    put("collapse_key", "mail")
                    put("ttl", "600s")
                }
            }
        }
        val access = synchronized(credentials) {
            credentials.refreshIfExpired()
            credentials.accessToken?.tokenValue ?: error("Firebase gave no access token")
        }
        val request = HttpRequest.newBuilder(endpoint)
            .timeout(Duration.ofSeconds(15))
            .header("Authorization", "Bearer $access")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        return when {
            response.statusCode() in 200..299 -> PushResult.SENT
            // The app was uninstalled or the token replaced: stop sending to it.
            response.statusCode() == 404 || isUnregistered(response.body()) -> PushResult.TOKEN_GONE
            else -> PushResult.FAILED
        }
    }

    private fun isUnregistered(body: String): Boolean = runCatching {
        val error = Json.parseToJsonElement(body).jsonObject["error"] as JsonObject
        body.contains("UNREGISTERED") || error["status"]?.jsonPrimitive?.content == "NOT_FOUND"
    }.getOrDefault(false)
}
