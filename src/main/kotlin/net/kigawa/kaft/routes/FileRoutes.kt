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

private sealed interface RangeResult {
    data object Absent : RangeResult
    data object NotSatisfiable : RangeResult
    data class Satisfiable(val range: LongRange) : RangeResult
}

private fun parseRange(header: String?, size: Long): RangeResult {
    if (header == null || !header.startsWith("bytes=")) return RangeResult.Absent
    val spec = header.removePrefix("bytes=")
    if (spec.contains(',')) return RangeResult.Absent
    val dashIndex = spec.indexOf('-')
    if (dashIndex < 0) return RangeResult.Absent
    val startStr = spec.substring(0, dashIndex)
    val endStr = spec.substring(dashIndex + 1)

    if (startStr.isEmpty()) {
        val suffixLength = endStr.toLongOrNull() ?: return RangeResult.Absent
        if (suffixLength <= 0) return RangeResult.Absent
        if (size <= 0) return RangeResult.NotSatisfiable
        val start = maxOf(0, size - suffixLength)
        return RangeResult.Satisfiable(start until size)
    }

    val start = startStr.toLongOrNull() ?: return RangeResult.Absent
    if (start < 0) return RangeResult.Absent
    val end = if (endStr.isEmpty()) size - 1 else (endStr.toLongOrNull() ?: return RangeResult.Absent)
    if (start > end) return RangeResult.Absent
    if (start >= size) return RangeResult.NotSatisfiable
    return RangeResult.Satisfiable(start..minOf(end, size - 1))
}

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

            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, filename).toString()
            )
            call.response.header(HttpHeaders.AcceptRanges, "bytes")

            if (meta.visibility == Visibility.PUBLIC) {
                call.response.header(HttpHeaders.CacheControl, "public, max-age=31536000, immutable")
            }

            when (val rangeResult = parseRange(call.request.headers[HttpHeaders.Range], meta.size)) {
                RangeResult.NotSatisfiable -> {
                    call.response.header(HttpHeaders.ContentRange, "bytes */${meta.size}")
                    call.respond(HttpStatusCode.RequestedRangeNotSatisfiable)
                }
                is RangeResult.Satisfiable -> {
                    val range = rangeResult.range
                    val length = range.last - range.first + 1
                    val channel = fileStorage.openReadChannel(fileId, range)
                        ?: return@get call.respond(HttpStatusCode.NotFound)
                    call.response.header(HttpHeaders.ContentRange, "bytes ${range.first}-${range.last}/${meta.size}")
                    call.respondBytesWriter(
                        contentType = ContentType.parse(meta.contentType),
                        status = HttpStatusCode.PartialContent,
                        contentLength = length,
                    ) {
                        channel.copyTo(this, limit = length)
                    }
                }
                RangeResult.Absent -> {
                    val channel = fileStorage.openReadChannel(fileId) ?: return@get call.respond(HttpStatusCode.NotFound)
                    call.respondBytesWriter(contentType = ContentType.parse(meta.contentType), contentLength = meta.size) {
                        channel.copyTo(this, limit = meta.size)
                    }
                }
            }
        }
    }
}
