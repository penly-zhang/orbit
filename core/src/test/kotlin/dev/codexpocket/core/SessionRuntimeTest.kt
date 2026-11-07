package dev.codexpocket.core

import org.junit.Test
import kotlin.test.*

class SessionRuntimeTest {
    @Test fun `interleaved background turns keep separate messages status and timing`() {
        val sessions = mutableMapOf("a" to SessionRuntime(), "b" to SessionRuntime())
        fun event(id: String, method: String, params: kotlinx.serialization.json.JsonObject) { sessions[id] = sessionEvent(sessions.getValue(id), method, params, 5000) }
        event("a", "turn/started", obj("turn" to obj("id" to "ta", "startedAt" to 1)))
        event("b", "turn/started", obj("turn" to obj("id" to "tb", "startedAt" to 2)))
        event("a", "item/agentMessage/delta", obj("itemId" to "ia", "turnId" to "ta", "delta" to "First"))
        event("b", "item/agentMessage/delta", obj("itemId" to "ib", "turnId" to "tb", "delta" to "Other"))
        event("a", "item/agentMessage/delta", obj("itemId" to "ia", "turnId" to "ta", "delta" to " tab"))
        event("a", "turn/completed", obj("turn" to obj("id" to "ta", "completedAt" to 4)))
        assertEquals("First tab", sessions.getValue("a").items.single().text)
        assertEquals(3000L, sessions.getValue("a").items.single().turnDurationMs)
        assertNull(sessions.getValue("a").activeTurn)
        assertEquals("Other", sessions.getValue("b").items.single().text)
        assertEquals("tb", sessions.getValue("b").activeTurn)
    }
    @Test fun `a delayed completion never stops a newer turn`() {
        val state = SessionRuntime(activeTurn = "new", startedAtMs = 9000)
        val result = sessionEvent(state, "turn/completed", obj("turn" to obj("id" to "old")), 10000)
        assertEquals("new", result.activeTurn)
        assertEquals(9000L, result.startedAtMs)
    }
}
