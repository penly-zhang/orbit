package dev.codexpocket

import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.codexpocket.core.*
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

class MainActivity : ComponentActivity() {
    private val pocket: PocketViewModel by lazy { androidx.lifecycle.ViewModelProvider(this)[PocketViewModel::class.java] }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pocket.openSessionKey(intent?.getStringExtra("sessionKey").orEmpty())
        setContent {
            val prefs by pocket.preferences.collectAsStateWithLifecycle()
            val dark = prefs.theme == "dark" || (prefs.theme == "system" && isSystemInDarkTheme())
            DisposableEffect(dark) {
                val bars = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT) else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
                onDispose { }
            }
            PocketTheme(prefs.theme) { PocketApp(pocket) }
        }
    }
    override fun onStart() { super.onStart(); pocket.appVisible(true) }
    override fun onStop() { pocket.appVisible(false); super.onStop() }
    override fun onNewIntent(intent: android.content.Intent) { super.onNewIntent(intent); setIntent(intent); pocket.openSessionKey(intent.getStringExtra("sessionKey").orEmpty()) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun PocketApp(vm: PocketViewModel = viewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    var editHost by remember { mutableStateOf<HostProfile?>(null) }
    var review by remember { mutableStateOf(false) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var projectMenu by rememberSaveable { mutableStateOf(false) }
    var searching by rememberSaveable(s.profile?.id, s.thread?.id) { mutableStateOf(false) }
    val showingProjects = s.browsingProjects
    val showingConversation = !showingProjects && (s.tab == 0 || s.project == null)
    val sessionUi = rememberSaveableStateHolder()
    var pickerProfile by rememberSaveable { mutableStateOf("") }
    var pickerTab by rememberSaveable { mutableIntStateOf(0) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_OUTGOING_IMAGES)) { uris ->
        vm.importImages(pickerProfile, pickerTab, uris)
    }
    var documentProfile by rememberSaveable { mutableStateOf("") }
    var documentTab by rememberSaveable { mutableIntStateOf(0) }
    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> vm.importDocuments(documentProfile, documentTab, uris) }
    val exportDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri -> uri?.let(vm::exportConversation) }
    val snackbar = remember { SnackbarHostState() }
    var requestId by remember { mutableStateOf<Pair<String, JsonElement>?>(null) }
    fun navigateBack() {
        when { settings -> settings = false; showingConversation && searching -> searching = false; showingProjects -> vm.returnToConversation(); s.preview != null || s.diff != null -> vm.closePreview(); s.tab != 0 -> vm.tab(0); else -> vm.showProjects() }
    }
    BackHandler(settings || showingProjects || searching || s.tab != 0 || s.preview != null || s.diff != null) { navigateBack() }
    CompositionLocalProvider(LocalDocumentPicker provides { profile, number -> documentProfile = profile; documentTab = number; documentPicker.launch(arrayOf("*/*")) }, LocalExportConversation provides { text -> vm.pendingExport = text; exportDocument.launch("orbit-conversation.md") }, LocalImagePicker provides { profileId, number ->
        pickerProfile = profileId; pickerTab = number
        imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }) {
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(expandedHeight = if (showingConversation && !settings) 40.dp else if (showingProjects && !settings) 56.dp else 64.dp, title = {
                Column {
                    Text(if (settings) "设置" else if (showingProjects) "项目与会话" else if (showingConversation && s.thread != null) s.thread!!.title else if (s.project != null && s.tab != 0) s.project!!.name else if (showingConversation) "新会话" else "Orbit", fontWeight = if (showingConversation) FontWeight.SemiBold else FontWeight.Bold, fontSize = if (showingConversation) 17.sp else 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!showingConversation && (settings || !showingProjects)) Text(if (settings) "${s.connections.values.count { it.connected }} 台电脑已连接" else if (s.reconnecting) "连接中断，正在自动重连 · 第 ${s.reconnectAttempt} 次" else if (s.connecting) "正在建立 SSH 连接…" else if (s.connected) "● ${s.project?.name?.let { "$it · " }.orEmpty()}${s.profile?.name} · SSH" else s.profile?.let { "${it.name} · 已断开" } ?: "你的项目，随身继续", maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, color = if (s.connected) Mint else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }, navigationIcon = {
                IconButton(onClick = ::navigateBack, modifier = Modifier.size(if (showingConversation && !settings) 40.dp else 48.dp)) {
                    Icon(if (showingConversation && !settings) Icons.Outlined.Menu else Icons.Outlined.ArrowBack,
                        if (showingConversation && !settings) "项目与会话列表" else if (showingProjects && !settings) "返回会话" else "返回")
                }
            }, actions = {
                if (!settings) {
                    if (showingConversation || showingProjects) IconButton(onClick = vm::newConversation, enabled = !s.creatingConversation) { Icon(Icons.Outlined.Add, "新建会话") }
                    if (showingConversation && s.thread != null) key(s.profile?.id, s.thread?.id) { ConversationActions(s, vm, searching) { searching = !searching } }
                    if (!showingProjects && !showingConversation) Box {
                        IconButton(onClick = { projectMenu = true }) { Icon(Icons.Outlined.MoreHoriz, "项目操作") }
                        DropdownMenu(projectMenu, { projectMenu = false }) { WorkspaceMenuItems(s, vm) { projectMenu = false } }
                    }
                    if (s.connected && !showingProjects && !showingConversation) IconButton(onClick = { when (s.tab) { 1 -> vm.loadFiles(s.directory); 2 -> if (s.diff != null) vm.refreshDiff() else vm.refreshChanges(); else -> if (s.thread != null) vm.refreshThread() else if (s.project != null) vm.loadThreads() else vm.refreshProjects() } }) { Icon(Icons.Outlined.Refresh, "刷新") }
                    if (showingProjects || (showingConversation && s.thread == null)) IconButton(onClick = { settings = true }) { Icon(Icons.Outlined.Settings, "设置") }
                }
            }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Paper))
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            s.error?.let { issue -> Box(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) { StateNotice(if (issue.contains("发送结果未确认")) "消息发送状态待核对" else "操作未完成", issue, dismiss = vm::clearError, failure = true) } }
            if (!settings && s.pending.isNotEmpty()) Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer).padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.PendingActions, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                Text("${s.pending.size} 项等待确认", fontSize = 13.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { s.pending.firstOrNull()?.let { requestId = it.profileId to it.id } }) { Text("处理") }
            }
            if (s.loading || s.connecting || s.reconnecting) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Mint)
            if (!settings && showingConversation && s.sessionTabs.size > 1) {
                SessionTabStrip(s, vm)
                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            }
            when {
                settings -> SettingsScreen(s, vm, { editHost = it })
                showingProjects -> Box(Modifier.fillMaxSize().sessionSwipe("project-navigation") { if (it > 0) vm.returnToConversation() }) {
                    ProjectsScreen(s, vm, { settings = true }, allowArchiveSwipe = false)
                }
                showingConversation && s.thread == null -> NewConversationScreen(s, vm, { settings = true })
                else -> {
                    if (s.project!!.roots.size > 1) Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        s.project!!.roots.forEach { root -> FilterChip(selected = s.root == root, onClick = { vm.changeRoot(root) }, label = { Text(root.substringAfterLast('/')) }) }
                    }
                    when (s.tab) {
                        0 -> if (s.thread != null) {
                            val sessionKey = "${s.profile?.id}:${s.thread!!.id}"
                            // Dispose the old render tree on tab changes. SaveableStateProvider
                            // retains each tab's scroll state without reusing its live view layers.
                            key(sessionKey) {
                                sessionUi.SaveableStateProvider(sessionKey) { Conversation(s, vm, searching, { searching = false }) }
                            }
                        } else ProjectsScreen(s, vm, { settings = true })
                        1 -> if (s.preview != null) key(s.profile?.id, s.preview!!.path) { FilePreviewScreen(s.preview!!, s, vm) } else FilesScreen(s, vm)
                        2 -> if (s.diff != null) sessionUi.SaveableStateProvider("review:${s.profile?.id}:${s.root}:${s.reviewRange}:${if (s.reviewRange == "task") s.taskReview?.token else ""}:${s.diffPath}:${s.diffSide}") { ReviewDiffScreen(s, vm) } else ReviewChangesScreen(s, vm, { review = true })
                    }
                }
            }
        }
    }
    }
    WebPreviewDialog(vm)
    editHost?.let { profile -> HostEditor(profile, vm.storedCredentials(profile.id), onDismiss = { editHost = null }) { updated, secret, remember -> editHost = null; vm.saveAndConnect(updated, secret, remember) } }
    s.trust?.let { identity ->
        AlertDialog(onDismissRequest = vm::dismissTrust, icon = { Icon(Icons.Outlined.VerifiedUser, null) },
            title = { Text(if (identity.changed) "主机密钥发生变化" else "确认这台电脑") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (identity.changed) "已阻止连接。请先在电脑上核实主机密钥；如确认是更换电脑或系统重装，请删除旧连接后重新添加。" else "首次连接，请与电脑上的 SSH 主机指纹核对。确认后，这台电脑的指纹将保存在手机中。")
                Text(identity.endpoint.ifBlank { "${s.trustProfile?.username}@${s.trustProfile?.host}:${s.trustProfile?.port}" }, fontWeight = FontWeight.Bold)
                if (identity.hopId != null) Text("正在验证跳板：${identity.hopId}", fontSize = 12.sp)
                SelectionContainer { Text("${identity.algorithm}\n${identity.fingerprint}", fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
                Text("电脑上可运行：\nfor f in /etc/ssh/ssh_host_*_key.pub; do ssh-keygen -lf \"\$f\"; done", fontSize = 12.sp)
            } }, confirmButton = { if (!identity.changed) TextButton(onClick = vm::acceptHostKey) { Text("指纹一致，连接") } }, dismissButton = { TextButton(onClick = vm::dismissTrust) { Text("返回") } })
    }
    if (review) ReviewDialog({ review = false }) { target -> review = false; vm.review(target) }
    s.pending.find { requestId == (it.profileId to it.id) }?.let { request -> RequestDialog(request, vm, listOfNotNull(s.profiles.find { it.id == request.profileId }?.connectionLabel(s.profiles), s.sessionTabs.find { it.profileId == request.profileId && it.thread.id == request.params.text("threadId") }?.let { "${it.project.name} · ${it.thread.title}" }).joinToString(" · ")) { requestId = null } }
}

@Composable internal fun Markdown(text: String) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val lineHeightPx = with(density) { 27.sp.toPx() }
    val paragraphGapPx = with(density) { 12.dp.roundToPx() }
    val openFile = LocalOpenProjectFile.current
    val textColor = Ink.toArgb()
    val linkColor = Mint.toArgb()
    val codeColor = MaterialTheme.colorScheme.surfaceContainer.toArgb()
    val markwon = remember(context, textColor, codeColor, linkColor, paragraphGapPx) { Markwon.builder(context).usePlugin(TablePlugin.create(context)).usePlugin(object : io.noties.markwon.AbstractMarkwonPlugin() {
        override fun configureTheme(builder: io.noties.markwon.core.MarkwonTheme.Builder) { builder.codeTextColor(textColor).codeBlockTextColor(textColor).codeBackgroundColor(codeColor).codeBlockBackgroundColor(codeColor).linkColor(linkColor).blockMargin(paragraphGapPx) }
    }).build() }
    AndroidView(factory = { ctx -> TextView(ctx).apply { textSize = 16f; includeFontPadding = false; setTextIsSelectable(true); movementMethod = android.text.method.LinkMovementMethod.getInstance() } }, update = { view ->
        // TextView spacing is in physical pixels. Match Compose's scaled 27sp baseline rhythm.
        view.setLineSpacing((lineHeightPx - view.paint.fontSpacing).coerceAtLeast(0f), 1f)
        view.setTextColor(textColor); view.setLinkTextColor(linkColor)
        markwon.setMarkdown(view, text)
        if (openFile != null) {
            val content = android.text.SpannableStringBuilder(view.text)
            val matcher = android.util.Patterns.WEB_URL.matcher(content)
            while (matcher.find()) {
                if (content.getSpans(matcher.start(), matcher.end(), android.text.style.URLSpan::class.java).isEmpty()) {
                    val value = matcher.group().orEmpty()
                    if (isWebLink(value)) content.setSpan(android.text.style.URLSpan(value), matcher.start(), matcher.end(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            content.getSpans(0, content.length, android.text.style.URLSpan::class.java).forEach { span ->
                if (isRemoteFileLink(span.url) || isWebLink(span.url)) {
                    val start = content.getSpanStart(span); val end = content.getSpanEnd(span)
                    content.removeSpan(span)
                    content.setSpan(object : android.text.style.ClickableSpan() { override fun onClick(widget: android.view.View) { openFile(span.url) } }, start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            view.text = content
        }
    }, modifier = Modifier.fillMaxWidth())
}

@Composable internal fun HostEditor(profile: HostProfile, secret: Credentials, onDismiss: () -> Unit, save: (HostProfile, Credentials, Boolean) -> Unit) {
    var route by remember { mutableStateOf(profile) }
    val selectedEngine = AgentEngines.default.descriptors.find { it.id == route.engineId }
    var hopSecrets by remember { mutableStateOf(secret.jumps) }
    var name by remember { mutableStateOf(profile.name) }
    var host by remember { mutableStateOf(profile.host) }
    var port by remember { mutableStateOf(profile.port.toString()) }
    var username by remember { mutableStateOf(profile.username) }
    var path by remember { mutableStateOf(profile.codexPath) }
    var proxyEnabled by remember { mutableStateOf(profile.proxyEnabled) }
    var autoConnect by remember { mutableStateOf(profile.autoConnect) }
    var proxyHost by remember { mutableStateOf(profile.proxyHost) }
    var proxyPort by remember { mutableStateOf(profile.proxyPort.toString()) }
    var auth by remember { mutableStateOf(profile.authType) }
    var password by remember { mutableStateOf(secret.password) }
    var key by remember { mutableStateOf(secret.privateKey) }
    var passphrase by remember { mutableStateOf(secret.passphrase) }
    var rememberSecret by remember { mutableStateOf(secret.privateKey.isNotBlank() || secret.password.isNotBlank()) }
    var advanced by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching { withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)!!.use { input ->
                val bytes = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) { val n = input.read(buffer); if (n < 0) break; require(bytes.size() + n <= 1024 * 1024) { "私钥文件过大" }; bytes.write(buffer, 0, n) }
                bytes.toString("UTF-8")
            } } }
                .onSuccess { key = it; importError = null }.onFailure { importError = it.message }
        }
    }
    FullPage(if (profile.host.isBlank()) "添加连接" else "编辑连接", onDismiss, bottom = {
        Surface(color = Panel) { Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Button(modifier = Modifier.fillMaxWidth(), onClick = { save(route.copy(name = name.ifBlank { host }, host = host, port = port.toIntOrNull() ?: 0, username = username, codexPath = path, authType = auth, proxyEnabled = proxyEnabled, autoConnect = autoConnect, proxyHost = proxyHost, proxyPort = proxyPort.toIntOrNull() ?: 0), Credentials(password, key, passphrase, hopSecrets.filterKeys { id -> route.jumpHosts.any { it.id == id } }), rememberSecret) }, enabled = (selectedEngine != null && (!selectedEngine.requiresExecutable || path.isNotBlank())) && host.isNotBlank() && username.isNotBlank() && port.toIntOrNull() in 1..65535 && (!proxyEnabled || (proxyHost.isNotBlank() && proxyPort.toIntOrNull() in 1..65535)) && (route.routeMode != "remoteSsh" || route.remoteTarget.isNotBlank()) && route.jumpHosts.all { it.host.isNotBlank() && it.username.isNotBlank() && it.port in 1..65535 && (if (it.authType == "key") !hopSecrets[it.id]?.privateKey.isNullOrBlank() else !hopSecrets[it.id]?.password.isNullOrBlank()) } && (if (auth == "key") key.isNotBlank() else password.isNotBlank())) { Text(if (autoConnect) "保存并连接" else "保存") }
        } }
    }) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("连接信息", style = MaterialTheme.typography.titleSmall)
            Text("通过 SSH 安全访问，任务在电脑上执行。", color = Muted, fontSize = 12.sp)
                OutlinedTextField(name, { name = it }, label = { Text("名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("启用连接", fontSize = 14.sp); Text("保存后立即连接，断线自动重连", fontSize = 12.sp, color = Muted) }; Switch(autoConnect, { autoConnect = it }) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(host, { host = it.trim() }, label = { Text("电脑地址") }, placeholder = { Text("电脑 IP 或主机名") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(port, { port = it.filter(Char::isDigit) }, label = { Text("端口") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.width(88.dp))
                }
                OutlinedTextField(username, { username = it.trim() }, label = { Text("SSH 用户名") }, placeholder = { Text("username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { FilterChip(auth == "password", { auth = "password" }, label = { Text("密码") }); FilterChip(auth == "key", { auth = "key" }, label = { Text("SSH 密钥") }) }
                if (auth == "password") OutlinedTextField(password, { password = it }, label = { Text("SSH 登录密码") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                else {
                    OutlinedButton(onClick = { launcher.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Key, null); Text(if (key.isBlank()) "选择私钥文件" else "私钥已导入 · 重新选择") }
                    OutlinedTextField(passphrase, { passphrase = it }, label = { Text("私钥口令（可选）") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (importError != null) Text(importError!!, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(rememberSecret, { rememberSecret = it }); Text("加密保存登录信息", fontSize = 13.sp) }
                TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "收起高级设置" else "高级设置：Agent、跳板与代理") }
                if (advanced) {
                SshRouteEditor(route.copy(host = host, port = port.toIntOrNull() ?: 22, username = username, authType = auth), hopSecrets, { updated ->
                    route = updated; host = updated.host; port = updated.port.toString(); username = updated.username; auth = updated.authType
                }, { hopSecrets = it })
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(proxyEnabled, { proxyEnabled = it }); Text("通过 SOCKS5 代理连接", fontSize = 13.sp) }
                if (proxyEnabled) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(proxyHost, { proxyHost = it.trim() }, label = { Text("代理地址") }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(proxyPort, { proxyPort = it.filter(Char::isDigit) }, label = { Text("代理端口") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.width(100.dp))
                    }
                    Text("TailSocks Proxy 模式：填写其本地 SOCKS5 地址和端口。支持无认证代理；电脑地址仍填写电脑的 Tailscale IP。", fontSize = 12.sp, color = Muted)
                }
                Text("Agent 引擎", style = MaterialTheme.typography.titleSmall)
                AgentEngines.default.descriptors.forEach { engine ->
                    FilterChip(selected = route.engineId == engine.id, onClick = { route = route.copy(engineId = engine.id) }, label = { Text(engine.name) })
                }
                Text("兼容 Agent 需支持 Codex app-server proxy；其他协议暂未接入。", color = Muted, fontSize = 12.sp)
                OutlinedTextField(path, { path = it }, label = { Text(if (selectedEngine?.requiresExecutable == true) "Agent 可执行文件（必填）" else "Agent 可执行文件（可选）") }, placeholder = { Text("自动查找，例如 ~/.local/bin/codex") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("连接前请在电脑上启动所选 Agent 的 app-server 服务。手机和电脑需处于可互通的网络；在外网可通过已有 VPN 访问。", fontSize = 12.sp, color = Muted, lineHeight = 19.sp)
                }
        }
    }
}

@Composable private fun ReviewDialog(dismiss: () -> Unit, start: (ReviewTarget) -> Unit) {
    var kind by remember { mutableStateOf(0) }
    var value by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Code Review") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("将在当前目录新建独立审查会话。")
            listOf("未提交的修改", "相对分支", "指定 Commit").forEachIndexed { index, label -> Row(Modifier.fillMaxWidth().clickable { kind = index }, verticalAlignment = Alignment.CenterVertically) { RadioButton(kind == index, { kind = index }); Text(label) } }
            if (kind != 0) OutlinedTextField(value, { value = it }, label = { Text(if (kind == 1) "分支名称，例如 main" else "Commit SHA") }, singleLine = true)
        }
    }, confirmButton = { TextButton(onClick = { start(when (kind) { 1 -> ReviewTarget.Branch(value.trim()); 2 -> ReviewTarget.Commit(value.trim()); else -> ReviewTarget.UncommittedChanges }) }, enabled = kind == 0 || value.isNotBlank()) { Text("开始审查") } }, dismissButton = { TextButton(onClick = dismiss) { Text("取消") } })
}

@Composable private fun RequestDialog(request: PendingRequest, vm: PocketViewModel, profileName: String, later: () -> Unit) {
    if (request.kind == RequestKind.MCP) { McpRequestDialog(request, profileName, vm::answerMcp, later); return }
    val questions = request.params.array("questions").mapNotNull { it as? JsonObject }
    var answers by remember(request.profileId, request.id) { mutableStateOf<Map<String, String>>(emptyMap()) }
    if (request.kind == RequestKind.QUESTIONS) {
        AlertDialog(onDismissRequest = later, title = { Text("Agent 需要你的回答") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("电脑：$profileName", fontSize = 12.sp, color = Muted)
                questions.forEach { question ->
                    val id = question.text("id")
                    Text(question.text("question"), fontWeight = FontWeight.Medium)
                    question.array("options").mapNotNull { it as? JsonObject }.forEach { option -> AssistChip(onClick = { answers = answers + (id to option.text("label")) }, label = { Text(option.text("label")) }) }
                    OutlinedTextField(answers[id].orEmpty(), { answers = answers + (id to it) }, label = { Text("回答") })
                }
            }
        }, confirmButton = { TextButton(onClick = { vm.answerQuestions(request, answers) }, enabled = questions.all { answers[it.text("id")]?.isNotBlank() == true }) { Text("提交") } }, dismissButton = { TextButton(onClick = later) { Text("稍后处理") } })
    } else {
        AlertDialog(onDismissRequest = later, title = { Text(when { request.kind == RequestKind.COMMAND -> "批准执行命令？"; request.kind == RequestKind.PERMISSIONS -> "批准本轮额外权限？"; else -> "批准修改文件？" }) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("电脑：$profileName", fontSize = 12.sp, color = Muted)
                Text(request.params.text("reason").ifBlank { "Agent 正在等待你的决定。" })
                Text("会话 ${request.params.text("threadId").take(12)}", fontSize = 12.sp, color = Muted)
                SelectionContainer { Text(request.params.text("command").ifBlank { request.params.toString() }.take(12000), fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
            }
        }, confirmButton = { TextButton(onClick = { vm.decide(request, true) }) { Text("仅批准这次") } }, dismissButton = { Row { TextButton(onClick = later) { Text("稍后") }; TextButton(onClick = { vm.decide(request, false) }) { Text("拒绝") } } })
    }
}
