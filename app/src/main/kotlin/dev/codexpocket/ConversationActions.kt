package dev.codexpocket

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.codexpocket.core.*
import java.util.UUID

internal val LocalExportConversation = staticCompositionLocalOf<(String) -> Unit> { {} }
internal fun shareText(context: Context, title: String, text: String) {
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, title).putExtra(Intent.EXTRA_TEXT, text), title))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun ConversationActions(s: PocketState, vm: PocketViewModel, searching: Boolean, toggleSearch: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var prompts by rememberSaveable { mutableStateOf(false) }
    var history by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<QuickPrompt?>(null) }
    var title by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    val prefs by vm.preferences.collectAsStateWithLifecycle()
    IconButton(onClick = { menu = true }, modifier = Modifier.size(40.dp)) { Icon(Icons.Outlined.MoreHoriz, "聊天工具") }
    if (menu) ModalBottomSheet(onDismissRequest = { menu = false }) {
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 28.dp)) {
            item { Text("聊天工具", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 18.dp)) }
            item { ActionRow(if (searching) "关闭消息搜索" else "搜索会话消息", Icons.Outlined.Search) { menu = false; toggleSearch() } }
            item { ActionRow("刷新消息", Icons.Outlined.Refresh, s.connected && !s.loading) { menu = false; vm.refreshThread() } }
            item { ActionRow("文件", Icons.Outlined.FolderOpen, s.connected) { menu = false; vm.tab(1) } }
            item { ActionRow("变更", Icons.Outlined.Difference, s.connected) { menu = false; vm.tab(2) } }
            item { HorizontalDivider(Modifier.padding(horizontal = 18.dp)) }
            item { ActionRow("常用提示词", Icons.Outlined.BookmarkBorder) { menu = false; prompts = true } }
            item { ActionRow("重用历史指令", Icons.Outlined.History) { menu = false; history = true } }
        }
    }
    if (prompts || history) ModalBottomSheet(onDismissRequest = { prompts = false; history = false }) {
        LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text(if (history) "重用历史指令" else "常用提示词", style = MaterialTheme.typography.titleLarge); Text("插入当前草稿，确认后发送。", fontSize = 12.sp, color = Muted) }
            if (history) {
                val previous = s.items.filter { it.kind == "userMessage" && it.text.isNotBlank() }.asReversed().distinctBy { it.text }
                if (previous.isEmpty()) item { Text("暂无已加载的历史指令", color = Muted) }
                items(previous, key = { it.id }) { item -> Surface(onClick = { vm.draft(s.draft + if (s.draft.isBlank()) item.text else "\n" + item.text); history = false }, color = Panel, shape = MaterialTheme.shapes.medium) { Text(item.text, maxLines = 3, modifier = Modifier.fillMaxWidth().padding(14.dp)) } }
            } else {
                items(prefs.prompts, key = { it.id }) { prompt ->
                    Surface(color = Panel, shape = MaterialTheme.shapes.medium) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { vm.draft(s.draft + if (s.draft.isBlank()) prompt.text else "\n" + prompt.text); prompts = false }, modifier = Modifier.weight(1f)) { Column(Modifier.fillMaxWidth()) { Text(prompt.title); Text(prompt.text, fontSize = 12.sp, color = Muted, maxLines = 2) } }
                            IconButton(onClick = { editing = prompt; title = prompt.title; text = prompt.text }) { Icon(Icons.Outlined.Edit, "编辑提示词") }
                        }
                    }
                }
                item { TextButton(onClick = { editing = QuickPrompt(UUID.randomUUID().toString(), "", ""); title = ""; text = "" }) { Text("添加提示词") } }
            }
        }
    }
    editing?.let { prompt -> AlertDialog(onDismissRequest = { editing = null }, title = { Text("编辑提示词") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(title, { title = it.take(60) }, label = { Text("名称") }, singleLine = true)
            OutlinedTextField(text, { text = it.take(10000) }, label = { Text("内容") }, minLines = 3, maxLines = 7)
            if (prefs.prompts.any { it.id == prompt.id }) TextButton(onClick = { vm.updatePreferences { it.copy(prompts = it.prompts.filterNot { it.id == prompt.id }) }; editing = null }) { Text("删除提示词") }
        }
    }, confirmButton = { TextButton(enabled = title.isNotBlank() && text.isNotBlank(), onClick = { vm.updatePreferences { it.copy(prompts = it.prompts.filterNot { it.id == prompt.id } + prompt.copy(title = title.trim(), text = text)) }; editing = null }) { Text("保存") } }, dismissButton = { TextButton(onClick = { editing = null }) { Text("取消") } }) }
}

@Composable internal fun WorkspaceMenuItems(s: PocketState, vm: PocketViewModel, dismiss: () -> Unit) {
    if (s.tab != 0) DropdownMenuItem(text = { Text("返回会话") }, leadingIcon = { Icon(Icons.Outlined.ChatBubbleOutline, null) }, onClick = { dismiss(); vm.closePreview(); vm.tab(0) })
    DropdownMenuItem(text = { Text("文件") }, leadingIcon = { Icon(Icons.Outlined.FolderOpen, null) }, enabled = s.connected && s.project != null,
        onClick = { dismiss(); vm.tab(1) })
    DropdownMenuItem(text = { Text("变更") }, leadingIcon = { Icon(Icons.Outlined.Difference, null) }, enabled = s.connected && s.project != null,
        onClick = { dismiss(); vm.tab(2) })
}

@Composable internal fun ConversationDetail(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        androidx.compose.foundation.text.selection.SelectionContainer { Text(value.ifBlank { "—" }, fontSize = 15.sp) }
    }
}

@Composable internal fun ConversationUsageInfo(s: PocketState) {
    val usage = s.usage[s.thread?.id]
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ConversationDetail("上下文已使用", usage?.contextPercent?.let { "$it%" } ?: "暂无数据")
        if (usage != null) {
            s.usageUpdatedAt[s.thread?.id]?.let { Text("同步于 ${java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(it))}", color = Muted, fontSize = 12.sp) }
            if (!s.connected) Text("上次同步的用量，重新连接后更新", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            ConversationDetail("本轮用量", "${compactTokenCount(usage.last)} tokens")
            ConversationDetail("累计用量", "${compactTokenCount(usage.total)} tokens")
            usage.contextWindow?.let { ConversationDetail("上下文窗口", "${compactTokenCount(it)} tokens") }
        }
        val reading = s.thread?.id in s.usageLoading
        val problem = s.usageErrors[s.thread?.id]
        val message = when {
            reading -> "正在读取电脑保存的用量…"
            problem != null -> problem
            usage == null && !s.connected -> "连接电脑后可读取用量"
            usage == null && s.items.none { it.kind == "userMessage" } -> "发送消息后生成用量"
            usage == null -> "此会话暂无可用的用量记录"
            else -> null
        }
        if (message != null) Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
    }
}

internal fun conversationMaintenanceReason(s: PocketState): String? = when {
    !s.connected -> "连接电脑后可用"
    s.activeTurn != null || s.sending -> "任务进行中，结束后可用"
    !s.threadReady -> "会话接续完成后可用"
    s.loading -> "正在同步，请稍后"
    s.items.none { it.kind == "userMessage" } -> "发送首条消息后可用"
    else -> null
}

