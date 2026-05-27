package net.kigawa.kaft.config

import io.ktor.server.application.*
import java.nio.file.Path
import java.nio.file.Paths

data class JwtConfig(
    val secret: String,
    val issuer: String,
    val expirationSeconds: Long,
)

data class InternalConfig(
    val jwtSecret: String,
)

data class KaftConfig(
    val storagePath: Path,
    val jwt: JwtConfig,
    val internal: InternalConfig,
) {
    companion object {
        fun fromApplication(app: Application): KaftConfig {
            val config = app.environment.config
            return KaftConfig(
                storagePath = Paths.get(config.property("kaft.storage.path").getString()),
                jwt = JwtConfig(
                    secret = config.property("kaft.jwt.secret").getString(),
                    issuer = config.property("kaft.jwt.issuer").getString(),
                    expirationSeconds = config.property("kaft.jwt.expirationSeconds").getString().toLong(),
                ),
                internal = InternalConfig(
                    jwtSecret = config.property("kaft.internal.jwtSecret").getString(),
                ),
            )
        }
    }
}
