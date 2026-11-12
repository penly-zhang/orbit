package dev.codexpocket.core

import kotlinx.serialization.Serializable

@Serializable data class ProjectInfo(
    val id: String, val name: String, val roots: List<String>,
    // Directory-backed projects group several exact thread cwd values under one Git root.
    val threadCwds: List<String> = roots,
)
@Serializable data class ThreadInfo(val id: String, val title: String, val cwd: String, val status: String = "", val updated: Long = 0, val engineId: String = "codex")
@Serializable data class ChatItem(
    val id: String, val kind: String, val text: String, val status: String = "",
    val phase: String = "", val turnId: String = "", val command: String = "",
    val turnStartedAtMs: Long? = null, val turnCompletedAtMs: Long? = null, val turnDurationMs: Long? = null,
    val images: List<ChatImage> = emptyList(),
    val exitCode: Int? = null,
)
data class HistoryPage(val items: List<ChatItem>, val cursor: String?, val turnId: String?, val latestTurn: TurnSummary? = null)
data class FileEntry(val name: String, val directory: Boolean)
data class FilePreview(val path: String, val bytes: ByteArray)
@Serializable data class GitChange(
    val path: String, val oldPath: String? = null, val status: String,
    val added: Int? = null, val deleted: Int? = null, val stagedAdded: Int? = null, val stagedDeleted: Int? = null,
    val workingAdded: Int? = null, val workingDeleted: Int? = null, val binary: Boolean = false, val fingerprint: String = "",
)

data class FileSearchResult(val name: String, val path: String, val directory: Boolean)

/** Latest backend turn outcome, independent of the loaded message page. */
data class TurnSummary(val id: String, val status: String, val error: String? = null)
