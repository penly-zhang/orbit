package dev.codexpocket

import android.Manifest
import android.app.NotificationManager
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

@Composable internal fun TaskReminderHealth(vm: PocketViewModel) {
    val prefs by vm.preferences.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    fun allowed() = androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled() &&
        context.getSystemService(NotificationManager::class.java).getNotificationChannel("tasks")?.importance != NotificationManager.IMPORTANCE_NONE
    var systemAllowed by remember { mutableStateOf(allowed()) }
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) systemAllowed = allowed()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        systemAllowed = allowed()
        if (granted) vm.updatePreferences { it.copy(background = true, notifications = true) }
    }
    if (!prefs.background || !prefs.notifications || !systemAllowed) {
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Text("离开应用后提醒不可用；重新连接时会核对关注任务。", color = Muted, fontSize = 12.sp)
            TextButton(onClick = {
                if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                else if (!systemAllowed) context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                else vm.updatePreferences { it.copy(background = true, notifications = true) }
            }) { Text(if (!systemAllowed) "开启任务通知" else "开启后台同步与提醒") }
            Text("后台同步会显示常驻通知，可能增加耗电。", color = Muted, fontSize = 11.sp)
        }
    }
}

@Composable internal fun TaskBannerSettings() {
    val context = LocalContext.current
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    fun importance() = context.getSystemService(NotificationManager::class.java).getNotificationChannel("tasks")?.importance ?: NotificationManager.IMPORTANCE_NONE
    var level by remember { mutableStateOf(importance()) }
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) level = importance()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    Column {
        if (level < NotificationManager.IMPORTANCE_HIGH) Text("当前系统通知级别不支持横幅，请在系统设置中开启横幅或悬浮通知。", color = Muted, fontSize = 12.sp)
        TextButton(onClick = {
            context.startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, "tasks"))
        }) { Text("横幅、声音与振动设置") }
        TextButton(onClick = {
            context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        }) { Text("应用通知设置（含系统横幅开关）") }
        Text("电脑和后台连接需保持在线；强行停止应用后，需重新打开 Orbit 才能恢复提醒。", color = Muted, fontSize = 11.sp)
    }
}
