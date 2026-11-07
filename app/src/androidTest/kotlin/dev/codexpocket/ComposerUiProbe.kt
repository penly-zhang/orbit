package dev.codexpocket

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.view.WindowInsets
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

/** The production composer with fixture callbacks: never changes a real model, permission, or task. */
internal class ComposerUiProbe(private val runner: Instrumentation) {
    private val targetContext get() = runner.targetContext
    private val uiAutomation get() = runner.uiAutomation
    private fun runOnMainSync(block: () -> Unit) = runner.runOnMainSync { block() }
    fun run() {
        var activity: MainActivity? = null
        val result = Bundle()
        var stage = "start"
        try {
            activity = runner.startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)) as MainActivity
            runOnMainSync { activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            val efforts = listOf("low", "medium", "high", "xhigh", "max", "ultra").map { EffortOption(it, "") }
            val models = listOf(ModelOption("fixture-sol", "GPT-6.1 Sol", "演示模型", efforts, "medium", true), ModelOption("fixture-terra", "GPT-6.1 Terra", "演示模型", efforts.take(3), "high", false))
            val permissions = listOf(
                PermissionOption(":workspace", "默认权限", "在沙盒中运行命令", true),
                PermissionOption(":workspace", "自动审查", "自动审查提权请求", true, "auto_review"),
                PermissionOption(":read-only", "只读", "修改文件等额外操作需批准", true),
                PermissionOption(":danger-full-access", "完整访问权限", "电脑完整访问权限（风险较高）", true),
            )
            val state = mutableStateOf(PocketState(profile = HostProfile(id = "ui-fixture", autoConnect = false), connected = true, threadReady = true,
                project = ProjectInfo("fixture-project", "Project", listOf("/fixture")),
                thread = ThreadInfo("fixture-thread", "示例", "/fixture"), models = models, permissions = permissions,
                settings = conversationSettings(obj("model" to "fixture-sol", "effort" to "medium", "activePermissionProfile" to obj("id" to ":danger-full-access"), "sandboxPolicy" to obj("type" to "dangerFullAccess"), "approvalPolicy" to "never"))))
            val completions = MutableSharedFlow<SendCompletion>(extraBufferCapacity = 8)
            var clickedSend = false
            val actions = ComposerActions(
                draft = { state.value = state.value.copy(draft = it) }, send = { clickedSend = true; state.value = state.value.copy(sending = true) }, stop = {}, addImage = {}, removeImage = {},
                model = { option -> state.value = state.value.copy(settings = state.value.settings!!.copy(model = option.id, effort = option.defaultEffort)) },
                effort = { option -> state.value = state.value.copy(settings = state.value.settings!!.copy(effort = option.id)) },
                permission = { option -> state.value = state.value.copy(settings = state.value.settings!!.copy(permissionId = option.id, reviewer = option.reviewer, sandbox = obj("type" to if (option.id == ":read-only") "readOnly" else "workspaceWrite"))) },
                mode = {}, refresh = {},
            )
            val screen = activity
            runOnMainSync { screen.setContent { PocketTheme("light") { Surface(Modifier.fillMaxSize()) { Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Text("输入框示例 · 演示数据", modifier = Modifier.padding(24.dp), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f)); MessageComposer(state.value, actions, completions)
            } } } } }
            stage = "initial editor"; await { findEdit() != null }
            check(find("6.1 Sol 中") == null) { "Idle composer must be a compact single row" }
            check(find("选择权限：完整访问权限") == null)
            check(find("在 Project · 我的电脑 上工作") != null) { "Missing dynamic project and computer hint" }
            stage = "focus expansion"; requireNotNull(findEdit()).performAction(AccessibilityNodeInfo.ACTION_CLICK)
            await { find("6.1 Sol 中") != null }
            runOnMainSync { state.value = state.value.copy(draft = "输入框布局与焦点验证") }
            await { findEdit()?.text?.toString() == "输入框布局与焦点验证" }
            check(find("在 Project · 我的电脑 上工作") == null) { "Destination must disappear once text is entered" }
            screenshot("composer-single-line.png")
            val editorBounds = android.graphics.Rect()
            requireNotNull(findEdit()).getBoundsInScreen(editorBounds)
            check(editorBounds.height() <= 48 * targetContext.resources.displayMetrics.density + 2) { "Single-line editor height=${editorBounds.height()}, density=${targetContext.resources.displayMetrics.density}, fontScale=${targetContext.resources.configuration.fontScale}" }
            val sendBounds = android.graphics.Rect()
            var sendNode = requireNotNull(find("发送"))
            while (!sendNode.isClickable && sendNode.parent != null) sendNode = sendNode.parent
            sendNode.getBoundsInScreen(sendBounds)
            var viewportRight = 0
            var viewportDensity = 1f
            runOnMainSync {
                val location = IntArray(2); screen.window.decorView.getLocationOnScreen(location)
                viewportRight = location[0] + screen.window.decorView.width
                viewportDensity = screen.resources.displayMetrics.density
            }
            check(sendBounds.right > 0 && sendBounds.right <= viewportRight + 2 && viewportRight - sendBounds.right <= 30 * viewportDensity) {
                "Send button bounds=$sendBounds; viewportRight=$viewportRight; density=$viewportDensity"
            }
            stage = "model menu"; tap("6.1 Sol 中"); await { find("Ultra") != null }
            check(listOf("低", "中", "高", "极高", "Max", "Ultra").all { find(it) != null }) { "Missing native effort choices" }
            screenshot("composer-model.png")
            stage = "effort"; tap("极高"); await { find("6.1 Sol 极高") != null }
            stage = "model selection"; tap("6.1 Sol 极高"); tap("模型"); await { find("GPT-6.1 Terra") != null }
            tap("GPT-6.1 Terra"); await { find("6.1 Terra 高") != null }
            stage = "permission menu"; tap("选择权限：完整访问权限"); await { find("自动审查") != null }
            check(listOf("默认权限", "自动审查", "只读", "完整访问权限").all { find(it) != null })
            screenshot("composer-permissions.png")
            stage = "permission selection"; tap("只读"); await { find("选择权限：只读") != null }
            stage = "refocus"; val edit = requireNotNull(findEdit())
            edit.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            await { findEdit()?.isFocused == true }
            runOnMainSync { completions.tryEmit(SendCompletion("other-host", "fixture-thread")) }
            Thread.sleep(200); check(findEdit()?.isFocused == true) { "Another host stole input focus" }
            stage = "send"; tap("发送"); await { clickedSend }
            check(findEdit()?.isFocused == true) { "Focus cleared before send acknowledgement" }
            stage = "acknowledgement"; runOnMainSync { state.value = state.value.copy(draft = "", sending = false); completions.tryEmit(SendCompletion("ui-fixture", "fixture-thread")) }
            await { findEdit()?.isFocused == false }
            if (Build.VERSION.SDK_INT >= 30) await {
                var showing = true
                runOnMainSync { showing = screen.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true }
                !showing
            }
            stage = "collapse"; await { find("6.1 Terra 高") == null && find("选择权限：只读") == null }
            screenshot("composer-complete.png")
            result.putString("stream", "PASS: compact idle composer, focus expansion, dynamic project/computer hint, native-style menus, send acknowledgement focus and keyboard dismissal with collapse; no real tasks or settings changed\n")
        } catch (error: Throwable) { result.putString("stream", "FAIL at $stage: ${error.javaClass.simpleName}: ${error.message}\n"); result.putString("failure", error.toString()); runCatching { screenshot("composer-failure.png") } }
        finally { activity?.let { screen -> runOnMainSync { screen.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); screen.recreate() } }; runner.finish(if (result.containsKey("failure")) Activity.RESULT_CANCELED else Activity.RESULT_OK, result) }
    }
    private fun await(check: () -> Boolean) { repeat(80) { if (check()) return; Thread.sleep(100) }; error("Composer UI wait timed out") }
    private fun walk(node: AccessibilityNodeInfo?, check: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        if (check(node)) return node
        for (i in 0 until node.childCount) walk(node.getChild(i), check)?.let { return it }
        return null
    }
    private fun find(label: String): AccessibilityNodeInfo? { if (Build.VERSION.SDK_INT >= 33) uiAutomation.clearCache(); return walk(uiAutomation.rootInActiveWindow) { it.text?.toString()?.lineSequence()?.any { line -> line == label } == true || it.contentDescription?.toString() == label } }
    private fun findEdit(): AccessibilityNodeInfo? { if (Build.VERSION.SDK_INT >= 33) uiAutomation.clearCache(); return walk(uiAutomation.rootInActiveWindow) { it.className?.toString() == "android.widget.EditText" } }
    private fun tap(label: String) {
        await { find(label) != null }
        var node = requireNotNull(find(label))
        while (!node.isClickable && node.parent != null) node = node.parent
        check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "Cannot click $label" }
        Thread.sleep(150)
    }
    private fun screenshot(name: String) {
        Thread.sleep(300)
        val bitmap = requireNotNull(uiAutomation.takeScreenshot())
        File(targetContext.cacheDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
}
