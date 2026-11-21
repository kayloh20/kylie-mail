package com.kayloh20.kyliemail.web

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respondText

/**
 * The browser rules every page runs under. The site loads only its own scripts and styles, sends
 * data only to itself, can't be framed by anyone, and leaks nothing about where you came from.
 * Email bodies get a separate, even stricter policy when they're shown (see the reading step).
 */
object SecurityHeaders {
    const val CONTENT_SECURITY_POLICY =
        "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:; " +
            "font-src 'self'; connect-src 'self'; frame-src 'self'; manifest-src 'self'; " +
            "base-uri 'none'; form-action 'self'; frame-ancestors 'none'"

    val all: Map<String, String> = mapOf(
        "Content-Security-Policy" to CONTENT_SECURITY_POLICY,
        "Strict-Transport-Security" to "max-age=63072000; includeSubDomains",
        "X-Content-Type-Options" to "nosniff",
        "X-Frame-Options" to "DENY",
        "Referrer-Policy" to "no-referrer",
        "Cross-Origin-Opener-Policy" to "same-origin",
        "Cross-Origin-Resource-Policy" to "same-origin",
        "Cross-Origin-Embedder-Policy" to "require-corp",
        "Permissions-Policy" to
            "camera=(), microphone=(), geolocation=(), payment=(), usb=(), interest-cohort=(), " +
            "publickey-credentials-get=(self), publickey-credentials-create=(self)",
        "X-Robots-Tag" to "noindex, nofollow",
    )
}

/** Adds [SecurityHeaders] to every response and keeps mail out of every cache. */
val SecurityHeadersPlugin = createApplicationPlugin("SecurityHeaders") {
    onCall { call ->
        for ((name, value) in SecurityHeaders.all) call.response.headers.append(name, value, safeOnly = false)
        val cache = if (call.request.path().startsWith("/assets/")) "no-cache" else "no-store"
        call.response.headers.append(HttpHeaders.CacheControl, cache, safeOnly = false)
    }
}

/**
 * Turns away requests that change something unless they come from this site's own pages, so
 * another website can't make your browser act on your mail (cross-site request forgery).
 */
class SameOriginConfig {
    lateinit var origin: String
}

val SameOriginPlugin = createApplicationPlugin("SameOrigin", ::SameOriginConfig) {
    val origin = pluginConfig.origin
    onCall { call ->
        val method = call.request.httpMethod
        if (method == HttpMethod.Get || method == HttpMethod.Head || method == HttpMethod.Options) return@onCall
        val sentFrom = call.request.header(HttpHeaders.Origin)
        val fetchSite = call.request.header("Sec-Fetch-Site")
        if (sentFrom != origin || (fetchSite != null && fetchSite != "same-origin")) {
            call.respondText("Requests must come from Kylie Mail itself.", status = HttpStatusCode.Forbidden)
        }
    }
}
