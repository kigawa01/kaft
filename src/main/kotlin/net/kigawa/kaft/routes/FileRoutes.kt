package net.kigawa.kaft.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import net.kigawa.kaft.auth.JwtService
import net.kigawa.kaft.storage.FileState
import net.kigawa.kaft.storage.FileStorage
import net.kigawa.kaft.storage.Visibility

fun Application.configureFileRoutes(jwtService: JwtService, fileStorage: FileStorage) {
    routing {
        put("/files/{uuid}") {
            val uuid = call.parameters["uuid"] ?: return@put call.respond(HttpStatusCode.BadRequest)
            val token = call.request.authorization()?.removePrefix("Bearer ")
                ?: return@put call.respond(HttpStatusCode.Unauthorized)

            if (!jwtService.verifyUploadToken(token, uuid)) {
                return@put call.respond(HttpStatusCode.Unauthorized)
            }

            if (fileStorage.exists(uuid)) {
                return@put call.respond(HttpStatusCode.Conflict)
            }

            val data = call.receive<ByteArray>()
            fileStorage.savePending(uuid, data)
            call.respond(HttpStatusCode.Created)
        }

        get("/files/{uuid}/{filename}") {
            val uuid = call.parameters["uuid"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val filename = call.parameters["filename"] ?: return@get call.respond(HttpStatusCode.BadRequest)

            val meta = fileStorage.getMeta(uuid) ?: return@get call.respond(HttpStatusCode.NotFound)

            if (meta.state != FileState.CONFIRMED) {
                return@get call.respond(HttpStatusCode.NotFound)
            }

            if (meta.visibility == Visibility.PRIVATE) {
                val token = call.request.authorization()?.removePrefix("Bearer ")
                    ?: return@get call.respond(HttpStatusCode.Unauthorized)
                if (!jwtService.verifyReadToken(token, uuid)) {
                    return@get call.respond(HttpStatusCode.Unauthorized)
                }
            }

            val data = fileStorage.getBytes(uuid) ?: return@get call.respond(HttpStatusCode.NotFound)

            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, filename).toString()
            )

            if (meta.visibility == Visibility.PUBLIC) {
                call.response.header(HttpHeaders.CacheControl, "public, max-age=31536000, immutable")
            }

            call.respondBytes(data, ContentType.Application.OctetStream)
        }
    }
}
