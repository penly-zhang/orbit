package dev.codexpocket.core

import kotlin.test.*
import org.junit.Test

class ProductWorkflowTest {
    @Test fun `plan updates replace their prior checklist and stay in their own turn`() {
        val first = obj("turnId" to "t", "plan" to listOf(obj("step" to "Investigate", "status" to "inProgress"), obj("step" to "Verify", "status" to "pending")))
        var state = sessionEvent(SessionRuntime(activeTurn = "t", startedAtMs = 12), "turn/plan/updated", first, 20)
        assertEquals(1, state.items.size)
        assertTrue("▶ Investigate" in state.items.single().text)
        state = sessionEvent(state, "turn/plan/updated", obj("turnId" to "t", "plan" to listOf(obj("step" to "Investigate", "status" to "completed"))), 30)
        assertEquals(1, state.items.size)
        assertEquals("t", state.items.single().turnId)
        assertTrue("[x] Investigate" in state.items.single().text)
        assertEquals(12, state.items.single().turnStartedAtMs)
    }
    @Test fun `usage requires real service counters and handles missing context limits`() {
        assertNull(tokenUsage(obj()))
        assertNull(tokenUsage(obj("last" to obj("totalTokens" to -1), "total" to obj("totalTokens" to 2))))
        assertNull(tokenUsage(obj("last" to obj("totalTokens" to 50), "total" to obj("totalTokens" to 300)))?.contextPercent)
        val usage = tokenUsage(obj("last" to obj("totalTokens" to 50), "total" to obj("totalTokens" to 300), "modelContextWindow" to 200))!!
        assertEquals(25, usage.contextPercent)
        assertEquals(300, usage.total)
    }
    @Test fun `sharing conversations excludes internal commands private attachment paths and payloads`() {
        val output = conversationMarkdown("中文\n会话", listOf(ChatItem("u", "userMessage", "解释这张图", images = listOf(ChatImage("/private/key-folder/image.jpg"))), ChatItem("c", "commandExecution", "secret command"), ChatItem("a", "agentMessage", "答案\n```kt\nval x = 1\n```")))
        assertTrue("解释这张图" in output); assertTrue("```kt" in output)
        assertFalse("secret command" in output); assertFalse("key-folder" in output)
        assertTrue("图片附件" in output)
    }
}
