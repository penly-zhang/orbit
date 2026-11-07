package dev.codexpocket

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.codexpocket.core.*
import kotlinx.serialization.json.*

/** Native MCP requests remain pending until the user explicitly accepts or declines. */
@Composable internal fun McpRequestDialog(request: PendingRequest, host: String, answer: (PendingRequest, String, JsonObject?) -> Unit, later: () -> Unit = {}) {
    val p = request.params; val mode = p.text("mode"); val schema = p.objectAt("requestedSchema")
    val fields = schema.objectAt("properties"); val required = schema.array("required").map { it.jsonPrimitive.content }.toSet()
    val supported = mode == "url" || mode == "form" && fields.size <= 30 && fields.values.all { (it as? JsonObject)?.text("type") in setOf("string", "boolean", "integer", "number") }
    var values by remember(request.profileId, request.id) { mutableStateOf(fields.mapValues { (_, v) -> (v as? JsonObject)?.get("default")?.jsonPrimitive?.contentOrNull ?: if ((v as? JsonObject)?.text("type") == "boolean") "false" else "" }) }
    val uri = LocalUriHandler.current
    var error by remember(request.id) { mutableStateOf<String?>(null) }
    fun content(): JsonObject = JsonObject(fields.mapNotNull { (key, raw) ->
        val f = raw.jsonObject; val value = values[key].orEmpty()
        if (key !in required && value.isEmpty()) return@mapNotNull null
        require(value.isNotBlank()) { "请填写 ${f.text("title").ifBlank { key }}" }
        val parsed = when (f.text("type")) {
            "boolean" -> JsonPrimitive(value.toBooleanStrict())
            "integer" -> JsonPrimitive(value.toLongOrNull() ?: error("请输入整数"))
            "number" -> JsonPrimitive(value.toDoubleOrNull()?.takeIf { it.isFinite() } ?: error("请输入有效数字"))
            else -> JsonPrimitive(value)
        }
        val options = f.array("enum").map { it.jsonPrimitive.content }
        require(options.isEmpty() || value in options) { "请选择有效选项" }
        key to parsed
    }.toMap())
    AlertDialog(onDismissRequest = later, title = { Text("${p.text("serverName")} 需要确认") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(host, color = Muted, fontSize = 12.sp)
            Text(p.text("message").ifBlank { p.text("description") })
            if (!supported) Text("此确认方式需要在电脑端完成。你可以拒绝本次请求后在电脑端重试。", color = Muted)
            else if (mode == "url") TextButton(onClick = { runCatching {
                val url = p.text("url"); require(java.net.URI(url).scheme == "https") { "授权页面必须使用 HTTPS" }; uri.openUri(url)
            }.onFailure { error = it.message } }) { Text("打开授权页面") }
            else fields.forEach { (key, raw) ->
                val f = raw.jsonObject; val title = f.text("title").ifBlank { key } + if (key in required) " *" else ""
                val options = f.array("enum").map { it.jsonPrimitive.content }
                when {
                    f.text("type") == "boolean" -> Row { Checkbox(values[key] == "true", { values = values + (key to it.toString()) }); Text(title, modifier = Modifier.padding(top = 12.dp)) }
                    options.isNotEmpty() -> { Text(title); options.forEach { option -> FilterChip(values[key] == option, { values = values + (key to option) }, label = { Text(option) }) } }
                    else -> OutlinedTextField(values[key].orEmpty(), { values = values + (key to it) }, label = { Text(title) }, supportingText = { Text(f.text("description")) }, modifier = Modifier.fillMaxWidth())
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { if (supported) TextButton(onClick = {
        runCatching { if (mode == "url") null else content() }.onSuccess { answer(request, "accept", it) }.onFailure { error = it.message }
    }) { Text(if (mode == "url") "已完成授权" else "提交") } }, dismissButton = { Row { TextButton(onClick = later) { Text("稍后") }; TextButton(onClick = { answer(request, "decline", null) }) { Text("拒绝") } } })
}
