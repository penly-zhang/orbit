package dev.codexpocket

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.codexpocket.core.*
import java.io.File

/** Exercises readable prose, production work summaries and both detail sheets using local fixtures. */
internal class ConversationUiProbe(private val runner: Instrumentation) {
    private val automation get() = runner.uiAutomation
    fun run() {
        var activity: MainActivity? = null
        val result = Bundle()
        var stage = "start"
        try {
            val screen = runner.startActivitySync(Intent(runner.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)) as MainActivity
            activity = screen
            val prose = "正文行距验证：我会先检查项目的连接状态，再确认执行结果。会话里的每一段文字都应有清晰的行距，在手机上阅读长段落时也不会挤在一起。\n\n接下来查看命令执行与步骤详情，所有命令和输出均为演示数据。"
            val items = listOf(
                ChatItem("reason", "reasoning", "**检查项目连接**", "completed", turnId = "fixture"),
                ChatItem("command", "commandExecution", "连接状态正常\n已读取 4 个项目\n验证完成", "completed", turnId = "fixture", command = "orbit status --verbose"),
                ChatItem("failure", "commandExecution", "连接被拒绝", "failed", turnId = "fixture", command = "orbit check --port 22"),
            )
            val liveProse = ChatItem("progress", "agentMessage", "执行期间的说明文字", phase = "commentary", turnId = "fixture")
            val running = mutableStateOf(true)
            val loader = ConversationImageLoader { _, _ -> error("Fixture must not fetch images") }
            runner.runOnMainSync { screen.setContent { PocketTheme("light") { Surface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Text("会话排版验证", style = MaterialTheme.typography.titleMedium)
                    ConversationBody(prose, emptyList(), "/fixture", "fixture", loader)
                    WorkGroup(WorkEntry("fixture", items + liveProse, running.value, null, 5200), "/fixture", "fixture", loader)
                    ConversationBody("检查完成。过程会在任务结束后自动收起，仍可点开查看。", emptyList(), "/fixture", "fixture", loader)
                }
            } } } }
            stage = "prose metrics"
            var body: TextView? = null
            await { runner.runOnMainSync { body = findTextView(screen.window.decorView, "正文行距验证") }; body != null }
            runner.runOnMainSync {
                val view = requireNotNull(body)
                val scale = view.resources.displayMetrics.scaledDensity
                check(view.lineHeight / scale >= 25f) { "Dense prose: lineHeight=${view.lineHeight}, scale=$scale" }
                check(view.lineCount >= 3) { "Multiline prose not laid out" }
            }
            await { find("执行 2 条命令") != null }
            check(find("执行期间的说明文字") != null)
            check(find("执行未完成") != null) { "Failed command must be marked in the conversation" }
            screenshot("conversation-overview.png")
            stage = "command sheet"
            tap("执行 2 条命令"); await { find("复制输出") != null }
            check(find("orbit status --verbose") != null) { "Command missing" }
            check(find("连接状态正常\n已读取 4 个项目\n验证完成") != null) { "Output should be visible without another expansion" }
            check(find("复制命令") != null)
            screenshot("conversation-command-sheet.png")
            tap("复制输出")
            runner.runOnMainSync {
                val clipboard = runner.targetContext.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                check(clipboard.primaryClip?.getItemAt(0)?.text?.toString() == items[1].text) { "Copy output changed its content" }
            }
            Thread.sleep(2500) // Let the copy toast clear before capturing the next sheet.
            automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            await { find("查看执行步骤") != null }
            stage = "steps sheet"
            tap("查看执行步骤"); await { find("执行步骤") != null }
            check(find("检查项目连接") != null)
            screenshot("conversation-steps-sheet.png")
            automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            await { find("收起工作过程") != null }
            stage = "automatic completion collapse"
            runner.runOnMainSync { running.value = false }
            await { find("展开工作过程") != null }
            check(find("执行 2 条命令") == null) { "Commands remained expanded after completion" }
            check(find("执行期间的说明文字") == null) { "Progress prose remained expanded after completion" }
            screenshot("conversation-completed-collapsed.png")
            tap("展开工作过程")
            await { find("执行 2 条命令") != null }
            check(find("执行期间的说明文字") != null)
            tap("收起工作过程")
            await { find("执行 2 条命令") == null }
            result.putString("stream", "PASS: scaled multiline prose, inline summaries, command/output sheet with copy actions, chronological steps, batched commands, automatic completion collapse and manual re-expansion\n")
        } catch (error: Throwable) {
            result.putString("stream", "FAIL at $stage: ${error.javaClass.simpleName}: ${error.message}\n")
            result.putString("failure", error.toString())
            runCatching { screenshot("conversation-failure.png") }
        } finally {
            activity?.let { screen -> runner.runOnMainSync { screen.recreate() } }
            runner.finish(if (result.containsKey("failure")) Activity.RESULT_CANCELED else Activity.RESULT_OK, result)
        }
    }
    private fun findTextView(view: View, prefix: String): TextView? {
        if (view is TextView && view.text.startsWith(prefix)) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findTextView(view.getChildAt(i), prefix)?.let { return it }
        return null
    }
    private fun await(check: () -> Boolean) { repeat(60) { if (check()) return; Thread.sleep(120) }; error("Conversation UI wait timed out") }
    private fun walk(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
        for (i in 0 until node.childCount) walk(node.getChild(i), label)?.let { return it }
        return null
    }
    private fun find(label: String): AccessibilityNodeInfo? {
        if (Build.VERSION.SDK_INT >= 33) automation.clearCache()
        return walk(automation.rootInActiveWindow, label)
    }
    private fun tap(label: String) {
        var node = requireNotNull(find(label)) { "Missing $label" }
        while (!node.isClickable && node.parent != null) node = node.parent
        check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "Cannot tap $label" }
    }
    private fun screenshot(name: String) {
        Thread.sleep(1000) // Wait for sheet animation and the compositor, not just accessibility nodes.
        val bitmap = requireNotNull(automation.takeScreenshot())
        File(runner.targetContext.cacheDir, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
