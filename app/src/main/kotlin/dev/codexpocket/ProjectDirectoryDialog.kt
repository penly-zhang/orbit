package dev.codexpocket

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.codexpocket.core.FileEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun ProjectDirectoryDialog(host: String, hostName: String, initial: String, actions: ProjectActions, dismiss: () -> Unit) {
    var path by remember { mutableStateOf(initial.ifBlank { "/" }) }
    var loadedPath by remember { mutableStateOf<String?>(null) }
    var entries by remember { mutableStateOf(emptyList<FileEntry>()) }
    var busy by remember { mutableStateOf(false) }
    var issue by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    suspend fun browse(destination: String) {
        busy = true; issue = null; loadedPath = null; entries = emptyList()
        try {
            require(destination.startsWith('/') && destination.none(Char::isISOControl)) { "请输入绝对路径，例如 /home/user/workspace" }
            val normalized = destination.split('/').fold(mutableListOf<String>()) { parts, part ->
                when (part) { "", "." -> Unit; ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex); else -> parts.add(part) }; parts
            }.joinToString("/", prefix = "/")
            path = normalized
            entries = requireNotNull(actions.projectDirectories).invoke(host, normalized)
            loadedPath = normalized
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { issue = e.message ?: "无法读取目录" }
        finally { busy = false }
    }
    LaunchedEffect(host) { browse(path) }
    AlertDialog(onDismissRequest = { if (!busy) dismiss() }, title = { Text("创建项目 · $hostName") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("选择这台电脑上的已有目录作为项目目录。")
            OutlinedTextField(path, { path = it; loadedPath = null }, enabled = !busy, label = { Text("远程目录") }, singleLine = true)
            Row {
                TextButton(enabled = !busy, onClick = { scope.launch { browse(path.substringBeforeLast('/', "").ifEmpty { "/" }) } }) { Text("上一级") }
                TextButton(enabled = !busy, onClick = { scope.launch { browse(path) } }) { Text("打开路径") }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            issue?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 260.dp)) {
                items(entries, key = { it.name }) { entry ->
                    Row(Modifier.fillMaxWidth().clickable(enabled = !busy && loadedPath == path) {
                        scope.launch { browse(path.trimEnd('/') + "/" + entry.name) }
                    }.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Outlined.FolderOpen, null)
                        Text(entry.name)
                    }
                }
                if (loadedPath != null && entries.isEmpty()) item { Text("此目录没有子目录，可以直接选择。") }
            }
        }
    }, confirmButton = {
        TextButton(enabled = !busy && loadedPath == path, onClick = {
            scope.launch {
                busy = true; issue = null
                try { requireNotNull(actions.addProject).invoke(host, path); dismiss() }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { issue = e.message ?: "创建项目失败" }
                finally { busy = false }
            }
        }) { Text("选择此目录") }
    }, dismissButton = { TextButton(enabled = !busy, onClick = dismiss) { Text("取消") } })
}
