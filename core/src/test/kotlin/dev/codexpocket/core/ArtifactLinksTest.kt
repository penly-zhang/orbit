package dev.codexpocket.core

import org.junit.Test
import kotlin.test.*

class ArtifactLinksTest {
    @Test fun `tunnel rewrites only explicit remote loopback URLs preserving paths queries and fragments`() {
        val source = "http://localhost:48761/content/a%20b/index.html?q=中文#air/result"
        assertEquals(48761, loopbackPort(source))
        assertEquals("http://127.0.0.1:1234/content/a%20b/index.html?q=中文#air/result", tunneledUrl(source, 1234))
        assertTrue(isLoopbackLink("http://[::1]:3000/"))
        assertFalse(isLoopbackLink("http://localhost.evil.test/"))
        assertFalse(isWebLink("javascript:alert(1)"))
        assertFails { tunneledUrl("https://example.com/", 1234) }
        assertFails { tunneledUrl("https://localhost:3000/", 1234) }
    }
    @Test fun `HTML resource scope permits web assets and rejects escapes private and unsupported files`() {
        assertEquals("/project/output/assets/a b.png", artifactResourcePath("/project/output", "/assets/a%20b.png"))
        for (path in listOf("/../secret.json", "/%2e%2e/secret.json", "/.env", "/.ssh/key.js", "/dir%5c..%5csecret.json", "/id_rsa", "/secret%00.png")) assertFails(path) { artifactResourcePath("/project/output", path) }
        assertEquals("image/svg+xml", artifactMime("chart.svg"))
    }
    @Test fun `renderable documents preserve source and regular code examples remain code`() {
        val source = "before\n```html\n<button>点击</button>\n```\nafter [报告](./reports/result.md)"
        val parts = messageContent(source)
        assertEquals("<button>点击</button>\n", parts.filterIsInstance<MessageContent.Document>().single().source)
        assertEquals("./reports/result.md", parts.filterIsInstance<MessageContent.Artifact>().single().link)
        assertTrue(messageContent("<html><body>你好</body></html>").single() is MessageContent.Document)
        assertTrue(messageContent("```kotlin\nval html = \"<div>test</div>\"\n```").single() is MessageContent.Text)
    }
}
