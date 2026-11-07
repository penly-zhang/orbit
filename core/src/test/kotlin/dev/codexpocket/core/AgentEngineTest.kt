package dev.codexpocket.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import org.junit.Test
import kotlin.test.*

class AgentEngineTest {
    @Test fun `old profiles default to Codex and preserve serialized fields`() {
        val old = """{"id":"saved-host","host":"air","codexPath":"/custom/codex","remoteTarget":"work"}"""
        val profile = json.decodeFromString<HostProfile>(old)
        assertEquals("codex", profile.engineId)
        val changed = json.decodeFromString<HostProfile>(json.encodeToString(profile.copy(engineId = "codex-compatible")))
        assertEquals("saved-host", changed.id)
        assertEquals("/custom/codex", changed.codexPath)
        assertEquals("work", changed.remoteTarget)
        assertEquals("codex-compatible", changed.engineId)
    }

    @Test fun `compatible agents require an explicit binary and preserve shell quoting`() {
        val profile = HostProfile(engineId = "codex-compatible")
        val engine = AgentEngines.default.engine(profile.engineId) as CodexEngine
        assertFailsWith<IllegalArgumentException> { engine.command(profile) }
        val command = engine.command(profile.copy(codexPath = "/opt/my agent/bin/fork"))
        assertTrue(command.contains("app-server proxy"))
        assertFalse(command.contains("command -v codex"))
        assertTrue(command.contains("/opt/my agent/bin/fork"))
    }

    @Test fun `registry rejects missing and duplicate adapters`() = runBlocking<Unit> {
        assertFailsWith<IllegalStateException> { AgentEngines.default.open(HostProfile(engineId = "unknown"), Credentials(), this) }
        assertFailsWith<IllegalArgumentException> { AgentEngineRegistry(listOf(CodexEngine(), CodexEngine())) }
    }

    @Test fun `new protocols can register without changing workspace construction`() = runBlocking<Unit> {
        var opened = false
        val custom = object : AgentEngine {
            override val descriptor = EngineDescriptor("fixture", "Fixture")
            override suspend fun open(profile: HostProfile, credentials: Credentials, scope: CoroutineScope): AgentRepository {
                opened = true
                error("fixture open")
            }
        }
        val registry = AgentEngineRegistry(listOf(custom))
        assertEquals(listOf(custom.descriptor), registry.descriptors)
        assertFailsWith<IllegalStateException> { registry.open(HostProfile(engineId = "fixture"), Credentials(), this) }
        assertTrue(opened)
    }

    @Test fun `timeline accepts another adapter without Codex JSON`() {
        val started = sessionEvent(SessionRuntime(), TimelineUpdate.Started("turn", 1000), 1000)
        val delta = sessionEvent(started, TimelineUpdate.Delta("reply", "agentMessage", "hello", "turn"), 1100)
        val completed = sessionEvent(delta, TimelineUpdate.Completed("turn", 1800, null, "completed", null), 1800)
        assertNull(completed.activeTurn)
        assertEquals("hello", completed.items.single().text)
        assertEquals(800L, completed.items.single().turnDurationMs)
    }

    @Test fun `a late completion cannot borrow a newer turns start time`() {
        val state = SessionRuntime(listOf(ChatItem("old-reply", "agentMessage", "old", turnId = "old", turnStartedAtMs = 1000)), "new", 5000)
        val completed = sessionEvent(state, TimelineUpdate.Completed("old", 3000, null, "completed", null), 6000)
        assertEquals("new", completed.activeTurn)
        assertEquals(5000L, completed.startedAtMs)
        assertEquals(2000L, completed.items.single().turnDurationMs)
    }

    @Test fun `unknown notifications do not become timeline updates`() {
        assertNull(codexEvent(obj("method" to "unrelated/event", "params" to obj("threadId" to "thread"))))
        assertNull(codexEvent(obj("id" to 7, "method" to "unsupported/request")))
        val event = codexEvent(obj("method" to "item/agentMessage/delta", "params" to obj("threadId" to "thread", "itemId" to "item", "delta" to "hello")))
        assertEquals(AgentEvent.Timeline("thread", TimelineUpdate.Delta("item", "agentMessage", "hello", "")), event)
    }
}
