package dev.codexpocket

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable internal fun QueuedMessages(s: PocketState, vm: PocketViewModel) {
    var menu by remember(s.profile?.id, s.thread?.id) { mutableStateOf<String?>(null) }
    var editing by remember(s.profile?.id, s.thread?.id) { mutableStateOf<QueuedMessage?>(null) }
    var text by rememberSaveable { mutableStateOf("") }
    var resumeAfterEdit by remember { mutableStateOf(false) }
    fun closeEditor() { editing = null; if (resumeAfterEdit) vm.resumeQueuedMessages() }
    if (s.queuedMessages.isNotEmpty()) Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        if (s.queuePaused) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("待发队列已暂停 · ${s.queuedMessages.size} 条", fontSize = 12.sp, color = Muted, modifier = Modifier.weight(1f))
            TextButton(onClick = vm::resumeQueuedMessages, enabled = s.connected && s.threadReady && !s.sending && editing == null) { Text("继续发送") }
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 156.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(s.queuedMessages, key = { it.id }) { message ->
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                    Surface(color = Panel, shape = RoundedCornerShape(22.dp), border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)) {
                        Row(Modifier.widthIn(max = 320.dp).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (message.sending) CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 1.5.dp)
                            else Icon(Icons.Outlined.FormatListBulleted, "待发消息", Modifier.size(18.dp), tint = Muted)
                            Spacer(Modifier.width(8.dp))
                            Text(message.text.ifBlank { message.context.firstOrNull()?.label ?: "图片消息" }, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp, modifier = Modifier.weight(1f, fill = false))
                            if (message.images.isNotEmpty()) Text(" · ${message.images.size} 张图片", fontSize = 12.sp, color = Muted)
                            if (message.context.isNotEmpty()) Text(" · ${message.context.size} 项引用", fontSize = 12.sp, color = Muted)
                            Box {
                                IconButton(onClick = { menu = message.id }, enabled = !message.sending, modifier = Modifier.size(40.dp)) { Icon(Icons.Outlined.MoreHoriz, "待发消息操作", Modifier.size(21.dp)) }
                                DropdownMenu(menu == message.id, { menu = null }) {
                                    if (message.reviewComments.isNotEmpty()) DropdownMenuItem(text = { Text("返回评论检查") }, leadingIcon = { Icon(Icons.Outlined.RateReview, null) }, onClick = { menu = null; vm.restoreQueuedReview(message.id) })
                                    DropdownMenuItem(text = { Text("编辑消息") }, leadingIcon = { Icon(Icons.Outlined.Edit, null) }, onClick = {
                                        menu = null; resumeAfterEdit = !s.queuePaused; vm.pauseQueuedMessages(); text = message.text; editing = message
                                    })
                                    DropdownMenuItem(text = { Text("改为引导") }, leadingIcon = { Icon(Icons.Outlined.SubdirectoryArrowRight, null) },
                                        enabled = s.connected && s.threadReady && s.activeTurn != null && !s.sending && !s.settingsApplying,
                                        onClick = { menu = null; vm.steerQueuedMessage(message.id) })
                                    DropdownMenuItem(text = { Text("取消消息", color = MaterialTheme.colorScheme.error) }, leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
                                        onClick = { menu = null; vm.cancelQueuedMessage(message.id) })
                                }
                            }
                        }
                    }
                    message.error?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)) }
                }
            }
        }
    }
    editing?.let { message -> AlertDialog(onDismissRequest = ::closeEditor, title = { Text("编辑待发消息") }, text = {
        Column {
            OutlinedTextField(text, { text = it }, minLines = 2, maxLines = 6, modifier = Modifier.fillMaxWidth())
            if (message.context.isNotEmpty()) Text(message.context.joinToString("\n") { "引用：${it.label}" }, fontSize = 12.sp, color = Muted)
            if (message.images.isNotEmpty()) Text("保留 ${message.images.size} 张图片", fontSize = 12.sp, color = Muted)
            if (message.reviewComments.isNotEmpty()) Text("修改文字后会作为普通消息发送；核对代码位置可选择“返回评论检查”。", fontSize = 12.sp, color = Muted)
        }
    }, confirmButton = { TextButton(onClick = { vm.editQueuedMessage(message.id, text); closeEditor() }, enabled = text.isNotBlank() || message.images.isNotEmpty() || message.context.isNotEmpty()) { Text("保存") } },
        dismissButton = { TextButton(onClick = ::closeEditor) { Text("取消") } }) }
}
