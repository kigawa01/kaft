package net.kigawa.kaft

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import net.kigawa.kaft.auth.JwtService
import net.kigawa.kaft.config.KaftConfig
import net.kigawa.kaft.config.StorageConfig
import net.kigawa.kaft.routes.configureFileRoutes
import net.kigawa.kaft.routes.configureHealthRoutes
import net.kigawa.kaft.routes.configureInternalRoutes
import net.kigawa.kaft.storage.FileStorage
import net.kigawa.kaft.storage.LocalFileStorage
import net.kigawa.kaft.storage.R2FileStorage

fun Application.module() {
    val config = KaftConfig.fromApplication(this)
    val jwtService = JwtService(config.jwt, config.internal)
    val fileStorage: FileStorage = when (val storage = config.storage) {
        is StorageConfig.Local -> LocalFileStorage(storage.path)
        is StorageConfig.R2 -> R2FileStorage(storage.config)
    }

    install(ContentNegotiation) {
        json()
    }

    // ブラウザがfiles APIへ直接PUT/GETするため（KaftConfig.cors参照）。
    install(CORS) {
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Put)
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        config.cors.allowedOrigins.forEach { origin ->
            val url = Url(origin)
            allowHost(url.host, schemes = listOf(url.protocol.name))
        }
    }

    install(Authentication) {
        bearer("internal") {
            authenticate { credential ->
                if (jwtService.verifyInternalToken(credential.token)) UserIdPrincipal("api-server") else null
            }
        }
    }

    configureHealthRoutes()
    configureFileRoutes(jwtService, fileStorage)
    configureInternalRoutes(jwtService, fileStorage)
}
