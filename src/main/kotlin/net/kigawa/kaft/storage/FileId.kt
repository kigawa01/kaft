package net.kigawa.kaft.storage

import java.util.UUID

@JvmInline
value class FileId(val value: UUID) {
    override fun toString(): String = value.toString()

    companion object {
        fun parseOrNull(raw: String): FileId? = try {
            FileId(UUID.fromString(raw))
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
