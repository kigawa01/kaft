package net.kigawa.kaft.config

import io.ktor.client.request.*
import io.ktor.server.config.*
import io.ktor.server.testing.*
import net.kigawa.kaft.module
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFailsWith

class KaftConfigTest {

    @TempDir
    lateinit var tempDir: Path

    private fun testAppConfig(jwtSecret: String, internalJwtSecret: String): ApplicationConfig =
        MapApplicationConfig(
            "kaft.storage.path" to tempDir.toString(),
            "kaft.jwt.secret" to jwtSecret,
            "kaft.jwt.issuer" to "kaft-test",
            "kaft.jwt.expirationSeconds" to "3600",
            "kaft.internal.jwtSecret" to internalJwtSecret,
            "kaft.internal.issuer" to "api-server-test",
            "kaft.internal.audience" to "kaft-test",
        )

    @Test
    fun `startup fails when jwt secret is blank`() = testApplication {
        environment { config = testAppConfig("", "valid-internal-secret") }
        application { module() }
        assertFailsWith<IllegalStateException> { client.get("/health") }
    }

    @Test
    fun `startup fails when jwt secret is default value`() = testApplication {
        environment { config = testAppConfig("change-this-secret-in-production", "valid-internal-secret") }
        application { module() }
        assertFailsWith<IllegalStateException> { client.get("/health") }
    }

    @Test
    fun `startup fails when internal jwt secret is blank`() = testApplication {
        environment { config = testAppConfig("valid-jwt-secret", "") }
        application { module() }
        assertFailsWith<IllegalStateException> { client.get("/health") }
    }

    @Test
    fun `startup fails when internal jwt secret is default value`() = testApplication {
        environment {
            config = testAppConfig("valid-jwt-secret", "change-this-internal-secret-in-production")
        }
        application { module() }
        assertFailsWith<IllegalStateException> { client.get("/health") }
    }

    @Test
    fun `startup succeeds with secure secrets`() = testApplication {
        environment { config = testAppConfig("valid-jwt-secret", "valid-internal-secret") }
        application { module() }
        client.get("/health")
    }
}
