package dev.codexpocket

import dev.codexpocket.core.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import org.junit.Test
import kotlin.test.*

class TaskProgressTest {
    private val project = ProjectInfo("p", "Project", listOf("/project"))
    private val tab = SessionTab(ThreadInfo("thread", "Task", "/project"), project, 1, profileId = "a", taskReady = true, tracked = true)
    private fun state(vararg tabs: SessionTab) = PocketState(sessionTabs = tabs.toList(), connections = mapOf("a" to HostConnectionStatus(connected = true)))
    @Test fun `normal completion needs no acceptance and legacy workspaces remain readable`() {
        val finished = tab.copy(progress = TaskProgress("turn", "completed", syncedAt = 42))
        assertEquals("本轮已结束", taskPresentation(state(finished), finished).label)
        assertTrue(attentionTasks(state(finished)).isEmpty())
        assertEquals("result", taskGroup(state(finished), finished))
        assertEquals("状态待核实", taskPresentation(PocketState(), finished).label)
        val accepted = TaskProgress("turn", "completed", accepted = true)
        assertTrue(json.decodeFromString<TaskProgress>(json.encodeToString(accepted)).seen)
        assertTrue(json.decodeFromString<TaskProgress>("""{"turnId":"old","status":"completed","accepted":true}""").seen)
        assertFalse(taskProgress(accepted, TimelineUpdate.Started("next", null), 43).seen)
    }
    @Test fun `late completion cannot replace newer task and reading survives repeated history`() {
        val current = TaskProgress("new", "running")
        val after = taskProgress(current, TimelineUpdate.Completed("old", null, null, "failed", "old error"), 10)
        assertEquals("new", after.turnId); assertEquals("running", after.status)
        val accepted = TaskProgress("new", "completed", seen = true)
        assertTrue(taskProgress(accepted, TimelineUpdate.Completed("new", null, null, "completed", null), 11).seen)
    }
    @Test fun `only failures and actionable issues remain in attention`() {
        val done = tab.copy(progress = TaskProgress("t", "completed"))
        val failed = tab.copy(thread = tab.thread.copy(id = "failed"), progress = TaskProgress("f", "failed"))
        val running = tab.copy(thread = tab.thread.copy(id = "running"), running = true)
        val accepted = done.copy(thread = tab.thread.copy(id = "accepted"), progress = done.progress.copy(accepted = true))
        assertEquals(listOf("failed"), attentionTasks(state(done, running, accepted, failed)).map { it.thread.id })
        assertEquals("需要检查", taskPresentation(state(), tab.copy(taskIssue = "发送结果未确认")).label)
    }
    @Test fun `new send problems remain actionable after accepting the prior turn`() {
        val accepted = tab.copy(progress = TaskProgress("old", "completed", accepted = true))
        val sending = accepted.copy(taskSending = true)
        assertEquals("running", taskGroup(state(sending), sending))
        val uncertain = accepted.copy(taskIssue = "发送结果未确认")
        assertEquals("action", taskGroup(state(uncertain), uncertain))
        assertEquals(listOf(uncertain), attentionTasks(state(uncertain)))
    }
    @Test fun `closing a failure reminder preserves failure and cannot suppress a new turn`() {
        val failed = tab.copy(progress = TaskProgress("t", "failed", dismissed = true, seen = true))
        assertTrue(attentionTasks(state(failed)).isEmpty())
        assertEquals("执行失败", taskPresentation(state(failed), failed).label)
        assertEquals("result", taskGroup(state(failed), failed))
        val next = taskProgress(failed.progress, TimelineUpdate.Started("next", null), 43)
        assertFalse(next.dismissed)
        assertFalse(taskProgress(next, TimelineUpdate.Completed("next", null, null, "failed", "new error"), 44).dismissed)
        val request = PendingRequest(kotlinx.serialization.json.JsonPrimitive("approval"), RequestKind.COMMAND, obj("threadId" to failed.thread.id), failed.profileId)
        assertEquals(listOf(failed), attentionTasks(state(failed).copy(pending = listOf(request))))
        val disconnected = state(failed).copy(connections = emptyMap())
        assertTrue(attentionTasks(disconnected).isEmpty())
        assertEquals("状态待核实", taskPresentation(disconnected, failed).label)
    }
    @Test fun `result does not reuse an earlier answer for a later failed turn`() {
        val s = state().copy(items = listOf(ChatItem("answer", "agentMessage", "old answer", turnId = "old")))
        assertNull(taskResult(s, tab.copy(progress = TaskProgress("new", "failed"))))
    }
}
