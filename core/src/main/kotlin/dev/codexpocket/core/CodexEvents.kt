package dev.codexpocket.core

import kotlinx.serialization.json.*

internal fun codexTimelineUpdate(method: String, params: JsonObject): TimelineUpdate? {
    val turn = params.objectAt("turn")
    return when (method) {
        "turn/started" -> TimelineUpdate.Started(turn.text("id"), turn["startedAt"]?.jsonPrimitive?.longOrNull?.times(1000))
        "turn/completed" -> TimelineUpdate.Completed(turn.text("id"), turn["completedAt"]?.jsonPrimitive?.longOrNull?.times(1000),
            turn["durationMs"]?.jsonPrimitive?.longOrNull, turn.text("status"), turn.objectAt("error").text("message").takeIf(String::isNotBlank))
        "turn/plan/updated" -> planMessage(params)?.let { TimelineUpdate.Item(it, params.text("turnId")) }
        "item/started", "item/completed" -> chatItem(params.objectAt("item"))?.let { TimelineUpdate.Item(it, params.text("turnId")) }
        "item/agentMessage/delta", "item/commandExecution/outputDelta", "item/plan/delta", "item/reasoning/summaryTextDelta" -> {
            val kind = when (method) {
                "item/commandExecution/outputDelta" -> "commandExecution"
                "item/reasoning/summaryTextDelta" -> "reasoning"
                "item/plan/delta" -> "plan"
                else -> "agentMessage"
            }
            TimelineUpdate.Delta(params.text("itemId"), kind, params.text("delta"), params.text("turnId"))
        }
        "item/reasoning/summaryPartAdded" -> TimelineUpdate.Paragraph(params.text("itemId"))
        else -> null
    }
}

internal fun codexEvent(message: JsonObject): AgentEvent? {
    val method = message.text("method")
    val params = message.objectAt("params")
    message["id"]?.let { id ->
        val kind = when (method) {
            "item/commandExecution/requestApproval" -> RequestKind.COMMAND
            "item/fileChange/requestApproval" -> RequestKind.FILE_CHANGE
            "item/permissions/requestApproval" -> RequestKind.PERMISSIONS
            "item/tool/requestUserInput", "tool/requestUserInput" -> RequestKind.QUESTIONS
            "mcpServer/elicitation/request" -> RequestKind.MCP
            else -> return null
        }
        return AgentEvent.Request(id, kind, params)
    }
    val thread = params.text("threadId")
    return when (method) {
        "serverRequest/resolved" -> params["requestId"]?.let(AgentEvent::RequestResolved)
        "thread/tokenUsage/updated" -> tokenUsage(params.objectAt("tokenUsage"))?.let { AgentEvent.Usage(thread, it) }
        "thread/archived", "thread/unarchived" -> AgentEvent.Archived(thread)
        "thread/settings/updated" -> AgentEvent.Settings(thread, conversationSettings(params.objectAt("threadSettings")))
        "thread/closed" -> AgentEvent.Closed(thread)
        "thread/name/updated" -> AgentEvent.Renamed(thread, params.text("threadName").ifBlank { params.text("name") })
        else -> codexTimelineUpdate(method, params)?.let { AgentEvent.Timeline(thread, it) }
    }
}

