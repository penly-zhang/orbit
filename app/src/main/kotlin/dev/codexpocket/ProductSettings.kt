package dev.codexpocket

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
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
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    var help by remember { mutableStateOf(false) }
    var dataHelp by remember { mutableStateOf(false) }
    fun notificationsAllowed(): Boolean {
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        return androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            manager.getNotificationChannel("tasks")?.importance != android.app.NotificationManager.IMPORTANCE_NONE
    }
    var systemNotifications by remember { mutableStateOf(notificationsAllowed()) }
    DisposableEffect(lifecycle, context) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) systemNotifications = notificationsAllowed()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        systemNotifications = notificationsAllowed()
        if (granted) vm.updatePreferences { it.copy(notifications = true, background = true) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 10.dp)) {
        Text("后台与通知", color = Muted, fontSize = 14.sp)
        Surface(color = Panel, shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row {
                    Column(Modifier.weight(1f)) { Text("后台保持同步"); Text("离开应用后维持连接，并显示常驻通知。可能增加耗电。", fontSize = 12.sp, color = Muted) }
                    Switch(prefs.background, { checked -> vm.updatePreferences { it.copy(background = checked) } })
                }
                if (!prefs.background) Text("离开应用约 30 秒后暂停手机连接；这不是停止电脑任务的操作，任务是否继续取决于 Agent 和电脑状态。", fontSize = 12.sp, color = Muted)
                HorizontalDivider()
                Row {
                    Column(Modifier.weight(1f)) { Text("任务与审批提醒"); Text("离开 Orbit 后，任务结束、待确认或待回答时弹出系统横幅。开启时同时启用后台同步，不展示消息正文。", fontSize = 12.sp, color = Muted) }
                    Switch(prefs.notifications, { checked ->
                        vm.updatePreferences { it.copy(notifications = checked, background = if (checked) true else it.background) }
                        if (checked && Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    })
                }
                if (!systemNotifications) {
                    Text("系统任务通知已关闭${if (prefs.notifications) "，任务提醒暂不生效" else ""}。", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }) { Text("打开系统通知设置") }
                } else if (prefs.notifications) Text(if (prefs.background) "已允许系统通知，后台连接期间可接收提醒。" else "后台同步已关闭，离开应用后无法持续接收提醒。", fontSize = 12.sp, color = Muted)
                if (prefs.notifications && !prefs.background) TextButton(onClick = { vm.updatePreferences { it.copy(background = true) } }) { Text("开启后台同步") }
                TaskBannerSettings()
            }
        }
        Text("外观", color = Muted, fontSize = 14.sp)
        Surface(color = Panel, shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEach { (key, name) ->
                    Row(Modifier.fillMaxWidth().clickable { vm.updatePreferences { it.copy(theme = key) } }, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        RadioButton(prefs.theme == key, { vm.updatePreferences { it.copy(theme = key) } }); Text(name)
                    }
                }
            }
        }
        Text("帮助与关于", color = Muted, fontSize = 14.sp)
        Surface(color = Panel, shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { help = !help }) { Text(if (help) "收起连接帮助" else "连接与使用帮助") }
                if (help) {
                    ConnectionPreparation()
                    Text("多台电脑可同时连接，在新会话中选择电脑、项目和 Agent。手动断开会暂停本次运行中的自动连接。系统强制停止应用后，需要重新打开才能恢复同步。", color = Muted, fontSize = 13.sp)
                }
                TextButton(onClick = { shareText(context, "Orbit 诊断", diagnosticReport(state, prefs)) }) { Text("分享诊断摘要") }
                Text("诊断不含地址、凭据或消息内容。", color = Muted, fontSize = 12.sp)
                TextButton(onClick = { dataHelp = !dataHelp }) { Text(if (dataHelp) "收起数据保存说明" else "数据保存说明") }
                if (dataHelp) Text("标签、文字、附件和待发消息保存在本机。关闭标签会清除草稿，归档会同步到电脑。登录信息仅在勾选保存时加密存于本机；删除电脑会移除对应的连接和登录信息。", color = Muted, fontSize = 12.sp)
                Text("Orbit · ${BuildConfig.VERSION_NAME}", color = Muted, fontSize = 12.sp)
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
