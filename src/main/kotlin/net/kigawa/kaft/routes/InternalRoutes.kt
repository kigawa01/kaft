package net.kigawa.kaft.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import net.kigawa.kaft.auth.JwtService
import net.kigawa.kaft.storage.FileId
import net.kigawa.kaft.storage.FileStorage
import net.kigawa.kaft.storage.Visibility

@Serializable
data class TokenRequest(
    val scope: String,
    val uuid: String? = null,
    val uuids: List<String>? = null,
)

@Serializable
data class TokenResponse(val token: String)

@Serializable
data class VisibilityRequest(val visibility: String)

fun Application.configureInternalRoutes(jwtService: JwtService, fileStorage: FileStorage) {
    routing {
        authenticate("internal") {
            route("/internal") {
                post("/token") {
                    val req = call.receive<TokenRequest>()
                    val token = when (req.scope) {
                        "upload" -> {
                            val uuid = req.uuid ?: return@post call.respond(HttpStatusCode.BadRequest)
                            jwtService.issueUploadToken(uuid)
                        }
                        "read" -> {
                            val uuids = req.uuids ?: return@post call.respond(HttpStatusCode.BadRequest)
                            jwtService.issueReadToken(uuids)
                        }
                        else -> return@post call.respond(HttpStatusCode.BadRequest)
                    }
                    call.respond(TokenResponse(token))
                }

                post("/files/{uuid}/confirm") {
                    val fileId = call.parameters["uuid"]?.let { FileId.parseOrNull(it) }
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                    if (!fileStorage.exists(fileId)) return@post call.respond(HttpStatusCode.NotFound)
                    fileStorage.confirm(fileId)
                    call.respond(HttpStatusCode.OK)
                }

                delete("/files/{uuid}") {
                    val fileId = call.parameters["uuid"]?.let { FileId.parseOrNull(it) }
                        ?: return@delete call.respond(HttpStatusCode.BadRequest)
                    if (!fileStorage.exists(fileId)) return@delete call.respond(HttpStatusCode.NotFound)
                    fileStorage.delete(fileId)
                    call.respond(HttpStatusCode.NoContent)
                }

                patch("/files/{uuid}/visibility") {
                    val fileId = call.parameters["uuid"]?.let { FileId.parseOrNull(it) }
                        ?: return@patch call.respond(HttpStatusCode.BadRequest)
                    if (!fileStorage.exists(fileId)) return@patch call.respond(HttpStatusCode.NotFound)
                    val req = call.receive<VisibilityRequest>()
                    val visibility = when (req.visibility) {
                        "public" -> Visibility.PUBLIC
                        "private" -> Visibility.PRIVATE
                        else -> return@patch call.respond(HttpStatusCode.BadRequest)
                    }
                    fileStorage.updateVisibility(fileId, visibility)
                    call.respond(HttpStatusCode.OK)
                }
            }
        }
    }
}
