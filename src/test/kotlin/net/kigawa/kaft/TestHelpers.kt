package net.kigawa.kaft

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.server.config.*
import java.nio.file.Path
import java.util.*

const val TEST_JWT_SECRET = "test-jwt-secret"
const val TEST_INTERNAL_SECRET = "test-internal-secret"
const val TEST_JWT_ISSUER = "kaft-test"
const val TEST_INTERNAL_ISSUER = "api-server-test"
const val TEST_INTERNAL_AUDIENCE = "kaft-test"

fun createTestConfig(storageDir: Path): ApplicationConfig = MapApplicationConfig(
    "ktor.deployment.port" to "8080",
    "kaft.storage.path" to storageDir.toString(),
    "kaft.jwt.secret" to TEST_JWT_SECRET,
    "kaft.jwt.issuer" to TEST_JWT_ISSUER,
    "kaft.jwt.expirationSeconds" to "3600",
    "kaft.internal.jwtSecret" to TEST_INTERNAL_SECRET,
    "kaft.internal.issuer" to TEST_INTERNAL_ISSUER,
    "kaft.internal.audience" to TEST_INTERNAL_AUDIENCE,
)

fun issueUploadToken(uuid: String): String =
    JWT.create()
        .withIssuer(TEST_JWT_ISSUER)
        .withSubject(uuid)
        .withClaim("scope", "upload")
        .withIssuedAt(Date())
        .withExpiresAt(Date(System.currentTimeMillis() + 3600_000))
        .sign(Algorithm.HMAC256(TEST_JWT_SECRET))

fun issueReadToken(uuids: List<String>): String =
    JWT.create()
        .withIssuer(TEST_JWT_ISSUER)
        .withClaim("uuids", uuids)
        .withClaim("scope", "read")
        .withIssuedAt(Date())
        .withExpiresAt(Date(System.currentTimeMillis() + 3600_000))
        .sign(Algorithm.HMAC256(TEST_JWT_SECRET))

fun issueInternalToken(): String =
    JWT.create()
        .withIssuer(TEST_INTERNAL_ISSUER)
        .withAudience(TEST_INTERNAL_AUDIENCE)
        .withClaim("scope", "internal")
        .withIssuedAt(Date())
        .withExpiresAt(Date(System.currentTimeMillis() + 3600_000))
        .sign(Algorithm.HMAC256(TEST_INTERNAL_SECRET))
