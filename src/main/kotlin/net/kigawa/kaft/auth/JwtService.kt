package net.kigawa.kaft.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import net.kigawa.kaft.config.JwtConfig
import net.kigawa.kaft.config.InternalConfig
import java.util.Date

class JwtService(
    private val jwtConfig: JwtConfig,
    private val internalConfig: InternalConfig,
) {
    private val algorithm = Algorithm.HMAC256(jwtConfig.secret)
    private val internalAlgorithm = Algorithm.HMAC256(internalConfig.jwtSecret)
    private val verifier = JWT.require(algorithm).withIssuer(jwtConfig.issuer).build()
    private val internalVerifier = JWT.require(internalAlgorithm).withClaim("scope", "internal").build()

    fun issueUploadToken(uuid: String): String =
        JWT.create()
            .withIssuer(jwtConfig.issuer)
            .withSubject(uuid)
            .withClaim("scope", "upload")
            .withIssuedAt(Date())
            .withExpiresAt(Date(System.currentTimeMillis() + jwtConfig.expirationSeconds * 1000))
            .sign(algorithm)

    fun issueReadToken(uuids: List<String>): String =
        JWT.create()
            .withIssuer(jwtConfig.issuer)
            .withClaim("uuids", uuids)
            .withClaim("scope", "read")
            .withIssuedAt(Date())
            .withExpiresAt(Date(System.currentTimeMillis() + jwtConfig.expirationSeconds * 1000))
            .sign(algorithm)

    fun verifyUploadToken(token: String, expectedUuid: String): Boolean = try {
        val decoded = verifier.verify(token)
        decoded.getClaim("scope").asString() == "upload" &&
            decoded.subject == expectedUuid
    } catch (_: JWTVerificationException) {
        false
    }

    fun verifyReadToken(token: String, uuid: String): Boolean = try {
        val decoded = verifier.verify(token)
        decoded.getClaim("scope").asString() == "read" &&
            decoded.getClaim("uuids").asList(String::class.java)?.contains(uuid) == true
    } catch (_: JWTVerificationException) {
        false
    }

    fun verifyInternalToken(token: String): Boolean = try {
        internalVerifier.verify(token)
        true
    } catch (_: JWTVerificationException) {
        false
    }
}
