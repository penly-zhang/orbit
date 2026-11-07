package dev.codexpocket

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.codexpocket.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.net.URI
import java.net.URLDecoder
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun ToolPicker(host: String, actions: ContextActions, dismiss: () -> Unit, selected: List<ContextInput> = emptyList()) {
    var tab by remember { mutableStateOf("Skills") }; var query by remember { mutableStateOf("") }
    var catalog by remember { mutableStateOf<ToolCatalog?>(null) }; var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }; var revision by remember { mutableIntStateOf(0) }
    var server by remember { mutableStateOf<String?>(null) }; var plugin by remember { mutableStateOf<String?>(null) }
    var pluginLoading by remember { mutableStateOf<String?>(null) }
    var pluginIssue by remember { mutableStateOf<String?>(null) }
    var toolLimit by remember(server, query) { mutableIntStateOf(30) }
    var pluginTools by remember { mutableStateOf<Map<String, PluginTools>>(emptyMap()) }
    LaunchedEffect(plugin, revision) {
        val selected = catalog?.plugins?.find { it.id == plugin } ?: return@LaunchedEffect
        pluginLoading = selected.id; pluginIssue = null
        try { pluginTools = pluginTools + (selected.id to actions.plugin(selected)) } catch (c: CancellationException) { throw c } catch (e: Exception) { pluginIssue = e.message ?: "插件详情读取失败" } finally { pluginLoading = null }
    }
    var authorization by remember { mutableStateOf<JsonObject?>(null) }; var callback by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope(); val uri = LocalUriHandler.current
    fun add(ref: ContextInput) { if (actions.add(ref)) dismiss() else error = "无法添加，请检查当前草稿的上下文数量" }
    fun task(block: suspend () -> Unit) { scope.launch { loading = true; error = null; try { block() } catch (c: CancellationException) { throw c } catch (e: Exception) { error = e.message ?: "操作失败" } finally { loading = false } } }
    LaunchedEffect(revision) {
        loading = true; error = null
        try { catalog = actions.catalog() } catch (c: CancellationException) { throw c } catch (e: Exception) { error = e.message ?: "工具读取失败" } finally { loading = false }
    }
    FullPage("工具与能力", dismiss) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { Text("${catalog?.skills?.size ?: 0} 个 Skill · ${catalog?.servers?.size ?: 0} 个 MCP 服务", color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f)); IconButton(onClick = { revision++ }, enabled = !loading) { Icon(Icons.Outlined.Refresh, "刷新工具列表") } }
            Text("$host · 选择后随消息交给 Agent 使用", color = Muted, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("Skills", "MCP", "插件").forEach { name -> FilterChip(tab == name, { tab = name; server = null; plugin = null }, label = { Text(name) }) } }
            SearchBox(query, { query = it }, "搜索名称或说明")
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
            error?.let { StateNotice("操作未完成", it, dismiss = { error = null }, failure = true) }
            catalog?.errors?.get(tab)?.let { StateNotice("无法读取 $tab 目录", it, "重试", { revision++ }, failure = true, actionEnabled = !loading) }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 32.dp)) {
                if (tab == "Skills") {
                    val skills = catalog?.skills.orEmpty().filter { query.isBlank() || "${it.name} ${it.description}".contains(query, true) }
                    if (skills.isEmpty() && !loading && catalog?.errors?.get(tab) == null) item { EmptyTools("这台电脑没有匹配的 Skill") }
                    items(skills, key = { it.path }) { skill ->
                        ListItem(headlineContent = { Text(skill.name) }, supportingContent = { Text(skill.description, maxLines = 3, overflow = TextOverflow.Ellipsis) },
                            trailingContent = { if (skill.enabled) Icon(if (selected.any { it.kind == "skill" && it.path == skill.path }) Icons.Outlined.Check else Icons.Outlined.Add, if (selected.any { it.path == skill.path }) "已加入当前消息" else "使用 ${skill.name}") else TextButton(onClick = { task { actions.enableSkill(skill); revision++ } }, enabled = !loading) { Text("启用") } },
                            modifier = Modifier.clickable(enabled = skill.enabled && !loading) { add(ContextInput(UUID.randomUUID().toString(), skill.name, skill.path, "skill")) })
                        HorizontalDivider()
                    }
                } else if (tab == "MCP") {
                    item { TextButton(onClick = { task { actions.reloadMcp(); revision++ } }, enabled = !loading) { Text("重新加载 MCP 配置") } }
                    val servers = catalog?.servers.orEmpty().filter { query.isBlank() || it.name.contains(query, true) || it.tools.keys.any { tool -> tool.contains(query, true) } }
                    if (servers.isEmpty() && !loading && catalog?.errors?.get(tab) == null) item { EmptyTools("没有匹配的 MCP 服务") }
                    items(servers, key = { it.name }) { mcp ->
                        ListItem(headlineContent = { Text(mcp.name) }, supportingContent = { Column {
                            Text("${mcpStatus(mcp.status)} · ${mcp.tools.size} 个工具 · ${mcpAuth(mcp.auth)}")
                            mcp.error?.let { Text(it, color = MaterialTheme.colorScheme.error, maxLines = 4) }
                        } }, trailingContent = { Icon(if (server == mcp.name) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null) }, modifier = Modifier.clickable { server = if (server == mcp.name) null else mcp.name })
                        if (server == mcp.name) {
                            if (mcp.auth == "notLoggedIn" || mcp.status == "authenticationRequired") TextButton(onClick = { task {
                                authorization = actions.authorize(mcp.name); callback = ""
                            } }, enabled = !loading) { Text("登录授权") }
                            val visibleTools = mcp.tools.filter { (name, description) -> query.isBlank() || name.contains(query, true) || description.contains(query, true) || mcp.name.contains(query, true) }
                            visibleTools.entries.take(toolLimit).forEach { (name, description) ->
                                ListItem(headlineContent = { Text(name, fontSize = 14.sp) }, supportingContent = { Text(description, maxLines = 3, overflow = TextOverflow.Ellipsis) },
                                    trailingContent = { Icon(if (selected.any { it.kind == "tool" && it.path == "${mcp.name}/$name" }) Icons.Outlined.Check else Icons.Outlined.Add, if (selected.any { it.path == "${mcp.name}/$name" }) "已加入当前消息" else "使用工具") }, modifier = Modifier.padding(start = 12.dp).clickable(enabled = mcp.status !in setOf("failed", "disabled", "cancelled", "authenticationRequired", "starting", "notStarted") && mcp.auth != "notLoggedIn") {
                                        add(ContextInput(UUID.randomUUID().toString(), name, "${mcp.name}/$name", "tool"))
                                    })
                            }
                            if (visibleTools.size > toolLimit) TextButton(onClick = { toolLimit += 30 }) { Text("显示更多工具（${visibleTools.size - toolLimit}）") }
                            if (mcp.tools.isEmpty()) Text("尚无可用工具，授权或刷新后重试。", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(12.dp))
                        }
                        HorizontalDivider()
                    }
                } else {
                    val plugins = catalog?.plugins.orEmpty().filter { query.isBlank() || "${it.name} ${it.description}".contains(query, true) }
                    if (plugins.isEmpty() && !loading && catalog?.errors?.get(tab) == null) item { EmptyTools("这台电脑没有匹配的已安装插件") }
                    items(plugins, key = { it.id }) { p ->
                        ListItem(headlineContent = { Text(p.name) }, supportingContent = { Text(listOf(if (p.enabled) "已启用" else "已停用", p.description, p.reason.orEmpty()).filter { it.isNotBlank() }.joinToString(" · "), maxLines = 3, overflow = TextOverflow.Ellipsis) }, trailingContent = { Icon(Icons.Outlined.ExpandMore, null) }, modifier = Modifier.clickable { plugin = if (plugin == p.id) null else p.id })
                        if (plugin == p.id) {
                            val detail = pluginTools[p.id]
                            val skills = (catalog?.skills.orEmpty().filter { it.pluginId == p.id } + detail?.skills.orEmpty()).distinctBy { it.name }
                            skills.forEach { skill -> ListItem(headlineContent = { Text(skill.name, fontSize = 14.sp) }, trailingContent = { Icon(Icons.Outlined.Add, null) }, modifier = Modifier.padding(start = 12.dp).clickable(enabled = p.enabled && skill.enabled && skill.path.isNotBlank()) { add(ContextInput(UUID.randomUUID().toString(), skill.name, skill.path, "skill")) }) }
                            val servers = catalog?.servers.orEmpty().filter { it.pluginId == p.id || it.name in detail?.servers.orEmpty() }
                            servers.forEach { mcp -> TextButton(onClick = { tab = "MCP"; server = mcp.name; query = mcp.name }) { Text("${mcp.name} · ${mcpStatus(mcp.status)}") } }
                            if (skills.any { it.path.isBlank() }) Text("部分 Skill 尚未在电脑端准备好，暂时无法引用。", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(12.dp))
                            if (pluginLoading == p.id) Text("正在读取插件能力…", color = Muted, fontSize = 12.sp)
                            else if (pluginIssue != null) Text("插件详情读取失败：$pluginIssue", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                            if (detail != null && skills.isEmpty() && servers.isEmpty()) Text("插件尚未向当前会话提供 Skill 或 MCP 工具；可在电脑端检查配置后刷新。", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(12.dp))
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
    authorization?.let { auth ->
        val url = auth.text("authorizationUrl")
        val redirect = remember(url) { runCatching { URI(url).rawQuery.orEmpty().split('&').firstOrNull { it.substringBefore('=') == "redirect_uri" }?.substringAfter('=')?.let { URLDecoder.decode(it, "UTF-8") } }.getOrNull() }
        AlertDialog(onDismissRequest = { authorization = null; revision++ }, title = { Text("MCP 登录授权") }, text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("在手机浏览器中完成授权，返回后刷新工具状态。", fontSize = 14.sp)
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            TextButton(onClick = { runCatching { require(URI(url).scheme == "https" || isLoopbackLink(url)); uri.openUri(url) }.onFailure { error = it.message } }) { Text("打开授权页面") }
            if (redirect != null && isLoopbackLink(redirect)) {
                Text("若完成后停在 localhost 页面，请复制浏览器地址到下方，由手机转发到电脑完成登录。", fontSize = 12.sp, color = Muted)
                OutlinedTextField(callback, { callback = it }, label = { Text("授权后的回调地址") }, maxLines = 3)
                TextButton(onClick = { task { actions.finishAuthorization(redirect, callback); authorization = null; revision++ } }, enabled = !loading && callback.isNotBlank()) { Text("完成登录") }
            }
        } }, confirmButton = { TextButton(onClick = { authorization = null; revision++ }) { Text("刷新状态") } })
    }
}
@Composable private fun EmptyTools(text: String) { Text(text, color = Muted, modifier = Modifier.padding(vertical = 24.dp)) }
private fun mcpStatus(value: String): String = when (value) { "connected" -> "已连接"; "failed" -> "连接失败"; "authenticationRequired" -> "需要授权"; "disabled" -> "已停用"; "starting" -> "连接中"; "notStarted" -> "尚未启动"; "cancelled" -> "已取消"; else -> "状态未提供" }
private fun mcpAuth(value: String): String = when (value) { "notLoggedIn" -> "未登录"; "oAuth" -> "OAuth 已授权"; "bearerToken" -> "令牌认证"; "unsupported" -> "不支持 OAuth"; else -> "认证状态未提供" }
