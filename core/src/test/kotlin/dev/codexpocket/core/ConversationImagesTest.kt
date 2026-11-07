package dev.codexpocket.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import org.junit.Test
import java.util.Base64
import kotlin.test.*

class ConversationImagesTest {
    @Test fun `tool images remain accessible inside completed work and linked images remain parsed`() {
        val tool = chatItem(obj("id" to "tool", "type" to "dynamicToolCall", "tool" to "view", "contentItems" to listOf(obj("type" to "inputImage", "imageUrl" to "data:image/png;base64,YWJj"))))!!
        val mcp = chatItem(obj("id" to "mcp", "type" to "mcpToolCall", "result" to obj("content" to listOf(obj("type" to "image", "mimeType" to "image/jpeg", "data" to "YWJj")))))!!
        val output = chatItem(obj("id" to "output", "type" to "functionCallOutput", "output" to listOf(obj("type" to "input_image", "image_url" to "/tmp/output.png"))))!!
        assertEquals("data:image/jpeg;base64,YWJj", mcp.images.single().source)
        assertEquals("/tmp/output.png", output.images.single().source)
        assertEquals(3, conversationTimeline(listOf(tool, mcp, output), null).filterIsInstance<WorkEntry>().single().items.size)
        assertEquals(3, conversationTimeline(listOf(tool, mcp, output), "legacy-start").filterIsInstance<WorkEntry>().single().items.size)
        val parts = messageContent("[图片](/tmp/figure.png) <img src=\"https://example.com/a.jpg?a=1&amp;b=2\" alt=\"预览\"> [源码](/tmp/code.kt)")
        assertEquals(listOf("/tmp/figure.png", "https://example.com/a.jpg?a=1&b=2"), parts.filterIsInstance<MessageContent.Image>().map { it.image.source })
        assertTrue(parts.filterIsInstance<MessageContent.Text>().last().text.contains("[源码]"))
    }
    @Test fun `native attachment metadata becomes thumbnails without hiding other files`() {
        val path = "/tmp/codex-remote-attachments/截图.jpg"
        val raw = "# Files mentioned by the user:\n\n## 截图.jpg: $path\n\n## My request for Codex:\n\n请看截图"
        val item = chatItem(obj("id" to "u", "type" to "userMessage", "content" to listOf(
            obj("type" to "text", "text" to raw), obj("type" to "localImage", "path" to path),
            obj("type" to "localImage", "path" to path), obj("type" to "image", "url" to "https://example.com/image.png"),
        )))!!
        assertEquals("请看截图", item.text)
        assertEquals(listOf(path, "https://example.com/image.png"), item.images.map { it.source })
        val otherFile = raw.replace("## 截图.jpg:", "## notes.txt: /tmp/notes.txt\n\n## 截图.jpg:")
        assertEquals(otherFile, displayUserText(otherFile, item.images))
    }

    @Test fun `image only messages survive timeline and retain their chronological position`() {
        val user = chatItem(obj("id" to "u", "type" to "userMessage", "content" to listOf(obj("type" to "localImage", "path" to "/tmp/image.png"))))!!.copy(turnId = "t")
        val viewed = chatItem(obj("id" to "v", "type" to "imageView", "path" to "/tmp/result.png"))!!.copy(turnId = "t")
        val generated = chatItem(obj("id" to "g", "type" to "imageGeneration", "status" to "completed", "savedPath" to "/tmp/generated.png", "result" to "ignored"))!!.copy(turnId = "t")
        val timeline = conversationTimeline(listOf(user, viewed, generated), null)
        assertEquals(listOf("u", "g"), timeline.filterIsInstance<MessageEntry>().map { it.item.id })
        assertEquals("v", timeline.filterIsInstance<WorkEntry>().single().items.single().id)
        assertEquals("查看图片 · result.png", workItemTitle(viewed))
        assertEquals("/tmp/generated.png", generated.images.single().source)
        assertEquals("data:image/png;base64,YWJj", chatItem(obj("id" to "g2", "type" to "imageGeneration", "result" to "YWJj"))!!.images.single().source)
    }

    @Test fun `old encrypted history remains readable and structured replies show answers`() {
        val old = json.decodeFromString<ChatItem>("""{"id":"u","kind":"userMessage","text":"hello"}""")
        assertTrue(old.images.isEmpty())
        val reply = """<send_user_message_question_reply>
            [{"questionItemId":"internal","question":"ZIP 也会卡住？","answer":"还没试过 ZIP"},{"answer":"第二个回答"}]
            </send_user_message_question_reply>""".trimIndent()
        assertEquals("还没试过 ZIP\n\n第二个回答", displayUserText(reply))
        val broken = "<send_user_message_question_reply>invalid</send_user_message_question_reply>"
        assertEquals(broken, displayUserText(broken))
        assertEquals("示例 $reply", displayUserText("示例 $reply"))
    }

    @Test fun `markdown images preserve text order balanced paths titles and spaces`() {
        val parts = messageContent("之前\n![第一张](/tmp/a(1).png \"标题\")\n中间 ![第二张](<file:///tmp/with%20space.jpg>) 之后")
        assertEquals(5, parts.size)
        assertEquals("之前\n", (parts[0] as MessageContent.Text).text)
        assertEquals("/tmp/a(1).png", (parts[1] as MessageContent.Image).image.source)
        assertEquals("第一张", (parts[1] as MessageContent.Image).image.label)
        assertEquals("file:///tmp/with%20space.jpg", (parts[3] as MessageContent.Image).image.source)
        assertEquals(" 之后", (parts[4] as MessageContent.Text).text)
    }

    @Test fun `markdown examples inside inline or fenced code never fetch images`() {
        val example = "`![示例](/tmp/inline.png)`\n```md\n![示例](/tmp/fenced.png)\n```\n~~~\n![示例](/tmp/tilde.png)\n~~~\n\\![转义](/tmp/escaped.png)\n"
        assertTrue(messageContent(example).filterIsInstance<MessageContent.Document>().single().source.contains("/tmp/fenced.png"))
        assertTrue(messageContent(example).filterIsInstance<MessageContent.Text>().joinToString { it.text }.contains("/tmp/inline.png"))
        assertTrue(messageContent(example).none { it is MessageContent.Image })
        assertEquals("/tmp/real.png", (messageContent(example + "![真实](/tmp/real.png)").last() as MessageContent.Image).image.source)
        assertTrue(messageContent("```md\n![未完成示例](/tmp/no.png)").none { it is MessageContent.Image })
    }

    @Test fun `image sources resolve to remote host files or bounded inline bytes`() = runBlocking<Unit> {
        val paths = mutableListOf<String>()
        val read: suspend (String) -> ByteArray = { paths += it; byteArrayOf(1, 2, 3) }
        readChatImage("images/chart.png", "/project", read)
        readChatImage("file:///tmp/with%20space.png", "/project", read)
        readChatImage("sandbox:/tmp/result.png", "/project", read)
        assertEquals(listOf("/project/images/chart.png", "/tmp/with space.png", "/tmp/result.png"), paths)
        val bytes = byteArrayOf(0, 127, -1, 2)
        assertContentEquals(bytes, readChatImage("data:image/png;base64," + Base64.getEncoder().encodeToString(bytes), "/project", read))
        assertEquals(3, paths.size)
    }

    @Test fun `invalid and oversized image payloads fail without exposing credentials`() = runBlocking<Unit> {
        var reads = 0
        val read: suspend (String) -> ByteArray = { reads++; byteArrayOf(1) }
        for (bad in listOf("javascript:alert(1)", "data:text/html;base64,YWJj", "codex-file:file-id", "http://example.com/image.png", "file://another-host/tmp/img.png")) {
            val failure = runCatching { readChatImage(bad, "/project", read) }.exceptionOrNull()
            assertNotNull(failure, bad)
        }
        assertEquals(0, reads)
        assertFailsWith<IllegalArgumentException> { readChatImage("data:image/png;base64," + "A".repeat(MAX_CHAT_IMAGE_BYTES * 4 / 3 + 8), "/project", read) }
        assertFailsWith<IllegalArgumentException> { readChatImage("/tmp/too-large.png", "/project") { ByteArray(MAX_CHAT_IMAGE_BYTES + 1) } }
    }
}
