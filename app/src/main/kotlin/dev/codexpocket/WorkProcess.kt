package dev.codexpocket

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.codexpocket.core.*

/** Tool summaries stay in the conversation; details have their own scrollable sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun WorkGroup(entry: WorkEntry, cwd: String, session: String, loader: ConversationImageLoader, turnItems: List<ChatItem> = entry.items, turnRunning: Boolean = entry.active) {
    // Changing from a running turn to a finished turn must reset a manually expanded process too.
    var expanded by rememberSaveable(entry.id, turnRunning) { mutableStateOf(turnRunning) }
    var showSteps by rememberSaveable(entry.id) { mutableStateOf(false) }
    var commandIds by rememberSaveable(entry.id) { mutableStateOf<List<String>>(emptyList()) }
    var selectedId by rememberSaveable(entry.id) { mutableStateOf<String?>(null) }
    var visibleCount by rememberSaveable(entry.id) { mutableIntStateOf(30) }
    val steps = remember(turnItems, entry.items) {
        turnItems.filter { it.kind == "reasoning" && it.text.isNotBlank() }.ifEmpty { entry.items }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 10.dp).semantics { contentDescription = if (expanded) "收起工作过程" else "展开工作过程" },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (entry.active) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp)
            Text(workGroupTitle(turnItems), color = Muted, fontSize = 15.sp, lineHeight = 25.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, Modifier.size(18.dp), tint = Muted)
        }
        if (expanded) {
            val groups = remember(entry.items) { workDisplayGroups(entry.items) }
            // Live output must include the tail even after a turn exceeds the history page size.
            val visibleGroups = if (turnRunning) groups else groups.take(visibleCount)
            visibleGroups.forEach { group -> key(group.first().id) {
                val item = group.first()
                if (item.kind == "agentMessage") ConversationBody(item.text, item.images, cwd, session, loader)
                else WorkSummary(item, group.size, group.any { it.status in setOf("failed", "declined") }) {
                    if (item.kind == "reasoning") showSteps = true
                    else if (group.size > 1) commandIds = group.map { it.id }
                    else selectedId = item.id
                }
            } }
            if (!turnRunning && groups.size > visibleCount) TextButton(onClick = { visibleCount += 30 }) { Text("再显示 30 条工作记录") }
            TextButton(onClick = { showSteps = true }, contentPadding = PaddingValues(0.dp)) {
                Text("查看执行步骤", color = Muted, fontSize = 13.sp)
            }
        }
    }
    if (showSteps) ModalBottomSheet(onDismissRequest = { showSteps = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Text(steps.lastOrNull { it.kind == "reasoning" }?.let(::workItemTitle) ?: "执行步骤", style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(horizontal = 24.dp).padding(bottom = 20.dp).semantics { contentDescription = "执行步骤" })
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 580.dp), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 28.dp)) {
            items(steps, key = { it.id }) { item ->
                val lineColor = MaterialTheme.colorScheme.outlineVariant
                Row(Modifier.fillMaxWidth().clickable { showSteps = false; selectedId = item.id }) {
                    Box(Modifier.width(22.dp).heightIn(min = 72.dp).drawBehind {
                        if (item.id != steps.last().id) drawLine(lineColor, Offset(5.dp.toPx(), 23.dp.toPx()), Offset(5.dp.toPx(), size.height), 1.dp.toPx())
                    }) { Text("•", fontSize = 22.sp, lineHeight = 26.sp) }
                    Column(Modifier.weight(1f).padding(start = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(workItemTitle(item), fontSize = 16.sp, lineHeight = 26.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Text(workStatus(item), fontSize = 13.sp, lineHeight = 21.sp, color = if (item.status in setOf("failed", "declined")) MaterialTheme.colorScheme.error else Muted)
                    }
                }
            }
        }
    }
    (turnItems + entry.items).find { it.id == selectedId }?.let { item ->
        WorkDetailSheet(item, cwd, session, loader) { selectedId = null }
    }
    if (commandIds.isNotEmpty()) ModalBottomSheet(onDismissRequest = { commandIds = emptyList() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Text("执行 ${commandIds.size} 条命令", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 580.dp), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            items(entry.items.filter { it.id in commandIds }, key = { it.id }) { item ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CommandCard(item)
                    Text(workStatus(item), color = if (item.status in setOf("failed", "declined")) MaterialTheme.colorScheme.error else Muted, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable private fun WorkSummary(item: ChatItem, count: Int = 1, failed: Boolean = item.status in setOf("failed", "declined"), open: () -> Unit) {
    val icon = when (item.kind) {
        "commandExecution" -> Icons.Outlined.Terminal
        "reasoning" -> Icons.Outlined.Lightbulb
        "webSearch" -> Icons.Outlined.Search
        "fileChange" -> Icons.Outlined.Description
        "plan" -> Icons.Outlined.Checklist
        else -> Icons.Outlined.Build
    }
    Row(Modifier.fillMaxWidth().clickable(onClick = open).padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Icon(icon, null, Modifier.padding(top = 3.dp).size(18.dp), tint = Muted)
        Text(if (item.kind == "commandExecution") (if (count > 1) "执行 $count 条命令" else "命令执行") else workItemTitle(item),
            fontSize = 15.sp, lineHeight = 25.sp, color = Muted, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (failed) Icon(Icons.Outlined.ErrorOutline, "执行未完成", Modifier.padding(top = 3.dp).size(18.dp), tint = MaterialTheme.colorScheme.error)
    }
}

private fun workStatus(item: ChatItem) = when (item.status) {
    "inProgress", "running" -> "执行中"
    "failed" -> "执行失败"
    "declined" -> "已拒绝"
    "completed" -> "已完成"
    else -> "执行记录"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun WorkDetailSheet(item: ChatItem, cwd: String, session: String, loader: ConversationImageLoader, dismiss: () -> Unit) {
    var outputLimit by rememberSaveable(item.id) { mutableIntStateOf(12000) }
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().heightIn(max = 620.dp).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(when (item.kind) { "commandExecution" -> "执行命令"; "reasoning" -> "执行说明"; else -> workItemTitle(item) },
                fontSize = 18.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)
            if (item.kind == "commandExecution") {
                CommandCard(item)
            } else if (item.kind in setOf("reasoning", "plan") || item.images.isNotEmpty()) {
                ConversationBody(item.text, item.images, cwd, session, loader)
            } else {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(18.dp)) {
                    Column { CopyableWorkText(item.text.take(outputLimit), item.text, "详情")
                        if (item.text.length > outputLimit) TextButton(onClick = { outputLimit += 12000 }) { Text("显示更多详情") }
                    }
                }
            }
            Text(workStatus(item), fontSize = 13.sp, lineHeight = 21.sp, color = if (item.status in setOf("failed", "declined")) MaterialTheme.colorScheme.error else Muted)
        }
    }
}

@Composable private fun CommandCard(item: ChatItem) {
    var outputLimit by rememberSaveable(item.id) { mutableIntStateOf(12000) }
    val command = item.command.ifBlank { item.text.substringBefore('\n').removePrefix("$ ") }
    val output = commandOutput(item)
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(18.dp), border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.fillMaxWidth()) {
            CopyableWorkText(command, command, "命令", syntax = true)
            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
            if (output.isNotBlank()) {
                CopyableWorkText(output.take(outputLimit), output, "输出")
                if (output.length > outputLimit) TextButton(onClick = { outputLimit += 12000 }) { Text("显示更多输出") }
            } else Text(if (item.status in setOf("inProgress", "running")) "等待命令输出…" else "无输出", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(16.dp))
        }
    }
}

@Composable private fun CopyableWorkText(display: String, full: String, label: String, syntax: Boolean = false) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 10.dp, top = 8.dp, bottom = 16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 12.sp, color = Muted, modifier = Modifier.weight(1f))
            IconButton(onClick = {
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, full))
                Toast.makeText(context, "已复制$label", Toast.LENGTH_SHORT).show()
            }, modifier = Modifier.size(36.dp)) { Icon(Icons.Outlined.ContentCopy, "复制$label", Modifier.size(20.dp)) }
        }
        val highlighted = if (syntax) shellSyntax(display) else AnnotatedString(display)
        SelectionContainer { Text(highlighted, fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 22.sp,
            softWrap = true, modifier = Modifier.fillMaxWidth().padding(end = 6.dp)) }
    }
}

@Composable private fun shellSyntax(text: String): AnnotatedString {
    val colors = MaterialTheme.colorScheme
    return remember(text, colors) { buildAnnotatedString {
        append(text)
        Regex("\"[^\"\\n]*\"|'[^'\\n]*'|(?<!\\S)--?[\\w-]+|\\b\\d+\\b").findAll(text).forEach { token ->
            addStyle(SpanStyle(color = if (token.value.startsWith('-')) colors.tertiary else colors.primary), token.range.first, token.range.last + 1)
        }
        Regex("(?m)^\\s*([\\w./-]+)").findAll(text).forEach { token ->
            val range = token.groups[1]!!.range
            addStyle(SpanStyle(color = colors.tertiary, fontWeight = FontWeight.Medium), range.first, range.last + 1)
        }
    } }
}
