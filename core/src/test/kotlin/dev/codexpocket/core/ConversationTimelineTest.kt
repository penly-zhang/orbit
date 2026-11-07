package dev.codexpocket.core

import kotlinx.serialization.decodeFromString
import org.junit.Test
import kotlin.test.*

class ConversationTimelineTest {
    @Test fun `completed turn folds prose and tools together while final answer stays visible`() {
        val items = listOf(
            ChatItem("u", "userMessage", "检查这个项目", turnId = "t"),
            ChatItem("empty", "reasoning", "", turnId = "t"),
            ChatItem("r", "reasoning", "**Checking files**", turnId = "t", turnDurationMs = 185000),
            ChatItem("progress", "agentMessage", "我会先检查目录。", phase = "commentary", turnId = "t"),
            ChatItem("cmd", "commandExecution", "one\ntwo\n", command = "ls -la", turnId = "t"),
            ChatItem("final", "agentMessage", "检查完成。", phase = "final_answer", turnId = "t"),
        )
        val rows = conversationTimeline(items, null)
        assertEquals(3, rows.size)
        assertEquals("u", (rows[0] as MessageEntry).item.id)
        val work = rows[1] as WorkEntry
        assertEquals(listOf("r", "progress", "cmd"), work.items.map { it.id })
        assertEquals(185000, work.durationMs)
        assertFalse(work.active)
        assertEquals("Checking files", workItemTitle(work.items.first()))
        assertEquals("final", (rows[2] as MessageEntry).item.id)
        assertEquals("3 分 5 秒", workDuration(185000))
    }
    @Test fun `active commentary is visible as progress without losing its phase`() {
        val items = listOf(ChatItem("r", "reasoning", "", turnId = "t"), ChatItem("a", "agentMessage", "正在读取文件。", phase = "commentary", turnId = "t"))
        val rows = conversationTimeline(items, "t")
        assertEquals(1, rows.size)
        assertTrue((rows.single() as WorkEntry).active)
        assertEquals("a", (rows.single() as WorkEntry).items.single().id)
        assertEquals("commentary", (rows.single() as WorkEntry).items.single().phase)
        assertTrue(conversationTimeline(listOf(items.first()), "t").isEmpty())
    }
    @Test fun `legacy cached history keeps its last untagged answer visible`() {
        val old = json.decodeFromString<ChatItem>("""{"id":"terminal","kind":"commandExecution","text":"$ ls -la\nREADME.md","status":"completed"}""")
        val rows = conversationTimeline(listOf(ChatItem("u", "userMessage", "检查"), ChatItem("a1", "agentMessage", "我先看看。"), old, ChatItem("a2", "agentMessage", "完成。")), null)
        assertEquals(3, rows.size)
        assertEquals("a2", (rows.last() as MessageEntry).item.id)
        assertEquals("ls -la", workItemTitle(old))
        assertEquals("README.md", commandOutput(old))
    }
    @Test fun `turn completion collapses the same live progress without losing commands or prose`() {
        val items = listOf(
            ChatItem("u", "userMessage", "开始", turnId = "t"),
            ChatItem("p", "agentMessage", "正在检查", phase = "commentary", turnId = "t"),
            ChatItem("c1", "commandExecution", "first", command = "pwd", turnId = "t"),
            ChatItem("c2", "commandExecution", "error", "failed", command = "ls", turnId = "t"),
            ChatItem("r", "reasoning", "**Checking results**", turnId = "t"),
            ChatItem("a", "agentMessage", "完成", phase = "final_answer", turnId = "t"))
        assertEquals(listOf("u", "a"), conversationTimeline(items, "t").filterIsInstance<MessageEntry>().map { it.item.id })
        assertTrue(conversationTimeline(items, "t").filterIsInstance<WorkEntry>().single().active)
        val done = conversationTimeline(items, null)
        assertEquals(listOf("u", "a"), done.filterIsInstance<MessageEntry>().map { it.item.id })
        val work = done.filterIsInstance<WorkEntry>().single()
        assertEquals(listOf("p", "c1", "c2", "r"), work.items.map { it.id })
        assertEquals("Checking results", workGroupTitle(work.items))
        val groups = workDisplayGroups(work.items)
        assertEquals(listOf(1, 2, 1), groups.map { it.size })
        assertEquals("error", commandOutput(groups[1][1]))
    }
    @Test fun `history preserves final phase and actual turn duration`() {
        val turn = obj("id" to "turn1", "status" to "completed", "startedAt" to 100, "completedAt" to 110,
            "items" to listOf(obj("id" to "a", "type" to "agentMessage", "phase" to "final_answer", "text" to "完成")))
        val item = historyPage(listOf(turn), null).items.single()
        assertEquals("turn1", item.turnId)
        assertEquals("final_answer", item.phase)
        assertEquals(100000, item.turnStartedAtMs)
        assertEquals(10000, item.turnDurationMs)
    }
    @Test fun `steering messages keep work before and after them in chronological order`() {
        val rows = conversationTimeline(listOf(
            ChatItem("u", "userMessage", "开始", turnId = "t"),
            ChatItem("c1", "commandExecution", "one", command = "ls", turnId = "t"),
            ChatItem("u2", "userMessage", "补充要求", turnId = "t"),
            ChatItem("c2", "commandExecution", "two", command = "pwd", turnId = "t", turnDurationMs = 5000),
            ChatItem("a", "agentMessage", "完成", phase = "final_answer", turnId = "t")), null)
        assertEquals(5, rows.size)
        assertNull((rows[1] as WorkEntry).durationMs)
        assertEquals(5000, (rows[3] as WorkEntry).durationMs)
        assertEquals("u2", (rows[2] as MessageEntry).item.id)
    }
}
