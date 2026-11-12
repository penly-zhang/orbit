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
import dev.codexpocket.core.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun NewConversationScreen(s: PocketState, vm: PocketViewModel, settings: () -> Unit) {
    var choosing by remember { mutableStateOf(false) }
    val imagePicker = LocalImagePicker.current
    val documentPicker = LocalDocumentPicker.current
    val context = if (s.profile != null && s.project != null) composerContextActions(vm, s,
        SessionTab(ThreadInfo("", "", s.root, engineId = s.newConversationEngine), s.project, 0, profileId = s.profile.id), documentPicker).copy(
            addDocument = { documentPicker(NEW_TASK_DRAFT, 0) },
            add = { vm.addContext(NEW_TASK_DRAFT, 0, it) }, remove = { vm.removeContext(NEW_TASK_DRAFT, 0, it.id) }) else null
    NewConversationContent(s, vm::newConversationDraft, {
        when {
            s.profiles.isEmpty() -> settings()
            s.project == null -> { choosing = true }
            else -> vm.sendNewConversation()
        }
    }, { choosing = true }, settings, vm::showProjects, vm::chooseNewConversationEngine,
        composerActions = ComposerActions(
            draft = vm::newConversationDraft, send = {}, stop = {},
            addImage = { imagePicker(NEW_TASK_DRAFT, 0) },
            removeImage = { vm.removeDraftImage(NEW_TASK_DRAFT, 0, it.id) },
            model = {}, effort = {}, permission = {}, mode = {}, refresh = {},
        ), contextActions = context ?: newTaskLocalContext(vm, documentPicker))
    if (choosing) ModalBottomSheet(onDismissRequest = { choosing = false }) {
        LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text("选择电脑和项目", style = MaterialTheme.typography.titleLarge) }
            items(s.hostProjects, key = { it.key }) { target ->
                ListItem(headlineContent = { Text(target.project.name) }, supportingContent = { Text(target.profileName) },
                    leadingContent = { Icon(Icons.Outlined.FolderOpen, null) },
                    trailingContent = { TextButton(onClick = { vm.selectHostProject(target); choosing = false }) { Text("选择") } })
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
    chooseEngine: (String) -> Unit = {},
    composerActions: ComposerActions? = null,
    contextActions: ContextActions? = null,
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
        if (s.project != null && s.connected) {
            Row(Modifier.align(Alignment.CenterHorizontally), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                s.availableEngines.forEach { engine ->
                    FilterChip(selected = s.newConversationEngine == engine, onClick = { chooseEngine(engine) },
                        enabled = !s.creatingConversation, label = { Text(agentLabel(engine)) })
                }
            }
            Text("会话创建后 Agent 不可更换", color = Muted, modifier = Modifier.align(Alignment.CenterHorizontally))
            s.engineIssues.forEach { (engine, issue) ->
                Text("${agentLabel(engine)} 暂不可用：$issue\n启动电脑上的服务后，可在连接设置中重新连接以检测。",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp), maxLines = 4)
            }
        }
        if (s.project != null && !s.connected) Text(
            if (s.connecting || s.reconnecting) "正在连接，连接后即可发送" else "电脑未连接，草稿已保留",
            color = Muted, modifier = Modifier.padding(horizontal = 20.dp))
        val actions = composerActions ?: ComposerActions(draft, send, {}, {}, {}, {}, {}, {}, {}, {})
        MessageComposer(newConversationComposerState(s), actions.copy(draft = draft, send = send),
            kotlinx.coroutines.flow.emptyFlow(), contextActions, newConversation = true)
    }
}

/** Do not inherit a previous conversation's runtime, permissions or model into a new draft. */
internal fun newConversationComposerState(s: PocketState) = s.copy(
    thread = null, draft = s.newConversationDraft, draftImages = s.newImages, draftContext = s.newContext,
    imagesLoading = s.newImagesLoading, contextLoading = s.newContextLoading,
    imageError = s.newAttachmentError, contextError = null, sending = s.creatingConversation,
    activeTurn = null, threadReady = false, queuedMessages = emptyList(),
    settings = null, models = emptyList(), permissions = emptyList(), settingsApplying = false, sendingComments = false,
)
