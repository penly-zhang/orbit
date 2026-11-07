package dev.codexpocket

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

internal class TaskNotices(private val app: Application) {
    private val manager = app.getSystemService(NotificationManager::class.java)
    private val ids = mutableSetOf<Int>()
    init { manager.createNotificationChannel(NotificationChannel("tasks", "任务与审批", NotificationManager.IMPORTANCE_DEFAULT).apply { description = "任务完成和待处理审批；通知不显示消息正文" }) }
    private fun show(key: String, title: String, tab: SessionTab?) {
        if (Build.VERSION.SDK_INT >= 33 && app.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val id = key.hashCode()
        val intent = Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("sessionKey", tab?.key)
        val pending = PendingIntent.getActivity(app, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(id, Notification.Builder(app, "tasks").setSmallIcon(dev.codexpocket.R.drawable.ic_pocket).setContentTitle(title)
            .setContentText(if (tab == null) "打开 Orbit 处理" else "${tab.profileName} · 标签 ${tab.label}")
            .setVisibility(Notification.VISIBILITY_PRIVATE).setContentIntent(pending).setAutoCancel(true).build())
        ids += id
    }
    fun changed(before: PocketState, after: PocketState) {
        taskSignals(before, after).forEach { show(it.key, it.title, it.tab) }
    }
    fun clear() { ids.forEach(manager::cancel); ids.clear() }
}

/** Explicit, user-controlled persistent SSH session service. It never starts at boot. */
class ConnectionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("connections", "后台连接", NotificationManager.IMPORTANCE_LOW))
        show("正在维持 SSH 连接")
        scope.launch {
            PocketRuntime.get(application).controller.state.map { s ->
                "${s.connections.values.count { it.connected }} 台已连接 · ${s.sessionTabs.count { it.running }} 个任务运行中"
            }.distinctUntilChanged().collect { show(it) }
        }
    }
    private fun show(text: String) {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, ConnectionService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, "connections").setSmallIcon(R.drawable.ic_pocket).setContentTitle("Orbit · 后台连接")
            .setContentText(text).setContentIntent(open).setOngoing(true).setVisibility(Notification.VISIBILITY_PRIVATE)
            .addAction(Notification.Action.Builder(null, "关闭后台连接", stop).build()).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(42, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(42, notification)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { PocketRuntime.get(application).update { it.copy(background = false) }; stopSelf() }
        return START_NOT_STICKY
    }
    override fun onDestroy() { scope.cancel(); stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy() }
}
