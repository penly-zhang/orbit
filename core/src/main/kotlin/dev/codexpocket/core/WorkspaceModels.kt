package dev.codexpocket.core

import kotlinx.serialization.Serializable

@Serializable data class ProjectInfo(val id: String, val name: String, val roots: List<String>)
@Serializable data class ThreadInfo(val id: String, val title: String, val cwd: String, val status: String = "", val updated: Long = 0)
@Serializable data class ChatItem(
    val id: String, val kind: String, val text: String, val status: String = "",
    val phase: String = "", val turnId: String = "", val command: String = "",
    val turnStartedAtMs: Long? = null, val turnCompletedAtMs: Long? = null, val turnDurationMs: Long? = null,
    val images: List<ChatImage> = emptyList(),
)
data class HistoryPage(val items: List<ChatItem>, val cursor: String?, val turnId: String?)
data class FileEntry(val name: String, val directory: Boolean)
data class FilePreview(val path: String, val bytes: ByteArray)
@Serializable data class GitChange(
    val path: String, val oldPath: String? = null, val status: String,
    val added: Int? = null, val deleted: Int? = null, val stagedAdded: Int? = null, val stagedDeleted: Int? = null,
    val workingAdded: Int? = null, val workingDeleted: Int? = null, val binary: Boolean = false, val fingerprint: String = "",
)

data class FileSearchResult(val name: String, val path: String, val directory: Boolean)
