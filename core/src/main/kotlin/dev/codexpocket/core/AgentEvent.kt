package dev.codexpocket.core

import kotlinx.serialization.json.*

/** Stable events consumed by the workspace. Wire method names are decoded by each adapter. */
sealed interface AgentEvent {
    // Params use the shared approval, question and MCP presentation schema.
    data class Request(val id: JsonElement, val kind: RequestKind, val params: JsonObject) : AgentEvent
    data class RequestResolved(val id: JsonElement) : AgentEvent
    data class Usage(val threadId: String, val usage: TokenUsage) : AgentEvent
    data class Archived(val threadId: String) : AgentEvent
    data class Settings(val threadId: String, val settings: ConversationSettings) : AgentEvent
    data class Closed(val threadId: String) : AgentEvent
    data class Renamed(val threadId: String, val title: String) : AgentEvent
    data class Timeline(val threadId: String, val update: TimelineUpdate) : AgentEvent
}

sealed interface TimelineUpdate {
    data class Started(val turnId: String, val startedAtMs: Long?) : TimelineUpdate
    data class Completed(val turnId: String, val completedAtMs: Long?, val durationMs: Long?, val status: String, val error: String?) : TimelineUpdate
    data class Item(val item: ChatItem, val turnId: String) : TimelineUpdate
    data class Delta(val itemId: String, val kind: String, val text: String, val turnId: String) : TimelineUpdate
    data class Paragraph(val itemId: String) : TimelineUpdate
}

enum class RequestKind { COMMAND, FILE_CHANGE, PERMISSIONS, QUESTIONS, MCP }
