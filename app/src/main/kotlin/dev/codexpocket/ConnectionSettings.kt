package dev.codexpocket

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.codexpocket.core.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable internal fun SettingsScreen(s: PocketState, vm: PocketViewModel, edit: (HostProfile) -> Unit, details: (HostProfile) -> Unit = edit) {
    var deletion by remember { mutableStateOf<HostProfile?>(null) }
    var menu by remember { mutableStateOf<String?>(null) }
    var managing by remember { mutableStateOf(false) }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("电脑与连接", color = Muted, modifier = Modifier.weight(1f))
                if (s.profiles.size > 1) TextButton(onClick = { managing = !managing }) { Text(if (managing) "完成排序" else "排序") }
            }
        }
        if (s.profiles.isEmpty()) item { Text("添加一台电脑，连接后即可在手机上继续工作。", color = Muted, fontSize = 14.sp) }
        itemsIndexed(s.profiles, key = { _, profile -> profile.id }) { index, profile ->
            val connection = s.connections[profile.id] ?: HostConnectionStatus()
            Surface(shape = MaterialTheme.shapes.large, color = Panel) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).clickable { details(profile) }.padding(vertical = 8.dp)) {
                            Text(profile.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (s.profiles.count { it.name == profile.name } > 1) Text(profile.host, fontSize = 12.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(connection.label, color = if (connection.connected) Mint else if (connection.connectionError != null) MaterialTheme.colorScheme.error else Muted, fontSize = 13.sp)
                            Text(if (profile.autoConnect) "自动连接已开启" else "手动连接", color = Muted, fontSize = 12.sp)
                        }
                        if (managing) {
                            IconButton(onClick = { vm.moveProfile(profile.id, -1) }, enabled = index > 0) { Icon(Icons.Outlined.KeyboardArrowUp, "上移 ${profile.name}") }
                            IconButton(onClick = { vm.moveProfile(profile.id, 1) }, enabled = index < s.profiles.lastIndex) { Icon(Icons.Outlined.KeyboardArrowDown, "下移 ${profile.name}") }
                        } else Box {
                            IconButton(onClick = { menu = profile.id }) { Icon(Icons.Outlined.MoreHoriz, "管理 ${profile.name}") }
                            DropdownMenu(menu == profile.id, { menu = null }) {
                                DropdownMenuItem(text = { Text("连接详情") }, onClick = { menu = null; details(profile) })
                                DropdownMenuItem(text = { Text("编辑配置") }, onClick = { menu = null; edit(profile) })
                                DropdownMenuItem(text = { Text("删除电脑") }, onClick = { menu = null; deletion = profile })
                            }
                        }
                    }
                    if (!managing) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (connection.connected) TextButton(onClick = { details(profile) }) { Text("查看详情") }
                        else if (connection.needsCredentials) TextButton(onClick = { edit(profile) }) { Text("填写登录信息") }
                        else if (connection.needsTrust) TextButton(onClick = { details(profile) }) { Text("核对指纹") }
                        else if (connection.connectionError != null) TextButton(onClick = { details(profile) }) { Text("处理连接问题") }
                        else if (!connection.connecting && !connection.reconnecting) TextButton(onClick = { if (!vm.connectNow(profile)) edit(profile) }) { Text("连接") }
                        if (connection.connected || connection.connecting || connection.reconnecting) TextButton(onClick = { vm.disconnectHost(profile) }) { Text(if (connection.connected) "断开" else "取消连接") }
                    }
                }
            }
        }
        item { OutlinedButton(onClick = { edit(HostProfile(name = "")) }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Add, null); Text("添加电脑") } }
        item { ProductSettings(vm, s) }
    }
    deletion?.let { profile -> AlertDialog(onDismissRequest = { deletion = null }, title = { Text("删除 ${profile.name}？") }, text = { Text("移除手机保存的连接和登录信息，不删除电脑上的项目。") }, confirmButton = { TextButton(onClick = { vm.deleteProfile(profile); deletion = null }) { Text("删除") } }, dismissButton = { TextButton(onClick = { deletion = null }) { Text("取消") } }) }
}

internal fun connectionRouteSummary(profile: HostProfile): String = buildList {
    if (profile.proxyEnabled) add("SOCKS5 代理")
    if (profile.jumpHosts.isNotEmpty()) add("${profile.jumpHosts.size} 个跳板")
    add(when (profile.routeMode) { "remoteSsh" -> "入口电脑上的 SSH 别名"; "loginShell" -> "登录后自动跳转"; else -> if (profile.jumpHosts.isEmpty()) "直接连接" else "连接目标电脑" })
}.joinToString(" · ")

@Composable internal fun ConnectionPreparation() {
    Text("1. 电脑开启 SSH（macOS 为“远程登录”），并保持唤醒。\n2. 手机能访问电脑所在网络；使用内网地址时需同一网络或已连接 VPN。\n3. 电脑安装并登录 Codex 或 traex；Orbit 会自动检测可用 Agent。\n\n用户名是电脑上的登录账户，可在电脑终端运行 whoami 查看。首次连接需核对电脑的 SSH 指纹。", color = Muted, fontSize = 13.sp, lineHeight = 21.sp)
}

@Composable internal fun ConnectionDetails(profile: HostProfile, status: HostConnectionStatus, vm: PocketViewModel, close: () -> Unit, edit: () -> Unit, start: () -> Unit) {
    val workspace by vm.state.collectAsStateWithLifecycle()
    var technical by remember { mutableStateOf(false) }
    var help by remember { mutableStateOf(false) }
    val busy = status.connecting || status.reconnecting
    val authenticationFailed = status.connectionError?.let { connectionAdvice(it).title == "SSH 登录未通过" } == true
    FullPage(profile.name.ifBlank { "连接详情" }, close, bottom = {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (status.connected && status.availableEngines.isNotEmpty()) Button(onClick = start, modifier = Modifier.fillMaxWidth()) { Text("开始新会话") }
            else if (status.needsCredentials || authenticationFailed) Button(onClick = edit, modifier = Modifier.fillMaxWidth()) { Text("填写登录信息") }
            else if (!status.needsTrust) Button(onClick = { if (!vm.connectNow(profile)) edit() }, enabled = !status.connecting, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "立即重试" else "连接并检查") }
            if (status.connected || busy || status.needsTrust) TextButton(onClick = { vm.disconnectHost(profile) }, modifier = Modifier.fillMaxWidth()) { Text(if (status.connected) "断开连接" else "取消连接") }
        }
    }) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(status.label, style = MaterialTheme.typography.headlineSmall, color = if (status.connected) Mint else Ink)
            if (busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("正在连接电脑、验证登录并检测 Agent…", color = Muted)
            }
            Text("${profile.username}@${profile.host}:${profile.port}", fontSize = 13.sp, color = Muted)
            Text(connectionRouteSummary(profile), fontSize = 13.sp, color = Muted)
            Surface(color = Panel, shape = MaterialTheme.shapes.medium) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("连接检查", style = MaterialTheme.typography.titleMedium)
                    Text("1. 连接与 SSH 登录 · " + when { status.connected -> "已通过"; status.needsTrust -> "等待核对指纹"; status.needsCredentials -> "需要登录信息"; busy -> "检查中"; else -> "尚未通过" })
                    Text("2. Agent 服务 · " + when { status.availableEngines.isNotEmpty() && status.connected -> "${status.availableEngines.size} 个可用"; status.connected -> "未检测到可用服务"; else -> "等待连接" })
                    val hostProjects = workspace.hostProjects.filter { it.profileId == profile.id }
                    Text("3. 工作项目 · " + if (!status.connected) "等待连接" else if (hostProjects.isEmpty()) "连接后选择或添加项目" else "${hostProjects.size} 个项目可选择")
                    Text("发送前仍需确认目标项目与 Agent。", fontSize = 12.sp, color = Muted)
                }
            }
            if (status.manuallyPaused) Text("已手动断开。本次运行中暂停自动连接，点击连接可恢复。", color = Muted, fontSize = 13.sp)
            if (status.needsCredentials) Text("请补充这台电脑或跳板的密码、私钥后再连接。", color = Muted)
            if (status.needsTrust) Text("请在指纹确认窗口中核对电脑身份；只有指纹一致时才继续连接。", color = Muted)
            status.connectionError?.takeIf { !status.connected }?.let { message ->
                val advice = connectionAdvice(message)
                Text(advice.title, style = MaterialTheme.typography.titleMedium)
                Text(advice.action, color = Muted, fontSize = 14.sp)
                if (status.reconnecting) Text("正在自动重试 · 第 ${status.attempt} 次", color = Muted, fontSize = 12.sp)
                TextButton(onClick = { technical = !technical }) { Text(if (technical) "收起错误详情" else "查看错误详情") }
                if (technical) SelectionContainer { Text(message, fontSize = 12.sp) }
            }
            if (status.connected) {
                Text("可用 Agent", style = MaterialTheme.typography.titleMedium)
                status.availableEngines.forEach { Text("✓ ${agentLabel(it)}", color = Mint) }
                if (status.availableEngines.isEmpty()) Text("尚未检测到可用 Agent，请检查电脑上的安装与登录状态。", color = Muted)
                status.engineIssues.forEach { (engine, issue) ->
                    Text("${agentLabel(engine)} 暂不可用", style = MaterialTheme.typography.titleSmall)
                    Text(issue, fontSize = 12.sp, color = Muted)
                }
                TextButton(onClick = { if (!vm.connectNow(profile)) edit() }) { Text("重新连接并检测 Agent") }
            }
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("自动连接"); Text("启动应用时连接，意外断线后重试。修改此项不立即连接或断开。", color = Muted, fontSize = 12.sp) }
                Switch(profile.autoConnect, { vm.setAutoConnect(profile, it) })
            }
            OutlinedButton(onClick = edit, modifier = Modifier.fillMaxWidth()) { Text("编辑配置") }
            TextButton(onClick = { help = !help }) { Text(if (help) "收起准备说明" else "电脑准备说明") }
            if (help) ConnectionPreparation()
        }
    }
}
