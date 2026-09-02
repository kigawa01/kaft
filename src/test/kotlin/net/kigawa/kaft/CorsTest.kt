package net.kigawa.kaft

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CorsTest {

    @TempDir
    lateinit var tempDir: Path

    private fun testApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        environment {
            config = createTestConfig(tempDir)
        }
        application {
            module()
        }
        block()
    }

    @Test
    fun `preflight for an allowed origin is accepted`() = testApp {
        val response = client.options("/files/some-uuid") {
            header(HttpHeaders.Origin, TEST_ALLOWED_ORIGIN)
            header(HttpHeaders.AccessControlRequestMethod, "PUT")
            header(HttpHeaders.AccessControlRequestHeaders, "Authorization")
        }

        assertEquals(TEST_ALLOWED_ORIGIN, response.headers[HttpHeaders.AccessControlAllowOrigin])
    }

    @Test
    fun `preflight for a different origin is rejected`() = testApp {
        val response = client.options("/files/some-uuid") {
            header(HttpHeaders.Origin, "https://not-allowed.invalid")
            header(HttpHeaders.AccessControlRequestMethod, "PUT")
            header(HttpHeaders.AccessControlRequestHeaders, "Authorization")
        }

        assertNull(response.headers[HttpHeaders.AccessControlAllowOrigin])
    }
}
