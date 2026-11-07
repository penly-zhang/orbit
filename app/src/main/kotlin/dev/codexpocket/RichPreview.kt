package dev.codexpocket

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.*
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.codexpocket.core.*
import kotlinx.coroutines.*
import java.io.ByteArrayInputStream
import java.net.URI
import java.util.UUID

internal data class ArtifactContext(val profile: String, val cwd: String, val vm: PocketViewModel)
internal val LocalArtifactContext = staticCompositionLocalOf<ArtifactContext?> { null }
data class WebPreview(val source: String, val profile: String, val url: String? = null, val loading: Boolean = false, val error: String? = null, val tunnel: LoopbackTunnel? = null, val markdown: String? = null)

@Composable internal fun WebPreviewDialog(vm: PocketViewModel) {
    val preview by vm.webPreview.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    preview?.let { target ->
        val connected = state.connections[target.profile]?.connected == true
        var lost by remember(target.profile, target.source) { mutableStateOf(false) }
        LaunchedEffect(connected, target.source) {
            if (isLoopbackLink(target.source)) {
                if (!connected) lost = true
                else if (lost) { lost = false; vm.openLink(target.profile, target.source, "/") }
            }
        }
        Dialog(onDismissRequest = vm::closeWebPreview, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            DialogBars()
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.safeDrawingPadding()) {
                    Row {
                        IconButton(onClick = vm::closeWebPreview) { Icon(Icons.Outlined.Close, "关闭网页") }
                        Column(Modifier.weight(1f).padding(vertical = 10.dp)) { Text(runCatching { URI(target.source).host }.getOrNull().orEmpty().ifBlank { "网页预览" }, fontSize = 15.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold); Text(target.source, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, fontSize = 12.sp, color = Muted) }
                        val context = LocalContext.current
                        IconButton(onClick = { vm.openLink(target.profile, target.source, "/") }, enabled = !target.loading) { Icon(Icons.Outlined.Refresh, "刷新预览") }
                        IconButton(enabled = target.url != null, onClick = { target.url?.let { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) } } }) { Icon(Icons.Outlined.OpenInBrowser, "在手机浏览器打开") }
                    }
                    when {
                        target.loading -> CircularProgressIndicator(Modifier.padding(24.dp))
                        target.error != null -> Box(Modifier.padding(16.dp)) { StateNotice("预览加载失败", target.error, "重试", { vm.openLink(target.profile, target.source, "/") }, failure = true) }
                        target.markdown != null -> NetworkMarkdown(target, vm, Modifier.weight(1f).fillMaxWidth())
                        target.url != null -> if (target.url.startsWith("http://") && !isLoopbackLink(target.url)) {
                            Text("此 HTTP 链接可在手机浏览器中打开。", modifier = Modifier.padding(20.dp))
                        } else key(target.url) { BrowserPane(url = target.url, context = ArtifactContext(target.profile, "/", vm), remote = target.source, localPort = target.tunnel?.port, modifier = Modifier.weight(1f).fillMaxWidth()) }
                    }
                }
            }
        }
    }
}

@Composable private fun NetworkMarkdown(target: WebPreview, vm: PocketViewModel, modifier: Modifier) {
    fun resolve(link: String): String = if (isRemoteFileLink(link) || link.startsWith('#')) URI(target.source).resolve(link).toString() else link
    val loader = remember(target.profile, target.source) { ConversationImageLoader { source, _ -> vm.readNetworkImage(target.profile, resolve(source)) } }
    CompositionLocalProvider(LocalArtifactContext provides null, LocalOpenProjectFile provides { link -> vm.openLink(target.profile, resolve(link), "/") }) {
        Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp)) { ConversationBody(target.markdown.orEmpty(), emptyList(), "/", "web:${target.profile}:${target.source}", loader, expandArtifacts = false) }
    }
}

@Composable internal fun DocumentCard(source: String, format: String, context: ArtifactContext?, root: String? = context?.cwd, title: String = "${format.uppercase()} 预览") {
    var code by remember(source) { mutableStateOf(false) }
    var expanded by remember(source) { mutableStateOf(false) }
    Surface(color = Panel, shape = MaterialTheme.shapes.medium, border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column {
            Row(Modifier.padding(horizontal = 10.dp)) {
                Text(title, fontSize = 12.sp, modifier = Modifier.weight(1f).padding(vertical = 14.dp))
                TextButton(onClick = { code = !code }) { Text(if (code) "预览" else "源码", fontSize = 12.sp) }
                IconButton(onClick = { expanded = true }) { Icon(Icons.Outlined.OpenInFull, "全屏查看成果") }
            }
            if (code) androidx.compose.foundation.text.selection.SelectionContainer { Text(source, fontSize = 12.sp, modifier = Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()).padding(12.dp)) }
            else DocumentPane(source, format, context, root, Modifier.fillMaxWidth().height(260.dp))
        }
    }
    if (expanded) Dialog(onDismissRequest = { expanded = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        DialogBars()
        Surface(Modifier.fillMaxSize()) { Column(Modifier.safeDrawingPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = { expanded = false }) { Icon(Icons.Outlined.ArrowBack, "关闭预览") }; Text(format.uppercase() + " 预览", style = MaterialTheme.typography.titleMedium) }
            DocumentPane(source, format, context, root, Modifier.weight(1f).fillMaxWidth())
        } }
    }
}
@Composable internal fun DocumentPane(source: String, format: String, context: ArtifactContext?, root: String?, modifier: Modifier) {
    if (format in setOf("md", "markdown")) Column(modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
        // Prevent recursively expanding linked documents inside a document preview.
        if (context == null) Markdown(source)
        else CompositionLocalProvider(LocalArtifactContext provides context.copy(cwd = root ?: context.cwd), LocalOpenProjectFile provides { link -> context.vm.openLink(context.profile, link, root ?: context.cwd) }) {
            ConversationBody(source, emptyList(), root ?: "/", "document:${context.profile}", context.vm.imageLoaderFor(context.profile), expandArtifacts = false)
        }
    } else key(source, root, context?.profile) { BrowserPane(html = source, context = context, root = root, modifier = modifier) }
}

@Composable internal fun LinkedDocument(link: String, label: String) {
    val context = LocalArtifactContext.current
    val path = remember(link, context?.cwd) { runCatching { remoteFileLink(link, context?.cwd ?: "/") }.getOrNull() }
    val result by produceState<Result<FilePreview>?>(null, link, context?.profile, context?.cwd) {
        if (context != null && path != null) {
            try { value = Result.success(context.vm.readDocument(context.profile, path)) }
            catch (c: CancellationException) { throw c }
            catch (e: Exception) { value = Result.failure(e) }
        }
    }
    val doc = result?.getOrNull()
    if (doc != null) DocumentCard(doc.bytes.toString(Charsets.UTF_8), doc.path.substringAfterLast('.').lowercase(), context, doc.path.substringBeforeLast('/'), label)
    else Column {
        Text(if (result == null && context != null) "正在加载 $label…" else result?.exceptionOrNull()?.message ?: label, fontSize = 12.sp, color = Muted)
        if (context != null) TextButton(onClick = { context.vm.openLink(context.profile, link, context.cwd) }) { Text("打开 $label") }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable internal fun BrowserPane(modifier: Modifier, context: ArtifactContext?, html: String? = null, root: String? = null, url: String? = null, remote: String? = null, localPort: Int? = null) {
    val origin = remember(html, root, context?.profile) { "https://${UUID.randomUUID()}.pocket.invalid" }
    var view by remember { mutableStateOf<WebView?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var back by remember { mutableStateOf(false) }
    BackHandler(url != null && back) { view?.goBack() }
    Column(modifier) {
        error?.let { Text("页面加载失败：$it", color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(8.dp)); TextButton(onClick = { error = null; view?.reload() }) { Text("重新加载") } }
        AndroidView(factory = { ctx -> WebView(ctx).apply {
            view = this
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false; settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.setSupportMultipleWindows(false)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(v: WebView, target: String?) { back = v.canGoBack() }
                override fun onReceivedError(v: WebView, request: WebResourceRequest, failure: WebResourceError) { if (request.isForMainFrame) error = failure.description.toString() }
                override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                    val address = request.url.toString()
                    if (address.startsWith(origin + "/")) {
                        if (request.isForMainFrame && root != null && context != null && request.url.path?.substringAfterLast('.')?.lowercase() in setOf("md", "markdown")) {
                            runCatching { artifactResourcePath(root, request.url.encodedPath ?: "/") }.getOrNull()?.let { context.vm.openLink(context.profile, it, root) }
                            return true
                        }
                        return false
                    }
                    if (url != null && isWebLink(address)) {
                        if (isLoopbackLink(address)) {
                            if (localPort != null && URI(address).port == localPort) return false
                            if (remote != null && isLoopbackLink(remote) && localPort != null && loopbackPort(address) == loopbackPort(remote)) { v.loadUrl(tunneledUrl(address, localPort)); return true }
                        } else if (address.startsWith("https://")) return false
                    }
                    if (!request.isForMainFrame || !request.hasGesture()) return true
                    if (context != null && (isWebLink(address) || isRemoteFileLink(address))) context.vm.openLink(context.profile, address, root ?: context.cwd)
                    return true
                }
                override fun shouldInterceptRequest(v: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val address = request.url.toString()
                    if (!address.startsWith(origin + "/")) return if (html != null && isLoopbackLink(address)) denied() else null
                    if (context == null || root == null || request.method != "GET") return denied()
                    return try {
                        val file = runBlocking(Dispatchers.IO) { context.vm.readWebResource(context.profile, root, request.url.encodedPath ?: "/") }
                        WebResourceResponse(artifactMime(file.path), "UTF-8", ByteArrayInputStream(file.bytes))
                    } catch (_: Exception) { denied() }
                }
            }
            if (html != null) {
                val page = if (Regex("(?i)<(?:!doctype|html|head|body)\\b").containsMatchIn(html)) html else "<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'></head><body>$html</body></html>"
                loadDataWithBaseURL("$origin/", page, "text/html", "UTF-8", null)
            } else if (url != null) loadUrl(url)
        } }, modifier = Modifier.weight(1f).fillMaxWidth())
    }
    DisposableEffect(Unit) { onDispose { view?.stopLoading(); view?.webViewClient = WebViewClient(); view?.loadUrl("about:blank"); view?.destroy(); view = null } }
}
private fun denied() = WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream("Preview resource unavailable".toByteArray()))
