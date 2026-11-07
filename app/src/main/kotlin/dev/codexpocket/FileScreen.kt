package dev.codexpocket

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.codexpocket.core.*
import java.util.UUID

internal fun directoryCrumbs(root: String, path: String): List<Pair<String, String>> {
    val base = root.trimEnd('/').ifBlank { "/" }; val current = path.trimEnd('/').ifBlank { "/" }
    if (current != base && !current.startsWith(if (base == "/") "/" else "$base/")) return listOf(shortPath(current) to current)
    var built = base
    return listOf(shortPath(base) to base) + current.removePrefix(base).trim('/').split('/').filter(String::isNotBlank).map { part ->
        built = built.trimEnd('/') + "/" + part; part to built
    }
}

@Composable internal fun PathBreadcrumb(root: String, path: String, open: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
        directoryCrumbs(root, path).forEachIndexed { i, crumb ->
            if (i > 0) Icon(Icons.Outlined.ChevronRight, null, Modifier.size(15.dp), tint = Muted)
            TextButton(onClick = { open(crumb.second) }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text(crumb.first, fontSize = 12.sp, maxLines = 1) }
        }
    }
}

@Composable internal fun FilesScreen(s: PocketState, vm: PocketViewModel) {
    var query by rememberSaveable(s.profile?.id, s.root) { mutableStateOf("") }
    var reverse by rememberSaveable { mutableStateOf(false) }
    var showPath by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("文件", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = { showPath = true }) { Icon(Icons.Outlined.Info, "查看完整目录路径", Modifier.size(21.dp)) }
            IconButton(onClick = { reverse = !reverse }) { Icon(Icons.Outlined.SortByAlpha, if (reverse) "按名称升序" else "按名称降序", Modifier.size(21.dp)) }
        }
        PathBreadcrumb(s.root, s.directory, vm::loadFiles)
        SearchBox(query, { query = it }, "筛选当前目录文件")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${s.files.size} 项", fontSize = 12.sp, color = Muted, modifier = Modifier.weight(1f))
            TextButton(onClick = vm::reviewDirectory, enabled = s.connected && !s.filesLoading && !s.sendingComments) { Text("审查此目录", fontSize = 12.sp) }
            IconButton(onClick = { vm.loadFiles(s.directory) }, enabled = s.connected && !s.filesLoading) { Icon(Icons.Outlined.Refresh, "刷新文件", Modifier.size(20.dp)) }
        }
        if (s.filesLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        s.filesError?.let { StateNotice(if (s.files.isEmpty()) "无法读取目录" else "更新失败，显示上次目录", it, "重试", { vm.loadFiles(s.directory) }, failure = true, actionEnabled = s.connected && !s.filesLoading) }
        val files = s.files.filter { it.name.contains(query.trim(), true) }.sortedWith(compareByDescending<FileEntry> { it.directory }.thenComparator { a, b -> if (reverse) b.name.compareTo(a.name, true) else a.name.compareTo(b.name, true) })
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 8.dp)) {
            if (s.directory != s.root) item { ActionRow("上一级", Icons.Outlined.DriveFolderUpload, s.connected, action = vm::parentDirectory) }
            items(files, key = { it.name }) { entry ->
                ListItem(headlineContent = { Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 15.sp) }, leadingContent = { Icon(if (entry.directory) Icons.Outlined.Folder else Icons.Outlined.Description, null, tint = if (entry.directory) Mint else Muted) }, trailingContent = { Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = Muted) },
                    modifier = Modifier.clickable(enabled = s.connected && !s.filesLoading) { vm.openFile(entry) }, colors = ListItemDefaults.colors(containerColor = Panel))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            }
            if (files.isEmpty() && !s.filesLoading && s.filesError == null) item { Text(if (query.isBlank()) "目录为空" else "没有匹配的文件", color = Muted, modifier = Modifier.padding(vertical = 36.dp)) }
        }
    }
    if (showPath) AlertDialog(onDismissRequest = { showPath = false }, title = { Text("当前目录") }, text = { SelectionContainer { Text(s.directory) } }, confirmButton = { TextButton(onClick = { showPath = false }) { Text("完成") } })
}

@Composable internal fun FilePreviewScreen(file: FilePreview, s: PocketState, vm: PocketViewModel) {
    val ext = file.path.substringAfterLast('.').lowercase()
    val image = ext in setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")
    val rich = ext in setOf("html", "htm", "svg", "md", "markdown")
    val binary = !image && !rich && file.bytes.any { it == 0.toByte() }
    var source by rememberSaveable(file.path) { mutableStateOf(false) }; var wrap by rememberSaveable { mutableStateOf(true) }
    var query by rememberSaveable(file.path) { mutableStateOf("") }; var searching by rememberSaveable { mutableStateOf(false) }
    val text = remember(file.bytes) { file.bytes.toString(Charsets.UTF_8) }
    val context = LocalContext.current
    val artifact = s.profile?.id?.let { ArtifactContext(it, file.path.substringBeforeLast('/'), vm) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(shortPath(file.path), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis); Text("${"%.1f".format(file.bytes.size / 1024.0)} KB", color = Muted, fontSize = 12.sp) }
            if (!image && !binary) IconButton(onClick = { searching = !searching; source = true }) { Icon(Icons.Outlined.Search, "搜索文件内容", Modifier.size(21.dp)) }
            if (!image && !binary) IconButton(onClick = { (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(shortPath(file.path), text)) }) { Icon(Icons.Outlined.ContentCopy, "复制文件内容", Modifier.size(21.dp)) }
            var menu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreHoriz, "文件操作") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("引用到当前会话") }, enabled = s.thread != null && !s.sending, onClick = {
                        val tab = s.sessionTabs.find { it.profileId == s.profile?.id && it.thread.id == s.thread?.id }
                        if (tab != null && vm.addContext(tab.profileId, tab.number, ContextInput(UUID.randomUUID().toString(), shortPath(file.path), file.path))) { vm.closePreview(); vm.tab(0) }; menu = false
                    })
                    DropdownMenuItem(text = { Text("复制文件路径") }, onClick = { (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("文件路径", file.path)); menu = false })
                    if (!image && !binary) DropdownMenuItem(text = { Text("分享文件内容") }, onClick = { shareText(context, shortPath(file.path), text); menu = false })
                    if (!image && !binary) DropdownMenuItem(text = { Text(if (wrap) "关闭自动换行" else "开启自动换行") }, onClick = { wrap = !wrap; menu = false })
                }
            }
        }
        if (rich) Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { FilterChip(!source, { source = false; searching = false }, label = { Text("预览") }); FilterChip(source, { source = true }, label = { Text("源码") }) }
        if (searching) SearchBox(query, { query = it }, "查找文本", Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        HorizontalDivider()
        when {
            image -> {
                val bitmap = remember(file.path, file.bytes) { BitmapFactory.decodeByteArray(file.bytes, 0, file.bytes.size) }
                if (bitmap == null) StateNotice("图片无法解码", failure = true) else Image(bitmap.asImageBitmap(), shortPath(file.path), Modifier.fillMaxSize().padding(12.dp))
            }
            binary -> StateNotice("此文件暂不支持预览", "可以复制路径或引用到会话，由电脑端工具读取。")
            rich && !source -> DocumentPane(text, if (ext in setOf("md", "markdown")) "markdown" else if (ext == "svg") "svg" else "html", artifact, file.path.substringBeforeLast('/'), Modifier.weight(1f).fillMaxWidth())
            else -> FileCodeContent(text, if (searching) query else "", wrap, Modifier.weight(1f))
        }
    }
}

@Composable private fun FileCodeContent(text: String, query: String, wrap: Boolean, modifier: Modifier) {
    val display = remember(text) { text.take(200000) }
    val lines = remember(display) { display.split('\n') }
    val matches = remember(lines, query) { if (query.isBlank()) emptyList() else lines.indices.filter { lines[it].contains(query, true) } }
    var match by remember(query) { mutableIntStateOf(0) }
    val list = rememberLazyListState(); val color = MaterialTheme.colorScheme.secondaryContainer
    LaunchedEffect(matches, match) { if (matches.isNotEmpty()) list.animateScrollToItem(matches[match.mod(matches.size)]) }
    Column(modifier.fillMaxWidth()) {
        if (display.length < text.length) Text("预览前 200,000 字符，复制和分享包含全文", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        if (query.isNotBlank()) Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (matches.isEmpty()) "没有匹配行" else "${match.mod(matches.size) + 1} / ${matches.size} 行匹配", color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
            IconButton(onClick = { match = (match - 1).mod(matches.size) }, enabled = matches.isNotEmpty()) { Icon(Icons.Outlined.KeyboardArrowUp, "上一处文本匹配") }
            IconButton(onClick = { match = (match + 1).mod(matches.size) }, enabled = matches.isNotEmpty()) { Icon(Icons.Outlined.KeyboardArrowDown, "下一处文本匹配") }
        }
        val horizontal = rememberScrollState()
        SelectionContainer { LazyColumn(Modifier.fillMaxSize().then(if (wrap) Modifier else Modifier.horizontalScroll(horizontal)), state = list, contentPadding = PaddingValues(16.dp)) {
            items(lines.size, key = { it }) { i ->
                val line = lines[i]
                val value = remember(line, query, color) { androidx.compose.ui.text.buildAnnotatedString {
                    if (query.isBlank()) append(line.ifEmpty { " " }) else {
                        var last = 0
                        Regex(Regex.escape(query), RegexOption.IGNORE_CASE).findAll(line).take(2000).forEach { m -> append(line.substring(last, m.range.first)); pushStyle(androidx.compose.ui.text.SpanStyle(background = color)); append(m.value); pop(); last = m.range.last + 1 }
                        append(line.substring(last))
                    }
                } }
                Text(value, fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 21.sp, softWrap = wrap)
            }
        } }
    }
}
