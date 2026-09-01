package net.kigawa.kaft.storage

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.jvm.javaio.copyTo
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
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

    override fun exists(id: FileId): Boolean = Files.exists(fileDir(id))

    override suspend fun createPending(id: FileId, data: ByteReadChannel, size: Long, contentType: String): CreateResult {
        try {
            Files.createDirectory(fileDir(id))
        } catch (_: FileAlreadyExistsException) {
            return CreateResult.AlreadyExists
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
        return CreateResult.Created
    }

    override fun confirm(id: FileId) = synchronized(lockFor(id)) {
        val meta = getMeta(id) ?: error("File not found: $id")
        writeMeta(id, meta.copy(state = FileState.CONFIRMED))
    }

    override fun openReadChannel(id: FileId): ByteReadChannel? =
        if (Files.exists(dataPath(id))) Files.newInputStream(dataPath(id)).toByteReadChannel() else null

    override fun getMeta(id: FileId): FileMeta? {
        val path = metaPath(id)
        if (!Files.exists(path)) return null
        return Json.decodeFromString(Files.readString(path))
    }

    override fun delete(id: FileId) {
        val dir = fileDir(id)
        if (Files.exists(dir)) {
            Files.deleteIfExists(dataPath(id))
            Files.deleteIfExists(metaPath(id))
            Files.deleteIfExists(dir)
        }
    }

    override fun updateVisibility(id: FileId, visibility: Visibility) = synchronized(lockFor(id)) {
        val meta = getMeta(id) ?: error("File not found: $id")
        writeMeta(id, meta.copy(visibility = visibility))
    }

    private fun writeMeta(id: FileId, meta: FileMeta) {
        Files.writeString(metaPath(id), Json.encodeToString(meta))
    }
}
