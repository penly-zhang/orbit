package dev.codexpocket.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import org.junit.Test
import java.nio.file.Files
import kotlin.test.*

class AgentEngineTest {
    @Test fun `traex starts its shared service before proxy and keeps startup output off the wire`() {
        val root = Files.createTempDirectory("orbit-shared-service").toFile()
        try {
            val agent = root.resolve("agent with spaces")
            agent.writeText("""#!/bin/sh
printf '%s\n' "${'$'}*" >> "${'$'}PROBE_CALLS"
if [ "${'$'}*" = 'app-server daemon start' ]; then
    echo 'daemon startup log'
    exit "${'$'}{PROBE_START_EXIT:-0}"
fi
if [ "${'$'}*" = 'app-server proxy' ]; then printf 'websocket bytes'; exit 0; fi
exit 99
""")
            assertTrue(agent.setExecutable(true))
            fun run(exit: Int): Triple<Int, String, String> {
                val process = ProcessBuilder("sh", "-c", "agent_bin=${shellQuote(agent.path)}; ${agentProxyInvocation("traex")}").apply {
                    environment()["PROBE_CALLS"] = root.resolve("calls").path
                    environment()["PROBE_START_EXIT"] = exit.toString()
                    redirectOutput(root.resolve("stdout"))
                    redirectError(root.resolve("stderr"))
                }.start()
                if (!process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    error("Shared service command did not finish")
                }
                val output = root.resolve("stdout").readText()
                val errors = root.resolve("stderr").readText()
                return Triple(process.waitFor(), output, errors)
            }
            val success = run(0)
            assertEquals(0, success.first)
            assertEquals("websocket bytes", success.second)
            assertTrue(success.third.contains("daemon startup log"))
            assertEquals(listOf("app-server daemon start", "app-server proxy"), root.resolve("calls").readLines())
            root.resolve("calls").writeText("")
            val failed = run(7)
            assertNotEquals(0, failed.first)
            assertEquals("", failed.second)
            assertEquals(listOf("app-server daemon start"), root.resolve("calls").readLines())
            assertTrue(failed.third.contains("共享后台"))
            assertFalse(agentProxyInvocation("codex").contains("daemon"))
        } finally { root.deleteRecursively() }
    }

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

    @Test fun `supported agents discover their own binary and ignore legacy path overrides`() {
        assertEquals(listOf("codex", "traex"), AgentEngines.default.descriptors.map { it.id })
        for (id in listOf("codex", "traex")) {
            val engine = AgentEngines.default.engine(id) as CodexEngine
            val command = engine.command(HostProfile(codexPath = "/obsolete/custom-agent"))
            assertTrue(command.contains("command -v $id"))
            assertTrue(command.contains("app-server proxy"))
            assertFalse(command.contains("/obsolete/custom-agent"))
        }
    }

    @Test fun `registry rejects missing and duplicate adapters`() = runBlocking<Unit> {
        assertFailsWith<IllegalStateException> { AgentEngines.default.engine("unknown") }
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
