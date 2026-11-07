package dev.codexpocket

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Exercises the production preview composable on a real Android WebView; no remote tasks/files. */
class PreviewInstrumentation : Instrumentation() {
    private var scenario = ""
    override fun onCreate(arguments: Bundle?) { scenario = arguments?.getString("scenario").orEmpty(); super.onCreate(arguments); start() }
    override fun onStart() {
        if (scenario == "conversation") { ConversationUiProbe(this).run(); return }
        if (scenario == "session-gestures") { ProductUiProbe(this).run(gesturesOnly = true); return }
        if (scenario == "navigation-tools") { ProductUiProbe(this).run(toolsOnly = true); return }
        if (scenario == "conversation-home") { ProductUiProbe(this).run(homeOnly = true); return }
        if (scenario == "session-list") { ProductUiProbe(this).run(projectsOnly = true); return }
        if (scenario == "product") { ProductUiProbe(this).run(); return }
        if (scenario == "context") { ContextUiProbe(this).run(); return }
        if (scenario == "composer") { ComposerUiProbe(this).run(); return }
        if (scenario == "review") { ReviewUiProbe(this).run(); return }
        var activity: MainActivity? = null
        val result = Bundle()
        try {
            activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)) as MainActivity
            val html = """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><style>
                body{margin:0;background:#f4f7fa;font:16px/1.6 sans-serif;color:#173a37;padding:24px}main{background:white;padding:24px;border-radius:24px;box-shadow:0 8px 32px #173a3710}h1{font-size:26px;line-height:1.4}small{color:#56736a}button{border:0;border-radius:12px;background:#087c68;color:white;padding:14px 24px;font:600 16px sans-serif}svg{width:100%;height:120px;margin:20px 0}img{width:12px;height:12px}p{color:#526960}</style></head><body><main><small>ORBIT · HTML PREVIEW</small><h1>手机成果预览</h1><p>HTML、SVG 与图片在手机中直接呈现。</p><svg viewBox="0 0 280 100"><rect x="0" y="55" width="70" height="45" rx="8" fill="#9ddbc7"/><rect x="90" y="25" width="70" height="75" rx="8" fill="#50b598"/><rect id="bar" x="180" y="0" width="70" height="100" rx="8" fill="#087c68"/></svg><button id="counter" onclick="this.innerText='已点击 1 次'">点击测试</button><p><img id="image" src="data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+j4XcAAAAASUVORK5CYII="/> 图片与脚本加载测试</p></main></body></html>"""
            val screen = activity
            runOnMainSync { screen.setContent { PocketTheme("light") { BrowserPane(Modifier.fillMaxSize(), context = null, html = html) } } }
            var web: WebView? = null
            repeat(60) { if (web == null) { runOnMainSync { web = findWeb(screen.window.decorView) }; if (web == null) Thread.sleep(100) } }
            val browser = requireNotNull(web) { "Production WebView not created" }
            fun evaluate(js: String): String {
                val queue = LinkedBlockingQueue<String>()
                runOnMainSync { browser.evaluateJavascript(js) { queue.offer(it) } }
                return queue.poll(5, TimeUnit.SECONDS) ?: error("JavaScript callback timed out")
            }
            var ready = false
            repeat(40) { if (!ready) { ready = evaluate("!!document.getElementById('counter') && document.getElementById('image').complete && document.getElementById('image').naturalWidth===1") == "true"; if (!ready) Thread.sleep(150) } }
            check(ready) { "HTML/image failed to render" }
            check(evaluate("document.getElementById('bar').getAttribute('fill')") == "\"#087c68\"") { "SVG missing" }
            check(evaluate("document.getElementById('counter').click();document.getElementById('counter').innerText").contains("1")) { "Interactive JavaScript failed" }
            Thread.sleep(250)
            runOnMainSync {
                val bitmap = Bitmap.createBitmap(browser.width, browser.height, Bitmap.Config.ARGB_8888)
                browser.draw(android.graphics.Canvas(bitmap))
                File(targetContext.cacheDir, "pocket-rich-preview-test.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            }
            result.putString("stream", "PASS: production HTML preview, SVG, data image and interactive button rendered on Android WebView\n")
        } catch (error: Throwable) { result.putString("stream", "FAIL: ${error.javaClass.simpleName}: ${error.message}\n"); result.putString("failure", error.toString()) }
        finally {
            activity?.let { screen -> runOnMainSync { screen.recreate() } }
            finish(if (result.containsKey("failure")) Activity.RESULT_CANCELED else Activity.RESULT_OK, result)
        }
    }
    private fun findWeb(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findWeb(view.getChildAt(index))?.let { return it }
        return null
    }
}
