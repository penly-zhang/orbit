package dev.codexpocket

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf

/** Reproduces unrelated stream/state updates without making any remote agent requests. */
internal class MarkdownStabilityProbe(private val runner: Instrumentation) {
    fun run() {
        val result = Bundle()
        var activity: MainActivity? = null
        try {
            val screen = runner.startActivitySync(Intent(runner.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)) as MainActivity
            activity = screen
            val tick = mutableStateOf(0)
            val body = mutableStateOf("稳定正文 **粗体** [文件](/example/file.kt)")
            var openedAt = -1
            onMain { screen.setContent {
                val revision = tick.value
                PocketTheme("light") {
                    CompositionLocalProvider(LocalOpenProjectFile provides { openedAt = revision }) {
                        Column { Text("状态更新 $revision"); Markdown(body.value) }
                    }
                }
            } }
            Thread.sleep(1000)
            var target: TextView? = null
            var rewrites = 0
            onMain {
                target = find(screen.window.decorView)
                requireNotNull(target).addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { rewrites++ }
                    override fun afterTextChanged(s: Editable?) {}
                })
            }
            repeat(12) { i -> onMain { tick.value = i + 1 }; Thread.sleep(80) }
            onMain {
                check(rewrites == 0) { "Unchanged Markdown was rewritten $rewrites times for 12 unrelated updates" }
                check(find(screen.window.decorView) === target)
                val text = requireNotNull(target).text as android.text.Spanned
                text.getSpans(0, text.length, android.text.style.ClickableSpan::class.java).single().onClick(target!!)
                check(openedAt == 12) { "Link callback used stale conversation context" }
                body.value += " 新增内容"
            }
            Thread.sleep(250)
            onMain {
                check(requireNotNull(target).text.contains("新增内容"))
                check(rewrites == 1) { "Changed text should be assigned once, got $rewrites" }
            }
            result.putString("stream", "PASS: 12 unrelated updates caused zero text rewrites; fresh link callback; new text rendered once\n")
        } catch (error: Throwable) {
            result.putString("failure", error.toString())
            result.putString("stream", "FAIL: ${error.message}\n")
        } finally {
            activity?.let { onMain { it.recreate() } }
            runner.finish(if (result.containsKey("failure")) Activity.RESULT_CANCELED else Activity.RESULT_OK, result)
        }
    }
    private fun onMain(block: () -> Unit) {
        var failure: Throwable? = null
        runner.runOnMainSync { try { block() } catch (error: Throwable) { failure = error } }
        failure?.let { throw it }
    }
    private fun find(view: View): TextView? {
        if (view is TextView && view.text.startsWith("稳定正文")) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
        return null
    }
}
