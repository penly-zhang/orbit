package dev.codexpocket

import dev.codexpocket.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*

internal class HostWorkspace(
    private val vault: PocketStorage,
    private val viewModelScope: CoroutineScope,
    profile: HostProfile,
    profiles: List<HostProfile>,
    private val saveProfile: (HostProfile) -> Unit,
    private val onSendCompleted: (SendCompletion) -> Unit,
    private val onQueueChanged: () -> Unit,
    private val engines: AgentEngineRegistry = AgentEngines.default,
    private val allocateTabNumber: () -> Int,
) {
    private val mutable = MutableStateFlow(run {
        val projects = runCatching { vault.get("projects:${profile.id}")?.let { json.decodeFromString<List<ProjectInfo>>(it) } }.getOrNull().orEmpty()
        val last = runCatching { vault.get("lastProject:${profile.id}") }.getOrNull()
        val project = projects.find { it.id == last }
        PocketState(profiles = profiles, profile = profile, projects = projects, project = project,
            root = project?.roots?.firstOrNull().orEmpty(), directory = project?.roots?.firstOrNull().orEmpty())
    })
    val state = mutable.asStateFlow()
    private var repo: AgentRepository? = null
    private val images = lazy { ConversationImageLoader { source, cwd -> requireRepo().image(source, cwd) } }
    fun artifactRepository() = requireRepo()
    internal val imageLoader get() = images.value
    private var connectionJobs = mutableListOf<Job>()
    private var connectJob: Job? = null
    private var reconnectJob: Job? = null
    private var reconnectAttempt = 0
    private var ephemeralCredentials = Credentials()
    private var threadGeneration = 0
    private var fileGeneration = 0
    private var changesGeneration = 0
    private var changesJob: Job? = null
    private val reviewBaselines = mutableMapOf<String, ReviewBaseline>()
    private val reviewFinishing = mutableSetOf<String>()
    private val reviewPolls = mutableMapOf<String, Job>()
    private val reviewSendingRoots = mutableSetOf<String>()
    private val usageReads = mutableMapOf<String, Job>()
    private val usageRevisions = mutableMapOf<String, Long>()
    private val turnRevisions = mutableMapOf<String, Long>()
    private val sessionLoads = mutableMapOf<String, Int>()
    private var choicesJob: Job? = null
    private var threadListGeneration = 0
    private var threadListJob: Job? = null
    private var threadListQuery: Triple<String, Boolean, String?>? = null
    private var threadLists: List<SavedThreadList>? = null
    private val sessions = mutableMapOf<String, PocketState>()
    fun hasDraft(id: String): Boolean { val s = if (state.value.thread?.id == id) state.value else sessions[id]; return s != null && (s.draft.isNotBlank() || s.draftImages.isNotEmpty() || s.draftContext.isNotEmpty() || s.contextLoading || s.imagesLoading || s.queuedMessages.isNotEmpty()) }
    fun navigationThreads(): Map<String, List<ThreadInfo>> {
        val s = state.value
        val lists = threadLists.orEmpty().filter { it.archived == s.showArchived }.associate { it.projectId to it.threads }.toMutableMap()
        s.project?.let { if (s.threads.isNotEmpty() || (!s.threadsLoading && s.threadsError == null)) lists[it.id] = s.threads }
        if (!s.showArchived) s.sessionTabs.groupBy { it.project.id }.forEach { (id, tabs) ->
            lists[id] = (lists[id].orEmpty() + tabs.map { it.thread }).distinctBy { it.id }
        }
        return lists
    }
    fun conversationState(id: String): PocketState? = session(id)?.copy(
        connected = state.value.connected, connecting = state.value.connecting, reconnecting = state.value.reconnecting,
        loading = state.value.loading, profile = state.value.profile, usage = state.value.usage,
        usageUpdatedAt = state.value.usageUpdatedAt, usageLoading = state.value.usageLoading, usageErrors = state.value.usageErrors)
    fun recovery(): SavedHost {
        val s = state.value
        return SavedHost(s.profile!!.id, s.sessionTabs.map { tab ->
            val source = if (s.thread?.id == tab.thread.id) s else sessions[tab.thread.id]
            SavedTab(tab.thread, tab.project, tab.number, tab.name, source?.draft.orEmpty(), source?.draftImages.orEmpty(), (source?.sending == true && source.queuedMessages.none { it.sending }) || source?.imageError?.startsWith("发送结果未确认") == true, source?.queuedMessages.orEmpty(), source?.taskReview?.copy(diffs = emptyMap()), source?.draftContext.orEmpty())
        }, s.thread?.id, s.usage, s.usageUpdatedAt)
    }
    fun restore(saved: SavedHost) {
        val base = state.value.copy(usage = saved.usage, usageUpdatedAt = saved.usageUpdatedAt)
        val tabs = saved.tabs.filter { it.number > 0 && it.thread.id.isNotBlank() }.distinctBy { it.thread.id }
        tabs.forEach { tab ->
            val images = restoredImages(tab.images)
            val queued = tab.queuedMessages.take(20).map { message ->
                val valid = restoredImages(message.images)
                message.copy(images = valid, context = message.context, sending = false, error = when {
                    restoredContext(message.context).size != message.context.size -> "部分上下文附件已不可用，请取消此消息后重新添加"
                    message.sending -> "发送结果未确认，请先检查历史再决定是否继续"
                    valid.size != message.images.size -> "部分图片已不可用，请编辑或取消此消息"
                    else -> message.error
                })
            }
            sessions[tab.thread.id] = base.copy(project = tab.project, root = tab.thread.cwd.ifBlank { tab.project.roots.firstOrNull().orEmpty() },
                directory = tab.thread.cwd, thread = tab.thread, draft = tab.draft, draftImages = images, draftContext = restoredContext(tab.context), contextError = if (restoredContext(tab.context).size != tab.context.size) "部分上下文附件已不可用，请重新选择" else null, cached = true,
                queuedMessages = queued, queuePaused = queued.isNotEmpty(), taskReview = tab.taskReview,
                imageError = if (images.size != tab.images.size) "部分图片草稿已不可用，请重新选择" else if (tab.uncertainSend) "发送结果未确认，请检查历史后再决定是否重试" else null)
        }
        tabs.forEach { tab -> viewModelScope.launch {
            val cached = withContext(Dispatchers.IO) { runCatching { vault.get("history:${base.profile!!.id}:${tab.thread.id}")?.let { json.decodeFromString<List<ChatItem>>(it) } }.getOrNull().orEmpty() }
            if (state.value.sessionTabs.any { it.thread.id == tab.thread.id }) updateSession(tab.thread.id) { if (it.items.isEmpty()) it.copy(items = cached) else it }
        } }
        val selected = sessions[saved.selectedThread]
        mutable.value = (selected ?: base).copy(sessionTabs = tabs.map { SessionTab(it.thread, it.project, it.number, it.name, profileId = base.profile!!.id, profileName = base.profile.name) })
    }
    fun resumeConnection() {
        val s = state.value
        if (s.profile?.autoConnect == true && !s.connecting && !s.connected && !s.needsCredentials && s.trust == null) reconnect(s.profile)
    }
    private var uiVisible = false
    fun setVisible(visible: Boolean) {
        uiVisible = visible
        if (visible) mutable.update { s -> s.copy(sessionTabs = s.sessionTabs.map { if (it.thread.id == s.thread?.id) it.copy(unread = false) else it }) }
    }
    fun updateProfiles(profiles: List<HostProfile>) { mutable.update { it.copy(profiles = profiles, profile = profiles.find { p -> p.id == it.profile?.id } ?: it.profile) } }
    fun credentialsMissing() { mutable.update { it.copy(needsCredentials = true, connecting = false, reconnecting = false) } }
    fun storedCredentials(id: String) = runCatching { vault.credentials(id) }.getOrDefault(Credentials())
    fun reconnect(profile: HostProfile): Boolean {
        val stored = storedCredentials(profile.id)
        val credentials = if (if (profile.authType == "key") stored.privateKey.isNotBlank() else stored.password.isNotBlank()) stored else ephemeralCredentials
        if (if (profile.authType == "key") credentials.privateKey.isBlank() else credentials.password.isBlank()) { credentialsMissing(); return false }
        if (profile.jumpHosts.any { val secret = credentials.jumps[it.id]; if (it.authType == "key") secret?.privateKey.isNullOrBlank() else secret?.password.isNullOrBlank() }) { credentialsMissing(); return false }
        connect(profile, credentials, preserveWorkspace = state.value.sessionTabs.isNotEmpty() || state.value.project != null)
        return true
    }
    fun saveAndConnect(profile: HostProfile, credentials: Credentials, remember: Boolean) {
        if (profile.host.isBlank() || profile.username.isBlank() || profile.port !in 1..65535) { error("请填写 SSH 地址、用户名和正确端口"); return }
        if (profile.proxyEnabled && (profile.proxyHost.isBlank() || profile.proxyPort !in 1..65535)) { error("请填写正确的 SOCKS5 代理地址和端口"); return }
        val previous = state.value.profiles.find { it.id == profile.id }
        val safeProfile = profile.copy(fingerprint = if (previous != null && (previous.host != profile.host || previous.port != profile.port)) "" else profile.fingerprint,
            jumpHosts = profile.jumpHosts.map { hop -> val old = previous?.jumpHosts?.find { it.id == hop.id }; if (old != null && (old.host != hop.host || old.port != hop.port)) hop.copy(fingerprint = "") else hop })
        saveProfile(safeProfile)
        runCatching { if (remember) vault.saveCredentials(safeProfile.id, credentials) else vault.remove("secret:${safeProfile.id}") }.onFailure { error(it.message.orEmpty()) }
        ephemeralCredentials = credentials
        val sameRoute = previous != null && previous.host == safeProfile.host && previous.port == safeProfile.port && previous.username == safeProfile.username && previous.routeMode == safeProfile.routeMode && previous.remoteTarget == safeProfile.remoteTarget && previous.jumpHosts == safeProfile.jumpHosts && previous.engineId == safeProfile.engineId && previous.codexPath == safeProfile.codexPath
        if (safeProfile.autoConnect) connect(safeProfile, credentials, preserveWorkspace = sameRoute && (state.value.sessionTabs.isNotEmpty() || state.value.project != null))
        else { disconnect(preserveWorkspace = sameRoute); ephemeralCredentials = credentials }
    }
    fun connect(profile: HostProfile, credentials: Credentials, preserveWorkspace: Boolean = false) {
        if (preserveWorkspace) stashSession() else reconnectAttempt = 0
        val workspace = state.value.takeIf { preserveWorkspace }
        val savedSessions = if (preserveWorkspace) sessions.toMap() else emptyMap()
        disconnect()
        sessions.putAll(savedSessions.mapValues { (_, saved) -> saved.copy(queuedMessages = pausedQueuedMessages(saved.queuedMessages), queuePaused = saved.queuedMessages.isNotEmpty(), taskReviewLoading = false, threadReady = false, attaching = false, settingsApplying = false, sending = false, sendingComments = false, usageLoading = emptySet()) })
        ephemeralCredentials = credentials
        mutable.update { current -> workspace?.copy(profiles = current.profiles, profile = profile, connected = false, connecting = true, reconnecting = true, reconnectAttempt = reconnectAttempt,
            threadReady = false, usageLoading = emptySet(), attaching = workspace.thread != null, taskReviewLoading = false, taskReviewError = if (workspace.taskReviewLoading) "连接已中断，本轮统计未完成" else workspace.taskReviewError, queuedMessages = pausedQueuedMessages(workspace.queuedMessages), queuePaused = workspace.queuedMessages.isNotEmpty(), settingsApplying = false, sending = false, sendingComments = false, pending = emptyList(), error = null, needsCredentials = false)
            ?: PocketState(profiles = current.profiles, profile = profile, connecting = true) }
        runCatching { vault.get("projects:${profile.id}")?.let { json.decodeFromString<List<ProjectInfo>>(it) } }.getOrNull()?.let { cached -> mutable.update { it.copy(projects = cached, cached = true) } }
        connectJob = viewModelScope.launch {
            try {
                val client = engines.open(profile, credentials, viewModelScope)
                repo = client
                connectionJobs += launch(start = CoroutineStart.UNDISPATCHED) { client.events.collect { event(it) } }
                client.initialize()
                vault.put("lastProfile", profile.id)
                mutable.update { it.copy(connected = true, connecting = false, reconnecting = false, reconnectAttempt = 0, trust = null, connectionIssue = null) }
                reconnectAttempt = 0
                // Start the visible project's list before restoring background conversations.
                val current = state.value
                if (current.project == null && current.sessionTabs.isEmpty()) {
                    val last = vault.get("lastProject:${profile.id}")
                    current.projects.find { it.id == last }?.let(::selectProject)
                } else if (current.project != null && current.thread == null) loadThreads()
                refreshProjects()
                if (preserveWorkspace) restoreSessions(repo!!)
                connectionJobs += launch {
                    val message = client.failure.filterNotNull().first()
                    if (repo === client) scheduleReconnect(profile, credentials, message)
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (required: HostKeyRequired) { mutable.update { it.copy(connecting = false, reconnecting = false, trust = required.identity) } }
            catch (error: Exception) {
                repo?.close(); repo = null
                scheduleReconnect(profile, credentials, error.message ?: "连接失败")
            }
        }
    }
    private fun scheduleReconnect(profile: HostProfile, credentials: Credentials, message: String) {
        if (reconnectJob?.isActive == true) return
        val saved = state.value.profiles.find { it.id == profile.id } ?: return
        val retry = saved.autoConnect && state.value.profile?.id == profile.id
        mutable.update { it.copy(connected = false, connecting = false, filesLoading = false, reconnecting = retry, pending = emptyList(), threadReady = false, settingsApplying = false, sending = false,
            queuedMessages = pausedQueuedMessages(it.queuedMessages), queuePaused = it.queuedMessages.isNotEmpty(), taskReviewLoading = false, connectionIssue = message, error = if (retry) null else "$message。可在设置中手动连接。") }
        if (!retry) return
        val attempt = ++reconnectAttempt
        mutable.update { it.copy(reconnectAttempt = attempt) }
        reconnectJob = viewModelScope.launch {
            delay(reconnectDelaySeconds(attempt) * 1000)
            val latest = state.value.profiles.find { it.id == profile.id }?.takeIf { it.autoConnect } ?: return@launch
            reconnectJob = null
            connect(latest, credentials, preserveWorkspace = true)
        }
    }
    private fun restoreSessions(repository: AgentRepository) {
        state.value.sessionTabs.forEach { restoreSession(repository, it) }
        if (state.value.thread != null) refreshChoices()
    }
    private fun restoreSession(repository: AgentRepository, tab: SessionTab) {
        connectionJobs += viewModelScope.launch {
            try {
                refreshUsage(tab.thread.id)
                val revision = turnRevisions[tab.thread.id] ?: 0L
                val source = if (state.value.thread?.id == tab.thread.id) state.value else sessions[tab.thread.id]
                // Missing history alone is never enough to replace an existing conversation.
                val cached = withContext(Dispatchers.IO) { vault.get("history:${state.value.profile!!.id}:${tab.thread.id}") }
                val emptyCache = cached == null || runCatching { json.decodeFromString<List<ChatItem>>(cached).isEmpty() }.getOrDefault(false)
                val replaceEmpty = source != null && source.items.isEmpty() && emptyCache && source.activeTurn == null &&
                    !source.sending && source.imageError == null && source.queuedMessages.none { it.sending || it.error != null } &&
                    tab.thread.title == "未命名会话"
                val (restored, actual) = repository.reconnectThread(tab.thread, tab.project, replaceMissingEmpty = replaceEmpty)
                if (repo !== repository) return@launch
                if (restored.id != tab.thread.id) {
                    val current = state.value
                    val saved = if (current.thread?.id == tab.thread.id) current else sessions[tab.thread.id]
                    if (saved != null) {
                        sessions.remove(tab.thread.id)
                        sessionLoads.remove(tab.thread.id)
                        sessions[restored.id] = saved.copy(thread = restored, threadSyncError = null, cached = false, settings = actual, threadReady = true, attaching = false, activeTurn = null, activeTurnStartedAtMs = null)
                        mutable.update { it.copy(sessionTabs = it.sessionTabs.map { entry -> if (entry.thread.id == tab.thread.id) entry.copy(thread = restored, running = false) else entry },
                            threadSyncError = if (it.thread?.id == tab.thread.id) null else it.threadSyncError,
                            cached = if (it.thread?.id == tab.thread.id) false else it.cached,
                            thread = if (it.thread?.id == tab.thread.id) restored else it.thread,
                            settings = if (it.thread?.id == tab.thread.id) actual else it.settings,
                            threadReady = if (it.thread?.id == tab.thread.id) true else it.threadReady,
                            attaching = if (it.thread?.id == tab.thread.id) false else it.attaching) }
                    }
                    return@launch
                }
                val page = repository.history(restored.id)
                if (repo !== repository) return@launch
                updateSession(tab.thread.id) { current ->
                    val unchanged = (turnRevisions[tab.thread.id] ?: 0L) == revision
                    current.copy(threadSyncError = null, threadReady = true, attaching = false, cached = false, settings = actual, historyCursor = page.cursor,
                        items = if (unchanged) mergeItems(current.items, page.items) else mergeItems(page.items, current.items),
                        activeTurn = if (unchanged) page.turnId else current.activeTurn,
                        activeTurnStartedAtMs = if (unchanged) page.items.firstOrNull { it.turnId == page.turnId }?.turnStartedAtMs else current.activeTurnStartedAtMs)
                }
            } catch (c: CancellationException) { throw c }
            catch (e: Exception) { if (repo === repository) sessionSyncFailed(tab.thread.id, e) }
        }
    }
    fun acceptHostKey() {
        val s = state.value
        val identity = s.trust ?: return
        if (identity.changed) return
        val current = s.profile ?: return
        val profile = if (identity.hopId == null) current.copy(fingerprint = identity.fingerprint)
            else current.copy(jumpHosts = current.jumpHosts.map { if (it.id == identity.hopId) it.copy(fingerprint = identity.fingerprint) else it })
        saveProfile(profile); connect(profile, ephemeralCredentials, preserveWorkspace = state.value.sessionTabs.isNotEmpty() || state.value.project != null)
    }
    fun dismissTrust() { mutable.update { it.copy(trust = null, error = "主机指纹尚未确认，请编辑连接后重试") } }
    fun disconnect(preserveWorkspace: Boolean = false) {
        if (preserveWorkspace) stashSession()
        reconnectJob?.cancel(); reconnectJob = null
        choicesJob?.cancel()
        reviewPolls.values.forEach(Job::cancel); reviewPolls.clear(); reviewBaselines.clear(); reviewFinishing.clear()
        changesJob?.cancel(); changesGeneration++
        threadListJob?.cancel(); threadListJob = null; threadListQuery = null
        usageReads.values.forEach(Job::cancel); usageReads.clear(); usageRevisions.clear()
        sessionLoads.clear(); turnRevisions.clear()
        if (preserveWorkspace) sessions.replaceAll { _, saved -> saved.copy(threadReady = false, attaching = false, queuedMessages = pausedQueuedMessages(saved.queuedMessages), queuePaused = saved.queuedMessages.isNotEmpty(), taskReviewLoading = false, imageError = if (saved.sending) "发送结果未确认，请检查历史后再决定是否重试" else saved.imageError, sending = false, settingsApplying = false, sendingComments = false) } else sessions.clear()
        if (images.isInitialized()) images.value.clear()
        connectJob?.cancel(); connectionJobs.forEach(Job::cancel); connectionJobs.clear()
        repo?.close(); repo = null
        if (!preserveWorkspace) ephemeralCredentials = Credentials()
        threadGeneration++; fileGeneration++; threadListGeneration++
        mutable.update { if (!preserveWorkspace) PocketState(profiles = it.profiles, profile = it.profile) else it.copy(connected = false, connecting = false, reconnecting = false, threadsLoading = false, filesLoading = false, changesLoading = false, taskReviewLoading = false, queuedMessages = pausedQueuedMessages(it.queuedMessages), queuePaused = it.queuedMessages.isNotEmpty(), usageLoading = emptySet(), reconnectAttempt = 0, trust = null, pending = emptyList(), imageError = if (it.sending) "发送结果未确认，请检查历史后再决定是否重试" else it.imageError, sending = false, attaching = false, threadReady = false, settingsApplying = false, choicesLoading = false, sendingComments = false) }
    }
    fun home() { stashSession(); threadGeneration++; threadListGeneration++; threadListJob?.cancel(); mutable.update { it.copy(project = null, thread = null, preview = null, diff = null, tab = 0, threadCursor = null, threadsLoading = false) } }
    fun clearError() { mutable.update { it.copy(error = null) } }
    private fun error(message: String) { mutable.update { it.copy(error = message) } }
    private fun work(block: suspend () -> Unit) = viewModelScope.launch {
        mutable.update { it.copy(loading = true) }
        try { block() } catch (cancel: CancellationException) { throw cancel } catch (e: Exception) { error(e.message ?: "操作失败") }
        finally { mutable.update { it.copy(loading = false) } }
    }
    fun refreshProjects() = work {
        val repository = requireRepo()
        val discovered = repository.projects()
        if (repo !== repository) return@work
        // Directory groups are user workspaces too. A newer native project or a
        // recent-history window must not erase their cached navigation entries.
        val projects = retainDirectoryProjects(discovered, state.value.projects)
        val id = state.value.profile?.id ?: return@work
        withContext(Dispatchers.IO) { vault.put("projects:$id", json.encodeToString(projects)) }
        mutable.update { it.copy(projects = projects, cached = false) }
        val last = vault.get("lastProject:$id")
        if (state.value.project == null && state.value.sessionTabs.isEmpty() && last != null) projects.find { it.id == last }?.let(::selectProject)
    }
    fun selectProject(project: ProjectInfo) {
        stashSession()
        threadGeneration++; fileGeneration++
        val root = project.roots.firstOrNull().orEmpty()
        val saved = threadLists?.find { it.projectId == project.id && !it.archived }
        mutable.update { it.copy(project = project, root = root, thread = null, draftImages = emptyList(), draftContext = emptyList(), contextLoading = false, contextError = null, queuedMessages = emptyList(), queuePaused = false, taskReview = null, taskReviewLoading = false, taskReviewError = null, imagesLoading = false, imageError = null, threads = saved?.threads.orEmpty(), threadCursor = saved?.cursor, threadsCached = saved != null, threadsError = null, showArchived = false, items = emptyList(), activeTurn = null, directory = root, files = emptyList(), preview = null, changes = emptyList(), changesRoot = null, changesError = null, reviewOverview = null, reviewRange = "workspace", reanchorComment = null, diff = null, tab = 0) }
        state.value.profile?.id?.let { vault.put("lastProject:$it", project.id) }
        loadReviewComments(root)
        loadThreads()
    }
    fun changeRoot(root: String) { fileGeneration++; mutable.update { it.copy(root = root, directory = root, files = emptyList(), preview = null, changes = emptyList(), changesRoot = null, changesError = null, reviewOverview = null, reviewRange = "workspace", reanchorComment = null, diff = null) }; loadReviewComments(root); tab(state.value.tab) }
    fun tab(index: Int) { if (index != 2) { fileGeneration++; mutable.update { it.copy(filesLoading = false) } }; mutable.update { it.copy(tab = index) }; if (index == 1) loadFiles(state.value.directory.ifBlank { state.value.root }); if (index == 2) refreshChanges() }
    fun loadThreads(more: Boolean = false): Job {
        val s = state.value
        val project = s.project ?: return viewModelScope.launch { }
        val repository = repo ?: return viewModelScope.launch { }
        val archived = s.showArchived
        val cursor = if (more) s.threadCursor else null
        val query = Triple(project.id, archived, cursor)
        threadListJob?.takeIf { it.isActive && threadListQuery == query }?.let { return it }
        threadListJob?.cancel()
        threadListQuery = query
        val generation = ++threadListGeneration
        fun current() = repo === repository && generation == threadListGeneration && state.value.project?.id == project.id && state.value.showArchived == archived
        mutable.update { it.copy(threadsLoading = true, threadsError = null) }
        return viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val key = "threadLists:${s.profile!!.id}"
                if (threadLists == null) threadLists = withContext(Dispatchers.IO) {
                    runCatching { vault.get(key)?.let { json.decodeFromString<List<SavedThreadList>>(it) } }.getOrNull().orEmpty().take(20)
                }
                if (!more && current()) threadLists?.find { it.projectId == project.id && it.archived == archived }?.let { saved ->
                    if (state.value.threads.isEmpty()) mutable.update { it.copy(threads = saved.threads, threadCursor = saved.cursor, threadsCached = true) }
                }
                val (threads, next) = withTimeout(15000) { repository.threads(project, cursor, archived) }
                if (current()) {
                    mutable.update { it.copy(threads = if (more) (it.threads + threads).distinctBy(ThreadInfo::id) else threads, threadCursor = next, threadsCached = false) }
                    if (!more) {
                        val saved = SavedThreadList(project.id, archived, threads, next)
                        threadLists = (listOf(saved) + threadLists.orEmpty().filterNot { it.projectId == project.id && it.archived == archived }).take(20)
                        val encoded = json.encodeToString(threadLists!!)
                        withContext(Dispatchers.IO) { runCatching { vault.put(key, encoded) } }
                    }
                }
            } catch (cancel: CancellationException) {
                currentCoroutineContext().ensureActive()
                if (current()) mutable.update { it.copy(threadsError = "加载超时，请重试") }
            } catch (e: Exception) { if (current()) mutable.update { it.copy(threadsError = e.message ?: "加载失败，请重试") } }
            finally { if (current()) mutable.update { it.copy(threadsLoading = false) } }
        }.also { threadListJob = it; connectionJobs += it; it.start() }
    }
    fun showArchived(value: Boolean) {
        val saved = threadLists?.find { it.projectId == state.value.project?.id && it.archived == value }
        mutable.update { it.copy(showArchived = value, threads = saved?.threads.orEmpty(), threadCursor = saved?.cursor, threadsCached = saved != null, threadsError = null) }
        loadThreads()
    }
    fun dismissArchiveUndo() { mutable.update { it.copy(archiveUndo = null) } }
    fun undoArchive() { state.value.archiveUndo?.let { archiveThread(it.thread, restore = !it.restored, undoing = true) } }
    fun archiveThread(thread: ThreadInfo, restore: Boolean = state.value.showArchived, undoing: Boolean = false) {
        val s = state.value
        if (!s.connected || s.archivingThread != null) return
        val source = if (s.thread?.id == thread.id) s else sessions[thread.id]
        if (!restore && (hasDraft(thread.id) || source?.sending == true || source?.activeTurn != null)) {
            error("此会话还有草稿、待发消息或正在执行的任务，请处理完成后再归档。")
            return
        }
        val repository = requireRepo()
        mutable.update { it.copy(archivingThread = thread.id) }
        viewModelScope.launch {
            try {
                if (restore) repository.unarchiveThread(thread.id) else repository.archiveThread(thread.id)
                if (repo === repository) {
                    mutable.update { it.copy(archiveUndo = if (undoing) null else ArchiveUndo(thread, restore)) }
                }
                if (repo === repository && state.value.project?.id == s.project?.id) {
                    threadListJob?.cancel()
                    threadLists = threadLists?.map { it.copy(threads = it.threads.filterNot { entry -> entry.id == thread.id }) }
                    mutable.update { it.copy(threads = it.threads.filterNot { t -> t.id == thread.id }) }
                    loadThreads()
                }
            } catch (c: CancellationException) { throw c }
            catch (e: Exception) { error("${if (restore) "恢复" else "归档"}失败：${e.message}") }
            finally { mutable.update { it.copy(archivingThread = null) } }
        }
    }
    fun openThread(thread: ThreadInfo, freshlyCreated: Boolean = false) {
        if (state.value.thread?.id == thread.id && !freshlyCreated) { mutable.update { it.copy(tab = 0, preview = null, diff = null) }; return }
        stashSession()
        val snapshot = sessions[thread.id]
        if (!state.value.connected) {
            if (snapshot != null) mutable.update { it.copy(project = snapshot.project, thread = snapshot.thread, tab = 0, root = snapshot.root, directory = snapshot.directory,
                threadSyncError = snapshot.threadSyncError, items = snapshot.items, draft = snapshot.draft, draftImages = snapshot.draftImages, draftContext = snapshot.draftContext, contextLoading = snapshot.contextLoading, contextError = snapshot.contextError, queuedMessages = snapshot.queuedMessages, queuePaused = snapshot.queuePaused, taskReview = snapshot.taskReview, taskReviewLoading = snapshot.taskReviewLoading, taskReviewError = snapshot.taskReviewError, imagesLoading = snapshot.imagesLoading, imageError = snapshot.imageError, settings = snapshot.settings, historyCursor = snapshot.historyCursor, activeTurn = snapshot.activeTurn,
                activeTurnStartedAtMs = snapshot.activeTurnStartedAtMs, threadReady = false, attaching = false, sending = false, settingsApplying = false, preview = null, diff = null) }
            return
        }
        val project = snapshot?.project ?: state.value.sessionTabs.find { it.thread.id == thread.id }?.project ?: state.value.project ?: return
        val newTab = if (state.value.sessionTabs.none { it.thread.id == thread.id }) SessionTab(thread, project, allocateTabNumber(), profileId = state.value.profile!!.id, profileName = state.value.profile!!.name) else null
        mutable.update { current -> current.copy(sessionTabs = if (newTab == null) current.sessionTabs.map { if (it.thread.id == thread.id) it.copy(unread = false) else it } else current.sessionTabs + newTab) }
        if (!freshlyCreated) refreshUsage(thread.id)
        if (snapshot != null && snapshot.threadReady && !snapshot.attaching) {
            threadGeneration++; fileGeneration++
            mutable.update { current -> current.copy(project = project, thread = snapshot.thread, tab = 0, root = snapshot.root, directory = snapshot.directory,
                threadSyncError = snapshot.threadSyncError, items = snapshot.items, draft = snapshot.draft, draftImages = snapshot.draftImages, draftContext = snapshot.draftContext, contextLoading = snapshot.contextLoading, contextError = snapshot.contextError, queuedMessages = snapshot.queuedMessages, queuePaused = snapshot.queuePaused, taskReview = snapshot.taskReview, taskReviewLoading = snapshot.taskReviewLoading, taskReviewError = snapshot.taskReviewError, imagesLoading = snapshot.imagesLoading, imageError = snapshot.imageError, historyCursor = snapshot.historyCursor, activeTurn = snapshot.activeTurn, activeTurnStartedAtMs = snapshot.activeTurnStartedAtMs,
                settings = snapshot.settings, settingsApplying = snapshot.settingsApplying, sending = snapshot.sending, sendingComments = snapshot.sendingComments,
                threadReady = true, attaching = false, cached = snapshot.cached, files = emptyList(), changes = emptyList(), changesRoot = null, changesError = null, reviewOverview = null, reviewRange = "workspace", reanchorComment = null, preview = null, diff = null,
                showArchived = if (current.project?.id == project.id) current.showArchived else false,
                threads = if (current.project?.id == project.id) current.threads else emptyList(), threadCursor = if (current.project?.id == project.id) current.threadCursor else null) }
            loadReviewComments(snapshot.root)
            loadThreads()
            refreshChoices()
            return
        }
        val generation = ++threadGeneration
        sessionLoads[thread.id] = generation
        val repository = requireRepo()
        fileGeneration++
        val profileId = state.value.profile?.id.orEmpty()
        var cached = emptyList<ChatItem>()
        var historyLoaded = false
        val projectChanged = state.value.project?.id != project.id
        val root = thread.cwd.ifBlank { project.roots.firstOrNull().orEmpty() }
        mutable.update { it.copy(project = project, tab = 0, thread = thread, threadSyncError = null, items = snapshot?.items.orEmpty(), activeTurn = snapshot?.activeTurn, activeTurnStartedAtMs = snapshot?.activeTurnStartedAtMs, historyCursor = null, cached = false, draft = snapshot?.draft.orEmpty(), draftImages = snapshot?.draftImages.orEmpty(), draftContext = snapshot?.draftContext.orEmpty(), contextLoading = snapshot?.contextLoading ?: false, contextError = snapshot?.contextError, queuedMessages = snapshot?.queuedMessages.orEmpty(), queuePaused = snapshot?.queuePaused ?: false, taskReview = snapshot?.taskReview, taskReviewLoading = snapshot?.taskReviewLoading ?: false, taskReviewError = snapshot?.taskReviewError, imagesLoading = snapshot?.imagesLoading ?: false, imageError = snapshot?.imageError, attaching = !freshlyCreated, threadReady = freshlyCreated,
            root = root, directory = root, files = emptyList(), changes = emptyList(), changesRoot = null, changesError = null, reviewOverview = null, reviewRange = "workspace", reanchorComment = null, preview = null, diff = null,
            showArchived = if (projectChanged) false else it.showArchived, threads = if (projectChanged) emptyList() else it.threads, threadCursor = if (projectChanged) null else it.threadCursor,
            settings = if (freshlyCreated) repo?.settings(thread.id) else null, settingsApplying = false, sending = snapshot?.sending ?: false, sendingComments = snapshot?.sendingComments ?: false) }
        if (projectChanged) loadThreads()
        loadReviewComments(state.value.root)
        refreshChoices()
        // A new thread has no durable history until the first user message.
        // Starting a conversation subscribes it, so its composer is ready immediately.
        if (freshlyCreated) return
        viewModelScope.launch {
            val snapshot = withContext(Dispatchers.IO) { runCatching { vault.get("history:$profileId:${thread.id}")?.let { json.decodeFromString<List<ChatItem>>(it) } }.getOrNull().orEmpty() }
            if (sessionLoads[thread.id] == generation && repo === repository && !historyLoaded && snapshot.isNotEmpty()) {
                cached = snapshot
                updateSession(thread.id) { if (it.items.isEmpty()) it.copy(items = snapshot, cached = true) else it }
            }
        }
        val attachment = CompletableDeferred<ConversationSettings>()
        // Read persisted history independently; only a not-loaded response waits for resume.
        viewModelScope.launch {
          try {
            val revision = turnRevisions[thread.id] ?: 0L
            val page = try { repository.history(thread.id) } catch (e: RpcError) {
                if (e.message.trim() != "thread not loaded: ${thread.id}") throw e
                attachment.await()
                repository.history(thread.id)
            }
            if (sessionLoads[thread.id] != generation || repo !== repository) return@launch
            historyLoaded = true
            updateSession(thread.id) { current -> current.copy(items = mergeItems(page.items, current.items.filterNot { old -> cached.any { it == old } }), historyCursor = page.cursor, activeTurn = if ((turnRevisions[thread.id] ?: 0L) == revision) page.turnId else current.activeTurn,
                activeTurnStartedAtMs = if ((turnRevisions[thread.id] ?: 0L) == revision) page.items.firstOrNull { it.turnId == page.turnId }?.turnStartedAtMs else current.activeTurnStartedAtMs, cached = false) }
            cacheHistory(if (state.value.thread?.id == thread.id) state.value else sessions[thread.id] ?: return@launch)
          } catch (c: CancellationException) { throw c }
          catch (e: Exception) { if (sessionLoads[thread.id] == generation && repo === repository) sessionSyncFailed(thread.id, e) }
        }
        viewModelScope.launch {
            try {
                val settings = repository.attachThread(thread.id)
                attachment.complete(settings)
                if (sessionLoads[thread.id] == generation && repo === repository) updateSession(thread.id) { it.copy(attaching = false, threadReady = true, settings = settings) }
            } catch (cancel: CancellationException) { attachment.cancel(cancel); throw cancel }
            catch (e: Exception) { attachment.completeExceptionally(e); if (sessionLoads[thread.id] == generation && repo === repository) { sessionSyncFailed(thread.id, e) } }
        }
    }
    fun closeThread() { stashSession(); threadGeneration++; mutable.update { it.copy(thread = null, threadSyncError = null, items = emptyList(), activeTurn = null, draft = "", draftImages = emptyList(), draftContext = emptyList(), contextLoading = false, contextError = null, imagesLoading = false, imageError = null, attaching = false, threadReady = false, sending = false, settingsApplying = false) } }
    fun refreshUsage(threadId: String? = state.value.thread?.id) {
        val id = threadId?.takeIf { it.isNotBlank() } ?: return
        val repository = repo?.takeIf { state.value.connected } ?: return
        if (usageReads[id]?.isActive == true) return
        val revision = usageRevisions[id] ?: 0L
        mutable.update { it.copy(usageLoading = it.usageLoading + id, usageErrors = it.usageErrors - id) }
        fun stillCurrent() = repo === repository && (usageRevisions[id] ?: 0L) == revision
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val usage = withTimeout(8000) { repository.savedTokenUsage(id) }
                if (stillCurrent()) mutable.update { current -> if (usage != null) current.copy(usage = current.usage + (id to usage), usageUpdatedAt = current.usageUpdatedAt + (id to System.currentTimeMillis()), usageErrors = current.usageErrors - id)
                    else current.copy(usageErrors = current.usageErrors + (id to "此会话暂无已保存的用量记录")) }
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                if (stillCurrent()) mutable.update { it.copy(usageErrors = it.usageErrors + (id to "读取用量超时，可点击刷新重试")) }
            } catch (e: Exception) {
                if (stillCurrent()) mutable.update { it.copy(usageErrors = it.usageErrors + (id to (e.message ?: "读取用量失败，可点击刷新重试"))) }
            } finally {
                if (repo === repository) mutable.update { it.copy(usageLoading = it.usageLoading - id) }
            }
        }
        usageReads[id] = job
        connectionJobs += job
        job.start()
    }
    fun refreshThread() {
        val s = state.value; val thread = s.thread ?: return
        val repository = repo?.takeIf { s.connected } ?: return
        val tab = s.sessionTabs.find { it.thread.id == thread.id } ?: return
        stashSession()
        updateSession(thread.id) { it.copy(attaching = true, threadReady = false, threadSyncError = null) }
        restoreSession(repository, tab)
    }
    private fun sessionSyncFailed(id: String, failure: Exception) {
        val missing = failure is RpcError && failure.message.trim() == "no rollout found for thread id $id"
        updateSession(id) { it.copy(attaching = false, threadReady = false,
            activeTurn = if (missing) null else it.activeTurn,
            activeTurnStartedAtMs = if (missing) null else it.activeTurnStartedAtMs,
            queuedMessages = pausedQueuedMessages(it.queuedMessages), queuePaused = it.queuedMessages.isNotEmpty(),
            threadSyncError = if (missing) "电脑上找不到此会话的历史记录。已保留本机缓存和草稿；可刷新重试，或新建会话继续。" else "会话同步失败，可刷新重试：${failure.message}") }
    }
    private fun stashSession() { val s = state.value; s.thread?.id?.let { sessions[it] = s } }
    private fun updateSession(id: String, transform: (PocketState) -> PocketState) {
        val visible = state.value.thread?.id == id
        val source = if (visible) state.value else sessions[id] ?: return
        val changed = transform(source)
        if (visible) mutable.update { transform(it) } else sessions[id] = changed
        mutable.update { current -> current.copy(sessionTabs = current.sessionTabs.map { tab -> if (tab.thread.id == id) tab.copy(thread = changed.thread ?: tab.thread, running = changed.activeTurn != null, unread = tab.unread || !(visible && uiVisible)) else tab }) }
    }
    fun closeConversation(id: String) {
        val source = if (state.value.thread?.id == id) state.value else sessions[id]
        val abandoned = source?.let { (if (it.sending) emptyList() else it.draftImages) + it.queuedMessages.filterNot { message -> message.sending }.flatMap { message -> message.images } }.orEmpty()
        val visible = state.value.thread?.id == id
        if (visible) closeThread()
        sessions.remove(id); sessionLoads.remove(id)
        mutable.update { it.copy(sessionTabs = it.sessionTabs.filterNot { tab -> tab.thread.id == id }) }
        val abandonedContext = source?.let { (if (it.sending) emptyList() else it.draftContext) + it.queuedMessages.filterNot { q -> q.sending }.flatMap { q -> q.context } }.orEmpty()
        viewModelScope.launch(Dispatchers.IO) { abandoned.forEach { java.io.File(it.path).delete() }; abandonedContext.mapNotNull { it.localPath }.forEach { java.io.File(it).delete() } }
        if (visible) state.value.sessionTabs.lastOrNull()?.let { openThread(it.thread) }
    }
    fun renameSessionTab(id: String, name: String) {
        val label = name.trim().take(24)
        mutable.update { current -> current.copy(sessionTabs = current.sessionTabs.map { if (it.thread.id == id) it.copy(name = label) else it }) }
    }
    fun switchSession(direction: Int) {
        val s = state.value
        if (s.tab != 0 || s.thread == null || !s.connected) return
        val index = s.sessionTabs.indexOfFirst { it.thread.id == s.thread.id }
        if (index < 0) return
        s.sessionTabs.getOrNull(index + direction)?.let { openThread(it.thread) }
    }
    fun moreHistory() = work {
        val s = state.value; val thread = s.thread ?: return@work; val cursor = s.historyCursor ?: return@work
        val page = requireRepo().history(thread.id, cursor)
        if (state.value.thread?.id == thread.id) mutable.update { it.copy(items = mergeItems(page.items, it.items), historyCursor = page.cursor) }
    }
    fun createConversation(text: String, created: (SessionTab) -> Unit): Job {
        val source = state.value
        val project = requireNotNull(source.project)
        val repository = requireRepo()
        return work {
            val thread = repository.newThread(project, source.root)
            if (repo !== repository) return@work
            val tab = SessionTab(thread, project, allocateTabNumber(), profileId = source.profile!!.id, profileName = source.profile.name)
            sessions[thread.id] = PocketState(profiles = source.profiles, profile = source.profile, connected = true,
                project = project, root = source.root, directory = source.root, thread = thread,
                threadReady = true, settings = repository.settings(thread.id), models = source.models,
                queuedMessages = listOf(QueuedMessage(text)))
            mutable.update { it.copy(sessionTabs = it.sessionTabs + tab,
                threads = if (it.project?.id == project.id && !it.showArchived) listOf(thread) + it.threads else it.threads) }
            created(tab)
            onQueueChanged()
            drainQueue(thread.id)
        }
    }
    fun newThread() = work {
        val s = state.value; val project = s.project ?: return@work
        val thread = requireRepo().newThread(project, s.root)
        if (state.value.project?.id != project.id) return@work
        mutable.update { it.copy(tab = 0, showArchived = false, threads = if (it.showArchived) listOf(thread) else listOf(thread) + it.threads) }
        openThread(thread, freshlyCreated = true)
    }
    fun draft(text: String) { mutable.update { it.copy(draft = text) } }
    private fun imageDraft(number: Int): Pair<String, PocketState>? {
        val s = state.value
        val tab = s.sessionTabs.find { it.number == number } ?: return null
        val source = if (s.thread?.id == tab.thread.id) s else sessions[tab.thread.id] ?: return null
        return tab.thread.id to source
    }
    fun beginImageImport(number: Int): ImageImportBudget? {
        val (id, source) = imageDraft(number) ?: return null
        if (source.sending || source.imagesLoading) return null
        val count = MAX_OUTGOING_IMAGES - source.draftImages.size
        if (count <= 0) { updateSession(id) { it.copy(imageError = "每条消息最多添加 4 张图片") }; return null }
        updateSession(id) { it.copy(imagesLoading = true, imageError = null) }
        return ImageImportBudget(count, MAX_OUTGOING_TOTAL_BYTES - source.draftImages.sumOf { it.bytes })
    }
    fun finishImageImport(number: Int, images: List<DraftImage>, message: String?): Boolean {
        val (id, source) = imageDraft(number) ?: return false
        val merged = (source.draftImages + images).distinctBy { it.id }
        val valid = merged.size <= MAX_OUTGOING_IMAGES && merged.sumOf { it.bytes.toLong() } <= MAX_OUTGOING_TOTAL_BYTES && images.all { it.bytes in 1..MAX_OUTGOING_IMAGE_BYTES }
        updateSession(id) { it.copy(draftImages = if (valid) merged else it.draftImages, imagesLoading = false, imageError = if (valid) message else "图片超过单条消息限制，请减少图片后重试") }
        return valid
    }
    fun removeDraftImage(number: Int, imageId: String) {
        val (id, source) = imageDraft(number) ?: return
        if (source.sending || source.imagesLoading) return
        val image = source.draftImages.find { it.id == imageId } ?: return
        updateSession(id) { it.copy(draftImages = it.draftImages.filterNot { image -> image.id == imageId }, imageError = null) }
        viewModelScope.launch(Dispatchers.IO) { java.io.File(image.path).delete() }
    }
    fun beginContextImport(number: Int): Boolean {
        val (id, source) = imageDraft(number) ?: return false
        if (source.sending || source.contextLoading) return false
        updateSession(id) { it.copy(contextLoading = true, contextError = null) }; return true
    }
    fun finishContextImport(number: Int, inputs: List<ContextInput>, issue: String? = null): Boolean {
        val (id, source) = imageDraft(number) ?: return false
        val merged = (source.draftContext + inputs).distinctBy { listOf(it.kind, it.path, it.localPath, it.startLine?.toString(), it.endLine?.toString()) }
        val failure = runCatching { validateContext(merged) }.exceptionOrNull()?.message
        updateSession(id) { it.copy(draftContext = if (failure == null) merged else it.draftContext, contextLoading = false, contextError = failure ?: issue) }
        return failure == null
    }
    fun removeContext(number: Int, inputId: String) {
        val (id, source) = imageDraft(number) ?: return
        if (source.sending || source.contextLoading) return
        val item = source.draftContext.find { it.id == inputId } ?: return
        updateSession(id) { it.copy(draftContext = it.draftContext.filterNot { ref -> ref.id == inputId }, contextError = null) }
        item.localPath?.let { path -> viewModelScope.launch(Dispatchers.IO) { java.io.File(path).delete() } }
    }
    fun refreshChoices() {
        choicesJob?.cancel()
        val s = state.value; val cwd = s.root
        if (!s.connected || cwd.isBlank()) return
        choicesJob = viewModelScope.launch {
            val repository = requireRepo()
            mutable.update { it.copy(choicesLoading = true, choicesError = null, permissions = emptyList()) }
            try {
                supervisorScope {
                    val models = async { try { Result.success(repository.models()) } catch (c: CancellationException) { throw c } catch (e: Exception) { Result.failure(e) } }
                    val permissions = async { try { Result.success(repository.permissions(cwd)) } catch (c: CancellationException) { throw c } catch (e: Exception) { Result.failure(e) } }
                    val modes = async { try { repository.collaborationModes() } catch (c: CancellationException) { throw c } catch (_: Exception) { emptyList() } }
                    val m = models.await(); val p = permissions.await(); val availableModes = modes.await()
                    if (state.value.root == cwd && repo === repository) mutable.update { it.copy(models = m.getOrNull() ?: it.models, permissions = p.getOrNull().orEmpty(), collaborationModes = availableModes,
                        choicesError = listOfNotNull(m.exceptionOrNull()?.message, p.exceptionOrNull()?.message).joinToString("；").takeIf(String::isNotBlank)) }
                }
            } finally { if (state.value.root == cwd && repo === repository) mutable.update { it.copy(choicesLoading = false) } }
        }
    }
    fun chooseCollaboration(mode: String) {
        val s = state.value
        if (s.settings == null || mode !in s.collaborationModes || s.activeTurn != null) return
        applySettings(SettingsChange.Collaboration(mode))
    }
    fun renameThread(name: String) { state.value.thread?.id?.let { renameThread(it, name) } }
    fun renameThread(threadId: String, name: String) {
        if (!state.value.connected || name.isBlank()) return
        val title = name.trim().take(120)
        val repository = requireRepo()
        work {
            repository.renameThread(threadId, title)
            if (repo === repository) updateThreadName(threadId, title)
        }
    }
    private fun updateThreadName(threadId: String, title: String) {
        updateSession(threadId) { it.copy(thread = it.thread?.copy(title = title)) }
        mutable.update { current -> current.copy(threads = current.threads.map { if (it.id == threadId) it.copy(title = title) else it }) }
        threadLists = threadLists?.map { saved -> saved.copy(threads = saved.threads.map { if (it.id == threadId) it.copy(title = title) else it }) }
    }
    fun forkThread() { state.value.thread?.id?.let { id -> forkThread(id) { tab -> if (state.value.thread?.id == id) openThread(tab.thread) } } }
    fun forkThread(id: String, created: (SessionTab) -> Unit) {
        val s = conversationState(id) ?: return
        val project = s.project ?: return
        if (conversationMaintenanceReason(s) != null) return
        val repository = requireRepo()
        work {
            val thread = repository.forkThread(id)
            if (repo !== repository) return@work
            val tab = SessionTab(thread, project, allocateTabNumber(), profileId = s.profile!!.id, profileName = s.profile.name)
            sessions[thread.id] = PocketState(profiles = s.profiles, profile = s.profile, connected = true,
                thread = thread, project = project, root = thread.cwd.ifBlank { s.root }, directory = thread.cwd.ifBlank { s.root })
            mutable.update { it.copy(sessionTabs = it.sessionTabs + tab) }
            created(tab)
        }
    }
    fun compactThread() { state.value.thread?.id?.let(::compactThread) }
    fun compactThread(id: String) {
        val s = conversationState(id) ?: return
        if (conversationMaintenanceReason(s) != null) return
        val repository = requireRepo()
        work { repository.compactThread(id) }
    }
    fun chooseModel(option: ModelOption) {
        val s = state.value; val current = s.settings ?: return
        if (s.models.none { it.id == option.id }) return
        val effort = current.effort?.takeIf { value -> option.efforts.any { it.id == value } } ?: option.defaultEffort
        applySettings(SettingsChange.Model(option.id, effort))
    }
    fun chooseEffort(option: EffortOption) {
        val s = state.value; val current = s.settings ?: return
        val model = s.models.find { it.id == current.model } ?: return
        if (model.efforts.none { it.id == option.id }) return
        applySettings(SettingsChange.Model(current.model, option.id))
    }
    fun choosePermission(option: PermissionOption) {
        if (!option.allowed || state.value.permissions.none { it == option }) return
        applySettings(SettingsChange.Permission(option))
    }
    private fun applySettings(change: SettingsChange) {
        val s = state.value; val thread = s.thread ?: return
        if (!s.connected || !s.threadReady || s.settingsApplying || s.sending || s.sendingComments) return
        val generation = threadGeneration
        val repository = requireRepo()
        mutable.update { it.copy(settingsApplying = true) }
        viewModelScope.launch {
            try {
                val actual = repository.updateSettings(thread.id, change)
                if (repo === repository) updateSession(thread.id) { it.copy(settings = actual) }
            } catch (c: CancellationException) { throw c }
            catch (e: Exception) { if (generation == threadGeneration) error("未能确认设置已生效，请刷新会话核对。${e.message}") }
            finally { if (repo === repository) { updateSession(thread.id) { it.copy(settingsApplying = false) }; drainQueue(thread.id) } }
        }
    }
    fun send() {
        val s = state.value; val thread = s.thread ?: return
        if ((s.draft.isBlank() && s.draftImages.isEmpty() && s.draftContext.isEmpty()) || s.contextLoading || s.imagesLoading || s.sending || s.sendingComments || s.settingsApplying || !s.connected || !s.threadReady) return
        if (s.draftImages.isNotEmpty() && s.models.find { it.id == s.settings?.model }?.inputModalities?.contains("image") == false) { error("当前模型不支持图片，请选择支持图片的模型"); return }
        if (s.activeTurn != null || s.queuedMessages.isNotEmpty() || thread.id in reviewFinishing) {
            if (s.queuedMessages.size >= 20) { error("待发消息最多 20 条，请先处理已有消息。"); return }
            mutable.update { it.copy(queuedMessages = it.queuedMessages + QueuedMessage(s.draft, s.draftImages, context = s.draftContext),
                queuePaused = it.queuedMessages.isNotEmpty() && it.queuePaused, draft = "", draftImages = emptyList(), draftContext = emptyList(), contextLoading = false, contextError = null, imageError = null) }
            onQueueChanged()
            onSendCompleted(SendCompletion(s.profile!!.id, thread.id, true))
            drainQueue(thread.id)
            return
        }
        val repository = requireRepo()
        mutable.update { it.copy(sending = true) }
        viewModelScope.launch {
            try {
                beginTurnReview(repository, thread.id, s.root)
                withContext(Dispatchers.IO) {
                    val images = s.draftImages.map { image ->
                        val out = java.io.ByteArrayOutputStream()
                        java.io.File(image.path).inputStream().use { input ->
                            val buffer = ByteArray(8192)
                            while (true) { val count = input.read(buffer); if (count < 0) break; require(out.size() + count <= MAX_OUTGOING_IMAGE_BYTES) { "图片过大，请重新选择" }; out.write(buffer, 0, count) }
                        }
                        OutgoingImage(out.toByteArray())
                    }
                    repository.send(thread.id, s.draft, s.activeTurn, images, s.draftContext)
                }
                val sent = s.draftImages.map { it.id }.toSet()
                updateSession(thread.id) { current -> current.copy(draft = if (current.draft == s.draft) "" else current.draft,
                    draftImages = current.draftImages.filterNot { it.id in sent }, draftContext = current.draftContext.filterNot { item -> s.draftContext.any { it.id == item.id } }, imageError = null,
                    thread = current.thread?.let { if (it.title == "未命名会话") it.copy(title = s.draft.lineSequence().firstOrNull()?.takeIf(String::isNotBlank)?.take(60) ?: if (s.draftImages.isNotEmpty()) "图片 · ${s.draftImages.size} 张" else "上下文 · ${s.draftContext.size} 项") else it }) }
                val acknowledged = if (state.value.thread?.id == thread.id) state.value else sessions[thread.id]
                onSendCompleted(SendCompletion(s.profile!!.id, thread.id, acknowledged?.draft.isNullOrBlank()))
                withContext(Dispatchers.IO) { s.draftImages.forEach { java.io.File(it.path).delete() }; s.draftContext.mapNotNull { it.localPath }.forEach { java.io.File(it).delete() } }
            } catch (cancel: CancellationException) { throw cancel }
            catch (e: Exception) { updateSession(thread.id) { it.copy(imageError = "发送结果未确认，请检查历史后再决定是否重试") }; error("发送结果未确认，文字和图片已保留；请先检查会话状态再重试。${e.message}") }
            finally {
                updateSession(thread.id) { it.copy(sending = false) }
                if (state.value.sessionTabs.none { it.thread.id == thread.id }) withContext(NonCancellable + Dispatchers.IO) { s.draftImages.forEach { java.io.File(it.path).delete() }; s.draftContext.mapNotNull { it.localPath }.forEach { java.io.File(it).delete() } }
                drainQueue(thread.id)
            }
        }
    }
    private fun session(id: String): PocketState? = if (state.value.thread?.id == id) state.value else sessions[id]
    fun editQueuedMessage(id: String, text: String) {
        val s = state.value
        val message = s.queuedMessages.find { it.id == id && !it.sending } ?: return
        if (text.isBlank() && message.images.isEmpty() && message.context.isEmpty()) return
        mutable.update { it.copy(queuedMessages = it.queuedMessages.map { entry -> if (entry.id == id) entry.copy(text = text, error = null, reviewComments = if (entry.text == text) entry.reviewComments else emptyList()) else entry }) }
    }
    fun cancelQueuedMessage(id: String) {
        val message = state.value.queuedMessages.find { it.id == id && !it.sending } ?: return
        mutable.update { it.copy(queuedMessages = it.queuedMessages.filterNot { entry -> entry.id == id }) }
        viewModelScope.launch(Dispatchers.IO) { message.images.forEach { java.io.File(it.path).delete() }; message.context.mapNotNull { it.localPath }.forEach { java.io.File(it).delete() } }
    }
    fun steerQueuedMessage(id: String) { state.value.thread?.id?.let { sendQueuedMessage(it, id, steer = true) } }
    fun pauseQueuedMessages() { mutable.update { it.copy(queuePaused = true) } }
    fun resumeQueuedMessages() {
        val s = state.value
        if (!s.connected || !s.threadReady || s.sending) return
        mutable.update { it.copy(queuePaused = false, queuedMessages = it.queuedMessages.map { entry -> entry.copy(error = null) }) }
        s.thread?.id?.let(::drainQueue)
    }
    private fun drainQueue(threadId: String) {
        val s = session(threadId) ?: return
        if (!state.value.connected || !s.threadReady || s.activeTurn != null || s.queuePaused || s.sending || s.settingsApplying || s.sendingComments || threadId in reviewFinishing) return
        val next = s.queuedMessages.firstOrNull()?.takeIf { !it.sending && it.error == null } ?: return
        sendQueuedMessage(threadId, next.id, steer = false)
    }
    private fun sendQueuedMessage(threadId: String, id: String, steer: Boolean) {
        val s = session(threadId) ?: return
        val message = s.queuedMessages.find { it.id == id && !it.sending } ?: return
        val repository = repo ?: return
        if (!state.value.connected || !s.threadReady || s.sending || s.settingsApplying || s.sendingComments || (steer && s.activeTurn == null)) return
        if (message.images.isNotEmpty() && s.models.find { it.id == s.settings?.model }?.inputModalities?.contains("image") == false) {
            updateSession(threadId) { it.copy(queuePaused = true, queuedMessages = it.queuedMessages.map { entry -> if (entry.id == id) entry.copy(error = "当前模型不支持图片，请切换模型后继续") else entry }) }; return
        }
        updateSession(threadId) { it.copy(sending = true, queuedMessages = it.queuedMessages.map { entry -> if (entry.id == id) entry.copy(sending = true, error = null) else entry }) }
        onQueueChanged()
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            var attempted = false
            var acknowledged = false
            val revision = turnRevisions[threadId] ?: 0L
            try {
                if (message.reviewComments.isNotEmpty()) {
                    val stale = repository.validateReviewComments(message.reviewComments)
                    require(stale.isEmpty()) { "评论位置已变化，请返回评论重新定位后发送" }
                }
                val images = withContext(Dispatchers.IO) { message.images.map { image ->
                    val file = java.io.File(image.path)
                    require(file.isFile && file.length() in 1..MAX_OUTGOING_IMAGE_BYTES.toLong()) { "图片已不可用，请编辑或取消消息" }
                    OutgoingImage(file.readBytes())
                } }
                if (!steer) beginTurnReview(repository, threadId, s.root)
                attempted = true
                val turn = repository.send(threadId, message.text, if (steer) s.activeTurn else null, images, message.context)
                if (repo !== repository) return@launch
                acknowledged = true
                updateSession(threadId) { it.copy(queuedMessages = it.queuedMessages.filterNot { entry -> entry.id == id },
                    activeTurn = if (!steer && turn.isNotBlank() && (turnRevisions[threadId] ?: 0L) == revision) turn else it.activeTurn) }
                onQueueChanged()
                withContext(Dispatchers.IO) { message.images.forEach { java.io.File(it.path).delete() }; message.context.mapNotNull { it.localPath }.forEach { java.io.File(it).delete() } }
            } catch (cancel: CancellationException) {
                if (repo === repository) updateSession(threadId) { it.copy(queuePaused = true, queuedMessages = it.queuedMessages.map { entry -> if (entry.id == id) entry.copy(sending = false, error = if (attempted) "发送结果未确认，请先检查历史再继续" else "发送已暂停") else entry }) }
                throw cancel
            } catch (e: Exception) {
                if (repo === repository) updateSession(threadId) { it.copy(queuePaused = true, queuedMessages = it.queuedMessages.map { entry -> if (entry.id == id) entry.copy(sending = false, error = if (attempted) "发送结果未确认，请检查历史后再继续：${e.message}" else e.message) else entry }) }
            } finally {
                if (repo === repository) {
                    updateSession(threadId) { it.copy(sending = false) }
                    onQueueChanged()
                    if (acknowledged) drainQueue(threadId)
                }
            }
        }
        connectionJobs += job; job.start()
    }
    fun stop() = work { val s = state.value; requireRepo().interrupt(s.thread?.id ?: return@work, s.activeTurn ?: return@work) }
    private suspend fun beginTurnReview(repository: AgentRepository, threadId: String, root: String) {
        reviewPolls.remove(threadId)?.cancel()
        reviewBaselines.remove(threadId)
        updateSession(threadId) { it.copy(taskReview = null, taskReviewLoading = true, taskReviewError = null) }
        val baseline = try { withTimeout(6000) { repository.beginReviewBaseline(root) } }
        catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            if (repo === repository) updateSession(threadId) { it.copy(taskReviewLoading = false, taskReviewError = "未记录本轮起点，可查看工作区变更") }
            return
        }
        if (repo !== repository) return
        reviewBaselines[threadId] = baseline
        updateSession(threadId) { it.copy(taskReviewLoading = false) }
        reviewPolls[threadId] = viewModelScope.launch {
            while (repo === repository && reviewBaselines[threadId] == baseline) {
                delay(8000)
                if (!uiVisible || state.value.thread?.id != threadId || threadId in reviewFinishing) continue
                try {
                    val report = withTimeout(12000) { repository.taskReview(baseline) }
                    if (repo === repository && reviewBaselines[threadId] == baseline) updateSession(threadId) { it.copy(taskReview = report, taskReviewError = null) }
                } catch (e: Exception) { currentCoroutineContext().ensureActive() }
            }
        }.also { connectionJobs += it }
    }
    private fun finishTurnReview(threadId: String) {
        val baseline = reviewBaselines.remove(threadId)
        reviewPolls.remove(threadId)?.cancel()
        val repository = repo
        if (baseline == null || repository == null) { drainQueue(threadId); return }
        reviewFinishing += threadId
        updateSession(threadId) { it.copy(taskReviewLoading = true) }
        val job = viewModelScope.launch {
            try {
                val report = withTimeout(15000) { repository.taskReview(baseline, finish = true) }
                if (repo === repository && session(threadId)?.activeTurn == null) { updateSession(threadId) { it.copy(taskReview = report, taskReviewError = null) }; onQueueChanged() }
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                if (repo === repository) updateSession(threadId) { it.copy(taskReviewError = "本轮统计未完成，可查看工作区变更") }
            } finally {
                if (repo === repository) { reviewFinishing -= threadId; updateSession(threadId) { it.copy(taskReviewLoading = false) }; drainQueue(threadId) }
            }
        }
        connectionJobs += job
    }
    fun refreshTaskReview(): Job {
        val s = state.value; val threadId = s.thread?.id
        val repository = repo
        if (threadId == null || repository == null) return viewModelScope.launch { }
        val baseline = reviewBaselines[threadId]
        val token = baseline?.token ?: s.taskReview?.token
        if (token == null) { mutable.update { it.copy(taskReviewError = "未记录本轮起点，可查看工作区变更") }; return viewModelScope.launch { } }
        updateSession(threadId) { it.copy(taskReviewLoading = true) }
        fun current() = repo === repository && (reviewBaselines[threadId]?.token == token || session(threadId)?.taskReview?.token == token)
        return viewModelScope.launch {
            try {
                val report = withTimeout(15000) { if (baseline != null) repository.taskReview(baseline) else repository.savedTaskReview(s.root, token) }
                if (current()) updateSession(threadId) { it.copy(taskReview = report, taskReviewError = null) }
            } catch (e: Exception) { currentCoroutineContext().ensureActive(); if (current()) updateSession(threadId) { it.copy(taskReviewError = "本轮记录暂不可用或已过期，请查看工作区变更") } }
            finally { if (current()) updateSession(threadId) { it.copy(taskReviewLoading = false) } }
        }.also { connectionJobs += it }
    }
    fun openTaskChanges() { mutable.update { it.copy(tab = 2, diff = null, reviewRange = "task", reanchorComment = null) }; refreshTaskReview() }
    fun chooseReviewRange(value: String) {
        if (value !in setOf("workspace", "task")) return
        mutable.update { it.copy(reviewRange = value, diff = null, reanchorComment = null) }; refreshChanges()
    }
    fun review(target: ReviewTarget) = work {
        val s = state.value
        val thread = requireRepo().newThread(s.project ?: return@work, s.root, readOnly = true)
        openThread(thread, freshlyCreated = true)
        mutable.update { it.copy(tab = 0, threads = listOf(thread) + it.threads) }
        requireRepo().review(thread.id, target)
    }
    fun loadFiles(path: String) {
        if (path.isBlank()) return
        val generation = ++fileGeneration
        mutable.update { it.copy(directory = path, filesLoading = true, filesError = null, preview = null, files = if (it.filesPath == path) it.files else emptyList()) }
        val repository = repo
        val job = viewModelScope.launch {
            try {
                val entries = withTimeout(15000) { requireNotNull(repository) { "请先连接电脑" }.files(path) }
                if (generation == fileGeneration && repo === repository && state.value.directory == path) mutable.update { it.copy(files = entries, filesPath = path) }
            } catch (c: CancellationException) {
                currentCoroutineContext().ensureActive()
                if (generation == fileGeneration) mutable.update { it.copy(filesError = "读取超时，请重试") }
            } catch (e: Exception) { if (generation == fileGeneration) mutable.update { it.copy(filesError = e.message ?: "无法读取目录") } }
            finally { if (generation == fileGeneration) mutable.update { it.copy(filesLoading = false) } }
        }
        connectionJobs += job
    }
    fun openFile(entry: FileEntry) {
        val path = state.value.directory.trimEnd('/') + "/" + entry.name
        if (entry.directory) { loadFiles(path); return }
        val generation = ++fileGeneration
        work { val file = requireRepo().file(path); if (generation == fileGeneration) mutable.update { it.copy(preview = file) } }
    }
    fun openLinkedFile(link: String, cwd: String = state.value.root) {
        val s = state.value; val generation = ++fileGeneration
        work {
            val path = remoteFileLink(link, cwd)
            val file = requireRepo().file(path)
            if (generation == fileGeneration) mutable.update { it.copy(tab = 1, preview = file, directory = path.substringBeforeLast('/')) }
        }
    }
    fun parentDirectory() { val s = state.value; val parent = s.directory.substringBeforeLast('/', ""); if (s.directory != s.root && (parent == s.root || parent.startsWith(s.root.trimEnd('/') + "/"))) loadFiles(parent) }
    fun reviewDirectory() {
        val s = state.value; val directory = s.directory
        if (directory.isBlank()) return
        if (directory != s.root) selectProject(ProjectInfo("cwd:$directory", directory.substringAfterLast('/'), listOf(directory)))
        tab(2)
    }
    fun closePreview() { fileGeneration++; mutable.update { it.copy(preview = null, diff = null) } }
    fun refreshChanges(): Job {
        if (state.value.reviewRange == "task") return refreshTaskReview()
        val root = state.value.root
        val repository = repo
        if (root.isBlank() || repository == null) return viewModelScope.launch { }
        changesJob?.cancel()
        val generation = ++changesGeneration
        fun current() = repo === repository && state.value.root == root && changesGeneration == generation
        mutable.update { it.copy(changesLoading = true, changesError = null) }
        return viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val comments = state.value.reviewComments
                val overview = withTimeout(20000) { repository.reviewOverview(root, comments.map { it.path }.distinct()) }
                if (current()) {
                    mutable.update { it.copy(changes = overview.changes, changesRoot = root, reviewOverview = overview,
                        reviewComments = it.reviewComments.map { c -> if (c.fingerprint.isNotBlank() && c.fingerprint != overview.fingerprints[c.path]) c.copy(stale = true) else c }) }
                    saveReviewComments()
                }
            } catch (cancel: CancellationException) {
                currentCoroutineContext().ensureActive()
                if (current()) mutable.update { it.copy(changesError = "读取变更超时，请重试。") }
            } catch (e: Exception) { if (current()) mutable.update { it.copy(changesError = gitFailureMessage(e.message.orEmpty())) } }
            finally { if (current()) mutable.update { it.copy(changesLoading = false) } }
        }.also { changesJob = it; connectionJobs += it; it.start() }
    }
    fun openDiff(change: GitChange, side: String = "all") = openReviewFile(change.path, side)
    private fun openReviewFile(path: String, side: String = "all", commentId: String? = null) {
        val s = state.value; val repository = repo ?: return
        val generation = ++fileGeneration
        work {
            val (text, fingerprint, metadata) = if (s.reviewRange == "task" && commentId == null) {
                val summary = s.taskReview ?: throw IllegalStateException("本轮记录暂不可用")
                val report = if (path in summary.diffs) summary else repository.savedTaskReview(s.root, summary.token)
                Triple(requireNotNull(report.diffs[path]) { "本轮中该文件没有可读取的差异" }, report.changes.find { it.path == path }?.fingerprint.orEmpty(), null)
            } else repository.reviewFile(s.root, path)
            if (repo === repository && state.value.root == s.root && fileGeneration == generation) mutable.update { it.copy(
                diff = reviewDiffSide(text, side), diffPath = path, diffSide = side, diffFingerprint = fingerprint,
                changes = metadata?.changes ?: it.changes, reviewOverview = metadata ?: it.reviewOverview, changesRoot = if (metadata != null) s.root else it.changesRoot,
                reanchorComment = commentId, showReviewComments = false, tab = 2, reviewRange = if (commentId != null) "workspace" else s.reviewRange) }
        }
    }
    fun refreshDiff() { val s = state.value; openReviewFile(s.diffPath, s.diffSide, s.reanchorComment) }
    fun navigateReviewFile(direction: Int) {
        val s = state.value
        val changes = (if (s.reviewRange == "task") s.taskReview?.changes.orEmpty() else s.changes).filter {
            s.reviewRange == "task" || s.diffSide == "all" || (s.diffSide == "staged" && it.staged) || (s.diffSide == "working" && it.working)
        }
        val index = changes.indexOfFirst { it.path == s.diffPath }
        changes.getOrNull(index + direction)?.let { openDiff(it, s.diffSide) }
    }
    fun toggleReviewViewed(path: String, side: String = "all") {
        val s = state.value
        val change = (if (s.reviewRange == "task") s.taskReview?.changes.orEmpty() else s.changes).find { it.path == path } ?: return
        if (change.fingerprint.isBlank()) return
        if (s.diff != null && s.diffPath == path && s.diffFingerprint.isNotBlank() && s.diffFingerprint != change.fingerprint) return
        val token = if (s.reviewRange == "task") s.taskReview?.token else null
        val sides = if (side != "all" || s.reviewRange == "task") listOf(side) else listOfNotNull("staged".takeIf { change.staged }, "working".takeIf { change.working })
        val keys = sides.map { reviewViewKey(s.reviewRange, token, path, it) }
        val allViewed = keys.all { s.reviewViewed[it] == change.fingerprint }
        val viewed = s.reviewViewed.toMutableMap().apply { keys.forEach { if (allViewed) remove(it) else put(it, change.fingerprint) } }.entries.toList().takeLast(3000).associate { it.toPair() }
        mutable.update { it.copy(reviewViewed = viewed) }
        s.profile?.id?.let { vault.put("reviewViewed:$it:${s.root}", json.encodeToString(viewed)) }
    }
    fun relocateReviewComment(id: String) {
        val comment = state.value.reviewComments.find { it.id == id } ?: return
        openReviewFile(comment.path, commentId = id)
    }
    fun cancelReviewRelocation() { mutable.update { it.copy(reanchorComment = null) } }
    fun reanchorReviewComment(id: String, line: DiffLine?, body: String) {
        val s = state.value
        if (body.isBlank() || s.sendingComments || s.diffFingerprint.isBlank()) return
        mutable.update { it.copy(reviewComments = it.reviewComments.map { c -> if (c.id == id) c.copy(path = s.diffPath, section = line?.section ?: "文件", oldLine = line?.oldLine, newLine = line?.newLine, excerpt = line?.text.orEmpty(), body = body.trim(), fingerprint = s.diffFingerprint, stale = false) else c }, reanchorComment = null, reviewSendIssue = null) }
        saveReviewComments()
    }
    private fun commentsKey(profile: String, root: String) = "reviewComments:$profile:$root"
    private fun loadReviewComments(root: String) {
        val profile = state.value.profile?.id ?: return
        val comments = runCatching { vault.get(commentsKey(profile, root))?.let { json.decodeFromString<List<ReviewComment>>(it) } }.getOrNull().orEmpty()
        val current = state.value
        val queuedIds = (sessions.filterKeys { it != current.thread?.id }.values + current).flatMap { it.queuedMessages }.flatMap { it.reviewComments }.filter { it.root == root }.map { it.id }.toSet()
        val drafts = comments.filter { it.root == root && it.id !in queuedIds }
        if (drafts.size != comments.size) vault.put(commentsKey(profile, root), json.encodeToString(drafts))
        val viewed = runCatching { vault.get("reviewViewed:$profile:$root")?.let { json.decodeFromString<Map<String, String>>(it) } }.getOrNull().orEmpty()
        mutable.update { it.copy(reviewComments = drafts, reviewViewed = viewed, reviewSendIssue = null, reanchorComment = null, sendingComments = root in reviewSendingRoots) }
    }
    private fun saveReviewComments() {
        val s = state.value; val profile = s.profile?.id ?: return
        vault.put(commentsKey(profile, s.root), json.encodeToString(s.reviewComments))
    }
    fun addReviewComment(line: DiffLine?, body: String) {
        val s = state.value
        if (body.isBlank() || body.length > 5000 || s.diff == null || s.reviewComments.size >= 100 || s.sendingComments) return
        val comment = ReviewComment(root = s.root, path = s.diffPath, section = line?.section ?: "文件", oldLine = line?.oldLine, newLine = line?.newLine, excerpt = line?.text.orEmpty().take(2000), body = body.trim(), fingerprint = s.diffFingerprint)
        mutable.update { it.copy(reviewComments = it.reviewComments + comment) }; saveReviewComments()
    }
    fun editReviewComment(id: String, body: String) {
        if (body.isBlank() || body.length > 5000 || state.value.sendingComments) return
        mutable.update { it.copy(reviewComments = it.reviewComments.map { c -> if (c.id == id) c.copy(body = body.trim()) else c }) }; saveReviewComments()
    }
    fun removeReviewComment(id: String) {
        if (state.value.sendingComments) return
        mutable.update { it.copy(reviewComments = it.reviewComments.filterNot { c -> c.id == id }) }; saveReviewComments()
    }
    fun sendReviewComments(ids: Set<String>? = null, steer: Boolean = false, newConversation: Boolean = false) {
        val s = state.value; val project = s.project ?: return; val profile = s.profile?.id ?: return
        val comments = s.reviewComments.filter { ids == null || it.id in ids }
        if (!s.connected || s.sending || s.settingsApplying || s.sendingComments || comments.isEmpty() || s.root in reviewSendingRoots) return
        val repository = requireRepo(); val generation = threadGeneration
        reviewSendingRoots += s.root
        mutable.update { it.copy(sendingComments = true, reviewSendIssue = null) }
        var targetId: String? = null
        viewModelScope.launch {
            try {
                val stale = withTimeout(20000) { repository.validateReviewComments(comments) }
                if (repo !== repository) return@launch
                if (stale.isNotEmpty()) {
                    val staleIds = stale.map { it.id }.toSet()
                    if (state.value.root == s.root) {
                        mutable.update { it.copy(reviewComments = it.reviewComments.map { c -> if (c.id in staleIds) c.copy(stale = true) else c }, reviewSendIssue = "${stale.size} 条评论的位置已变化，请重新定位后发送") }
                        saveReviewComments()
                    }
                    return@launch
                }
                if (state.value.root != s.root || threadGeneration != generation) return@launch
                val current = if (newConversation) null else s.thread?.takeIf { it.cwd == s.root && s.threadReady }
                require(!steer || (current != null && session(current.id)?.activeTurn != null)) { "当前任务已结束，请正常发送评论" }
                val target = current ?: repository.newThread(project, s.root)
                targetId = target.id
                if (current == null) openThread(target, freshlyCreated = true)
                updateSession(target.id) { it.copy(sendingComments = true) }
                val runtime = session(target.id) ?: state.value
                val content = reviewFeedback(comments)
                val queued = !steer && (runtime.activeTurn != null || runtime.queuedMessages.isNotEmpty() || target.id in reviewFinishing)
                if (queued) {
                    require(runtime.queuedMessages.size < 20) { "待发消息最多 20 条，请先处理已有消息" }
                    updateSession(target.id) { it.copy(queuedMessages = it.queuedMessages + QueuedMessage(content, reviewComments = comments), queuePaused = it.queuedMessages.isNotEmpty() && it.queuePaused) }
                    onQueueChanged()
                } else {
                    if (!steer) beginTurnReview(repository, target.id, s.root)
                    repository.send(target.id, content, if (steer) runtime.activeTurn else null)
                }
                val sentIds = comments.map { it.id }.toSet()
                val saved = runCatching { vault.get(commentsKey(profile, s.root))?.let { json.decodeFromString<List<ReviewComment>>(it) } }.getOrNull().orEmpty()
                vault.put(commentsKey(profile, s.root), json.encodeToString(saved.filterNot { it.id in sentIds }))
                if (state.value.root == s.root) mutable.update { it.copy(reviewComments = it.reviewComments.filterNot { c -> c.id in sentIds }, reviewSendIssue = null, showReviewComments = false) }
                if (state.value.thread?.id == target.id && repo === repository) mutable.update { it.copy(tab = 0, diff = null) }
            } catch (c: CancellationException) {
                currentCoroutineContext().ensureActive()
                if (state.value.root == s.root) mutable.update { it.copy(reviewSendIssue = "位置核对超时，评论已保留，请重试") }
            } catch (e: Exception) {
                if (state.value.root == s.root) mutable.update { it.copy(reviewSendIssue = e.message ?: "评论发送未确认，请检查会话后再重试；评论已保留") }
            } finally {
                reviewSendingRoots -= s.root
                if (repo === repository) {
                    sessions.replaceAll { _, saved -> if (saved.root == s.root) saved.copy(sendingComments = false) else saved }
                    mutable.update { if (it.root == s.root) it.copy(sendingComments = false) else it }
                    targetId?.let(::drainQueue)
                }
            }
        }
    }
    fun showReviewComments(value: Boolean) { mutable.update { it.copy(showReviewComments = value) } }
    fun restoreQueuedReview(id: String) {
        val message = state.value.queuedMessages.find { it.id == id && !it.sending && it.reviewComments.isNotEmpty() } ?: return
        mutable.update { it.copy(queuedMessages = it.queuedMessages.filterNot { q -> q.id == id },
            reviewComments = (it.reviewComments + message.reviewComments).distinctBy { c -> c.id },
            tab = 2, diff = null, reviewRange = "workspace", showReviewComments = true, reviewSendIssue = "请检查评论位置后发送") }
        saveReviewComments(); onQueueChanged(); refreshChanges()
    }
    fun decide(request: PendingRequest, accept: Boolean) = work {
        requireRepo().decide(request.id, accept)
        mutable.update { it.copy(pending = it.pending.filterNot { entry -> entry.id == request.id }) }
    }
    fun answerMcp(request: PendingRequest, action: String, content: JsonObject?) = work {
        require(action in setOf("accept", "decline", "cancel"))
        requireRepo().answerMcp(request.id, action, content)
        mutable.update { it.copy(pending = it.pending.filterNot { entry -> entry.id == request.id }) }
    }
    fun answerQuestions(request: PendingRequest, answers: Map<String, String>) = work {
        requireRepo().answerQuestions(request.id, answers)
        mutable.update { it.copy(pending = it.pending.filterNot { entry -> entry.id == request.id }) }
    }
    private fun event(event: AgentEvent) {
        when (event) {
            is AgentEvent.Request -> mutable.update { it.copy(pending = (it.pending + PendingRequest(event.id, event.kind, event.params, state.value.profile!!.id)).distinctBy(PendingRequest::id)) }
            is AgentEvent.RequestResolved -> mutable.update { it.copy(pending = it.pending.filterNot { p -> p.id == event.id }) }
            is AgentEvent.Usage -> if (event.threadId.isNotBlank()) {
                usageRevisions[event.threadId] = (usageRevisions[event.threadId] ?: 0L) + 1
                mutable.update { it.copy(usage = it.usage + (event.threadId to event.usage), usageUpdatedAt = it.usageUpdatedAt + (event.threadId to System.currentTimeMillis()), usageErrors = it.usageErrors - event.threadId) }
            }
            is AgentEvent.Archived -> {
                repo?.forgetThread(event.threadId)
                closeConversation(event.threadId)
                if (state.value.project != null) loadThreads()
            }
            is AgentEvent.Settings -> {
                repo?.acceptSettings(event.threadId, event.settings)
                updateSession(event.threadId) { it.copy(settings = event.settings) }
            }
            is AgentEvent.Closed -> {
                repo?.forgetSubscription(event.threadId)
                updateSession(event.threadId) { it.copy(threadReady = false, attaching = false) }
            }
            is AgentEvent.Renamed -> if (event.title.isNotBlank()) updateThreadName(event.threadId, event.title)
            is AgentEvent.Timeline -> timelineEvent(event.threadId, event.update)
        }
    }
    private fun timelineEvent(threadId: String, update: TimelineUpdate) {
        if (threadId.isBlank()) return
        if (update is TimelineUpdate.Started && threadId !in reviewBaselines)
            updateSession(threadId) { it.copy(taskReview = null, taskReviewLoading = false, taskReviewError = "未记录本轮起点，可查看工作区变更") }
        if (update is TimelineUpdate.Started || update is TimelineUpdate.Completed)
            turnRevisions[threadId] = (turnRevisions[threadId] ?: 0L) + 1
        updateSession(threadId) { current ->
            val runtime = sessionEvent(SessionRuntime(current.items, current.activeTurn, current.activeTurnStartedAtMs), update, System.currentTimeMillis())
            current.copy(items = runtime.items, activeTurn = runtime.activeTurn, activeTurnStartedAtMs = runtime.startedAtMs)
        }
        if (update is TimelineUpdate.Completed) {
            if (update.status == "failed") error(update.error ?: "任务失败")
            cacheHistory(if (state.value.thread?.id == threadId) state.value else sessions[threadId] ?: return)
            if (update.status in setOf("failed", "interrupted")) updateSession(threadId) { it.copy(queuePaused = it.queuedMessages.isNotEmpty()) }
            finishTurnReview(threadId)
        }
    }
    private fun requireRepo() = repo?.takeIf { state.value.connected } ?: throw IllegalStateException("请先连接电脑")
    private fun cacheHistory(s: PocketState = state.value) {
         val id = s.profile?.id ?: return; val thread = s.thread?.id ?: return
        val items = s.items.takeLast(150).map { item -> item.copy(text = item.text.take(20000), images = item.images.map { image ->
            // Large inline payloads are refetched with history, never written into preferences.
            if (image.source.startsWith("data:") && image.source.length > 65536) image.copy(source = "codex-file:refresh-history") else image
        }) }
        viewModelScope.launch(Dispatchers.IO) { runCatching { vault.put("history:$id:$thread", json.encodeToString(items)) } }
    }
    fun draftImagePaths(): List<String> = recovery().tabs.flatMap { tab -> (tab.images + tab.queuedMessages.flatMap { it.images }).map { it.path } + (tab.context + tab.queuedMessages.flatMap { it.context }).mapNotNull { it.localPath } }.distinct()
    fun close() { disconnect(); viewModelScope.cancel() }
}
