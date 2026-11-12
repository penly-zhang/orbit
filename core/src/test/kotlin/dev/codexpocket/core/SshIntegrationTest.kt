package dev.codexpocket.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.junit.Before
import org.junit.After
import org.junit.Assume
import org.junit.Test
import java.io.*
import java.nio.file.Files
import java.util.Base64
import java.security.MessageDigest
import java.net.ServerSocket
import java.net.Socket
import java.net.InetAddress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import kotlin.test.*

private suspend fun connectCodex(profile: HostProfile, credentials: Credentials): SshConnection =
    SshConnection.connect(profile, credentials, proxyCommand(profile.codexPath))

class SshIntegrationTest {
    private lateinit var server: SshServer
    private lateinit var profile: HostProfile
    private val secrets = Credentials(password = "fixture-password")
    @Before fun setup() {
        System.setProperty("pocket.upload.root", Files.createTempDirectory("pocket-core-images").toString())
        server = SshServer.setUpDefaultServer()
        server.host = "127.0.0.1"; server.port = 0
        server.keyPairProvider = SimpleGeneratorHostKeyProvider(Files.createTempDirectory("pocket-ssh-test").resolve("hostkey"))
        server.passwordAuthenticator = org.apache.sshd.server.auth.password.PasswordAuthenticator { user, password, _ -> user == "fixture" && password == secrets.password }
        server.publickeyAuthenticator = org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator { user, _, _ -> user == "fixture" }
        server.commandFactory = org.apache.sshd.server.command.CommandFactory { _, command -> ProcessCommand(command) }
        server.start()
        profile = HostProfile(host = "127.0.0.1", port = server.port, username = "fixture")
    }
    @After fun teardown() { server.stop(true); File(System.getProperty("pocket.upload.root")).deleteRecursively() }

    private suspend fun trusted(): SshConnection {
        val untrusted = assertFailsWith<HostKeyRequired> { connectCodex(profile, secrets) }
        assertFalse(untrusted.identity.changed)
        profile = profile.copy(fingerprint = untrusted.identity.fingerprint)
        return connectCodex(profile, secrets)
    }

    @Test fun `shared traex daemon keeps both clients attached across disconnect`() = runBlocking<Unit> {
        val binary = System.getenv("ORBIT_TRAEX_BINARY")
        Assume.assumeTrue("Set ORBIT_TRAEX_BINARY for the isolated local multi-client probe", binary != null)
        val root = Files.createTempDirectory("orbit-traex-shared").toFile()
        val home = root.resolve("home").apply { mkdirs() }
        val work = root.resolve("work").apply { mkdirs() }
        home.resolve("traecli.toml").writeText("""
            model_provider = "probe"
            model = "probe"
            [model_providers.probe]
            name = "Offline protocol probe"
            base_url = "http://127.0.0.1:1/v1"
            wire_api = "responses"
            requires_openai_auth = false
        """.trimIndent())
        val environment = "export TRAE_HOME=${shellQuote(home.path)}; agent_bin=${shellQuote(binary!!)}; "
        var first: RpcClient? = null
        var second: RpcClient? = null
        try {
            val command = "fixture-route:" + environment + agentProxyInvocation("traex")
            val untrusted = assertFailsWith<HostKeyRequired> { SshConnection.connect(profile, secrets, command) }
            val pinned = profile.copy(fingerprint = untrusted.identity.fingerprint)
            val sshA = SshConnection.connect(pinned, secrets, command)
            val rpcA = RpcClient(sshA, this).also { first = it; it.initialize() }
            val repositoryA = CodexRepository(rpcA, sshA)
            val thread = repositoryA.newThread(ProjectInfo("cwd:${work.path}", "Probe", listOf(work.path)), work.path)
            rpcA.request("thread/inject_items", obj("threadId" to thread.id, "items" to listOf(
                obj("type" to "message", "role" to "user", "content" to listOf(obj("type" to "input_text", "text" to "Local shared-session protocol probe"))))))
            // The second connection repeats daemon start: it must reuse the first client's service.
            val sshB = SshConnection.connect(pinned, secrets, command)
            val rpcB = RpcClient(sshB, this).also { second = it; it.initialize() }
            val repositoryB = CodexRepository(rpcB, sshB)
            repositoryB.attachThread(thread.id)
            val renamed = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(10000) { repositoryA.events.first { it is AgentEvent.Renamed && it.threadId == thread.id && it.title == "Shared probe" } }
            }
            repositoryB.renameThread(thread.id, "Shared probe")
            renamed.await()
            assertEquals("Shared probe", rpcA.request("thread/read", obj("threadId" to thread.id, "includeTurns" to false)).objectAt("thread").text("name"))
            repositoryA.close(); first = null
            repositoryB.renameThread(thread.id, "After disconnect")
            assertEquals("After disconnect", rpcB.request("thread/read", obj("threadId" to thread.id, "includeTurns" to false)).objectAt("thread").text("name"))
        } finally {
            first?.close(); second?.close()
            val stop = ProcessBuilder("sh", "-c", environment + "exec \"\$agent_bin\" app-server daemon stop").redirectErrorStream(true).start()
            if (!stop.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)) stop.destroyForcibly()
            root.deleteRecursively()
        }
    }

    @Test fun `compatible engine uses normalized events and shared host services`() = runBlocking<Unit> {
        val untrusted = assertFailsWith<HostKeyRequired> { connectCodex(profile, secrets) }
        val compatible = profile.copy(fingerprint = untrusted.identity.fingerprint,
            engineId = "traex")
        val repository: AgentRepository = AgentEngines.default.open(compatible, secrets, this)
        try {
            val seen = mutableListOf<AgentEvent>()
            val collector = launch(start = CoroutineStart.UNDISPATCHED) { repository.events.collect { seen += it } }
            try {
                repository.initialize()
                assertTrue(repository.models().isNotEmpty())
                assertEquals("日本語と中文", repository.file("/fixture/project/README.md").bytes.toString(Charsets.UTF_8))
                val project = repository.projects().first()
                val thread = repository.forEngine("traex").newThread(project, project.roots.first())
                repository.send(thread.id, "fixture-queue-normalized", null)
                withTimeout(5000) { while (seen.none { it is AgentEvent.Timeline && it.update is TimelineUpdate.Started }) delay(10) }
                assertTrue(seen.any { it is AgentEvent.Timeline && it.threadId == thread.id })
            } finally { collector.cancelAndJoin() }
        } finally { repository.close() }
    }

    @Test fun `an installed but unavailable agent does not block the healthy agent`() = runBlocking<Unit> {
        val untrusted = assertFailsWith<HostKeyRequired> { connectCodex(profile, secrets) }
        val trustedProfile = profile.copy(fingerprint = untrusted.identity.fingerprint)
        server.commandFactory = org.apache.sshd.server.command.CommandFactory { _, command ->
            ProcessCommand(if (command.contains("app-server proxy") && command.contains("command -v traex")) "fixture-unavailable-agent" else command)
        }
        val repository = AgentEngines.default.open(trustedProfile, secrets, this)
        try {
            repository.initialize()
            assertEquals(listOf("codex"), repository.availableEngines)
            assertTrue(repository.engineIssues.containsKey("traex"))
            assertTrue(repository.models().isNotEmpty())
            assertFailsWith<IllegalStateException> { repository.forEngine("traex") }
        } finally { repository.close() }
    }

    @Test fun `adapter translates approval events and replies without exposing RPC to the workspace`() = runBlocking<Unit> {
        val ssh = trusted()
        val rpc = RpcClient(ssh, this)
        val repository: AgentRepository = CodexRepository(rpc, ssh)
        try {
            val approval = async(start = CoroutineStart.UNDISPATCHED) { repository.events.first { it is AgentEvent.Request } as AgentEvent.Request }
            repository.initialize()
            val probe = async { rpc.request("probe/approval") }
            val request = withTimeout(5000) { approval.await() }
            assertEquals(RequestKind.COMMAND, request.kind)
            assertEquals("test command", request.params.text("command"))
            repository.decide(request.id, true)
            assertEquals("accept", probe.await().text("decision"))
            assertFailsWith<IllegalStateException> { repository.decide(request.id, true) }
        } finally { repository.close() }
    }

    private fun gateway(): SshServer = SshServer.setUpDefaultServer().apply {
        host = "127.0.0.1"; port = 0
        keyPairProvider = SimpleGeneratorHostKeyProvider(Files.createTempDirectory("pocket-gateway").resolve("key"))
        passwordAuthenticator = org.apache.sshd.server.auth.password.PasswordAuthenticator { user, password, _ -> user == "jump" && password == "jump-secret" }
        forwardingFilter = org.apache.sshd.server.forward.AcceptAllForwardingFilter.INSTANCE
        start()
    }
    @Test fun `ProxyJump through SOCKS verifies every host and executes RPC and files on target`() = runBlocking<Unit> {
        val gateway = gateway()
        try {
            SocksRelay(gateway.port).use { proxy ->
                profile = profile.copy(jumpHosts = listOf(JumpHost("entry", "bastion.invalid", gateway.port, "jump")), proxyEnabled = true, proxyHost = "127.0.0.1", proxyPort = proxy.port)
                val credentials = secrets.copy(jumps = mapOf("entry" to JumpCredentials(password = "jump-secret")))
                val entry = assertFailsWith<HostKeyRequired> { connectCodex(profile, credentials) }.identity
                assertEquals("entry", entry.hopId)
                profile = profile.copy(jumpHosts = profile.jumpHosts.map { it.copy(fingerprint = entry.fingerprint) })
                val destination = assertFailsWith<HostKeyRequired> { connectCodex(profile, credentials) }.identity
                assertNull(destination.hopId)
                assertNotEquals(entry.fingerprint, destination.fingerprint)
                profile = profile.copy(fingerprint = destination.fingerprint)
                val ssh = connectCodex(profile, credentials); val rpc = RpcClient(ssh, this)
                try {
                    assertEquals("fixture", rpc.initialize().text("userAgent"))
                    val imageBytes = byteArrayOf(0, 13, 10, -1, 42)
                    val imagePath = ssh.uploadImage(imageBytes)
                    assertContentEquals(imageBytes, File(imagePath).readBytes())
                    assertEquals("21", ssh.execute("wc -c < '/fixture/project/README.md'").stdout)
                    assertEquals("日本語と中文", CodexRepository(rpc, ssh).file("/fixture/project/README.md").bytes.toString(Charsets.UTF_8))
                } finally { rpc.close() }
                val wrong = profile.copy(jumpHosts = profile.jumpHosts.map { it.copy(fingerprint = "SHA256:wrong") })
                assertTrue(assertFailsWith<HostKeyRequired> { connectCodex(wrong, credentials) }.identity.changed)
                assertTrue(proxy.destinations.all { it == "bastion.invalid:${gateway.port}" })
            }
        } finally { gateway.stop(true) }
    }
    @Test fun `remote SSH alias and automatic login jump carry clean websocket and final target commands`() = runBlocking<Unit> {
        Assume.assumeTrue(File("/usr/bin/ssh").canExecute() && File("/usr/bin/ssh-keygen").canExecute())
        val dir = Files.createTempDirectory("pocket-route")
        val privateKey = dir.resolve("key")
        assertEquals(0, ProcessBuilder("/usr/bin/ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-f", privateKey.toString()).start().waitFor())
        val known = dir.resolve("known_hosts")
        val publicKey = org.apache.sshd.common.config.keys.PublicKeyEntry.toString(server.keyPairProvider.loadKeys(null).first().public)
        Files.writeString(known, "[127.0.0.1]:${server.port} $publicKey\n")
        val config = dir.resolve("config")
        Files.writeString(config, "Host destination\n HostName 127.0.0.1\n Port ${server.port}\n User fixture\n IdentityFile $privateKey\n UserKnownHostsFile $known\n StrictHostKeyChecking yes\n")
        val codex = dir.resolve("codex")
        Files.writeString(codex, "#!/bin/sh\nexec python3 " + shellQuote(File(System.getProperty("pocket.root"), "tools/mock_codex.py").absolutePath) + "\n")
        codex.toFile().setExecutable(true)
        server.shellFactory = org.apache.sshd.server.shell.ShellFactory { ProcessCommand("fixture-shell") }
        val gateway = gateway()
        gateway.commandFactory = org.apache.sshd.server.command.CommandFactory { _, command ->
            val routed = if (command.contains("SHELL:-/bin/sh")) "printf 'Welcome to A\\n'; exec /usr/bin/ssh -F ${shellQuote(config.toString())} destination"
                else command.replaceFirst("exec ssh ", "exec /usr/bin/ssh -F ${shellQuote(config.toString())} ")
            ProcessCommand("fixture-route:" + routed)
        }
        try {
            for (mode in listOf("remoteSsh", "loginShell")) {
                var routed = HostProfile(host = "127.0.0.1", port = gateway.port, username = "jump", routeMode = mode, remoteTarget = "destination", codexPath = codex.toString())
                val credentials = Credentials(password = "jump-secret")
                val identity = assertFailsWith<HostKeyRequired> { connectCodex(routed, credentials) }.identity
                routed = routed.copy(fingerprint = identity.fingerprint)
                val ssh = connectCodex(routed, credentials); val rpc = RpcClient(ssh, this)
                try {
                    assertEquals("fixture", rpc.initialize().text("userAgent"))
                    val message = "中文 🛰️".repeat(10000)
                    assertEquals(message, rpc.request("probe/echo", obj("text" to message)).text("text"))
                    val result = ssh.execute(if (mode == "loginShell") "printf final-target; exit 7" else "wc -c < '/fixture/project/README.md'")
                    assertEquals(if (mode == "loginShell") "final-target" else "21", result.stdout)
                    if (mode == "loginShell") assertEquals(7, result.code)
                    verifyWebTunnel(ssh)
                } finally { rpc.close() }
            }
        } finally { gateway.stop(true) }
    }

    @Test fun `cancelling an in-progress SSH handshake closes its socket before the connection timeout`() = runBlocking<Unit> {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { listener ->
            val accepted = async(Dispatchers.IO) { listener.accept() }
            val attempt = async { connectCodex(profile.copy(port = listener.localPort), secrets) }
            val socket = withTimeout(3000) { accepted.await() }
            socket.use {
                attempt.cancel()
                withTimeout(2000) { attempt.join() }
                socket.soTimeout = 1500
                val input = socket.getInputStream()
                while (input.read() != -1) { }
                assertTrue(attempt.isCancelled)
            }
        }
    }

    @Test fun `SOCKS5 resolves destination remotely and preserves host trust and RPC`() = runBlocking<Unit> {
        SocksRelay(server.port).use { proxy ->
            profile = profile.copy(host = "mac.tailnet.invalid", proxyEnabled = true, proxyHost = "127.0.0.1", proxyPort = proxy.port)
            val unknown = assertFailsWith<HostKeyRequired> { connectCodex(profile, secrets) }
            profile = profile.copy(fingerprint = "SHA256:wrong-key")
            assertTrue(assertFailsWith<HostKeyRequired> { connectCodex(profile, secrets) }.identity.changed)
            profile = profile.copy(fingerprint = unknown.identity.fingerprint)
            val rpc = RpcClient(connectCodex(profile, secrets), this)
            try {
                assertEquals("fixture", rpc.initialize().text("userAgent"))
                assertEquals("代理连接", rpc.request("probe/echo", obj("text" to "代理连接")).text("text"))
            } finally { rpc.close() }
            assertEquals(3, proxy.destinations.size)
            assertTrue(proxy.destinations.all { it == "mac.tailnet.invalid:${server.port}" })
        }
    }

    @Test fun `SOCKS5 rejection never falls back to a reachable direct SSH host`() = runBlocking<Unit> {
        SocksRelay(server.port, reject = true).use { proxy ->
            profile = profile.copy(proxyEnabled = true, proxyHost = "127.0.0.1", proxyPort = proxy.port)
            val failure = assertFailsWith<IOException> { connectCodex(profile, secrets) }
            assertTrue(failure.message.orEmpty().contains("SOCKS5"))
            assertEquals(1, proxy.destinations.size)
        }
    }

    private class SocksRelay(private val targetPort: Int, private val reject: Boolean = false) : Closeable {
        private val listener = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = listener.localPort
        val destinations = ConcurrentLinkedQueue<String>()
        private val sockets = ConcurrentLinkedQueue<Socket>()
        private val workers = Executors.newCachedThreadPool { task -> Thread(task, "fixture-socks").apply { isDaemon = true } }
        init {
            workers.submit {
                while (!listener.isClosed) {
                    val client = try { listener.accept() } catch (_: IOException) { break }
                    sockets.add(client)
                    workers.submit {
                        client.use {
                            client.soTimeout = 10000
                            val input = DataInputStream(client.getInputStream())
                            val output = client.getOutputStream()
                            check(input.readUnsignedByte() == 5)
                            val methods = ByteArray(input.readUnsignedByte()).also(input::readFully)
                            check(methods.contains(0.toByte()))
                            output.write(byteArrayOf(5, 0)); output.flush()
                            check(input.readUnsignedByte() == 5 && input.readUnsignedByte() == 1)
                            input.readUnsignedByte()
                            val host = when (val type = input.readUnsignedByte()) {
                                3 -> ByteArray(input.readUnsignedByte()).also(input::readFully).toString(Charsets.UTF_8)
                                1 -> InetAddress.getByAddress(ByteArray(4).also(input::readFully)).hostAddress
                                4 -> InetAddress.getByAddress(ByteArray(16).also(input::readFully)).hostAddress
                                else -> error("Unexpected address type $type")
                            }
                            destinations.add("$host:${input.readUnsignedShort()}")
                            if (reject) {
                                output.write(byteArrayOf(5, 5, 0, 1, 127, 0, 0, 1, 0, 0)); output.flush()
                            } else {
                                Socket("127.0.0.1", targetPort).use { upstream ->
                                    sockets.add(upstream)
                                    output.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 0)); output.flush()
                                    workers.submit { runCatching { upstream.getInputStream().copyTo(output) }; runCatching { client.close() } }
                                    runCatching { input.copyTo(upstream.getOutputStream()) }
                                }
                            }
                        }
                    }
                }
            }
        }
        override fun close() {
            listener.close()
            sockets.forEach { runCatching { it.close() } }
            workers.shutdownNow()
        }
    }

    @Test fun `SSH host key must be explicitly pinned and changed keys are rejected`() = runBlocking<Unit> {
        val unknown = assertFailsWith<HostKeyRequired> { connectCodex(profile, secrets) }
        assertTrue(unknown.identity.fingerprint.startsWith("SHA256:"))
        profile = profile.copy(fingerprint = "SHA256:wrong-key")
        val changed = assertFailsWith<HostKeyRequired> { connectCodex(profile, secrets) }
        assertTrue(changed.identity.changed)
    }

    @Test fun `native project discovery keeps historical cwd groups and their conversations`() = runBlocking<Unit> {
        val ssh = trusted(); val rpc = RpcClient(ssh, this)
        try {
            rpc.initialize(); val repo = CodexRepository(rpc, ssh)
            val native = repo.projects().single()
            val assigned = repo.newThread(native, native.roots.single())
            repo.send(assigned.id, "native project task", null)
            val paths = listOf("/legacy/Projects", "/legacy/user", "/legacy/notes/ai_infra")
            val originals = paths.associateWith { cwd ->
                val group = ProjectInfo("cwd:$cwd", cwd.substringAfterLast('/'), listOf(cwd))
                val thread = repo.newThread(group, cwd)
                repo.send(thread.id, "legacy task at $cwd", null)
                thread
            }
            val projects = repo.projects()
            assertEquals(4, projects.size)
            assertEquals(native, projects.first())
            assertEquals(paths.map { "cwd:$it" }.toSet(), projects.drop(1).map { it.id }.toSet())
            val discovery = rpc.request("probe/stats").objectAt("lastThreadListParams")
            assertEquals(100, discovery["limit"]?.jsonPrimitive?.int)
            assertTrue(discovery["useStateDbOnly"]?.jsonPrimitive?.boolean == true)
            val legacy = projects.first { it.id == "cwd:${paths.first()}" }
            repo.threads(legacy)
            val query = rpc.request("probe/stats").objectAt("lastThreadListParams")
            assertEquals(paths.first(), query.array("cwd").single().jsonPrimitive.content)
            assertFalse("projectId" in query)
            originals.forEach { (cwd, thread) ->
                assertTrue(repo.history(thread.id).items.any { it.kind == "userMessage" && it.text == "legacy task at $cwd" })
            }
        } finally { rpc.close() }
    }

    @Test fun `repository groups Git subdirectory conversations and queries all member cwd values`() = runBlocking<Unit> {
        val root = Files.createTempDirectory("orbit-project-group").toRealPath().toFile()
        val process = ProcessBuilder("git", "init", root.path).redirectErrorStream(true).start()
        process.inputStream.readBytes()
        assertEquals(0, process.waitFor())
        val ssh = trusted(); val rpc = RpcClient(ssh, this)
        try {
            rpc.initialize()
            val repo = CodexRepository(rpc, ssh)
            val cwds = listOf("app", "core").map { root.resolve(it).apply { mkdirs() }.path }
            val threads = cwds.map { cwd ->
                val group = ProjectInfo("cwd:$cwd", "child", listOf(cwd))
                repo.newThread(group, cwd).also { repo.send(it.id, "fixture task", null) }
            }
            val project = repo.projects().single { it.id == "cwd:${root.path}" }
            assertEquals(listOf(root.path), project.roots)
            assertEquals(setOf(root.path) + cwds, project.threadCwds.toSet())
            val loaded = repo.threads(project).first
            assertEquals(threads.map { it.id }.toSet(), loaded.map { it.id }.toSet())
            assertEquals(cwds.toSet(), loaded.map { it.cwd }.toSet())
            threads.forEach { repo.archiveThread(it.id) }
            val rediscovered = repo.projects(listOf(project)).single { it.id == project.id }
            assertTrue(repo.threads(rediscovered).first.isEmpty())
            assertEquals(threads.map { it.id }.toSet(), repo.threads(rediscovered, archived = true).first.map { it.id }.toSet())
        } finally { rpc.close(); root.deleteRecursively() }
    }

    @Test fun `SSH exec carries websocket RPC unicode fragmented large messages and approval replies`() = runBlocking<Unit> {
        val ssh = trusted()
        val rpc = RpcClient(ssh, this)
        try {
            assertEquals("fixture", rpc.initialize().text("userAgent"))
            val text = "中文与 emoji 🛰️".repeat(12000)
            assertEquals(text, rpc.request("probe/echo", obj("text" to text)).text("text"))
            val request = async(start = CoroutineStart.UNDISPATCHED) { rpc.events.first { it.text("method") == "item/commandExecution/requestApproval" } }
            val pending = async { rpc.request("probe/approval") }
            val approval = withTimeout(5000) { request.await() }
            assertEquals("approval-string-id", approval["id"]?.jsonPrimitive?.content)
            rpc.answer(approval["id"]!!, obj("decision" to "decline"))
            assertEquals("decline", pending.await().text("decision"))
            val repo = CodexRepository(rpc, ssh)
            assertEquals("/fixture/project", repo.projects().single().roots.single())
            assertEquals("README.md", repo.files("/fixture/project").single().name)
            assertEquals("日本語と中文", repo.file("/fixture/project/README.md").bytes.toString(Charsets.UTF_8))
            val image = repo.image("image.png", "/fixture/project")
            assertContentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10), image.take(8).toByteArray())
            assertFailsWith<IllegalArgumentException> { repo.image("/fixture/oversized.png", "/fixture/project") }
        } finally { rpc.close() }
    }

    @Test fun `encrypted OpenSSH ed25519 private keys can authenticate`() = runBlocking<Unit> {
        Assume.assumeTrue(File("/usr/bin/ssh-keygen").canExecute())
        val key = Files.createTempDirectory("pocket-key-test").resolve("id_ed25519")
        val generator = ProcessBuilder("/usr/bin/ssh-keygen", "-q", "-t", "ed25519", "-N", "fixture-passphrase", "-f", key.toString()).start()
        assertEquals(0, generator.waitFor())
        val credentials = Credentials(privateKey = Files.readString(key), passphrase = "fixture-passphrase")
        profile = profile.copy(authType = "key")
        val unknown = assertFailsWith<HostKeyRequired> { connectCodex(profile, credentials) }
        profile = profile.copy(fingerprint = unknown.identity.fingerprint)
        val ssh = connectCodex(profile, credentials)
        val rpc = RpcClient(ssh, this)
        try { assertEquals("fixture", rpc.initialize().text("userAgent")) } finally { rpc.close() }
    }

    @Test fun `git changes through SSH preserve staged unstaged and renamed files`() = runBlocking<Unit> {
        val root = Files.createTempDirectory("pocket git ' 中文 ")
        fun git(vararg args: String) {
            val process = ProcessBuilder(listOf("git", "-C", root.toString()) + args).start()
            val error = process.errorStream.bufferedReader().readText()
            assertEquals(0, process.waitFor(), error)
        }
        git("init", "-q")
        val sample = root.resolve("sample.txt")
        Files.writeString(sample, "base\nshared\ncontent\n")
        git("add", "sample.txt")
        git("-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "commit", "-qm", "baseline")
        Files.writeString(sample, "two\nshared\ncontent\n")
        git("add", "sample.txt")
        Files.writeString(sample, "three\nshared\ncontent\n")
        val ssh = trusted()
        val rpc = RpcClient(ssh, this)
        try {
            rpc.initialize()
            val repo = CodexRepository(rpc, ssh)
            val change = repo.changes(root.toString()).single()
            assertEquals("MM", change.status)
            val diff = repo.diff(root.toString(), change)
            assertTrue(diff.contains("已暂存\n"))
            assertTrue(diff.contains("工作区\n"))
            assertTrue(diff.contains("+two"))
            assertTrue(diff.contains("+three"))
            val overview = repo.reviewOverview(root.toString())
            val fingerprint = overview.changes.single().fingerprint
            assertEquals("MM", overview.changes.single().status)
            val comment = ReviewComment(root = root.toString(), path = "sample.txt", section = "工作区", newLine = 1, body = "fix", fingerprint = fingerprint)
            assertTrue(repo.validateReviewComments(listOf(comment)).isEmpty())
            val baseline = repo.beginReviewBaseline(root.toString())
            Files.writeString(sample, "four\nshared\ncontent\n")
            assertEquals(listOf(comment), repo.validateReviewComments(listOf(comment)))
            val task = repo.taskReview(baseline, finish = true)
            assertEquals(1, task.changes.single().added)
            assertEquals(1, task.changes.single().deleted)
            assertContains(task.diffs.getValue("sample.txt"), "+four")
            assertEquals(task, repo.savedTaskReview(root.toString(), baseline.token))
            Files.writeString(sample, "three\nshared\ncontent\n")
            git("mv", "sample.txt", "新 文件.txt")
            val rename = repo.changes(root.toString()).single()
            assertEquals("新 文件.txt", rename.path)
            assertEquals("sample.txt", rename.oldPath)
            val nested = Files.createDirectory(root.resolve("nested"))
            val outside = repo.changes(nested.toString()).single()
            assertEquals("../新 文件.txt", outside.path)
            assertEquals("../sample.txt", outside.oldPath)
            assertTrue(repo.diff(nested.toString(), outside).contains("+three"))
            val nestedOverview = repo.reviewOverview(nested.toString())
            assertEquals("../新 文件.txt", nestedOverview.changes.single().path)
            val nestedBaseline = repo.beginReviewBaseline(nested.toString())
            Files.writeString(root.resolve("新 文件.txt"), "four\nshared\ncontent\n")
            val nestedTask = repo.taskReview(nestedBaseline, finish = true)
            assertEquals("../新 文件.txt", nestedTask.changes.single().path)
        } finally { rpc.close() }
    }

    @Test fun `new conversation accepts its first message without reading unmaterialized history`() = runBlocking<Unit> {
        val ssh = trusted()
        val rpc = RpcClient(ssh, this)
        try {
            rpc.initialize()
            val repo = CodexRepository(rpc, ssh)
            val project = repo.projects().single()
            val thread = repo.newThread(project, project.roots.single())
            assertTrue(repo.history(thread.id).items.isEmpty())
            repo.attachThread(thread.id)
            val stats = rpc.request("probe/stats")
            assertEquals("0", stats.text("historyBeforeFirst"))
            assertEquals("0", stats.text("resumeBeforeFirst"))
            assertEquals(thread.id, repo.threads(project).first.single().id)
            assertTrue(repo.send(thread.id, "第一条消息", null).isNotBlank())
            val page = repo.history(thread.id)
            assertEquals("第一条消息", page.items.first().text)
            assertEquals("final_answer", page.items.last().phase)
            assertEquals(thread.id, repo.threads(project).first.single().id)
            val reconnect = CodexRepository(rpc, ssh)
            coroutineScope { List(8) { async { reconnect.attachThread(thread.id) } }.awaitAll() }
            assertEquals("1", rpc.request("probe/stats").text("resumeCount"))
            reconnect.forgetSubscription(thread.id)
            reconnect.attachThread(thread.id)
            assertEquals("2", rpc.request("probe/stats").text("resumeCount"))
        } finally { rpc.close() }
    }

    @Test fun `writer conflict preserves ownership and remains retryable`() = runBlocking<Unit> {
        val ssh = trusted(); val rpc = RpcClient(ssh, this)
        try {
            rpc.initialize()
            val repo = CodexRepository(rpc, ssh)
            repeat(2) {
                val error = assertFailsWith<ThreadInUseException> { repo.attachThread("fixture-writer-conflict") }
                assertTrue(error.cause is RpcError)
                assertTrue(error.message.orEmpty().contains("共享后台"))
                assertNull(repo.settings("fixture-writer-conflict"))
            }
        } finally { rpc.close() }
    }

    @Test fun `saved usage is read through its own SSH host without sending a turn`() = runBlocking<Unit> {
        val ssh = trusted(); val rpc = RpcClient(ssh, this)
        try {
            rpc.initialize(); val repo = CodexRepository(rpc, ssh)
            val usage = repo.savedTokenUsage("fixture-saved-usage")
            assertEquals(TokenUsage(150, 1500, 1000), usage)
            assertNull(repo.savedTokenUsage("fixture-saved-empty"))
            assertFailsWith<IllegalStateException> { repo.savedTokenUsage("fixture-saved-mismatch") }
            assertEquals("0", rpc.request("probe/stats").text("firstMessages"))
        } finally { rpc.close() }
    }

    @Test fun `HTML previews read scoped host assets but reject child symlinks`() = runBlocking<Unit> {
        val ssh = trusted(); val rpc = RpcClient(ssh, this)
        try {
            rpc.initialize(); val repo = CodexRepository(rpc, ssh)
            val root = java.nio.file.Path.of(System.getProperty("pocket.upload.root")).resolve("page")
            Files.createDirectories(root.resolve("assets"))
            Files.writeString(root.resolve("assets/style.css"), "body { color: green; }")
            val outside = root.parent.resolve("private.css"); Files.writeString(outside, "private")
            Files.createSymbolicLink(root.resolve("escape.css"), outside)
            val file = repo.artifactResource(root.toString(), "/assets/style.css")
            assertEquals("body { color: green; }", file.bytes.toString(Charsets.UTF_8))
            assertFails { repo.artifactResource(root.toString(), "/escape.css") }
            assertFails { repo.artifactResource(root.toString(), "/../private.css") }
        } finally { rpc.close() }
    }

    @Test fun `remote localhost tunnel carries paths binary POST bodies and websocket bytes`() = runBlocking<Unit> {
        val ssh = trusted()
        try { verifyWebTunnel(ssh) } finally { ssh.close() }
    }
    @Test fun `remote localhost preview reaches services listening only on IPv6 loopback`() = runBlocking<Unit> {
        val ssh = trusted()
        try { verifyWebTunnel(ssh, "::1") } finally { ssh.close() }
    }
    private suspend fun verifyWebTunnel(ssh: SshConnection, address: String = "127.0.0.1") = coroutineScope {
        ServerSocket(0, 4, InetAddress.getByName(address)).use { service ->
            val worker = async(Dispatchers.IO) {
                repeat(3) { index -> service.accept().use { socket ->
                    socket.soTimeout = 5000
                    val input = socket.getInputStream(); val headers = ByteArrayOutputStream()
                    while (!headers.toString("US-ASCII").endsWith("\r\n\r\n")) { val b = input.read(); check(b >= 0); headers.write(b); check(headers.size() < 16384) }
                    val header = headers.toString("US-ASCII")
                    val output = socket.getOutputStream()
                    if (index == 2) {
                        assertTrue("Upgrade: websocket" in header)
                        output.write("HTTP/1.1 101 Switching Protocols\r\nConnection: Upgrade\r\nUpgrade: websocket\r\n\r\n".toByteArray()); output.flush()
                        val frame = input.readNBytes(4); output.write(frame); output.flush()
                    } else {
                        val body = if (index == 0) { assertTrue("GET /docs/report.md?q=1 HTTP/1.1" in header); "# 手机报告\n\n[下一页](./next.md)\n".toByteArray() }
                        else { assertTrue("POST /api HTTP/1.1" in header); input.readNBytes(4) }
                        output.write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray()); output.write(body); output.flush()
                    }
                } }
            }
            ssh.openWebTunnel(service.localPort).use { tunnel -> withContext(Dispatchers.IO) {
                fun request(request: ByteArray): ByteArray = Socket("127.0.0.1", tunnel.port).use { socket -> socket.soTimeout = 8000; socket.getOutputStream().write(request); socket.getOutputStream().flush(); socket.getInputStream().readBytes() }
                val get = downloadWebResource("http://127.0.0.1:${tunnel.port}/docs/report.md?q=1", 2 * 1024 * 1024)
                assertEquals("# 手机报告\n\n[下一页](./next.md)\n", get.toString(Charsets.UTF_8))
                val post = request("POST /api HTTP/1.1\r\nHost: localhost\r\nContent-Length: 4\r\n\r\n".toByteArray() + byteArrayOf(0, -1, 8, 9))
                assertContentEquals(byteArrayOf(0, -1, 8, 9), post.takeLast(4).toByteArray())
                Socket("127.0.0.1", tunnel.port).use { socket ->
                    socket.soTimeout = 8000
                    socket.getOutputStream().write("GET /socket HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\n\r\n".toByteArray()); socket.getOutputStream().flush()
                    val header = ByteArrayOutputStream()
                    while (!header.toString("US-ASCII").endsWith("\r\n\r\n")) { val b = socket.getInputStream().read(); check(b >= 0); header.write(b) }
                    val frame = byteArrayOf(-127, 2, 10, 42); socket.getOutputStream().write(frame); socket.getOutputStream().flush()
                    assertContentEquals(frame, socket.getInputStream().readNBytes(4))
                }
            } }
            worker.await()
        }
    }

    @Test fun `native image inputs survive SSH transport history and active turn steering`() = runBlocking<Unit> {
        val ssh = trusted(); val rpc = RpcClient(ssh, this)
        try {
            rpc.initialize(); val repo = CodexRepository(rpc, ssh)
            val project = repo.projects().single(); val thread = repo.newThread(project, project.roots.single())
            val bytes = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+j4XcAAAAASUVORK5CYII=")
            val turn = repo.send(thread.id, "", null, listOf(OutgoingImage(bytes)))
            val first = repo.history(thread.id).items.first { it.kind == "userMessage" }
            assertTrue(first.text.isEmpty())
            assertContentEquals(bytes, repo.image(first.images.single().source, project.roots.single()))
            repo.send(thread.id, "补充图片", turn, listOf(OutgoingImage(bytes), OutgoingImage(bytes)))
            assertEquals("turn/steer", rpc.request("probe/stats").text("lastMethod"))
            val last = repo.history(thread.id).items.last { it.kind == "userMessage" }
            assertEquals("补充图片", last.text); assertTrue(last.images.isNotEmpty())
        } finally { rpc.close() }
    }

    @Test fun `native fork rename compact and plan mode preserve the original conversation`() = runBlocking<Unit> {
        val ssh = trusted(); val rpc = RpcClient(ssh, this)
        try {
            rpc.initialize(); val repo = CodexRepository(rpc, ssh)
            val project = repo.projects().single(); val original = repo.newThread(project, project.roots.single())
            repo.send(original.id, "original instructions", null)
            repo.renameThread(original.id, "Named 中文")
            assertEquals("Named 中文", repo.threads(project).first.single().title)
            assertTrue("plan" in repo.collaborationModes())
            val planned = repo.updateSettings(original.id, collaborationSettingsPatch(repo.settings(original.id)!!, "plan"))
            assertEquals("plan", planned.collaboration!!.text("mode"))
            val fork = repo.forkThread(original.id)
            assertNotEquals(original.id, fork.id)
            assertTrue(repo.history(fork.id).items.any { it.text == "original instructions" })
            repo.send(fork.id, "branch instructions", null)
            assertTrue(repo.history(original.id).items.any { it.text == "original instructions" })
            assertFalse(repo.history(original.id).items.any { it.text == "branch instructions" })
            repo.compactThread(fork.id)
            assertEquals(1, rpc.request("probe/stats")["compactions"]?.jsonPrimitive?.int)
        } finally { rpc.close() }
    }

    @Test fun `archiving removes conversations from active list and restoring brings them back`() = runBlocking<Unit> {
        val ssh = trusted(); val rpc = RpcClient(ssh, this)
        try {
            rpc.initialize()
            val repo = CodexRepository(rpc, ssh)
            val project = repo.projects().single()
            val thread = repo.newThread(project, project.roots.single())
            repo.send(thread.id, "Archive regression", null)
            assertEquals(thread.id, repo.threads(project).first.single().id)
            repo.archiveThread(thread.id)
            assertTrue(repo.threads(project).first.isEmpty())
            assertEquals(thread.id, repo.threads(project, archived = true).first.single().id)
            repo.unarchiveThread(thread.id)
            assertTrue(repo.threads(project, archived = true).first.isEmpty())
            assertEquals(thread.id, repo.threads(project).first.single().id)
            assertTrue(repo.history(thread.id).items.any { it.text == "Archive regression" })
        } finally { rpc.close() }
    }

    @Test fun `reconnect recovers never-sent tabs without replaying a user message`() = runBlocking<Unit> {
        val ssh = trusted(); val rpc = RpcClient(ssh, this)
        try {
            rpc.initialize()
            val original = CodexRepository(rpc, ssh)
            val project = original.projects().single()
            val empty = original.newThread(project, project.roots.single())
            val reconnect = CodexRepository(rpc, ssh)
            val (replacement, settings) = reconnect.reconnectThread(empty, project)
            assertNotEquals(empty.id, replacement.id)
            assertEquals(empty.cwd, replacement.cwd)
            assertEquals("fixture-smart", settings.model)
            assertEquals("0", rpc.request("probe/stats").text("firstMessages"))
            assertTrue(reconnect.history(replacement.id).items.isEmpty())
            assertFailsWith<RpcError> { reconnect.reconnectThread(empty.copy(id = "fixture-history-denied"), project, replaceMissingEmpty = true) }
            val missing = empty.copy(id = "fixture-missing-empty")
            assertFailsWith<RpcError> { reconnect.reconnectThread(missing, project) }
            val (recovered, _) = reconnect.reconnectThread(missing, project, replaceMissingEmpty = true)
            assertNotEquals(missing.id, recovered.id)
            assertEquals("0", rpc.request("probe/stats").text("firstMessages"))
        } finally { rpc.close() }
    }

    @Test fun `bounded item history pages load without whole turn tool output and keep cursors`() = runBlocking<Unit> {
        val ssh = trusted(); val rpc = RpcClient(ssh, this)
        try {
            rpc.initialize()
            val repo = CodexRepository(rpc, ssh)
            val recent = repo.history("fixture-paged")
            assertEquals(40, recent.items.size)
            assertEquals("paged-agent-40", recent.items.first().id)
            assertEquals("paged-agent-79", recent.items.last().id)
            assertEquals(3000L, recent.items.first().turnDurationMs)
            assertTrue(recent.cursor!!.startsWith("items:"))
            val earlier = repo.history("fixture-paged", recent.cursor)
            assertEquals("paged-agent-0", earlier.items.first().id)
            assertEquals("paged-agent-39", earlier.items.last().id)
            assertNull(earlier.cursor)
            assertEquals("40", rpc.request("probe/stats").text("lastItemLimit"))
        } finally { rpc.close() }
    }

    @Test fun `model effort and permission selections are confirmed by server and survive sending feedback`() = runBlocking<Unit> {
        val ssh = trusted(); val rpc = RpcClient(ssh, this)
        try {
            rpc.initialize()
            val repo = CodexRepository(rpc, ssh)
            val models = repo.models()
            assertEquals(listOf("fixture-smart", "fixture-small"), models.map { it.id })
            val project = repo.projects().single()
            val thread = repo.newThread(project, project.roots.single())
            val changed = repo.updateSettings(thread.id, SettingsChange.Model(models[1].id, models[1].defaultEffort))
            assertEquals("fixture-small", changed.model); assertEquals("medium", changed.effort)
            assertEquals("Preserve these instructions", changed.collaboration!!.objectAt("settings").text("developer_instructions"))
            val permissions = repo.permissions(project.roots.single())
            assertFalse(permissions.first { it.id == "blocked" }.allowed)
            assertFailsWith<RpcError> { repo.updateSettings(thread.id, obj("permissions" to "blocked")) }
            assertEquals("默认权限", repo.settings(thread.id)!!.permissionLabel)
            val full = repo.updateSettings(thread.id, SettingsChange.Permission(permissions.first { it.id == ":danger-full-access" }))
            assertEquals("完整访问权限", full.permissionLabel); assertEquals("never", full.approvalPolicy.jsonPrimitive.content)
            val comment = ReviewComment(root = project.roots.single(), path = "app.kt", section = "工作区", newLine = 12, excerpt = "+code", body = "请处理空值")
            val feedback = reviewFeedback(listOf(comment))
            repo.send(thread.id, feedback, null)
            assertEquals(feedback, repo.history(thread.id).items.first().text)
            val resumed = CodexRepository(rpc, ssh).attachThread(thread.id)
            assertEquals("fixture-small", resumed.model); assertEquals("medium", resumed.effort); assertEquals("完整访问权限", resumed.permissionLabel)
            val normal = repo.updateSettings(thread.id, permissionSettingsPatch(permissions.first { it.id == ":workspace" }))
            assertEquals("on-request", normal.approvalPolicy.jsonPrimitive.content)
            val auto = permissions.first { it.reviewer == "auto_review" }
            val automatic = repo.updateSettings(thread.id, permissionSettingsPatch(auto))
            assertEquals("自动审查", automatic.permissionLabel)
            assertEquals("auto_review", automatic.reviewer)
            assertEquals(":workspace", automatic.permissionId)
            assertTrue(permissionSelected(automatic, auto))
            assertFalse(permissionSelected(automatic, permissions.first { it.id == ":workspace" && it.reviewer == "user" }))
        } finally { rpc.close() }
    }

    @Test fun `only the precise unmaterialized history error is treated as an empty conversation`() = runBlocking<Unit> {
        val ssh = trusted()
        val rpc = RpcClient(ssh, this)
        try {
            rpc.initialize()
            val repo = CodexRepository(rpc, ssh)
            assertTrue(repo.history("fixture-empty-existing").items.isEmpty())
            assertEquals(-32000, assertFailsWith<RpcError> { repo.history("fixture-history-denied") }.code)
        } finally { rpc.close() }
    }

    @Test fun `ephemeral image input reaches the live vision model`() = runBlocking<Unit> {
        Assume.assumeTrue("Enable liveImageProbe for a disposable vision test", System.getenv("CODEX_POCKET_LIVE_IMAGES") == "true")
        profile = profile.copy(codexPath = System.getenv("CODEX_POCKET_CODEX") ?: File(System.getProperty("user.home"), ".local/bin/codex").absolutePath)
        val ssh = trusted(); val rpc = RpcClient(ssh, this)
        var threadId: String? = null
        try {
            rpc.initialize(); val repository = CodexRepository(rpc, ssh)
            val models = repository.models().filter { "image" in it.inputModalities }
            val model = models.firstOrNull { it.isDefault } ?: models.first()
            val root = File(System.getProperty("pocket.root")).absolutePath
            val response = rpc.request("thread/start", obj("ephemeral" to true, "cwd" to root, "model" to model.id, "permissions" to ":read-only", "approvalPolicy" to "on-request"))
            val id = response.objectAt("thread").text("id"); threadId = id
            val bitmap = java.awt.image.BufferedImage(128, 64, java.awt.image.BufferedImage.TYPE_INT_RGB)
            for (y in 0 until 64) for (x in 0 until 128) bitmap.setRGB(x, y, if (x < 64) 0xff0000 else 0x0000ff)
            val bytes = ByteArrayOutputStream().apply { javax.imageio.ImageIO.write(bitmap, "png", this) }.toByteArray()
            val answers = mutableListOf<String>()
            val completed = async(start = CoroutineStart.UNDISPATCHED) {
                rpc.events.first { event ->
                    val params = event.objectAt("params")
                    if (params.text("threadId") == id && event.text("method") == "item/completed" && params.objectAt("item").text("type") == "agentMessage") answers += params.objectAt("item").text("text")
                    event.text("method") == "turn/completed" && params.text("threadId") == id
                }
            }
            try {
                repository.send(id, "Look at the attached image. Name the left-half color first and the right-half color second. Reply with only the two English color names. Do not use tools.", null, listOf(OutgoingImage(bytes)))
                val result = withTimeout(90000) { completed.await() }.objectAt("params").objectAt("turn")
                assertEquals("completed", result.text("status"), result.objectAt("error").toString())
                val answer = answers.joinToString(" ").lowercase()
                assertTrue("red" in answer && "blue" in answer && answer.indexOf("red") < answer.indexOf("blue"), answer.take(300))
                println("Live native image input OK: model=${model.id}; ephemeral answer=${answer.take(160)}")
            } finally { completed.cancel() }
        } finally {
            threadId?.let { runCatching { rpc.request("thread/unsubscribe", obj("threadId" to it)) } }
            rpc.close()
        }
    }

    @Test fun `ephemeral Codex settings accept real model efforts and permission profiles without inference`() = runBlocking<Unit> {
        Assume.assumeTrue("Enable liveSettingsProbe for a disposable settings-only thread", System.getenv("CODEX_POCKET_LIVE_SETTINGS") == "true")
        profile = profile.copy(codexPath = System.getenv("CODEX_POCKET_CODEX") ?: File(System.getProperty("user.home"), ".local/bin/codex").absolutePath)
        val ssh = trusted(); val rpc = RpcClient(ssh, this)
        var id: String? = null
        try {
            rpc.initialize()
            val repository = CodexRepository(rpc, ssh)
            val root = File(System.getProperty("pocket.root")).absolutePath
            val response = rpc.request("thread/start", obj("ephemeral" to true, "cwd" to root, "permissions" to ":read-only", "approvalPolicy" to "on-request"))
            val threadId = response.objectAt("thread").text("id"); id = threadId
            val models = repository.models()
            val initial = conversationSettings(response)
            val model = models.firstOrNull { it.id != initial.model } ?: models.first()
            val actual = repository.updateSettings(threadId, modelSettingsPatch(initial, model.id, model.defaultEffort))
            assertEquals(model.id, actual.model); assertEquals(model.defaultEffort, actual.effort)
            for (permission in repository.permissions(root).filter { it.allowed && it.id.startsWith(':') }) {
                val updated = repository.updateSettings(threadId, permissionSettingsPatch(permission))
                assertEquals(permission.id, updated.permissionId)
            }
            val modes = repository.collaborationModes()
            assertTrue("plan" in modes && "default" in modes)
            var modeSettings = repository.settings(threadId)!!
            for (mode in listOf("plan", "default")) {
                modeSettings = repository.updateSettings(threadId, collaborationSettingsPatch(modeSettings, mode))
                assertEquals(mode, modeSettings.collaboration?.text("mode"))
            }
            println("Live ephemeral settings OK: model + reasoning + allowed permissions confirmed; no turn/start or model inference")
        } finally {
            id?.let { runCatching { rpc.request("thread/unsubscribe", obj("threadId" to it)) } }
            rpc.close()
        }
    }

    @Test fun `actual Codex service accepts a custom client through an SSH exec stream`() = runBlocking<Unit> {
        Assume.assumeTrue("Enable with CODEX_POCKET_LIVE=true to read the existing local daemon", System.getenv("CODEX_POCKET_LIVE") == "true")
        profile = profile.copy(codexPath = System.getenv("CODEX_POCKET_CODEX") ?: File(System.getProperty("user.home"), ".local/bin/codex").absolutePath)
        val ssh = trusted()
        val rpc = RpcClient(ssh, this)
        try {
            val initialized = rpc.initialize()
            assertTrue(initialized.text("userAgent").isNotBlank())
            val status = rpc.request("remoteControl/status/read")
            assertTrue(status.text("status").isNotBlank())
            val entries = rpc.request("fs/readDirectory", obj("path" to System.getProperty("user.dir"))).array("entries")
            assertTrue(entries.isNotEmpty())
            val projects = rpc.request("project/list", obj("limit" to 1)).array("data")
            val repository = CodexRepository(rpc, ssh)
            val discovered = repository.projects()
            val models = repository.models()
            assertTrue(models.isNotEmpty())
            val root = File(System.getProperty("pocket.root")).absolutePath
            assertTrue(repository.permissions(root).isNotEmpty())
            System.getenv("CODEX_POCKET_THREAD")?.let { id ->
                val page = repository.history(id)
                assertTrue(page.items.size <= 40)
                assertTrue(page.items.isNotEmpty())
                println("Live bounded history OK; items=${page.items.size}; has more=${page.cursor != null}")
            }
            assertTrue(repository.file("$root/README.md").bytes.toString(Charsets.UTF_8).contains("Orbit"))
            System.getenv("CODEX_POCKET_IMAGE")?.let { path ->
                val image = repository.image(path, root)
                assertTrue(image.size > 100)
                assertContentEquals(byteArrayOf(-1, -40, -1), image.take(3).toByteArray())
                println("Live image read through SSH + fs/readFile OK; JPEG bytes=${image.size}")
            }
            println("Live read-only probe: initialize OK; status readable; directory entries=${entries.size}; native project page count=${projects.size}; discovered projects=${discovered.size}; file preview OK")
        } finally { rpc.close() }
    }

    @Test fun `context uploads preserve file bytes extension and route native skill references`() = runBlocking<Unit> {
        val ssh = trusted(); val rpc = RpcClient(ssh, this)
        val local = Files.createTempFile("context", ".log").toFile().apply { writeText("错误日志\nline two\n") }
        try {
            rpc.initialize(); val repo = CodexRepository(rpc, ssh)
            val project = repo.projects().first(); val thread = repo.newThread(project, project.roots.first())
            val catalog = repo.toolCatalog(project.roots.first(), thread.id)
            assertEquals("fixture-skill", catalog.skills.single().name)
            assertEquals("connected", catalog.servers.single().status)
            assertEquals("fixture-plugin", catalog.plugins.single().id)
            assertTrue(catalog.errors.isEmpty())
            repo.send(thread.id, "阅读附件", null, context = listOf(ContextInput("file", "错误日志.log", localPath = local.path, bytes = local.length().toInt()), ContextInput("skill", "fixture-skill", "/fixture/SKILL.md", "skill")))
            val sent = rpc.request("probe/stats").array("messages").last().jsonObject.array("input").map { it.jsonObject }
            assertEquals("skill", sent.last().text("type"))
            val remote = sent.first().text("text").substringAfter("\n\n@")
            assertTrue(remote.endsWith("错误日志.log")); assertFalse(remote == local.path)
            assertContentEquals(local.readBytes(), File(remote).readBytes())
            local.delete()
            val count = rpc.request("probe/stats").text("firstMessages")
            assertFailsWith<IllegalArgumentException> { repo.send(thread.id, "不可用附件", null, context = listOf(ContextInput("missing", "missing.log", localPath = local.path, bytes = 1))) }
            assertEquals(count, rpc.request("probe/stats").text("firstMessages"))
        } finally { rpc.close(); local.delete() }
    }

    private class ProcessCommand(private val command: String) : Command {
        private lateinit var input: InputStream
        private lateinit var output: OutputStream
        private lateinit var errors: OutputStream
        private lateinit var callback: ExitCallback
        private var process: Process? = null
        override fun setInputStream(stream: InputStream) { input = stream }
        override fun setOutputStream(stream: OutputStream) { output = stream }
        override fun setErrorStream(stream: OutputStream) { errors = stream }
        override fun setExitCallback(exitCallback: ExitCallback) { callback = exitCallback }
        override fun start(channel: ChannelSession, env: Environment) {
            val live = System.getenv("CODEX_POCKET_LIVE") == "true"
            val args = when {
                command.startsWith("fixture-route:") -> listOf("sh", "-c", command.removePrefix("fixture-route:"))
                command == "fixture-shell" -> listOf("sh", "-c", "printf 'Welcome to B\\n'; exec sh")
                command.startsWith("pocket_python=") -> listOf("sh", "-c", command)
                command.startsWith("umask 077;") -> listOf("sh", "-c", command)
                live -> listOf("sh", "-lc", command)
                command.startsWith("[ ! -L ") -> listOf("sh", "-c", command)
                command.startsWith("wc -c") && command.contains(System.getProperty("pocket.upload.root")) -> listOf("sh", "-c", command)
                command.startsWith("git ") || command.startsWith("pocket_git=") -> listOf("sh", "-lc", command)
                command == "cat" -> listOf("cat")
                command.contains("command -v traex") && !command.contains("app-server proxy") -> listOf("sh", "-c", "printf 'codex\ntraex\n'")
                command.contains("app-server proxy") -> listOf("python3", File(System.getProperty("pocket.root"), "tools/mock_codex.py").absolutePath)
                command.startsWith("wc -c") -> listOf("sh", "-c", if (command.contains("oversized.png")) "printf 9000000" else "printf 21")
                else -> listOf("sh", "-c", "exit 0")
            }
            val builder = ProcessBuilder(args).directory(File(System.getProperty("pocket.root")))
            builder.environment()["CODEX_POCKET_UPLOAD_ROOT"] = System.getProperty("pocket.upload.root")
            builder.environment()["CODEX_HOME"] = File(System.getProperty("pocket.upload.root"), "code-home").absolutePath
            val child = builder.start(); process = child
            Thread({ try { pump(input, child.outputStream) } catch (_: Exception) {} finally { runCatching { child.outputStream.close() } } }, "fixture-stdin").apply { isDaemon = true; start() }
            val stderr = Thread({ try { pump(child.errorStream, errors) } catch (_: Exception) {} }, "fixture-stderr").apply { isDaemon = true; start() }
            Thread({ try { pump(child.inputStream, output); val code = child.waitFor(); stderr.join(2000); callback.onExit(code) } catch (_: Exception) {} }, "fixture-stdout").apply { isDaemon = true; start() }
        }
        private fun pump(source: InputStream, target: OutputStream) {
            val buffer = ByteArray(8192)
            while (true) { val n = source.read(buffer); if (n < 0) break; target.write(buffer, 0, n); target.flush() }
        }
        override fun destroy(channel: ChannelSession) { process?.destroy() }
    }
}
