package dev.codexpocket

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable internal fun ProductSettings(vm: PocketViewModel, state: PocketState) {
    val prefs by vm.preferences.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var help by remember { mutableStateOf(false) }
    var denied by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.updatePreferences { it.copy(notifications = granted) }; denied = !granted
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 14.dp)) {
        Text("外观与运行", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(start = 12.dp))
        Surface(color = Panel, shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("外观")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEach { (key, name) -> FilterChip(prefs.theme == key, { vm.updatePreferences { it.copy(theme = key) } }, label = { Text(name) }) }
                }
                HorizontalDivider()
                Row {
                    Column(Modifier.weight(1f)) { Text("后台维持连接"); Text("离开应用后保持任务同步，显示常驻通知。", fontSize = 12.sp, color = Muted) }
                    Switch(prefs.background, { checked -> vm.updatePreferences { it.copy(background = checked) } })
                }
                HorizontalDivider()
                Row {
                    Column(Modifier.weight(1f)) { Text("任务与审批提醒"); Text("提醒任务结束或待确认事项，不展示消息正文。", fontSize = 12.sp, color = Muted) }
                    Switch(prefs.notifications, { checked ->
                        if (checked && Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else vm.updatePreferences { it.copy(notifications = checked) }
                    })
                }
                if (denied) TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }) { Text("允许通知后可在系统设置中开启") }
                if (prefs.notifications && !prefs.background) Text("开启后台连接后，离开应用也可收到任务提醒。", fontSize = 12.sp, color = Muted)
            }
        }
        Surface(color = Panel, shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("帮助与诊断")
                TextButton(onClick = { help = !help }) { Text(if (help) "收起使用帮助" else "连接与使用帮助") }
                if (help) Text("电脑需启动所选 Agent 并开启 SSH，首次连接请核对指纹。多台电脑可同时连接；右上角＋可新建会话；导航页每条会话的…统一管理会话，长按数字标签也可打开同一菜单。后台连接可能增加耗电，系统强制停止后需重新打开。", color = Muted, fontSize = 13.sp)
                TextButton(onClick = { shareText(context, "Orbit 诊断", diagnosticReport(state, prefs)) }) { Text("分享诊断摘要（不含地址、凭据和消息）") }
                Text("标签、文字、附件和待发消息会保存到本机。关闭标签会清除草稿，归档会同步到电脑。", color = Muted, fontSize = 12.sp)
            }
        }
    }
}

internal fun diagnosticReport(s: PocketState, prefs: AppPreferences): String = buildString {
    appendLine("Orbit ${BuildConfig.VERSION_NAME}")
    appendLine("Android API ${Build.VERSION.SDK_INT}")
    appendLine("Background: ${prefs.background}; notifications: ${prefs.notifications}; theme: ${prefs.theme}")
    s.profiles.forEachIndexed { index, profile ->
        val status = s.connections[profile.id]
        appendLine("Host ${index + 1}: route=${profile.routeMode}; jumps=${profile.jumpHosts.size}; proxy=${profile.proxyEnabled}; enabled=${profile.autoConnect}; state=${status?.label}; retry=${status?.attempt ?: 0}")
    }
    appendLine("Open tabs: ${s.sessionTabs.size}; running: ${s.sessionTabs.count { it.running }}; pending approvals: ${s.pending.size}")
}
