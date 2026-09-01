package net.kigawa.kaft.storage

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path

class LocalFileStorage(private val baseDir: Path) : FileStorage {

    init {
        Files.createDirectories(baseDir)
    }

    private fun fileDir(id: FileId): Path = baseDir.resolve(id.toString())
    private fun dataPath(id: FileId): Path = fileDir(id).resolve("data")
    private fun metaPath(id: FileId): Path = fileDir(id).resolve("meta.json")

    override fun exists(id: FileId): Boolean = Files.exists(fileDir(id))

    override fun createPending(id: FileId, data: ByteArray): CreateResult {
        try {
            Files.createDirectory(fileDir(id))
        } catch (_: FileAlreadyExistsException) {
            return CreateResult.AlreadyExists
        }
        Files.write(dataPath(id), data)
        writeMeta(id, FileMeta(state = FileState.PENDING, visibility = Visibility.PRIVATE))
        return CreateResult.Created
    }

    override fun confirm(id: FileId) {
        val meta = getMeta(id) ?: error("File not found: $id")
        writeMeta(id, meta.copy(state = FileState.CONFIRMED))
    }

    override fun getBytes(id: FileId): ByteArray? =
        if (Files.exists(dataPath(id))) Files.readAllBytes(dataPath(id)) else null

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

    override fun updateVisibility(id: FileId, visibility: Visibility) {
        val meta = getMeta(id) ?: error("File not found: $id")
        writeMeta(id, meta.copy(visibility = visibility))
    }

    private fun writeMeta(id: FileId, meta: FileMeta) {
        Files.writeString(metaPath(id), Json.encodeToString(meta))
    }
}
