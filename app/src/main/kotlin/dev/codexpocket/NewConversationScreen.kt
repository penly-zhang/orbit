package dev.codexpocket

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun NewConversationScreen(s: PocketState, vm: PocketViewModel, settings: () -> Unit) {
    var choosing by remember { mutableStateOf(false) }
    var sendAfterChoice by remember { mutableStateOf(false) }
    NewConversationContent(s, vm::newConversationDraft, {
        when {
            s.profiles.isEmpty() -> settings()
            s.project == null -> { sendAfterChoice = true; choosing = true }
            else -> vm.sendNewConversation()
        }
    }, { sendAfterChoice = false; choosing = true }, settings, vm::showProjects)
    if (choosing) ModalBottomSheet(onDismissRequest = { choosing = false }) {
        LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text("选择电脑和项目", style = MaterialTheme.typography.titleLarge) }
            items(s.hostProjects, key = { it.key }) { target ->
                ListItem(headlineContent = { Text(target.project.name) }, supportingContent = { Text(target.profileName) },
                    leadingContent = { Icon(Icons.Outlined.FolderOpen, null) },
                    trailingContent = { TextButton(onClick = { vm.selectHostProject(target); choosing = false; if (sendAfterChoice) vm.sendNewConversation(); sendAfterChoice = false }) { Text("选择") } })
            }
            if (s.hostProjects.isEmpty()) item {
                Text(if (s.connecting || s.reconnecting) "正在连接电脑，获取项目…" else "连接电脑后可选择项目。", color = Muted)
            }
            item { TextButton(onClick = { choosing = false; settings() }) { Text("设置连接") } }
        }
    }
}

@Composable internal fun NewConversationContent(
    s: PocketState, draft: (String) -> Unit, send: () -> Unit,
    choose: () -> Unit, settings: () -> Unit, projects: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(Icons.Outlined.Terminal, null, Modifier.size(36.dp), tint = Mint)
            Spacer(Modifier.height(16.dp))
            Text("开始一个新任务", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text("输入消息，开始会话", color = Muted)
            TextButton(onClick = projects) { Text("查看已有会话") }
        }
        TextButton(onClick = if (s.profiles.isEmpty()) settings else choose, enabled = !s.creatingConversation,
            modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Icon(Icons.Outlined.FolderOpen, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
            Text(if (s.project != null) "${s.project.name} · ${s.profile?.name.orEmpty()}" else "选择电脑和项目")
        }
        if (s.project != null && !s.connected) Text(
            if (s.connecting || s.reconnecting) "正在连接，连接后即可发送" else "电脑未连接，草稿已保留",
            color = Muted, modifier = Modifier.padding(horizontal = 20.dp))
        Surface(color = Panel, shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier.fillMaxWidth().imePadding().padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(s.newConversationDraft, draft, placeholder = { Text("输入消息…") },
                    modifier = Modifier.weight(1f), maxLines = 5, enabled = !s.creatingConversation)
                Spacer(Modifier.width(8.dp))
                FilledIconButton(onClick = send, enabled = s.newConversationDraft.isNotBlank() && !s.creatingConversation && (s.project == null || s.connected), modifier = Modifier.size(48.dp)) {
                    if (s.creatingConversation) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Outlined.ArrowUpward, "发送")
                }
            }
        }
    }
}
