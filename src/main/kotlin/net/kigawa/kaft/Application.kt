package net.kigawa.kaft

import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.contentnegotiation.*
import net.kigawa.kaft.auth.JwtService
import net.kigawa.kaft.config.KaftConfig
import net.kigawa.kaft.routes.configureFileRoutes
import net.kigawa.kaft.routes.configureInternalRoutes
import net.kigawa.kaft.storage.FileStorage

fun Application.module() {
    val config = KaftConfig.fromApplication(this)
    val jwtService = JwtService(config.jwt, config.internal)
    val fileStorage = FileStorage(config.storagePath)

    install(ContentNegotiation) {
        json()
    }

    install(Authentication) {
        bearer("internal") {
            authenticate { credential ->
                if (jwtService.verifyInternalToken(credential.token)) UserIdPrincipal("api-server") else null
            }
        }
    }

    configureFileRoutes(jwtService, fileStorage)
    configureInternalRoutes(jwtService, fileStorage)
}
