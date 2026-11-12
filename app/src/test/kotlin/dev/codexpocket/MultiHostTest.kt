package dev.codexpocket

import dev.codexpocket.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.junit.Test
import java.io.*
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** Real SSH/RPC connections with colliding server-side IDs exercise the actual app coordinator. */
class MultiHostTest {
    private val fixtureClients = ConcurrentHashMap<AgentRepository, RpcClient>()
    private val engines = AgentEngineRegistry(listOf(object : AgentEngine {
        override val descriptor = EngineDescriptor("codex", "Fixture Codex")
        override suspend fun open(profile: HostProfile, credentials: Credentials, scope: CoroutineScope): AgentRepository {
            val ssh = SshConnection.connect(profile, credentials, CodexEngine().command(profile))
            return try {
                val rpc = RpcClient(ssh, scope)
                CodexRepository(rpc, ssh).also { fixtureClients[it] = rpc }
            } catch (error: Exception) { ssh.close(); throw error }
        }
    }))
    private suspend fun AgentRepository.probe(method: String, params: JsonObject? = null): JsonObject =
        fixtureClients.getValue(this).request(method, params)

    private class MemoryVault : PocketStorage {
        private val values = ConcurrentHashMap<String, String>()
        override fun get(name: String) = values[name]
        override fun put(name: String, value: String) { values[name] = value }
        override fun remove(name: String) { values.remove(name) }
    }
    private class Machine(val id: String, start: Boolean = true, requestApproval: Boolean = false, listDelay: Double = 0.0) : Closeable {
        val uploadRoot = Files.createTempDirectory("pocket-images-$id").toFile()
        val approvalLog = Files.createTempFile("pocket-approval-$id", ".txt")
        val authentications = AtomicInteger()
        val server = SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"; port = if (start) 0 else java.net.ServerSocket(0).use { it.localPort }
            keyPairProvider = SimpleGeneratorHostKeyProvider(Files.createTempDirectory("pocket-$id").resolve("key"))
            passwordAuthenticator = org.apache.sshd.server.auth.password.PasswordAuthenticator { user, password, _ ->
                authentications.incrementAndGet(); user == "fixture" && password == "fixture-password"
            }
            commandFactory = org.apache.sshd.server.command.CommandFactory { _, command -> FixtureCommand(command, uploadRoot, if (requestApproval) approvalLog.toString() else null, listDelay) }
            if (start) start()
        }
        val profile: HostProfile get() {
            val raw = Base64.getDecoder().decode(PublicKeyEntry.toString(server.keyPairProvider.loadKeys(null).first().public).split(' ')[1])
            val fingerprint = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(raw))
            return HostProfile(id = id, name = id.uppercase(), host = "127.0.0.1", port = server.port, username = "fixture", fingerprint = fingerprint)
        }
        fun interrupt() { server.activeSessions.forEach { it.close(true) } }
        override fun close() { server.stop(true); uploadRoot.deleteRecursively() }
    }
    private class FixtureCommand(private val command: String, private val uploadRoot: File, private val approvalLog: String?, private val listDelay: Double) : Command {
        private lateinit var input: InputStream; private lateinit var output: OutputStream; private lateinit var errors: OutputStream
        private lateinit var callback: ExitCallback
        private var process: Process? = null
        override fun setInputStream(value: InputStream) { input = value }
        override fun setOutputStream(value: OutputStream) { output = value }
        override fun setErrorStream(value: OutputStream) { errors = value }
        override fun setExitCallback(value: ExitCallback) { callback = value }
        override fun start(channel: ChannelSession, env: Environment) {
            val fixture = File(System.getProperty("pocket.root"), "tools/mock_codex.py")
            val builder = if (command.startsWith("umask 077;") || command.startsWith("pocket_python=") || command.startsWith("pocket_git=")) ProcessBuilder("sh", "-c", command) else ProcessBuilder("python3", fixture.absolutePath)
            builder.environment()["CODEX_POCKET_UPLOAD_ROOT"] = uploadRoot.absolutePath
            builder.environment()["CODEX_HOME"] = File(uploadRoot, "code-home").absolutePath
            if (listDelay > 0) builder.environment()["POCKET_FIXTURE_LIST_DELAY"] = listDelay.toString()
            if (approvalLog != null) builder.environment()["POCKET_FIXTURE_APPROVAL_LOG"] = approvalLog
            val child = builder.start(); process = child
            Thread({ runCatching { pump(input, child.outputStream) }; runCatching { child.outputStream.close() } }, "host-fixture-in").apply { isDaemon = true; start() }
            Thread({ runCatching { pump(child.errorStream, errors) } }, "host-fixture-err").apply { isDaemon = true; start() }
            Thread({ runCatching { pump(child.inputStream, output); callback.onExit(child.waitFor()) } }, "host-fixture-out").apply { isDaemon = true; start() }
        }
        private fun pump(input: InputStream, output: OutputStream) {
            val buffer = ByteArray(8192)
            while (true) { val count = input.read(buffer); if (count < 0) break; output.write(buffer, 0, count); output.flush() }
        }
        override fun destroy(channel: ChannelSession) { process?.destroy() }
    }
    private suspend fun PocketController.await(predicate: (PocketState) -> Boolean): PocketState = try { withTimeout(15000) { state.first(predicate) } }
        catch (e: TimeoutCancellationException) { throw AssertionError("Workspace wait timed out: ${state.value.connections}; error=${state.value.error}", e) }
    private fun storage(vararg profiles: HostProfile) = MemoryVault().apply {
        saveProfiles(profiles.toList()); profiles.forEach { saveCredentials(it.id, Credentials(password = "fixture-password")) }
    }
    private suspend fun exercise(startAOffline: Boolean = false, requestApproval: Boolean = false, listDelay: Double = 0.0, prepare: (MemoryVault) -> Unit = {}, block: suspend (PocketController, Machine, Machine, MemoryVault) -> Unit) {
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val job = SupervisorJob()
        Machine("a", start = !startAOffline, requestApproval = requestApproval, listDelay = listDelay).use { a -> Machine("b", requestApproval = requestApproval, listDelay = listDelay).use { b ->
            val vault = storage(a.profile, b.profile)
            prepare(vault)
            val controller = withContext(dispatcher) { PocketController(vault, CoroutineScope(job + dispatcher), engines) }
            try { withContext(dispatcher) { block(controller, a, b, vault) } }
            finally { withContext(dispatcher) { controller.close() }; job.cancel(); dispatcher.close() }
        } }
    }

    @Test fun `manual disconnect preserves automatic preference and suppresses foreground resume`() = runBlocking {
        exercise { controller, a, b, vault ->
            controller.await { it.connections.values.count { c -> c.connected } == 2 }
            controller.disconnectHost(a.profile)
            assertTrue(vault.profiles().first { it.id == a.profile.id }.autoConnect)
            controller.resumeConnections()
            delay(250)
            assertFalse(controller.state.value.connections.getValue(a.profile.id).connected)
            assertTrue(controller.state.value.connections.getValue(a.profile.id).manuallyPaused)
            assertEquals(1, a.authentications.get())
            assertTrue(controller.state.value.connections.getValue(b.profile.id).connected)
            controller.connectNow(a.profile)
            controller.await { it.connections.getValue(a.profile.id).connected }
            assertFalse(controller.state.value.connections.getValue(a.profile.id).manuallyPaused)
        }
    }

    @Test fun `renaming connected host and changing automatic preference do not reconnect`() = runBlocking {
        exercise { controller, a, _, vault ->
            controller.await { it.connections.values.count { c -> c.connected } == 2 }
            val renamed = a.profile.copy(name = "Work computer")
            controller.saveAndConnect(renamed, Credentials(password = "fixture-password"), true, false)
            controller.setAutoConnect(renamed, false)
            delay(250)
            assertTrue(controller.state.value.connections.getValue(a.profile.id).connected)
            assertEquals(1, a.authentications.get())
            assertEquals("Work computer", vault.profiles().first { it.id == a.profile.id }.name)
            assertFalse(vault.profiles().first { it.id == a.profile.id }.autoConnect)
            controller.disconnectHost(renamed)
            controller.connectNow(renamed)
            controller.await { it.connections.getValue(a.profile.id).connected }
            assertFalse(vault.profiles().first { it.id == a.profile.id }.autoConnect, "Connect once must not enable automatic connection")
        }
    }

    @Test fun `Orbit project removal survives discovery and readding preserves native identity`() = runBlocking {
        exercise { controller, _, _, vault ->
            val ready = controller.await { it.connected && !it.loading && it.projects.isNotEmpty() }
            val project = ready.projects.first()
            val host = ready.profile!!.id
            controller.removeProject(host, project)
            assertFalse(controller.state.value.projects.any { it.id == project.id })
            controller.refreshProjects()
            controller.await { it.loading }
            controller.await { !it.loading }
            assertFalse(controller.state.value.projects.any { it.id == project.id })
            assertFalse(OrbitProjects(vault, host).apply(listOf(project)).any { it.id == project.id })
            assertEquals(listOf(project), OrbitProjects(vault, "other-host").apply(listOf(project)))
            controller.addProject(host, project.roots.first())
            assertTrue(controller.state.value.projects.any { it.id == project.id })
            controller.refreshProjects()
            controller.await { it.loading }
            controller.await { !it.loading }
            assertEquals(1, controller.state.value.projects.count { it.id == project.id })
            val root = "/fixture/new project"
            controller.addProject(host, root)
            controller.addProject(host, "$root/")
            controller.refreshProjects()
            controller.await { it.loading }
            controller.await { !it.loading }
            assertEquals(1, controller.state.value.projects.count { it.id == "cwd:$root" })
            assertTrue(OrbitProjects(vault, host).apply(emptyList()).any { it.roots == listOf(root) })
            assertFailsWith<Exception> { controller.addProject(host, "/fixture/unreadable") }
            assertFalse(controller.state.value.projects.any { "/fixture/unreadable" in it.roots })
        }
    }

    @Test fun `cached subdirectory project and open session migrate to Git root without changing cwd or draft`() = runBlocking {
        val root = Files.createTempDirectory("orbit-project-migration").toRealPath().toFile()
        val init = ProcessBuilder("git", "init", root.path).redirectErrorStream(true).start()
        init.inputStream.readBytes()
        assertEquals(0, init.waitFor())
        try {
            val cwd = root.resolve("app").apply { mkdirs() }.path
            val old = ProjectInfo("cwd:$cwd", "app", listOf(cwd))
            val thread = ThreadInfo("fixture-saved-ok", "Saved session", cwd)
            exercise(prepare = { vault ->
                vault.put("projects:a", json.encodeToString(listOf(old)))
                vault.put("lastProject:a", old.id)
                vault.put("workspace:v1", json.encodeToString(SavedWorkspace(listOf(SavedHost("a", listOf(
                    SavedTab(thread, old, 1, draft = "keep draft")
                ), thread.id)))))
            }) { controller, _, _, vault ->
                val migrated = controller.await { it.connected && it.threadReady && it.project?.id == "cwd:${root.path}" }
                assertEquals(listOf(root.path), migrated.project!!.roots)
                assertEquals(cwd, migrated.thread!!.cwd)
                assertEquals(cwd, migrated.root)
                assertEquals("keep draft", migrated.draft)
                assertEquals(migrated.project, migrated.sessionTabs.single().project)
                assertFalse(migrated.projects.any { it.id == old.id })
                assertEquals(migrated.project.id, vault.get("lastProject:a"))
                controller.home()
                controller.openSession(controller.state.value.sessionTabs.single())
                val reopened = controller.await { it.thread?.id == thread.id && it.threadReady }
                assertEquals(migrated.project, reopened.project)
                assertEquals("keep draft", reopened.draft)
                assertEquals(cwd, reopened.root)
            }
        } finally { root.deleteRecursively() }
    }
    @Test fun `missing rollout restores locally without poisoning other tabs or losing drafts`() = runBlocking {
        val project = ProjectInfo("fixture-project", "Fixture", listOf("/fixture/project"))
        val valid = ThreadInfo("fixture-saved-ok", "Valid", "/fixture/project")
        val missing = ThreadInfo("fixture-missing-rollout", "未命名会话", "/fixture/project")
        val cached = listOf(ChatItem("cached", "userMessage", "cached history"))
        exercise(prepare = { vault ->
            vault.put("workspace:v1", json.encodeToString(SavedWorkspace(listOf(SavedHost("a", listOf(
                SavedTab(valid, project, 1), SavedTab(missing, project, 2, draft = "keep my draft")
            ), valid.id)))))
            vault.put("history:a:${missing.id}", json.encodeToString(cached))
        }) { controller, _, _, _ ->
            controller.await { it.profile?.id == "a" && it.thread?.id == valid.id && it.threadReady }
            delay(300)
            assertNull(controller.state.value.error)
            controller.openSession(controller.state.value.sessionTabs.first { it.thread.id == missing.id })
            val failed = controller.await { it.thread?.id == missing.id && it.threadSyncError != null && !it.attaching }
            assertFalse(failed.threadReady)
            assertEquals("keep my draft", failed.draft)
            assertEquals(cached, failed.items)
            assertNull(failed.error)
            assertTrue(failed.threadSyncError!!.contains("找不到"))
            controller.refreshThread()
            controller.await { it.threadSyncError != null && !it.attaching }
            assertEquals(setOf(valid.id, missing.id), controller.state.value.sessionTabs.map { it.thread.id }.toSet())
            controller.openSession(controller.state.value.sessionTabs.first { it.thread.id == valid.id })
            controller.await { it.thread?.id == valid.id && it.threadReady }
            assertNull(controller.state.value.threadSyncError)
            assertNull(controller.state.value.error)
        }
    }

    @Test fun `missing empty tab is recovered in place with draft and numbering preserved`() = runBlocking {
        val project = ProjectInfo("fixture-project", "Fixture", listOf("/fixture/project"))
        val full = ThreadInfo("fixture-saved-ok", "Existing", "/fixture/project")
        val empty = ThreadInfo("fixture-missing-empty", "未命名会话", "/fixture/project")
        exercise(prepare = { vault ->
            vault.put("workspace:v1", json.encodeToString(SavedWorkspace(listOf(SavedHost("a", listOf(
                SavedTab(full, project, 1), SavedTab(empty, project, 2, draft = "unsent draft")
            ), empty.id)))))
        }) { controller, a, _, _ ->
            val restored = controller.await { it.thread?.id?.startsWith("fixture-new-") == true && it.threadReady }
            assertEquals("unsent draft", restored.draft)
            assertNull(restored.threadSyncError)
            assertTrue(restored.items.isEmpty())
            assertEquals(listOf(1, 2), restored.sessionTabs.map { it.number })
            assertEquals(full.id, restored.sessionTabs.first().thread.id)
            a.interrupt()
            controller.await { !it.connected }
            val again = controller.await { it.connected && it.threadReady }
            assertEquals("unsent draft", again.draft)
            assertNull(again.threadSyncError)
            assertEquals(2, again.sessionTabs.size)
        }
    }

    @Test fun `history waits for resume when the server has not loaded the thread`() = runBlocking {
        exercise { controller, _, _, _ ->
            controller.await { it.hostProjects.any { p -> p.profileId == "a" } }
            controller.selectHostProject(controller.state.value.hostProjects.first { it.profileId == "a" })
            controller.openThread(ThreadInfo("fixture-saved-await-attach", "Existing", "/fixture/project"))
            val ready = controller.await { it.thread?.id == "fixture-saved-await-attach" && it.threadReady && it.items.isNotEmpty() }
            assertTrue(ready.items.any { it.text == "saved history" })
            delay(200)
            assertNull(controller.state.value.threadSyncError)
        }
    }

    @Test fun `closing tabs compacts display labels without changing attachment routing identities`() = runBlocking {
        exercise { controller, _, _, _ ->
            controller.await { it.hostProjects.any { p -> p.profileId == "a" } }
            controller.selectHostProject(controller.state.value.hostProjects.first { it.profileId == "a" })
            repeat(3) { index ->
                controller.newThread()
                controller.await { it.sessionTabs.size == index + 1 && it.threadReady }
            }
            val before = controller.state.value.sessionTabs
            controller.closeConversation(before.first().key)
            val remaining = controller.state.value.sessionTabs
            assertEquals(listOf("1", "2"), remaining.map { it.label })
            assertEquals(before.drop(1).map { it.number }, remaining.map { it.number })
            assertEquals(before.drop(1).map { it.key }, remaining.map { it.key })
            controller.renameSessionTab(remaining.last().key, "工作")
            assertEquals(listOf("1", "工作"), controller.state.value.sessionTabs.map { it.label })
            controller.newThread()
            controller.await { it.sessionTabs.size == 3 && it.threadReady }
            assertEquals(listOf("1", "工作", "3"), controller.state.value.sessionTabs.map { it.label })
        }
    }

    @Test fun `startup shows saved lists before slow server response and isolates host caches`() = runBlocking {
        val project = ProjectInfo("fixture-project", "Fixture", listOf("/fixture/project"))
        exercise(listDelay = 0.6, prepare = { vault ->
            vault.saveProfiles(vault.profiles().map { if (it.id == "b") it.copy(autoConnect = false) else it })
            for (id in listOf("a", "b")) {
                vault.put("projects:$id", json.encodeToString(listOf(project)))
                vault.put("lastProject:$id", project.id)
                vault.put("threadLists:$id", json.encodeToString(listOf(SavedThreadList(project.id, false, listOf(ThreadInfo("cached-$id", "Cached $id", "/fixture/project"))))))
            }
        }) { controller, a, b, vault ->
            val cached = controller.await { it.profile?.id == "a" && it.threadsCached && it.threadsLoading }
            assertEquals("cached-a", cached.threads.single().id)
            controller.loadThreads(); controller.loadThreads()
            controller.await { it.profile?.id == "a" && !it.threadsLoading && !it.threadsCached }
            assertTrue(controller.state.value.threads.isEmpty(), "Deleted remote threads must replace the cache")
            assertEquals(1, File(a.uploadRoot, "list-requests.jsonl").readLines().count { json.parseToJsonElement(it).jsonObject.text("projectId") == project.id }, "Concurrent project list loads must share one request; discovery is separate")
            val stored = json.decodeFromString<List<SavedThreadList>>(vault.get("threadLists:a")!!)
            assertTrue(stored.single().threads.isEmpty())
            controller.selectHost("b"); controller.setConnectionEnabled(b.profile, true)
            val other = controller.await { it.profile?.id == "b" && it.threadsLoading && it.threadsCached }
            assertEquals("cached-b", other.threads.single().id)
            controller.await { it.profile?.id == "b" && !it.threadsLoading && !it.threadsCached }
        }
    }
    @Test fun `list failures retain saved results and retry replaces them without affecting other projects`() = runBlocking {
        val project = ProjectInfo("fixture-list-retry", "Retry", listOf("/fixture/retry"))
        exercise(listDelay = 0.3, prepare = { vault ->
            vault.put("threadLists:a", json.encodeToString(listOf(SavedThreadList(project.id, false, listOf(ThreadInfo("saved", "Saved result", "/fixture/retry"))))))
        }) { controller, _, _, _ ->
            controller.await { it.connected && it.projects.isNotEmpty() }
            controller.selectProject(project)
            val failed = controller.await { !it.threadsLoading && it.threadsError != null }
            assertTrue(failed.threadsCached)
            assertEquals("saved", failed.threads.single().id)
            controller.loadThreads()
            controller.await { !it.threadsLoading && it.threadsError == null && !it.threadsCached }
            assertTrue(controller.state.value.threads.isEmpty())
            controller.selectProject(project)
            controller.selectProject(ProjectInfo("other-project", "Other", listOf("/other")))
            val switched = controller.await { it.project?.id == "other-project" && !it.threadsLoading }
            assertTrue(switched.threads.isEmpty())
            assertNull(switched.threadsError)
        }
    }
    @Test fun `conversation home restores last session after browsing another host and project`() {
        val job = SupervisorJob(); val scope = CoroutineScope(job + Dispatchers.Unconfined)
        val project = ProjectInfo("project", "Project", listOf("/fixture/project"))
        val other = ProjectInfo("other", "Other", listOf("/fixture/other"))
        val thread = ThreadInfo("saved", "Saved", "/fixture/project")
        val vault = storage(HostProfile(id = "a", autoConnect = false), HostProfile(id = "b", autoConnect = false))
        vault.put("workspace:v1", json.encodeToString(SavedWorkspace(listOf(SavedHost("a", listOf(SavedTab(thread, project, 1, draft = "我的草稿")), "saved")))))
        val controller = PocketController(vault, scope, engines)
        try {
            assertFalse(controller.state.value.browsingProjects)
            assertEquals("saved", controller.state.value.thread?.id)
            controller.showProjects()
            assertTrue(controller.state.value.browsingProjects)
            assertEquals("saved", controller.state.value.thread?.id)
            controller.selectHostProject(HostProject("b", "B", other))
            assertNull(controller.state.value.thread)
            controller.returnToConversation()
            assertFalse(controller.state.value.browsingProjects)
            assertEquals("a", controller.state.value.profile?.id)
            assertEquals("saved", controller.state.value.thread?.id)
            assertEquals("我的草稿", controller.state.value.draft)
            controller.showProjects()
            controller.selectHostProject(HostProject("b", "B", other))
            controller.flushRecovery()
        } finally { controller.close() }
        val restored = PocketController(vault, scope, engines)
        try {
            assertFalse(restored.state.value.browsingProjects)
            assertEquals("a", restored.state.value.profile?.id)
            assertEquals("saved", restored.state.value.thread?.id)
            assertEquals("我的草稿", restored.state.value.draft)
        } finally { restored.close(); job.cancel() }
    }
    @Test fun `empty conversation home keeps its draft and default target without creating threads`() {
        val job = SupervisorJob(); val scope = CoroutineScope(job + Dispatchers.Unconfined)
        val project = ProjectInfo("default", "Default", listOf("/fixture/project"))
        val vault = storage(HostProfile(id = "a", autoConnect = false))
        vault.put("projects:a", json.encodeToString(listOf(project)))
        vault.put("lastProject:a", "default")
        val controller = PocketController(vault, scope, engines)
        try {
            assertEquals(project, controller.state.value.project)
            assertNull(controller.state.value.thread)
            assertFalse(controller.state.value.browsingProjects)
            controller.newConversationDraft("未发送的新任务")
            controller.showProjects(); controller.returnToConversation()
            assertFalse(controller.state.value.browsingProjects)
            assertEquals("未发送的新任务", controller.state.value.newConversationDraft)
            controller.sendNewConversation() // Offline: keep the draft, create nothing.
            assertTrue(controller.state.value.sessionTabs.isEmpty())
        } finally { controller.close() }
        val restored = PocketController(vault, scope, engines)
        try {
            assertEquals("未发送的新任务", restored.state.value.newConversationDraft)
            assertEquals(project, restored.state.value.project)
            assertTrue(restored.state.value.sessionTabs.isEmpty())
        } finally { restored.close(); job.cancel() }
    }
    @Test fun `first send from empty home creates and sends exactly one conversation`() = runBlocking {
        exercise { controller, _, _, _ ->
            controller.await { it.connected && it.projects.isNotEmpty() }
            controller.selectProject(controller.state.value.projects.first())
            controller.newConversation()
            controller.newConversationDraft("从主界面直接发送")
            assertTrue(controller.state.value.sessionTabs.isEmpty())
            controller.sendNewConversation(); controller.sendNewConversation()
            controller.await { !it.creatingConversation && it.threadReady && it.thread != null && !it.sending && it.queuedMessages.isEmpty() }
            assertFalse(controller.state.value.browsingProjects)
            assertEquals(1, controller.state.value.sessionTabs.size)
            assertEquals("", controller.state.value.newConversationDraft)
            assertEquals("1", controller.artifactRepository(controller.state.value.profile!!.id).probe("probe/stats").text("firstMessages"))
        }
    }
    @Test fun `new task attachments persist without a host and survive rejected send`() = runBlocking {
        val vault = MemoryVault()
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Unconfined)
        val file = Files.createTempFile("new-task", ".txt").toFile().apply { writeText("fixture attachment") }
        val input = ContextInput("local", "notes.txt", localPath = file.absolutePath, bytes = file.length().toInt())
        val first = PocketController(vault, scope, engines)
        try {
            assertTrue(first.beginContextImport(NEW_TASK_DRAFT, 0))
            assertTrue(first.finishContextImport(NEW_TASK_DRAFT, 0, listOf(input)))
            first.sendNewConversation()
            assertEquals(listOf(input), first.state.value.newContext)
            first.close()
            val restored = PocketController(vault, scope, engines)
            try {
                assertEquals(listOf(input), restored.state.value.newContext)
                restored.removeContext(NEW_TASK_DRAFT, 0, input.id)
                assertTrue(restored.state.value.newContext.isEmpty())
            } finally { restored.close() }
        } finally { first.close(); job.cancel(); file.delete() }
    }
    @Test fun `attachment only first send transfers ownership to one conversation`() = runBlocking {
        exercise { controller, _, _, _ ->
            controller.await { it.connected && it.projects.isNotEmpty() }
            controller.selectProject(controller.state.value.projects.first())
            controller.newConversation()
            val file = Files.createTempFile("new-task-send", ".txt").toFile().apply { writeText("fixture document") }
            try {
                assertTrue(controller.beginContextImport(NEW_TASK_DRAFT, 0))
                assertTrue(controller.finishContextImport(NEW_TASK_DRAFT, 0, listOf(ContextInput("local", "notes.txt", localPath = file.absolutePath, bytes = file.length().toInt()))))
                controller.sendNewConversation(); controller.sendNewConversation()
                controller.await { !it.creatingConversation && it.threadReady && it.thread != null && !it.sending && it.queuedMessages.isEmpty() }
                assertEquals(1, controller.state.value.sessionTabs.size)
                assertTrue(controller.state.value.newContext.isEmpty())
                assertEquals("1", controller.artifactRepository(controller.state.value.profile!!.id).probe("probe/stats").text("firstMessages"))
            } finally { file.delete() }
        }
    }
    @Test fun `restart and reconnect reconcile a closed tracked task without opening a tab`() = runBlocking {
        val project = ProjectInfo("fixture-project", "Fixture", listOf("/fixture/project"))
        val thread = ThreadInfo("fixture-saved-tracked", "Tracked", "/fixture/project")
        exercise(prepare = { vault ->
            vault.put("workspace:v1", json.encodeToString(SavedWorkspace(listOf(SavedHost("a", listOf(
                SavedTab(thread, project, 1, progress = TaskProgress("saved-turn", "running"), tabOpen = false, tracked = true)
            ))))))
        }) { controller, a, _, _ ->
            controller.await { it.taskRecords.any { tab -> tab.taskReady && tab.progress.status == "completed" } }
            assertTrue(controller.state.value.sessionTabs.isEmpty())
            assertEquals("result", taskGroup(controller.state.value, controller.state.value.taskRecords.single()))
            controller.disconnectHost(a.profile)
            assertEquals("result", taskGroup(controller.state.value, controller.state.value.taskRecords.single()))
            controller.connectNow(a.profile)
            controller.await { it.taskRecords.single().taskReady && it.connections["a"]?.connected == true }
            assertTrue(controller.state.value.sessionTabs.isEmpty())
            assertEquals("result", taskGroup(controller.state.value, controller.state.value.taskRecords.single()))
            controller.openSession(controller.state.value.taskRecords.single())
            controller.await { it.thread?.id == thread.id && it.threadReady }
        }
    }

    @Test fun `closed tracked task receives completion survives storage and can reopen`() = runBlocking {
        exercise { controller, _, _, vault ->
            controller.await { it.connected && it.projects.isNotEmpty() }
            controller.selectProject(controller.state.value.projects.first())
            controller.newConversation(); controller.newConversationDraft("fixture-queue-tracked")
            controller.sendNewConversation()
            controller.await { it.thread != null && it.activeTurn != null && !it.sending && !it.creatingConversation }
            val tab = controller.state.value.sessionTabs.single()
            assertTrue(tab.tracked)
            controller.closeConversation(tab.key)
            assertTrue(controller.state.value.sessionTabs.isEmpty())
            assertFalse(controller.state.value.taskRecords.single().tabOpen)
            controller.artifactRepository(tab.profileId).probe("probe/completeTurn", obj("threadId" to tab.thread.id))
            controller.await { it.taskRecords.single().progress.status == "completed" }
            assertEquals("result", taskGroup(controller.state.value, controller.state.value.taskRecords.single()))
            controller.flushRecovery()
            val saved = json.decodeFromString<SavedWorkspace>(vault.get("workspace:v1")!!).hosts.flatMap { it.tabs }.single()
            assertFalse(saved.tabOpen); assertTrue(saved.tracked)
            controller.openSession(controller.state.value.taskRecords.single())
            controller.await { it.threadReady && it.sessionTabs.size == 1 }
            assertTrue(controller.state.value.taskRecords.single().progress.seen)
            controller.draft("fixture-queue-next"); controller.send()
            controller.await { it.activeTurn != null && !it.sending }
            assertFalse(controller.state.value.taskRecords.single().progress.seen)
        }
    }

    @Test fun `viewing a completed turn is automatic and remains read across refresh and storage`() = runBlocking {
        exercise { controller, _, _, vault ->
            controller.await { it.connected && it.projects.isNotEmpty() }
            controller.selectProject(controller.state.value.projects.first())
            controller.newConversation(); controller.newConversationDraft("fixture-queue-acceptance")
            controller.sendNewConversation()
            controller.await { it.thread != null && it.activeTurn != null && !it.sending && !it.creatingConversation }
            controller.setAppVisible(false)
            controller.artifactRepository(controller.state.value.profile!!.id).probe("probe/completeTurn", obj("threadId" to controller.state.value.thread!!.id))
            controller.await { it.sessionTabs.any { tab -> tab.taskReady && tab.progress.status == "completed" } && !it.sending }
            val tab = controller.state.value.sessionTabs.single()
            assertEquals("本轮已结束", taskPresentation(controller.state.value, tab).label)
            assertTrue(attentionTasks(controller.state.value).isEmpty())
            assertFalse(tab.progress.seen)
            controller.setAppVisible(true)
            assertTrue(controller.state.value.sessionTabs.single().progress.seen)
            controller.flushRecovery()
            val saved = json.decodeFromString<SavedWorkspace>(vault.get("workspace:v1")!!)
            assertTrue(saved.hosts.flatMap { it.tabs }.single().progress.seen)
            controller.refreshThread()
            controller.await { it.threadReady && !it.attaching }
            assertTrue(controller.state.value.sessionTabs.single().progress.seen)
        }
    }
    @Test fun `first home send stays on its originating host while browsing elsewhere`() = runBlocking {
        exercise { controller, _, _, _ ->
            controller.await { it.connections.values.count { c -> c.connected } == 2 && it.hostProjects.size == 2 }
            controller.selectHostProject(controller.state.value.hostProjects.first { it.profileId == "a" })
            controller.newConversation(); controller.newConversationDraft("发送后查看其他电脑")
            controller.sendNewConversation()
            controller.showProjects(); controller.selectHost("b")
            controller.await { !it.creatingConversation && it.sessionTabs.any { tab -> tab.profileId == "a" } }
            assertTrue(controller.state.value.browsingProjects)
            assertEquals("b", controller.state.value.profile?.id)
            controller.returnToConversation()
            controller.await { it.threadReady && !it.sending && it.queuedMessages.isEmpty() }
            assertEquals("a", controller.state.value.profile?.id)
            assertEquals("1", controller.artifactRepository("a").probe("probe/stats").text("firstMessages"))
            assertEquals("0", controller.artifactRepository("b").probe("probe/stats").text("firstMessages"))
        }
    }
    @Test fun `navigation management targets a background host without changing the visible conversation`() = runBlocking {
        exercise { controller, _, _, _ ->
            controller.await { it.connections.values.count { c -> c.connected } == 2 && it.hostProjects.size == 2 }
            controller.selectHostProject(controller.state.value.hostProjects.first { it.profileId == "a" })
            controller.newThread(); controller.await { it.threadReady }
            val source = controller.state.value.thread!!
            val sourceProject = controller.state.value.project!!
            controller.draft("source management fixture"); controller.send()
            controller.await { !it.sending && it.draft.isBlank() }
            controller.refreshThread()
            controller.await { it.threadReady && it.activeTurn == null && it.items.any { item -> item.kind == "userMessage" } }
            controller.draft("保留来源会话草稿")
            controller.selectHostProject(controller.state.value.hostProjects.first { it.profileId == "b" })
            controller.newThread(); controller.await { it.threadReady }
            val visible = controller.state.value.thread!!
            controller.draft("保留当前会话草稿")
            val target = controller.conversationState("a", source.id)!!
            assertEquals("保留来源会话草稿", target.draft)
            assertEquals(source.id, target.thread?.id)
            controller.compactThread("a", source.id)
            withTimeout(15000) { while (controller.artifactRepository("a").probe("probe/stats").text("compactions") != "1") delay(20) }
            controller.await { controller.conversationState("a", source.id)?.loading == false }
            assertEquals("1", controller.artifactRepository("a").probe("probe/stats").text("compactions"))
            assertEquals("", controller.artifactRepository("b").probe("probe/stats").text("compactions"))
            controller.pinThread("a", sourceProject.id, source.id, true)
            assertTrue(controller.isThreadPinned("a", sourceProject.id, source.id))
            assertFalse(controller.isThreadPinned("b", sourceProject.id, source.id))
            controller.forkThread("a", source.id)
            controller.await { it.sessionTabs.count { tab -> tab.profileId == "a" } == 2 }
            assertEquals("b", controller.state.value.profile?.id)
            assertEquals(visible, controller.state.value.thread)
            assertEquals("保留当前会话草稿", controller.state.value.draft)
            assertEquals("保留来源会话草稿", controller.conversationState("a", source.id)?.draft)
            assertTrue(controller.conversationState("a", source.id)!!.items.none { it.text == "保留当前会话草稿" })
        }
    }
    @Test fun `search results open their own project without overwriting another conversation draft`() = runBlocking {
        exercise { controller, _, _, _ ->
            controller.await { it.connected && it.projects.isNotEmpty() }
            val project = controller.state.value.projects.first()
            controller.selectProject(project); controller.newThread(); controller.await { it.threadReady }
            val thread = controller.state.value.thread!!
            controller.draft("搜索前的草稿")
            controller.showProjects()
            controller.selectProject(ProjectInfo("different", "Different", listOf("/fixture/different")))
            controller.openProjectThread(project, thread)
            controller.await { it.threadReady && it.thread?.id == thread.id }
            assertEquals(project.id, controller.state.value.project?.id)
            assertEquals("搜索前的草稿", controller.state.value.draft)
            assertFalse(controller.state.value.browsingProjects)
        }
    }
    @Test fun `horizontal session switching works in both directions and stops at the ends across hosts`() {
        val job = SupervisorJob(); val scope = CoroutineScope(job + Dispatchers.Unconfined)
        val project = ProjectInfo("project", "Project", listOf("/fixture/project"))
        val first = ThreadInfo("first", "First", "/fixture/project")
        val middle = ThreadInfo("middle", "Middle", "/fixture/project")
        val last = ThreadInfo("last", "Last", "/fixture/project")
        val vault = storage(HostProfile(id = "a", autoConnect = false), HostProfile(id = "b", autoConnect = false))
        vault.put("workspace:v1", json.encodeToString(SavedWorkspace(listOf(
            SavedHost("a", listOf(SavedTab(first, project, 1, draft = "first draft"), SavedTab(middle, project, 2, draft = "middle draft")), "middle"),
            SavedHost("b", listOf(SavedTab(last, project, 3, draft = "last draft")), "last")))))
        val controller = PocketController(vault, scope, engines)
        fun verify(id: String, draft: String) {
            assertEquals(id, controller.state.value.thread?.id)
            assertEquals(draft, controller.state.value.draft)
            assertFalse(controller.state.value.browsingProjects)
        }
        try {
            verify("middle", "middle draft")
            controller.switchSession(1); verify("last", "last draft")
            controller.switchSession(1); verify("last", "last draft")
            controller.switchSession(-1); verify("middle", "middle draft")
            controller.switchSession(-1); verify("first", "first draft")
            controller.switchSession(-1); verify("first", "first draft")
        } finally { controller.close(); job.cancel() }
    }
    @Test fun `single session swipes leave the conversation and draft in place`() {
        val job = SupervisorJob(); val scope = CoroutineScope(job + Dispatchers.Unconfined)
        val project = ProjectInfo("project", "Project", listOf("/fixture/project"))
        val thread = ThreadInfo("only", "Only", "/fixture/project")
        val vault = storage(HostProfile(id = "a", autoConnect = false))
        vault.put("workspace:v1", json.encodeToString(SavedWorkspace(listOf(SavedHost("a", listOf(SavedTab(thread, project, 1, draft = "keep draft")), "only")))))
        val controller = PocketController(vault, scope, engines)
        try {
            listOf(-1, 1, -1, 1).forEach { direction ->
                controller.switchSession(direction)
                assertEquals("only", controller.state.value.thread?.id)
                assertEquals("keep draft", controller.state.value.draft)
                assertFalse(controller.state.value.browsingProjects)
            }
        } finally { controller.close(); job.cancel() }
    }
    @Test fun `pins persist in newest first order and remain isolated by host and project`() {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Unconfined)
        val vault = storage(HostProfile(id = "a", name = "A", autoConnect = false), HostProfile(id = "b", name = "B", autoConnect = false))
        val controller = PocketController(vault, scope, engines)
        try {
            controller.selectHost("a")
            controller.pinThread("project", "one", true)
            controller.pinThread("project", "two", true)
            controller.pinThread("other", "one", true)
            assertEquals(listOf("two", "one"), controller.state.value.pinnedThreads["project"])
            controller.pinThread("project", "two", true)
            assertEquals(listOf("two", "one"), controller.state.value.pinnedThreads["project"])
            controller.selectHost("b")
            assertTrue(controller.state.value.pinnedThreads.isEmpty())
            controller.pinThread("project", "one", true)
            controller.selectHost("a")
            controller.pinThread("project", "two", false)
            assertEquals(listOf("one"), controller.state.value.pinnedThreads["project"])
            assertEquals(listOf("one"), controller.state.value.pinnedThreads["other"])
        } finally { controller.close() }
        val restored = PocketController(vault, scope, engines)
        try {
            assertEquals(listOf("one"), restored.state.value.pinnedThreads["project"])
            assertEquals(listOf("one"), restored.state.value.pinnedThreads["other"])
            restored.selectHost("b")
            assertEquals(listOf("one"), restored.state.value.pinnedThreads["project"])
            assertNull(restored.state.value.pinnedThreads["other"])
        } finally { restored.close(); job.cancel() }
    }
    @Test fun `list rename targets its host and unopened thread without changing the visible session`() = runBlocking {
        exercise { controller, _, _, _ ->
            controller.await { it.connections.values.count { c -> c.connected } == 2 && it.hostProjects.size == 2 }
            controller.selectHostProject(controller.state.value.hostProjects.first { it.profileId == "a" })
            controller.newThread(); controller.await { it.threadReady && it.thread != null }
            controller.draft("rename fixture"); controller.send()
            controller.await { !it.sending && it.draft.isBlank() }
            controller.closeConversation(controller.state.value.sessionTabs.first { it.profileId == "a" }.key)
            controller.loadThreads()
            val listed = controller.await { !it.threadsLoading && it.threads.isNotEmpty() }.threads.first()
            assertNull(controller.state.value.thread)
            controller.renameThread("a", listed.id, "  列表重命名  ")
            controller.await { it.threads.any { t -> t.id == listed.id && t.title == "列表重命名" } }
            assertNull(controller.state.value.thread)
            controller.openThread(controller.state.value.threads.first { it.id == listed.id })
            controller.await { it.threadReady }
            controller.draft("保留当前草稿")
            controller.closeThread()
            assertNull(controller.state.value.thread)
            val tab = controller.state.value.sessionTabs.first { it.profileId == "a" && it.thread.id == listed.id }
            assertTrue(tab.hasDraft)
            controller.selectHostProject(controller.state.value.hostProjects.first { it.profileId == "b" })
            controller.newThread(); controller.await { it.threadReady && it.thread != null }
            val visible = controller.state.value.thread!!
            controller.draft("另一台电脑的草稿")
            controller.renameThread("a", listed.id, "后台会话名称")
            controller.await { it.sessionTabs.any { t -> t.profileId == "a" && t.thread.id == listed.id && t.thread.title == "后台会话名称" } }
            assertEquals("b", controller.state.value.profile?.id)
            assertEquals(visible, controller.state.value.thread)
            assertEquals("另一台电脑的草稿", controller.state.value.draft)
            controller.openSession(controller.state.value.sessionTabs.first { it.profileId == "a" && it.thread.id == listed.id })
            controller.await { it.threadReady && it.thread?.title == "后台会话名称" }
            assertEquals("保留当前草稿", controller.state.value.draft)
        }
    }
    @Test fun `archive undo restores the thread without changing the selected archive filter`() = runBlocking {
        exercise { controller, _, _, _ ->
            controller.await { it.connections.values.count { c -> c.connected } == 2 && it.hostProjects.size == 2 }
            controller.selectHostProject(controller.state.value.hostProjects.first { it.profileId == "a" })
            controller.newThread(); controller.await { it.threadReady && it.thread != null }
            val thread = controller.state.value.thread!!
            controller.draft("保留草稿"); controller.archiveThread(thread)
            assertEquals("保留草稿", controller.state.value.draft)
            assertNotNull(controller.state.value.error)
            controller.clearError()
            controller.draft("archive fixture"); controller.send()
            controller.await { !it.sending && it.draft.isBlank() }
            assertEquals("1", controller.artifactRepository("a").probe("probe/stats").text("firstMessages"))
            controller.archiveThread(thread)
            controller.await { it.archivingThread == null && it.archiveUndo?.thread?.id == thread.id }
            assertFalse(controller.state.value.showArchived)
            controller.undoArchive()
            controller.await { it.archivingThread == null && it.archiveUndo == null && it.threads.any { t -> t.id == thread.id } }
            assertFalse(controller.state.value.showArchived)
            controller.showArchived(true)
            controller.await { !it.threadsLoading && it.showArchived }
            assertTrue(controller.state.value.threads.none { it.id == thread.id })
        }
    }
    @Test fun `directory failures remain distinct from empty folders and successful retry clears the error`() = runBlocking {
        exercise { controller, _, _, _ ->
            controller.await { it.connected && it.projects.isNotEmpty() }
            controller.loadFiles("/fixture/unreadable")
            val failure = controller.await { !it.filesLoading && it.filesError != null }
            assertTrue(failure.files.isEmpty())
            assertNotEquals("/fixture/unreadable", failure.filesPath)
            controller.loadFiles("/fixture/project")
            val success = controller.await { !it.filesLoading && it.filesPath == "/fixture/project" }
            assertNull(success.filesError); assertTrue(success.files.isNotEmpty())
        }
    }
    @Test fun `context drafts and queued attachments stay on their own host and survive persistence`() = runBlocking {
        exercise { controller, a, b, vault ->
            controller.await { it.connections.values.count { c -> c.connected } == 2 && it.hostProjects.size == 2 }
            controller.selectHostProject(controller.state.value.hostProjects.first { it.profileId == "a" })
            controller.newThread(); controller.await { it.threadReady && it.thread != null }
            val tabA = controller.state.value.sessionTabs.single()
            val attachment = Files.createTempFile("phone-log", ".txt").toFile().apply { writeText("private fixture log") }
            try {
                assertTrue(controller.finishContextImport("a", tabA.number, listOf(ContextInput("log", "trace.log", localPath = attachment.path, bytes = attachment.length().toInt()))))
                controller.selectHostProject(controller.state.value.hostProjects.first { it.profileId == "b" })
                controller.newThread(); controller.await { it.profile?.id == "b" && it.threadReady && it.thread != null }
                val tabB = controller.state.value.sessionTabs.last()
                val other = ContextInput("b-file", "other.kt", "/fixture/b.kt", startLine = 2, endLine = 4)
                assertTrue(controller.finishContextImport("b", tabB.number, listOf(other)))
                controller.openSession(tabA); controller.await { it.profile?.id == "a" && it.threadReady }
                assertEquals("log", controller.state.value.draftContext.single().id)
                controller.draft("fixture-queue-start"); controller.send()
                controller.await { it.activeTurn != null && !it.sending }
                assertFalse(attachment.exists())
                assertTrue(controller.state.value.draftContext.isEmpty())
                val skill = ContextInput("skill", "fixture-skill", "/fixture/SKILL.md", "skill")
                assertTrue(controller.finishContextImport("a", tabA.number, listOf(skill)))
                controller.draft("queued skill"); controller.send()
                val queued = controller.state.value.queuedMessages.single()
                assertEquals(skill, queued.context.single())
                val saved = json.decodeFromString<SavedWorkspace>(vault.get("workspace:v1")!!)
                assertEquals(skill, saved.hosts.first { it.id == "a" }.tabs.single().queuedMessages.single().context.single())
                controller.openSession(tabB); controller.await { it.profile?.id == "b" && it.threadReady }
                assertEquals(other, controller.state.value.draftContext.single())
                val repo = controller.artifactRepository("a")
                repo.probe("probe/completeTurn", obj("threadId" to tabA.thread.id))
                withTimeout(10000) { while (repo.probe("probe/stats").array("messages").size < 2) delay(20) }
                val sent = repo.probe("probe/stats").array("messages")
                val path = sent.first().jsonObject.text("text").substringAfter("\n\n@")
                assertTrue(path.startsWith(a.uploadRoot.path + "/")); assertFalse(path.startsWith(b.uploadRoot.path + "/"))
                assertEquals("private fixture log", File(path).readText())
                assertEquals("skill", sent.last().jsonObject.array("input").last().jsonObject.text("type"))
                assertEquals(other, controller.state.value.draftContext.single())
            } finally { attachment.delete() }
        }
    }
    @Test fun `running messages queue support edit cancel steer and send in background on their own host`() = runBlocking {
        exercise { controller, _, _, _ ->
            controller.await { it.connections.values.count { c -> c.connected } == 2 && it.hostProjects.size == 2 }
            controller.selectHostProject(controller.state.value.hostProjects.first { it.profileId == "a" })
            controller.newThread(); controller.await { it.threadReady && it.thread != null }
            val thread = controller.state.value.thread!!.id
            controller.draft("fixture-queue-start"); controller.send()
            controller.await { it.activeTurn != null && !it.sending }
            controller.draft("fixture-queue-first"); controller.send()
            val first = controller.state.value.queuedMessages.single().id
            assertEquals("", controller.state.value.draft)
            controller.editQueuedMessage(first, "fixture-queue-edited")
            controller.draft("cancel me"); controller.send()
            controller.cancelQueuedMessage(controller.state.value.queuedMessages.last().id)
            assertEquals(listOf("fixture-queue-edited"), controller.state.value.queuedMessages.map { it.text })
            val repository = controller.artifactRepository("a")
            assertEquals(1, repository.probe("probe/stats", obj())["firstMessages"]!!.jsonPrimitive.int)
            controller.draft("fixture-queue-steer"); controller.send()
            controller.steerQueuedMessage(controller.state.value.queuedMessages.last().id)
            controller.await { !it.sending && it.queuedMessages.size == 1 }
            assertEquals("turn/steer", repository.probe("probe/stats", obj()).text("lastMethod"))
            val tabA = controller.state.value.sessionTabs.single()
            controller.selectHostProject(controller.state.value.hostProjects.first { it.profileId == "b" })
            controller.newThread(); controller.await { it.profile?.id == "b" && it.threadReady && it.thread != null }
            controller.draft("untouched B draft")
            assertTrue(controller.state.value.queuedMessages.isEmpty())
            repository.probe("probe/completeTurn", obj("threadId" to thread))
            withTimeout(10000) { while (repository.probe("probe/stats", obj())["firstMessages"]!!.jsonPrimitive.int != 3) delay(20) }
            assertEquals("untouched B draft", controller.state.value.draft)
            controller.openSession(tabA)
            controller.await { it.profile?.id == "a" && !it.sending && it.queuedMessages.isEmpty() && it.activeTurn != null }
            val messages = repository.probe("probe/stats", obj()).array("messages").map { it.jsonObject.text("text") }
            assertEquals(listOf("fixture-queue-start", "fixture-queue-steer", "fixture-queue-edited"), messages)
        }
    }
    @Test fun `failed queued sends pause successors and persisted pending messages never replay on restore`() = runBlocking {
        exercise { controller, a, b, vault ->
            controller.await { it.connected && it.projects.isNotEmpty() }
            controller.selectProject(controller.state.value.projects.first())
            controller.newThread(); controller.await { it.threadReady && it.thread != null }
            val thread = controller.state.value.thread!!.id
            controller.draft("fixture-queue-start"); controller.send(); controller.await { it.activeTurn != null && !it.sending }
            controller.draft("fixture-reject-images"); controller.send()
            controller.draft("fixture-queue-later"); controller.send()
            controller.artifactRepository("a").probe("probe/completeTurn", obj("threadId" to thread))
            val failed = controller.await { it.queuePaused && !it.sending && it.queuedMessages.firstOrNull()?.error != null }
            assertEquals(2, failed.queuedMessages.size)
            controller.editQueuedMessage(failed.queuedMessages.first().id, "fixture-queue-fixed")
            controller.resumeQueuedMessages()
            val inFlight = json.decodeFromString<SavedWorkspace>(vault.get("workspace:v1")!!)
                .hosts.first { it.id == "a" }.tabs.first { it.thread.id == thread }.queuedMessages.first()
            assertTrue(inFlight.sending, "Queue ownership must be durable before sending over the network")
            controller.await { !it.sending && it.queuedMessages.size == 1 && it.activeTurn != null }
            controller.setConnectionEnabled(a.profile, false); controller.setConnectionEnabled(b.profile, false)
            controller.flushRecovery(); controller.close()
            val restoredScope = CoroutineScope(currentCoroutineContext() + SupervisorJob(currentCoroutineContext()[Job]))
            val restored = PocketController(vault, restoredScope, engines)
            try {
                val saved = restored.await { it.thread != null && it.queuedMessages.isNotEmpty() }
                assertTrue(saved.queuePaused)
                assertEquals("fixture-queue-later", saved.queuedMessages.single().text)
                assertFalse(saved.queuedMessages.single().sending)
                assertFalse(saved.connected)
            } finally { restored.close(); restoredScope.cancel() }
        }
    }
    @Test fun `git read failures remain errors rather than a verified clean worktree`() = runBlocking {
        exercise { controller, _, _, _ ->
            controller.await { it.connected && it.projects.isNotEmpty() }
            controller.selectProject(controller.state.value.projects.first())
            controller.tab(2)
            val failed = controller.await { !it.changesLoading && it.changesError != null }
            assertNull(failed.changesRoot)
            assertTrue(failed.changes.isEmpty())
            assertNull(failed.error, "Review errors should stay visible on the review screen")
        }
    }
    @Test fun `review comments queue by default revalidate before delivery and can be relocated`() = runBlocking {
        val root = Files.createTempDirectory("pocket-app-review").toFile()
        fun git(vararg args: String) {
            val p = ProcessBuilder(listOf("git", "-C", root.path) + args).redirectErrorStream(true).start()
            val output = p.inputStream.bufferedReader().readText()
            assertEquals(0, p.waitFor(), output)
        }
        try {
            git("init", "-q"); root.resolve("code.kt").writeText("base\n"); git("add", ".")
            git("-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "commit", "-qm", "base")
            root.resolve("code.kt").writeText("changed\n")
            exercise { controller, _, _, vault ->
                controller.await { it.connected && it.projects.isNotEmpty() }
                controller.selectProject(ProjectInfo("cwd:${root.path}", "Review", listOf(root.path)))
                controller.newThread(); controller.await { it.threadReady && it.thread != null }
                val thread = controller.state.value.thread!!.id
                controller.draft("fixture-queue-start"); controller.send(); controller.await { it.activeTurn != null && !it.sending }
                delay(8500)
                assertNull(controller.state.value.taskReview, "Running turns must not poll code changes, even in the foreground")
                controller.chooseReviewRange("task")
                controller.await { it.taskReview != null && !it.taskReviewLoading }
                controller.chooseReviewRange("workspace")
                controller.tab(2); controller.await { !it.changesLoading && it.changesRoot == root.path }
                val change = controller.state.value.changes.single()
                controller.toggleReviewViewed(change.path, "working")
                assertTrue(changeViewed(change, "workspace", null, controller.state.value.reviewViewed))
                controller.openDiff(change, "working"); controller.await { it.diff != null }
                val line = diffLines(controller.state.value.diff!!).first { it.text == "+changed" }
                controller.addReviewComment(line, "fix this")
                val comment = controller.state.value.reviewComments.single()
                controller.sendReviewComments(); controller.sendReviewComments()
                controller.await { !it.sendingComments && it.queuedMessages.size == 1 }
                val tab = controller.state.value.sessionTabs.single()
                vault.put("reviewComments:a:${root.path}", json.encodeToString(listOf(comment)))
                controller.selectProject(ProjectInfo("cwd:${root.path}", "Review", listOf(root.path)))
                assertTrue(controller.state.value.reviewComments.isEmpty(), "A crash between durable enqueue and draft cleanup must not resurrect the same comment")
                controller.openSession(tab)
                controller.await { it.threadReady && it.activeTurn != null && it.queuedMessages.size == 1 }
                val repository = controller.artifactRepository("a")
                assertEquals(1, repository.probe("probe/stats", obj())["firstMessages"]!!.jsonPrimitive.int)
                root.resolve("code.kt").writeText("shift\nchanged\n")
                repository.probe("probe/completeTurn", obj("threadId" to thread))
                val stopped = controller.await { it.queuePaused && !it.sending && it.queuedMessages.firstOrNull()?.error != null }
                assertContains(stopped.queuedMessages.single().error!!, "位置已变化")
                assertEquals(1, repository.probe("probe/stats", obj())["firstMessages"]!!.jsonPrimitive.int)
                assertEquals(1, stopped.taskReview?.changes?.size)
                controller.restoreQueuedReview(stopped.queuedMessages.single().id)
                controller.await { !it.changesLoading && it.reviewComments.singleOrNull()?.stale == true }
                assertFalse(changeViewed(controller.state.value.changes.single(), "workspace", null, controller.state.value.reviewViewed))
                controller.relocateReviewComment(comment.id)
                controller.await { it.reanchorComment == comment.id && it.diff != null }
                val relocated = diffLines(controller.state.value.diff!!).first { it.text == "+changed" }
                assertEquals(2, relocated.newLine)
                controller.reanchorReviewComment(comment.id, relocated, "fix this")
                assertFalse(controller.state.value.reviewComments.single().stale)
                controller.sendReviewComments()
                controller.await { !it.sendingComments && it.reviewComments.isEmpty() }
                val sent = repository.probe("probe/stats", obj()).array("messages").last().jsonObject.text("text")
                assertEquals("code.kt:2\nfix this", sent)
            }
        } finally { root.deleteRecursively() }
    }
    @Test fun `all enabled hosts connect independently and colliding session IDs retain isolated drafts`() = runBlocking {
        exercise { controller, a, b, _ ->
            controller.await { it.connections.size == 2 && it.connections.values.all { status -> status.connected } && it.hostProjects.size == 2 }
            for (machine in listOf(a, b)) {
                controller.selectHostProject(controller.state.value.hostProjects.first { it.profileId == machine.id })
                controller.newThread()
                controller.await { it.profile?.id == machine.id && it.thread != null && it.threadReady }
                controller.draft("draft-${machine.id}")
            }
            val tabs = controller.state.value.sessionTabs
            assertEquals(2, tabs.size)
            assertEquals(1, tabs.map { it.thread.id }.distinct().size, "The fixture intentionally emits identical IDs on both machines")
            assertEquals(2, tabs.map { it.key }.distinct().size)
            assertEquals(listOf(1, 2), tabs.map { it.number })
            controller.openSession(tabs[0]); assertEquals("draft-a", controller.state.value.draft)
            controller.renameSessionTab(tabs[0].key, "work-a")
            controller.switchSession(1); assertEquals("draft-b", controller.state.value.draft)
            assertEquals("work-a", controller.state.value.sessionTabs[0].label)
            controller.closeConversation(tabs[0].key)
            controller.await { it.sessionTabs.size == 1 }
            assertEquals("b", controller.state.value.sessionTabs.single().profileId)
            assertTrue(controller.state.value.connections.values.all { it.connected })
            assertEquals(1, a.authentications.get()); assertEquals(1, b.authentications.get())
        }
    }
    @Test fun `live token usage survives restart and stays isolated for identical thread IDs on different hosts`() = runBlocking {
        exercise { controller, a, b, vault ->
            controller.await { it.connections.values.count { status -> status.connected } == 2 && it.hostProjects.size == 2 }
            for (id in listOf("a", "b")) {
                controller.selectHostProject(controller.state.value.hostProjects.first { it.profileId == id })
                controller.newThread(); controller.await { it.threadReady && it.thread != null }
                controller.draft("fixture-usage-$id"); controller.send()
                val expected = if (id == "a") 150L else 750L
                controller.await { !it.sending && it.usage[it.thread?.id]?.last == expected }
            }
            val tabs = controller.state.value.sessionTabs
            assertEquals(1, tabs.map { it.thread.id }.distinct().size)
            controller.setConnectionEnabled(a.profile, false); controller.setConnectionEnabled(b.profile, false)
            controller.flushRecovery(); controller.close()
            val recoveredScope = CoroutineScope(currentCoroutineContext() + SupervisorJob())
            val recovered = PocketController(vault, recoveredScope, engines)
            try {
                for (tab in tabs) {
                    recovered.openSession(tab)
                    val expected = if (tab.profileId == "a") 150L else 750L
                    val usage = recovered.state.value.usage[tab.thread.id]
                    assertEquals(expected, usage?.last)
                    assertEquals(expected * 10, usage?.total)
                    assertEquals(if (tab.profileId == "a") 15 else 75, usage?.contextPercent)
                    assertFalse(recovered.state.value.connected)
                }
            } finally { recovered.close(); recoveredScope.cancel() }
        }
    }

    @Test fun `opening saved history loads counters without a new turn and newer live events win`() = runBlocking {
        exercise { controller, _, _, _ ->
            controller.await { it.connections.values.all { status -> status.connected } && it.hostProjects.size == 2 }
            controller.selectHostProject(controller.state.value.hostProjects.first { it.profileId == "a" })
            for ((id, expected) in listOf("fixture-saved-usage" to 150L, "fixture-saved-race" to 900L)) {
                controller.openThread(ThreadInfo(id, "Saved usage", "/fixture/project"))
                val s = controller.await { it.thread?.id == id && id !in it.usageLoading && it.usage[id]?.last == expected }
                assertEquals(expected * 10, s.usage.getValue(id).total)
                assertTrue(s.usageErrors[id].isNullOrBlank())
                assertEquals("", s.draft)
                assertFalse(s.sending)
            }
            controller.openThread(ThreadInfo("fixture-saved-empty", "No usage", "/fixture/project"))
            val empty = controller.await { "fixture-saved-empty" in it.usageErrors && "fixture-saved-empty" !in it.usageLoading }
            assertNull(empty.usage["fixture-saved-empty"])
            assertTrue(empty.usageErrors.getValue("fixture-saved-empty").contains("暂无"))
        }
    }

    @Test fun `equal approval request IDs on different machines are answered on their originating connections`() = runBlocking {
        exercise(requestApproval = true) { controller, a, b, _ ->
            val ready = controller.await { it.connections.values.count { status -> status.connected } == 2 && it.pending.size == 2 }
            assertEquals(1, ready.pending.map { it.id }.distinct().size)
            controller.selectHost("a")
            controller.decide(ready.pending.first { it.profileId == "b" }, true)
            controller.decide(ready.pending.first { it.profileId == "a" }, false)
            controller.await { it.pending.isEmpty() }
            withTimeout(5000) { while (a.approvalLog.toFile().length() == 0L || b.approvalLog.toFile().length() == 0L) delay(20) }
            assertEquals("decline", a.approvalLog.toFile().readText().trim())
            assertEquals("accept", b.approvalLog.toFile().readText().trim())
        }
    }

    private fun draftImage(): DraftImage {
        val bytes = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+j4XcAAAAASUVORK5CYII=")
        val file = Files.createTempFile("pocket-draft-image", ".png").toFile().apply { writeBytes(bytes) }
        return DraftImage(file.name, file.absolutePath, "fixture.png", bytes.size)
    }
    @Test fun `image picker results stay with their originating machine and survive reconnect before image only send`() = runBlocking {
        exercise { controller, a, _, _ ->
            controller.await { it.connections.values.count { status -> status.connected } == 2 && it.hostProjects.size == 2 }
            for (id in listOf("a", "b")) {
                controller.selectHostProject(controller.state.value.hostProjects.first { it.profileId == id })
                controller.newThread(); controller.await { it.profile?.id == id && it.threadReady && it.thread != null }
            }
            val tab = controller.state.value.sessionTabs.first { it.profileId == "a" }
            val image = draftImage(); val expected = File(image.path).readBytes()
            assertEquals(4, controller.beginImageImport("a", tab.number)?.count)
            // B is visible when the asynchronous picker/import on A finishes.
            assertEquals("b", controller.state.value.profile?.id)
            assertTrue(controller.finishImageImport("a", tab.number, listOf(image), null))
            assertTrue(controller.state.value.draftImages.isEmpty())
            controller.openSession(tab)
            assertEquals(image.id, controller.state.value.draftImages.single().id)
            controller.setConnectionEnabled(a.profile, false)
            assertEquals(image.id, controller.state.value.draftImages.single().id)
            assertTrue(File(image.path).exists())
            controller.setConnectionEnabled(a.profile, true)
            controller.await { it.profile?.id == "a" && it.connected && it.threadReady }
            assertEquals(image.id, controller.state.value.draftImages.single().id)
            controller.send()
            controller.await { !it.sending && it.draftImages.isEmpty() }
            controller.refreshThread()
            val history = controller.await { it.items.any { item -> item.kind == "userMessage" && item.images.isNotEmpty() } }
            val source = history.items.first { it.kind == "userMessage" }.images.single().source
            assertTrue(File(source).canonicalPath.startsWith(a.uploadRoot.canonicalPath + File.separator))
            assertContentEquals(expected, File(source).readBytes())
            assertTrue(history.draft.isEmpty())
        }
    }
    @Test fun `process restart restores cross host tab names text and image drafts without connecting disabled hosts`() = runBlocking {
        exercise { controller, a, b, vault ->
            controller.await { it.connections.values.count { status -> status.connected } == 2 && it.hostProjects.size == 2 }
            var image: DraftImage? = null
            for (id in listOf("a", "b")) {
                controller.selectHostProject(controller.state.value.hostProjects.first { it.profileId == id })
                controller.newThread(); controller.await { it.profile?.id == id && it.threadReady && it.thread != null }
                controller.draft("persistent-$id 中文")
                val tab = controller.state.value.sessionTabs.last()
                controller.renameSessionTab(tab.key, "name-$id")
                if (id == "a") {
                    image = draftImage()
                    controller.beginImageImport(id, tab.number)
                    controller.finishImageImport(id, tab.number, listOf(image!!), null)
                }
            }
            controller.setConnectionEnabled(a.profile, false); controller.setConnectionEnabled(b.profile, false)
            controller.flushRecovery()
            val keys = controller.state.value.sessionTabs.map { it.key }
            val attempts = a.authentications.get() + b.authentications.get()
            controller.close()
            val recoveredScope = CoroutineScope(currentCoroutineContext() + SupervisorJob(currentCoroutineContext()[Job]))
            val recovered = PocketController(vault, recoveredScope, engines)
            try {
                assertEquals(keys, recovered.state.value.sessionTabs.map { it.key })
                assertEquals(listOf(1, 2), recovered.state.value.sessionTabs.map { it.number })
                assertEquals(listOf("name-a", "name-b"), recovered.state.value.sessionTabs.map { it.name })
                assertTrue(recovered.state.value.sessionTabs.all { it.hasDraft })
                for (tab in recovered.state.value.sessionTabs) {
                    recovered.openSession(tab)
                    assertEquals("persistent-${tab.profileId} 中文", recovered.state.value.draft)
                    assertEquals(if (tab.profileId == "a") listOf(image!!.id) else emptyList(), recovered.state.value.draftImages.map { it.id })
                    assertFalse(recovered.state.value.threadReady)
                }
                delay(450)
                assertEquals(attempts, a.authentications.get() + b.authentications.get())
                assertFalse(vault.get("workspace:v1")!!.contains("fixture-password"))
            } finally { recovered.close(); recoveredScope.cancel(); image?.let { File(it.path).delete() } }
        }
    }

    @Test fun `send acknowledgement events target the originating tab and failures never blur the composer`() = runBlocking {
        exercise { controller, _, _, _ ->
            controller.await { it.connections.values.count { status -> status.connected } == 2 && it.hostProjects.size == 2 }
            for (id in listOf("a", "b")) {
                controller.selectHostProject(controller.state.value.hostProjects.first { it.profileId == id })
                controller.newThread(); controller.await { it.profile?.id == id && it.threadReady && it.thread != null }
            }
            val events = java.util.concurrent.CopyOnWriteArrayList<SendCompletion>()
            val observer = launch(start = CoroutineStart.UNDISPATCHED) { controller.sendCompleted.collect { events += it } }
            try {
                val tabs = controller.state.value.sessionTabs
                val a = tabs.first { it.profileId == "a" }; val b = tabs.first { it.profileId == "b" }
                controller.openSession(a); controller.draft("accepted message"); controller.send()
                controller.openSession(b); controller.draft("draft on other machine")
                withTimeout(5000) { while (events.isEmpty()) delay(20) }
                assertEquals(listOf(SendCompletion("a", a.thread.id)), events.toList())
                assertEquals("draft on other machine", controller.state.value.draft)
                controller.draft("fixture-reject-images"); controller.send()
                controller.await { !it.sending && it.error?.contains("fixture rejected message") == true }
                assertEquals(1, events.size)
                assertEquals("fixture-reject-images", controller.state.value.draft)
            } finally { observer.cancel() }
        }
    }

    @Test fun `failed image sends preserve attachments and text without leaking into another machine`() = runBlocking {
        exercise { controller, _, _, _ ->
            controller.await { it.connections.values.count { status -> status.connected } == 2 && it.hostProjects.size == 2 }
            controller.selectHostProject(controller.state.value.hostProjects.first { it.profileId == "a" })
            controller.newThread(); controller.await { it.profile?.id == "a" && it.threadReady && it.thread != null }
            val tab = controller.state.value.sessionTabs.single()
            val image = draftImage()
            assertNotNull(controller.beginImageImport("a", tab.number))
            assertTrue(controller.finishImageImport("a", tab.number, listOf(image), null))
            controller.draft("fixture-reject-images"); controller.send()
            controller.selectHost("b")
            controller.await { it.connections["a"]?.error?.contains("fixture rejected message") == true }
            assertTrue(controller.state.value.draftImages.isEmpty())
            controller.openSession(tab)
            assertEquals("fixture-reject-images", controller.state.value.draft)
            assertEquals(image.id, controller.state.value.draftImages.single().id)
            assertTrue(File(image.path).exists())
            controller.removeDraftImage("a", tab.number, image.id)
            assertTrue(controller.state.value.draftImages.isEmpty())
        }
    }

    @Test fun `message sends and history reads go to the selected machine despite identical thread IDs`() = runBlocking {
        exercise { controller, _, _, _ ->
            controller.await { it.connections.values.count { status -> status.connected } == 2 && it.hostProjects.size == 2 }
            for (id in listOf("a", "b")) {
                controller.selectHostProject(controller.state.value.hostProjects.first { it.profileId == id })
                controller.newThread()
                controller.await { it.profile?.id == id && it.threadReady && it.thread != null }
                controller.draft("message-$id")
                controller.send()
                controller.await { it.profile?.id == id && !it.sending && it.draft.isEmpty() }
            }
            val tabs = controller.state.value.sessionTabs
            for (tab in tabs) {
                controller.openSession(tab)
                controller.refreshThread()
                val state = controller.await { it.profile?.id == tab.profileId && it.items.any { item -> item.kind == "userMessage" } }
                assertEquals("message-${tab.profileId}", state.items.first { it.kind == "userMessage" }.text)
                assertFalse(state.items.any { it.text == "message-${if (tab.profileId == "a") "b" else "a"}" })
            }
        }
    }
    @Test fun `enabled unavailable host retries from initial failure while healthy host connects`() = runBlocking {
        exercise(startAOffline = true) { controller, a, b, _ ->
            controller.await { it.connections["b"]?.connected == true && it.connections["a"]?.reconnecting == true }
            assertEquals(1, b.authentications.get())
            a.server.start()
            controller.await { it.connections.values.count { status -> status.connected } == 2 }
            assertEquals(1, b.authentications.get())
        }
    }

    @Test fun `connection switch disconnects only its host and cancels retries while other host stays live`() = runBlocking {
        exercise { controller, a, b, vault ->
            controller.await { it.connections.values.count { status -> status.connected } == 2 }
            assertTrue(controller.setConnectionEnabled(a.profile, false))
            controller.await { it.connections["a"]?.connected == false && it.connections["b"]?.connected == true }
            assertFalse(vault.profiles().first { it.id == "a" }.autoConnect)
            delay(1200)
            assertEquals(1, a.authentications.get()); assertEquals(1, b.authentications.get())
            controller.selectHostProject(controller.state.value.hostProjects.first { it.profileId == "b" })
            controller.newThread()
            controller.await { it.profile?.id == "b" && it.threadReady && it.thread != null }
            assertTrue(controller.setConnectionEnabled(a.profile, true))
            controller.await { it.connections.values.all { status -> status.connected } }
            a.interrupt()
            controller.await { it.connections["a"]?.reconnecting == true }
            assertTrue(controller.state.value.connections.getValue("b").connected)
            controller.setConnectionEnabled(a.profile, false)
            val attempts = a.authentications.get()
            delay(1800)
            assertEquals(attempts, a.authentications.get(), "A cancelled retry must not reconnect a disabled machine")
            assertEquals(1, b.authentications.get())
            assertFalse(controller.state.value.connections.getValue("a").connected)
            assertFalse(controller.state.value.connections.getValue("a").reconnecting)
        }
    }
    @Test fun `populated and empty tabs keep their content during offline switching and reconnect`() = runBlocking {
        exercise { controller, a, _, _ ->
            controller.await { it.hostProjects.any { p -> p.profileId == "a" } }
            controller.selectHostProject(controller.state.value.hostProjects.first { it.profileId == "a" })
            controller.openThread(ThreadInfo("fixture-saved-full-tab", "Populated", "/fixture/project"))
            controller.await { it.threadReady && it.items.any { item -> item.text == "saved history" } }
            val full = controller.state.value.sessionTabs.single()
            controller.draft("full draft")
            controller.openThread(ThreadInfo("fixture-saved-empty-tab", "Empty", "/fixture/project"))
            controller.await { it.thread?.id == "fixture-saved-empty-tab" && it.threadReady && !it.attaching }
            val empty = controller.state.value.sessionTabs.last()
            controller.draft("empty draft")
            a.interrupt()
            controller.await { !it.connected && it.reconnecting }
            repeat(25) {
                controller.openSession(full)
                assertEquals(full.thread.id, controller.state.value.thread?.id)
                assertTrue(controller.state.value.items.any { item -> item.text == "saved history" })
                assertEquals("full draft", controller.state.value.draft)
                controller.openSession(empty)
                assertEquals(empty.thread.id, controller.state.value.thread?.id)
                assertTrue(controller.state.value.items.isEmpty())
                assertEquals("empty draft", controller.state.value.draft)
                delay(20)
            }
            controller.await { it.connected && it.threadReady && it.thread?.id == empty.thread.id }
            assertTrue(controller.state.value.items.isEmpty())
            controller.openSession(full)
            controller.await { it.thread?.id == full.thread.id && it.threadReady }
            assertTrue(controller.state.value.items.any { it.text == "saved history" })
            assertEquals("full draft", controller.state.value.draft)
        }
    }

    @Test fun `transport loss retries the affected host without closing another host`() = runBlocking {
        exercise { controller, a, b, _ ->
            controller.await { it.connections.values.count { status -> status.connected } == 2 }
            a.interrupt()
            controller.await { it.connections["a"]?.reconnecting == true }
            controller.await { it.connections["a"]?.connected == true }
            assertTrue(a.authentications.get() >= 2)
            assertEquals(1, b.authentications.get())
            assertTrue(controller.state.value.connections.getValue("b").connected)
        }
    }
}
