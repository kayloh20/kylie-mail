package com.kayloh20.kyliemail.web

import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import com.kayloh20.kyliemail.data.mail.FolderRole
import kotlinx.serialization.Serializable
import javax.mail.Flags

const val EMAIL_BODY_PATH = "/api/message/body"
const val ATTACHMENT_PATH = "/api/message/attachment"

/** Folders mail can be filed to, as in the app. */
val FILING_TARGETS = setOf(FolderRole.CUSTOM, FolderRole.ARCHIVE)

@Serializable data class FlagBody(val folder: String, val validity: Long, val uids: List<Long>, val flag: String, val value: Boolean)
@Serializable data class RemoveBody(
    val folder: String,
    val validity: Long,
    val uids: List<Long>,
    /** "delete", "archive" or "move". */
    val action: String,
    /** The destination folder, for "move". */
    val target: String? = null,
)
@Serializable data class UndoBody(val id: String)
@Serializable data class TrainBody(val folders: List<String>)
@Serializable data class FilingFolder(val id: String, val name: String, val trained: Boolean, val examples: Int)
@Serializable data class FilingReply(val examples: Int, val folders: List<FilingFolder>, val status: TrainingStatus)
@Serializable data class SuggestReply(val trained: Boolean, val suggestions: List<FilingSuggestion>)
@Serializable data class UndoReply(val undone: Boolean)
@Serializable data class TrustBody(val address: String)
@Serializable data class Done(val ok: Boolean = true)

private fun ApplicationCall.param(name: String): String =
    request.queryParameters[name]?.takeIf { it.isNotEmpty() } ?: throw ApiError(HttpStatusCode.BadRequest, "Missing $name.")

private fun ApplicationCall.longParam(name: String): Long =
    param(name).toLongOrNull() ?: throw ApiError(HttpStatusCode.BadRequest, "Bad $name.")

/** Reading mail: folders, message lists, messages, their attachments, and marking them read. */
fun Route.mailRoutes(services: Services) = route("/api") {
    get("/folders") {
        val session = call.requireSession(services)
        call.respond(services.mail(session).folders())
    }

    get("/messages") {
        val session = call.requireSession(services)
        val before = call.request.queryParameters["before"]?.toIntOrNull()
        call.respond(services.mail(session).page(call.param("folder"), before))
    }

    get("/message") {
        val session = call.requireSession(services)
        val folder = call.param("folder")
        val validity = call.longParam("validity")
        val opened = services.mail(session).message(folder, validity, call.longParam("uid"))
        val safe = cleaned(opened, allowImages = false)
        call.respond(
            MessageDetail(
                folder = folder,
                validity = validity,
                summary = opened.summary,
                to = opened.to,
                cc = opened.cc,
                replyTo = opened.replyTo,
                attachments = opened.body.attachments.map { it.summary() },
                remoteImages = safe.remoteImages,
                imagesAllowed = opened.summary.fromAddress.lowercase() in services.vault.imageSenders(session.accountKey),
            ),
        )
    }

    // The email itself, as a page for the sandboxed frame. See SecurityHeadersPlugin for its rules.
    get("/message/body") {
        val session = call.requireSession(services)
        val opened = services.mail(session).message(call.param("folder"), call.longParam("validity"), call.longParam("uid"))
        val safe = cleaned(opened, allowImages = call.request.queryParameters["images"] == "1")
        call.respondText(safe.html, ContentType.Text.Html.withParameter("charset", "utf-8"))
    }

    get("/message/attachment") {
        val session = call.requireSession(services)
        val download = services.mail(session).attachment(call.param("folder"), call.longParam("validity"), call.longParam("uid"), call.param("path"))
        // Always a download, never shown on this site: an HTML or SVG attachment could otherwise run here.
        call.response.header(
            HttpHeaders.ContentDisposition,
            ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, download.filename).toString(),
        )
        call.respondBytes(download.bytes, ContentType.Application.OctetStream)
    }

    post("/messages/flags") {
        val session = call.requireSession(services)
        val body = call.receive<FlagBody>()
        checkCount(body.uids)
        val flag = when (body.flag) {
            "seen" -> Flags.Flag.SEEN
            "flagged" -> Flags.Flag.FLAGGED
            else -> throw ApiError(HttpStatusCode.BadRequest, "Unknown flag.")
        }
        services.mail(session).setFlag(body.folder, body.validity, body.uids, flag, body.value)
        call.respond(Done())
    }

    // Delete, archive and move, with a few seconds to undo (see MailClient.remove).
    post("/messages/remove") {
        val session = call.requireSession(services)
        val body = call.receive<RemoveBody>()
        checkCount(body.uids)
        val mail = services.mail(session)
        val source = mail.roleOf(body.folder)
        val target = when (body.action) {
            // Deleting from Trash, or when there's no Trash, deletes for good, as in the app.
            "delete" -> mail.folderWithRole(FolderRole.TRASH)?.id?.takeIf { source != FolderRole.TRASH }
            "archive" -> mail.folderWithRole(FolderRole.ARCHIVE)?.id?.takeIf { source != FolderRole.ARCHIVE }
                ?: throw ApiError(HttpStatusCode.BadRequest, "There's no Archive folder to move them to.")
            "move" -> body.target?.takeIf { it.isNotEmpty() } ?: throw ApiError(HttpStatusCode.BadRequest, "Choose a folder.")
            else -> throw ApiError(HttpStatusCode.BadRequest, "Unknown action.")
        }
        val (removal, moved) = mail.remove(body.folder, body.validity, body.uids, target)
        // Filing a message teaches smart filing where this kind of mail goes, as in the app.
        if (target != null && body.action != "delete" && mail.roleOf(target) in FILING_TARGETS) {
            runCatching { services.filing(session).recordMoves(removal.id, target, moved) }
        }
        call.respond(removal)
    }

    post("/messages/undo") {
        val session = call.requireSession(services)
        val id = call.receive<UndoBody>().id
        val undone = services.mail(session).undo(id)
        if (undone) session.filing?.forgetMoves(id)
        call.respond(UndoReply(undone))
    }

    // Smart filing.

    get("/filing") {
        val session = call.requireSession(services)
        val mail = services.mail(session)
        val filing = services.filing(session)
        val data = filing.snapshot()
        val counts = data.examples.groupingBy { it.folder }.eachCount()
        val trained = data.trained.map { it.fullName }.toSet()
        val folders = mail.folders().filter { FolderRole.valueOf(it.role.uppercase()) in FILING_TARGETS }
            .map { FilingFolder(it.id, it.name, it.id in trained, counts[it.id] ?: 0) }
        call.respond(FilingReply(data.examples.size, folders, filing.status))
    }

    post("/filing/train") {
        val session = call.requireSession(services)
        val mail = services.mail(session)
        val wanted = call.receive<TrainBody>().folders.toSet()
        val folders = mail.knownFolders().filter { it.id in wanted && FolderRole.valueOf(it.role.uppercase()) in FILING_TARGETS }
        services.filing(session).train(folders.map { it.id to it.name }, mail)
        call.respond(Done())
    }

    post("/filing/stop") {
        val session = call.requireSession(services)
        services.filing(session).stopTraining()
        call.respond(Done())
    }

    get("/filing/suggest") {
        val session = call.requireSession(services)
        val folder = call.param("folder")
        val uids = call.param("uids").split(',').mapNotNull { it.trim().toLongOrNull() }.take(50)
        if (uids.isEmpty()) throw ApiError(HttpStatusCode.BadRequest, "No messages chosen.")
        val mail = services.mail(session)
        val filing = services.filing(session)
        val targets = mail.knownFolders().filter { FolderRole.valueOf(it.role.uppercase()) in FILING_TARGETS }.associate { it.id to it.name }
        val features = mail.features(folder, call.longParam("validity"), uids)
        call.respond(SuggestReply(filing.snapshot().examples.isNotEmpty(), filing.suggest(features, folder, targets)))
    }

    post("/image-senders") {
        val session = call.requireSession(services)
        services.vault.trustImageSender(session.accountKey, call.receive<TrustBody>().address)
        call.respond(Done())
    }
}

private fun checkCount(uids: List<Long>) {
    if (uids.isEmpty()) throw ApiError(HttpStatusCode.BadRequest, "No messages chosen.")
    if (uids.size > 1000) throw ApiError(HttpStatusCode.BadRequest, "Too many messages at once.")
}

private fun cleaned(opened: OpenedMessage, allowImages: Boolean): SafeEmail {
    val body = opened.body
    return when {
        body.html != null -> MailHtml.clean(body.html!!, body.inlineImages, allowImages)
        else -> MailHtml.plainText(body.text.orEmpty())
    }
}
