package net.kigawa.kaft.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.copyTo
import net.kigawa.kaft.auth.JwtService
import net.kigawa.kaft.storage.CreateResult
import net.kigawa.kaft.storage.FileId
import net.kigawa.kaft.storage.FileState
import net.kigawa.kaft.storage.FileStorage
import net.kigawa.kaft.storage.Visibility

private const val DEFAULT_CONTENT_TYPE = "application/octet-stream"

fun Application.configureFileRoutes(jwtService: JwtService, fileStorage: FileStorage) {
    routing {
        put("/files/{uuid}") {
            val fileId = call.parameters["uuid"]?.let { FileId.parseOrNull(it) }
                ?: return@put call.respond(HttpStatusCode.BadRequest)
            val token = call.request.authorization()?.removePrefix("Bearer ")
                ?: return@put call.respond(HttpStatusCode.Unauthorized)

            if (!jwtService.verifyUploadToken(token, fileId.toString())) {
                return@put call.respond(HttpStatusCode.Unauthorized)
            }

            val size = call.request.contentLength()
                ?: return@put call.respond(HttpStatusCode.LengthRequired)
            val contentType = call.request.headers[HttpHeaders.ContentType] ?: DEFAULT_CONTENT_TYPE
            when (fileStorage.createPending(fileId, call.receiveChannel(), size, contentType)) {
                CreateResult.Created -> call.respond(HttpStatusCode.Created)
                CreateResult.AlreadyExists -> call.respond(HttpStatusCode.Conflict)
            }
        }

        get("/files/{uuid}/{filename}") {
            val fileId = call.parameters["uuid"]?.let { FileId.parseOrNull(it) }
                ?: return@get call.respond(HttpStatusCode.BadRequest)
            val filename = call.parameters["filename"] ?: return@get call.respond(HttpStatusCode.BadRequest)

            val meta = fileStorage.getMeta(fileId) ?: return@get call.respond(HttpStatusCode.NotFound)

            if (meta.state != FileState.CONFIRMED) {
                return@get call.respond(HttpStatusCode.NotFound)
            }

            if (meta.visibility == Visibility.PRIVATE) {
                val token = call.request.authorization()?.removePrefix("Bearer ")
                    ?: return@get call.respond(HttpStatusCode.Unauthorized)
                if (!jwtService.verifyReadToken(token, fileId.toString())) {
                    return@get call.respond(HttpStatusCode.Unauthorized)
                }
            }

            val channel = fileStorage.openReadChannel(fileId) ?: return@get call.respond(HttpStatusCode.NotFound)

            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, filename).toString()
            )

            if (meta.visibility == Visibility.PUBLIC) {
                call.response.header(HttpHeaders.CacheControl, "public, max-age=31536000, immutable")
            }

            call.respondBytesWriter(contentType = ContentType.parse(meta.contentType), contentLength = meta.size) {
                channel.copyTo(this)
            }
        }
    }
}
