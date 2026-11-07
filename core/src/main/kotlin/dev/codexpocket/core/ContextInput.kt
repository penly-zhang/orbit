package dev.codexpocket.core

import kotlinx.serialization.Serializable

const val MAX_CONTEXT_ITEMS = 8
const val MAX_ATTACHMENT_BYTES = 4 * 1024 * 1024
const val MAX_ATTACHMENT_TOTAL_BYTES = 12 * 1024 * 1024

/** A selected reference belongs to a single host and draft; localPath is never sent to an agent. */
@Serializable data class ContextInput(
    val id: String, val name: String, val path: String = "", val kind: String = "file",
    val localPath: String? = null, val bytes: Int = 0,
    val startLine: Int? = null, val endLine: Int? = null,
) {
    val label: String get() = name + (startLine?.let { ":$it${endLine?.takeIf { end -> end != it }?.let { end -> "-$end" }.orEmpty()}" }.orEmpty())
    val reference: String get() = path + (startLine?.let { ":$it${endLine?.takeIf { end -> end != it }?.let { end -> "-$end" }.orEmpty()}" }.orEmpty())
}
fun validateContext(inputs: List<ContextInput>) {
    require(inputs.size <= MAX_CONTEXT_ITEMS) { "每条消息最多引用 8 项上下文" }
    require(inputs.distinctBy { it.id }.size == inputs.size) { "上下文引用重复" }
    require(inputs.sumOf { it.bytes.toLong() } <= MAX_ATTACHMENT_TOTAL_BYTES) { "附件总大小不能超过 12 MB" }
    inputs.forEach {
        require(it.kind in setOf("file", "directory", "skill", "tool")) { "不支持的上下文类型" }
        require(it.name.isNotBlank() && it.name.length <= 240 && it.name.none(Char::isISOControl)) { "附件名称无效" }
        require(it.path.length <= 8192 && it.path.none(Char::isISOControl)) { "引用路径无效" }
        require(it.localPath != null || it.path.isNotBlank()) { "引用路径不能为空" }
        require(it.localPath == null || it.kind == "file" && it.bytes in 1..MAX_ATTACHMENT_BYTES) { "单个附件需在 4 MB 以内" }
        require(it.startLine == null || it.startLine > 0 && (it.endLine ?: it.startLine) >= it.startLine && (it.endLine ?: it.startLine) - it.startLine < 500) { "请选择有效行号，最多 500 行" }
    }
}

data class SkillEntry(val name: String, val path: String, val description: String, val enabled: Boolean, val pluginId: String? = null)
data class McpEntry(val name: String, val status: String, val auth: String, val tools: Map<String, String>, val error: String?, val pluginId: String? = null)
data class PluginEntry(val id: String, val name: String, val description: String, val enabled: Boolean, val installed: Boolean, val reason: String?, val slug: String = name, val marketplace: String = "", val marketplacePath: String? = null)
data class ToolCatalog(val skills: List<SkillEntry> = emptyList(), val servers: List<McpEntry> = emptyList(), val plugins: List<PluginEntry> = emptyList(), val errors: Map<String, String> = emptyMap())

data class PluginTools(val skills: List<SkillEntry>, val servers: List<String>, val description: String)
