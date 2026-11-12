package dev.codexpocket

import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.codexpocket.core.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable internal fun SessionTabStrip(s: PocketState, vm: PocketViewModel) {
    val list = rememberLazyListState()
    var closing by remember { mutableStateOf<SessionTab?>(null) }
    var managing by remember { mutableStateOf<SessionTab?>(null) }
    LaunchedEffect(s.profile?.id, s.thread?.id, s.sessionTabs.size) {
        val index = s.sessionTabs.indexOfFirst { it.profileId == s.profile?.id && it.thread.id == s.thread?.id }
        if (index >= 0) list.animateScrollToItem(index)
    }
    Surface(color = Paper, modifier = Modifier.zIndex(1f)) {
        Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically) {
            BoxWithConstraints(Modifier.weight(1f)) {
                val tabWidth = (maxWidth / s.sessionTabs.size.coerceAtLeast(1)).coerceAtLeast(88.dp)
                val dividerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f)
                LazyRow(state = list, modifier = Modifier.fillMaxWidth()) {
                    items(s.sessionTabs, key = { it.key }) { tab ->
                        val selected = s.profile?.id == tab.profileId && s.thread?.id == tab.thread.id && s.tab == 0
                        Row(Modifier.width(tabWidth).height(32.dp).drawBehind {
                            if (tab.key != s.sessionTabs.lastOrNull()?.key) {
                                val stroke = 0.5.dp.toPx()
                                val x = size.width - stroke / 2
                                drawLine(dividerColor, Offset(x, size.height * 0.25f), Offset(x, size.height * 0.75f), strokeWidth = stroke)
                            }
                        }, verticalAlignment = Alignment.CenterVertically) {
                            Row(Modifier.weight(1f).fillMaxHeight()
                                .semantics { contentDescription = "标签 ${tab.label}，${tab.profileName}，${tab.project.name}，${tab.thread.title}" }
                                .combinedClickable(onClick = { vm.openSession(tab) }, onLongClickLabel = "会话操作", onLongClick = { managing = tab })
                                .padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
                                if (tab.running) CircularProgressIndicator(Modifier.size(10.dp), strokeWidth = 1.dp, color = Mint)
                                else if (tab.unread) Text("●", fontSize = 6.sp, color = Mint)
                                Text(tab.label, fontSize = 14.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (selected) Mint else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (selected) IconButton(onClick = { if (tab.hasDraft) closing = tab else vm.closeConversation(tab.key) }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Outlined.Close, "关闭标签 ${tab.label}", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f))
                            }
                        }
                    }
                }
            }
        }
    }
    managing?.let { tab -> NavigationThreadActions(s, vm, ThreadActionTarget(tab.profileId, tab.project, tab.thread)) { managing = null } }
    CloseSessionDialog(closing, { closing = null }) { vm.closeConversation(it.key); closing = null }

}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable internal fun ProjectsScreen(s: PocketState, vm: PocketViewModel, settings: () -> Unit, allowArchiveSwipe: Boolean = true) {
    var target by remember { mutableStateOf<ThreadActionTarget?>(null) }
    ProjectsScreen(s, ProjectActions(vm).copy(manageThread = { project, thread ->
        s.profile?.id?.let { target = ThreadActionTarget(it, project, thread, s.showArchived) }
    }, selectProject = { project -> vm.selectProject(project); if (s.showArchived) vm.showArchived(true) }), settings, allowArchiveSwipe)
    target?.let { NavigationThreadActions(s, vm, it) { target = null } }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable internal fun ProjectsScreen(s: PocketState, vm: ProjectActions, settings: () -> Unit, allowArchiveSwipe: Boolean = true) {
    var search by rememberSaveable(s.profile?.id) { mutableStateOf("") }
    var filter by rememberSaveable(s.profile?.id) { mutableStateOf("all") }
    var filterMenu by remember { mutableStateOf(false) }
    val filters = listOf("all" to "全部", "opened" to "已打开", "running" to "运行中", "unread" to "未读", "pending" to "待确认")
    val rows = projectNavigationRows(s, search, filter)
    var threadMenu by remember { mutableStateOf<String?>(null) }
    var projectInfo by remember(s.profile?.id) { mutableStateOf<ProjectInfo?>(null) }
    var removingProject by remember(s.profile?.id) { mutableStateOf<ProjectInfo?>(null) }
    var sortingProjects by rememberSaveable(s.profile?.id) { mutableStateOf(false) }
    var creatingProject by remember(s.profile?.id) { mutableStateOf(false) }
    var renaming by remember(s.profile?.id) { mutableStateOf<ThreadInfo?>(null) }
    var threadName by remember { mutableStateOf("") }
    LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (s.profiles.isNotEmpty()) item(key = "machines") {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                s.profiles.forEach { profile ->
                    val status = s.connections[profile.id]
                    val label = if (!profile.autoConnect) "已关闭" else status?.label ?: "待连接"
                    FilterChip(selected = s.profile?.id == profile.id, onClick = { vm.selectHost(profile.id) },
                        leadingIcon = { if (status?.connecting == true || status?.reconnecting == true) CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp) else Icon(Icons.Outlined.LaptopMac, null, Modifier.size(17.dp)) },
                        label = { Text("${profile.connectionLabel(s.profiles)} · $label", fontSize = 12.sp) })
                }
            }
        }
        if (!s.connected && s.connectionIssue != null) item(key = "connectionHelp") {
            val advice = connectionAdvice(s.connectionIssue)
            StateNotice(advice.title, advice.action, "检查设置", settings, failure = true)
        }
        s.archiveUndo?.let { undo -> item(key = "archiveUndo") {
            StateNotice(if (undo.restored) "会话已恢复" else "会话已归档", actionLabel = "撤销", action = vm.undoArchive, dismiss = vm.dismissArchiveUndo)
        } }
        item(key = "search") { SearchBox(search, { search = it }, "搜索项目或会话") }
        if (search.isNotBlank() || filter != "all") item(key = "searchScope") { Text("当前电脑 · 仅搜索已加载的会话", color = Muted, fontSize = 11.sp) }
        item(key = "projectsLabel") { Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (s.showArchived) "已归档" else if (filter == "all") "项目" else "项目 · ${filters.first { it.first == filter }.second}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text("${rows.size} 个", color = Muted, fontSize = 12.sp)
            if (vm.moveProject != null) IconButton(onClick = { sortingProjects = true }, enabled = s.projects.size > 1) { Icon(Icons.Outlined.SwapVert, "调整项目顺序", Modifier.size(20.dp)) }
            if (vm.addProject != null) IconButton(onClick = { creatingProject = true }, enabled = s.connected) { Icon(Icons.Outlined.CreateNewFolder, "创建项目", Modifier.size(20.dp)) }
            Box {
                IconButton(onClick = { filterMenu = true }) { Icon(Icons.Outlined.FilterList, "筛选会话", Modifier.size(20.dp), tint = if (filter == "all" && !s.showArchived) Muted else Mint) }
                DropdownMenu(filterMenu, { filterMenu = false }) {
                    filters.forEach { (key, label) -> DropdownMenuItem(text = { Text(if (key == "all") "活动会话" else label) }, onClick = { vm.showArchived(false); filter = key; filterMenu = false }) }
                    DropdownMenuItem(text = { Text("已归档") }, onClick = { vm.showArchived(true); filter = "all"; filterMenu = false })
                }
            }
            IconButton(onClick = vm.refreshProjects, enabled = s.connected && !s.loading, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.Refresh, "刷新项目", Modifier.size(20.dp)) }
        } }
        if (rows.isEmpty() && s.projects.isNotEmpty()) item { Text("没有匹配的项目或已加载会话", color = Muted, modifier = Modifier.padding(16.dp)) }
        rows.forEach { row ->
            val project = row.project
            val expanded = row.expanded
            item(key = "project:${project.id}") {
                Surface(color = if (expanded) MaterialTheme.colorScheme.primaryContainer else Panel, shape = MaterialTheme.shapes.medium) {
                    Row(Modifier.fillMaxWidth().combinedClickable(onClick = { if (s.project?.id == project.id) vm.home() else if (s.connected) vm.selectProject(project) }, onLongClick = { projectInfo = project }).padding(start = 14.dp, end = 8.dp, top = 9.dp, bottom = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Outlined.FolderOpen, null, tint = Mint, modifier = Modifier.size(25.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(project.name, style = MaterialTheme.typography.titleSmall)
                            Text(projectNavigationSummary(s, project), fontSize = 11.sp, color = Muted)
                        }
                        if (vm.removeProject != null) IconButton(onClick = { projectInfo = project }) { Icon(Icons.Outlined.MoreHoriz, "项目操作", Modifier.size(20.dp)) }
                        Icon(if (expanded) Icons.Outlined.ExpandMore else Icons.Outlined.ChevronRight, if (s.project?.id == project.id) "收起项目" else "加载项目会话", tint = Muted)
                    }
                }
            }
            if (expanded) {
                if (s.project?.id == project.id && s.threadsLoading) item(key = "threadSync:${project.id}") {
                    Row(Modifier.padding(start = 14.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp)
                        Text(if (s.threads.isEmpty()) "正在加载会话…" else "正在更新列表…", fontSize = 12.sp, color = Muted)
                    }
                }
                s.threadsError?.takeIf { s.project?.id == project.id }?.let { issue -> item(key = "threadError:${project.id}") { StateNotice(if (s.threads.isEmpty()) "会话列表加载失败" else "更新失败，已保留上次列表", issue, "重试", { vm.loadThreads() }, failure = true, actionEnabled = s.connected && !s.threadsLoading) } }
                val pinned = s.pinnedThreads[project.id].orEmpty()
                val threads = row.threads.sortedBy { pinned.indexOf(it.id).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE }
                items(threads, key = { "thread:${project.id}:${it.id}" }) { thread ->
                    val tab = s.sessionTabs.find { it.profileId == s.profile?.id && it.thread.id == thread.id }
                    val busy = s.archivingThread == thread.id
                    val dismiss = rememberSwipeToDismissBoxState(confirmValueChange = { value ->
                        if (value == SwipeToDismissBoxValue.EndToStart && s.connected && s.archivingThread == null) vm.archiveThread(thread)
                        false
                    })
                    val threadCard: @Composable () -> Unit = {
                        Surface(color = Panel, shape = RoundedCornerShape(12.dp), modifier = Modifier.padding(start = 10.dp)) {
                            Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).combinedClickable(onClick = { if ((s.connected || tab != null) && !s.showArchived && !busy) { vm.openProjectThread?.invoke(project, thread) ?: vm.openThread(thread) } }, onLongClick = { if (vm.manageThread != null) vm.manageThread.invoke(project, thread) else threadMenu = thread.id }).padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                        if (thread.id in pinned) Icon(Icons.Outlined.PushPin, "已置顶", Modifier.size(12.dp), tint = Mint)
                                        Text(thread.title, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    val running = tab?.running == true || thread.status in setOf("active", "inProgress")
                                    Text(listOfNotNull(agentLabel(thread.engineId), if (tab != null) taskPresentation(s, tab).label else if (running) "运行中" else threadDate(thread.updated), "草稿".takeIf { tab?.hasDraft == true }, "未读".takeIf { tab?.unread == true }).joinToString(" · "), fontSize = 12.sp, color = if (running) Mint else Muted)
                                }
                                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                Box {
                                    IconButton(onClick = { if (vm.manageThread != null) vm.manageThread.invoke(project, thread) else threadMenu = thread.id }) { Icon(Icons.Outlined.MoreHoriz, "会话列表操作", Modifier.size(20.dp), tint = Muted) }
                                    DropdownMenu(threadMenu == thread.id, { threadMenu = null }) {
                                        DropdownMenuItem(text = { Text(if (thread.id in pinned) "取消置顶" else "置顶会话") }, leadingIcon = { Icon(Icons.Outlined.PushPin, null) }, onClick = { threadMenu = null; vm.pinThread(project.id, thread.id, thread.id !in pinned) })
                                        DropdownMenuItem(text = { Text("重命名会话") }, leadingIcon = { Icon(Icons.Outlined.Edit, null) }, enabled = s.connected && !busy, onClick = { threadMenu = null; threadName = thread.title; renaming = thread })
                                        DropdownMenuItem(text = { Text(if (s.showArchived) "恢复会话" else "归档会话") }, leadingIcon = { Icon(if (s.showArchived) Icons.Outlined.Unarchive else Icons.Outlined.Archive, null) }, enabled = s.connected && s.archivingThread == null, onClick = { threadMenu = null; vm.archiveThread(thread) })
                                    }
                                }
                            }
                        }
                    }
                    if (allowArchiveSwipe) {
                    SwipeToDismissBox(state = dismiss, enableDismissFromStartToEnd = false, enableDismissFromEndToStart = allowArchiveSwipe && s.connected && !busy,
                        backgroundContent = { Box(Modifier.fillMaxSize().padding(start = 10.dp).background(if (dismiss.dismissDirection == SwipeToDismissBoxValue.Settled) Color.Transparent else MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(12.dp)).padding(end = 20.dp), contentAlignment = Alignment.CenterEnd) { Text(if (s.showArchived) "恢复" else "归档", color = Mint) } }) {
                        threadCard()
                    }
                    } else threadCard()
                }
                if (threads.isEmpty() && !s.threadsLoading && s.threadsError == null) item(key = "empty:${project.id}") { Text(if (search.isNotBlank() || filter != "all") "没有匹配的已加载会话" else if (s.showArchived) "暂无归档会话" else "还没有会话，开始一个新任务吧。", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 18.dp)) }
                if (s.project?.id == project.id && s.threadCursor != null) item(key = "more:${project.id}") { TextButton(onClick = { vm.loadThreads(true) }, enabled = !s.threadsLoading, modifier = Modifier.fillMaxWidth()) { Text("加载更多会话") } }
            }
        }
        if (s.projects.isEmpty() && !s.loading && !s.connecting) item(key = "emptyProjects") {
            Column(Modifier.fillMaxWidth().padding(vertical = 44.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.FolderOpen, null, Modifier.size(36.dp), tint = Muted)
                Text(if (s.connected) "还没有项目" else "连接你的电脑", style = MaterialTheme.typography.titleMedium)
                Text(if (s.connected) "点击创建项目，选择电脑上的已有目录。" else "连接后即可查看项目，继续会话。", color = Muted, fontSize = 14.sp)
                if (s.connected && vm.addProject != null) Button(onClick = { creatingProject = true }) { Text("创建项目") }
                if (!s.connected) Button(onClick = settings) { Text("设置连接") }
            }
        }
    }
    renaming?.let { thread ->
        AlertDialog(onDismissRequest = { renaming = null }, title = { Text("重命名会话") }, text = {
            OutlinedTextField(threadName, { threadName = it.take(120) }, label = { Text("会话名称") }, singleLine = true,
                supportingText = { Text("同步到电脑") })
        }, confirmButton = { TextButton(enabled = s.connected && threadName.isNotBlank(), onClick = {
            s.profile?.id?.let { vm.renameThread(it, thread.id, threadName) }; renaming = null
        }) { Text("保存") } }, dismissButton = { TextButton(onClick = { renaming = null }) { Text("取消") } })
    }
    if (sortingProjects) {
        AlertDialog(onDismissRequest = { sortingProjects = false }, title = { Text("调整项目顺序") }, text = {
            Column {
                Text("当前电脑的全部项目 · 调整后自动保存", fontSize = 12.sp, color = Muted)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                    items(s.projects, key = { it.id }) { project ->
                        val index = s.projects.indexOfFirst { it.id == project.id }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(project.name, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            IconButton(enabled = index > 0, onClick = { s.profile?.id?.let { vm.moveProject?.invoke(it, project.id, -1) } }) {
                                Icon(Icons.Outlined.ArrowUpward, "上移 ${project.name}")
                            }
                            IconButton(enabled = index < s.projects.lastIndex, onClick = { s.profile?.id?.let { vm.moveProject?.invoke(it, project.id, 1) } }) {
                                Icon(Icons.Outlined.ArrowDownward, "下移 ${project.name}")
                            }
                        }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { sortingProjects = false }) { Text("完成") } })
    }
    if (creatingProject) s.profile?.let { profile ->
        ProjectDirectoryDialog(profile.id, profile.connectionLabel(s.profiles), s.root, vm) { creatingProject = false }
    }
    projectInfo?.let { p -> AlertDialog(onDismissRequest = { projectInfo = null }, title = { Text(p.name) }, text = { SelectionContainer { Text(p.roots.joinToString("\n"), fontSize = 14.sp) } }, confirmButton = { TextButton(onClick = { projectInfo = null }) { Text("完成") } }, dismissButton = {
        if (vm.removeProject != null) TextButton(onClick = { projectInfo = null; removingProject = p }) { Text("移除项目") }
    }) }
    removingProject?.let { p -> AlertDialog(onDismissRequest = { removingProject = null }, title = { Text("移除 ${p.name}？") }, text = { Text("仅从 Orbit 项目列表解除关联，保留电脑上的目录、会话和已打开的标签。之后可重新选择该目录添加。") }, confirmButton = {
        TextButton(onClick = { s.profile?.id?.let { vm.removeProject?.invoke(it, p) }; removingProject = null }) { Text("移除") }
    }, dismissButton = { TextButton(onClick = { removingProject = null }) { Text("取消") } }) }
}

private fun threadDate(seconds: Long): String {
    if (seconds <= 0) return "随时继续"
    return SimpleDateFormat("MM 月 dd 日 · HH:mm", Locale.CHINA).format(Date(seconds * 1000))
}
