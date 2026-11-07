package dev.codexpocket

import dev.codexpocket.core.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Test
import kotlin.test.*

class UsageRecoveryTest {
    @Test fun `older workspaces without usage remain readable`() {
        val old = json.decodeFromString<SavedHost>("""{"id":"a","tabs":[],"selectedThread":null}""")
        assertTrue(old.usage.isEmpty())
        val updated = old.copy(usage = mapOf("t" to TokenUsage(10, 500, null)))
        assertEquals(updated, json.decodeFromString<SavedHost>(json.encodeToString(updated)))
        assertNull(updated.usage.getValue("t").contextPercent)
    }
    @Test fun `maintenance explains running tasks and becomes available after completion`() {
        val ready = PocketState(connected = true, threadReady = true, items = listOf(ChatItem("u", "userMessage", "task")))
        assertEquals("任务进行中，结束后可用", conversationMaintenanceReason(ready.copy(activeTurn = "running")))
        assertNull(conversationMaintenanceReason(ready))
        assertEquals("连接电脑后可用", conversationMaintenanceReason(ready.copy(connected = false)))
        assertEquals("发送首条消息后可用", conversationMaintenanceReason(ready.copy(items = emptyList())))
    }
}
