package dev.codexpocket.core

import kotlinx.serialization.json.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.ConcurrentHashMap

class CodexRepository(private val rpc: RpcClient, private val ssh: SshConnection) :
    AgentRepository,
    WorkspaceRepository by SshWorkspace(ssh, CodexFiles(rpc, ssh)),
    AgentTools by CodexTools(rpc) {
    override val events: Flow<AgentEvent> = rpc.events.transform { message ->
        val event = codexEvent(message)
        if (message["id"] != null && event == null) rpc.unsupported(message.getValue("id"))
        if (event is AgentEvent.Request) requests[event.id] = event
        if (event is AgentEvent.RequestResolved) requests.remove(event.id)
        if (event != null) emit(event)
    }
    override val failure get() = rpc.failure
    private val requests = ConcurrentHashMap<JsonElement, AgentEvent.Request>()
    override suspend fun initialize() { rpc.initialize() }
    override fun close() { requests.clear(); rpc.close() }
    override suspend fun decide(id: JsonElement, accept: Boolean) {
        val request = requests[id] ?: error("请求已结束，请刷新会话")
        val result = if (request.kind == RequestKind.PERMISSIONS)
            obj("permissions" to if (accept) JsonObject(request.params.objectAt("permissions").filterValues { it != JsonNull }) else obj(), "scope" to "turn")
        else obj("decision" to if (accept) "accept" else "decline")
        rpc.answer(id, result)
        requests.remove(id)
    }
    override suspend fun answerMcp(id: JsonElement, action: String, content: JsonObject?) {
        require(action in setOf("accept", "decline", "cancel"))
        rpc.answer(id, obj("action" to action, "content" to content))
        requests.remove(id)
    }
    override suspend fun answerQuestions(id: JsonElement, answers: Map<String, String>) {
        rpc.answer(id, obj("answers" to JsonObject(answers.mapValues { obj("answers" to listOf(it.value)) })))
        requests.remove(id)
    }

    private val freshThreads = ConcurrentHashMap<String, Pair<ProjectInfo, ThreadInfo>>()
    private val settings = ConcurrentHashMap<String, ConversationSettings>()
    private val attached = ConcurrentHashMap.newKeySet<String>()
    override fun settings(id: String): ConversationSettings? = settings[id]
    override fun acceptSettings(id: String, value: ConversationSettings) { settings[id] = value }
    override fun forgetSubscription(id: String) { attached.remove(id) }
    override suspend fun collaborationModes(): List<String> = try {
        rpc.request("collaborationMode/list", obj()).array("data").mapNotNull { (it as? JsonObject)?.text("mode") }.filter { it in setOf("default", "plan") }.distinct()
    } catch (e: RpcError) { if (e.code == -32601) emptyList() else throw e }
    override suspend fun renameThread(id: String, name: String) { require(name.isNotBlank()); rpc.request("thread/name/set", obj("threadId" to id, "name" to name.trim().take(120))) }
    override suspend fun compactThread(id: String) { rpc.request("thread/compact/start", obj("threadId" to id)) }
    override suspend fun forkThread(id: String): ThreadInfo {
        val response = rpc.request("thread/fork", obj("threadId" to id, "excludeTurns" to true))
        val thread = threadInfo(response.objectAt("thread"))
        require(thread.id.isNotBlank()) { "电脑未返回分支会话" }
        val actual = conversationSettings(response)
        if (actual.model.isNotBlank()) { settings[thread.id] = actual; attached += thread.id }
        return thread
    }
    override suspend fun models(): List<ModelOption> {
        val entries = mutableListOf<ModelOption>()
        var cursor: String? = null
        do {
            val page = rpc.request("model/list", obj("limit" to 100, "cursor" to cursor, "includeHidden" to false))
            entries += page.array("data").mapNotNull { (it as? JsonObject)?.takeUnless { m -> m["hidden"]?.jsonPrimitive?.booleanOrNull == true }?.let(::modelOption) }
            cursor = page.text("nextCursor").takeIf(String::isNotBlank)
        } while (cursor != null && entries.size < 2000)
        return entries.distinctBy { it.id }
    }
    override suspend fun permissions(cwd: String): List<PermissionOption> {
        val entries = mutableListOf<PermissionOption>()
        var cursor: String? = null
        do {
            val page = rpc.request("permissionProfile/list", obj("limit" to 100, "cursor" to cursor, "cwd" to cwd))
            entries += page.array("data").mapNotNull { (it as? JsonObject)?.let { p -> PermissionOption(p.text("id"), permissionName(p.text("id")), p.text("description").ifBlank { permissionDescription(p.text("id")) }, p["allowed"]?.jsonPrimitive?.booleanOrNull == true) } }
            cursor = page.text("nextCursor").takeIf(String::isNotBlank)
        } while (cursor != null && entries.size < 2000)
        val requirements = rpc.request("configRequirements/read").objectAt("requirements")
        fun permits(key: String, value: String) = requirements[key] !is JsonArray || requirements.array(key).any { (it as? JsonPrimitive)?.contentOrNull == value }
        return entries.distinctBy { it.id }.flatMap { option ->
            val policy = if (option.id == ":danger-full-access") "never" else "on-request"
            val manual = option.copy(allowed = option.allowed && (!option.id.startsWith(':') || (permits("allowedApprovalsReviewers", "user") && permits("allowedApprovalPolicies", policy))))
            if (option.id == ":workspace") listOf(manual, option.copy(name = "自动审查", description = "自动审查提权请求", reviewer = "auto_review",
                allowed = option.allowed && permits("allowedApprovalsReviewers", "auto_review") && permits("allowedApprovalPolicies", "on-request"))) else listOf(manual)
        }
    }
    override suspend fun updateSettings(id: String, change: SettingsChange): ConversationSettings {
        val current = settings[id] ?: error("请先接续会话")
        val patch = when (change) {
            is SettingsChange.Model -> modelSettingsPatch(current, change.model, change.effort)
            is SettingsChange.Permission -> permissionSettingsPatch(change.option)
            is SettingsChange.Collaboration -> collaborationSettingsPatch(current, change.mode)
        }
        return updateSettings(id, patch)
    }
    internal suspend fun updateSettings(id: String, patch: JsonObject): ConversationSettings = coroutineScope {
        settings[id]?.takeIf { settingsMatch(it, patch) }?.let { return@coroutineScope it }
        val updated = async(start = CoroutineStart.UNDISPATCHED) {
            rpc.events.first { it.text("method") == "thread/settings/updated" && it.objectAt("params").text("threadId") == id }
                .objectAt("params").objectAt("threadSettings").let(::conversationSettings)
        }
        try {
            rpc.request("thread/settings/update", JsonObject(patch + ("threadId" to JsonPrimitive(id))))
            (withTimeoutOrNull(10000) { updated.await() } ?: error("电脑未返回设置确认，请刷新会话核对")).also { settings[id] = it }
        } finally { updated.cancel() }
    }
    private val interactiveSources = listOf("cli", "vscode", "appServer")
    override suspend fun projects(): List<ProjectInfo> {
        val projects = mutableListOf<ProjectInfo>()
        try {
            var cursor: String? = null
            do {
                val page = rpc.request("project/list", obj("limit" to 100, "cursor" to cursor))
                projects += page.array("data").mapNotNull { (it as? JsonObject)?.let { project -> ProjectInfo(project.text("id"), project.text("name"), project.array("roots").mapNotNull { root -> (root as? JsonObject)?.text("path") }) } }
                cursor = page.text("nextCursor").takeIf(String::isNotBlank)
            } while (cursor != null && projects.size < 2000)
        } catch (error: RpcError) { if (error.code != -32601 && error.code != -32600) throw error }
        val threads = rpc.request("thread/list", obj("limit" to 100, "useStateDbOnly" to true, "sourceKinds" to interactiveSources)).array("data")
        return combineProjectSources(projects, threads.mapNotNull { it as? JsonObject })
    }
    override suspend fun threads(project: ProjectInfo, cursor: String?, archived: Boolean): Pair<List<ThreadInfo>, String?> {
        val params = if (project.id.startsWith("cwd:")) obj("cwd" to project.roots, "limit" to 30, "cursor" to cursor, "useStateDbOnly" to true, "sortKey" to "updated_at", "sourceKinds" to interactiveSources)
        else obj("projectId" to project.id, "limit" to 30, "cursor" to cursor, "useStateDbOnly" to true, "sortKey" to "updated_at", "sourceKinds" to interactiveSources)
        val page = rpc.request("thread/list", JsonObject(params + ("archived" to JsonPrimitive(archived))))
        val fresh = if (cursor == null && !archived) freshThreads.values.filter { it.first.id == project.id }.map { it.second } else emptyList()
        return (fresh + page.array("data").mapNotNull { (it as? JsonObject)?.let(::threadInfo) }).distinctBy(ThreadInfo::id) to page.text("nextCursor").takeIf(String::isNotBlank)
    }
    override fun forgetThread(id: String) { freshThreads.remove(id); attached.remove(id); settings.remove(id) }
    override suspend fun archiveThread(id: String) { rpc.request("thread/archive", obj("threadId" to id)); forgetThread(id) }
    override suspend fun unarchiveThread(id: String) { rpc.request("thread/unarchive", obj("threadId" to id)); forgetThread(id) }
    override suspend fun attachThread(id: String): ConversationSettings {
        if (freshThreads.containsKey(id) || id in attached) settings[id]?.let { return it }
        // Join an already-running thread without changing its runtime permissions or model.
        return conversationSettings(rpc.request("thread/resume", obj("threadId" to id, "excludeTurns" to true))).also { settings[id] = it; attached += id }
    }
    /** An empty, never-sent thread may have no resumable history after transport loss. */
    override suspend fun reconnectThread(thread: ThreadInfo, project: ProjectInfo, replaceMissingEmpty: Boolean): Pair<ThreadInfo, ConversationSettings> {
        try { return thread to attachThread(thread.id) }
        catch (error: RpcError) {
            val missing = error.message.trim() == "no rollout found for thread id ${thread.id}"
            if (!isUnmaterializedHistory(error) && !(replaceMissingEmpty && missing)) throw error
            val replacement = newThread(project, thread.cwd.ifBlank { project.roots.firstOrNull().orEmpty() })
            return replacement to settings.getValue(replacement.id)
        }
    }
    override suspend fun savedTokenUsage(id: String): TokenUsage? {
        if (freshThreads.containsKey(id)) return null
        val thread = rpc.request("thread/read", obj("threadId" to id, "includeTurns" to false)).objectAt("thread")
        val path = thread.text("path")
        check(path.isNotBlank()) { "电脑未提供该会话的用量文件" }
        val result = ssh.execute(savedTokenUsageCommand(path, id), 8192)
        check(result.code == 0) { "读取用量失败，请确认电脑可运行 Python 3" }
        val record = json.parseToJsonElement(result.stdout.trim()).jsonObject
        check(record.text("error").isBlank()) { when (record.text("error")) {
            "scan_limit" -> "最近记录中未找到用量，保留上次同步数据"
            "session_mismatch" -> "用量文件与当前会话不匹配"
            else -> "电脑上的用量文件暂不可读取"
        } }
        return tokenUsage(record)
    }
    override suspend fun history(id: String, cursor: String?): HistoryPage {
        if (freshThreads.containsKey(id)) return HistoryPage(emptyList(), null, null)
        if (cursor?.startsWith("turns:") != true) {
            try {
                return coroutineScope {
                    val records = async { rpc.request("thread/items/list", obj("threadId" to id, "limit" to 40, "sortDirection" to "desc", "cursor" to cursor?.removePrefix("items:"))) }
                    val turns = async { rpc.request("thread/turns/list", obj("threadId" to id, "limit" to 2, "sortDirection" to "desc", "itemsView" to "notLoaded")) }
                    val page = records.await()
                    itemHistoryPage(page.array("data").mapNotNull { it as? JsonObject }.reversed(), turns.await().array("data").mapNotNull { it as? JsonObject }, page.text("nextCursor").takeIf(String::isNotBlank)?.let { "items:$it" })
                }
            } catch (error: RpcError) {
                if (isUnmaterializedHistory(error)) return HistoryPage(emptyList(), null, null)
                if (error.code != -32601) throw error
            }
        }
        val page = try {
            rpc.request("thread/turns/list", obj("threadId" to id, "limit" to 2, "sortDirection" to "desc", "itemsView" to "full", "cursor" to cursor?.removePrefix("turns:")))
        } catch (error: RpcError) {
            if (isUnmaterializedHistory(error)) return HistoryPage(emptyList(), null, null)
            throw error
        }
        return historyPage(page.array("data").mapNotNull { it as? JsonObject }.reversed(), page.text("nextCursor").takeIf(String::isNotBlank)?.let { "turns:$it" })
    }
    override suspend fun newThread(project: ProjectInfo, root: String, readOnly: Boolean): ThreadInfo {
        val params = obj("cwd" to root).toMutableMap()
        if (readOnly) params.putAll(obj("approvalPolicy" to "on-request", "sandbox" to "read-only"))
        if (!project.id.startsWith("cwd:")) params["projectId"] = JsonPrimitive(project.id)
        val response = rpc.request("thread/start", JsonObject(params))
        return threadInfo(response.objectAt("thread")).also { freshThreads[it.id] = project to it; settings[it.id] = conversationSettings(response); attached += it.id }
    }
    override suspend fun send(thread: String, text: String, activeTurn: String?, images: List<OutgoingImage>, context: List<ContextInput>): String {
        validateContext(context)
        validateOutgoingImages(if (context.isNotEmpty() && text.isBlank()) "上下文" else text, images)
        val resolved = context.map { item ->
            if (item.localPath == null) item else {
                val file = java.io.File(item.localPath)
                require(file.isFile && file.length() == item.bytes.toLong() && file.length() in 1..MAX_ATTACHMENT_BYTES.toLong()) { "附件已不可用，请重新选择：${item.name}" }
                val bytes = withContext(Dispatchers.IO) { file.inputStream().use { input ->
                    val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                    while (true) { val n = input.read(buffer); if (n < 0) break; require(out.size() + n <= MAX_ATTACHMENT_BYTES) { "附件大小已变化，请重新选择" }; out.write(buffer, 0, n) }; out.toByteArray()
                } }
                require(bytes.size == item.bytes) { "附件读取失败或大小已变化" }
                item.copy(path = ssh.uploadAttachment(item.name, bytes), localPath = null)
            }
        }
        val input = contextUserInput(text, resolved).toMutableList()
        for (image in images) input += obj("type" to "localImage", "path" to ssh.uploadImage(image.bytes))
        val params = obj("threadId" to thread, "input" to input)
        val response = if (activeTurn != null) rpc.request("turn/steer", JsonObject(params + ("expectedTurnId" to JsonPrimitive(activeTurn)))) else rpc.request("turn/start", params)
        freshThreads.remove(thread)
        return response.objectAt("turn").text("id").ifBlank { response.text("turnId") }
    }
    override suspend fun interrupt(thread: String, turn: String) { rpc.request("turn/interrupt", obj("threadId" to thread, "turnId" to turn)) }
    override suspend fun review(thread: String, target: ReviewTarget) {
        val encoded = when (target) {
            ReviewTarget.UncommittedChanges -> obj("type" to "uncommittedChanges")
            is ReviewTarget.Branch -> obj("type" to "baseBranch", "branch" to target.name)
            is ReviewTarget.Commit -> obj("type" to "commit", "sha" to target.sha, "title" to null)
        }
        rpc.request("review/start", obj("threadId" to thread, "target" to encoded, "delivery" to "inline"))
    }
}
