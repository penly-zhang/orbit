package dev.codexpocket.core

/** Export only visible messages; credentials, internal requests and tool output are excluded. */
fun conversationMarkdown(title: String, items: List<ChatItem>): String = buildString {
    appendLine("# ${title.replace('\n', ' ').replace('\r', ' ')}\n")
    items.filter { it.kind in setOf("userMessage", "agentMessage") }.forEach { item ->
        appendLine("## ${if (item.kind == "userMessage") "You" else "Agent"}\n")
        if (item.text.isNotBlank()) appendLine(item.text.trim() + "\n")
        item.images.forEach { image ->
            // Do not embed large payloads, phone paths, or signed download URLs in shared text.
            appendLine("[图片附件${image.label.takeIf { it.isNotBlank() }?.replace('\n', ' ')?.let { "：$it" }.orEmpty()}]\n")
        }
    }
}
