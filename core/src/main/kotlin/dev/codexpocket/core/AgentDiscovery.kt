package dev.codexpocket.core

/** Run in the final SSH target's login environment, including remote-alias routes. */
internal fun locateAgent(binary: String): String {
    require(binary in setOf("codex", "traex"))
    val candidates = listOf("\$HOME/.local/bin/$binary", "\$HOME/.$binary/packages/standalone/current/bin/$binary", "/opt/homebrew/bin/$binary", "/usr/local/bin/$binary")
    return "agent_bin=\$(command -v $binary || true); if [ -z \"\$agent_bin\" ]; then for p in " +
        candidates.joinToString(" ") { "\"$it\"" } + "; do if [ -x \"\$p\" ]; then agent_bin=\"\$p\"; break; fi; done; fi"
}
internal fun agentDiscoveryCommand(): String = "sh -lc " + shellQuote(
    listOf("codex", "traex").joinToString("; ") { "${locateAgent(it)}; if [ -n \"\$agent_bin\" ]; then echo $it; fi" })
internal fun discoveredProxyCommand(binary: String): String = "exec sh -lc " + shellQuote(
    "${locateAgent(binary)}; [ -n \"\$agent_bin\" ] || { echo '$binary executable not found' >&2; exit 127; }; ${agentProxyInvocation(binary)}")

/** start is idempotent: reuse the existing daemon, never restart an active session. */
internal fun agentProxyInvocation(binary: String): String {
    require(binary in setOf("codex", "traex"))
    val start = if (binary == "traex")
        "\"\$agent_bin\" app-server daemon start >&2 || { echo '无法启动 traex 共享后台，请检查电脑上的 traex 版本和服务状态。' >&2; exit 1; }; "
    else ""
    return start + "exec \"\$agent_bin\" app-server proxy"
}

fun threadEngine(id: String): String = if (id.startsWith("traex::")) "traex" else "codex"
fun agentLabel(id: String): String = if (id == "traex") "traex" else "Codex"
