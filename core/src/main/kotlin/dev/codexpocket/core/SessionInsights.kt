package dev.codexpocket.core

import kotlinx.serialization.json.*
import kotlinx.serialization.Serializable

@Serializable data class TokenUsage(val last: Long, val total: Long, val contextWindow: Long?) {
    val contextPercent: Int? get() = contextWindow?.takeIf { it > 0 }?.let { ((last.toDouble() / it) * 100).toInt().coerceIn(0, 100) }
}
fun tokenUsage(record: JsonObject): TokenUsage? {
    val last = record.objectAt("last")["totalTokens"]?.jsonPrimitive?.longOrNull ?: return null
    val total = record.objectAt("total")["totalTokens"]?.jsonPrimitive?.longOrNull ?: return null
    if (last < 0 || total < 0) return null
    return TokenUsage(last, total, record["modelContextWindow"]?.jsonPrimitive?.longOrNull?.takeIf { it > 0 })
}
fun planMessage(params: JsonObject): ChatItem? {
    val turn = params.text("turnId"); if (turn.isBlank()) return null
    val steps = params.array("plan").mapNotNull { it as? JsonObject }
    if (steps.isEmpty()) return null
    return ChatItem("plan:$turn", "plan", buildString {
        params.text("explanation").takeIf { it.isNotBlank() }?.let { appendLine(it + "\n") }
        steps.forEach { step -> appendLine("${when (step.text("status")) { "completed" -> "- [x]"; "inProgress" -> "- [ ] ▶"; else -> "- [ ]" }} ${step.text("step")}") }
    }.trim(), turnId = turn)
}

data class ConnectionAdvice(val title: String, val action: String)
fun connectionAdvice(message: String): ConnectionAdvice {
    val text = message.lowercase()
    return when {
        "auth fail" in text || "authentication" in text || "permission denied" in text -> ConnectionAdvice("SSH 登录未通过", "在设置中核对用户名、密码或私钥，以及跳板的登录信息。")
        "not found" in text && ("codex" in text || "127" in text) -> ConnectionAdvice("电脑未找到 Agent", "填写所选 Agent 可执行文件的完整路径，并确认服务已启动。")
        "refused" in text -> ConnectionAdvice("电脑拒绝连接", "确认电脑已开启 SSH、端口正确且电脑没有休眠。")
        "resolve" in text || "unknownhost" in text -> ConnectionAdvice("无法解析电脑地址", "核对地址，并确认 VPN、DNS 或代理可用。")
        "timeout" in text || "timed out" in text || "network" in text || "socket" in text -> ConnectionAdvice("网络连接中断", "检查手机网络、VPN、代理和电脑是否在线；连接开关开启时会自动重试。")
        else -> ConnectionAdvice("连接暂不可用", "确认电脑在线、SSH 可访问，且所选 Agent 已启动；可在设置中编辑连接后重试。")
    }
}
