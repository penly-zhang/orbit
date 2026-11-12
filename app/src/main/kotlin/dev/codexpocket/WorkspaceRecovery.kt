package dev.codexpocket

import dev.codexpocket.core.*
import kotlinx.serialization.Serializable
import java.io.File
import java.util.UUID

@Serializable data class QueuedMessage(
    val text: String, val images: List<DraftImage> = emptyList(), val id: String = UUID.randomUUID().toString(),
    val sending: Boolean = false, val error: String? = null,
    val reviewComments: List<ReviewComment> = emptyList(),
    val context: List<ContextInput> = emptyList(),
)

@Serializable internal data class SavedTab(
    val thread: ThreadInfo, val project: ProjectInfo, val number: Int, val name: String = "",
    val draft: String = "", val images: List<DraftImage> = emptyList(), val uncertainSend: Boolean = false,
    val queuedMessages: List<QueuedMessage> = emptyList(),
    val taskReview: TaskReviewReport? = null,
    val context: List<ContextInput> = emptyList(),
    val progress: TaskProgress = TaskProgress(),
    val tabOpen: Boolean = true, val tracked: Boolean = progress.turnId.isNotBlank(),
)
@Serializable internal data class SavedHost(val id: String, val tabs: List<SavedTab> = emptyList(), val selectedThread: String? = null, val usage: Map<String, TokenUsage> = emptyMap(), val usageUpdatedAt: Map<String, Long> = emptyMap())
@Serializable internal data class SavedWorkspace(val hosts: List<SavedHost> = emptyList())

internal fun pausedQueuedMessages(messages: List<QueuedMessage>) = messages.map {
    it.copy(sending = false, error = if (it.sending) "发送结果未确认，请先检查历史再继续" else it.error)
}

internal fun restoredImages(images: List<DraftImage>): List<DraftImage> = images.take(MAX_OUTGOING_IMAGES).filter {
    val file = File(it.path)
    it.bytes in 1..MAX_OUTGOING_IMAGE_BYTES && file.isFile && file.length() == it.bytes.toLong()
}.distinctBy { it.id }

internal fun restoredContext(items: List<ContextInput>): List<ContextInput> = items.take(MAX_CONTEXT_ITEMS).filter { item ->
    runCatching { validateContext(listOf(item)) }.isSuccess && (item.localPath == null || java.io.File(requireNotNull(item.localPath)).let { it.isFile && it.length() == item.bytes.toLong() })
}.distinctBy { it.id }
