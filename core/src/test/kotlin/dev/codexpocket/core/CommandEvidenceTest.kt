package dev.codexpocket.core

import org.junit.Test
import kotlin.test.*

class CommandEvidenceTest {
    @Test fun `command exit code survives wire normalization and history`() {
        val command = obj("id" to "cmd", "type" to "commandExecution", "command" to "pytest", "status" to "completed", "aggregatedOutput" to "1 failed", "exitCode" to 1)
        assertEquals(1, chatItem(command)?.exitCode)
        assertNull(chatItem(obj("id" to "cmd", "type" to "commandExecution"))?.exitCode)
        val history = historyPage(listOf(obj("id" to "turn", "status" to "completed", "items" to kotlinx.serialization.json.JsonArray(listOf(command)))), null)
        assertEquals(1, history.items.single().exitCode)
        assertEquals("turn", history.items.single().turnId)
    }
}
