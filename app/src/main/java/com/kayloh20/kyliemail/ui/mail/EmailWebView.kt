package com.kayloh20.kyliemail.ui.mail

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.kayloh20.kyliemail.data.mail.EmailHtml
import com.kayloh20.kyliemail.data.mail.InlineImageStore
import java.io.ByteArrayInputStream
import java.io.FileInputStream

/**
 * Shows an email's HTML. Scripts are off, local files are unreachable, remote content is blocked
 * unless [allowRemote] is true, and links open in the browser only when clicked.
 */
@Composable
fun EmailWebView(
    messageId: Long,
    html: String,
    allowRemote: Boolean,
    inlineImages: InlineImageStore,
    onRemoteBlocked: () -> Unit,
    onOpenAttachment: (path: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val currentOnRemoteBlocked by rememberUpdatedState(onRemoteBlocked)
    val currentOnOpenAttachment by rememberUpdatedState(onOpenAttachment)
    val client = remember { EmailWebViewClient(context.applicationContext, inlineImages) }
    client.onRemoteBlocked = { currentOnRemoteBlocked() }
    client.onOpenAttachment = { currentOnOpenAttachment(it) }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                configureForEmail(darken = true)
                webViewClient = client
            }
        },
        update = { view ->
            client.messageId = messageId
            client.allowRemote = allowRemote
            val key = "$messageId:$allowRemote:${html.hashCode()}"
            if (view.tag != key) {
                view.tag = key
                view.loadDataWithBaseURL(EmailHtml.BASE_URL, EmailHtml.prepareForDisplay(html), "text/html", "utf-8", null)
            }
        },
        onRelease = { it.destroy() },
    )
}

fun WebView.configureForEmail(darken: Boolean) {
    settings.apply {
        javaScriptEnabled = false
        javaScriptCanOpenWindowsAutomatically = false
        setSupportMultipleWindows(false)
        allowFileAccess = false
        allowContentAccess = false
        domStorageEnabled = false
        setGeolocationEnabled(false)
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        builtInZoomControls = true
        displayZoomControls = false
    }
    if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
        WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, darken)
    }
    isVerticalScrollBarEnabled = true
    setBackgroundColor(if (darken) Color.TRANSPARENT else Color.WHITE)
}

class EmailWebViewClient(
    private val appContext: Context,
    private val inlineImages: InlineImageStore,
) : WebViewClient() {
    @Volatile var messageId: Long = 0
    @Volatile var allowRemote: Boolean = false
    var onRemoteBlocked: () -> Unit = {}
    var onPageFinished: ((WebView) -> Unit)? = null
    var onOpenAttachment: (path: String) -> Unit = {}

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val url = request.url
        if (url.host == EmailHtml.INLINE_HOST) {
            val key = url.lastPathSegment ?: return empty()
            val (file, mimeType) = inlineImages.find(messageId, key) ?: return empty()
            return WebResourceResponse(mimeType, null, FileInputStream(file))
        }
        if (EmailHtml.isRemote(url.toString())) {
            if (allowRemote) return null
            mainHandler.post { onRemoteBlocked() }
            return empty()
        }
        // Anything else (file:, content:, unknown schemes) is not something an email should load.
        return if (url.scheme == "data") null else empty()
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        // Navigations the user didn't click (meta refresh, for example) are dropped.
        if (!request.isForMainFrame || !request.hasGesture()) return true
        val url = request.url
        // File cards in the message text open the attachment in the app.
        if (url.host == EmailHtml.INLINE_HOST && url.path?.startsWith("/attachment/") == true) {
            onOpenAttachment(url.path!!.removePrefix("/attachment/"))
        } else {
            openExternally(url)
        }
        return true
    }

    override fun onPageFinished(view: WebView, url: String?) {
        onPageFinished?.invoke(view)
    }

    private fun openExternally(uri: Uri) {
        if (uri.scheme?.lowercase() !in setOf("http", "https", "mailto", "tel")) return
        try {
            appContext.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
        }
    }

    private fun empty() = WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
}
