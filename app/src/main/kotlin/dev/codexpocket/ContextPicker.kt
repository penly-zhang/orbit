package dev.codexpocket

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.codexpocket.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.util.UUID

internal data class ContextActions(
    val addDocument: () -> Unit,
    val add: (ContextInput) -> Boolean,
    val remove: (ContextInput) -> Unit,
    val files: suspend (String, String) -> List<ContextFile>,
    val preview: suspend (String) -> String,
    val catalog: suspend () -> ToolCatalog,
    val plugin: suspend (PluginEntry) -> PluginTools,
    val enableSkill: suspend (SkillEntry) -> Unit,
    val reloadMcp: suspend () -> Unit,
    val authorize: suspend (String) -> JsonObject,
    val finishAuthorization: suspend (String, String) -> Unit,
)
internal data class ContextFile(val name: String, val path: String, val directory: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun ContextTray(s: PocketState, actions: ContextActions?) {
    var managing by remember(s.profile?.id, s.thread?.id) { mutableStateOf(false) }
    var inspecting by remember(s.profile?.id, s.thread?.id) { mutableStateOf<ContextInput?>(null) }
    if (s.draftContext.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        s.draftContext.take(if (s.draftContext.size > 2) 1 else 2).forEach { ref -> InputChip(selected = true, onClick = { inspecting = ref },
            label = { Text(ref.label, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, modifier = Modifier.widthIn(max = if (s.draftContext.size > 2) 150.dp else 105.dp)) },
            leadingIcon = { Icon(when (ref.kind) { "skill", "tool" -> Icons.Outlined.Build; "directory" -> Icons.Outlined.Folder; else -> Icons.Outlined.Description }, null, Modifier.size(16.dp)) },
            trailingIcon = { IconButton(onClick = { actions?.remove?.invoke(ref) }, enabled = !s.sending && !s.contextLoading, modifier = Modifier.size(28.dp)) { Icon(Icons.Outlined.Close, "移除上下文 ${ref.label}", Modifier.size(15.dp)) } }) }
        if (s.draftContext.size > 2) TextButton(onClick = { managing = true }) { Text("还有 ${s.draftContext.size - 1} 项", fontSize = 12.sp) }
    }
    if (managing) ModalBottomSheet(onDismissRequest = { managing = false }) {
        LazyColumn(contentPadding = PaddingValues(bottom = 28.dp)) {
            item { Text("当前消息的上下文", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(20.dp)) }
            items(s.draftContext, key = { it.id }) { ref -> ListItem(headlineContent = { Text(ref.label) }, supportingContent = { Text(if (ref.localPath == null) ref.path else "手机附件 · ${ref.bytes / 1024} KB", maxLines = 2, overflow = TextOverflow.Ellipsis) }, trailingContent = { IconButton(onClick = { actions?.remove?.invoke(ref) }, enabled = !s.sending && !s.contextLoading) { Icon(Icons.Outlined.Close, "移除上下文 ${ref.label}") } }) }
        }
    }
    inspecting?.let { ref ->
        AlertDialog(onDismissRequest = { inspecting = null }, title = { Text(ref.label) }, text = { SelectionContainer { Text(if (ref.localPath == null) ref.reference else "手机附件 · ${"%.1f".format(ref.bytes / 1024.0)} KB\n发送时上传到 ${s.profile?.name.orEmpty()}，由 Agent 读取。") } }, confirmButton = { TextButton(onClick = { inspecting = null }) { Text("完成") } })
    }
    if (s.contextLoading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(12.dp))
    s.contextError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun ContextPicker(root: String, actions: ContextActions, dismiss: () -> Unit, selected: () -> Unit) {
    var path by remember(root) { mutableStateOf(root.trimEnd('/').ifBlank { "/" }) }
    var query by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<ContextFile>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var preview by remember { mutableStateOf<ContextFile?>(null) }
    fun add(ref: ContextInput) { if (actions.add(ref)) { selected(); dismiss() } else error = "无法添加，请检查草稿上下文数量或当前会话" }
    LaunchedEffect(path, query, retry) {
        loading = true; error = null; entries = emptyList()
        try { if (query.isNotBlank()) delay(250); entries = actions.files(path, query) }
        catch (c: CancellationException) { throw c } catch (e: Exception) { error = e.message ?: "读取文件失败" }
        finally { loading = false }
    }
    FullPage("引用上下文", dismiss) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Text("选择文件、目录，或预览后指定代码行", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
            SearchBox(query, { query = it }, "搜索项目文件")
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { path = path.substringBeforeLast('/').ifBlank { "/" }; query = "" }, enabled = path != root.trimEnd('/').ifBlank { "/" }) { Icon(Icons.Outlined.ArrowUpward, "上级目录") }
                Text(path, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                TextButton(onClick = { add(ContextInput(UUID.randomUUID().toString(), path.substringAfterLast('/').ifBlank { "/" }, path, "directory")) }) { Text("引用此目录") }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { StateNotice("文件列表加载失败", it, "重试", { retry++ }, failure = true, actionEnabled = !loading) }
            if (!loading && error == null && entries.isEmpty()) Text("没有匹配的文件", color = Muted, modifier = Modifier.padding(16.dp))
            LazyColumn(Modifier.weight(1f)) { items(entries, key = { it.path }) { entry ->
                ListItem(headlineContent = { Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }, supportingContent = { if (query.isNotBlank()) Text(entry.path, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingContent = { Icon(if (entry.directory) Icons.Outlined.Folder else Icons.Outlined.Description, null) },
                    trailingContent = { Icon(if (entry.directory) Icons.Outlined.ChevronRight else Icons.Outlined.Add, null) },
                    modifier = Modifier.clickable { if (entry.directory) { path = entry.path; query = "" } else preview = entry })
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
            } }
        }
    }
    preview?.let { file -> CodeContextDialog(file, actions, { preview = null }) { start, end ->
        add(ContextInput(UUID.randomUUID().toString(), file.name, file.path, startLine = start, endLine = end))
    } }
}

@Composable private fun CodeContextDialog(file: ContextFile, actions: ContextActions, dismiss: () -> Unit, add: (Int?, Int?) -> Unit) {
    var content by remember(file.path) { mutableStateOf<String?>(null) }
    var error by remember(file.path) { mutableStateOf<String?>(null) }
    var start by remember { mutableStateOf("") }; var end by remember { mutableStateOf("") }
    LaunchedEffect(file.path) { try { content = actions.preview(file.path) } catch (c: CancellationException) { throw c } catch (e: Exception) { error = e.message ?: "无法预览" } }
    val valid = start.isEmpty() && end.isEmpty() || start.toIntOrNull()?.let { it > 0 && (end.toIntOrNull() ?: it) >= it && (end.toIntOrNull() ?: it) - it < 500 && (end.isEmpty() || end.toIntOrNull() != null) } == true
    FullPage(file.name, dismiss, bottom = {
        Surface(color = Panel) { Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Button(onClick = { add(start.toIntOrNull(), end.toIntOrNull()); dismiss() }, enabled = valid, modifier = Modifier.fillMaxWidth()) { Text("添加引用") }
        } }
    }) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SelectionContainer { Text(file.path, fontSize = 12.sp, color = Muted) }
            Text("留空引用整个文件；填写行号引用代码，最多 500 行", fontSize = 12.sp, color = Muted)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(start, { start = it.filter(Char::isDigit).take(8) }, label = { Text("起始行") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(end, { end = it.filter(Char::isDigit).take(8) }, label = { Text("结束行") }, singleLine = true, modifier = Modifier.weight(1f))
            }
            if (content == null && error == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { StateNotice("无法预览文本", "$it\n仍可引用整个文件，由 Agent 在电脑端读取。") }
            content?.let { body ->
                val lines = body.lineSequence().take(201).toList()
                if (lines.size > 200) Text("预览前 200 行，引用范围可填写实际文件行号", fontSize = 12.sp, color = Muted)
                Surface(color = Panel, shape = MaterialTheme.shapes.small) { SelectionContainer {
                    Text(lines.take(200).mapIndexed { i, line -> "${i + 1}  $line" }.joinToString("\n"), fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 21.sp, modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(12.dp))
                } }
            }
        }
    }
}
