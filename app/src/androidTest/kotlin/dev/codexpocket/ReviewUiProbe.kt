package dev.codexpocket

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import dev.codexpocket.core.*
import java.io.File

/** Pure review fixtures. UI actions never reach the user's controller, projects or agent. */
internal class ReviewUiProbe(private val runner: Instrumentation) {
    private val context get() = runner.targetContext
    private val automation get() = runner.uiAutomation
    fun run() {
        var activity: MainActivity? = null
        val result = Bundle()
        var stage = "start"
        try {
            activity = runner.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)) as MainActivity
            runner.runOnMainSync { activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            val changes = listOf(
                GitChange("a.kt", status = "M ", added = 3, deleted = 1, stagedAdded = 3, stagedDeleted = 1, workingAdded = 0, workingDeleted = 0, fingerprint = "a"),
                GitChange("b.kt", status = " M", added = 2, deleted = 0, stagedAdded = 0, stagedDeleted = 0, workingAdded = 2, workingDeleted = 0, fingerprint = "b"),
                GitChange("new.kt", status = "??", added = 1, deleted = 0, workingAdded = 1, workingDeleted = 0, fingerprint = "new"),
            )
            val diff = "工作区\ndiff --git a/b.kt b/b.kt\n--- a/b.kt\n+++ b/b.kt\n@@ -1,120 +1,120 @@\n+val count = 1 // " + "long line ".repeat(25) + "\n" +
                (1..100).joinToString("\n") { " context $it" } + "\n@@ -201,90 +201,90 @@\n+return count\n" + (1..90).joinToString("\n") { " tail $it" }
            val task = TaskReviewReport("fixture-task", "/fixture", "/fixture/repo", "main", listOf(changes[1]), mapOf("b.kt" to diff), 100000)
            val state = mutableStateOf(PocketState(profile = HostProfile(id = "review-fixture", autoConnect = false), root = "/fixture", connected = true, threadReady = true,
                thread = ThreadInfo("fixture-review", "示例会话", "/fixture"), activeTurn = "running", changesRoot = "/fixture", changes = changes,
                reviewOverview = ReviewOverview("/fixture", "/fixture/repo", "main", changes = changes), taskReview = task))
            var sent = false
            val actions = ReviewActions(
                chooseReviewRange = { state.value = state.value.copy(reviewRange = it, diff = null) }, refreshChanges = {}, refreshDiff = {},
                openDiff = { change, side -> state.value = state.value.copy(diff = diff, diffPath = change.path, diffSide = side, diffFingerprint = change.fingerprint) },
                toggleReviewViewed = { path, side -> val s = state.value; val c = changes.first { it.path == path }; val key = reviewViewKey(s.reviewRange, if (s.reviewRange == "task") task.token else null, path, side)
                    state.value = s.copy(reviewViewed = if (s.reviewViewed[key] == c.fingerprint) s.reviewViewed - key else s.reviewViewed + (key to c.fingerprint)) },
                navigateReviewFile = {}, showReviewComments = { state.value = state.value.copy(showReviewComments = it) },
                relocateReviewComment = { state.value = state.value.copy(reanchorComment = it, showReviewComments = false) },
                cancelReviewRelocation = { state.value = state.value.copy(reanchorComment = null) },
                reanchorReviewComment = { id, line, body -> state.value = state.value.copy(reviewComments = state.value.reviewComments.map { if (it.id == id) it.copy(oldLine = line?.oldLine, newLine = line?.newLine, body = body, stale = false) else it }, reanchorComment = null) },
                addReviewComment = { line, body -> state.value = state.value.copy(reviewComments = state.value.reviewComments + ReviewComment(root = "/fixture", path = "b.kt", section = line?.section ?: "文件", oldLine = line?.oldLine, newLine = line?.newLine, body = body, fingerprint = "b")) },
                editReviewComment = { id, body -> state.value = state.value.copy(reviewComments = state.value.reviewComments.map { if (it.id == id) it.copy(body = body) else it }) },
                removeReviewComment = { id -> state.value = state.value.copy(reviewComments = state.value.reviewComments.filterNot { it.id == id }) },
                sendReviewComments = { ids, steer, newConversation -> check(!steer && !newConversation); sent = true; state.value = state.value.copy(reviewComments = state.value.reviewComments.filterNot { ids == null || it.id in ids }, showReviewComments = false) },
            )
            val screen = activity
            runner.runOnMainSync { screen.setContent { PocketTheme("light") { Surface(Modifier.fillMaxSize()) { Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                if (state.value.diff == null) ReviewChangesScreen(state.value, actions, {}) else ReviewDiffScreen(state.value, actions)
            } } } } }
            stage = "overview"; await { find("已看过 0 / 3 个文件") != null }
            screenshot("review-list.png")
            stage = "viewed"; tap("标记已看过"); await { find("已看过 1 / 3 个文件") != null }
            stage = "unread"; tap("未读"); await { find("a.kt") == null }
            stage = "file"; tap("全部"); tap("b.kt")
            await { find("开启自动换行") != null }
            stage = "wrap"; tap("开启自动换行"); await { find("关闭自动换行") != null }
            screenshot("review-diff.png")
            stage = "hunk"; tap("下一处修改"); await { find("2 / 2") != null }
            stage = "comment"; tap("评论 b.kt:201"); await { findEdit() != null }
            val text = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "检查空值") }
            check(requireNotNull(findEdit()).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, text))
            tap("保存评论"); await { find("发送 1 条评论") != null }
            tap("发送 1 条评论"); await { find("排队发送 1 条评论") != null }
            check(find("发送到：示例会话") != null)
            screenshot("review-comments.png")
            tap("排队发送 1 条评论"); await { sent && find("发送 1 条评论") == null }
            runner.runOnMainSync { state.value = state.value.copy(reviewComments = listOf(ReviewComment(id = "stale", root = "/fixture", path = "b.kt", section = "工作区", newLine = 3, body = "检查空值", stale = true)), showReviewComments = true) }
            await { find("位置已变化，请重新定位") != null }
            screenshot("review-stale.png")
            tap("重新定位"); await { find("选择行号，为评论重新定位") != null }
            tap("评论 b.kt:201"); await { find("确认评论新位置") != null }
            tap("保存评论"); await { state.value.reviewComments.single().stale == false }
            runner.runOnMainSync { state.value = state.value.copy(diff = null, reviewComments = emptyList()) }
            tap("本轮期间"); await { find("1 个文件") != null }
            screenshot("review-task.png")
            result.putString("stream", "PASS: review progress and unread filter, file diff and wrapping, hunk navigation, gutter comments, queued-send destination, stale relocation, task scope and hidden empty footer; fixture-only actions\n")
        } catch (e: Throwable) { result.putString("stream", "FAIL at $stage: ${e.javaClass.simpleName}: ${e.message}\n"); result.putString("failure", e.toString()); runCatching { screenshot("review-failure.png") } }
        finally { activity?.let { runner.runOnMainSync { it.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); it.recreate() } }; runner.finish(if (result.containsKey("failure")) Activity.RESULT_CANCELED else Activity.RESULT_OK, result) }
    }
    private fun await(condition: () -> Boolean) { repeat(100) { if (condition()) return; Thread.sleep(100) }; error("Review UI wait timed out") }
    private fun walk(node: AccessibilityNodeInfo?, check: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        if (check(node)) return node
        for (i in 0 until node.childCount) walk(node.getChild(i), check)?.let { return it }
        return null
    }
    private fun find(text: String): AccessibilityNodeInfo? {
        if (android.os.Build.VERSION.SDK_INT >= 33) automation.clearCache()
        return walk(automation.rootInActiveWindow) { it.text?.toString() == text || it.contentDescription?.toString() == text }
    }
    private fun findEdit() = walk(automation.rootInActiveWindow) { it.className?.toString() == "android.widget.EditText" }
    private fun tap(text: String) {
        await { find(text) != null }; var node = requireNotNull(find(text))
        while (!node.isClickable && node.parent != null) node = node.parent
        check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "Cannot click $text" }; Thread.sleep(200)
    }
    private fun screenshot(name: String) {
        Thread.sleep(350)
        val bitmap = requireNotNull(automation.takeScreenshot())
        File(context.cacheDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
}
