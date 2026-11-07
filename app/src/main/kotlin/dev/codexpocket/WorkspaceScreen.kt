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
    var projectInfo by remember { mutableStateOf<ProjectInfo?>(null) }
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
                                    Text(listOfNotNull(if (running) "运行中" else threadDate(thread.updated), "草稿".takeIf { tab?.hasDraft == true }, "未读".takeIf { tab?.unread == true }).joinToString(" · "), fontSize = 12.sp, color = if (running) Mint else Muted)
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
                Text(if (s.connected) "在电脑上的 Agent 中添加项目或创建会话后刷新。" else "连接后即可查看项目，继续会话。", color = Muted, fontSize = 14.sp)
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
    projectInfo?.let { p -> AlertDialog(onDismissRequest = { projectInfo = null }, title = { Text(p.name) }, text = { androidx.compose.foundation.text.selection.SelectionContainer { Text(p.roots.joinToString("\n"), fontSize = 14.sp) } }, confirmButton = { TextButton(onClick = { projectInfo = null }) { Text("完成") } }) }
}

private fun threadDate(seconds: Long): String {
    if (seconds <= 0) return "随时继续"
    return SimpleDateFormat("MM 月 dd 日 · HH:mm", Locale.CHINA).format(Date(seconds * 1000))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable internal fun SettingsScreen(s: PocketState, vm: PocketViewModel, edit: (HostProfile) -> Unit) {
    var deletion by remember { mutableStateOf<HostProfile?>(null) }
    var menu by remember { mutableStateOf<String?>(null) }
    var issue by remember { mutableStateOf<HostProfile?>(null) }
    LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)) {
        item {
            Text("连接", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, bottom = 10.dp))
        }
        items(s.profiles, key = { it.id }) { profile ->
            val connection = s.connections[profile.id] ?: HostConnectionStatus()
            val first = s.profiles.firstOrNull()?.id == profile.id
            val busy = connection.connecting || connection.reconnecting
            val status = if (!profile.autoConnect) "已关闭" else connection.label
            Surface(color = Panel, shape = RoundedCornerShape(topStart = if (first) 24.dp else 0.dp, topEnd = if (first) 24.dp else 0.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).combinedClickable(onClick = { edit(profile) }, onLongClick = { menu = profile.id }).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Outlined.DragHandle, "拖动调整连接顺序", tint = Ink, modifier = Modifier.size(48.dp).pointerInput(profile.id) {
                        var drag = 0f
                        detectVerticalDragGestures(onDragStart = { drag = 0f }, onDragEnd = { drag = 0f }, onDragCancel = { drag = 0f }) { change, delta ->
                            change.consume(); drag += delta
                            if (kotlin.math.abs(drag) > 64.dp.toPx()) { vm.moveProfile(profile.id, if (drag > 0) 1 else -1); drag = 0f }
                        }
                    })
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(profile.name, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (s.profiles.count { it.name == profile.name } > 1) Text(profile.host, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (busy) Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                    Box {
                        if (!busy) Text(status, color = if (connection.error != null) MaterialTheme.colorScheme.error else Muted, fontSize = 12.sp, modifier = Modifier.clickable(enabled = connection.error != null || connection.needsCredentials || connection.needsTrust) { issue = profile }.padding(vertical = 12.dp))
                        DropdownMenu(expanded = menu == profile.id, onDismissRequest = { menu = null }) {
                            DropdownMenuItem(text = { Text(profile.name, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }, onClick = {}, enabled = false)
                            DropdownMenuItem(text = { Text("编辑") }, leadingIcon = { Icon(Icons.Outlined.Edit, null) }, onClick = { menu = null; edit(profile) })
                            DropdownMenuItem(text = { Text(if (profile.autoConnect) "断开连接" else "连接") }, leadingIcon = { Icon(Icons.Outlined.PowerSettingsNew, null) }, onClick = {
                                menu = null
                                if (!vm.setConnectionEnabled(profile, !profile.autoConnect)) edit(profile.copy(autoConnect = true))
                            })
                            DropdownMenuItem(text = { Text("删除连接") }, leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null) }, onClick = { menu = null; deletion = profile })
                        }
                    }
                    Switch(profile.autoConnect, { enabled -> if (!vm.setConnectionEnabled(profile, enabled) && enabled) edit(profile.copy(autoConnect = true)) }, modifier = Modifier.semantics { contentDescription = "${profile.name} 连接开关" },
                        colors = SwitchDefaults.colors(checkedTrackColor = Mint, checkedThumbColor = MaterialTheme.colorScheme.onPrimary, uncheckedBorderColor = MaterialTheme.colorScheme.outlineVariant))
                }
            }
        }
        item {
            Surface(onClick = { edit(HostProfile()) }, color = Panel, shape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp,
                topStart = if (s.profiles.isEmpty()) 24.dp else 0.dp, topEnd = if (s.profiles.isEmpty()) 24.dp else 0.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Text("添加连接", fontSize = 16.sp, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 20.dp))
            }
        }
        item { ProductSettings(vm, s) }
        item {
            Text("开关开启后自动连接并重连。点击编辑，长按管理，拖动左侧调整顺序。", fontSize = 12.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp))
            Text("ORBIT  ·  ${BuildConfig.VERSION_NAME}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 12.dp, top = 8.dp))
        }
    }
    issue?.let { profile ->
        val message = s.connections[profile.id]?.error.orEmpty()
        val advice = connectionAdvice(message)
        AlertDialog(onDismissRequest = { issue = null }, title = { Text("${profile.name} · 连接问题") }, text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(advice.title); Text(advice.action, fontSize = 13.sp, color = Muted)
            if (message.isNotBlank()) SelectionContainer { Text(message, fontSize = 12.sp, color = Muted) }
        } }, confirmButton = { TextButton(onClick = { issue = null; edit(profile) }) { Text("编辑连接") } }, dismissButton = { TextButton(onClick = { issue = null; vm.reconnect(profile) }) { Text("重新连接") } })
    }
    deletion?.let { profile -> AlertDialog(onDismissRequest = { deletion = null }, title = { Text("删除 ${profile.name}？") }, text = { Text("将移除手机保存的连接和登录信息。") }, confirmButton = { TextButton(onClick = { vm.deleteProfile(profile); deletion = null }) { Text("删除") } }, dismissButton = { TextButton(onClick = { deletion = null }) { Text("取消") } }) }
}
