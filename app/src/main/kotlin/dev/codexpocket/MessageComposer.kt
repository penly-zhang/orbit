package dev.codexpocket

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.codexpocket.core.*
import kotlinx.coroutines.flow.Flow

/** Same composer for real conversations and device UI checks; callbacks never change settings optimistically. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable internal fun MessageComposer(s: PocketState, actions: ComposerActions, completions: Flow<SendCompletion>, contextActions: ContextActions? = null) {
    var contextPicker by remember(s.profile?.id, s.thread?.id) { mutableStateOf(false) }
    var toolPicker by remember(s.profile?.id, s.thread?.id) { mutableStateOf(false) }
    LaunchedEffect(s.draft) { if (contextActions != null && Regex("(^|\\s)@$" ).containsMatchIn(s.draft)) contextPicker = true }
    var showUsage by rememberSaveable(s.profile?.id, s.thread?.id) { mutableStateOf(false) }
    var inputFocused by remember(s.profile?.id, s.thread?.id) { mutableStateOf(false) }
    var permissionOpen by remember(s.profile?.id, s.thread?.id) { mutableStateOf(false) }
    var modelOpen by remember(s.profile?.id, s.thread?.id) { mutableStateOf(false) }
    val imeVisible = WindowInsets.isImeVisible
    var imeWasVisible by remember { mutableStateOf(false) }
    val contextPercent = s.usage[s.thread?.id]?.contextPercent
    val destination = listOfNotNull(s.project?.name?.takeIf { it.isNotBlank() }, s.profile?.name?.takeIf { it.isNotBlank() }).joinToString(" · ").ifBlank { "选择项目和电脑" }
    val connectionHint = when { s.reconnecting -> "正在重连…"; s.connecting -> "正在连接…"; !s.connected -> "已断开"; else -> null }
    val locationLabel = listOfNotNull("在 $destination 上工作", connectionHint).joinToString(" · ")
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(imeVisible) {
        // Android can hide the keyboard while leaving the editor focused.
        if (imeWasVisible && !imeVisible) focus.clearFocus(force = true)
        imeWasVisible = imeVisible
    }
    LaunchedEffect(completions, s.profile?.id, s.thread?.id) {
        completions.collect { event ->
            if (event.profileId == s.profile?.id && event.threadId == s.thread?.id && event.clearFocus) {
                focus.clearFocus(force = true)
                keyboard?.hide()
            }
        }
    }
    val model = s.models.find { it.id == s.settings?.model }
    val supportsImages = model?.inputModalities?.contains("image") != false
    val showStop = s.activeTurn != null && s.draft.isBlank() && s.draftImages.isEmpty() && s.draftContext.isEmpty() && !s.contextLoading && !s.imagesLoading
    val expanded = s.draftContext.isNotEmpty() || s.contextLoading || s.contextError != null || contextPicker || toolPicker || inputFocused || imeVisible || s.draft.isNotEmpty() || s.draftImages.isNotEmpty() || s.imagesLoading || s.imageError != null || showUsage || permissionOpen || modelOpen
    Surface(color = Panel, shape = RoundedCornerShape(28.dp), shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth().imePadding().padding(horizontal = 10.dp, vertical = 8.dp)) {
        Column(Modifier.animateContentSize()) {
            ContextTray(s, contextActions)
            if (s.draftImages.isNotEmpty() || s.imagesLoading || s.imageError != null) DraftImageTray(s, actions.removeImage)
            if (!supportsImages && s.draftImages.isNotEmpty()) Text("当前模型不支持图片，请先切换模型", color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 5.dp))
            Row(Modifier.fillMaxWidth().heightIn(min = if (expanded) 0.dp else 56.dp).padding(horizontal = if (expanded) 0.dp else 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!expanded) ComposerAddButton(s, actions, supportsImages, contextActions, { contextPicker = true }, { toolPicker = true })
            // Keep one editor in the same composition position during the height transition.
            key("message-editor") { BasicTextField(s.draft, actions.draft,
                modifier = Modifier.weight(1f).onFocusChanged { inputFocused = it.isFocused }
                    .padding(start = if (expanded) 16.dp else 10.dp, end = if (expanded) 16.dp else 8.dp,
                        top = if (expanded) 14.dp else 10.dp, bottom = if (expanded) 4.dp else 10.dp),
                minLines = 1, maxLines = if (expanded) 5 else 1, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                textStyle = LocalTextStyle.current.copy(color = Ink, fontSize = 16.sp, lineHeight = 22.sp),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                decorationBox = { editor -> Box(Modifier.fillMaxWidth()) {
                    if (s.draft.isEmpty()) Text(locationLabel, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp, lineHeight = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    editor()
                } }) }
            if (!expanded) ComposerSendButton(s, actions, showStop, supportsImages)
            }
            if (expanded) {
            Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                ComposerAddButton(s, actions, supportsImages, contextActions, { contextPicker = true }, { toolPicker = true })
                ComposerPermission(s, actions) { permissionOpen = it }
                TextButton(onClick = { showUsage = true; actions.refreshUsage() },
                    modifier = Modifier.defaultMinSize(minWidth = if (LocalDensity.current.fontScale >= 1.5f) 76.dp else 48.dp).height(48.dp).semantics { contentDescription = contextPercent?.let { "上下文已使用 $it%，查看详细用量" } ?: "上下文用量，查看详细信息" },
                    contentPadding = PaddingValues(horizontal = 5.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = if (contextPercent != null && contextPercent >= 85) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)) {
                    Text(contextPercent?.let { "$it%" } ?: "—%", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Clip)
                }
                ComposerModel(s, actions, Modifier.weight(1f)) { modelOpen = it }
                Spacer(Modifier.width(6.dp))
                ComposerSendButton(s, actions, showStop, supportsImages)
            }
            }
        }
    }
    if (contextPicker && contextActions != null) ContextPicker(s.root, contextActions, { contextPicker = false }) {
        if (s.draft.endsWith("@")) actions.draft(s.draft.dropLast(1))
    }
    if (toolPicker && contextActions != null) ToolPicker(s.profile?.name.orEmpty(), contextActions, { toolPicker = false }, s.draftContext)
    if (showUsage) ModalBottomSheet(onDismissRequest = { showUsage = false }) {
        LazyColumn(contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.fillMaxWidth()) {
            item { Text("上下文与用量", style = MaterialTheme.typography.titleLarge) }
            item { ConversationUsageInfo(s) }
            item { TextButton(onClick = actions.refreshUsage, enabled = s.connected && s.thread?.id !in s.usageLoading) { Text("刷新用量") } }
        }
    }

}

@Composable private fun ComposerAddButton(s: PocketState, actions: ComposerActions, supportsImages: Boolean, context: ContextActions?, references: () -> Unit, tools: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { if (context == null) actions.addImage() else menu = true }, enabled = s.thread != null && !s.sending && !s.imagesLoading && !s.contextLoading, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Outlined.Add, if (context == null) "添加图片" else "添加附件、上下文或工具", Modifier.size(27.dp))
        }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(text = { Text("照片") }, leadingIcon = { Icon(Icons.Outlined.Image, null) }, enabled = supportsImages && s.draftImages.size < MAX_OUTGOING_IMAGES, onClick = { menu = false; actions.addImage() })
            DropdownMenuItem(text = { Text("上传文件") }, leadingIcon = { Icon(Icons.Outlined.AttachFile, null) }, enabled = s.draftContext.size < MAX_CONTEXT_ITEMS, onClick = { menu = false; context?.addDocument?.invoke() })
            DropdownMenuItem(text = { Text("引用文件或代码 @") }, leadingIcon = { Icon(Icons.Outlined.AlternateEmail, null) }, enabled = s.connected && s.draftContext.size < MAX_CONTEXT_ITEMS, onClick = { menu = false; references() })
            HorizontalDivider()
            DropdownMenuItem(text = { Text("工具与能力") }, leadingIcon = { Icon(Icons.Outlined.Build, null) }, enabled = s.connected, onClick = { menu = false; tools() })
        }
    }
}

@Composable private fun ComposerSendButton(s: PocketState, actions: ComposerActions, showStop: Boolean, supportsImages: Boolean) {
    FilledIconButton(onClick = { if (showStop) actions.stop() else actions.send() },
        enabled = s.connected && s.threadReady && !s.sending && !s.settingsApplying && !s.sendingComments && !s.imagesLoading && !s.contextLoading && (showStop || s.draft.isNotBlank() || s.draftImages.isNotEmpty() || s.draftContext.isNotEmpty()) && (s.draftImages.isEmpty() || supportsImages),
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Ink, contentColor = Paper), modifier = Modifier.size(48.dp)) {
        if (s.sending) CircularProgressIndicator(Modifier.size(19.dp), color = Paper, strokeWidth = 2.dp)
        else Icon(if (showStop) Icons.Outlined.Stop else Icons.Outlined.ArrowUpward, if (showStop) "停止任务" else if (s.activeTurn != null || s.queuedMessages.isNotEmpty()) "加入待发队列" else "发送", Modifier.size(24.dp))
    }
}
