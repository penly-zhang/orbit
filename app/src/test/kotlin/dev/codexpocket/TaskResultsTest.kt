package dev.codexpocket

import dev.codexpocket.core.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Test
import kotlin.test.*

class TaskResultsTest {
    @Test fun `links support spaces parentheses references and exclude code and unsafe schemes`() {
        val links = resultLinks("""
            [页面](</project/my page.html>)
            [图片](/project/image(1).png)
            [预览](http://localhost:3000)
            [重复](http://localhost:3000)
            [引用][result]
            [危险](javascript:alert)
            `[示例](/not-a-result)`

            [result]: /project/report.md
        """.trimIndent())
        assertEquals(listOf("/project/my page.html", "/project/image(1).png", "http://localhost:3000", "/project/report.md"), links.map { it.target })
    }
    @Test fun `verification is scoped to exact turn with output and exit code retained`() {
        val item = ChatItem("check", "commandExecution", "1 failed", status = "completed", turnId = "new", command = "python -m pytest", exitCode = 1)
        val items = listOf(item.copy(id = "old", turnId = "old", exitCode = 0), item, item.copy(id = "chat", kind = "agentMessage"))
        assertEquals(listOf(item), verificationEvidence(items, "new"))
        assertTrue(verificationEvidence(items, "").isEmpty())
        assertEquals(1, json.decodeFromString<ChatItem>(json.encodeToString(item)).exitCode)
    }
    @Test fun `legacy recovery keeps open tabs and follows known turns`() {
        val saved = SavedTab(ThreadInfo("t", "Task", "/project"), ProjectInfo("p", "Project", listOf("/project")), 1, progress = TaskProgress("turn", "completed"))
        val restored = json.decodeFromString<SavedTab>(json.encodeToString(saved))
        assertTrue(restored.tabOpen); assertTrue(restored.tracked)
        val closed = restored.copy(tabOpen = false)
        assertFalse(json.decodeFromString<SavedTab>(json.encodeToString(closed)).tabOpen)
    }
}
