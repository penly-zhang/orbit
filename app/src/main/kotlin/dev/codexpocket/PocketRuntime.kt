package dev.codexpocket

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

@Serializable data class QuickPrompt(val id: String, val title: String, val text: String)
@Serializable data class AppPreferences(val theme: String = "system", val background: Boolean = false, val notifications: Boolean = false,
    val prompts: List<QuickPrompt> = listOf(QuickPrompt("review", "检查改动", "请审查当前未提交的改动，优先指出缺陷、回归风险和缺失的验证。"), QuickPrompt("explain", "解释项目", "请解释这个项目的结构、主要流程和本地运行方式。")))

/** Application lifetime keeps connections independent of Activity recreation and navigation. */
internal class PocketRuntime private constructor(val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val vault = Vault(app)
    val controller = PocketController(vault, scope)
    val preferences = MutableStateFlow(runCatching { vault.get("preferences:v1")?.let { dev.codexpocket.core.json.decodeFromString<AppPreferences>(it) } }.getOrNull() ?: AppPreferences())
    var visible = false; private set
    private val notices = TaskNotices(app)
    private val main = Handler(Looper.getMainLooper())
    private var previous: PocketState? = null
    private var backgroundGrace: Job? = null
    init {
        scope.launch(Dispatchers.IO) {
            val saved = runCatching { vault.get("workspace:v1")?.let { dev.codexpocket.core.json.decodeFromString<SavedWorkspace>(it) } }.getOrNull()
            val newTask = runCatching { vault.get("newTaskAttachments:v1")?.let { dev.codexpocket.core.json.decodeFromString<NewTaskAttachments>(it) } }.getOrNull()
            val newTaskPaths = newTask?.let { it.images.map { image -> image.path } + it.context.mapNotNull { item -> item.localPath } }.orEmpty()
            val retained = newTaskPaths.toSet() + saved?.hosts.orEmpty().flatMap { it.tabs }.flatMap { tab -> (tab.images + tab.queuedMessages.flatMap { it.images }).map { it.path } + (tab.context + tab.queuedMessages.flatMap { it.context }).mapNotNull { it.localPath } }.toSet()
            listOf("composer-images", "composer-files").flatMap { java.io.File(app.filesDir, it).listFiles().orEmpty().toList() }.filter { it.isFile && it.absolutePath !in retained && System.currentTimeMillis() - it.lastModified() > 24 * 60 * 60 * 1000L }.forEach { it.delete() }
        }
        val connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        runCatching { connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { main.post { if (visible || preferences.value.background) controller.resumeConnections() } }
        }) }
        scope.launch { controller.state.collect { state ->
            val before = previous
            if (before != null && preferences.value.notifications && !visible) notices.changed(before, state)
            previous = state
        } }
    }
    fun update(change: (AppPreferences) -> AppPreferences) {
        val value = change(preferences.value)
        vault.put("preferences:v1", dev.codexpocket.core.json.encodeToString(value))
        preferences.value = value
        if (!value.background) { app.stopService(Intent(app, ConnectionService::class.java)); if (!visible) controller.pauseConnections() }
        else if (visible) startConnectionService()
    }
    fun foreground(value: Boolean) {
        val leaving = visible && !value
        visible = value
        if (leaving && preferences.value.notifications) {
            val state = controller.state.value
            notices.changed(state.copy(pending = emptyList()), state)
        }
        controller.setAppVisible(value)
        backgroundGrace?.cancel()
        if (!value && !preferences.value.background) backgroundGrace = scope.launch { delay(30000); if (!visible && !preferences.value.background) controller.pauseConnections() }
        if (value) {
            notices.clear()
            controller.resumeConnections()
            if (preferences.value.background) startConnectionService()
        }
    }
    private fun startConnectionService() {
        runCatching { app.startForegroundService(Intent(app, ConnectionService::class.java)) }
            .onFailure { update { it.copy(background = false) } }
    }
    companion object {
        @Volatile private var instance: PocketRuntime? = null
        fun get(app: Application): PocketRuntime = instance ?: synchronized(this) { instance ?: PocketRuntime(app).also { instance = it } }
    }
}
