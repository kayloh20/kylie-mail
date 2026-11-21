package com.kayloh20.kyliemail.web

import java.net.URI

data class WebConfig(
    /** Scheme and host only, with no trailing slash, such as https://mail.example.org. */
    val origin: String,
    val dataDir: String,
) {
    init {
        val uri = runCatching { URI(origin) }.getOrNull()
        require(
            uri != null && uri.host != null && uri.path.isNullOrEmpty() && uri.query == null &&
                (uri.scheme == "https" || (uri.scheme == "http" && uri.host == "localhost"))
        ) { "PUBLIC_ORIGIN must look like https://mail.example.org (plain http only for localhost)" }
    }

    val host: String get() = URI(origin).host
}
