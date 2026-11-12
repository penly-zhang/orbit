package dev.codexpocket.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

/** One host, multiple adapters. Codex IDs stay unchanged for saved-session compatibility. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class MultiAgentRepository(
    private val agents: Map<String, AgentRepository>, scope: CoroutineScope,
    override val engineIssues: Map<String, String> = emptyMap(),
) : AgentRepository by (agents["codex"] ?: agents.values.first()) {
    private val lifecycle = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    override val availableEngines = agents.keys.toList()
    override fun forEngine(id: String): AgentRepository = EngineView(id, agents[id] ?: error("$id 不可用，请检查电脑上的安装并重新连接"))
    private fun engine(id: String) = forEngine(threadEngine(id))
    override val events = merge(*agents.map { (id, repo) -> repo.events.map { namespaceEvent(id, it) } }.toTypedArray())
    override val failure = combine(agents.map { it.value.failure }) { failures -> failures.firstOrNull { it != null } }
        .stateIn(lifecycle, SharingStarted.WhileSubscribed(), null)
    override suspend fun initialize() { agents.values.forEach { it.initialize() } }
    override fun close() { lifecycle.cancel(); agents.values.forEach { it.close() } }
    override suspend fun projects(cached: List<ProjectInfo>): List<ProjectInfo> {
        val all = agents.flatMap { (engine, repository) -> repository.projects(cached).map { if (engine == "codex") it else it.copy(id = "cwd:${it.roots.firstOrNull().orEmpty()}") } }
        // A project's directory, rather than an engine's private project ID, is its identity.
        return all.groupBy { it.roots.sorted() }.values.map { group ->
            val first = group.first()
            first.copy(threadCwds = group.flatMap { it.threadCwds }.distinct())
        }
    }
    override suspend fun threads(project: ProjectInfo, cursor: String?, archived: Boolean): Pair<List<ThreadInfo>, String?> {
        val pages = cursor?.let { json.decodeFromString<Map<String, AgentPage>>(it).toMutableMap() }
            ?: agents.keys.associateWith { AgentPage() }.toMutableMap()
        val rows = mutableListOf<ThreadInfo>()
        repeat(30) {
            for (id in agents.keys) {
                var page = pages[id] ?: AgentPage(exhausted = true)
                while (page.rows.isEmpty() && !page.exhausted) {
                    val fetched = forEngine(id).threads(project, page.cursor, archived)
                    check(fetched.second == null || fetched.second != page.cursor) { "Agent 返回了重复的会话分页位置" }
                    page = AgentPage(fetched.first.sortedByDescending { it.updated }, fetched.second, fetched.second == null)
                }
                pages[id] = page
            }
            val next = pages.filterKeys { it in agents }.filterValues { it.rows.isNotEmpty() }
                .maxByOrNull { it.value.rows.first().updated }
            if (next != null) {
                rows += next.value.rows.first()
                pages[next.key] = next.value.copy(rows = next.value.rows.drop(1))
            }
        }
        return rows to json.encodeToString(pages).takeIf { pages.values.any { it.rows.isNotEmpty() || !it.exhausted } }
    }

    override suspend fun newThread(project: ProjectInfo, root: String, readOnly: Boolean) =
        forEngine(availableEngines.first()).newThread(project, root, readOnly)
    override fun settings(id: String) = engine(id).settings(id)
    override fun acceptSettings(id: String, value: ConversationSettings) = engine(id).acceptSettings(id, value)
    override fun forgetSubscription(id: String) = engine(id).forgetSubscription(id)
    override fun forgetThread(id: String) = engine(id).forgetThread(id)
    override suspend fun updateSettings(id: String, change: SettingsChange) = engine(id).updateSettings(id, change)
    override suspend fun renameThread(id: String, name: String) = engine(id).renameThread(id, name)
    override suspend fun compactThread(id: String) = engine(id).compactThread(id)
    override suspend fun forkThread(id: String) = engine(id).forkThread(id)
    override suspend fun archiveThread(id: String) = engine(id).archiveThread(id)
    override suspend fun unarchiveThread(id: String) = engine(id).unarchiveThread(id)
    override suspend fun attachThread(id: String) = engine(id).attachThread(id)
    override suspend fun savedTokenUsage(id: String) = engine(id).savedTokenUsage(id)
    override suspend fun history(id: String, cursor: String?) = engine(id).history(id, cursor)
    override suspend fun send(thread: String, text: String, activeTurn: String?, images: List<OutgoingImage>, context: List<ContextInput>) = engine(thread).send(thread, text, activeTurn, images, context)
    override suspend fun interrupt(thread: String, turn: String) = engine(thread).interrupt(thread, turn)
    override suspend fun review(thread: String, target: ReviewTarget) = engine(thread).review(thread, target)
    override suspend fun toolCatalog(cwd: String, thread: String) = engine(thread).toolCatalog(cwd, thread)
    override suspend fun authorizeMcp(name: String, thread: String) = engine(thread).authorizeMcp(name, thread)
    override suspend fun reconnectThread(thread: ThreadInfo, project: ProjectInfo, replaceMissingEmpty: Boolean) =
        engine(thread.id).reconnectThread(thread, project, replaceMissingEmpty)
    private fun requestEngine(id: JsonElement) = agents[requestParts(id).first] ?: error("请求所属 Agent 不可用")
    override suspend fun decide(id: JsonElement, accept: Boolean) = requestEngine(id).decide(requestParts(id).second, accept)
    override suspend fun answerMcp(id: JsonElement, action: String, content: JsonObject?) = requestEngine(id).answerMcp(requestParts(id).second, action, content)
    override suspend fun answerQuestions(id: JsonElement, answers: Map<String, String>) = requestEngine(id).answerQuestions(requestParts(id).second, answers)
}

private fun scopedId(engine: String, id: String) = if (engine == "codex") id else "$engine::$id"
private fun rawId(engine: String, id: String) = if (engine == "codex") id else id.removePrefix("$engine::")
private fun scopedThread(engine: String, thread: ThreadInfo) = thread.copy(id = scopedId(engine, thread.id), engineId = engine)
private fun requestId(engine: String, id: JsonElement): JsonElement = obj("engine" to engine, "id" to id)
private fun requestParts(id: JsonElement): Pair<String, JsonElement> =
    if (id is JsonObject && id["engine"] != null) id.text("engine") to id.getValue("id") else "codex" to id

internal fun namespaceEvent(engine: String, event: AgentEvent): AgentEvent = when (event) {
    is AgentEvent.Request -> event.copy(id = requestId(engine, event.id), params = JsonObject(event.params.mapValues { (key, value) ->
        if (key in setOf("threadId", "thread_id") && value is JsonPrimitive && value.isString) JsonPrimitive(scopedId(engine, value.content)) else value
    }))
    is AgentEvent.RequestResolved -> event.copy(id = requestId(engine, event.id))
    is AgentEvent.Usage -> event.copy(threadId = scopedId(engine, event.threadId))
    is AgentEvent.Archived -> event.copy(threadId = scopedId(engine, event.threadId))
    is AgentEvent.Settings -> event.copy(threadId = scopedId(engine, event.threadId))
    is AgentEvent.Closed -> event.copy(threadId = scopedId(engine, event.threadId))
    is AgentEvent.Renamed -> event.copy(threadId = scopedId(engine, event.threadId))
    is AgentEvent.Timeline -> event.copy(threadId = scopedId(engine, event.threadId))
}

private class EngineView(private val engine: String, private val delegate: AgentRepository) : AgentRepository by delegate {
    override fun settings(id: String) = delegate.settings(rawId(engine, id))
    override fun acceptSettings(id: String, value: ConversationSettings) = delegate.acceptSettings(rawId(engine, id), value)
    override fun forgetSubscription(id: String) = delegate.forgetSubscription(rawId(engine, id))
    override fun forgetThread(id: String) = delegate.forgetThread(rawId(engine, id))
    override suspend fun updateSettings(id: String, change: SettingsChange) = delegate.updateSettings(rawId(engine, id), change)
    override suspend fun renameThread(id: String, name: String) = delegate.renameThread(rawId(engine, id), name)
    override suspend fun compactThread(id: String) = delegate.compactThread(rawId(engine, id))
    override suspend fun forkThread(id: String) = delegate.forkThread(rawId(engine, id)).let { scopedThread(engine, it) }
    override suspend fun archiveThread(id: String) = delegate.archiveThread(rawId(engine, id))
    override suspend fun unarchiveThread(id: String) = delegate.unarchiveThread(rawId(engine, id))
    override suspend fun attachThread(id: String) = delegate.attachThread(rawId(engine, id))
    override suspend fun savedTokenUsage(id: String) = delegate.savedTokenUsage(rawId(engine, id))
    override suspend fun history(id: String, cursor: String?) = delegate.history(rawId(engine, id), cursor)
    override suspend fun send(thread: String, text: String, activeTurn: String?, images: List<OutgoingImage>, context: List<ContextInput>) = delegate.send(rawId(engine, thread), text, activeTurn, images, context)
    override suspend fun interrupt(thread: String, turn: String) = delegate.interrupt(rawId(engine, thread), turn)
    override suspend fun review(thread: String, target: ReviewTarget) = delegate.review(rawId(engine, thread), target)
    override suspend fun toolCatalog(cwd: String, thread: String) = delegate.toolCatalog(cwd, rawId(engine, thread))
    override suspend fun authorizeMcp(name: String, thread: String) = delegate.authorizeMcp(name, rawId(engine, thread))
    override suspend fun newThread(project: ProjectInfo, root: String, readOnly: Boolean) =
        scopedThread(engine, delegate.newThread(directoryProject(project), root, readOnly))
    override suspend fun threads(project: ProjectInfo, cursor: String?, archived: Boolean): Pair<List<ThreadInfo>, String?> {
        val page = delegate.threads(directoryProject(project), cursor, archived)
        return page.first.map { scopedThread(engine, it) } to page.second
    }
    override suspend fun reconnectThread(thread: ThreadInfo, project: ProjectInfo, replaceMissingEmpty: Boolean): Pair<ThreadInfo, ConversationSettings> {
        val result = delegate.reconnectThread(thread.copy(id = rawId(engine, thread.id)), directoryProject(project), replaceMissingEmpty)
        return scopedThread(engine, result.first) to result.second
    }
    private fun directoryProject(project: ProjectInfo) = project.copy(id = "cwd:${project.roots.firstOrNull().orEmpty()}")
}

@Serializable private data class AgentPage(val rows: List<ThreadInfo> = emptyList(), val cursor: String? = null, val exhausted: Boolean = false)
