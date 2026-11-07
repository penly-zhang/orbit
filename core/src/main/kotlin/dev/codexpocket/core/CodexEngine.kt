package dev.codexpocket.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonObject

/** Forks can reuse this adapter only when they preserve the app-server proxy protocol. */
class CodexEngine(override val descriptor: EngineDescriptor = EngineDescriptor("codex", "Codex")) : AgentEngine {
    fun command(profile: HostProfile): String {
        require(!descriptor.requiresExecutable || profile.codexPath.isNotBlank()) { "请填写兼容 Agent 的可执行文件路径" }
        return proxyCommand(profile.codexPath)
    }
    override suspend fun open(profile: HostProfile, credentials: Credentials, scope: CoroutineScope): AgentRepository {
        val ssh = SshConnection.connect(profile, credentials, command(profile))
        return try { CodexRepository(RpcClient(ssh, scope), ssh) }
        catch (error: Exception) { ssh.close(); throw error }
    }
}

fun proxyCommand(path: String): String {
    val locate = if (path.isBlank()) "codex_bin=\$(command -v codex || true); if [ -z \"\$codex_bin\" ]; then for p in \"\$HOME/.local/bin/codex\" \"\$HOME/.codex/packages/standalone/current/bin/codex\"; do if [ -x \"\$p\" ]; then codex_bin=\"\$p\"; break; fi; done; fi; [ -n \"\$codex_bin\" ] || { echo 'Codex executable not found' >&2; exit 127; }; exec \"\$codex_bin\" app-server proxy"
    else {
        val executable = if (path.startsWith("~/")) "\"\$HOME\"/" + shellQuote(path.removePrefix("~/")) else shellQuote(path)
        "exec $executable app-server proxy"
    }
    return "exec sh -lc " + shellQuote(locate)
}


internal suspend fun RpcClient.initialize(): JsonObject {
    val result = request("initialize", obj("clientInfo" to obj("name" to "codex_pocket_android", "title" to "Orbit", "version" to "0.12.20"), "capabilities" to obj("experimentalApi" to true)))
    notify("initialized")
    return result
}
