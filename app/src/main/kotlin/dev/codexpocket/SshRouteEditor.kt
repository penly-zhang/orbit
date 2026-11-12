package dev.codexpocket

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.codexpocket.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

private suspend fun readSshText(context: Context, uri: Uri, max: Int): String = withContext(Dispatchers.IO) {
    context.contentResolver.openInputStream(uri)!!.use { input ->
        val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while (true) { val n = input.read(buffer); if (n < 0) break; require(out.size() + n <= max) { "文件过大" }; out.write(buffer, 0, n) }
        out.toString("UTF-8")
    }
}

@Composable internal fun SshConfigImporter(profile: HostProfile, change: (HostProfile) -> Unit, changeSecrets: (Map<String, JumpCredentials>) -> Unit) {
    val context = LocalContext.current; val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var config by remember { mutableStateOf<SshConfigImport?>(null) }
    var configName by remember { mutableStateOf("") }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) scope.launch {
        runCatching { SshConfigImport(readSshText(context, uri, 256 * 1024)) }.onSuccess { parsed ->
            if (parsed.aliases.isEmpty()) error = "配置中没有可选择的 Host 别名" else { config = parsed; configName = ""; error = null }
        }.onFailure { error = it.message }
    } }
        OutlinedButton(onClick = { import.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.FileOpen, null, Modifier.size(17.dp)); Spacer(Modifier.width(6.dp)); Text("导入 SSH 配置", fontSize = 12.sp) }
    if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
    config?.let { parsed -> AlertDialog(onDismissRequest = { config = null }, title = { Text("选择 SSH Host 别名") }, text = {
        Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("导入 HostName、User、Port 和 ProxyJump。密钥请在手机上另行选择。", fontSize = 12.sp)
            parsed.aliases.forEach { alias -> TextButton(onClick = { configName = alias }, modifier = Modifier.fillMaxWidth()) { Text(if (configName == alias) "✓ $alias" else alias) } }
        }
    }, confirmButton = { TextButton(enabled = configName.isNotBlank(), onClick = {
        runCatching { parsed.resolve(configName, profile.username) }.onSuccess { route ->
            change(profile.copy(host = route.host, port = route.port, username = route.username, authType = if (route.keyFile != null) "key" else profile.authType, jumpHosts = route.jumps))
            changeSecrets(emptyMap()); config = null; error = if (route.keyFile != null || route.jumps.any { it.authType == "key" }) "配置已导入；请分别选择目标与跳板使用的私钥文件。" else null
        }.onFailure { error = it.message; config = null }
    }) { Text("导入") } }, dismissButton = { TextButton(onClick = { config = null }) { Text("取消") } }) }
}

@Composable internal fun SshRouteEditor(profile: HostProfile, secrets: Map<String, JumpCredentials>, change: (HostProfile) -> Unit, changeSecrets: (Map<String, JumpCredentials>) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("连接方式", style = MaterialTheme.typography.titleSmall)
        listOf("direct" to "直接连接或通过跳板", "remoteSsh" to "通过入口电脑上的 SSH 别名", "loginShell" to "登录后自动跳转").forEach { (mode, label) ->
            Row(Modifier.fillMaxWidth().clickable { change(profile.copy(routeMode = mode)) }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(profile.routeMode == mode, { change(profile.copy(routeMode = mode)) }); Text(label, fontSize = 14.sp)
            }
        }
        when (profile.routeMode) {
            "loginShell" -> Text("上方填写入口电脑的地址和登录信息。登录后沿用电脑已配置的自动跳转，在最终工作电脑上运行任务。", fontSize = 12.sp, color = Muted)
            "remoteSsh" -> {
                OutlinedTextField(profile.remoteTarget, { change(profile.copy(remoteTarget = it.trim())) }, label = { Text("工作电脑的 SSH 别名或 user@host") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("上方填写入口电脑。入口电脑需已能免密 SSH 到工作电脑，并信任其指纹。", fontSize = 12.sp, color = Muted)
            }
            else -> Text("上方填写目标电脑。需要跳板时，按经过的顺序添加；代理只用于第一个入口。", fontSize = 12.sp, color = Muted)
        }
        Text(if (profile.routeMode == "direct") "手机 → ${if (profile.jumpHosts.isEmpty()) "" else "跳板 → "}目标电脑" else "手机 → ${if (profile.jumpHosts.isEmpty()) "" else "跳板 → "}入口电脑 → 工作电脑", fontSize = 13.sp, color = Muted)
        profile.jumpHosts.forEachIndexed { index, hop -> key(hop.id) {
            JumpEditor(hop, secrets[hop.id] ?: JumpCredentials(), index + 1,
                { updated -> change(profile.copy(jumpHosts = profile.jumpHosts.map { if (it.id == hop.id) updated else it })) },
                { secret -> changeSecrets(secrets + (hop.id to secret)) },
                { change(profile.copy(jumpHosts = profile.jumpHosts.filterNot { it.id == hop.id })); changeSecrets(secrets - hop.id) })
        } }
        if (profile.jumpHosts.size < 8) TextButton(onClick = { change(profile.copy(jumpHosts = profile.jumpHosts + JumpHost(UUID.randomUUID().toString(), "", username = profile.username))) }) { Icon(Icons.Outlined.Add, null, Modifier.size(16.dp)); Text("添加跳板", fontSize = 12.sp) }
    }
}

@Composable private fun JumpEditor(hop: JumpHost, secret: JumpCredentials, number: Int, change: (JumpHost) -> Unit, changeSecret: (JumpCredentials) -> Unit, remove: () -> Unit) {
    val context = LocalContext.current; val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    val keyPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) scope.launch {
        runCatching { readSshText(context, uri, 1024 * 1024) }.onSuccess { changeSecret(secret.copy(privateKey = it)); error = null }.onFailure { error = it.message }
    } }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Text("跳板 $number", modifier = Modifier.weight(1f)); IconButton(onClick = remove, modifier = Modifier.size(32.dp)) { Icon(Icons.Outlined.Close, "移除跳板 $number", Modifier.size(17.dp)) } }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(hop.host, { change(hop.copy(host = it.trim())) }, label = { Text("地址") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(hop.port.toString(), { change(hop.copy(port = it.toIntOrNull() ?: 0)) }, label = { Text("端口") }, singleLine = true, modifier = Modifier.width(85.dp))
            }
            OutlinedTextField(hop.username, { change(hop.copy(username = it.trim())) }, label = { Text("用户名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { FilterChip(hop.authType == "password", { change(hop.copy(authType = "password")) }, label = { Text("密码") }); FilterChip(hop.authType == "key", { change(hop.copy(authType = "key")) }, label = { Text("密钥") }) }
            if (hop.authType == "password") OutlinedTextField(secret.password, { changeSecret(secret.copy(password = it)) }, label = { Text("跳板登录密码") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            else {
                OutlinedButton(onClick = { keyPicker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) { Text(if (secret.privateKey.isBlank()) "选择跳板私钥" else "跳板私钥已导入", fontSize = 12.sp) }
                OutlinedTextField(secret.passphrase, { changeSecret(secret.copy(passphrase = it)) }, label = { Text("密钥口令（可选）") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            }
            if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        }
    }
}
