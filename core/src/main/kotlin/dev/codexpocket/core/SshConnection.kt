package dev.codexpocket.core

import com.jcraft.jsch.*
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.*
import java.security.MessageDigest
import java.security.Security
import java.util.Base64
import java.util.UUID

@Serializable
data class HostProfile(val id: String = UUID.randomUUID().toString(), val name: String = "我的电脑", val host: String = "", val port: Int = 22,
    val username: String = "", val fingerprint: String = "", val codexPath: String = "", val authType: String = "password",
    val proxyEnabled: Boolean = false, val proxyHost: String = "127.0.0.1", val proxyPort: Int = 1055,
    val autoConnect: Boolean = true, val jumpHosts: List<JumpHost> = emptyList(),
    val routeMode: String = "direct", val remoteTarget: String = "",
    val engineId: String = "codex")

fun reconnectDelaySeconds(attempt: Int): Long = listOf(1L, 2L, 4L, 8L, 15L, 30L)[(attempt - 1).coerceIn(0, 5)]
@Serializable
data class Credentials(val password: String = "", val privateKey: String = "", val passphrase: String = "", val jumps: Map<String, JumpCredentials> = emptyMap())
data class HostIdentity(val fingerprint: String, val algorithm: String, val changed: Boolean, val hopId: String? = null, val endpoint: String = "")
class HostKeyRequired(val identity: HostIdentity) : IOException(if (identity.changed) "主机密钥已变化，连接已阻止" else "首次连接，请核对电脑的 SSH 主机指纹")
data class CommandResult(val code: Int, val stdout: String, val stderr: String)

fun shellQuote(value: String): String {
    require(!value.contains('\u0000') && !value.contains('\n') && !value.contains('\r')) { "路径中不能包含换行或空字符" }
    return "'" + value.replace("'", "'\"'\"'") + "'"
}
class SshConnection private constructor(private val sessions: List<Session>, private val channel: ChannelExec, val wire: WebSocketWire, private val profile: HostProfile) : Closeable {
    private val session get() = sessions.last()
    companion object {
        suspend fun connect(profile: HostProfile, credentials: Credentials, command: String, handshake: Boolean = true): SshConnection {
            val resources = java.util.concurrent.CopyOnWriteArrayList<Session>()
            fun closeResources() { resources.asReversed().forEach { it.disconnect() } }
            return try {
                coroutineScope {
                    val attempt = async(Dispatchers.IO) {
                        try { connectProfile(profile, credentials, resources, command, handshake) }
                        catch (error: Exception) { currentCoroutineContext().ensureActive(); throw error }
                    }
                    try { attempt.await() } catch (error: Exception) { closeResources(); throw error }
                }
            } catch (error: Exception) { closeResources(); throw error }
        }
        private suspend fun connectProfile(profile: HostProfile, credentials: Credentials, resources: MutableList<Session>, command: String, handshake: Boolean): SshConnection = withContext(Dispatchers.IO) {
            require(profile.host.isNotBlank() && profile.username.isNotBlank() && profile.port in 1..65535) { "请填写正确的 SSH 地址、端口和用户名" }
            require(profile.jumpHosts.size <= 8) { "最多支持 8 个 SSH 跳板" }
            require(!profile.proxyEnabled || (profile.proxyHost.isNotBlank() && profile.proxyPort in 1..65535)) { "请填写正确的 SOCKS5 代理地址和端口" }
            routedCommand(profile.routeMode, profile.remoteTarget, "true")
            synchronized(SshConnection::class.java) {
                if (Security.getProvider("BC")?.javaClass != BouncyCastleProvider::class.java) {
                    Security.removeProvider("BC"); Security.addProvider(BouncyCastleProvider())
                }
            }
            val sessions = mutableListOf<Session>()
            var exec: ChannelExec? = null
            try {
                val endpoints = profile.jumpHosts + JumpHost("target", profile.host, profile.port, profile.username, profile.authType, profile.fingerprint)
                endpoints.forEachIndexed { index, endpoint ->
                    require(endpoint.host.isNotBlank() && endpoint.username.isNotBlank() && endpoint.port in 1..65535) { "请补全 ${endpoint.id} 的 SSH 地址、用户名和端口" }
                    val secret = if (index == endpoints.lastIndex) JumpCredentials(credentials.password, credentials.privateKey, credentials.passphrase)
                        else credentials.jumps[endpoint.id] ?: error("请填写跳板 ${endpoint.id} 的登录信息")
                    val parent = sessions.lastOrNull()
                    val localPort = parent?.setPortForwardingL("127.0.0.1", 0, endpoint.host, endpoint.port)
                    val ssh = connectEndpoint(endpoint, secret, if (index == endpoints.lastIndex) null else endpoint.id,
                        if (parent == null) profile else null, if (localPort == null) endpoint.host else "127.0.0.1", localPort ?: endpoint.port, resources)
                    sessions += ssh
                }
                val stream = openCommand(sessions.last(), profile, command)
                exec = stream.channel
                val wire = WebSocketWire(stream.input, stream.output)
                val watchdog = launch(Dispatchers.IO) { delay(15000); stream.channel.disconnect() }
                try { if (handshake) wire.handshake() } catch (error: Exception) { throw IOException("${error.message}\n${stream.errors.toString("UTF-8")}".trim()) }
                finally { watchdog.cancel() }
                SshConnection(sessions.toList(), stream.channel, wire, profile)
            } catch (error: Exception) {
                exec?.disconnect(); sessions.asReversed().forEach { it.disconnect() }; throw error
            }
        }
        private suspend fun connectEndpoint(endpoint: JumpHost, secret: JumpCredentials, hopId: String?, proxyProfile: HostProfile?, networkHost: String, networkPort: Int, resources: MutableList<Session>): Session {
            val jsch = JSch()
            var seen: HostIdentity? = null
            jsch.hostKeyRepository = object : HostKeyRepository {
                override fun check(host: String?, key: ByteArray): Int {
                    val fingerprint = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(key))
                    seen = HostIdentity(fingerprint, HostKey(host, key).type, endpoint.fingerprint.isNotBlank() && endpoint.fingerprint != fingerprint, hopId, "${endpoint.username}@${endpoint.host}:${endpoint.port}")
                    return if (endpoint.fingerprint == fingerprint) HostKeyRepository.OK else if (endpoint.fingerprint.isBlank()) HostKeyRepository.NOT_INCLUDED else HostKeyRepository.CHANGED
                }
                override fun add(hostkey: HostKey?, ui: UserInfo?) = Unit
                override fun remove(host: String?, type: String?) = Unit
                override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
                override fun getKnownHostsRepositoryID() = "Orbit pinned host key"
                override fun getHostKey(): Array<HostKey> = emptyArray()
                override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
            }
            if (endpoint.authType == "key") {
                require(secret.privateKey.isNotBlank()) { "请导入 ${endpoint.id} 的 SSH 私钥" }
                jsch.addIdentity("pocket-key", secret.privateKey.toByteArray(), null, secret.passphrase.takeIf(String::isNotBlank)?.toByteArray())
            }
            val ssh = jsch.getSession(endpoint.username, networkHost, networkPort)
            resources += ssh
            currentCoroutineContext().ensureActive()
            if (proxyProfile?.proxyEnabled == true) ssh.setProxy(ProxySOCKS5(proxyProfile.proxyHost, proxyProfile.proxyPort))
            ssh.setConfig("StrictHostKeyChecking", "yes")
            ssh.setConfig("PreferredAuthentications", if (endpoint.authType == "key") "publickey" else "password,keyboard-interactive")
            if (endpoint.authType != "key") {
                require(secret.password.isNotBlank()) { "请填写 ${endpoint.id} 的 SSH 密码" }
                ssh.setPassword(secret.password)
                ssh.userInfo = object : UserInfo, UIKeyboardInteractive {
                    override fun getPassword() = secret.password
                    override fun getPassphrase(): String? = null
                    override fun promptPassword(message: String?) = true
                    override fun promptPassphrase(message: String?) = false
                    override fun promptYesNo(message: String?) = false
                    override fun showMessage(message: String?) = Unit
                    override fun promptKeyboardInteractive(destination: String?, name: String?, instruction: String?, prompt: Array<out String>?, echo: BooleanArray?): Array<String>? =
                        if (prompt?.size == 1 && echo?.firstOrNull() == false) arrayOf(secret.password) else null
                }
            }
            ssh.serverAliveInterval = 15000; ssh.serverAliveCountMax = 3; ssh.timeout = 20000
            currentCoroutineContext().job.invokeOnCompletion { cause -> if (cause != null) ssh.disconnect() }
            try { ssh.connect(20000); ssh.timeout = 0; return ssh }
            catch (error: Exception) {
                ssh.disconnect()
                if (error is CancellationException) throw error
                seen?.takeIf { it.fingerprint != endpoint.fingerprint }?.let { throw HostKeyRequired(it) }
                if (proxyProfile?.proxyEnabled == true && seen == null) throw IOException("经 SOCKS5 代理连接失败，请检查代理是否运行及目标是否可达：${error.message}", error)
                throw error
            }
        }
        private data class ExecStream(val channel: ChannelExec, val input: InputStream, val output: OutputStream, val errors: ByteArrayOutputStream)
        private suspend fun openCommand(session: Session, profile: HostProfile, command: String): ExecStream = coroutineScope {
            val exec = session.openChannel("exec") as ChannelExec
            exec.setPty(false)
            val login = profile.routeMode == "loginShell"
            exec.setCommand(if (login) "exec \"\${SHELL:-/bin/sh}\" -il" else routedCommand(profile.routeMode, profile.remoteTarget, command))
            val input = exec.inputStream; val output = exec.outputStream; val errors = ByteArrayOutputStream()
            exec.setErrStream(object : OutputStream() { override fun write(b: Int) { if (errors.size() < 8192) errors.write(b) } })
            val watchdog = launch(Dispatchers.IO) { delay(20000); exec.disconnect() }
            try {
                exec.connect(15000)
                if (login) {
                    val marker = "POCKET_READY_${UUID.randomUUID()}:"
                    output.write(("printf '%s' ${shellQuote(marker)}; exec sh -c ${shellQuote(command)}\n").toByteArray()); output.flush()
                    skipShellPreamble(input, marker.toByteArray())
                }
                ExecStream(exec, input, output, errors)
            } catch (error: Exception) { exec.disconnect(); throw IOException("${error.message}\n${errors.toString("UTF-8")}".trim(), error) }
            finally { watchdog.cancel() }
        }
    }
    suspend fun openWebTunnel(port: Int): LoopbackTunnel {
        require(port in 1..65535)
        require(execute(pythonCommand(listOf("-c", "pass")), 4096).code == 0) { "电脑本地网页转接需要可运行的 Python 3.7 或以上版本；请在最终目标电脑安装后重试" }
        // Run on the final host, including login-shell and remote-alias redirects.
        val program = """
import socket,sys,os,threading
s=None
for host in ('127.0.0.1','::1'):
 try:
  s=socket.create_connection((host,$port),15)
  break
 except OSError as failure: last_error=failure
if s is None: raise last_error
s.settimeout(None)
def upload():
 try:
  while True:
   data=os.read(0,65536)
   if not data: break
   s.sendall(data)
 finally:
  try: s.shutdown(socket.SHUT_WR)
  except OSError: pass
threading.Thread(target=upload,daemon=True).start()
while True:
 data=s.recv(65536)
 if not data: break
 sys.stdout.buffer.write(data)
 sys.stdout.buffer.flush()
""".trimIndent()
        // shellQuote rejects embedded newlines; encode source so Python keeps indentation.
        val encoded = Base64.getEncoder().encodeToString(program.toByteArray())
        val safeCommand = pythonCommand(listOf("-u", "-c", "import base64;exec(base64.b64decode('$encoded'))"))
        return LoopbackTunnel {
            val stream = withContext(Dispatchers.IO) { openCommand(session, profile, safeCommand) }
            TunnelStream(stream.input, stream.output) { stream.channel.disconnect() }
        }
    }
    suspend fun execute(command: String, maxBytes: Int = 2 * 1024 * 1024): CommandResult = executeInput(command, null, maxBytes)
    suspend fun uploadImage(bytes: ByteArray): String {
        require(bytes.size in 1..MAX_OUTGOING_IMAGE_BYTES) { "图片大小无效" }
        val root = "\${CODEX_POCKET_UPLOAD_ROOT:-\${CODEX_HOME:-\$HOME/.codex}/codex-pocket/images}"
        val command = "umask 077; d=\"$root\"; mkdir -p \"\$d\" && p=\$(mktemp \"\$d/image.XXXXXXXX\") || exit 1; if cat > \"\$p\" && [ \"\$(wc -c < \"\$p\")\" -eq ${bytes.size} ]; then printf '%s' \"\$p\"; else rm -f -- \"\$p\"; exit 1; fi"
        val result = executeInput(command, bytes, 4096)
        require(result.code == 0) { result.stderr.ifBlank { "图片传输失败，请检查电脑的磁盘与目录权限" } }
        val path = result.stdout.trim()
        require(path.startsWith('/') && path.none { it == '\n' || it == '\r' || it == '\u0000' }) { "电脑未返回有效的图片附件路径" }
        return path
    }
    suspend fun uploadAttachment(name: String, bytes: ByteArray): String {
        require(bytes.size in 1..MAX_ATTACHMENT_BYTES) { "附件需在 4 MB 以内" }
        val safeName = name.substringAfterLast('/').substringAfterLast('\\').filter { it.isLetterOrDigit() || it in "._- " }.take(120).ifBlank { "attachment" }
        val root = "\${CODEX_POCKET_UPLOAD_ROOT:-\${CODEX_HOME:-\$HOME/.codex}/codex-pocket/attachments}"
        val command = "umask 077; d=\"$root\"; mkdir -p \"\$d\" && t=\$(mktemp -d \"\$d/file.XXXXXXXX\") || exit 1; p=\"\$t\"/" + shellQuote(safeName) + "; if cat > \"\$p\" && [ \"\$(wc -c < \"\$p\")\" -eq ${bytes.size} ]; then printf '%s' \"\$p\"; else rm -f -- \"\$p\"; rmdir \"\$t\"; exit 1; fi"
        val result = executeInput(command, bytes, 4096)
        require(result.code == 0) { result.stderr.ifBlank { "附件传输失败，请检查电脑磁盘及目录权限" } }
        return result.stdout.trim().also { require(it.startsWith('/') && it.none(Char::isISOControl)) { "电脑未返回有效的附件路径" } }
    }
    private suspend fun executeInput(command: String, input: ByteArray?, maxBytes: Int): CommandResult = withContext(Dispatchers.IO) {
        val stream = openCommand(session, profile, command)
        val exec = stream.channel
        val watchdog = launch(Dispatchers.IO) { delay(20000); exec.disconnect() }
        try {
            if (input != null) { stream.output.write(input); stream.output.flush(); stream.output.close() }
            val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) {
                val n = stream.input.read(buffer); if (n < 0) break
                require(output.size() + n <= maxBytes) { "结果过大，请选择更小的文件或变更" }
                output.write(buffer, 0, n)
            }
            var wait = 0
            while (!exec.isClosed && wait++ < 100) delay(20)
            require(exec.isClosed) { "SSH 命令超时" }
            CommandResult(exec.exitStatus, output.toString("UTF-8"), stream.errors.toString("UTF-8"))
        } finally { watchdog.cancel(); exec.disconnect() }
    }
    override fun close() { channel.disconnect(); sessions.asReversed().forEach { it.disconnect() } }
}
