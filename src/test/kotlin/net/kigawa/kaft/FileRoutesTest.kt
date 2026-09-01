package net.kigawa.kaft

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import io.ktor.server.testing.*
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeStringUtf8
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FileRoutesTest {

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

    private suspend fun ApplicationTestBuilder.uploadConfirmedPublicFile(uuid: String, data: ByteArray) {
        val uploadToken = issueUploadToken(uuid)
        val internalToken = issueInternalToken()
        client.put("/files/$uuid") {
            header(HttpHeaders.Authorization, "Bearer $uploadToken")
            setBody(data)
        }
        client.post("/internal/files/$uuid/confirm") {
            header(HttpHeaders.Authorization, "Bearer $internalToken")
        }
        client.patch("/internal/files/$uuid/visibility") {
            header(HttpHeaders.Authorization, "Bearer $internalToken")
            contentType(ContentType.Application.Json)
            setBody("""{"visibility":"public"}""")
        }
    }

    @Test
    fun `upload and download public file`() = testApp {
        val uuid = UUID.randomUUID().toString()
        val uploadToken = issueUploadToken(uuid)
        val internalToken = issueInternalToken()

        // Upload
        val uploadResponse = client.put("/files/$uuid") {
            header(HttpHeaders.Authorization, "Bearer $uploadToken")
            setBody("hello world".toByteArray())
        }
        assertEquals(HttpStatusCode.Created, uploadResponse.status)

        // Confirm
        val confirmResponse = client.post("/internal/files/$uuid/confirm") {
            header(HttpHeaders.Authorization, "Bearer $internalToken")
        }
        assertEquals(HttpStatusCode.OK, confirmResponse.status)

        // Set public
        val visibilityResponse = client.patch("/internal/files/$uuid/visibility") {
            header(HttpHeaders.Authorization, "Bearer $internalToken")
            contentType(ContentType.Application.Json)
            setBody("""{"visibility":"public"}""")
        }
        assertEquals(HttpStatusCode.OK, visibilityResponse.status)

        // Download without token
        val downloadResponse = client.get("/files/$uuid/test.txt")
        assertEquals(HttpStatusCode.OK, downloadResponse.status)
        assertEquals("hello world", downloadResponse.bodyAsText())
        assertNotNull(downloadResponse.headers[HttpHeaders.CacheControl])
    }

    @Test
    fun `download returns stored content type`() = testApp {
        val uuid = UUID.randomUUID().toString()
        val uploadToken = issueUploadToken(uuid)
        val internalToken = issueInternalToken()

        client.put("/files/$uuid") {
            header(HttpHeaders.Authorization, "Bearer $uploadToken")
            contentType(ContentType.Image.PNG)
            setBody("fake-png-bytes".toByteArray())
        }
        client.post("/internal/files/$uuid/confirm") {
            header(HttpHeaders.Authorization, "Bearer $internalToken")
        }
        client.patch("/internal/files/$uuid/visibility") {
            header(HttpHeaders.Authorization, "Bearer $internalToken")
            contentType(ContentType.Application.Json)
            setBody("""{"visibility":"public"}""")
        }

        val response = client.get("/files/$uuid/photo.png")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(ContentType.Image.PNG, response.contentType())
    }

    @Test
    fun `download falls back to octet-stream when content type was not specified`() = testApp {
        val uuid = UUID.randomUUID().toString()
        val uploadToken = issueUploadToken(uuid)
        val internalToken = issueInternalToken()

        client.put("/files/$uuid") {
            header(HttpHeaders.Authorization, "Bearer $uploadToken")
            setBody("no content type".toByteArray())
        }
        client.post("/internal/files/$uuid/confirm") {
            header(HttpHeaders.Authorization, "Bearer $internalToken")
        }
        client.patch("/internal/files/$uuid/visibility") {
            header(HttpHeaders.Authorization, "Bearer $internalToken")
            contentType(ContentType.Application.Json)
            setBody("""{"visibility":"public"}""")
        }

        val response = client.get("/files/$uuid/file.bin")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(ContentType.Application.OctetStream, response.contentType())
    }

    @Test
    fun `upload and download large file streams correctly`() = testApp {
        val uuid = UUID.randomUUID().toString()
        val uploadToken = issueUploadToken(uuid)
        val internalToken = issueInternalToken()
        val data = ByteArray(8 * 1024 * 1024) { (it % 251).toByte() }

        val uploadResponse = client.put("/files/$uuid") {
            header(HttpHeaders.Authorization, "Bearer $uploadToken")
            setBody(data)
        }
        assertEquals(HttpStatusCode.Created, uploadResponse.status)

        client.post("/internal/files/$uuid/confirm") {
            header(HttpHeaders.Authorization, "Bearer $internalToken")
        }
        client.patch("/internal/files/$uuid/visibility") {
            header(HttpHeaders.Authorization, "Bearer $internalToken")
            contentType(ContentType.Application.Json)
            setBody("""{"visibility":"public"}""")
        }

        val downloadResponse = client.get("/files/$uuid/large.bin")
        assertEquals(HttpStatusCode.OK, downloadResponse.status)
        assertTrue(data.contentEquals(downloadResponse.bodyAsBytes()))
    }

    @Test
    fun `download without range header includes Accept-Ranges`() = testApp {
        val uuid = UUID.randomUUID().toString()
        uploadConfirmedPublicFile(uuid, "0123456789".toByteArray())

        val response = client.get("/files/$uuid/file.bin")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("bytes", response.headers[HttpHeaders.AcceptRanges])
    }

    @Test
    fun `range request with start-end returns 206 with correct slice`() = testApp {
        val uuid = UUID.randomUUID().toString()
        uploadConfirmedPublicFile(uuid, "0123456789".toByteArray())

        val response = client.get("/files/$uuid/file.bin") {
            header(HttpHeaders.Range, "bytes=2-4")
        }
        assertEquals(HttpStatusCode.PartialContent, response.status)
        assertEquals("bytes 2-4/10", response.headers[HttpHeaders.ContentRange])
        assertEquals("234", response.bodyAsText())
    }

    @Test
    fun `range request with open-ended start returns rest of file`() = testApp {
        val uuid = UUID.randomUUID().toString()
        uploadConfirmedPublicFile(uuid, "0123456789".toByteArray())

        val response = client.get("/files/$uuid/file.bin") {
            header(HttpHeaders.Range, "bytes=7-")
        }
        assertEquals(HttpStatusCode.PartialContent, response.status)
        assertEquals("bytes 7-9/10", response.headers[HttpHeaders.ContentRange])
        assertEquals("789", response.bodyAsText())
    }

    @Test
    fun `range request with suffix length returns last N bytes`() = testApp {
        val uuid = UUID.randomUUID().toString()
        uploadConfirmedPublicFile(uuid, "0123456789".toByteArray())

        val response = client.get("/files/$uuid/file.bin") {
            header(HttpHeaders.Range, "bytes=-3")
        }
        assertEquals(HttpStatusCode.PartialContent, response.status)
        assertEquals("bytes 7-9/10", response.headers[HttpHeaders.ContentRange])
        assertEquals("789", response.bodyAsText())
    }

    @Test
    fun `range request out of bounds returns 416`() = testApp {
        val uuid = UUID.randomUUID().toString()
        uploadConfirmedPublicFile(uuid, "0123456789".toByteArray())

        val response = client.get("/files/$uuid/file.bin") {
            header(HttpHeaders.Range, "bytes=100-200")
        }
        assertEquals(HttpStatusCode.RequestedRangeNotSatisfiable, response.status)
        assertEquals("bytes */10", response.headers[HttpHeaders.ContentRange])
    }

    @Test
    fun `malformed range header is ignored and full content is returned`() = testApp {
        val uuid = UUID.randomUUID().toString()
        uploadConfirmedPublicFile(uuid, "0123456789".toByteArray())

        val response = client.get("/files/$uuid/file.bin") {
            header(HttpHeaders.Range, "bytes=abc")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("0123456789", response.bodyAsText())
    }

    @Test
    fun `upload without content-length returns 411`() = testApp {
        val uuid = UUID.randomUUID().toString()
        val uploadToken = issueUploadToken(uuid)

        val response = client.put("/files/$uuid") {
            header(HttpHeaders.Authorization, "Bearer $uploadToken")
            setBody(object : OutgoingContent.WriteChannelContent() {
                override suspend fun writeTo(channel: ByteWriteChannel) {
                    channel.writeStringUtf8("chunked body without content-length")
                }
            })
        }
        assertEquals(HttpStatusCode.LengthRequired, response.status)
    }

    @Test
    fun `upload fails with wrong uuid in token`() = testApp {
        val uuid = UUID.randomUUID().toString()
        val wrongToken = issueUploadToken(UUID.randomUUID().toString())

        val response = client.put("/files/$uuid") {
            header(HttpHeaders.Authorization, "Bearer $wrongToken")
            setBody("data".toByteArray())
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `duplicate upload returns 409`() = testApp {
        val uuid = UUID.randomUUID().toString()
        val uploadToken = issueUploadToken(uuid)

        client.put("/files/$uuid") {
            header(HttpHeaders.Authorization, "Bearer $uploadToken")
            setBody("first".toByteArray())
        }

        val response = client.put("/files/$uuid") {
            header(HttpHeaders.Authorization, "Bearer $uploadToken")
            setBody("second".toByteArray())
        }
        assertEquals(HttpStatusCode.Conflict, response.status)
    }

    @Test
    fun `private file requires read token`() = testApp {
        val uuid = UUID.randomUUID().toString()
        val uploadToken = issueUploadToken(uuid)
        val internalToken = issueInternalToken()

        client.put("/files/$uuid") {
            header(HttpHeaders.Authorization, "Bearer $uploadToken")
            setBody("secret".toByteArray())
        }
        client.post("/internal/files/$uuid/confirm") {
            header(HttpHeaders.Authorization, "Bearer $internalToken")
        }

        // No token -> 401
        val noTokenResponse = client.get("/files/$uuid/file.bin")
        assertEquals(HttpStatusCode.Unauthorized, noTokenResponse.status)

        // With valid read token -> 200
        val readToken = issueReadToken(listOf(uuid))
        val response = client.get("/files/$uuid/file.bin") {
            header(HttpHeaders.Authorization, "Bearer $readToken")
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `delete file`() = testApp {
        val uuid = UUID.randomUUID().toString()
        val uploadToken = issueUploadToken(uuid)
        val internalToken = issueInternalToken()

        client.put("/files/$uuid") {
            header(HttpHeaders.Authorization, "Bearer $uploadToken")
            setBody("data".toByteArray())
        }

        val deleteResponse = client.delete("/internal/files/$uuid") {
            header(HttpHeaders.Authorization, "Bearer $internalToken")
        }
        assertEquals(HttpStatusCode.NoContent, deleteResponse.status)
    }

    @Test
    fun `upload with malformed uuid returns 400`() = testApp {
        val uploadToken = issueUploadToken("not-a-uuid")

        val response = client.put("/files/not-a-uuid") {
            header(HttpHeaders.Authorization, "Bearer $uploadToken")
            setBody("data".toByteArray())
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `download with malformed uuid returns 400`() = testApp {
        val response = client.get("/files/not-a-uuid/file.bin")
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `internal endpoints reject missing token`() = testApp {
        val response = client.post("/internal/token") {
            contentType(ContentType.Application.Json)
            setBody("""{"scope":"upload","uuid":"test"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }
}
