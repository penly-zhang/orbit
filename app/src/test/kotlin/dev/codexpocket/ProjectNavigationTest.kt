package dev.codexpocket

import dev.codexpocket.core.*
import org.junit.Test
import kotlin.test.*

class ProjectNavigationTest {
    private val profile = HostProfile(id = "air")
    private val current = ProjectInfo("main", "Main", listOf("/main"))
    private val other = ProjectInfo("web", "Website", listOf("/web"))
    private val state = PocketState(profile = profile, projects = listOf(current, other), project = current,
        threads = listOf(ThreadInfo("one", "Fix keyboard", "/main")),
        navigationThreads = mapOf("web" to listOf(ThreadInfo("two", "整理登录体验", "/web"))))

    @Test fun `title search finds a cached conversation in a collapsed project and preserves its destination`() {
        val result = projectNavigationRows(state, " 登录 ")
        assertEquals(listOf(other), result.map { it.project })
        assertEquals(listOf("two"), result.single().threads.map { it.id })
        assertTrue(result.single().expanded)
        assertEquals(current, state.project)
        assertEquals(listOf("one"), state.threads.map { it.id })
    }
    @Test fun `project search includes its loaded threads and clearing search restores collapsed groups`() {
        assertEquals(listOf("two"), projectNavigationRows(state, "WEBSITE").single().threads.map { it.id })
        assertFalse(projectNavigationRows(state, "").first { it.project == other }.expanded)
        assertTrue(projectNavigationRows(state, "not loaded anywhere").isEmpty())
    }
    @Test fun `opened and unread filters do not leak colliding thread ids from another computer`() {
        val local = SessionTab(state.threads.single(), current, 1, profileId = "air", unread = true)
        val remote = SessionTab(ThreadInfo("two", "Remote secret title", "/web"), other, 2, profileId = "remote", unread = true)
        val s = state.copy(sessionTabs = listOf(local, remote))
        assertTrue(projectNavigationRows(s, "Remote secret").isEmpty())
        assertEquals(listOf("one"), projectNavigationRows(s, "", "unread").flatMap { it.threads }.map { it.id })
        assertEquals(listOf(current), projectNavigationRows(s, "", "opened").map { it.project })
    }
    @Test fun `archive search excludes locally open active conversations`() {
        val opened = SessionTab(ThreadInfo("active", "Active conversation", "/main"), current, 1, profileId = "air")
        val archived = state.copy(showArchived = true, sessionTabs = listOf(opened), threads = listOf(ThreadInfo("old", "Archived conversation", "/main")))
        assertTrue(projectNavigationRows(archived, "Active conversation").isEmpty())
        assertEquals("old", projectNavigationRows(archived, "Archived").single().threads.single().id)
    }
    @Test fun `reconnecting summary counts visible cached tabs and deduplicates list entries`() {
        val saved = SessionTab(ThreadInfo("saved", "Cached conversation", "/main"), current, 1, profileId = "air")
        val reconnecting = state.copy(threads = emptyList(), threadsLoading = true, sessionTabs = listOf(saved), navigationThreads = emptyMap())
        assertEquals("1 条已加载 · 1 个已打开", projectNavigationSummary(reconnecting, current))
        assertEquals("1 条已加载 · 1 个已打开", projectNavigationSummary(reconnecting.copy(threads = listOf(saved.thread)), current))
        assertEquals("会话待加载 · 0 个已打开", projectNavigationSummary(reconnecting, other))
    }

}
