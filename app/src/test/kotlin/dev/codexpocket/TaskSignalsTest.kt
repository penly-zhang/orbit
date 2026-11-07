package dev.codexpocket

import dev.codexpocket.core.*
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.*
import org.junit.Test

class TaskSignalsTest {
    private val thread = ThreadInfo("same-thread", "Work", "/project")
    private val project = ProjectInfo("project", "Project", listOf("/project"))
    private val a = SessionTab(thread, project, 1, profileId = "a")
    private val b = SessionTab(thread, project, 2, profileId = "b")
    @Test fun `task notifications follow composite session IDs and never mistake transport loss for completion`() {
        val before = PocketState(sessionTabs = listOf(a.copy(running = true), b.copy(running = true)))
        val after = PocketState(sessionTabs = listOf(a, b.copy(running = true)), connections = mapOf("a" to HostConnectionStatus(connected = true)))
        assertEquals(listOf(a.key), taskSignals(before, after).map { it.tab?.key })
        assertTrue(taskSignals(before, after.copy(connections = emptyMap())).isEmpty())
        assertTrue(taskSignals(after, after).isEmpty())
        assertTrue(taskSignals(before, after.copy(sessionTabs = emptyList())).isEmpty())
    }
    @Test fun `identical approval IDs on two hosts create separately routed reminders`() {
        val ra = PendingRequest(JsonPrimitive("same"), RequestKind.COMMAND, obj("threadId" to thread.id), "a")
        val rb = ra.copy(profileId = "b")
        val before = PocketState(sessionTabs = listOf(a, b), pending = listOf(ra))
        val signals = taskSignals(before, before.copy(pending = listOf(ra, rb)))
        assertEquals(1, signals.size); assertEquals(b.key, signals.single().tab?.key)
    }
}
