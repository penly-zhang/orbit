package dev.codexpocket

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.codexpocket.core.*

internal data class ComposerActions(
    val draft: (String) -> Unit, val send: () -> Unit, val stop: () -> Unit,
    val addImage: () -> Unit, val removeImage: (DraftImage) -> Unit,
    val model: (ModelOption) -> Unit, val effort: (EffortOption) -> Unit,
    val permission: (PermissionOption) -> Unit, val mode: (String) -> Unit, val refresh: () -> Unit,
    val refreshUsage: () -> Unit = {},
)

@Composable internal fun ComposerPermission(s: PocketState, actions: ComposerActions, onMenuVisibility: (Boolean) -> Unit = {}) {
    var menu by rememberSaveable(s.profile?.id, s.thread?.id) { mutableStateOf(false) }
    LaunchedEffect(menu) { onMenuVisibility(menu) }
    val current = s.settings
    val enabled = settingsEnabled(s)
    val full = current?.permissionId == ":danger-full-access" || current?.sandbox?.text("type") == "dangerFullAccess"
    val icon = when { full -> Icons.Outlined.WarningAmber; current?.reviewer == "auto_review" -> Icons.Outlined.VerifiedUser; current?.permissionId == ":read-only" || current?.sandbox?.text("type") == "readOnly" -> Icons.Outlined.Lock; else -> Icons.Outlined.BackHand }
    Box {
        IconButton(onClick = { menu = true }, enabled = enabled, modifier = Modifier.size(48.dp)) {
            Icon(icon, "选择权限：${current?.permissionLabel ?: if (s.thread == null) "创建会话后可调整" else "读取中"}", Modifier.size(25.dp), tint = if (full) Color(0xffb57d20) else Ink)
        }
        DropdownMenu(menu, { menu = false }, modifier = Modifier.widthIn(min = 280.dp, max = 320.dp).heightIn(max = 520.dp), shape = RoundedCornerShape(22.dp), containerColor = Panel) {
            s.permissions.sortedBy { when { it.id == ":workspace" && it.reviewer == "user" -> 0; it.id == ":workspace" -> 1; it.id == ":read-only" -> 2; it.id == ":danger-full-access" -> 3; else -> 4 } }.forEach { option ->
                val selected = permissionSelected(current, option)
                val optionIcon = when { option.id == ":danger-full-access" -> Icons.Outlined.WarningAmber; option.reviewer == "auto_review" -> Icons.Outlined.VerifiedUser; option.id == ":read-only" -> Icons.Outlined.Lock; else -> Icons.Outlined.BackHand }
                DropdownMenuItem(text = { Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(option.name, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Text(option.description + if (!option.allowed) " · 电脑策略不允许" else "", fontSize = 12.sp, lineHeight = 17.sp, color = Muted)
                } }, leadingIcon = { Icon(optionIcon, null, tint = if (option.id == ":danger-full-access") Color(0xffb57d20) else Ink) },
                    trailingIcon = { if (selected) Icon(Icons.Outlined.Check, "当前权限") }, enabled = enabled && option.allowed,
                    onClick = { actions.permission(option); menu = false }, modifier = Modifier.padding(vertical = 3.dp))
            }
            if (s.permissions.isEmpty()) DropdownMenuItem(text = { Text("刷新权限选项") }, onClick = actions.refresh, enabled = !s.choicesLoading)
        }
    }
}

@Composable internal fun ComposerModel(s: PocketState, actions: ComposerActions, modifier: Modifier = Modifier, onMenuVisibility: (Boolean) -> Unit = {}) {
    var menu by rememberSaveable(s.profile?.id, s.thread?.id) { mutableStateOf(false) }
    LaunchedEffect(menu) { onMenuVisibility(menu) }
    var page by rememberSaveable(s.profile?.id, s.thread?.id) { mutableStateOf("effort") }
    val current = s.settings
    val model = s.models.find { it.id == current?.model }
    val effort = current?.effort ?: model?.defaultEffort
    val enabled = settingsEnabled(s)
    val name = model?.name ?: current?.model?.takeIf { it.isNotBlank() } ?: if (s.thread == null) "默认模型" else "读取模型…"
    Box(modifier, contentAlignment = Alignment.CenterEnd) {
        TextButton(onClick = { page = "effort"; menu = true }, enabled = enabled, modifier = Modifier.heightIn(min = 40.dp)) {
            Text(if (LocalDensity.current.fontScale >= 1.5f) "模型" else "${compactModelName(name)} ${effortName(effort)}", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (s.settingsApplying) { Spacer(Modifier.width(6.dp)); CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 1.5.dp) }
        }
        DropdownMenu(menu, { menu = false }, modifier = Modifier.widthIn(min = 240.dp, max = 300.dp).heightIn(max = 540.dp), shape = RoundedCornerShape(22.dp), containerColor = Panel) {
            if (page != "effort") DropdownMenuItem(text = { Text(if (page == "model") "选择模型" else "工作模式", fontWeight = FontWeight.SemiBold) }, leadingIcon = { Icon(Icons.Outlined.ChevronLeft, "返回智能选项") }, onClick = { page = "effort" })
            when (page) {
                "model" -> {
                    s.models.forEach { option -> DropdownMenuItem(text = { Column { Text(option.name, fontSize = 15.sp); if (option.description.isNotBlank()) Text(option.description, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, color = Muted) } },
                        trailingIcon = { if (current?.model == option.id) Icon(Icons.Outlined.Check, "当前模型") }, enabled = enabled,
                        onClick = { actions.model(option); menu = false }) }
                    if (s.models.isEmpty()) DropdownMenuItem(text = { Text("刷新模型列表") }, onClick = actions.refresh, enabled = !s.choicesLoading)
                }
                "mode" -> s.collaborationModes.forEach { mode -> DropdownMenuItem(text = { Text(if (mode == "plan") "先做计划" else "直接执行") },
                    trailingIcon = { if ((current?.collaboration?.text("mode") ?: "default") == mode) Icon(Icons.Outlined.Check, "当前模式") }, enabled = enabled && s.activeTurn == null,
                    onClick = { actions.mode(mode); menu = false }) }
                else -> {
                    Text("智能", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp))
                    model?.efforts?.forEach { option -> DropdownMenuItem(text = { Text(effortName(option.id), fontSize = 17.sp) }, trailingIcon = { if (effort == option.id) Icon(Icons.Outlined.Check, "当前推理强度") }, enabled = enabled, onClick = { actions.effort(option); menu = false }) }
                    if (model?.efforts.isNullOrEmpty()) Text("当前模型未提供可选档位", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(16.dp))
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 5.dp))
                    DropdownMenuItem(text = { Column { Text("模型", fontWeight = FontWeight.SemiBold); Text(name, fontSize = 12.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis) } }, trailingIcon = { Icon(Icons.Outlined.ChevronRight, null) }, onClick = { page = "model" })
                    if (s.collaborationModes.isNotEmpty()) DropdownMenuItem(text = { Column { Text("工作模式", fontWeight = FontWeight.SemiBold); Text(if (current?.collaboration?.text("mode") == "plan") "先做计划" else "直接执行", color = Muted, fontSize = 12.sp) } }, trailingIcon = { Icon(Icons.Outlined.ChevronRight, null) }, enabled = s.activeTurn == null, onClick = { page = "mode" })
                }
            }
            if (s.choicesError != null) Text("部分选项读取失败，可刷新重试", color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            DropdownMenuItem(text = { Text("刷新可用选项", fontSize = 12.sp, color = Muted) }, enabled = !s.choicesLoading, onClick = actions.refresh)
        }
    }
}
private fun settingsEnabled(s: PocketState) = s.connected && s.threadReady && s.settings != null && !s.settingsApplying && !s.sending && !s.sendingComments
