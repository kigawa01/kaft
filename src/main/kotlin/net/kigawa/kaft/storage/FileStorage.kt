package net.kigawa.kaft.storage

import kotlinx.serialization.Serializable

enum class FileState { PENDING, CONFIRMED }
enum class Visibility { PUBLIC, PRIVATE }

@Serializable
data class FileMeta(
    val state: FileState,
    val visibility: Visibility,
)

interface FileStorage {
    fun exists(id: FileId): Boolean
    fun savePending(id: FileId, data: ByteArray)
    fun confirm(id: FileId)
    fun getBytes(id: FileId): ByteArray?
    fun getMeta(id: FileId): FileMeta?
    fun delete(id: FileId)
    fun updateVisibility(id: FileId, visibility: Visibility)
}
