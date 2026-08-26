package net.kigawa.kaft.storage

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

class LocalFileStorage(private val baseDir: Path) : FileStorage {

    init {
        Files.createDirectories(baseDir)
    }

    private fun fileDir(uuid: String): Path = baseDir.resolve(uuid)
    private fun dataPath(uuid: String): Path = fileDir(uuid).resolve("data")
    private fun metaPath(uuid: String): Path = fileDir(uuid).resolve("meta.json")

    override fun exists(uuid: String): Boolean = Files.exists(fileDir(uuid))

    override fun savePending(uuid: String, data: ByteArray) {
        val dir = fileDir(uuid)
        Files.createDirectories(dir)
        Files.write(dataPath(uuid), data)
        writeMeta(uuid, FileMeta(state = FileState.PENDING, visibility = Visibility.PRIVATE))
    }

    override fun confirm(uuid: String) {
        val meta = getMeta(uuid) ?: error("File not found: $uuid")
        writeMeta(uuid, meta.copy(state = FileState.CONFIRMED))
    }

    override fun getBytes(uuid: String): ByteArray? =
        if (Files.exists(dataPath(uuid))) Files.readAllBytes(dataPath(uuid)) else null

    override fun getMeta(uuid: String): FileMeta? {
        val path = metaPath(uuid)
        if (!Files.exists(path)) return null
        return Json.decodeFromString(Files.readString(path))
    }

    override fun delete(uuid: String) {
        val dir = fileDir(uuid)
        if (Files.exists(dir)) {
            Files.deleteIfExists(dataPath(uuid))
            Files.deleteIfExists(metaPath(uuid))
            Files.deleteIfExists(dir)
        }
    }

    override fun updateVisibility(uuid: String, visibility: Visibility) {
        val meta = getMeta(uuid) ?: error("File not found: $uuid")
        writeMeta(uuid, meta.copy(visibility = visibility))
    }

    private fun writeMeta(uuid: String, meta: FileMeta) {
        Files.writeString(metaPath(uuid), Json.encodeToString(meta))
    }
}
