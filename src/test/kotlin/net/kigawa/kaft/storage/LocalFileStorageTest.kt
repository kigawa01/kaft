package net.kigawa.kaft.storage

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.toByteArray
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalFileStorageTest {

    @TempDir
    lateinit var tempDir: Path

    private fun createPending(storage: LocalFileStorage, id: FileId, data: ByteArray, contentType: String) =
        runBlocking { storage.createPending(id, ByteReadChannel(data), data.size.toLong(), contentType) }

    @Test
    fun `createPending on new id succeeds`() {
        val storage = LocalFileStorage(tempDir)
        val id = FileId(UUID.randomUUID())

        val result = createPending(storage, id, "data".toByteArray(), "text/plain")

        assertEquals(CreateResult.Created, result)
        assertTrue(storage.exists(id))
    }

    @Test
    fun `createPending on existing id returns AlreadyExists`() {
        val storage = LocalFileStorage(tempDir)
        val id = FileId(UUID.randomUUID())
        createPending(storage, id, "first".toByteArray(), "text/plain")

        val result = createPending(storage, id, "second".toByteArray(), "text/plain")

        assertEquals(CreateResult.AlreadyExists, result)
        val bytes = runBlocking { storage.openReadChannel(id)!!.toByteArray() }
        assertEquals("first", String(bytes))
    }

    @Test
    fun `createPending stores contentType and size in meta`() {
        val storage = LocalFileStorage(tempDir)
        val id = FileId(UUID.randomUUID())
        val data = "image-bytes".toByteArray()

        createPending(storage, id, data, "image/png")

        val meta = storage.getMeta(id)!!
        assertEquals("image/png", meta.contentType)
        assertEquals(data.size.toLong(), meta.size)
    }

    @Test
    fun `createPending writes data retrievable via openReadChannel`() {
        val storage = LocalFileStorage(tempDir)
        val id = FileId(UUID.randomUUID())
        val data = ByteArray(5 * 1024 * 1024) { (it % 251).toByte() }

        createPending(storage, id, data, "application/octet-stream")

        val readBack = runBlocking { storage.openReadChannel(id)!!.toByteArray() }
        assertTrue(data.contentEquals(readBack))
    }

    @Test
    fun `concurrent createPending on the same id succeeds exactly once`() {
        val storage = LocalFileStorage(tempDir)
        val id = FileId(UUID.randomUUID())
        val threadCount = 20
        val readyLatch = CountDownLatch(threadCount)
        val startLatch = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(threadCount)

        val results = List(threadCount) {
            executor.submit<CreateResult> {
                readyLatch.countDown()
                startLatch.await()
                createPending(storage, id, "data-$it".toByteArray(), "text/plain")
            }
        }

        readyLatch.await()
        startLatch.countDown()
        val outcomes = results.map { it.get(5, TimeUnit.SECONDS) }
        executor.shutdown()

        assertEquals(1, outcomes.count { it == CreateResult.Created })
        assertEquals(threadCount - 1, outcomes.count { it == CreateResult.AlreadyExists })
    }

    @Test
    fun `concurrent confirm and updateVisibility do not lose updates`() {
        val storage = LocalFileStorage(tempDir)
        val id = FileId(UUID.randomUUID())
        createPending(storage, id, "data".toByteArray(), "text/plain")

        val startLatch = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val confirmFuture = executor.submit {
            startLatch.await()
            storage.confirm(id)
        }
        val visibilityFuture = executor.submit {
            startLatch.await()
            storage.updateVisibility(id, Visibility.PUBLIC)
        }

        startLatch.countDown()
        confirmFuture.get(5, TimeUnit.SECONDS)
        visibilityFuture.get(5, TimeUnit.SECONDS)
        executor.shutdown()

        val meta = storage.getMeta(id)!!
        assertEquals(FileState.CONFIRMED, meta.state)
        assertEquals(Visibility.PUBLIC, meta.visibility)
    }
}
