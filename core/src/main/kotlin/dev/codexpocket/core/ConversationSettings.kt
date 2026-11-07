package dev.codexpocket.core

import kotlinx.serialization.json.*

data class EffortOption(val id: String, val description: String)
data class ModelOption(val id: String, val name: String, val description: String, val efforts: List<EffortOption>, val defaultEffort: String, val isDefault: Boolean, val inputModalities: List<String> = listOf("text", "image"))
data class PermissionOption(val id: String, val name: String, val description: String, val allowed: Boolean, val reviewer: String = "user")
data class ConversationSettings(
    val model: String, val effort: String?, val permissionId: String?,
    val sandbox: JsonObject, val approvalPolicy: JsonElement, val reviewer: String,
    val collaboration: JsonObject? = null,
) {
    val permissionLabel: String get() = if ((permissionId == ":workspace" || sandbox.text("type") == "workspaceWrite") && reviewer == "auto_review") "自动审查" else permissionId?.let(::permissionName) ?: when (sandbox.text("type")) {
        "dangerFullAccess" -> "完整访问权限"
        "readOnly" -> "只读"
        "workspaceWrite" -> "默认权限"
        else -> "自定义权限"
    }
}

fun conversationSettings(record: JsonObject): ConversationSettings = ConversationSettings(
    record.text("model"), record.text("effort").ifBlank { record.text("reasoningEffort") }.takeIf(String::isNotBlank),
    record.objectAt("activePermissionProfile").text("id").takeIf(String::isNotBlank),
    (record["sandboxPolicy"] as? JsonObject) ?: record.objectAt("sandbox"),
    record["approvalPolicy"] ?: JsonNull, record.text("approvalsReviewer", "user"),
    record["collaborationMode"] as? JsonObject,
)

fun modelOption(record: JsonObject) = ModelOption(record.text("model"), record.text("displayName").ifBlank { record.text("model") }, record.text("description"),
    record.array("supportedReasoningEfforts").mapNotNull { (it as? JsonObject)?.let { e -> EffortOption(e.text("reasoningEffort"), e.text("description")) } },
    record.text("defaultReasoningEffort"), record["isDefault"]?.jsonPrimitive?.booleanOrNull == true,
    if (record["inputModalities"] is JsonArray) record.array("inputModalities").mapNotNull { (it as? JsonPrimitive)?.contentOrNull } else listOf("text", "image"))

fun permissionName(id: String) = when (id) {
    ":workspace" -> "默认权限"
    ":read-only" -> "只读"
    ":danger-full-access" -> "完整访问权限"
    else -> id.removePrefix(":")
}
fun permissionDescription(id: String) = when (id) {
    ":workspace" -> "在沙盒中运行命令；额外访问需要批准"
    ":read-only" -> "查看文件与分析；修改文件等额外操作需批准"
    ":danger-full-access" -> "电脑完整访问权限（风险较高）"
    else -> "使用电脑上配置的权限方案"
}
fun effortName(id: String?) = when (id) {
    null, "" -> "默认"
    "none" -> "无"
    "minimal" -> "最低"
    "low" -> "低"
    "medium" -> "中"
    "high" -> "高"
    "xhigh" -> "极高"
    "max" -> "Max"
    "ultra" -> "Ultra"
    else -> id
}

/** Keep collaboration mode's model/effort aligned: it otherwise overrides the top-level fields. */
fun modelSettingsPatch(current: ConversationSettings, model: String, effort: String): JsonObject {
    val values = obj("model" to model, "effort" to effort).toMutableMap()
    current.collaboration?.let { mode ->
        values["collaborationMode"] = JsonObject(mode + ("settings" to JsonObject(mode.objectAt("settings") + obj("model" to model, "reasoning_effort" to effort))))
    }
    return JsonObject(values)
}
fun permissionSettingsPatch(option: PermissionOption): JsonObject {
    require(option.allowed) { "电脑的策略不允许选择此权限模式" }
    val values = obj("permissions" to option.id).toMutableMap()
    if (option.id in setOf(":workspace", ":read-only", ":danger-full-access")) {
        values["approvalPolicy"] = JsonPrimitive(if (option.id == ":danger-full-access") "never" else "on-request")
        values["approvalsReviewer"] = JsonPrimitive(option.reviewer)
    }
    return JsonObject(values)
}

fun permissionSelected(current: ConversationSettings?, option: PermissionOption): Boolean {
    if (current == null) return false
    val profile = current.permissionId ?: when (current.sandbox.text("type")) {
        "workspaceWrite" -> ":workspace"
        "readOnly" -> ":read-only"
        "dangerFullAccess" -> ":danger-full-access"
        else -> return false
    }
    return profile == option.id && (profile != ":workspace" || current.reviewer == option.reviewer)
}

fun settingsMatch(current: ConversationSettings, patch: JsonObject): Boolean {
    val values = obj("model" to current.model, "effort" to current.effort, "permissions" to current.permissionId,
        "sandboxPolicy" to current.sandbox, "approvalPolicy" to current.approvalPolicy, "approvalsReviewer" to current.reviewer,
        "collaborationMode" to current.collaboration)
    return patch.all { (key, value) -> values[key] == value }
}

fun collaborationSettingsPatch(current: ConversationSettings, mode: String): JsonObject {
    require(mode in setOf("default", "plan"))
    return obj("collaborationMode" to obj("mode" to mode, "settings" to obj("model" to current.model, "reasoning_effort" to current.effort, "developer_instructions" to null)))
}

fun compactModelName(name: String): String = name.replaceFirst(Regex("^GPT[- ]", RegexOption.IGNORE_CASE), "")
