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

data class R2StorageConfig(
    val accountId: String,
    val bucket: String,
    val accessKeyId: String,
    val secretAccessKey: String,
)

sealed class StorageConfig {
    data class Local(val path: Path) : StorageConfig()
    data class R2(val config: R2StorageConfig) : StorageConfig()
}

data class KaftConfig(
    val storage: StorageConfig,
    val jwt: JwtConfig,
    val internal: InternalConfig,
) {
    companion object {
        private const val DEFAULT_JWT_SECRET = "change-this-secret-in-production"
        private const val DEFAULT_INTERNAL_JWT_SECRET = "change-this-internal-secret-in-production"

        private fun requireSecureSecret(value: String, envVarName: String): String {
            check(value.isNotBlank() && value != DEFAULT_JWT_SECRET && value != DEFAULT_INTERNAL_JWT_SECRET) {
                "環境変数 $envVarName が未設定、またはデフォルト値のままです。安全な値を設定してください。"
            }
            return value
        }

        fun fromApplication(app: Application): KaftConfig {
            val config = app.environment.config
            val backend = config.propertyOrNull("kaft.storage.backend")?.getString() ?: "local"
            val storage = when (backend) {
                "r2" -> StorageConfig.R2(
                    R2StorageConfig(
                        accountId = config.property("kaft.storage.r2.accountId").getString(),
                        bucket = config.property("kaft.storage.r2.bucket").getString(),
                        accessKeyId = config.property("kaft.storage.r2.accessKeyId").getString(),
                        secretAccessKey = config.property("kaft.storage.r2.secretAccessKey").getString(),
                    ),
                )
                "local" -> StorageConfig.Local(Paths.get(config.property("kaft.storage.path").getString()))
                else -> error("Unknown kaft.storage.backend: $backend")
            }
            return KaftConfig(
                storage = storage,
                jwt = JwtConfig(
                    secret = requireSecureSecret(config.property("kaft.jwt.secret").getString(), "KAFT_JWT_SECRET"),
                    issuer = config.property("kaft.jwt.issuer").getString(),
                    expirationSeconds = config.property("kaft.jwt.expirationSeconds").getString().toLong(),
                ),
                internal = InternalConfig(
                    jwtSecret = requireSecureSecret(
                        config.property("kaft.internal.jwtSecret").getString(),
                        "KAFT_INTERNAL_JWT_SECRET",
                    ),
                ),
            )
        }
    }
}
