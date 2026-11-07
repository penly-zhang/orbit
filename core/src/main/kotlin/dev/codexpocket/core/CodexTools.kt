package dev.codexpocket.core

import kotlinx.serialization.json.*
import kotlinx.coroutines.*
import java.nio.file.Paths

internal fun parseSkills(result: JsonObject): Pair<List<SkillEntry>, String?> {
    val entries = result.array("data").mapNotNull { it as? JsonObject }
    return entries.flatMap { it.array("skills").mapNotNull { v -> (v as? JsonObject)?.let { s ->
        SkillEntry(s.text("name"), s.text("path"), s.objectAt("interface").text("shortDescription").ifBlank { s.text("description") }, s["enabled"]?.jsonPrimitive?.booleanOrNull == true, s.text("pluginId").takeIf(String::isNotBlank))
    } } }.distinctBy { it.path } to entries.flatMap { it.array("errors") }.mapNotNull { (it as? JsonObject)?.text("message")?.takeIf(String::isNotBlank) }.joinToString("\n").takeIf(String::isNotBlank)
}
internal fun parseServers(result: JsonObject): List<McpEntry> = result.array("data").mapNotNull { (it as? JsonObject)?.let { s ->
    McpEntry(s.text("name"), s.text("runtimeStatus").ifBlank { "unknown" }, s.text("authStatus"), s.objectAt("tools").mapValues { (_, tool) -> (tool as? JsonObject)?.text("description").orEmpty() }, s.text("toolsError").takeIf(String::isNotBlank), s.text("pluginId").takeIf(String::isNotBlank))
} }
internal fun parsePlugins(result: JsonObject): List<PluginEntry> = result.array("marketplaces").flatMap { entry ->
    val marketplace = entry as? JsonObject ?: return@flatMap emptyList()
    marketplace.array("plugins").mapNotNull { (it as? JsonObject)?.let { p ->
        val ui = p.objectAt("interface")
        PluginEntry(p.text("id"), ui.text("displayName").ifBlank { p.text("name") }, ui.text("shortDescription"), p["enabled"]?.jsonPrimitive?.booleanOrNull == true, p["installed"]?.jsonPrimitive?.booleanOrNull == true, p["disabledReason"]?.takeUnless { it is JsonNull }?.toString(), p.text("name"), marketplace.text("name"), marketplace.text("path").takeIf(String::isNotBlank))
    } }
}.filter { it.installed }.distinctBy { it.id }

internal class CodexTools(private val rpc: RpcClient) : AgentTools {
    override suspend fun searchFiles(root: String, query: String): List<FileSearchResult> =
        rpc.request("fuzzyFileSearch", obj("query" to query, "roots" to listOf(root))).array("files").mapNotNull { item ->
            (item as? JsonObject)?.let { file ->
                val relative = file.text("path")
                val base = file.text("root").ifBlank { root }
                val full = Paths.get(if (relative.startsWith('/')) relative else base.trimEnd('/') + "/" + relative).normalize()
                if (!full.startsWith(Paths.get(root).normalize())) null
                else FileSearchResult(file.text("file_name").ifBlank { relative.substringAfterLast('/') }, full.toString(), file.text("match_type") == "directory")
            }
        }.distinctBy { it.path }.take(100)
    override suspend fun enableSkill(path: String) { rpc.request("skills/config/write", obj("path" to path, "enabled" to true)) }
    override suspend fun reloadMcp() { rpc.request("config/mcpServer/reload") }
    override suspend fun authorizeMcp(name: String, thread: String): JsonObject =
        rpc.request("mcpServer/oauth/login", obj("name" to name, "threadId" to thread, "timeoutSecs" to 300))

    override suspend fun pluginTools(plugin: PluginEntry): PluginTools {
        val value = rpc.request("plugin/read", obj("pluginName" to plugin.slug, "marketplacePath" to plugin.marketplacePath, "remoteMarketplaceName" to plugin.marketplace.takeIf { plugin.marketplacePath == null })).objectAt("plugin")
        return PluginTools(value.array("skills").mapNotNull { (it as? JsonObject)?.let { s -> SkillEntry(s.text("name"), s.text("path"), s.text("description"), s["enabled"]?.jsonPrimitive?.booleanOrNull == true, plugin.id) } }, value.array("mcpServers").mapNotNull { it.jsonPrimitive.contentOrNull }, value.text("description"))
    }

    override suspend fun toolCatalog(cwd: String, thread: String): ToolCatalog = supervisorScope {
        suspend fun read(method: String, params: JsonObject): Result<JsonObject> = try { Result.success(withTimeout(20000) { rpc.request(method, params) }) } catch (e: CancellationException) { if (e !is TimeoutCancellationException) throw e; Result.failure(e) } catch (e: Exception) { Result.failure(e) }
        val skills = async { read("skills/list", obj("cwds" to listOf(cwd), "forceReload" to true)) }
        val servers = async {
            val all = mutableListOf<JsonElement>(); var cursor: String? = null; val seen = mutableSetOf<String>()
            do {
                val page = read("mcpServerStatus/list", obj("threadId" to thread, "cursor" to cursor, "limit" to 100))
                if (page.isFailure) return@async page
                val value = page.getOrThrow(); all.addAll(value.array("data")); cursor = value.text("nextCursor").takeIf(String::isNotBlank)
                if (cursor != null && !seen.add(cursor)) return@async Result.failure(IllegalStateException("MCP 分页返回重复游标"))
            } while (cursor != null && seen.size < 20)
            if (cursor != null) Result.failure(IllegalStateException("MCP 列表过大，请在电脑端检查配置")) else Result.success(obj("data" to all))
        }
        val plugins = async { read("plugin/list", obj("cwds" to listOf(cwd), "forceRefetch" to false)) }
        val s = skills.await(); val m = servers.await(); val p = plugins.await()
        val errors = mutableMapOf<String, String>()
        listOf("Skills" to s, "MCP" to m, "插件" to p).forEach { (name, result) -> result.exceptionOrNull()?.let { errors[name] = it.message ?: "读取失败" } }
        val parsed = s.getOrNull()?.let(::parseSkills)
        parsed?.second?.let { errors["Skills"] = it }
        p.getOrNull()?.array("marketplaceLoadErrors")?.takeIf { it.isNotEmpty() }?.let { errors["插件"] = it.joinToString("\n") { v -> (v as? JsonObject)?.text("message").orEmpty().ifBlank { v.toString() } } }
        ToolCatalog(parsed?.first.orEmpty(), m.getOrNull()?.let(::parseServers).orEmpty(), p.getOrNull()?.let(::parsePlugins).orEmpty(), errors)
    }
}
