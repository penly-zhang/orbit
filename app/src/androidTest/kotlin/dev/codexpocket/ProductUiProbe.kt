package dev.codexpocket

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.codexpocket.core.*
import java.io.File

/** Production screens and gesture modifier, with fixture actions only. */
internal class ProductUiProbe(private val runner: Instrumentation) {
    private val automation get() = runner.uiAutomation
    private var stage = "start"
    fun run(projectsOnly: Boolean = false, homeOnly: Boolean = false, toolsOnly: Boolean = false, gesturesOnly: Boolean = false) {
        var activity: MainActivity? = null; val result = Bundle()
        try {
            activity = runner.startActivitySync(Intent(runner.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)) as MainActivity
            val screen = activity
            runner.runOnMainSync { screen.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            val project = ProjectInfo("fixture", "Pocket", listOf("/fixture/Pocket"))
            val profile = HostProfile(id = "fixture", name = "Air", host = "air.example", username = "fixture", autoConnect = true)
            val threads = listOf(ThreadInfo("one", "完善移动端体验", "/fixture/Pocket", updated = 1), ThreadInfo("two", "检查项目构建", "/fixture/Pocket", updated = 2))
            val state = mutableStateOf(PocketState(profiles = listOf(profile), profile = profile, connected = true, project = project, root = "/fixture/Pocket", connections = mapOf("fixture" to HostConnectionStatus(connected = true)), projects = listOf(project, ProjectInfo("web", "Website", listOf("/fixture/Web"))), threads = threads))
            val actions = ProjectActions(selectHost = {}, home = { state.value = state.value.copy(project = null) }, selectProject = { state.value = state.value.copy(project = it) }, refreshProjects = {},
                undoArchive = { state.value.archiveUndo?.let { undo -> state.value = state.value.copy(threads = state.value.threads + undo.thread, archiveUndo = null) } }, dismissArchiveUndo = { state.value = state.value.copy(archiveUndo = null) },
                showArchived = { state.value = state.value.copy(showArchived = it) }, newThread = {}, load = {}, openThread = {}, archiveThread = { thread -> state.value = state.value.copy(threads = state.value.threads.filterNot { it.id == thread.id }, archiveUndo = ArchiveUndo(thread, false)) },
                renameThread = { _, id, name -> state.value = state.value.copy(threads = state.value.threads.map { if (it.id == id) it.copy(title = name) else it }) },
                pinThread = { projectId, id, pinned -> val ids = state.value.pinnedThreads[projectId].orEmpty().filterNot { it == id }; state.value = state.value.copy(pinnedThreads = state.value.pinnedThreads + (projectId to if (pinned) listOf(id) + ids else ids)) })
            var theme by mutableStateOf("light")
            fun show(content: @Composable () -> Unit) = runner.runOnMainSync { screen.setContent { PocketTheme(theme) { Surface(Modifier.fillMaxSize()) { Column(Modifier.fillMaxSize().safeDrawingPadding()) { content() } } } } }
            if (gesturesOnly) {
                stage = "bidirectional session gestures"
                var index by mutableIntStateOf(1)
                val directions = mutableListOf<Int>()
                show { Column(Modifier.fillMaxWidth().height(400.dp).sessionSwipe("bidirectional-fixture") { direction ->
                    directions += direction; index = (index + direction).coerceIn(0, 2)
                }.semantics { contentDescription = "标签滑动测试区域" }) {
                    Box(Modifier.fillMaxWidth().height(160.dp)) { Text("当前标签 ${index + 1}") }
                    Box(Modifier.fillMaxWidth().height(120.dp).semantics { contentDescription = "横向代码滚动" }.horizontalScroll(rememberScrollState())) {
                        Text("val code = " + "long-line ".repeat(80), softWrap = false)
                    }
                } }
                await { find("标签滑动测试区域") != null }
                val bounds = Rect(); requireNotNull(find("标签滑动测试区域")).getBoundsInScreen(bounds)
                fun gesture(left: Boolean) = swipe(bounds.left + bounds.width() * if (left) 0.8f else 0.2f,
                    bounds.left + bounds.width() * if (left) 0.2f else 0.8f, bounds.top + 60f)
                gesture(true); await { index == 2 }
                gesture(false); await { index == 1 }
                gesture(false); await { index == 0 }
                gesture(false); check(index == 0)
                gesture(true); await { index == 1 }
                gesture(true); await { index == 2 }
                gesture(true); check(index == 2)
                check(directions == listOf(1, -1, -1, -1, 1, 1, 1)) { "Wrong swipe directions: $directions" }
                val before = directions.size
                val code = Rect(); requireNotNull(find("横向代码滚动")).getBoundsInScreen(code)
                swipe(code.left + code.width() * 0.8f, code.left + code.width() * 0.2f, code.centerY().toFloat())
                swipe(code.left + code.width() * 0.2f, code.left + code.width() * 0.8f, code.centerY().toFloat())
                check(directions.size == before) { "Code scrolling switched a session" }
                result.putString("stream", "PASS: left and right session swipes, first/last boundaries, horizontal code scrolling priority; fixture-only actions\n")
                return
            }
            if (toolsOnly) {
                stage = "conversation title search"
                show { ProjectsScreen(state.value, actions, {}) }
                await { find("搜索项目或会话") != null && edit() != null }
                requireNotNull(edit()).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "构建") })
                await { find("检查项目构建") != null && find("完善移动端体验") == null }
                screenshot("navigation-title-search.png")
                tap("清除搜索"); await { find("完善移动端体验") != null }
                tap("筛选会话"); await { find("已归档") != null && find("未读") != null }; tap("活动会话")
                val vm = androidx.lifecycle.ViewModelProvider(screen)[PocketViewModel::class.java]
                stage = "chat tools without management duplicates"
                show { Row { Text("聊天工具 · 演示数据", modifier = Modifier.weight(1f)); ConversationActions(state.value.copy(thread = threads.first()), vm, false, {}) } }
                tap("聊天工具"); await { find("搜索会话消息") != null && find("刷新消息") != null }
                check(find("重命名会话") == null && find("会话管理") == null && find("会话与任务") == null && find("新建会话") == null)
                screenshot("navigation-chat-tools.png")
                stage = "navigation management menu"
                val tabs = threads.mapIndexed { index, thread -> SessionTab(thread, project, index + 1, profileId = profile.id, profileName = profile.name) }
                show { NavigationThreadActions(state.value.copy(sessionTabs = tabs), vm, ThreadActionTarget(profile.id, project, threads.first()), {}) }
                await { find("重命名会话") != null && find("关闭会话标签") != null && find("会话详情") != null }
                screenshot("navigation-thread-actions.png")
                tap("重命名会话"); await { edit() != null }; tap("取消")
                tap("会话详情"); await { find("工作目录") != null && find("会话 ID") != null }
                result.putString("stream", "PASS: conversation title search and clearing, archive filter access, compact chat tools with management removed, unified target menu and rename confirmation/details; fixture-only actions\n")
                return
            }
            if (homeOnly) {
                stage = "empty conversation home"
                val home = mutableStateOf(state.value.copy(connected = false, newConversationDraft = ""))
                var browsing by mutableStateOf(false)
                var sends = 0
                show {
                    if (browsing) Column(Modifier.fillMaxSize().sessionSwipe("home-project-list") { if (it > 0) browsing = false }) {
                        TextButton(onClick = { browsing = false }) { Text("返回会话") }
                        Box(Modifier.weight(1f)) { ProjectsScreen(state.value, actions, {}, allowArchiveSwipe = false) }
                    } else NewConversationContent(home.value, { home.value = home.value.copy(newConversationDraft = it) }, { sends++ }, {}, {}, { browsing = true })
                }
                await { find("开始一个新任务") != null && edit() != null }; screenshot("home-empty.png")
                requireNotNull(edit()).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "保留主界面草稿") })
                await { home.value.newConversationDraft == "保留主界面草稿" }
                var offlineSend = requireNotNull(find("发送"))
                while (!offlineSend.isClickable && offlineSend.parent != null) offlineSend = offlineSend.parent
                offlineSend.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                check(sends == 0) { "Offline send dispatched a message" }
                val width = runner.targetContext.resources.displayMetrics.widthPixels.toFloat()
                val height = runner.targetContext.resources.displayMetrics.heightPixels.toFloat()
                swipe(width * 0.2f, width * 0.8f, height * 0.3f)
                check(!browsing) { "Empty home right swipe opened navigation" }
                swipe(width * 0.8f, width * 0.2f, height * 0.3f)
                check(!browsing) { "Empty home left swipe opened navigation" }
                check(edit()?.text?.toString() == "保留主界面草稿")
                tap("查看已有会话"); await { browsing }
                swipe(width * 0.8f, width * 0.2f, height * 0.4f)
                await { !browsing && edit()?.text?.toString() == "保留主界面草稿" }
                runner.runOnMainSync { home.value = home.value.copy(connected = true) }
                tap("发送"); check(sends == 1)
                screenshot("home-draft.png")
                result.putString("stream", "PASS: empty conversation home, offline editing and send guard, no swipe navigation from empty home, explicit list entry and left swipe return, draft preservation; fixture-only actions\n")
                return
            }
            stage = "project scope"; show { ProjectsScreen(state.value, actions, {}) }
            await { find("搜索项目或会话") != null && find("完善移动端体验") != null }; screenshot("product-projects.png")
            tap("会话列表操作"); tap("置顶会话"); await { find("已置顶") != null }; screenshot("product-projects-pinned.png")
            tap("会话列表操作"); tap("取消置顶"); await { state.value.pinnedThreads[project.id].orEmpty().isEmpty() }
            tap("会话列表操作"); screenshot("product-projects-actions.png"); tap("重命名会话"); await { edit() != null }
            requireNotNull(edit()).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "重命名后的会话") })
            tap("保存"); await { find("重命名后的会话") != null }; screenshot("product-projects-renamed.png")
            tap("会话列表操作"); tap("归档会话"); await { state.value.archiveUndo != null }; tap("撤销"); await { state.value.threads.size == 2 && state.value.archiveUndo == null }
            if (projectsOnly) { result.putString("stream", "PASS: project conversation menu, pin/unpin, rename, archive and undo; fixture-only actions\n"); return }
            stage = "session operations"
            val vm = androidx.lifecycle.ViewModelProvider(screen)[PocketViewModel::class.java]
            val withTabs = state.value.copy(thread = threads.first(), sessionTabs = threads.mapIndexed { i, thread -> SessionTab(thread, project, i + 1, profileId = profile.id, profileName = "Air") })
            show { Row { Text("会话操作 · 演示数据", modifier = Modifier.weight(1f)); ConversationActions(withTabs, vm, false, {}) } }
            tap("聊天工具"); await { find("搜索会话消息") != null && find("刷新消息") != null }
            check(find("会话管理") == null && find("新建会话") == null && find("分享与导出") == null && find("会话与任务") == null)
            screenshot("product-chat-tools.png")
            show { NavigationThreadActions(withTabs, vm, ThreadActionTarget(profile.id, project, threads.first()), {}) }
            await { find("重命名会话") != null && find("关闭会话标签") != null && find("会话详情") != null }
            screenshot("product-thread-actions.png")
            tap("重命名会话"); await { edit() != null }; tap("取消")
            stage = "connection form"; var saved = false
            show { HostEditor(profile.copy(autoConnect = false), Credentials(password = "fixture-secret"), {}) { _, _, _ -> saved = true } }
            await { find("编辑连接") != null && find("保存") != null }; screenshot("product-connection.png")
            val bounds = Rect(); requireNotNull(find("保存")).getBoundsInScreen(bounds)
            check(bounds.bottom <= runner.targetContext.resources.displayMetrics.heightPixels && bounds.top > 0)
            tap("保存"); check(saved)
            runner.runOnMainSync { theme = "dark" }; Thread.sleep(350); screenshot("product-dark.png"); runner.runOnMainSync { theme = "light" }
            stage = "large text"; show { val d = LocalDensity.current; CompositionLocalProvider(LocalDensity provides Density(d.density, 2f)) { HostEditor(profile.copy(autoConnect = false), Credentials(password = "fixture-secret"), {}) { _, _, _ -> } } }
            await { find("保存") != null }; screenshot("product-large-text.png")
            requireNotNull(find("保存")).getBoundsInScreen(bounds); check(bounds.width() > 0 && bounds.right <= runner.targetContext.resources.displayMetrics.widthPixels)
            stage = "large composer"
            val settings = conversationSettings(obj("model" to "fixture", "effort" to "medium", "sandboxPolicy" to obj("type" to "workspaceWrite"), "approvalPolicy" to "on-request"))
            val composer = withTabs.copy(threadReady = true, draft = "检查大字体下的输入布局", settings = settings, usage = mapOf("one" to tokenUsage(obj("last" to obj("totalTokens" to 100), "total" to obj("totalTokens" to 1000), "modelContextWindow" to 1000))!!))
            val composerActions = ComposerActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {})
            show { val d = LocalDensity.current; CompositionLocalProvider(LocalDensity provides Density(d.density, 2f)) { Column(Modifier.fillMaxSize()) { Spacer(Modifier.weight(1f)); MessageComposer(composer, composerActions, kotlinx.coroutines.flow.MutableSharedFlow()) } } }
            await { find("发送") != null && find("10%") != null && find("模型") != null }; screenshot("product-composer-large.png")
            val send = Rect(); requireNotNull(find("发送")).getBoundsInScreen(send); check(send.right <= runner.targetContext.resources.displayMetrics.widthPixels)
            stage = "file reader"
            val file = FilePreview("/fixture/Pocket/main.kt", "fun main() {\n  // target one\n  val message = \"target two\"\n}\n".toByteArray())
            show { FilePreviewScreen(file, withTabs, vm) }
            tap("搜索文件内容"); await { edit() != null }; requireNotNull(edit()).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "target") })
            await { find("1 / 2 行匹配") != null }; tap("下一处文本匹配"); await { find("2 / 2 行匹配") != null }; screenshot("product-file.png")
            tap("文件操作"); tap("关闭自动换行"); await { find("2 / 2 行匹配") != null }
            stage = "deferred request"; var open by mutableStateOf(true); var answered = false
            show { Text("会话内容仍可阅读"); if (open) McpRequestDialog(PendingRequest(kotlinx.serialization.json.JsonPrimitive("fixture"), dev.codexpocket.core.RequestKind.MCP, obj("mode" to "url", "serverName" to "docs", "message" to "确认访问项目文档", "url" to "https://example.com/authorize")), "Air", { _, _, _ -> answered = true }, { open = false }) }
            tap("稍后"); await { !open }; check(!answered)
            stage = "gesture ownership"; var switches = 0
            show { Column(Modifier.fillMaxWidth().height(400.dp).sessionSwipe("fixture") { switches++ }.semantics { contentDescription = "会话滑动区域" }) {
                Box(Modifier.fillMaxWidth().height(160.dp)) { Text("会话区域：左右划切换") }
                Box(Modifier.fillMaxWidth().height(120.dp).semantics { contentDescription = "可横向滚动的代码" }.horizontalScroll(rememberScrollState())) { Text("val code = " + "long-line ".repeat(80), softWrap = false) }
            } }
            await { find("会话滑动区域") != null }; val area = Rect(); requireNotNull(find("会话滑动区域")).getBoundsInScreen(area)
            swipe(area.left + area.width() * 0.8f, area.left + area.width() * 0.2f, area.top + 60f)
            await { switches == 1 }
            val code = Rect(); requireNotNull(find("可横向滚动的代码")).getBoundsInScreen(code)
            swipe(code.left + code.width() * 0.8f, code.left + code.width() * 0.2f, code.centerY().toFloat()); Thread.sleep(400); check(switches == 1) { "Code scrolling switched conversation" }
            result.putString("stream", "PASS: scoped project/session search, archive menu and undo, full-page connection with pinned save, 200% form/composer text, searchable file preview and wrapping, deferred MCP request, conversation swipe and child code scrolling; fixture-only actions\n")
        } catch (e: Throwable) { result.putString("stream", "FAIL at $stage: ${e.javaClass.simpleName}: ${e.message}\n"); result.putString("failure", e.toString()); runCatching { screenshot("product-failure.png") } }
        finally { activity?.let { runner.runOnMainSync { it.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); it.recreate() } }; runner.finish(if (result.containsKey("failure")) Activity.RESULT_CANCELED else Activity.RESULT_OK, result) }
    }
    private fun await(test: () -> Boolean) { repeat(100) { if (test()) return; Thread.sleep(100) }; error("Product UI wait timed out") }
    private fun walk(node: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? { if (node == null) return null; if (predicate(node)) return node; for (i in 0 until node.childCount) walk(node.getChild(i), predicate)?.let { return it }; return null }
    private fun find(text: String): AccessibilityNodeInfo? { if (android.os.Build.VERSION.SDK_INT >= 33) automation.clearCache(); return walk(automation.rootInActiveWindow) { it.text?.toString() == text || it.contentDescription?.toString() == text } }
    private fun edit(): AccessibilityNodeInfo? { if (android.os.Build.VERSION.SDK_INT >= 33) automation.clearCache(); return walk(automation.rootInActiveWindow) { it.className?.toString() == "android.widget.EditText" } }
    private fun tap(text: String) { await { find(text) != null }; var node = requireNotNull(find(text)); while (!node.isClickable && node.parent != null) node = node.parent; check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)); Thread.sleep(250) }
    private fun screenshot(name: String) { Thread.sleep(350); val bitmap = requireNotNull(automation.takeScreenshot()); File(runner.targetContext.cacheDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle() }
    private fun swipe(from: Float, to: Float, y: Float) { val down = SystemClock.uptimeMillis(); for (i in 0..14) { val action = when (i) { 0 -> MotionEvent.ACTION_DOWN; 14 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }; val e = MotionEvent.obtain(down, down + i * 20, action, from + (to - from) * i / 14, y, 0); check(automation.injectInputEvent(e, true)); e.recycle(); Thread.sleep(20) } }
}
