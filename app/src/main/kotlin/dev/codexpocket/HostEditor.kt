package dev.codexpocket

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.codexpocket.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun connectionFormErrors(profile: HostProfile, credentials: Credentials, requireCredentials: Boolean): Map<String, String> = buildMap {
    if (profile.host.isBlank()) put("host", "请填写电脑的 IP 地址或主机名")
    if (profile.port !in 1..65535) put("port", "端口范围为 1–65535")
    if (profile.username.isBlank()) put("username", "请填写电脑的登录用户名")
    if (requireCredentials && (if (profile.authType == "key") credentials.privateKey.isBlank() else credentials.password.isBlank())) put("credentials", if (profile.authType == "key") "请选择私钥文件" else "请填写 SSH 登录密码")
    if (profile.proxyEnabled && (profile.proxyHost.isBlank() || profile.proxyPort !in 1..65535)) put("proxy", "请填写代理地址和有效端口（1–65535）")
    if (profile.routeMode == "remoteSsh" && profile.remoteTarget.isBlank()) put("target", "请填写入口电脑上可使用的 SSH 别名")
    profile.jumpHosts.forEachIndexed { index, hop ->
        if (hop.host.isBlank() || hop.username.isBlank() || hop.port !in 1..65535) put("hop:${hop.id}", "请完善跳板 ${index + 1} 的地址、用户名和有效端口")
        else if (requireCredentials && (if (hop.authType == "key") credentials.jumps[hop.id]?.privateKey.isNullOrBlank() else credentials.jumps[hop.id]?.password.isNullOrBlank())) put("hop:${hop.id}", "请填写跳板 ${index + 1} 的登录信息")
    }
}

@Composable internal fun HostEditor(profile: HostProfile, secret: Credentials, onDismiss: () -> Unit,
    initialRememberSecret: Boolean? = null, saveOnly: ((HostProfile, Credentials, Boolean) -> Unit)? = null, save: (HostProfile, Credentials, Boolean) -> Unit) {
    var route by remember { mutableStateOf(profile) }
    var hopSecrets by remember { mutableStateOf(secret.jumps) }
    var name by remember { mutableStateOf(profile.name) }
    var host by remember { mutableStateOf(profile.host) }
    var port by remember { mutableStateOf(profile.port.toString()) }
    var username by remember { mutableStateOf(profile.username) }
    var proxyEnabled by remember { mutableStateOf(profile.proxyEnabled) }
    var autoConnect by remember { mutableStateOf(profile.autoConnect) }
    var proxyHost by remember { mutableStateOf(profile.proxyHost) }
    var proxyPort by remember { mutableStateOf(profile.proxyPort.toString()) }
    var auth by remember { mutableStateOf(profile.authType) }
    var password by remember { mutableStateOf(secret.password) }
    var key by remember { mutableStateOf(secret.privateKey) }
    var passphrase by remember { mutableStateOf(secret.passphrase) }
    val initiallyRemembered = initialRememberSecret ?: (secret.privateKey.isNotBlank() || secret.password.isNotBlank() || secret.jumps.isNotEmpty())
    var rememberSecret by remember { mutableStateOf(initiallyRemembered) }
    var advanced by remember { mutableStateOf(false) }
    var preparation by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }
    var showPassword by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var attempted by remember { mutableStateOf(false) }
    var requireCredentials by remember { mutableStateOf(true) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    val draft = route.copy(name = name, host = host, port = port.toIntOrNull() ?: 0, username = username, authType = auth,
        proxyEnabled = proxyEnabled, autoConnect = autoConnect, proxyHost = proxyHost, proxyPort = proxyPort.toIntOrNull() ?: 0)
    val credentials = Credentials(password, key, passphrase, hopSecrets.filterKeys { id -> route.jumpHosts.any { it.id == id } })
    val dirty = draft != profile || credentials != secret || rememberSecret != initiallyRemembered
    val errors = if (attempted) connectionFormErrors(draft, credentials, requireCredentials) else emptyMap()
    val close = { if (dirty) confirmLeave = true else onDismiss() }
    fun submit(connect: Boolean) {
        requireCredentials = connect; attempted = true
        val found = connectionFormErrors(draft, credentials, connect)
        if (found.isNotEmpty()) {
            advanced = advanced || found.keys.any { it == "port" || it == "proxy" || it == "target" || it.startsWith("hop:") }
            scope.launch { scroll.animateScrollTo(0) }
        } else {
            val updated = draft.copy(name = name.trim().ifBlank { host })
            if (!connect && saveOnly != null) saveOnly(updated, credentials, rememberSecret) else save(updated, credentials, rememberSecret)
        }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching { withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)!!.use { input ->
                val bytes = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) { val n = input.read(buffer); if (n < 0) break; require(bytes.size() + n <= 1024 * 1024) { "私钥文件过大" }; bytes.write(buffer, 0, n) }
                bytes.toString("UTF-8")
            } } }.onSuccess { key = it; importError = null }.onFailure { importError = it.message }
        }
    }
    FullPage(if (profile.host.isBlank()) "添加电脑" else "编辑连接", close, bottom = {
        Surface(color = Panel) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                if (saveOnly != null && profile.host.isNotBlank()) {
                    Button(onClick = { submit(false) }, modifier = Modifier.fillMaxWidth()) { Text("保存修改") }
                    TextButton(onClick = { submit(true) }, modifier = Modifier.fillMaxWidth()) { Text("保存并连接检查") }
                } else {
                    Button(onClick = { submit(saveOnly != null || autoConnect) }, modifier = Modifier.fillMaxWidth()) { Text(if (saveOnly != null || autoConnect) "连接并检查" else "保存") }
                    if (saveOnly != null) TextButton(onClick = { submit(false) }, modifier = Modifier.fillMaxWidth()) { Text("仅保存，稍后连接") }
                }
            }
        }
    }) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (errors.isNotEmpty()) {
                Text("请检查以下信息", color = MaterialTheme.colorScheme.error)
                errors.values.forEach { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            }
            Text("任务在电脑上执行，手机通过 SSH 连接。", color = Muted, fontSize = 13.sp)
            TextButton(onClick = { preparation = !preparation }) { Text(if (preparation) "收起电脑准备说明" else "电脑需要准备什么？") }
            if (preparation) ConnectionPreparation()
            SshConfigImporter(draft, { updated ->
                route = updated; host = updated.host; port = updated.port.toString(); username = updated.username; auth = updated.authType
                if (updated.jumpHosts.isNotEmpty()) advanced = true
            }, { hopSecrets = it })
            Text(if (route.routeMode == "direct") "目标电脑" else "入口电脑", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(host, { host = it.trim() }, label = { Text(if (route.routeMode == "direct") "电脑地址" else "入口电脑地址") }, placeholder = { Text("IP 地址或主机名") }, isError = "host" in errors, supportingText = errors["host"]?.let { { Text(it) } }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(username, { username = it.trim() }, label = { Text("SSH 用户名") }, isError = "username" in errors, supportingText = { Text(errors["username"] ?: "电脑上的登录账户，可运行 whoami 查看") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(auth == "password", { auth = "password" }, label = { Text("密码登录") })
                FilterChip(auth == "key", { auth = "key" }, label = { Text("SSH 密钥") })
            }
            if (auth == "password") OutlinedTextField(password, { password = it }, label = { Text("SSH 登录密码") }, visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = { TextButton(onClick = { showPassword = !showPassword }) { Text(if (showPassword) "隐藏" else "显示") } },
                isError = "credentials" in errors, supportingText = errors["credentials"]?.let { { Text(it) } }, singleLine = true, modifier = Modifier.fillMaxWidth())
            else {
                OutlinedButton(onClick = { launcher.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) { Text(if (key.isBlank()) "选择私钥文件" else "私钥已导入 · 重新选择") }
                errors["credentials"]?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                OutlinedTextField(passphrase, { passphrase = it }, label = { Text("私钥口令（可选）") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                if (importError != null) Text(importError!!, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(rememberSecret, { rememberSecret = it }); Text("在本机加密保存登录信息", fontSize = 13.sp) }
            if (!rememberSecret) Text("不保存时，应用重新启动后需再次填写登录信息。", fontSize = 12.sp, color = Muted)
            OutlinedTextField(name, { name = it }, label = { Text("电脑名称（可选）") }, placeholder = { Text("不填则使用电脑地址") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("自动连接"); Text("启动应用时连接，意外断线后重试。", fontSize = 12.sp, color = Muted) }
                Switch(autoConnect, { autoConnect = it })
            }
            HorizontalDivider()
            TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "收起高级配置" else "高级配置 · 端口、跳板与代理") }
            Text("${connectionRouteSummary(draft)} · SSH 端口 $port", color = Muted, fontSize = 12.sp)
            if (advanced) {
                OutlinedTextField(port, { port = it.filter(Char::isDigit) }, label = { Text("SSH 端口") }, isError = "port" in errors, supportingText = { Text(errors["port"] ?: "默认 22") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
                SshRouteEditor(draft, hopSecrets, { updated -> route = updated }, { hopSecrets = it })
                errors.filterKeys { it == "target" || it.startsWith("hop:") }.values.forEach { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(proxyEnabled, { proxyEnabled = it }); Text("使用 SOCKS5 代理", fontSize = 13.sp) }
                if (proxyEnabled) {
                    OutlinedTextField(proxyHost, { proxyHost = it.trim() }, label = { Text("代理地址") }, isError = "proxy" in errors, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(proxyPort, { proxyPort = it.filter(Char::isDigit) }, label = { Text("代理端口") }, isError = "proxy" in errors, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
                    errors["proxy"]?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                    Text("填写手机可访问的无认证 SOCKS5 代理地址。代理只改变访问路径，电脑地址仍填写目标或入口电脑。", fontSize = 12.sp, color = Muted)
                }
            }
        }
    }
    if (confirmLeave) AlertDialog(onDismissRequest = { confirmLeave = false }, title = { Text("放弃未保存的修改？") }, text = { Text("返回后，本次填写的连接信息不会保存。") }, confirmButton = { TextButton(onClick = onDismiss) { Text("放弃修改") } }, dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("继续编辑") } })
}
