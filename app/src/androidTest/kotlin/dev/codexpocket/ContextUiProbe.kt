package dev.codexpocket

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.codexpocket.core.*
import kotlinx.coroutines.flow.MutableSharedFlow
import java.io.File

/** Fixture callbacks exercise the production picker without uploading user data or calling an agent. */
internal class ContextUiProbe(private val runner: Instrumentation) {
    private val automation get() = runner.uiAutomation
    private var stage = "start"
    fun run() {
        var activity: MainActivity? = null; val result = Bundle()
        try {
            activity = runner.startActivitySync(Intent(runner.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)) as MainActivity
            runner.runOnMainSync { activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            val state = mutableStateOf(PocketState(profile = HostProfile(id = "fixture", name = "Air", autoConnect = false), connected = true, threadReady = true,
                project = ProjectInfo("fixture", "Pocket", listOf("/fixture")), root = "/fixture", thread = ThreadInfo("fixture", "上下文示例", "/fixture")))
            var documentPicked = false; var sent = false
            val actions = ComposerActions(draft = { state.value = state.value.copy(draft = it) }, send = { sent = true }, stop = {}, addImage = {}, removeImage = {}, model = {}, effort = {}, permission = {}, mode = {}, refresh = {})
            val skill = SkillEntry("fixture-review", "/skills/review/SKILL.md", "检查代码质量与边界情况", true, "plugin")
            val context = ContextActions(addDocument = { documentPicked = true }, add = { state.value = state.value.copy(draftContext = state.value.draftContext + it); true }, remove = { ref -> state.value = state.value.copy(draftContext = state.value.draftContext.filterNot { it.id == ref.id }) },
                files = { path, query -> if (query.isNotBlank()) listOf(ContextFile("main.kt", "/fixture/src/main.kt", false)) else if (path.endsWith("src")) listOf(ContextFile("main.kt", "/fixture/src/main.kt", false)) else listOf(ContextFile("src", "/fixture/src", true), ContextFile("README.md", "/fixture/README.md", false)) },
                preview = { "fun main() {\n    println(\"Hello\")\n}\n" },
                catalog = { ToolCatalog(listOf(skill), listOf(McpEntry("docs", "connected", "oAuth", mapOf("search" to "搜索项目文档"), null, "plugin")), listOf(PluginEntry("plugin", "文档助手", "搜索与代码审查", true, true, null))) },
                plugin = { PluginTools(listOf(skill.copy(name = "plugin-read", path = "/plugin/read/SKILL.md")), listOf("docs"), "文档助手") }, enableSkill = {}, reloadMcp = {}, authorize = { obj() }, finishAuthorization = { _, _ -> })
            val screen = activity
            runner.runOnMainSync { screen.setContent { PocketTheme("light") { Surface(Modifier.fillMaxSize()) { Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Text("上下文与工具 · 演示数据", modifier = Modifier.padding(24.dp)); Spacer(Modifier.weight(1f)); MessageComposer(state.value, actions, MutableSharedFlow(), context)
            } } } } }
            stage = "menu"; tap("添加附件、上下文或工具"); await { find("上传文件") != null }; screenshot("context-menu.png")
            tap("上传文件"); check(documentPicked)
            stage = "files"; tap("添加附件、上下文或工具"); tap("引用文件或代码 @"); await { find("src") != null }; tap("src"); tap("main.kt")
            await { edits().size == 2 }; setText(edits()[0], "1"); setText(edits()[1], "3")
            screenshot("context-code.png"); tap("添加引用"); await { state.value.draftContext.size == 1 }
            check(state.value.draftContext.single().startLine == 1 && state.value.draftContext.single().endLine == 3)
            stage = "skill"; tap("添加附件、上下文或工具"); tap("工具与能力"); await { find("fixture-review") != null }; screenshot("context-skills.png"); tap("fixture-review")
            await { state.value.draftContext.size == 2 }; check(state.value.draftContext.last().kind == "skill")
            stage = "MCP"; tap("添加附件、上下文或工具"); tap("工具与能力"); tap("MCP"); await { find("docs") != null }; tap("docs"); await { find("search") != null }; screenshot("context-mcp.png"); tap("search")
            await { state.value.draftContext.size == 3 }; check(state.value.draftContext.last().path == "docs/search")
            stage = "plugin"; tap("添加附件、上下文或工具"); tap("工具与能力"); tap("插件"); await { find("文档助手") != null }; tap("文档助手"); await { find("plugin-read") != null }; screenshot("context-plugins.png"); tap("plugin-read")
            await { state.value.draftContext.size == 4 }
            stage = "send"; screenshot("context-composer.png"); tap("发送"); check(sent)
            runner.runOnMainSync { state.value = state.value.copy(draftContext = emptyList(), draft = "@") }
            stage = "@ trigger"; await { find("引用上下文") != null }
            result.putString("stream", "PASS: attachment menu, directory browsing, code preview and line range, native Skill selection, MCP tool reference, plugin capabilities, context-only send and @ picker; fixture-only callbacks\n")
        } catch (e: Throwable) { result.putString("stream", "FAIL at $stage: ${e.javaClass.simpleName}: ${e.message}\n"); result.putString("failure", e.toString()); runCatching { screenshot("context-failure.png") } }
        finally { activity?.let { runner.runOnMainSync { it.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); it.recreate() } }; runner.finish(if (result.containsKey("failure")) Activity.RESULT_CANCELED else Activity.RESULT_OK, result) }
    }
    private fun await(test: () -> Boolean) { repeat(100) { if (test()) return; Thread.sleep(100) }; error("Context UI wait timed out") }
    private fun walk(node: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? { if (node == null) return null; if (predicate(node)) return node; for (i in 0 until node.childCount) walk(node.getChild(i), predicate)?.let { return it }; return null }
    private fun find(text: String): AccessibilityNodeInfo? { if (android.os.Build.VERSION.SDK_INT >= 33) automation.clearCache(); return walk(automation.rootInActiveWindow) { it.text?.toString() == text || it.contentDescription?.toString() == text } }
    private fun edits(): List<AccessibilityNodeInfo> { if (android.os.Build.VERSION.SDK_INT >= 33) automation.clearCache(); val found = mutableListOf<AccessibilityNodeInfo>(); walk(automation.rootInActiveWindow) { if (it.className?.toString() == "android.widget.EditText") found += it; false }; return found }
    private fun setText(node: AccessibilityNodeInfo, value: String) { check(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) })); Thread.sleep(200) }
    private fun tap(text: String) { await { find(text) != null }; var node = requireNotNull(find(text)); while (!node.isClickable && node.parent != null) node = node.parent; check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "Cannot click $text" }; Thread.sleep(250) }
    private fun screenshot(name: String) { Thread.sleep(350); val bitmap = requireNotNull(automation.takeScreenshot()); File(runner.targetContext.cacheDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle() }
}
