package dev.codexpocket.core

import org.junit.Test
import kotlin.test.*

class MermaidDocumentTest {
    @Test fun `mermaid fences preserve prose and stream partial input as code`() {
        val parts = messageContent("之前\n```mermaid\nflowchart LR\n A-->B\n```\n之后\n~~~mermaid\nsequenceDiagram\n A->>B: hello\n~~~~\n")
        assertEquals(listOf("mermaid", "mermaid"), parts.filterIsInstance<MessageContent.Document>().map { it.format })
        assertTrue((parts.first() as MessageContent.Text).text.contains("之前"))
        assertTrue(parts.filterIsInstance<MessageContent.Text>().any { "之后" in it.text })
        assertTrue(messageContent("```mermaid\nflowchart LR\nA-->").single() is MessageContent.Text)
        assertTrue(messageContent("````text\n```mermaid\nA-->B\n```\n````").single() is MessageContent.Text)
    }
    @Test fun `diagram source cannot escape its script and renderer is local only`() {
        val attack = "flowchart LR\n A[\"</script><script>alert(1)</script>\"]"
        val html = mermaidDocument(attack)
        assertFalse(html.contains(attack))
        assertTrue(html.contains("\\u003c/script\\u003e"))
        assertTrue(html.contains("securityLevel:'strict'"))
        assertTrue(html.contains("connect-src 'none'"))
        assertTrue(html.contains("/__orbit_mermaid__/mermaid.min.js"))
        assertTrue(mermaidDocument("graph LR\nA-->B", true).contains("theme:'dark'"))
        assertEquals("./diagram.mmd", messageContent("[图](./diagram.mmd)").filterIsInstance<MessageContent.Artifact>().single().link)
    }
}
