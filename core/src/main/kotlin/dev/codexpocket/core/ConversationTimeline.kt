package dev.codexpocket.core

sealed interface TimelineEntry { val id: String }
data class MessageEntry(val item: ChatItem) : TimelineEntry { override val id = "message:${item.id}" }
data class WorkEntry(
    val turnId: String, val items: List<ChatItem>, val active: Boolean,
    val startedAtMs: Long?, val durationMs: Long?,
) : TimelineEntry { override val id = "work:${items.first().id}" }

/** Show live progress chronologically; completed turns fold their process before the final answer. */
fun conversationTimeline(items: List<ChatItem>, activeTurn: String?): List<TimelineEntry> {
    val turns = mutableListOf<Pair<String, MutableList<ChatItem>>>()
    var legacyTurn = "legacy-start"
    for (item in items) {
        if (item.kind == "userMessage" && item.turnId.isBlank()) legacyTurn = "legacy:${item.id}"
        val key = item.turnId.ifBlank { legacyTurn }
        if (turns.lastOrNull()?.first != key) turns += key to mutableListOf()
        turns.last().second += item
    }
    return buildList {
        for ((turnId, all) in turns) {
            val visible = all.filter { it.text.isNotBlank() || it.images.isNotEmpty() || (it.kind == "commandExecution" && it.command.isNotBlank()) }
            val isActive = turnId == activeTurn
            val explicitFinals = visible.filter { it.kind == "agentMessage" && it.phase == "final_answer" }.map { it.id }.toSet()
            val finalIds = explicitFinals.ifEmpty {
                if (!isActive) setOfNotNull(visible.lastOrNull { it.kind == "agentMessage" && it.phase != "commentary" }?.id) else emptySet()
            }
            fun isMessage(item: ChatItem) = item.kind in setOf("userMessage", "exitedReviewMode", "imageGeneration") || item.id in finalIds
            val lastWorkId = visible.lastOrNull { !isMessage(it) }?.id
            val pending = mutableListOf<ChatItem>()
            fun flushWork() {
                if (pending.isEmpty()) return
                val last = pending.last().id == lastWorkId
                add(WorkEntry(turnId, pending.toList(), isActive && last,
                    all.firstNotNullOfOrNull { it.turnStartedAtMs },
                    if (last && !isActive) all.firstNotNullOfOrNull { it.turnDurationMs } else null))
                pending.clear()
            }
            for (item in visible) {
                if (isMessage(item)) { flushWork(); add(MessageEntry(item)) } else pending += item
            }
            flushWork()
        }
    }
}

/** Coalesce adjacent commands without merging across prose, reasoning, file or search events. */
fun workDisplayGroups(items: List<ChatItem>): List<List<ChatItem>> {
    val groups = mutableListOf<MutableList<ChatItem>>()
    for (item in items) {
        if (item.kind == "commandExecution" && groups.lastOrNull()?.lastOrNull()?.kind == "commandExecution") groups.last() += item
        else groups += mutableListOf(item)
    }
    return groups
}

fun workGroupTitle(items: List<ChatItem>): String = items.lastOrNull { it.kind == "reasoning" && it.text.isNotBlank() }
    ?.let(::workItemTitle) ?: "工作过程"

fun workItemTitle(item: ChatItem): String {
    val text = when (item.kind) {
        "commandExecution" -> item.command.ifBlank { item.text.lineSequence().firstOrNull().orEmpty().removePrefix("$ ") }
        "fileChange" -> "修改文件 · " + item.text.lineSequence().firstOrNull().orEmpty()
        "mcpToolCall", "dynamicToolCall" -> item.text
        "webSearch" -> "搜索网页 · ${item.text}"
        "plan" -> "更新计划"
        "imageView" -> "查看图片 · ${(item.images.firstOrNull()?.source ?: item.text).substringAfterLast('/')}"
        "enteredReviewMode" -> "开始代码审查"
        else -> item.text
    }
    return text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        .replace(Regex("^\\s*[-#>]+\\s*"), "").replace("**", "").replace("__", "").replace("`", "")
        .trim().take(100).ifBlank { "工作记录" }
}

fun workDuration(durationMs: Long): String {
    val seconds = (durationMs.coerceAtLeast(0) / 1000)
    return when {
        seconds >= 3600 -> "${seconds / 3600} 小时 ${seconds % 3600 / 60} 分钟"
        seconds >= 60 -> "${seconds / 60} 分 ${seconds % 60} 秒"
        else -> "$seconds 秒"
    }
}

fun commandOutput(item: ChatItem): String = if (item.command.isNotBlank()) item.text else item.text.substringAfter('\n', "")
