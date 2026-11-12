package dev.codexpocket

import dev.codexpocket.core.*
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.*
import org.junit.Test

class TaskSignalsTest {
    @Test fun `questions notify with answer wording and keep session routing`() {
        val tab = a
        val before = PocketState(sessionTabs = listOf(tab), connections = mapOf("a" to HostConnectionStatus(connected = true)))
        val request = PendingRequest(JsonPrimitive("question"), RequestKind.QUESTIONS, obj("threadId" to thread.id), "a")
        val after = before.copy(pending = listOf(request))
        val signal = taskSignals(before, after).single()
        assertEquals("Agent 需要你的回答", signal.title)
        assertEquals(tab.key, signal.tab?.key)
        assertTrue(taskSignals(after, after).isEmpty())
    }
    private val thread = ThreadInfo("same-thread", "Work", "/project")
    private val project = ProjectInfo("project", "Project", listOf("/project"))
    private val a = SessionTab(thread, project, 1, profileId = "a", tracked = true, taskReady = true)
    private val b = SessionTab(thread, project, 2, profileId = "b", tracked = true, taskReady = true)
    @Test fun `task notifications follow composite session IDs and never mistake transport loss for completion`() {
        val before = PocketState(sessionTabs = listOf(a.copy(running = true), b.copy(running = true)))
        val after = PocketState(sessionTabs = listOf(a.copy(progress = TaskProgress("t", "completed")), b.copy(running = true)), connections = mapOf("a" to HostConnectionStatus(connected = true)))
        assertEquals(listOf(a.key), taskSignals(before, after).map { it.tab?.key })
        assertEquals("本轮已结束", taskSignals(before, after).single().title)
        assertTrue(taskSignals(before, after.copy(sessionTabs = listOf(after.sessionTabs.first().copy(progress = after.sessionTabs.first().progress.copy(seen = true))))).isEmpty())
        assertTrue(taskSignals(before, after.copy(connections = emptyMap())).isEmpty())
        assertTrue(taskSignals(after, after).isEmpty())
        assertTrue(taskSignals(before, after.copy(sessionTabs = emptyList())).isEmpty())
    }
    @Test fun `failure notification is not success and duplicate sync is silent`() {
        val before = PocketState(sessionTabs = listOf(a.copy(running = true, progress = TaskProgress("t", "running"))))
        val after = PocketState(sessionTabs = listOf(a.copy(progress = TaskProgress("t", "failed"))), connections = mapOf("a" to HostConnectionStatus(connected = true)))
        assertEquals("执行失败，请检查", taskSignals(before, after).single().title)
        assertTrue(taskSignals(after, after.copy(sessionTabs = listOf(after.sessionTabs.single().copy(progress = TaskProgress("t", "failed", syncedAt = 1))))).isEmpty())
        assertTrue(taskSignals(before, after.copy(sessionTabs = listOf(a))).isEmpty())
    }
    @Test fun `reconnect discovers an unseen completed turn in a closed tracked task`() {
        val old = a.copy(tabOpen = false, taskReady = false, progress = TaskProgress("old", "completed", accepted = true))
        val latest = old.copy(taskReady = true, progress = TaskProgress("next", "completed"))
        val before = PocketState(taskRecords = listOf(old))
        val after = PocketState(taskRecords = listOf(latest), connections = mapOf("a" to HostConnectionStatus(connected = true)))
        assertEquals(latest.key, taskSignals(before, after).single().tab?.key)
        assertTrue(taskSignals(after, after).isEmpty())
        assertTrue(taskSignals(before, after.copy(taskRecords = listOf(latest.copy(taskReady = false)))).isEmpty())
        assertTrue(taskSignals(before, after.copy(taskRecords = listOf(latest.copy(progress = latest.progress.copy(seen = true))))).isEmpty())
    }
    @Test fun `identical approval IDs on two hosts create separately routed reminders`() {
        val ra = PendingRequest(JsonPrimitive("same"), RequestKind.COMMAND, obj("threadId" to thread.id), "a")
        val rb = ra.copy(profileId = "b")
        val before = PocketState(sessionTabs = listOf(a, b), pending = listOf(ra), connections = mapOf("a" to HostConnectionStatus(connected = true), "b" to HostConnectionStatus(connected = true)))
        val signals = taskSignals(before, before.copy(pending = listOf(ra, rb)))
        assertEquals(1, signals.size); assertEquals(b.key, signals.single().tab?.key)
    }
}
