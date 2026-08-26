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
    fun exists(uuid: String): Boolean
    fun savePending(uuid: String, data: ByteArray)
    fun confirm(uuid: String)
    fun getBytes(uuid: String): ByteArray?
    fun getMeta(uuid: String): FileMeta?
    fun delete(uuid: String)
    fun updateVisibility(uuid: String, visibility: Visibility)
}
