package dev.codexpocket

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.codexpocket.core.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch


@Composable internal fun Conversation(s: PocketState, vm: PocketViewModel, searching: Boolean, closeSearch: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var match by rememberSaveable { mutableIntStateOf(0) }
    val entries = remember(s.items, s.activeTurn) { conversationTimeline(s.items, s.activeTurn) }
    val turnItems = remember(s.items) { s.items.groupBy { it.turnId } }
    val imageSession = "${s.profile?.id}:${s.thread?.id}:${s.connected}:${s.cached}"
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val picker = LocalImagePicker.current
    val selectedTab = s.sessionTabs.find { it.profileId == s.profile?.id && it.thread.id == s.thread?.id }
    var following by rememberSaveable(s.profile?.id, s.thread?.id) { mutableStateOf(true) }
    LaunchedEffect(listState, s.thread?.id) {
        snapshotFlow { listState.isScrollInProgress to (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) }
            .distinctUntilChanged().collect { (scrolling, last) -> if (scrolling) following = last >= listState.layoutInfo.totalItemsCount - 2 }
    }
    LaunchedEffect(s.items.lastOrNull()?.text, s.items.size, entries.size) {
        if (following && !listState.isScrollInProgress) {
            withFrameNanos { }
            if (listState.layoutInfo.totalItemsCount > 0) listState.scrollToItem(listState.layoutInfo.totalItemsCount - 1)
        }
    }
    CompositionLocalProvider(LocalOpenProjectFile provides { link -> s.profile?.id?.let { vm.openLink(it, link, s.root) } }, LocalArtifactContext provides s.profile?.id?.let { ArtifactContext(it, s.root, vm) }) {
     Column(Modifier.fillMaxSize().clipToBounds().background(Paper)) {
        val matches = remember(entries, query) { if (query.isBlank()) emptyList() else entries.mapIndexedNotNull { index, entry ->
            val text = when (entry) { is MessageEntry -> entry.item.text; is WorkEntry -> entry.items.joinToString("\n") { it.text + "\n" + it.command } }
            index.takeIf { text.contains(query.trim(), ignoreCase = true) }
        } }
        LaunchedEffect(matches, match, searching) {
            if (searching && matches.isNotEmpty()) { following = false; listState.animateScrollToItem(matches[match.mod(matches.size)] + if (s.historyCursor != null) 1 else 0) }
        }
        if (searching) Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(query, { query = it; match = 0 }, singleLine = true, placeholder = { Text("查找已加载消息") }, modifier = Modifier.weight(1f))
            Text(if (matches.isEmpty()) "0" else "${match.mod(matches.size) + 1}/${matches.size}", fontSize = 12.sp, modifier = Modifier.padding(horizontal = 6.dp))
            IconButton(onClick = { match = (match - 1).mod(matches.size.coerceAtLeast(1)) }, enabled = matches.isNotEmpty()) { Icon(Icons.Outlined.KeyboardArrowUp, "上一处") }
            IconButton(onClick = { match = (match + 1).mod(matches.size.coerceAtLeast(1)) }, enabled = matches.isNotEmpty()) { Icon(Icons.Outlined.KeyboardArrowDown, "下一处") }
            IconButton(onClick = closeSearch) { Icon(Icons.Outlined.Close, "关闭消息搜索") }
        }

        if (!s.connected) {
            val computer = s.profile?.connectionLabel(s.profiles).orEmpty().ifBlank { "电脑" }
            val status = if (s.connecting || s.reconnecting) "正在重新连接" else "连接已断开"
            Text("$computer · $status，当前会话内容和草稿已保留", fontSize = 12.sp, color = Muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
        }
        else if (s.threadSyncError != null) Box(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            StateNotice("此会话暂不可接续", s.threadSyncError, "重试", vm::refreshThread, failure = true)
        }
        else if (s.attaching || s.cached) Text(if (s.attaching) "正在接续会话…" else "正在更新最近消息…", fontSize = 12.sp, color = Muted,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        // Lazy items (including selectable text and previews) must stay inside the message viewport.
        // Column lays out siblings but does not clip drawing over the tabs, status or composer.
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().sessionSwipe(s.thread?.id, vm::switchSession)) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                if (s.historyCursor != null) item(key = "history") { TextButton(onClick = { following = false; vm.moreHistory() }, enabled = !s.loading, modifier = Modifier.fillMaxWidth()) { Text("加载更早消息") } }
                if (entries.isEmpty() && !s.loading && !s.attaching) item(key = "empty") {
                    Column(Modifier.fillMaxWidth().padding(top = 90.dp, bottom = 40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Outlined.Terminal, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                        Text("开始一个新任务", fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
                        Text("想在 ${s.project?.name.orEmpty()} 中做什么？", color = Muted, fontSize = 14.sp)
                    }
                }
                items(entries, key = { it.id }) { entry -> when (entry) {
                    is MessageEntry -> ConversationMessage(entry.item, s.root, imageSession, vm.imageLoader)
                    is WorkEntry -> WorkGroup(entry, s.root, imageSession, vm.imageLoader, turnItems[entry.turnId].orEmpty().ifEmpty { entry.items }, turnRunning = entry.turnId == s.activeTurn)
                } }
                if (s.activeTurn != null && entries.none { it is WorkEntry && it.active }) item(key = "working") {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 1.5.dp)
                        Text("正在工作…", fontSize = 13.sp, color = Muted)
                    }
                }
            }
            if (!following && entries.isNotEmpty()) FilledTonalIconButton(onClick = {
                following = true
                scope.launch { listState.animateScrollToItem((listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)) }
            }, modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp)) { Icon(Icons.Outlined.ArrowDownward, "回到最新消息") }
        }
        ConversationChanges(s, vm)
        QueuedMessages(s, vm)
        MessageComposer(s, ComposerActions(
            draft = vm::draft, send = vm::send, stop = vm::stop,
            addImage = { selectedTab?.let { picker(it.profileId, it.number) } },
            removeImage = { image -> selectedTab?.let { vm.removeDraftImage(it.profileId, it.number, image.id) } },
            model = vm::chooseModel, effort = vm::chooseEffort, permission = vm::choosePermission, mode = vm::chooseCollaboration, refresh = vm::refreshChoices, refreshUsage = vm::refreshUsage,
        ), vm.sendCompleted, selectedTab?.let { tab -> composerContextActions(vm, s, tab, LocalDocumentPicker.current) })
     }
    }
}

@Composable internal fun ConversationMessage(item: ChatItem, cwd: String, session: String, loader: ConversationImageLoader) {
    if (item.kind == "userMessage") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(24.dp), modifier = Modifier.widthIn(max = 340.dp)) {
                Box(Modifier.padding(horizontal = 14.dp, vertical = 13.dp)) {
                    SelectionContainer { ConversationBody(displayUserText(item.text, item.images), item.images, cwd, session, loader, markdown = false) }
                }
            }
        }
    } else {
        val context = LocalContext.current
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ConversationBody(item.text, item.images, cwd, session, loader)
            if (item.text.isNotBlank() && item.phase != "commentary") IconButton(onClick = {
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Orbit", item.text))
                Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
            }, modifier = Modifier.size(32.dp)) { Icon(Icons.Outlined.ContentCopy, "复制回复", Modifier.size(18.dp), tint = Muted) }
        }
    }
}

