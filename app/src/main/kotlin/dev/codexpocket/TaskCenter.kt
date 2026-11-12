package dev.codexpocket

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.codexpocket.core.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun syncTime(time: Long) = if (time == 0L) "尚未核实" else SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(time))

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun TaskCenter(s: PocketState, vm: PocketViewModel, request: (PendingRequest) -> Unit, close: () -> Unit) {
    var filter by remember { mutableStateOf("action") }
    val tasks = followedTasks(s).sortedByDescending { it.progress.syncedAt }
    ModalBottomSheet(onDismissRequest = close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text("任务中心", style = MaterialTheme.typography.titleLarge)
                Text("Orbit 发起或主动关注的会话 · 所有电脑；关闭标签后继续跟踪", color = Muted, fontSize = 12.sp)
                Text("未关注的远程会话不在此列表；审批请求会单独汇总。", color = Muted, fontSize = 12.sp)
            }
            item { TaskReminderHealth(vm) }
            item {
                androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf("action" to "需要处理", "running" to "执行中", "result" to "最近结束")) { (id, title) ->
                        val count = tasks.count { taskGroup(s, it) == id } + if (id == "action") s.pending.size else 0
                        FilterChip(selected = filter == id, onClick = { filter = id }, label = { Text("$title $count") })
                    }
                }
            }
            if (filter == "action") items(s.pending, key = { "request:${it.profileId}:${it.id}" }) { pending ->
                val tab = allTaskSessions(s).find { it.profileId == pending.profileId && it.thread.id == pending.params.text("threadId") }
                Surface(color = Panel, shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Text(tab?.thread?.title ?: "Agent 请求确认")
                        Text(listOfNotNull(tab?.project?.name, s.profiles.find { it.id == pending.profileId }?.name).joinToString(" · "), color = Muted, fontSize = 12.sp)
                        TextButton(onClick = { close(); request(pending) }, enabled = s.connections[pending.profileId]?.connected == true) { Text("处理请求") }
                        if (s.connections[pending.profileId]?.connected != true) Text("连接恢复后核对请求是否仍有效", color = Muted, fontSize = 12.sp)
                    }
                }
            }
            val visible = tasks.filter { taskGroup(s, it) == filter }
            items(visible, key = { it.key }) { tab ->
                val status = taskPresentation(s, tab)
                Surface(onClick = { vm.openSession(tab); close() }, color = Panel, shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(status.label, color = Mint, fontSize = 13.sp)
                        if (tab.progress.status == "completed" && !tab.progress.seen) Text("未读结果", color = Muted, fontSize = 12.sp)
                        Text(tab.thread.title, style = MaterialTheme.typography.titleSmall)
                        Text("${tab.project.name} · ${tab.profileName}", color = Muted, fontSize = 12.sp)
                        Text(status.detail, color = Muted, fontSize = 13.sp, maxLines = 3)
                        Text("手机连接：${if (s.connections[tab.profileId]?.connected == true) "已连接" else "未连接"} · 最近同步 ${syncTime(tab.progress.syncedAt)}", color = Muted, fontSize = 11.sp)
                    }
                }
            }
            if (visible.isEmpty() && (filter != "action" || s.pending.isEmpty())) item { Text("当前分类没有任务", modifier = Modifier.padding(vertical = 24.dp), color = Muted) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun TaskResultSheet(s: PocketState, vm: PocketViewModel, tab: SessionTab, close: () -> Unit) {
    val result = taskResult(s, tab)
    ModalBottomSheet(onDismissRequest = close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { Text("本轮结果", style = MaterialTheme.typography.titleLarge); Text(taskPresentation(s, tab).label, color = Muted) }
            tab.progress.error?.let { issue -> item { Text(issue, color = MaterialTheme.colorScheme.error) } }
            item {
                Text("结果摘要", style = MaterialTheme.typography.titleMedium)
                if (result != null) CompositionLocalProvider(
                    LocalOpenProjectFile provides { link -> vm.openLink(tab.profileId, link, s.root) },
                    LocalArtifactContext provides ArtifactContext(tab.profileId, s.root, vm),
                ) { ConversationBody(result.text, result.images, s.root, tab.key, vm.imageLoader) }
                else Text("本轮没有可展示的最终答复，请检查会话中的执行记录。", color = Muted)
            }
            item {
                Text("成果入口", style = MaterialTheme.typography.titleMedium)
                val links = result?.let { resultLinks(it.text) }.orEmpty()
                if (links.isEmpty()) Text("最终答复未提供可打开的成果链接。", color = Muted, fontSize = 13.sp)
                links.forEach { link -> TextButton(onClick = { close(); vm.openLink(tab.profileId, link.target, s.root) }, enabled = s.connected) { Text(link.label) } }
            }
            item {
                Text("验证情况", style = MaterialTheme.typography.titleMedium)
                Text("以下按命令名称筛选可能的验证记录，需检查输出；命令成功不等于全部需求已通过。", color = Muted, fontSize = 12.sp)
                val evidence = verificationEvidence(s.items, tab.progress.turnId)
                if (evidence.isEmpty()) Text("未提供可识别的验证命令证据；请核对结果摘要和执行记录。", color = Muted, fontSize = 13.sp)
                evidence.forEach { command ->
                    Text(command.command, fontSize = 13.sp)
                    Text("${command.status} · 退出码 ${command.exitCode ?: "未知"}", color = Muted, fontSize = 12.sp)
                    if (commandOutput(command).isNotBlank()) androidx.compose.foundation.text.selection.SelectionContainer { Text(commandOutput(command).takeLast(1600), fontSize = 12.sp) }
                }
                if (s.historyCursor != null) Text("历史尚未完整加载，以上证据可能不完整。", color = Muted, fontSize = 12.sp)
            }
            item {
                Text("本轮期间的变更", style = MaterialTheme.typography.titleMedium)
                Text(s.taskReview?.let { "本轮记录了 ${it.changes.size} 个文件变更${if (it.partial) "（记录不完整）" else ""}" } ?: s.taskReviewError ?: "本轮暂无变更记录", color = Muted, fontSize = 13.sp)
                Text("执行结束不代表验证通过；请核对答复中的测试结果与未完成事项。", color = Muted, fontSize = 12.sp)
                Row {
                    TextButton(onClick = { close(); vm.openTaskChanges() }, enabled = s.connected && s.taskReview != null) { Text("查看本轮变更") }
                    TextButton(onClick = { close(); vm.tab(1) }, enabled = s.connected) { Text("浏览成果文件") }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { if (s.draft.isBlank()) vm.draft("请继续修改：\n"); close() }) { Text("继续修改") }
                    if (tab.progress.status == "failed" && !tab.progress.dismissed) TextButton(onClick = { vm.dismissTaskReminder(tab); close() }) { Text("关闭本轮提醒") }
                }
            }
        }
    }
}
