package dev.codexpocket.core

import kotlinx.serialization.json.*

/** File references remain ordinary absolute paths; only skills use the native skill input. */
fun contextUserInput(text: String, inputs: List<ContextInput>): List<JsonObject> {
    validateContext(inputs)
    require(inputs.all { it.localPath == null }) { "附件尚未传到电脑" }
    return buildList {
        val refs = inputs.filter { it.kind != "skill" }.joinToString("\n") {
            if (it.kind == "tool") "使用 MCP 工具 ${it.path}" else "@${it.reference}"
        }
        val body = listOf(text.takeIf(String::isNotBlank), refs.takeIf(String::isNotBlank)).filterNotNull().joinToString("\n\n")
        if (body.isNotBlank()) add(obj("type" to "text", "text" to body))
        inputs.filter { it.kind == "skill" }.forEach { add(obj("type" to "skill", "name" to it.name, "path" to it.path)) }
    }
}

