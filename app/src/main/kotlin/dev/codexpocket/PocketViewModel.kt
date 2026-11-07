package dev.codexpocket

import android.app.Application
import android.net.Uri
import kotlinx.coroutines.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.codexpocket.core.*
import kotlinx.serialization.json.*

data class HostConnectionStatus(val connected: Boolean = false, val connecting: Boolean = false, val reconnecting: Boolean = false, val attempt: Int = 0, val needsCredentials: Boolean = false, val needsTrust: Boolean = false, val error: String? = null) {
    val label: String get() = when { needsTrust -> "待确认指纹"; needsCredentials -> "需填写登录信息"; reconnecting -> "正在重新连接…"; connecting -> "正在建立连接…"; connected -> "已连接"; error != null -> "连接失败"; else -> "已断开" }
}
data class HostProject(val profileId: String, val profileName: String, val project: ProjectInfo) { val key: String get() = "$profileId:${project.id}" }
internal fun HostProfile.connectionLabel(profiles: List<HostProfile>): String = if (profiles.count { it.name == name } > 1) "$name · $host" else name

data class ArchiveUndo(val thread: ThreadInfo, val restored: Boolean)
data class SendCompletion(val profileId: String, val threadId: String, val clearFocus: Boolean = true)
data class PendingRequest(val id: JsonElement, val kind: RequestKind, val params: JsonObject, val profileId: String = "")
@kotlinx.serialization.Serializable data class DraftImage(val id: String, val path: String, val name: String, val bytes: Int)
internal data class ImageImportBudget(val count: Int, val bytes: Int)
data class SessionTab(val thread: ThreadInfo, val project: ProjectInfo, val number: Int, val name: String = "", val running: Boolean = false, val unread: Boolean = false, val profileId: String = "", val profileName: String = "", val hasDraft: Boolean = false, val displayNumber: Int = number) {
    val key: String get() = "$profileId:${thread.id}"
    val label: String get() = name.ifBlank { displayNumber.toString() }
}
data class PocketState(
    val browsingProjects: Boolean = false, val newConversationDraft: String = "", val creatingConversation: Boolean = false,
    val profiles: List<HostProfile> = emptyList(), val profile: HostProfile? = null, val connecting: Boolean = false, val connected: Boolean = false,
    val reconnecting: Boolean = false, val reconnectAttempt: Int = 0,
    val needsCredentials: Boolean = false,
    val connections: Map<String, HostConnectionStatus> = emptyMap(), val hostProjects: List<HostProject> = emptyList(), val trustProfile: HostProfile? = null,
    val connectionIssue: String? = null, val usage: Map<String, TokenUsage> = emptyMap(),
    val usageUpdatedAt: Map<String, Long> = emptyMap(),
    val usageLoading: Set<String> = emptySet(), val usageErrors: Map<String, String> = emptyMap(),
    val error: String? = null, val trust: HostIdentity? = null, val loading: Boolean = false,
    val projects: List<ProjectInfo> = emptyList(), val project: ProjectInfo? = null, val root: String = "",
    val tab: Int = 0, val threads: List<ThreadInfo> = emptyList(), val threadCursor: String? = null,
    val threadsLoading: Boolean = false, val threadsCached: Boolean = false, val threadsError: String? = null,
    val sessionTabs: List<SessionTab> = emptyList(),
    val navigationThreads: Map<String, List<ThreadInfo>> = emptyMap(),
    val pinnedThreads: Map<String, List<String>> = emptyMap(),
    val archiveUndo: ArchiveUndo? = null,
    val showArchived: Boolean = false, val archivingThread: String? = null,
    val threadSyncError: String? = null, val thread: ThreadInfo? = null, val items: List<ChatItem> = emptyList(), val historyCursor: String? = null,
    val activeTurn: String? = null, val activeTurnStartedAtMs: Long? = null, val pending: List<PendingRequest> = emptyList(), val draft: String = "", val sending: Boolean = false, val attaching: Boolean = false, val threadReady: Boolean = false,
    val draftContext: List<ContextInput> = emptyList(), val contextLoading: Boolean = false, val contextError: String? = null,
    val draftImages: List<DraftImage> = emptyList(), val imagesLoading: Boolean = false, val imageError: String? = null,
    val queuedMessages: List<QueuedMessage> = emptyList(), val queuePaused: Boolean = false,
    val filesLoading: Boolean = false, val filesError: String? = null, val filesPath: String = "",
    val directory: String = "", val files: List<FileEntry> = emptyList(), val preview: FilePreview? = null,
    val changes: List<GitChange> = emptyList(), val diff: String? = null, val diffPath: String = "", val cached: Boolean = false,
    val changesLoading: Boolean = false, val changesError: String? = null, val changesRoot: String? = null,
    val reviewOverview: ReviewOverview? = null, val reviewRange: String = "workspace", val diffSide: String = "all", val diffFingerprint: String = "",
    val reviewViewed: Map<String, String> = emptyMap(), val reviewSendIssue: String? = null, val reanchorComment: String? = null,
    val showReviewComments: Boolean = false,
    val taskReview: TaskReviewReport? = null, val taskReviewLoading: Boolean = false, val taskReviewError: String? = null,
    val collaborationModes: List<String> = emptyList(),
    val settings: ConversationSettings? = null, val models: List<ModelOption> = emptyList(), val permissions: List<PermissionOption> = emptyList(),
    val settingsApplying: Boolean = false, val choicesLoading: Boolean = false, val choicesError: String? = null,
    val reviewComments: List<ReviewComment> = emptyList(), val sendingComments: Boolean = false,
)

class PocketViewModel(application: Application) : AndroidViewModel(application) {
    internal val runtime = PocketRuntime.get(application)
    private val controller = runtime.controller
    val preferences = runtime.preferences
    fun updatePreferences(change: (AppPreferences) -> AppPreferences) = runtime.update(change)
    fun appVisible(value: Boolean) = runtime.foreground(value)
    fun openSessionKey(key: String) { state.value.sessionTabs.find { it.key == key }?.let(controller::openSession) }
    val state get() = controller.state
    val sendCompleted get() = controller.sendCompleted
    internal fun imageLoaderFor(id: String) = controller.imageLoaderFor(id)
    internal val imageLoader get() = controller.imageLoader
    var pendingExport: String? = null
    fun exportConversation(uri: Uri) {
        val text = pendingExport ?: return
        pendingExport = null
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching {
                getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(text) } ?: error("无法写入此位置")
            } }
            android.widget.Toast.makeText(getApplication(), if (result.isSuccess) "已导出" else "导出失败：${result.exceptionOrNull()?.message}", android.widget.Toast.LENGTH_LONG).show()
        }
    }
    fun importImages(profileId: String, tabNumber: Int, uris: List<Uri>) {
        if (uris.isEmpty()) return
        val budget = controller.beginImageImport(profileId, tabNumber) ?: return
        viewModelScope.launch {
            val prepared = mutableListOf<DraftImage>()
            var message: String? = if (uris.size > budget.count) "最多可再添加 ${budget.count} 张图片，超出部分未添加" else null
            try {
                var remainingBytes = budget.bytes
                for (uri in uris.take(budget.count)) {
                    try {
                        val image = prepareDraftImage(getApplication<Application>(), uri)
                        if (image.bytes > remainingBytes) { java.io.File(image.path).delete(); message = "图片总大小超过 8 MB，部分图片未添加" }
                        else { prepared += image; remainingBytes -= image.bytes }
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (e: Exception) { message = e.message ?: "部分图片读取失败，请重新选择" }
                }
                if (!controller.finishImageImport(profileId, tabNumber, prepared, message)) withContext(Dispatchers.IO) { prepared.forEach { java.io.File(it.path).delete() } }
            } catch (cancel: CancellationException) {
                controller.finishImageImport(profileId, tabNumber, emptyList(), "图片读取已取消，请重新选择")
                withContext(NonCancellable + Dispatchers.IO) { prepared.forEach { java.io.File(it.path).delete() } }
                throw cancel
            }
        }
    }
    fun addContext(profile: String, number: Int, input: ContextInput): Boolean = controller.finishContextImport(profile, number, listOf(input))
    fun removeContext(profile: String, number: Int, id: String) = controller.removeContext(profile, number, id)
    internal fun contextRepository(profile: String) = controller.artifactRepository(profile)
    fun importDocuments(profile: String, number: Int, uris: List<Uri>) {
        if (uris.isEmpty() || !controller.beginContextImport(profile, number)) return
        viewModelScope.launch {
            val files = mutableListOf<ContextInput>(); var issue: String? = if (uris.size > MAX_CONTEXT_ITEMS) "每条消息最多引用 8 项上下文" else null
            try {
                for (uri in uris.take(MAX_CONTEXT_ITEMS)) {
                    try {
                        val file = prepareContextFile(getApplication(), uri)
                        if (files.sumOf { it.bytes } + file.bytes > MAX_ATTACHMENT_TOTAL_BYTES) { java.io.File(file.localPath!!).delete(); issue = "附件总大小超过 12 MB，部分文件未添加" }
                        else files += file
                    } catch (c: CancellationException) { throw c }
                    catch (e: Exception) { issue = e.message ?: "部分文件读取失败" }
                }
                if (!controller.finishContextImport(profile, number, files, issue)) withContext(Dispatchers.IO) { files.mapNotNull { it.localPath }.forEach { java.io.File(it).delete() } }
            } catch (c: CancellationException) {
                controller.finishContextImport(profile, number, emptyList(), "附件读取已取消")
                withContext(NonCancellable + Dispatchers.IO) { files.mapNotNull { it.localPath }.forEach { java.io.File(it).delete() } }; throw c
            }
        }
    }
    fun removeDraftImage(profileId: String, tabNumber: Int, imageId: String) { controller.removeDraftImage(profileId, tabNumber, imageId) }
    fun showProjects() = controller.showProjects()
    fun returnToConversation() = controller.returnToConversation()
    fun newConversation() = controller.newConversation()
    fun newConversationDraft(text: String) = controller.newConversationDraft(text)
    fun sendNewConversation() = controller.sendNewConversation()
    fun home() { controller.home() }
    fun refreshProjects() { controller.refreshProjects() }
    fun selectProject(project: ProjectInfo) { controller.selectProject(project) }
    fun changeRoot(root: String) { controller.changeRoot(root) }
    fun tab(index: Int) { controller.tab(index) }
    fun loadThreads(more: Boolean = false) { controller.loadThreads(more) }
    fun showArchived(value: Boolean) { controller.showArchived(value) }
    fun archiveThread(thread: ThreadInfo) { controller.archiveThread(thread) }
    fun undoArchive() = controller.undoArchive()
    fun dismissArchiveUndo() = controller.dismissArchiveUndo()
    fun openThread(thread: ThreadInfo, freshlyCreated: Boolean = false) { controller.openThread(thread, freshlyCreated) }
    fun closeThread() { controller.closeThread() }
    fun refreshThread() { controller.refreshThread() }
    fun refreshUsage() { controller.refreshUsage() }
    fun moreHistory() { controller.moreHistory() }
    fun newThread() { controller.newThread() }
    fun draft(text: String) { controller.draft(text) }
    fun refreshChoices() { controller.refreshChoices() }
    fun pinThread(projectId: String, threadId: String, pinned: Boolean) { controller.pinThread(projectId, threadId, pinned) }
    internal fun isThreadPinned(profileId: String, projectId: String, threadId: String) = controller.isThreadPinned(profileId, projectId, threadId)
    internal fun conversationState(profileId: String, threadId: String) = controller.conversationState(profileId, threadId)
    fun openProjectThread(profileId: String, project: ProjectInfo, thread: ThreadInfo) = controller.openProjectThread(profileId, project, thread)
    fun openProjectThread(project: ProjectInfo, thread: ThreadInfo) = controller.openProjectThread(project, thread)
    fun archiveThread(profileId: String, thread: ThreadInfo, restore: Boolean) = controller.archiveThread(profileId, thread, restore)
    fun pinThread(profileId: String, projectId: String, threadId: String, pinned: Boolean) = controller.pinThread(profileId, projectId, threadId, pinned)
    fun forkThread(profileId: String, threadId: String) = controller.forkThread(profileId, threadId)
    fun compactThread(profileId: String, threadId: String) = controller.compactThread(profileId, threadId)
    fun refreshUsage(profileId: String, threadId: String) = controller.refreshUsage(profileId, threadId)
    fun renameThread(profileId: String, threadId: String, name: String) { controller.renameThread(profileId, threadId, name) }
    fun renameThread(name: String) { controller.renameThread(name) }
    fun forkThread() { controller.forkThread() }
    fun compactThread() { controller.compactThread() }
    fun chooseCollaboration(mode: String) { controller.chooseCollaboration(mode) }
    fun chooseModel(option: ModelOption) { controller.chooseModel(option) }
    fun chooseEffort(option: EffortOption) { controller.chooseEffort(option) }
    fun choosePermission(option: PermissionOption) { controller.choosePermission(option) }
    fun send() { controller.send() }
    fun editQueuedMessage(id: String, text: String) { controller.editQueuedMessage(id, text) }
    fun cancelQueuedMessage(id: String) { controller.cancelQueuedMessage(id) }
    fun steerQueuedMessage(id: String) { controller.steerQueuedMessage(id) }
    fun resumeQueuedMessages() { controller.resumeQueuedMessages() }
    fun pauseQueuedMessages() { controller.pauseQueuedMessages() }
    fun stop() { controller.stop() }
    fun review(target: ReviewTarget) { controller.review(target) }
    fun loadFiles(path: String) { controller.loadFiles(path) }
    fun openFile(entry: FileEntry) { controller.openFile(entry) }
    internal val webPreview = kotlinx.coroutines.flow.MutableStateFlow<WebPreview?>(null)
    private var webJob: Job? = null
    private val documentPermits = kotlinx.coroutines.sync.Semaphore(2)
    internal suspend fun readDocument(profile: String, path: String): FilePreview {
        documentPermits.acquire()
        try { return withContext(Dispatchers.Main.immediate) { controller.artifactRepository(profile) }.file(path) }
        finally { documentPermits.release() }
    }
    internal suspend fun readNetworkImage(profile: String, source: String): ByteArray {
        if (source.startsWith("https://") && !isLoopbackLink(source) || source.startsWith("data:")) return readChatImage(source, "/") { error("图片地址无效") }
        return withContext(Dispatchers.Main.immediate) { controller.artifactRepository(profile) }.image(source, "/")
    }
    internal suspend fun readWebResource(profile: String, root: String, path: String): FilePreview =
        withContext(Dispatchers.Main.immediate) { controller.artifactRepository(profile) }.artifactResource(root, path)
    fun openLink(profile: String, link: String, cwd: String) {
        if (isWebLink(link)) {
            closeWebPreview()
            webPreview.value = WebPreview(link, profile, loading = true)
            webJob = viewModelScope.launch {
                var tunnel: LoopbackTunnel? = null
                try {
                    val url = if (isLoopbackLink(link)) {
                        require(java.net.URI(link).scheme == "http") { "电脑本地 HTTPS 预览暂不支持，请使用 HTTP 开发预览地址" }
                        tunnel = controller.artifactRepository(profile).webTunnel(loopbackPort(link))
                        tunneledUrl(link, tunnel.port)
                    } else link
                    ensureActive()
                    val document = if (java.net.URI(link).path.orEmpty().substringAfterLast('.').lowercase() in setOf("md", "markdown") && (url.startsWith("https://") || isLoopbackLink(url))) withContext(Dispatchers.IO) { downloadWebResource(url, 2 * 1024 * 1024).toString(Charsets.UTF_8) } else null
                    webPreview.value = WebPreview(link, profile, url = url, tunnel = tunnel, markdown = document)
                } catch (c: CancellationException) { tunnel?.close(); throw c }
                catch (e: Exception) { tunnel?.close(); webPreview.value = WebPreview(link, profile, error = e.message ?: "无法打开链接") }
            }
        } else if (isRemoteFileLink(link)) controller.openLinkedFileAt(profile, link, cwd)
    }
    fun closeWebPreview() { webJob?.cancel(); webJob = null; webPreview.value?.tunnel?.close(); webPreview.value = null }
    fun openLinkedFile(link: String) { val s = state.value; openLink(s.profile?.id ?: return, link, s.root) }
    fun parentDirectory() { controller.parentDirectory() }
    fun reviewDirectory() { controller.reviewDirectory() }
    fun closePreview() { controller.closePreview() }
    fun refreshChanges() { controller.refreshChanges() }
    fun openDiff(change: GitChange, side: String = "all") { controller.openDiff(change, side) }
    fun chooseReviewRange(value: String) { controller.chooseReviewRange(value) }
    fun openTaskChanges() { controller.openTaskChanges() }
    fun toggleReviewViewed(path: String, side: String = "all") { controller.toggleReviewViewed(path, side) }
    fun navigateReviewFile(direction: Int) { controller.navigateReviewFile(direction) }
    fun relocateReviewComment(id: String) { controller.relocateReviewComment(id) }
    fun cancelReviewRelocation() { controller.cancelReviewRelocation() }
    fun reanchorReviewComment(id: String, line: DiffLine?, body: String) { controller.reanchorReviewComment(id, line, body) }
    fun showReviewComments(value: Boolean) { controller.showReviewComments(value) }
    fun restoreQueuedReview(id: String) { controller.restoreQueuedReview(id) }
    fun refreshDiff() { controller.refreshDiff() }
    fun addReviewComment(line: DiffLine?, body: String) { controller.addReviewComment(line, body) }
    fun editReviewComment(id: String, body: String) { controller.editReviewComment(id, body) }
    fun removeReviewComment(id: String) { controller.removeReviewComment(id) }
    fun sendReviewComments(ids: Set<String>? = null, steer: Boolean = false, newConversation: Boolean = false) { controller.sendReviewComments(ids, steer, newConversation) }
    fun selectHost(id: String) { controller.selectHost(id) }
    fun selectHostProject(project: HostProject) { controller.selectHostProject(project) }
    fun openSession(tab: SessionTab) { controller.openSession(tab) }
    fun saveAndConnect(profile: HostProfile, credentials: Credentials, remember: Boolean) { controller.saveAndConnect(profile, credentials, remember) }
    fun moveProfile(id: String, direction: Int) { controller.moveProfile(id, direction) }
    fun deleteProfile(profile: HostProfile) { controller.deleteProfile(profile) }
    fun disconnect() { controller.disconnect() }
    fun acceptHostKey() { controller.acceptHostKey() }
    fun dismissTrust() { controller.dismissTrust() }
    fun closeConversation(key: String) { controller.closeConversation(key) }
    fun renameSessionTab(key: String, name: String) { controller.renameSessionTab(key, name) }
    fun switchSession(direction: Int) { controller.switchSession(direction) }
    fun decide(request: PendingRequest, accept: Boolean) { controller.decide(request, accept) }
    fun answerMcp(request: PendingRequest, action: String, content: JsonObject?) = controller.answerMcp(request, action, content)
    fun answerQuestions(request: PendingRequest, answers: Map<String, String>) { controller.answerQuestions(request, answers) }
    fun clearError() { controller.clearError() }
    fun storedCredentials(id: String) = controller.storedCredentials(id)
    fun reconnect(profile: HostProfile) = controller.reconnect(profile)
    fun setConnectionEnabled(profile: HostProfile, enabled: Boolean) = controller.setConnectionEnabled(profile, enabled)
    override fun onCleared() { closeWebPreview(); super.onCleared() }
}
fun mergeItems(older: List<ChatItem>, newer: List<ChatItem>): List<ChatItem> = LinkedHashMap<String, ChatItem>().apply { older.forEach { put(it.id, it) }; newer.forEach { put(it.id, it) } }.values.toList()
