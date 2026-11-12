package dev.codexpocket

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import dev.codexpocket.core.mermaidDocument
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

internal class MermaidUiProbe(private val runner: Instrumentation) {
    fun run() {
        var activity: MainActivity? = null
        val result = Bundle()
        var stage = "start"
        try {
            val screen = runner.startActivitySync(Intent(runner.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)) as MainActivity
            activity = screen
            val cases = listOf(
                "flow" to "flowchart LR\n A[手机] --> B[Orbit]\n B --> C[电脑]",
                "sequence" to "sequenceDiagram\n participant A as 手机\n participant B as 电脑\n A->>B: 发起任务\n B-->>A: 返回结果",
                "class" to "classDiagram\n class Workspace\n class Agent\n Workspace --> Agent",
                "invalid" to "this is not a diagram",
                "escape" to "flowchart LR\n A[\"</script><script>window.pwned=1</script>\"]"
            )
            var previous: WebView? = null
            for ((name, source) in cases) {
                stage = name
                runner.runOnMainSync { screen.setContent { PocketTheme(if (name == "sequence") "dark" else "light") { Surface {
                    DocumentPane(source, "mermaid", null, null, Modifier.fillMaxSize())
                } } } }
                var view: WebView? = null
                repeat(60) { if (view == null) { runner.runOnMainSync { view = find(screen.window.decorView)?.takeIf { it !== previous } }; if (view == null) Thread.sleep(100) } }
                val web = requireNotNull(view)
                previous = web
                fun evaluate(js: String): String {
                    val q = LinkedBlockingQueue<String>()
                    runner.runOnMainSync { web.evaluateJavascript(js) { q.offer(it) } }
                    return q.poll(5, TimeUnit.SECONDS) ?: error("JavaScript callback timed out")
                }
                var state = ""
                repeat(100) { if (state !in setOf("\"ready\"", "\"error\"")) { state = evaluate("window.orbitMermaidState"); if (state !in setOf("\"ready\"", "\"error\"")) Thread.sleep(150) } }
                check(evaluate("typeof window.pwned") == "\"undefined\"") { "Source escaped into JavaScript" }
                if (name in setOf("flow", "sequence", "class")) {
                    check(state == "\"ready\"") { "Render failed: " + evaluate("document.getElementById('status').textContent") }
                    check(evaluate("!!document.querySelector('#diagram svg')") == "true")
                    if (name == "flow") {
                        check(evaluate("document.getElementById('diagram').textContent.includes('手机')") == "true")
                        Thread.sleep(350)
                        val bitmap = runner.uiAutomation.takeScreenshot()
                        requireNotNull(bitmap)
                        File(runner.targetContext.cacheDir, "mermaid-flow.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
                    }
                } else check(state in setOf("\"ready\"", "\"error\"")) { "Preview remained blank/loading" }
                if (name == "invalid") check(state == "\"error\"")
            }
            result.putString("stream", "PASS: bundled Mermaid flowchart, sequence and class SVG, Chinese labels, dark theme, syntax-error fallback and script-escape protection\n")
        } catch (e: Throwable) { result.putString("stream", "FAIL at $stage: ${e.message}\n"); result.putString("failure", e.toString()) }
        finally { activity?.let { a -> runner.runOnMainSync { a.recreate() } }; runner.finish(if (result.containsKey("failure")) Activity.RESULT_CANCELED else Activity.RESULT_OK, result) }
    }
    private fun find(v: View): WebView? {
        if (v is WebView) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
        return null
    }
}
