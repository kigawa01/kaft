package net.kigawa.kaft.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Application.configureHealthRoutes() {
    routing {
        get("/health") {
            call.respondText("ok", status = HttpStatusCode.OK)
        }
    }
}
