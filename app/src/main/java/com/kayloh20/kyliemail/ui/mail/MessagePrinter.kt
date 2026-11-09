package com.kayloh20.kyliemail.ui.mail

import android.content.Context
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintManager
import android.webkit.WebView
import com.kayloh20.kyliemail.data.db.MessageEntity
import com.kayloh20.kyliemail.data.mail.EmailHtml
import com.kayloh20.kyliemail.data.mail.InlineImageStore

/** Prints a message, or saves it as a PDF, through the system print dialog. */
object MessagePrinter {
    // Holds each WebView only while its print job is pending; removed when the job finishes.
    @android.annotation.SuppressLint("StaticFieldLeak")
    private val pending = mutableSetOf<WebView>()

    fun print(activityContext: Context, message: MessageEntity, allowRemote: Boolean, inlineImages: InlineImageStore) {
        val view = WebView(activityContext).apply { configureForEmail(darken = false) }
        val client = EmailWebViewClient(activityContext.applicationContext, inlineImages).apply {
            messageId = message.id
            this.allowRemote = allowRemote
        }
        client.onPageFinished = { loaded ->
            client.onPageFinished = null
            val jobName = message.subject.ifBlank { "Email" }
            val printManager = activityContext.getSystemService(PrintManager::class.java)
            // The print job keeps the WebView alive until it finishes, then frees it.
            val adapter = ReleasingAdapter(loaded.createPrintDocumentAdapter(jobName)) {
                pending.remove(loaded)
                loaded.destroy()
            }
            if (printManager == null) {
                pending.remove(loaded)
                loaded.destroy()
            } else {
                printManager.print(jobName, adapter, PrintAttributes.Builder().build())
            }
        }
        view.webViewClient = client
        pending.add(view)
        view.loadDataWithBaseURL(EmailHtml.BASE_URL, EmailHtml.prepareForDisplay(printHtml(message)), "text/html", "utf-8", null)
    }

    private fun printHtml(message: MessageEntity): String {
        val header = buildString {
            append("<div style=\"font-family:sans-serif;margin-bottom:16px\">")
            append("<h2 style=\"margin:0 0 8px\">").append(EmailHtml.escape(message.subject.ifBlank { "(no subject)" })).append("</h2>")
            append("<div>From: ").append(EmailHtml.escape(senderLine(message))).append("</div>")
            if (message.toList.isNotBlank()) append("<div>To: ").append(EmailHtml.escape(message.toList)).append("</div>")
            if (message.ccList.isNotBlank()) append("<div>Cc: ").append(EmailHtml.escape(message.ccList)).append("</div>")
            append("<div>Date: ").append(EmailHtml.escape(formatFullDate(message.sentAt))).append("</div>")
            append("<hr></div>")
        }
        val html = message.bodyHtml?.takeIf { it.isNotBlank() }
        val body = html ?: EmailHtml.plainTextToHtml(message.bodyText.orEmpty())
        return header + body
    }
}

private class ReleasingAdapter(
    private val delegate: PrintDocumentAdapter,
    private val onFinished: () -> Unit,
) : PrintDocumentAdapter() {
    override fun onStart() = delegate.onStart()

    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes,
        cancellationSignal: CancellationSignal?,
        callback: LayoutResultCallback,
        extras: Bundle?,
    ) = delegate.onLayout(oldAttributes, newAttributes, cancellationSignal, callback, extras)

    override fun onWrite(
        pages: Array<out PageRange>,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal?,
        callback: WriteResultCallback,
    ) = delegate.onWrite(pages, destination, cancellationSignal, callback)

    override fun onFinish() {
        delegate.onFinish()
        onFinished()
    }
}

fun senderLine(message: MessageEntity): String = buildString {
    append(message.fromName.ifBlank { message.fromAddress })
    if (message.fromAddress.isNotBlank() && message.fromAddress != message.fromName) {
        append(" <").append(message.fromAddress).append(">")
    }
}
