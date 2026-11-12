package dev.codexpocket.core

import org.junit.Test
import kotlin.test.*

class TurnSummaryTest {
    @Test fun `history preserves failure even without a final message`() {
        val old = obj("id" to "old", "status" to "completed")
        val failed = obj("id" to "latest", "status" to "failed", "error" to obj("message" to "failed to execute"))
        val turns = historyPage(listOf(old, failed), null)
        val items = itemHistoryPage(emptyList(), listOf(failed, old), null)
        assertEquals(TurnSummary("latest", "failed", "failed to execute"), turns.latestTurn)
        assertEquals(turns.latestTurn, items.latestTurn)
        assertTrue(items.items.isEmpty()); assertNull(items.turnId)
    }
}
