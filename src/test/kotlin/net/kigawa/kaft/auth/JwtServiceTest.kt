package net.kigawa.kaft.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import net.kigawa.kaft.config.InternalConfig
import net.kigawa.kaft.config.JwtConfig
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JwtServiceTest {

    private val jwtSecret = "jwt-secret"
    private val internalSecret = "internal-secret"
    private val issuer = "api-server"
    private val audience = "kaft"

    private val jwtService = JwtService(
        jwtConfig = JwtConfig(secret = jwtSecret, issuer = "kaft-test", expirationSeconds = 3600),
        internalConfig = InternalConfig(jwtSecret = internalSecret, issuer = issuer, audience = audience),
    )

    private fun buildToken(
        iss: String = issuer,
        aud: String = audience,
        scope: String = "internal",
        expiresInMillis: Long = 3600_000,
    ): String =
        JWT.create()
            .withIssuer(iss)
            .withAudience(aud)
            .withClaim("scope", scope)
            .withIssuedAt(Date())
            .withExpiresAt(Date(System.currentTimeMillis() + expiresInMillis))
            .sign(Algorithm.HMAC256(internalSecret))

    @Test
    fun `valid internal token is accepted`() {
        assertTrue(jwtService.verifyInternalToken(buildToken()))
    }

    @Test
    fun `internal token with wrong issuer is rejected`() {
        assertFalse(jwtService.verifyInternalToken(buildToken(iss = "unknown-issuer")))
    }

    @Test
    fun `internal token with wrong audience is rejected`() {
        assertFalse(jwtService.verifyInternalToken(buildToken(aud = "unknown-audience")))
    }

    @Test
    fun `internal token with wrong scope is rejected`() {
        assertFalse(jwtService.verifyInternalToken(buildToken(scope = "upload")))
    }

    @Test
    fun `expired internal token is rejected`() {
        assertFalse(jwtService.verifyInternalToken(buildToken(expiresInMillis = -1_000)))
    }
}
