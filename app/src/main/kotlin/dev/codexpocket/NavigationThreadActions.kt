package dev.codexpocket

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.codexpocket.core.*

internal data class ThreadActionTarget(val profileId: String, val project: ProjectInfo, val thread: ThreadInfo, val archived: Boolean = false)

/** Every command uses the captured host and thread, even if selection changes behind the sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun NavigationThreadActions(s: PocketState, vm: PocketViewModel, target: ThreadActionTarget, dismiss: () -> Unit) {
    val context = LocalContext.current
    val export = LocalExportConversation.current
    val snapshot = vm.conversationState(target.profileId, target.thread.id)
    val tab = allTaskSessions(s).find { it.profileId == target.profileId && it.thread.id == target.thread.id }
    val thread = snapshot?.thread ?: tab?.thread ?: target.thread
    val connected = s.connections[target.profileId]?.connected == true
    val profile = s.profiles.find { it.id == target.profileId }
    val pinned = vm.isThreadPinned(target.profileId, target.project.id, thread.id)
    val reason = if (snapshot == null) "打开会话并加载消息后可用" else conversationMaintenanceReason(snapshot)
    val loaded = snapshot != null && snapshot.items.isNotEmpty()
    val running = tab?.running == true || thread.status in setOf("active", "inProgress")
    val archiving = s.profile?.id == target.profileId && s.archivingThread != null
    var page by remember(target) { mutableStateOf("main") }
    var operation by remember(target) { mutableStateOf<String?>(null) }
    var name by remember(target) { mutableStateOf(thread.title) }
    var closing by remember { mutableStateOf<SessionTab?>(null) }
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(contentPadding = PaddingValues(bottom = 28.dp)) {
            item {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Text(thread.title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                    Text("${target.project.name} · ${profile?.name.orEmpty()}", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (page == "details") {
                item { TextButton(onClick = { page = "main" }) { Text("返回会话操作") } }
                item { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("会话详情", style = MaterialTheme.typography.titleMedium)
                    ConversationDetail("工作目录", thread.cwd.ifBlank { target.project.roots.firstOrNull().orEmpty() })
                    ConversationDetail("连接", if (connected) "已连接" else "已断开")
                    ConversationDetail("会话 ID", thread.id)
                    if (snapshot != null) ConversationUsageInfo(snapshot)
                    else Text("打开会话后可查看已同步的用量和消息。", color = Muted)
                    TextButton(enabled = connected && snapshot != null, onClick = { vm.refreshUsage(target.profileId, thread.id) }) { Text("刷新用量") }
                } }
            } else {
                item { ActionRow("打开会话", Icons.Outlined.ChatBubbleOutline, !target.archived && (connected || tab != null)) {
                    dismiss(); vm.openProjectThread(target.profileId, target.project, thread)
                } }
                item { ActionRow(if (pinned) "取消置顶" else "置顶会话", Icons.Outlined.PushPin) {
                    vm.pinThread(target.profileId, target.project.id, thread.id, !pinned); dismiss()
                } }
                if (tab != null) item { ActionRow(if (tab.tracked) "取消关注任务" else "关注任务", Icons.Outlined.PendingActions, supporting = "关注后关闭标签仍会跟踪") { vm.trackTask(tab, !tab.tracked); dismiss() } }
                item { ActionRow("重命名会话", Icons.Outlined.Edit, connected, "同步到电脑") { name = thread.title; operation = "rename" } }
                if (tab != null && tab.tabOpen) item { ActionRow("标签名称", Icons.Outlined.Label, supporting = "仅修改手机上的标签") { name = tab.name; operation = "label" } }
                if (tab != null && tab.tabOpen) item { ActionRow("关闭会话标签", Icons.Outlined.Close, supporting = "电脑上的会话与任务保留") {
                    if (tab.hasDraft) closing = tab else { vm.closeConversation(tab.key); dismiss() }
                } }
                item { ActionRow(if (target.archived) "恢复会话" else "归档会话", if (target.archived) Icons.Outlined.Unarchive else Icons.Outlined.Archive,
                    connected && !archiving && (target.archived || (!running && tab?.hasDraft != true)),
                    if (!target.archived && (running || tab?.hasDraft == true)) "任务结束并处理草稿后可归档" else null) {
                    vm.archiveThread(target.profileId, thread, target.archived); dismiss()
                } }
                item { HorizontalDivider(Modifier.padding(horizontal = 18.dp)) }
                item { ActionRow("会话详情", Icons.Outlined.Info) { page = "details"; if (snapshot != null) vm.refreshUsage(target.profileId, thread.id) } }
                item { ActionRow("分享会话", Icons.Outlined.Share, loaded, if (loaded && snapshot?.historyCursor == null && snapshot?.cached == false) "本会话全部已同步消息" else if (loaded) "仅包含已加载的消息；可在消息搜索中加载完整历史" else "打开会话以加载消息") {
                    shareText(context, thread.title, conversationMarkdown(thread.title, snapshot!!.items)); dismiss()
                } }
                item { ActionRow("导出 Markdown", Icons.Outlined.FileDownload, loaded, if (snapshot?.historyCursor == null && snapshot?.cached == false) "本会话全部已同步消息" else "仅包含已加载的消息；可在消息搜索中加载完整历史") {
                    export(conversationMarkdown(thread.title, snapshot!!.items)); dismiss()
                } }
                item { HorizontalDivider(Modifier.padding(horizontal = 18.dp)) }
                item { ActionRow("从历史创建分支", Icons.Outlined.CallSplit, reason == null && !target.archived, reason) { operation = "fork" } }
                item { ActionRow("压缩模型上下文", Icons.Outlined.Compress, reason == null && !target.archived, reason) { operation = "compact" } }
            }
        }
    }
    operation?.let { action -> AlertDialog(onDismissRequest = { operation = null },
        title = { Text(when (action) { "label" -> "标签名称"; "rename" -> "重命名会话"; "fork" -> "创建会话分支？"; else -> "压缩模型上下文？" }) },
        text = { if (action == "rename" || action == "label") OutlinedTextField(name, { name = it.take(if (action == "label") 24 else 120) },
                label = { Text(if (action == "label") "标签名称" else "会话名称") }, singleLine = true,
                supportingText = { if (action == "label") Text("留空恢复数字编号。") })
            else Text(if (action == "fork") "从「${thread.title}」已保存的历史创建新会话，保留原会话和草稿。项目文件不会回滚。" else "整理「${thread.title}」的模型上下文，可能需要等待。请勿重复提交。") },
        confirmButton = { TextButton(enabled = if (action == "label") tab != null else connected && (if (action == "rename") name.isNotBlank() else reason == null), onClick = {
            when (action) {
                "label" -> tab?.let { vm.renameSessionTab(it.key, name) }
                "rename" -> vm.renameThread(target.profileId, thread.id, name)
                "fork" -> vm.forkThread(target.profileId, thread.id)
                else -> vm.compactThread(target.profileId, thread.id)
            }
            operation = null; dismiss()
        }) { Text(if (action in setOf("rename", "label")) "保存" else "确认") } },
        dismissButton = { TextButton(onClick = { operation = null }) { Text("取消") } }) }
    CloseSessionDialog(closing, { closing = null }) { vm.closeConversation(it.key); closing = null; dismiss() }
}
