package com.kayloh20.kyliemail.web

import com.kayloh20.kyliemail.data.compose.OriginalMessage
import com.kayloh20.kyliemail.data.compose.ReplyBuilder
import com.kayloh20.kyliemail.data.compose.ReplyMode
import com.kayloh20.kyliemail.data.mail.FolderRole
import com.kayloh20.kyliemail.data.mail.htmlToPlainText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable
import java.time.ZoneId
import java.util.Locale

@Serializable data class Identity(val name: String, val signature: String)
@Serializable data class StartBody(
    /** "new", "reply", "replyAll", "forward" or "draft" (edit one from the Drafts folder). */
    val mode: String,
    val message: MessageRef? = null,
    /** The browser's time zone, for "On Monday at 9:41, Mom wrote:". */
    val zone: String? = null,
)
@Serializable data class Started(val draft: Draft, val attachments: List<UploadInfo>, val warning: String? = null)
@Serializable data class IdBody(val id: String)

/** Writing mail: replies, forwards, new messages and drafts, sent in plain text only. */
fun Route.composeRoutes(services: Services) = route("/api/compose") {
    get("/identity") {
        val session = call.requireSession(services)
        val (name, signature) = services.vault.identity(session.accountKey)
        call.respond(Identity(name, signature))
    }

    post("/identity") {
        val session = call.requireSession(services)
        val body = call.receive<Identity>()
        services.vault.setIdentity(session.accountKey, body.name, body.signature)
        call.respond(Done())
    }

    get("/contacts") {
        val session = call.requireSession(services)
        call.respond(services.mail(session).contacts())
    }

    post("/start") {
        val session = call.requireSession(services)
        val body = call.receive<StartBody>()
        val account = services.vault.account() ?: throw ApiError(HttpStatusCode.Conflict, "Kylie Mail isn't set up yet.")
        val signature = services.vault.identity(session.accountKey).second
        if (body.mode == "new") {
            val block = if (signature.isBlank()) "" else "\n\n-- \n${signature.trimEnd()}"
            return@post call.respond(Started(Draft(body = block), emptyList()))
        }
        val ref = body.message ?: throw ApiError(HttpStatusCode.BadRequest, "Choose a message first.")
        val mail = services.mail(session)
        val outbox = services.outbox(session)
        val opened = mail.message(ref.folder, ref.validity, ref.uid)
        val text = opened.body.text?.takeIf { it.isNotBlank() }?.trimEnd()
            ?: opened.body.html?.let(::htmlToPlainText)?.replace(Regex("[ \\t]+\n"), "\n")?.replace(Regex("\n{3,}"), "\n\n")?.trim()
            ?: ""
        if (body.mode == "draft") {
            val files = runCatching { outbox.copyAttachments(ref, opened) }
            val draft = Draft(
                to = opened.to.joinToString(", "),
                cc = opened.cc.joinToString(", "),
                subject = opened.summary.subject.takeIf { it != "(no subject)" }.orEmpty(),
                body = text,
                replaces = ref,
                attachments = files.getOrDefault(emptyList()).map { it.id },
            )
            return@post call.respond(Started(draft, files.getOrDefault(emptyList()), files.exceptionOrNull()?.let { "Some attachments couldn't be added." }))
        }
        val mode = when (body.mode) {
            "reply" -> ReplyMode.REPLY
            "replyAll" -> ReplyMode.REPLY_ALL
            "forward" -> ReplyMode.FORWARD
            else -> throw ApiError(HttpStatusCode.BadRequest, "Unknown way to write.")
        }
        val zone = body.zone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.of("UTC")
        val reply = ReplyBuilder.build(
            OriginalMessage(
                fromName = opened.summary.fromName,
                fromAddress = opened.summary.fromAddress,
                toList = opened.to.joinToString(", "),
                ccList = opened.cc.joinToString(", "),
                subject = opened.summary.subject,
                sentAt = opened.summary.date,
                bodyText = text,
                messageIdHeader = opened.messageIdHeader,
            ),
            mode,
            Filing.ownAddresses(account.email, account.username),
            signature,
            zone,
            Locale.US,
        )
        // Forwards carry the original's files, as in the app.
        val files = if (mode == ReplyMode.FORWARD) runCatching { outbox.copyAttachments(ref, opened) } else Result.success(emptyList())
        val draft = Draft(
            to = reply.to,
            cc = reply.cc,
            subject = reply.subject,
            body = reply.body,
            inReplyTo = reply.inReplyTo,
            references = reply.references,
            replyTo = ref.takeIf { mode != ReplyMode.FORWARD },
            attachments = files.getOrDefault(emptyList()).map { it.id },
        )
        call.respond(Started(draft, files.getOrDefault(emptyList()), files.exceptionOrNull()?.let { "Some of the original's attachments couldn't be added." }))
    }

    // A file to attach: the raw bytes, with its name and type in headers.
    post("/upload") {
        val session = call.requireSession(services)
        val length = call.request.header(HttpHeaders.ContentLength)?.toLongOrNull()
        if (length != null && length > UPLOAD_LIMIT) throw ApiError(HttpStatusCode.PayloadTooLarge, "Files can be up to 14 MB in all; iCloud refuses bigger messages.")
        val bytes = call.receiveChannel().readRemaining(UPLOAD_LIMIT + 1).readByteArray()
        if (bytes.size > UPLOAD_LIMIT) throw ApiError(HttpStatusCode.PayloadTooLarge, "Files can be up to 14 MB in all; iCloud refuses bigger messages.")
        val name = call.request.header("X-Filename")?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull() } ?: "Attachment"
        val type = call.request.header(HttpHeaders.ContentType)?.substringBefore(';')?.trim().orEmpty()
        call.respond(services.outbox(session).upload(name, type, bytes))
    }

    post("/upload/remove") {
        val session = call.requireSession(services)
        services.outbox(session).removeUpload(call.receive<IdBody>().id)
        call.respond(Done())
    }

    post("/send") {
        val session = call.requireSession(services)
        call.respond(services.outbox(session).send(call.receive<Draft>()))
    }

    post("/undo") {
        val session = call.requireSession(services)
        call.respond(UndoReply(services.outbox(session).undo(call.receive<IdBody>().id)))
    }

    get("/status") {
        val session = call.requireSession(services)
        call.respond(session.outbox?.statuses().orEmpty())
    }

    post("/save") {
        val session = call.requireSession(services)
        val draft = call.receive<Draft>()
        val drafts = services.mail(session).folderWithRole(FolderRole.DRAFTS)
            ?: throw ApiError(HttpStatusCode.Conflict, "There's no Drafts folder to save it in.")
        services.outbox(session).save(draft, drafts.id)
        call.respond(Done())
    }

    post("/discard") {
        val session = call.requireSession(services)
        services.outbox(session).discard(call.receive<Draft>())
        call.respond(Done())
    }
}

/** One file's upload limit: iCloud's limit for a whole message, with room for encoding. */
private const val UPLOAD_LIMIT = 14L * 1024 * 1024
