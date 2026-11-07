package dev.codexpocket.core

import kotlinx.serialization.json.*

fun threadInfo(record: JsonObject) = ThreadInfo(record.text("id"), record.text("name").ifBlank { record.text("preview").take(100).ifBlank { "未命名会话" } }, record.text("cwd"), record.objectAt("status").text("type"), record["updatedAt"]?.jsonPrimitive?.longOrNull ?: 0)
fun chatItem(record: JsonObject): ChatItem? {
    val type = record.text("type")
    val images = when (type) {
        "userMessage" -> record.array("content").mapNotNull { (it as? JsonObject)?.let(::contentImage) }
        "agentMessage" -> messageContent(record.text("text")).filterIsInstance<MessageContent.Image>().map { it.image }
        "dynamicToolCall" -> record.array("contentItems").mapNotNull { (it as? JsonObject)?.let(::contentImage) }
        "mcpToolCall" -> record.objectAt("result").array("content").mapNotNull { (it as? JsonObject)?.let(::contentImage) }
        "functionCallOutput" -> (record["output"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::contentImage) }
        "imageView" -> listOfNotNull(record.text("path").takeIf(String::isNotBlank)?.let(::ChatImage))
        "imageGeneration" -> listOfNotNull(record.text("savedPath").ifBlank {
            record.text("result").let { if (it.isBlank() || it.startsWith("data:") || it.startsWith("https://") || it.startsWith('/')) it else "data:image/png;base64,$it" }
        }.ifBlank { record.text("fileId").takeIf(String::isNotBlank)?.let { "codex-file:$it" }.orEmpty() }.takeIf(String::isNotBlank)?.let { ChatImage(it, "生成的图片") })
        else -> emptyList()
    }.distinctBy { it.source }
    val text = when (type) {
        "userMessage" -> displayUserText(record.array("content").mapNotNull { value -> (value as? JsonObject)?.let { part -> when (part.text("type")) { "skill" -> "\$" + part.text("name"); "mention" -> "@" + part.text("name"); else -> part.text("text").takeIf(String::isNotBlank) } } }.joinToString("\n"), images)
        "agentMessage", "plan" -> record.text("text")
        "functionCallOutput" -> (record["output"] as? JsonPrimitive)?.contentOrNull ?: (record["output"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.text("text")?.takeIf(String::isNotBlank) }.joinToString("\n")
        "commandExecution" -> record.text("aggregatedOutput")
        "fileChange" -> record.array("changes").joinToString("\n") { (it as? JsonObject)?.let { change -> "${change.text("path")}\n${change.text("diff")}" }.orEmpty() }
        "enteredReviewMode", "exitedReviewMode" -> record.text("review")
        "mcpToolCall" -> "${record.text("server")} / ${record.text("tool")} · ${record.text("status")}"
        "reasoning" -> record.array("summary").joinToString("\n") { (it as? JsonPrimitive)?.contentOrNull.orEmpty() }
        "dynamicToolCall" -> record.text("tool") + record.array("contentItems").mapNotNull { (it as? JsonObject)?.text("text") }.joinToString("\n", prefix = "\n")
        "webSearch" -> record.objectAt("action").text("query").ifBlank { "搜索网页" }
        "imageView" -> ""
        "imageGeneration" -> if (record.text("status") == "failed") "图片生成失败" else ""
        "contextCompaction" -> "已整理上下文"
        else -> return null
    }
    return ChatItem(record.text("id"), type, text, record.text("status"), phase = record.text("phase"), command = record.text("command"), images = images)
}

fun historyPage(turns: List<JsonObject>, cursor: String?): HistoryPage = HistoryPage(
    turns.flatMap { turn ->
        val started = turn["startedAt"]?.jsonPrimitive?.longOrNull?.times(1000)
        val completed = turn["completedAt"]?.jsonPrimitive?.longOrNull?.times(1000)
        val duration = turn["durationMs"]?.jsonPrimitive?.longOrNull
            ?: if (started != null && completed != null) (completed - started).coerceAtLeast(0) else null
        turn.array("items").mapNotNull { (it as? JsonObject)?.let(::chatItem)?.copy(
            turnId = turn.text("id"), turnStartedAtMs = started, turnCompletedAtMs = completed, turnDurationMs = duration,
        ) }
    }, cursor, turns.lastOrNull { it.text("status") == "inProgress" }?.text("id"))

internal fun isUnmaterializedHistory(error: RpcError) = error.message.contains("not materialized yet", ignoreCase = true)
    && error.message.contains("before first user message", ignoreCase = true)

fun itemHistoryPage(entries: List<JsonObject>, turns: List<JsonObject>, cursor: String?): HistoryPage {
    val metadata = turns.associateBy { it.text("id") }
    return HistoryPage(entries.mapNotNull { entry ->
        val turnId = entry.text("turnId")
        val turn = metadata[turnId]
        chatItem(entry.objectAt("item"))?.copy(turnId = turnId,
            turnStartedAtMs = turn?.get("startedAt")?.jsonPrimitive?.longOrNull?.times(1000),
            turnCompletedAtMs = turn?.get("completedAt")?.jsonPrimitive?.longOrNull?.times(1000),
            turnDurationMs = turn?.get("durationMs")?.jsonPrimitive?.longOrNull)
    }, cursor, turns.firstOrNull { it.text("status") == "inProgress" }?.text("id"))
}

