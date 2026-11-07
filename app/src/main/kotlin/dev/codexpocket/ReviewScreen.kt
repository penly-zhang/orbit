package dev.codexpocket

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.codexpocket.core.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable internal fun ReviewChangesScreen(s: PocketState, vm: ReviewActions, runReview: () -> Unit) {
    var filter by rememberSaveable(s.root, s.reviewRange) { mutableStateOf("all") }
    var search by rememberSaveable(s.root) { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var showRepository by remember { mutableStateOf(false) }
    var scopeMenu by remember { mutableStateOf(false) }
    val task = s.reviewRange == "task"
    val verified = if (task) s.taskReview != null else s.changesRoot == s.root
    val changes = if (task) s.taskReview?.changes.orEmpty() else if (verified) s.changes else emptyList()
    val token = if (task) s.taskReview?.token else null
    val loading = if (task) s.taskReviewLoading else s.changesLoading
    val error = if (task) s.taskReviewError else s.changesError
    val viewed = changes.count { changeViewed(it, s.reviewRange, token, s.reviewViewed) }
    val groups = if (task) listOf("all" to "本轮期间") else listOf("staged" to "已暂存", "working" to "未暂存")
    fun sideViewed(change: GitChange, side: String) = change.fingerprint.isNotBlank() && s.reviewViewed[reviewViewKey(s.reviewRange, token, change.path, side)] == change.fingerprint
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item(key = "heading") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("代码审查", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    IconButton(onClick = { showRepository = true }) { Icon(Icons.Outlined.Info, "仓库详情", Modifier.size(20.dp)) }
                    IconButton(onClick = { searching = !searching }) { Icon(Icons.Outlined.Search, "搜索变更文件") }
                    FilledTonalButton(onClick = runReview, enabled = s.connected && !s.loading && !s.sendingComments) { Text("AI 审查") }
                }
                val repository = if (task) s.taskReview?.repository else s.reviewOverview?.repository
                val branch = if (task) s.taskReview?.branch else s.reviewOverview?.branch
                Text(listOfNotNull(repository?.substringAfterLast('/'), branch).joinToString(" · ").ifBlank { s.project?.name.orEmpty() }, fontSize = 12.sp, color = Muted)
                
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!task, { vm.chooseReviewRange("workspace") }, label = { Text("工作区") })
                    FilterChip(task, { vm.chooseReviewRange("task") }, enabled = s.taskReview != null || task, label = { Text("本轮期间") })
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (verified) "${changes.size} 个文件" else if (loading) "正在读取…" else "尚未读取", fontSize = 13.sp)
                    if (verified) ChangeCounts(changes)
                    Spacer(Modifier.weight(1f))
                    if (loading) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp)
                }
                if (verified && changes.isNotEmpty()) Text("已看过 $viewed / ${changes.size} 个文件", fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 4.dp))
                if (task && s.taskReview?.partial == true) Text("大文件或超出范围的文件未完整统计，行数未知显示为 —。", fontSize = 12.sp, color = Muted)
            }
            if (searching) item(key = "search") {
                OutlinedTextField(search, { search = it }, placeholder = { Text("搜索文件路径") }, singleLine = true, modifier = Modifier.fillMaxWidth(), trailingIcon = { IconButton(onClick = { search = ""; searching = false }) { Icon(Icons.Outlined.Close, "关闭文件搜索") } })
            }
            item(key = "filters") {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val filters = listOf("all" to "全部", "unread" to "未读", "comments" to "有评论")
                    filters.forEach { (id, label) -> FilterChip(filter == id, { filter = id }, label = { Text(label, fontSize = 12.sp) }) }
                    if (!task) Box {
                        TextButton(onClick = { scopeMenu = true }) { Text(when (filter) { "staged" -> "已暂存"; "working" -> "未暂存"; else -> "范围" }, fontSize = 12.sp) }
                        DropdownMenu(scopeMenu, { scopeMenu = false }) {
                            listOf("all" to "全部变更", "staged" to "已暂存", "working" to "未暂存").forEach { (id, name) -> DropdownMenuItem(text = { Text(name) }, onClick = { filter = id; scopeMenu = false }) }
                        }
                    }

                }
            }
            if (error != null) item(key = "error") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    StateNotice(if (task) "本轮记录暂不可用" else "无法读取变更", error + if (changes.isNotEmpty()) "\n下方保留上次读取的结果。" else "", "重试", { vm.refreshChanges() }, failure = true, actionEnabled = s.connected && !loading)
                    if (task) TextButton(onClick = { vm.chooseReviewRange("workspace") }) { Text("查看工作区") }
                }
            }
            var matches = 0
            for ((side, title) in groups) {
                val visible = changes.filter { change ->
                    (task || if (side == "staged") change.staged else change.working) &&
                        (filter != "staged" || side == "staged") && (filter != "working" || side == "working") &&
                        (filter != "unread" || !sideViewed(change, side)) &&
                        (filter != "comments" || s.reviewComments.any { it.path == change.path }) &&
                        (search.isBlank() || change.path.contains(search.trim(), ignoreCase = true))
                }
                matches += visible.size
                if (visible.isNotEmpty()) item(key = "group:$side") { Text("$title · ${visible.size}", fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 8.dp)) }
                items(visible, key = { "$side:${it.path}" }) { change ->
                    val read = sideViewed(change, side)
                    Surface(onClick = { vm.openDiff(change, side) }, color = Panel, shape = RoundedCornerShape(16.dp)) {
                        Row(Modifier.fillMaxWidth().padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(change.path, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, color = if (read) Muted else Ink)
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(changeLabel(change.status), fontSize = 12.sp, color = Muted)
                                    val counts = if (side == "staged") change.copy(added = change.stagedAdded, deleted = change.stagedDeleted) else if (side == "working") change.copy(added = change.workingAdded, deleted = change.workingDeleted) else change
                                    ChangeCounts(listOf(counts))
                                    val comments = s.reviewComments.count { it.path == change.path }
                                    if (comments > 0) Text("$comments 条评论", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                                }
                                if (change.oldPath != null) Text("原路径：${change.oldPath}", fontSize = 12.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            IconButton(onClick = { vm.toggleReviewViewed(change.path, side) }) { Icon(if (read) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked, if (read) "取消已读标记" else "标记已看过", Modifier.size(21.dp), tint = if (read) MaterialTheme.colorScheme.primary else Muted) }
                        }
                    }
                }
            }
            if (verified && !loading && error == null && changes.isEmpty()) item(key = "empty") {
                Text(if (task) "本轮期间未记录到文件内容变化。" else "工作区没有未提交的修改。", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 36.dp))
            } else if (verified && changes.isNotEmpty() && matches == 0) item(key = "noMatches") { Text("没有符合当前筛选条件的文件。", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 24.dp)) }
        }
        ReviewBar(s) { vm.showReviewComments(true) }
    }
    if (showRepository) AlertDialog(onDismissRequest = { showRepository = false }, title = { Text("仓库与范围") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SelectionContainer { Text(if (task) s.taskReview?.repository ?: s.root else s.reviewOverview?.repository ?: s.root) }
            Text(if (task) "对比任务开始与当前或结束时的文件，包括这段时间内的其他编辑。" else "显示当前工作区未提交的修改。", fontSize = 13.sp)
            val at = if (task) s.taskReview?.capturedAt else s.reviewOverview?.capturedAt
            at?.let { Text("更新于 ${SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(it))}", color = Muted, fontSize = 12.sp) }
        }
    }, confirmButton = { TextButton(onClick = { showRepository = false }) { Text("完成") } })
    if (s.showReviewComments) ReviewCommentsSheet(s, vm) { vm.showReviewComments(false) }
}

@Composable internal fun ReviewDiffScreen(s: PocketState, vm: ReviewActions) {
    val lines = remember(s.diff) { diffLines(s.diff.orEmpty()).filterNot { line -> line.kind == DiffKind.META && (listOf("diff --git ", "index ", "--- ", "+++ ").any { line.text.startsWith(it) } || line.text in setOf("工作区", "已暂存", "未暂存")) }.mapIndexed { index, line -> line.copy(index = index) } }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val horizontal = rememberScrollState()
    var wrap by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf<DiffLine?>(null) }
    var editor by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ReviewComment?>(null) }
    val reanchor = s.reviewComments.find { it.id == s.reanchorComment }
    val hunks = remember(lines) { lines.filter { it.kind == DiffKind.HUNK }.map { it.index } }
    val firstVisible by remember { derivedStateOf { list.firstVisibleItemIndex } }
    val currentHunk = hunks.indexOfLast { it <= firstVisible }.coerceAtLeast(0)
    val changes = if (s.reviewRange == "task") s.taskReview?.changes.orEmpty() else s.changes
    val navigation = changes.filter { s.reviewRange == "task" || s.diffSide == "all" || (s.diffSide == "staged" && it.staged) || (s.diffSide == "working" && it.working) }
    val change = changes.find { it.path == s.diffPath }
    val key = reviewViewKey(s.reviewRange, if (s.reviewRange == "task") s.taskReview?.token else null, s.diffPath, s.diffSide)
    val viewed = if (s.diffSide == "all" && change != null) changeViewed(change, s.reviewRange, if (s.reviewRange == "task") s.taskReview?.token else null, s.reviewViewed) else change?.fingerprint?.takeIf { it.isNotBlank() }?.let { s.reviewViewed[key] == it } ?: false
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(s.diffPath, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(when { reanchor != null -> "选择行号，为评论重新定位"; s.reviewRange == "task" -> "本轮期间 · 点击行号评论"; s.diffSide == "staged" -> "已暂存 · 点击行号评论"; s.diffSide == "working" -> "未暂存 · 点击行号评论"; else -> "点击行号评论 · 长按代码选择复制" }, color = Muted, fontSize = 12.sp)
            }
            IconButton(onClick = { selected = null; editor = true }, enabled = !s.sendingComments && s.reviewComments.size < 100) { Icon(Icons.Outlined.AddComment, if (reanchor != null) "重新定位为文件评论" else "添加文件评论", Modifier.size(21.dp)) }
            IconButton(onClick = { vm.toggleReviewViewed(s.diffPath, s.diffSide) }, enabled = change != null && s.reanchorComment == null) { Icon(if (viewed) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked, if (viewed) "取消已读标记" else "标记已看过", Modifier.size(21.dp), tint = if (viewed) MaterialTheme.colorScheme.primary else Muted) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.navigateReviewFile(-1) }, enabled = navigation.indexOfFirst { it.path == s.diffPath } > 0) { Icon(Icons.Outlined.ChevronLeft, "上个文件") }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) { Text("文件", color = Muted, fontSize = 12.sp); Text("${(navigation.indexOfFirst { it.path == s.diffPath } + 1).coerceAtLeast(1)}/${navigation.size}", fontSize = 12.sp) }
            IconButton(onClick = { vm.navigateReviewFile(1) }, enabled = navigation.indexOfFirst { it.path == s.diffPath } in 0 until navigation.lastIndex) { Icon(Icons.Outlined.ChevronRight, "下个文件") }
            VerticalDivider(Modifier.height(24.dp))
            IconButton(onClick = { scope.launch { list.animateScrollToItem(hunks[(currentHunk - 1).coerceAtLeast(0)]) } }, enabled = hunks.isNotEmpty() && currentHunk > 0) { Icon(Icons.Outlined.KeyboardArrowUp, "上一处修改") }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) { Text("修改", color = Muted, fontSize = 12.sp); Text(if (hunks.isEmpty()) "—" else "${currentHunk + 1} / ${hunks.size}", fontSize = 12.sp) }
            IconButton(onClick = { scope.launch { list.animateScrollToItem(hunks[(currentHunk + 1).coerceAtMost(hunks.lastIndex)]) } }, enabled = hunks.isNotEmpty() && currentHunk < hunks.lastIndex) { Icon(Icons.Outlined.KeyboardArrowDown, "下一处修改") }
            IconButton(onClick = { wrap = !wrap }) { Icon(if (wrap) Icons.Outlined.WrapText else Icons.Outlined.SwapHoriz, if (wrap) "关闭自动换行" else "开启自动换行", Modifier.size(21.dp)) }
        }
        if (change != null && s.diffFingerprint.isNotBlank() && change.fingerprint != s.diffFingerprint) Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("代码已更新，当前显示上次读取的差异", fontSize = 12.sp, color = Muted, modifier = Modifier.weight(1f))
            TextButton(onClick = vm.refreshDiff) { Text("刷新差异") }
        }
        if (reanchor != null) Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(reanchor.body, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f), fontSize = 12.sp)
            TextButton(onClick = vm.cancelReviewRelocation) { Text("取消定位") }
        }
        HorizontalDivider()
        val density = LocalDensity.current
        val measurer = rememberTextMeasurer()
        val style = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 19.sp)
        val widest = remember(lines) { lines.maxByOrNull { line -> line.text.sumOf { if (it.code >= 0x2e80) 2L else 1L } }?.text.orEmpty() }
        val width = remember(widest, style) { measurer.measure(widest, style, softWrap = false).size.width }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
            val viewport = maxWidth
            val contentWidth = if (wrap) viewport else maxOf(viewport, with(density) { width.toDp() } + 88.dp).coerceAtMost(20000.dp)
            Box(Modifier.fillMaxSize().horizontalScroll(horizontal, enabled = !wrap)) {
                SelectionContainer {
                    LazyColumn(Modifier.width(contentWidth).fillMaxHeight(), state = list, contentPadding = PaddingValues(bottom = 12.dp)) {
                        items(lines, key = { it.index }) { line ->
                            val rowComments = s.reviewComments.filter { it.path == s.diffPath && !it.stale && it.section == line.section && it.oldLine == line.oldLine && it.newLine == line.newLine && line.commentable }
                            val background = when (line.kind) { DiffKind.ADDED -> MaterialTheme.colorScheme.primaryContainer; DiffKind.REMOVED -> MaterialTheme.colorScheme.errorContainer; DiffKind.HUNK -> MaterialTheme.colorScheme.surfaceContainer; else -> Panel }
                            Row(Modifier.fillMaxWidth().background(background).padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                                Row(Modifier.width(72.dp).offset(x = if (wrap) 0.dp else with(density) { horizontal.value.toDp() }).zIndex(1f).background(background).semantics { contentDescription = "评论 ${s.diffPath}:${line.newLine?.toString() ?: "旧${line.oldLine}"}" }.clickable(enabled = line.commentable && !s.sendingComments) { selected = line; editor = true }.padding(vertical = 2.dp)) {
                                    Text(line.oldLine?.toString().orEmpty(), color = Muted, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.width(34.dp).padding(start = 4.dp))
                                    Text(line.newLine?.toString().orEmpty(), color = Muted, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.width(38.dp))
                                }
                                Text(reviewSyntax(line.text.ifEmpty { " " }, line.kind), fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 19.sp, modifier = Modifier.weight(1f).padding(end = 12.dp), softWrap = wrap)
                            }
                            rowComments.forEach { comment -> Box(Modifier.width(viewport).offset(x = if (wrap) 0.dp else with(density) { horizontal.value.toDp() })) { CommentCard(comment, { editing = comment }, { vm.removeReviewComment(comment.id) }, { vm.relocateReviewComment(comment.id) }, !s.sendingComments) } }
                        }
                        items(s.reviewComments.filter { it.path == s.diffPath && (it.stale || (it.oldLine == null && it.newLine == null)) }, key = { "comment:${it.id}" }) { comment ->
                            Box(Modifier.width(viewport).offset(x = if (wrap) 0.dp else with(density) { horizontal.value.toDp() })) { CommentCard(comment, { editing = comment }, { vm.removeReviewComment(comment.id) }, { vm.relocateReviewComment(comment.id) }, !s.sendingComments) }
                        }
                    }
                }
            }
        }
        ReviewBar(s) { vm.showReviewComments(true) }
    }
    if (editor) CommentEditor(if (reanchor != null) "确认评论新位置" else "添加评论", selected?.let { commentLocation(it.section, it.oldLine, it.newLine) } ?: "文件评论", selected?.text.orEmpty(), reanchor?.body.orEmpty(), { editor = false }) { body ->
        if (reanchor != null) vm.reanchorReviewComment(reanchor.id, selected, body) else vm.addReviewComment(selected, body)
        editor = false
    }
    editing?.let { c -> CommentEditor("编辑评论", c.path + " · " + commentLocation(c.section, c.oldLine, c.newLine), c.excerpt, c.body, { editing = null }) { body -> vm.editReviewComment(c.id, body); editing = null } }
    if (s.showReviewComments) ReviewCommentsSheet(s, vm) { vm.showReviewComments(false) }
}

private fun commentLocation(section: String, old: Int?, new: Int?) = "$section · " + when { new != null -> "L$new"; old != null -> "旧 L$old"; else -> "文件评论" }

@Composable private fun ReviewBar(s: PocketState, open: () -> Unit) {
    if (s.reviewComments.isEmpty()) return
    Surface(shadowElevation = 2.dp) {
        Button(onClick = open, enabled = !s.sendingComments, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            if (s.sendingComments) { CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp); Spacer(Modifier.width(8.dp)) }
            Text(if (s.sendingComments) "正在核对评论位置…" else "发送 ${s.reviewComments.size} 条评论")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ReviewCommentsSheet(s: PocketState, vm: ReviewActions, dismiss: () -> Unit) {
    var editing by remember { mutableStateOf<ReviewComment?>(null) }
    var selected by remember(s.root) { mutableStateOf(s.reviewComments.map { it.id }.toSet()) }
    var destinationMenu by remember { mutableStateOf(false) }
    var immediateMenu by remember { mutableStateOf(false) }
    val canCurrent = s.thread != null && s.thread.cwd == s.root && s.threadReady
    var newConversation by remember(s.thread?.id, s.root) { mutableStateOf(!canCurrent) }
    val ids = s.reviewComments.map { it.id }.toSet()
    val selectedIds = selected.intersect(ids)
    val active = !newConversation && canCurrent && s.activeTurn != null
    ModalBottomSheet(onDismissRequest = dismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 650.dp).imePadding().padding(horizontal = 16.dp).padding(bottom = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("评论 · ${s.reviewComments.size}", fontSize = 22.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { selected = if (selectedIds.size == ids.size) emptySet() else ids }) { Text(if (selectedIds.size == ids.size) "取消全选" else "全选") }
            }
            Box {
                TextButton(onClick = { destinationMenu = true }, enabled = !s.sendingComments) {
                    Text(if (newConversation) "发送到：当前项目的新会话" else "发送到：${s.thread?.title.orEmpty()}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Icon(Icons.Outlined.ExpandMore, null, Modifier.size(18.dp))
                }
                DropdownMenu(destinationMenu, { destinationMenu = false }) {
                    if (canCurrent) DropdownMenuItem(text = { Text("当前会话：${s.thread?.title.orEmpty()}") }, onClick = { newConversation = false; destinationMenu = false })
                    DropdownMenuItem(text = { Text("当前项目的新会话") }, onClick = { newConversation = true; destinationMenu = false })
                }
            }
            Text("仅发送文件、行号和评论${if (active) " · 当前任务结束后发送" else ""}", color = Muted, fontSize = 12.sp)
            s.reviewSendIssue?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp)) }
            LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth(), contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(s.reviewComments, key = { it.id }) { c ->
                    Row(verticalAlignment = Alignment.Top) {
                        Checkbox(c.id in selectedIds, { checked -> selected = if (checked) selected + c.id else selected - c.id }, enabled = !s.sendingComments)
                        Box(Modifier.weight(1f)) { CommentCard(c, { editing = c }, { vm.removeReviewComment(c.id) }, { dismiss(); vm.relocateReviewComment(c.id) }, !s.sendingComments) }
                    }
                }
                if (s.reviewComments.isEmpty()) item { Text("暂无评论。点击代码行号添加。", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 24.dp)) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { vm.sendReviewComments(selectedIds, false, newConversation) }, enabled = selectedIds.isNotEmpty() && s.connected && !s.sendingComments && !s.sending && !s.settingsApplying, modifier = Modifier.weight(1f)) {
                    if (s.sendingComments) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp)
                    else Text("${if (active) "排队发送" else "发送"} ${selectedIds.size} 条评论")
                }
                if (active) Box {
                    IconButton(onClick = { immediateMenu = true }, enabled = !s.sendingComments && selectedIds.isNotEmpty()) { Icon(Icons.Outlined.MoreHoriz, "评论发送方式") }
                    DropdownMenu(immediateMenu, { immediateMenu = false }) {
                        DropdownMenuItem(text = { Text("立即作为引导发送") }, onClick = { immediateMenu = false; vm.sendReviewComments(selectedIds, true, false) })
                    }
                }
            }
        }
    }
    editing?.let { c -> CommentEditor("编辑评论", c.path + " · " + commentLocation(c.section, c.oldLine, c.newLine), c.excerpt, c.body, { editing = null }) { body -> vm.editReviewComment(c.id, body); editing = null } }
}

@Composable private fun CommentCard(comment: ReviewComment, edit: () -> Unit, remove: () -> Unit, relocate: () -> Unit, enabled: Boolean) {
    Surface(color = if (comment.stale) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.padding(vertical = 4.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(comment.path + " · " + commentLocation(comment.section, comment.oldLine, comment.newLine), fontSize = 12.sp, color = Muted)
            if (comment.stale) Text("位置已变化，请重新定位", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            Text(comment.body, fontSize = 13.sp)
            Row {
                TextButton(onClick = edit, enabled = enabled) { Text("编辑", fontSize = 12.sp) }
                TextButton(onClick = relocate, enabled = enabled) { Text("重新定位", fontSize = 12.sp) }
                TextButton(onClick = remove, enabled = enabled) { Text("删除", fontSize = 12.sp) }
            }
        }
    }
}

@Composable private fun CommentEditor(title: String, location: String, excerpt: String, initial: String, dismiss: () -> Unit, save: (String) -> Unit) {
    var body by remember(location, initial) { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(location, fontSize = 12.sp, color = Muted)
            if (excerpt.isNotBlank()) Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(8.dp)) { Text(excerpt.take(2000), fontSize = 12.sp, fontFamily = FontFamily.Monospace, maxLines = 5, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(10.dp)) }
            OutlinedTextField(body, { if (it.length <= 5000) body = it }, label = { Text("评论") }, minLines = 3, maxLines = 8, modifier = Modifier.fillMaxWidth())
        }
    }, confirmButton = { TextButton(onClick = { save(body) }, enabled = body.isNotBlank()) { Text("保存评论") } }, dismissButton = { TextButton(onClick = dismiss) { Text("取消") } })
}
