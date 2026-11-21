package com.kayloh20.kyliemail.web

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ServerTest {
    private val config = WebConfig(origin = "https://mail.example.org", dataDir = "build/test-data")

    private fun server(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { webModule(config) }
        block()
    }

    @Test
    fun healthCheck() = server {
        val response = client.get("/healthz")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("ok", response.bodyAsText())
    }

    @Test
    fun servesTheAppWithStrictHeaders() = server {
        val response = client.get("/")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue("Kylie Mail" in response.bodyAsText())
        for ((name, value) in SecurityHeaders.all) assertEquals(value, response.headers[name], name)
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        val csp = response.headers["Content-Security-Policy"]!!
        assertTrue("frame-ancestors 'none'" in csp)
        assertTrue("'unsafe-inline'" !in csp && "'unsafe-eval'" !in csp)
    }

    @Test
    fun pageHasNoInlineScriptsOrStyles() = server {
        val html = client.get("/").bodyAsText()
        assertTrue(Regex("<script(?![^>]*\\bsrc=)").find(html) == null, "inline script")
        assertTrue("<style" !in html && "style=" !in html, "inline style")
    }

    @Test
    fun assetsAreServed() = server {
        assertEquals(HttpStatusCode.OK, client.get("/assets/app.js").status)
        assertEquals(HttpStatusCode.OK, client.get("/assets/app.css").status)
        assertEquals("no-cache", client.get("/assets/app.css").headers[HttpHeaders.CacheControl])
    }

    @Test
    fun unknownPathsAreNotFound() = server {
        val response = client.get("/nope")
        assertEquals(HttpStatusCode.NotFound, response.status)
        // Even errors carry the security headers.
        assertEquals(SecurityHeaders.CONTENT_SECURITY_POLICY, response.headers["Content-Security-Policy"])
    }

    @Test
    fun changesFromOtherSitesAreRefused() = server {
        assertEquals(HttpStatusCode.Forbidden, client.post("/anything").status)
        assertEquals(HttpStatusCode.Forbidden, client.post("/anything") { header(HttpHeaders.Origin, "https://evil.example") }.status)
        assertEquals(
            HttpStatusCode.Forbidden,
            client.post("/anything") {
                header(HttpHeaders.Origin, config.origin)
                header("Sec-Fetch-Site", "cross-site")
            }.status,
        )
        val own = client.post("/anything") {
            header(HttpHeaders.Origin, config.origin)
            header("Sec-Fetch-Site", "same-origin")
        }
        assertNotEquals(HttpStatusCode.Forbidden, own.status)
    }

    @Test
    fun originMustBeHttps() {
        WebConfig("http://localhost:8080", "x")
        for (bad in listOf("http://mail.example.org", "https://mail.example.org/", "mail.example.org", "https://mail.example.org/x")) {
            assertFailsWith<IllegalArgumentException>(bad) { WebConfig(bad, "x") }
        }
    }
}
