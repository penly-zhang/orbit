package dev.codexpocket

import dev.codexpocket.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

/** One workspace and retry loop per configured machine; UI selection never owns transport lifetime. */
internal class PocketController(private val vault: PocketStorage, private val scope: CoroutineScope, private val engines: AgentEngineRegistry = AgentEngines.default) {
    private val mutable = MutableStateFlow(PocketState())
    val state = mutable.asStateFlow()
    private val sendEvents = MutableSharedFlow<SendCompletion>(extraBufferCapacity = 16)
    val sendCompleted = sendEvents.asSharedFlow()
    private val hosts = linkedMapOf<String, HostWorkspace>()
    private val watchers = mutableMapOf<String, Job>()
    private var profiles = emptyList<HostProfile>()
    private var selected: String? = null
    private var pinnedThreads: Map<String, Map<String, List<String>>> = emptyMap()
    private var browsingProjects = false
    private var selectingDestination = false
    private var returnSession: String? = null
    private var newAttachments = NewTaskAttachments()
    private var newImagesLoading = false
    private var newContextLoading = false
    private var newAttachmentError: String? = null
    private fun saveNewAttachments() { vault.put("newTaskAttachments:v1", json.encodeToString(newAttachments)); publish() }
    private var emptyDraft = ""
    private var newConversationEngine = "codex"
    private var creatingConversation = false
    private var nextTabNumber = 1
    private var initialError: String? = null
    fun imageLoaderFor(id: String) = requireNotNull(hosts[id]).imageLoader
    internal val imageLoader get() = requireNotNull(active()).imageLoader

    private val manuallyPaused = mutableSetOf<String>()
    private var appVisible = true
    private val recovery = MutableStateFlow(SavedWorkspace())
    private var restoring = true
    private var recoveryJob: Job? = null
    @OptIn(FlowPreview::class)
    private fun observeRecovery() {
        recoveryJob = scope.launch { recovery.debounce(350).distinctUntilChanged().collect { saved -> withContext(Dispatchers.IO) { writeRecovery(saved) } } }
    }
    private val persistenceLock = Any()
    private fun writeRecovery(saved: SavedWorkspace) = synchronized(persistenceLock) { if (saved == recovery.value) vault.put("workspace:v1", json.encodeToString(saved)) }
    fun flushRecovery() { writeRecovery(recovery.value) }
    fun setAppVisible(visible: Boolean) {
        appVisible = visible
        hosts.forEach { (id, child) -> child.setVisible(visible && id == selected) }
        publish()
        if (!visible) flushRecovery()
    }
    fun pauseConnections() { hosts.values.forEach { it.disconnect(preserveWorkspace = true) }; publish(); flushRecovery() }
    fun resumeConnections() { hosts.filterKeys { it !in manuallyPaused }.values.forEach { it.resumeConnection() }; publish() }

    init {
        runCatching {
            profiles = vault.profiles()
            emptyDraft = vault.get("newConversationDraft").orEmpty()
            val savedAttachments = runCatching { vault.get("newTaskAttachments:v1")?.let { json.decodeFromString<NewTaskAttachments>(it) } }.getOrNull() ?: NewTaskAttachments()
            newAttachments = savedAttachments.copy(images = restoredImages(savedAttachments.images), context = restoredContext(savedAttachments.context))
            if (newAttachments != savedAttachments) newAttachmentError = "部分附件已不可用，请重新添加"
            returnSession = vault.get("lastConversation")
            pinnedThreads = runCatching { vault.get("pinnedThreads:v1")?.let { json.decodeFromString<Map<String, Map<String, List<String>>>>(it) } }.getOrNull().orEmpty()
            val last = vault.get("lastViewedHost") ?: vault.get("lastProfile")
            selected = profiles.find { it.id == last }?.id ?: profiles.firstOrNull()?.id
            selected?.let { vault.put("lastViewedHost", it) }
            val saved = runCatching { vault.get("workspace:v1")?.let { json.decodeFromString<SavedWorkspace>(it) } }.getOrNull()
            nextTabNumber = (saved?.hosts?.flatMap { it.tabs }?.maxOfOrNull { it.number } ?: 0).coerceIn(0, 1000000) + 1
            profiles.forEach(::workspace)
            saved?.hosts?.forEach { host -> hosts[host.id]?.restore(host) }
            // Recover an interrupted draft-to-queue transfer without sharing attachment ownership.
            val owned = hosts.values.flatMap { it.draftImagePaths() }.toSet()
            newAttachments = newAttachments.copy(images = newAttachments.images.filterNot { it.path in owned },
                context = newAttachments.context.filterNot { it.localPath != null && it.localPath in owned })
            vault.put("newTaskAttachments:v1", json.encodeToString(newAttachments))
            val lastTab = hosts.values.flatMap { it.state.value.sessionTabs }.find { it.key == returnSession }
            if (lastTab != null) {
                selected = lastTab.profileId
                vault.put("lastViewedHost", lastTab.profileId)
                hosts.forEach { (id, child) -> child.setVisible(appVisible && id == selected) }
                hosts[selected]?.openThread(lastTab.thread)
            }
            restoring = false
            publish()
            profiles.filter { it.autoConnect }.forEach { workspace(it).reconnect(it) }
        }.onFailure { initialError = "读取本地数据失败：${it.message}"; restoring = false; publish() }
        observeRecovery()
    }
    private fun active() = hosts[selected]
    private fun workspace(profile: HostProfile): HostWorkspace = hosts[profile.id] ?: run {
        val childScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
        val child = HostWorkspace(vault, childScope, profile, profiles, ::persistProfile, { sendEvents.tryEmit(it) }, { publish(); flushRecovery() }, engines) { nextTabNumber++ }
        hosts[profile.id] = child
        child.setVisible(appVisible && profile.id == selected)
        watchers[profile.id] = scope.launch(start = CoroutineStart.UNDISPATCHED) { child.state.collect { publish() } }
        child
    }
    fun chooseNewConversationEngine(id: String) {
        if (!creatingConversation && id in state.value.availableEngines) { newConversationEngine = id; publish() }
    }
    private fun publish() {
        val states = hosts.mapValues { it.value.state.value }
        val trustHost = profiles.firstOrNull { states[it.id]?.trust != null }
        val base = active()?.state?.value ?: PocketState(error = initialError)
        val tabs = profiles.flatMap { profile -> states[profile.id]?.sessionTabs.orEmpty().map { it.copy(profileName = profile.connectionLabel(profiles), hasDraft = hosts[profile.id]?.hasDraft(it.thread.id) == true,
                taskSending = hosts[profile.id]?.conversationState(it.thread.id)?.sending == true,
                taskReady = hosts[profile.id]?.conversationState(it.thread.id)?.let { state -> state.threadReady && !state.cached && !state.attaching } == true,
                taskIssue = hosts[profile.id]?.conversationState(it.thread.id)?.let { state -> state.threadSyncError ?: state.imageError?.takeIf { error -> error.startsWith("发送结果未确认") } ?: state.queuedMessages.firstOrNull { message -> message.error != null }?.error ?: "待发队列已暂停".takeIf { state.queuePaused && state.queuedMessages.isNotEmpty() } }) } }.sortedBy { it.number }.mapIndexed { index, tab -> tab.copy(displayNumber = index + 1) }
        val statuses = profiles.associate { profile ->
            val s = states[profile.id] ?: PocketState()
            profile.id to HostConnectionStatus(s.connected, s.connecting, s.reconnecting, s.reconnectAttempt, s.needsCredentials, s.trust != null, s.error, s.availableEngines, s.engineIssues, profile.id in manuallyPaused, s.connectionIssue)
        }
        if (!restoring && !browsingProjects && !selectingDestination && base.thread != null) {
            val key = "${base.profile?.id}:${base.thread.id}"
            if (returnSession != key) { returnSession = key; vault.put("lastConversation", key) }
        }
        val projects = profiles.flatMap { profile -> states[profile.id]?.projects.orEmpty().map { HostProject(profile.id, profile.connectionLabel(profiles), it) } }
        mutable.value = base.copy(newImages = newAttachments.images, newContext = newAttachments.context, newImagesLoading = newImagesLoading, newContextLoading = newContextLoading, newAttachmentError = newAttachmentError, newConversationEngine = newConversationEngine.takeIf { it in base.availableEngines } ?: base.availableEngines.firstOrNull().orEmpty(), navigationThreads = active()?.navigationThreads().orEmpty(), browsingProjects = browsingProjects, newConversationDraft = emptyDraft, creatingConversation = creatingConversation, error = base.error ?: initialError, pinnedThreads = pinnedThreads[selected].orEmpty(), profiles = profiles, sessionTabs = tabs.filter { it.tabOpen }, taskRecords = tabs, connections = statuses, hostProjects = projects,
            pending = profiles.flatMap { states[it.id]?.pending.orEmpty() }, trust = trustHost?.let { states[it.id]?.trust }, trustProfile = trustHost)
        if (!restoring) recovery.value = SavedWorkspace(profiles.mapNotNull { hosts[it.id]?.recovery() })
    }
    private fun persistProfile(profile: HostProfile) {
        profiles = if (profiles.any { it.id == profile.id }) profiles.map { if (it.id == profile.id) profile else it } else profiles + profile
        vault.saveProfiles(profiles)
        hosts.values.forEach { it.updateProfiles(profiles) }
        publish()
    }
    fun storedCredentials(id: String) = runCatching { vault.credentials(id) }.getOrDefault(Credentials())
    fun credentialsForEditing(id: String) = hosts[id]?.credentialsForEditing() ?: storedCredentials(id)
    fun setConnectionEnabled(profile: HostProfile, enabled: Boolean): Boolean {
        if (enabled) manuallyPaused.remove(profile.id)
        val updated = (profiles.find { it.id == profile.id } ?: profile).copy(autoConnect = enabled)
        persistProfile(updated)
        val child = workspace(updated)
        if (!enabled) { child.disconnect(preserveWorkspace = true); publish(); return true }
        val s = child.state.value
        if (s.connected || s.connecting || s.reconnecting || s.trust != null) return true
        val started = child.reconnect(updated)
        publish()
        return started
    }
    fun reconnect(profile: HostProfile): Boolean = setConnectionEnabled(profile, true)
    fun setAutoConnect(profile: HostProfile, enabled: Boolean) {
        val updated = (profiles.find { it.id == profile.id } ?: profile).copy(autoConnect = enabled)
        persistProfile(updated)
        if (!enabled && hosts[profile.id]?.state?.value?.reconnecting == true) hosts[profile.id]?.disconnect(preserveWorkspace = true)
        publish()
    }
    fun connectNow(profile: HostProfile): Boolean {
        manuallyPaused.remove(profile.id)
        val updated = profiles.find { it.id == profile.id } ?: profile
        val child = workspace(updated)
        if (child.state.value.connecting || child.state.value.trust != null) return true
        return child.reconnect(updated).also { publish() }
    }
    fun disconnectHost(profile: HostProfile) {
        manuallyPaused.add(profile.id)
        hosts[profile.id]?.disconnect(preserveWorkspace = true)
        publish(); flushRecovery()
    }
    fun saveAndConnect(profile: HostProfile, credentials: Credentials, remember: Boolean, connectNow: Boolean = profile.autoConnect) {
        val child = workspace(profiles.find { it.id == profile.id } ?: profile)
        if (connectNow) manuallyPaused.remove(profile.id)
        child.saveAndConnect(profile, credentials, remember, connectNow)
        selectHost(profile.id)
    }
    fun selectHost(id: String) {
        val profile = profiles.find { it.id == id } ?: return
        workspace(profile)
        selected = id
        hosts.forEach { (hostId, child) -> child.setVisible(appVisible && hostId == id) }
        vault.put("lastViewedHost", id)
        publish()
    }
    fun selectHostProject(project: HostProject) {
        if (creatingConversation) return
        if (selected != project.profileId || state.value.project?.id != project.project.id) {
            if (newAttachments.context.any { it.localPath == null }) {
                newAttachments = newAttachments.copy(context = newAttachments.context.filter { it.localPath != null })
                newAttachmentError = "执行位置已更换，请重新引用电脑上的文件"; saveNewAttachments()
            }
        }
        selectingDestination = true
        try { selectHost(project.profileId); active()?.selectProject(project.project) }
        finally { selectingDestination = false; publish() }
    }
    fun moveProfile(id: String, direction: Int) {
        val list = profiles.toMutableList(); val from = list.indexOfFirst { it.id == id }; val to = from + direction
        if (from < 0 || to !in list.indices) return
        list.add(to, list.removeAt(from)); profiles = list
        vault.saveProfiles(profiles); hosts.values.forEach { it.updateProfiles(profiles) }; publish()
    }
    fun deleteProfile(profile: HostProfile) {
        watchers.remove(profile.id)?.cancel()
        val removed = hosts.remove(profile.id)
        val abandoned = removed?.draftImagePaths().orEmpty()
        removed?.close()
        scope.launch(Dispatchers.IO) { abandoned.forEach { java.io.File(it).delete() } }
        vault.keys().filter { it.startsWith("history:${profile.id}:") || it.startsWith("reviewComments:${profile.id}:") || it.startsWith("reviewViewed:${profile.id}:") }.forEach(vault::remove)
        vault.remove("lastProject:${profile.id}")
        vault.remove("threadLists:${profile.id}")
        profiles = profiles.filterNot { it.id == profile.id }
        vault.saveProfiles(profiles); vault.remove("secret:${profile.id}"); vault.remove("projects:${profile.id}"); vault.remove("orbitProjects:${profile.id}")
        if (selected == profile.id) selected = profiles.firstOrNull()?.id
        hosts.values.forEach { it.updateProfiles(profiles) }; publish()
    }
    fun disconnect() { profiles.find { it.id == selected }?.let { setConnectionEnabled(it, false) } }
    fun acceptHostKey() { state.value.trustProfile?.id?.let { hosts[it]?.acceptHostKey() } }
    fun dismissTrust() { state.value.trustProfile?.id?.let { hosts[it]?.dismissTrust() } }
    fun openSession(tab: SessionTab) { browsingProjects = false; selectHost(tab.profileId); active()?.openThread(tab.thread); publish() }
    fun closeConversation(key: String) {
        val tab = findTab(key) ?: return
        hosts[tab.profileId]?.closeConversation(tab.thread.id)
        publish(); flushRecovery()
    }
    fun renameSessionTab(key: String, name: String) {
        val tab = findTab(key) ?: return
        hosts[tab.profileId]?.renameSessionTab(tab.thread.id, name)
        publish()
    }
    private fun findTab(key: String) = state.value.sessionTabs.find { it.key == key }
        ?: state.value.sessionTabs.find { it.profileId == selected && it.thread.id == key }
    fun switchSession(direction: Int) {
        val s = state.value; val index = s.sessionTabs.indexOfFirst { it.profileId == selected && it.thread.id == s.thread?.id }
        if (index >= 0) s.sessionTabs.getOrNull(index + direction)?.let(::openSession)
    }
    fun decide(request: PendingRequest, accept: Boolean) { hosts[request.profileId]?.decide(request, accept) }
    fun answerMcp(request: PendingRequest, action: String, content: JsonObject?) { hosts[request.profileId]?.answerMcp(request, action, content) }
    fun answerQuestions(request: PendingRequest, answers: Map<String, String>) { hosts[request.profileId]?.answerQuestions(request, answers) }
    fun beginImageImport(profileId: String, number: Int): ImageImportBudget? {
        if (profileId == NEW_TASK_DRAFT) {
            if (creatingConversation || newImagesLoading || newAttachments.images.size >= MAX_OUTGOING_IMAGES) return null
            newImagesLoading = true; newAttachmentError = null; publish()
            return ImageImportBudget(MAX_OUTGOING_IMAGES - newAttachments.images.size, MAX_OUTGOING_TOTAL_BYTES - newAttachments.images.sumOf { it.bytes })
        }
        val budget = hosts[profileId]?.beginImageImport(number)
        publish(); return budget
    }
    fun finishImageImport(profileId: String, number: Int, images: List<DraftImage>, message: String?): Boolean {
        if (profileId == NEW_TASK_DRAFT) {
            val merged = (newAttachments.images + images).distinctBy { it.id }
            val valid = !creatingConversation && merged.size <= MAX_OUTGOING_IMAGES && merged.sumOf { it.bytes.toLong() } <= MAX_OUTGOING_TOTAL_BYTES && images.all { it.bytes in 1..MAX_OUTGOING_IMAGE_BYTES }
            if (valid) newAttachments = newAttachments.copy(images = merged)
            newImagesLoading = false; newAttachmentError = if (valid) message else "图片超过限制，请减少图片后重试"
            saveNewAttachments(); return valid
        }
        val accepted = hosts[profileId]?.finishImageImport(number, images, message) ?: false
        publish(); return accepted
    }
    fun removeDraftImage(profileId: String, number: Int, imageId: String) {
        if (profileId == NEW_TASK_DRAFT) {
            if (creatingConversation || newImagesLoading) return
            val removed = newAttachments.images.find { it.id == imageId } ?: return
            newAttachments = newAttachments.copy(images = newAttachments.images.filterNot { it.id == imageId }); newAttachmentError = null; saveNewAttachments()
            scope.launch(Dispatchers.IO) { java.io.File(removed.path).delete() }; return
        }
        hosts[profileId]?.removeDraftImage(number, imageId); publish() }
    fun trackTask(tab: SessionTab, tracked: Boolean) { hosts[tab.profileId]?.trackTask(tab.thread.id, tracked); publish(); flushRecovery() }
    fun dismissTaskReminder(tab: SessionTab) { hosts[tab.profileId]?.dismissTaskReminder(tab.thread.id, tab.progress.turnId); publish(); flushRecovery() }
    fun clearError() { initialError = null; active()?.clearError(); publish() }
    fun close() { flushRecovery(); recoveryJob?.cancel(); watchers.values.forEach(Job::cancel); hosts.values.forEach { it.close() }; hosts.clear() }

    // Every operation is bound to the selected workspace before asynchronous work starts.
    fun showProjects() {
        browsingProjects = true
        active()?.let { if (it.state.value.project != null && it.state.value.connected) it.loadThreads() }
        publish()
    }
    fun returnToConversation() {
        val tab = state.value.sessionTabs.find { it.key == returnSession }
            ?: state.value.sessionTabs.lastOrNull().takeIf { returnSession != null }
        browsingProjects = false
        if (tab != null) openSession(tab) else { active()?.closeThread(); active()?.tab(0); publish() }
    }
    fun newConversation() {
        newConversationEngine = "codex"
        browsingProjects = false
        active()?.closeThread()
        active()?.tab(0)
        returnSession = null
        vault.remove("lastConversation")
        publish()
    }
    fun newConversationDraft(text: String) {
        emptyDraft = text
        vault.put("newConversationDraft", text)
        publish()
    }
    fun sendNewConversation() {
        val child = active() ?: return
        val target = child.state.value
        if (creatingConversation || newImagesLoading || newContextLoading || (emptyDraft.isBlank() && newAttachments.images.isEmpty() && newAttachments.context.isEmpty()) || !target.connected || target.project == null || state.value.newConversationEngine !in target.availableEngines) return
        if (newAttachments.context.any { it.localPath == null } && (newAttachments.referenceProfile != selected || newAttachments.referenceRoot != target.root)) {
            newAttachmentError = "引用文件属于其他执行位置，请移除后重新引用"; publish(); return
        }
        val text = emptyDraft
        creatingConversation = true
        publish()
        val attachments = newAttachments
        val job = child.createConversation(text, state.value.newConversationEngine, attachments.images, attachments.context) { tab ->
            publish(); flushRecovery()
            newAttachments = NewTaskAttachments(); newAttachmentError = null; saveNewAttachments()
            if (returnSession == null) { returnSession = tab.key; vault.put("lastConversation", tab.key) }
            if (emptyDraft == text) { newConversationEngine = "codex"; emptyDraft = ""; vault.remove("newConversationDraft") }
            if (!browsingProjects && selected == tab.profileId && state.value.thread == null) openSession(tab)
            publish()
            flushRecovery()
        }
        job.invokeOnCompletion { creatingConversation = false; publish() }
    }
    fun home() { active()?.home(); publish() }
    suspend fun addProject(host: String, path: String) { requireNotNull(hosts[host]).addProject(path); publish() }
    fun removeProject(host: String, project: ProjectInfo) { hosts[host]?.removeProject(project); publish() }
    fun moveProject(host: String, projectId: String, offset: Int) { hosts[host]?.moveProject(projectId, offset); publish() }
    fun refreshProjects() { active()?.refreshProjects(); publish() }
    fun selectProject(project: ProjectInfo) { active()?.selectProject(project); publish() }
    fun changeRoot(root: String) { active()?.changeRoot(root); publish() }
    fun tab(index: Int) { active()?.tab(index); publish() }
    fun loadThreads(more: Boolean = false) { active()?.loadThreads(more); publish() }
    fun showArchived(value: Boolean) { active()?.showArchived(value); publish() }
    fun undoArchive() { active()?.undoArchive(); publish() }
    fun dismissArchiveUndo() { active()?.dismissArchiveUndo(); publish() }
    fun archiveThread(thread: ThreadInfo) { active()?.archiveThread(thread); publish() }
    fun openThread(thread: ThreadInfo, freshlyCreated: Boolean = false) { browsingProjects = false; active()?.openThread(thread, freshlyCreated); publish() }
    fun closeThread() { active()?.closeThread(); publish() }
    fun refreshThread() { active()?.refreshThread(); publish() }
    fun refreshUsage() { active()?.refreshUsage(); publish() }
    fun loadAllHistory() { active()?.loadAllHistory(); publish() }
    fun moreHistory() { active()?.moreHistory(); publish() }
    fun newThread() { browsingProjects = false; active()?.newThread(); publish() }
    fun draft(text: String) { active()?.draft(text); publish() }
    fun refreshChoices() { active()?.refreshChoices(); publish() }
    fun pinThread(projectId: String, threadId: String, pinned: Boolean) { selected?.let { pinThread(it, projectId, threadId, pinned) } }
    fun pinThread(host: String, projectId: String, threadId: String, pinned: Boolean) {
        if (host !in hosts) return
        val projects = pinnedThreads[host].orEmpty()
        val ids = projects[projectId].orEmpty().filterNot { it == threadId }
        val updated = projects + (projectId to if (pinned) listOf(threadId) + ids else ids)
        val next = pinnedThreads + (host to updated)
        try {
            vault.put("pinnedThreads:v1", json.encodeToString(next))
            pinnedThreads = next
            publish()
        } catch (e: Exception) { initialError = "保存置顶失败：${e.message}"; publish() }
    }
    fun isThreadPinned(profileId: String, projectId: String, threadId: String) = threadId in pinnedThreads[profileId]?.get(projectId).orEmpty()
    fun conversationState(profileId: String, threadId: String) = hosts[profileId]?.conversationState(threadId)
    fun refreshUsage(profileId: String, threadId: String) { hosts[profileId]?.refreshUsage(threadId); publish() }
    fun openProjectThread(project: ProjectInfo, thread: ThreadInfo) { selected?.let { openProjectThread(it, project, thread) } }
    fun openProjectThread(profileId: String, project: ProjectInfo, thread: ThreadInfo) {
        val tab = state.value.sessionTabs.find { it.profileId == profileId && it.thread.id == thread.id }
        if (tab != null) { openSession(tab); return }
        selectingDestination = true
        try { selectHost(profileId); if (active()?.state?.value?.project?.id != project.id) active()?.selectProject(project) }
        finally { selectingDestination = false }
        openThread(thread)
    }
    fun archiveThread(profileId: String, thread: ThreadInfo, restore: Boolean) { hosts[profileId]?.archiveThread(thread, restore); publish() }
    fun forkThread(profileId: String, threadId: String) {
        hosts[profileId]?.forkThread(threadId) { tab ->
            publish()
            if (selected == profileId && browsingProjects) openSession(tab)
        }
        publish()
    }
    fun compactThread(profileId: String, threadId: String) { hosts[profileId]?.compactThread(threadId); publish() }
    fun renameThread(profileId: String, threadId: String, name: String) { hosts[profileId]?.renameThread(threadId, name); publish() }
    fun renameThread(name: String) { active()?.renameThread(name); publish() }
    fun forkThread() { active()?.forkThread(); publish() }
    fun compactThread() { active()?.compactThread(); publish() }
    fun chooseCollaboration(mode: String) { active()?.chooseCollaboration(mode); publish() }
    fun chooseModel(option: ModelOption) { active()?.chooseModel(option); publish() }
    fun chooseEffort(option: EffortOption) { active()?.chooseEffort(option); publish() }
    fun choosePermission(option: PermissionOption) { active()?.choosePermission(option); publish() }
    fun send() { active()?.send(); publish(); flushRecovery() }
    fun editQueuedMessage(id: String, text: String) { active()?.editQueuedMessage(id, text); publish(); flushRecovery() }
    fun cancelQueuedMessage(id: String) { active()?.cancelQueuedMessage(id); publish(); flushRecovery() }
    fun steerQueuedMessage(id: String) { active()?.steerQueuedMessage(id); publish(); flushRecovery() }
    fun resumeQueuedMessages() { active()?.resumeQueuedMessages(); publish(); flushRecovery() }
    fun pauseQueuedMessages() { active()?.pauseQueuedMessages(); publish(); flushRecovery() }
    fun stop() { active()?.stop(); publish() }
    fun review(target: ReviewTarget) { active()?.review(target); publish() }
    fun loadFiles(path: String) { active()?.loadFiles(path); publish() }
    fun openFile(entry: FileEntry) { active()?.openFile(entry); publish() }
    fun beginContextImport(id: String, number: Int): Boolean {
        if (id != NEW_TASK_DRAFT) return hosts[id]?.beginContextImport(number) == true
        if (creatingConversation || newContextLoading) return false
        newContextLoading = true; newAttachmentError = null; publish(); return true
    }
    fun finishContextImport(id: String, number: Int, inputs: List<ContextInput>, issue: String? = null): Boolean {
        if (id == NEW_TASK_DRAFT) {
            val movedReferences = inputs.any { it.localPath == null } &&
                (newAttachments.referenceProfile != selected || newAttachments.referenceRoot != state.value.root)
            val existing = if (movedReferences) newAttachments.context.filter { it.localPath != null } else newAttachments.context
            val merged = (existing + inputs).distinctBy { it.id }
            val failure = runCatching { check(!creatingConversation) { "正在创建会话" }; validateContext(merged) }.exceptionOrNull()?.message
            if (failure == null) newAttachments = newAttachments.copy(context = merged,
                referenceProfile = if (inputs.any { it.localPath == null }) selected else newAttachments.referenceProfile,
                referenceRoot = if (inputs.any { it.localPath == null }) state.value.root else newAttachments.referenceRoot)
            newContextLoading = false; newAttachmentError = failure ?: issue; saveNewAttachments(); return failure == null
        }
        val accepted = hosts[id]?.finishContextImport(number, inputs, issue) == true
        publish(); flushRecovery(); return accepted
    }
    fun removeContext(id: String, number: Int, inputId: String) {
        if (id == NEW_TASK_DRAFT) {
            if (creatingConversation || newContextLoading) return
            val removed = newAttachments.context.find { it.id == inputId } ?: return
            newAttachments = newAttachments.copy(context = newAttachments.context.filterNot { it.id == inputId }); newAttachmentError = null; saveNewAttachments()
            removed.localPath?.let { path -> scope.launch(Dispatchers.IO) { java.io.File(path).delete() } }; return
        }
        hosts[id]?.removeContext(number, inputId); publish(); flushRecovery() }
    fun artifactRepository(id: String) = requireNotNull(hosts[id]) { "电脑连接已移除" }.artifactRepository()
    fun openLinkedFileAt(id: String, link: String, cwd: String) { hosts[id]?.openLinkedFile(link, cwd); publish() }
    fun openLinkedFile(link: String) { active()?.openLinkedFile(link); publish() }
    fun parentDirectory() { active()?.parentDirectory(); publish() }
    fun reviewDirectory() { active()?.reviewDirectory(); publish() }
    fun closePreview() { active()?.closePreview(); publish() }
    fun refreshChanges() { active()?.refreshChanges(); publish() }
    fun openDiff(change: GitChange, side: String = "all") { active()?.openDiff(change, side); publish() }
    fun chooseReviewRange(value: String) { active()?.chooseReviewRange(value); publish() }
    fun openTaskChanges() { active()?.openTaskChanges(); publish() }
    fun toggleReviewViewed(path: String, side: String = "all") { active()?.toggleReviewViewed(path, side); publish() }
    fun navigateReviewFile(direction: Int) { active()?.navigateReviewFile(direction); publish() }
    fun relocateReviewComment(id: String) { active()?.relocateReviewComment(id); publish() }
    fun cancelReviewRelocation() { active()?.cancelReviewRelocation(); publish() }
    fun reanchorReviewComment(id: String, line: DiffLine?, body: String) { active()?.reanchorReviewComment(id, line, body); publish() }
    fun showReviewComments(value: Boolean) { active()?.showReviewComments(value); publish() }
    fun restoreQueuedReview(id: String) { active()?.restoreQueuedReview(id); publish(); flushRecovery() }
    fun refreshDiff() { active()?.refreshDiff(); publish() }
    fun addReviewComment(line: DiffLine?, body: String) { active()?.addReviewComment(line, body); publish() }
    fun editReviewComment(id: String, body: String) { active()?.editReviewComment(id, body); publish() }
    fun removeReviewComment(id: String) { active()?.removeReviewComment(id); publish() }
    fun sendReviewComments(ids: Set<String>? = null, steer: Boolean = false, newConversation: Boolean = false) { active()?.sendReviewComments(ids, steer, newConversation); publish() }
}
