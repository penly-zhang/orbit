package dev.codexpocket.core

import java.lang.reflect.Proxy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

class MultiAgentRepositoryTest {
    private val project = ProjectInfo("native-project", "Workspace", listOf("/workspace"))
    private fun unsupported(): AgentRepository = Proxy.newProxyInstance(
        AgentRepository::class.java.classLoader, arrayOf(AgentRepository::class.java),
    ) { _, method, _ -> error("Unexpected call: ${method.name}") } as AgentRepository

    private inner class Fixture(val name: String, val timestamps: List<Long> = emptyList()) : AgentRepository by unsupported() {
        override val events = MutableSharedFlow<AgentEvent>()
        override val failure = MutableStateFlow<String?>(null)
        val calls = mutableListOf<String>()
        var closed = false
        override suspend fun initialize() { calls += "initialize" }
        override fun close() { closed = true }
        override suspend fun projects(cached: List<ProjectInfo>) = listOf(project.copy(id = "$name-project"))
        override suspend fun threads(project: ProjectInfo, cursor: String?, archived: Boolean): Pair<List<ThreadInfo>, String?> {
            assertTrue(project.id.startsWith("cwd:"))
            calls += "list:${cursor ?: "first"}:$archived"
            val offset = cursor?.toInt() ?: 0
            val rows = timestamps.drop(offset).take(7).map { ThreadInfo("id-$it", "$name $it", "/workspace", updated = it) }
            return rows to (offset + rows.size).takeIf { it < timestamps.size }?.toString()
        }
        override suspend fun newThread(project: ProjectInfo, root: String, readOnly: Boolean): ThreadInfo {
            calls += "new"
            return ThreadInfo("same-id", name, root)
        }
        override suspend fun send(thread: String, text: String, activeTurn: String?, images: List<OutgoingImage>, context: List<ContextInput>): String {
            calls += "send:$thread:$text"; return "turn"
        }
        override suspend fun renameThread(id: String, name: String) { calls += "rename:$id:$name" }
        override suspend fun attachThread(id: String): ConversationSettings { calls += "attach:$id"; return conversationSettings(obj()) }
        override suspend fun decide(id: JsonElement, accept: Boolean) { calls += "decide:$id:$accept" }
        override suspend fun models(): List<ModelOption> { calls += "models"; return emptyList() }
        override suspend fun reconnectThread(thread: ThreadInfo, project: ProjectInfo, replaceMissingEmpty: Boolean): Pair<ThreadInfo, ConversationSettings> {
            calls += "restore:${thread.id}"
            return thread to conversationSettings(obj())
        }
    }

    @Test fun `identical backend IDs stay isolated across creation sends rename and restore`() = runBlocking {
        val codex = Fixture("codex"); val traex = Fixture("traex")
        val repo = MultiAgentRepository(linkedMapOf("codex" to codex, "traex" to traex), this)
        try {
            val c = repo.newThread(project, "/workspace")
            val t = repo.forEngine("traex").newThread(project, "/workspace")
            assertEquals("same-id", c.id)
            assertEquals("traex::same-id", t.id)
            assertEquals("traex", t.engineId)
            repo.send(c.id, "codex-message", null)
            repo.send(t.id, "traex-message", null)
            repo.renameThread(t.id, "renamed")
            val restored = repo.reconnectThread(t, project).first
            assertEquals(t, restored)
            repo.forEngine(t.engineId).models()
            assertTrue("send:same-id:codex-message" in codex.calls)
            assertFalse(codex.calls.any { "traex-message" in it || it == "models" })
            assertTrue("send:same-id:traex-message" in traex.calls)
            assertTrue("rename:same-id:renamed" in traex.calls)
            assertTrue("restore:same-id" in traex.calls)
        } finally { repo.close() }
        assertTrue(codex.closed && traex.closed)
    }

    @Test fun `project aggregation and pagination preserve global order without duplicate or missing rows`() = runBlocking {
        val c = Fixture("codex", (0L..80L step 2).reversed().toList())
        val t = Fixture("traex", (1L..79L step 2).reversed().toList())
        val repo = MultiAgentRepository(linkedMapOf("codex" to c, "traex" to t), this)
        try {
            assertEquals(1, repo.projects().size)
            val rows = mutableListOf<ThreadInfo>()
            var cursor: String? = null
            do { val page = repo.threads(project, cursor); rows += page.first; cursor = page.second } while (cursor != null)
            assertEquals((0L..80L).reversed().toList(), rows.map { it.updated })
            assertEquals(81, rows.map { it.id }.distinct().size)
            assertEquals(1, c.calls.count { it == "list:first:false" })
            assertEquals(1, t.calls.count { it == "list:first:false" })
            assertEquals(setOf("codex", "traex"), rows.map { it.engineId }.toSet())
        } finally { repo.close() }
    }

    @Test fun `same approval ID is namespaced with its source and replies keep original JSON type`() = runBlocking {
        val c = Fixture("codex"); val t = Fixture("traex")
        val repo = MultiAgentRepository(linkedMapOf("codex" to c, "traex" to t), this)
        try {
            val raw = AgentEvent.Request(JsonPrimitive(7), RequestKind.COMMAND, obj("threadId" to "same-id"))
            val first = namespaceEvent("codex", raw) as AgentEvent.Request
            val second = namespaceEvent("traex", raw) as AgentEvent.Request
            assertNotEquals(first.id, second.id)
            assertEquals("traex::same-id", second.params.text("threadId"))
            repo.decide(second.id, true)
            assertEquals(listOf("decide:7:true"), t.calls)
            assertTrue(c.calls.isEmpty())
            assertEquals(second.id, (namespaceEvent("traex", AgentEvent.RequestResolved(raw.id)) as AgentEvent.RequestResolved).id)
        } finally { repo.close() }
    }

    @Test fun `traex only hosts create traex threads and never resume old codex threads with traex`() = runBlocking {
        val t = Fixture("traex")
        val repo = MultiAgentRepository(linkedMapOf("traex" to t), this)
        try {
            assertEquals(listOf("traex"), repo.availableEngines)
            assertEquals("traex", repo.newThread(project, "/workspace").engineId)
            assertFailsWith<IllegalStateException> { repo.attachThread("legacy-codex-id") }
            assertFalse(t.calls.any { it.startsWith("attach:") })
        } finally { repo.close() }
    }

    @Test fun `saved threads without engine metadata remain codex`() {
        val thread = json.decodeFromString<ThreadInfo>("""{"id":"old","title":"Old task","cwd":"/workspace"}""")
        assertEquals("codex", thread.engineId)
    }
}
