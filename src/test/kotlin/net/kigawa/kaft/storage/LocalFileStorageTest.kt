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

        val result = storage.createPending(id, "data".toByteArray())

        assertEquals(CreateResult.Created, result)
        assertTrue(storage.exists(id))
    }

    @Test
    fun `createPending on existing id returns AlreadyExists`() {
        val storage = LocalFileStorage(tempDir)
        val id = FileId(UUID.randomUUID())
        storage.createPending(id, "first".toByteArray())

        val result = storage.createPending(id, "second".toByteArray())

        assertEquals(CreateResult.AlreadyExists, result)
        assertEquals("first", String(storage.getBytes(id)!!))
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
                storage.createPending(id, "data-$it".toByteArray())
            }
        }

        readyLatch.await()
        startLatch.countDown()
        val outcomes = results.map { it.get(5, TimeUnit.SECONDS) }
        executor.shutdown()

        assertEquals(1, outcomes.count { it == CreateResult.Created })
        assertEquals(threadCount - 1, outcomes.count { it == CreateResult.AlreadyExists })
    }
}
