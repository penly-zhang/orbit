package dev.codexpocket.core

import java.io.Closeable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.*

/** The engine refused to attach because another process owns the conversation. */
class ThreadInUseException(cause: Throwable) : IllegalStateException(
    "此会话仍被另一个执行进程独占。多端接续需要连接同一个共享后台。若使用 traex，请先运行 traex features enable daemon_auto_start，再正常退出并重新打开电脑端会话，然后在 Orbit 重试。只启动后台不会切换已有终端。已保留本机消息和草稿。", cause)

/** UI-facing contract. Adapters own protocol, settings encoding and request replies. */
interface AgentRepository : WorkspaceRepository, AgentTools, Closeable {
    val engineIssues: Map<String, String> get() = emptyMap()
    val availableEngines: List<String> get() = listOf("codex")
    fun forEngine(id: String): AgentRepository { require(id == "codex") { "Agent 不可用：$id" }; return this }
    val events: Flow<AgentEvent>
    val failure: StateFlow<String?>
    suspend fun initialize()
    fun settings(id: String): ConversationSettings?
    fun acceptSettings(id: String, value: ConversationSettings)
    fun forgetSubscription(id: String)
    fun forgetThread(id: String)
    suspend fun collaborationModes(): List<String>
    suspend fun models(): List<ModelOption>
    suspend fun permissions(cwd: String): List<PermissionOption>
    suspend fun updateSettings(id: String, change: SettingsChange): ConversationSettings
    suspend fun projects(cached: List<ProjectInfo> = emptyList()): List<ProjectInfo>
    suspend fun threads(project: ProjectInfo, cursor: String? = null, archived: Boolean = false): Pair<List<ThreadInfo>, String?>
    suspend fun renameThread(id: String, name: String)
    suspend fun compactThread(id: String)
    suspend fun forkThread(id: String): ThreadInfo
    suspend fun archiveThread(id: String)
    suspend fun unarchiveThread(id: String)
    suspend fun attachThread(id: String): ConversationSettings
    suspend fun reconnectThread(thread: ThreadInfo, project: ProjectInfo, replaceMissingEmpty: Boolean = false): Pair<ThreadInfo, ConversationSettings>
    suspend fun savedTokenUsage(id: String): TokenUsage?
    suspend fun history(id: String, cursor: String? = null): HistoryPage
    suspend fun newThread(project: ProjectInfo, root: String, readOnly: Boolean = false): ThreadInfo
    suspend fun send(thread: String, text: String, activeTurn: String?, images: List<OutgoingImage> = emptyList(), context: List<ContextInput> = emptyList()): String
    suspend fun interrupt(thread: String, turn: String)
    suspend fun review(thread: String, target: ReviewTarget)
    suspend fun decide(id: JsonElement, accept: Boolean)
    suspend fun answerMcp(id: JsonElement, action: String, content: JsonObject?)
    suspend fun answerQuestions(id: JsonElement, answers: Map<String, String>)
}

sealed interface SettingsChange {
    data class Model(val model: String, val effort: String) : SettingsChange
    data class Permission(val option: PermissionOption) : SettingsChange
    data class Collaboration(val mode: String) : SettingsChange
}

interface RemoteFiles {
    suspend fun files(path: String): List<FileEntry>
    suspend fun file(path: String, maxBytes: Int = 2 * 1024 * 1024): FilePreview
}

interface WorkspaceRepository : RemoteFiles {
    suspend fun webTunnel(port: Int): LoopbackTunnel
    suspend fun artifactResource(root: String, encodedPath: String): FilePreview
    suspend fun image(source: String, cwd: String): ByteArray
    suspend fun changes(root: String): List<GitChange>
    suspend fun reviewOverview(root: String, paths: List<String> = emptyList()): ReviewOverview
    suspend fun beginReviewBaseline(root: String): ReviewBaseline
    suspend fun taskReview(baseline: ReviewBaseline, finish: Boolean = false): TaskReviewReport
    suspend fun savedTaskReview(root: String, token: String): TaskReviewReport
    suspend fun validateReviewComments(comments: List<ReviewComment>): List<ReviewComment>
    suspend fun reviewFile(root: String, path: String): Triple<String, String, ReviewOverview>
    suspend fun diff(root: String, change: GitChange): String
}

interface AgentTools {
    suspend fun searchFiles(root: String, query: String): List<FileSearchResult>
    suspend fun toolCatalog(cwd: String, thread: String): ToolCatalog
    suspend fun pluginTools(plugin: PluginEntry): PluginTools
    suspend fun enableSkill(path: String)
    suspend fun reloadMcp()
    suspend fun authorizeMcp(name: String, thread: String): JsonObject
}
