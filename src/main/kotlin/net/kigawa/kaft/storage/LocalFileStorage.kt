package net.kigawa.kaft.storage

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.jvm.javaio.copyTo
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.channels.Channels
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap

class LocalFileStorage(private val baseDir: Path) : FileStorage {

    init {
        Files.createDirectories(baseDir)
    }

    private val locks = ConcurrentHashMap<FileId, Any>()
    private fun lockFor(id: FileId): Any = locks.computeIfAbsent(id) { Any() }

    private fun fileDir(id: FileId): Path = baseDir.resolve(id.toString())
    private fun dataPath(id: FileId): Path = fileDir(id).resolve("data")
    private fun metaPath(id: FileId): Path = fileDir(id).resolve("meta.json")

    override suspend fun exists(id: FileId): Boolean = withContext(Dispatchers.IO) {
        Files.exists(fileDir(id))
    }

    override suspend fun createPending(id: FileId, data: ByteReadChannel, size: Long, contentType: String): CreateResult =
        withContext(Dispatchers.IO) {
            try {
                Files.createDirectory(fileDir(id))
            } catch (_: FileAlreadyExistsException) {
                return@withContext CreateResult.AlreadyExists
            }
            Files.newOutputStream(dataPath(id)).use { out -> data.copyTo(out) }
            writeMeta(
                id,
                FileMeta(
                    state = FileState.PENDING,
                    visibility = Visibility.PRIVATE,
                    contentType = contentType,
                    size = size,
                ),
            )
            CreateResult.Created
        }

    override suspend fun confirm(id: FileId): Unit = withContext(Dispatchers.IO) {
        synchronized(lockFor(id)) {
            val meta = readMetaBlocking(id) ?: error("File not found: $id")
            writeMeta(id, meta.copy(state = FileState.CONFIRMED))
        }
    }

    override suspend fun openReadChannel(id: FileId, range: LongRange?): ByteReadChannel? = withContext(Dispatchers.IO) {
        if (!Files.exists(dataPath(id))) return@withContext null
        val channel = Files.newByteChannel(dataPath(id), StandardOpenOption.READ)
        if (range != null) channel.position(range.first)
        Channels.newInputStream(channel).toByteReadChannel()
    }

    override suspend fun getMeta(id: FileId): FileMeta? = withContext(Dispatchers.IO) { readMetaBlocking(id) }

    override suspend fun delete(id: FileId): Unit = withContext(Dispatchers.IO) {
        val dir = fileDir(id)
        if (Files.exists(dir)) {
            Files.deleteIfExists(dataPath(id))
            Files.deleteIfExists(metaPath(id))
            Files.deleteIfExists(dir)
        }
    }

    override suspend fun updateVisibility(id: FileId, visibility: Visibility): Unit = withContext(Dispatchers.IO) {
        synchronized(lockFor(id)) {
            val meta = readMetaBlocking(id) ?: error("File not found: $id")
            writeMeta(id, meta.copy(visibility = visibility))
        }
    }

    private fun readMetaBlocking(id: FileId): FileMeta? {
        val path = metaPath(id)
        if (!Files.exists(path)) return null
        return Json.decodeFromString(Files.readString(path))
    }

    private fun writeMeta(id: FileId, meta: FileMeta) {
        Files.writeString(metaPath(id), Json.encodeToString(meta))
    }
}
