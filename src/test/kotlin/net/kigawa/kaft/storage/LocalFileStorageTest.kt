package net.kigawa.kaft.storage

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

    @Test
    fun `createPending on new id succeeds`() {
        val storage = LocalFileStorage(tempDir)
        val id = FileId(UUID.randomUUID())

        val result = storage.createPending(id, "data".toByteArray(), "text/plain")

        assertEquals(CreateResult.Created, result)
        assertTrue(storage.exists(id))
    }

    @Test
    fun `createPending on existing id returns AlreadyExists`() {
        val storage = LocalFileStorage(tempDir)
        val id = FileId(UUID.randomUUID())
        storage.createPending(id, "first".toByteArray(), "text/plain")

        val result = storage.createPending(id, "second".toByteArray(), "text/plain")

        assertEquals(CreateResult.AlreadyExists, result)
        assertEquals("first", String(storage.getBytes(id)!!))
    }

    @Test
    fun `createPending stores contentType and size in meta`() {
        val storage = LocalFileStorage(tempDir)
        val id = FileId(UUID.randomUUID())
        val data = "image-bytes".toByteArray()

        storage.createPending(id, data, "image/png")

        val meta = storage.getMeta(id)!!
        assertEquals("image/png", meta.contentType)
        assertEquals(data.size.toLong(), meta.size)
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
                storage.createPending(id, "data-$it".toByteArray(), "text/plain")
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
        storage.createPending(id, "data".toByteArray(), "text/plain")

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
