package net.kigawa.kaft.storage

import kotlinx.serialization.Serializable

enum class FileState { PENDING, CONFIRMED }
enum class Visibility { PUBLIC, PRIVATE }

@Serializable
data class FileMeta(
    val state: FileState,
    val visibility: Visibility,
    val contentType: String,
    val size: Long,
)

sealed interface CreateResult {
    data object Created : CreateResult
    data object AlreadyExists : CreateResult
}

interface FileStorage {
    fun exists(id: FileId): Boolean
    fun createPending(id: FileId, data: ByteArray, contentType: String): CreateResult
    fun confirm(id: FileId)
    fun getBytes(id: FileId): ByteArray?
    fun getMeta(id: FileId): FileMeta?
    fun delete(id: FileId)
    fun updateVisibility(id: FileId, visibility: Visibility)
}
