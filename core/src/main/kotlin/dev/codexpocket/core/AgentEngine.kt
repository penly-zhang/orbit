package dev.codexpocket.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException

data class EngineDescriptor(val id: String, val name: String)

/** Register an adapter here; protocol-specific construction never belongs in the app. */
interface AgentEngine {
    val descriptor: EngineDescriptor
    suspend fun open(profile: HostProfile, credentials: Credentials, scope: CoroutineScope): AgentRepository
}

class AgentEngineRegistry(engines: List<AgentEngine>, private val discover: Boolean = false) {
    private val engines = engines.associateBy { it.descriptor.id }
    init {
        require(this.engines.size == engines.size) { "Agent 引擎 ID 重复" }
        require(engines.all { it.descriptor.id.isNotBlank() }) { "Agent 引擎 ID 不能为空" }
    }
    val descriptors: List<EngineDescriptor> get() = engines.values.map { it.descriptor }
    fun engine(id: String): AgentEngine = engines[id] ?: error("未安装 Agent 适配器：$id")
    suspend fun open(profile: HostProfile, credentials: Credentials, scope: CoroutineScope): AgentRepository {
        if (!discover) return engine(profile.engineId).open(profile, credentials, scope)
        val found = SshConnection.connect(profile, credentials, "cat", handshake = false).use { ssh ->
            val result = ssh.execute(agentDiscoveryCommand())
            check(result.code == 0) { "无法检测电脑上的 Agent：${result.stderr}" }
            result.stdout.lineSequence().filter { it in engines }.toSet()
        }
        check(found.isNotEmpty()) { "未发现 Codex 或 traex。请在执行任务的电脑上安装并启动 Agent 服务，然后重新连接。" }
        val opened = linkedMapOf<String, AgentRepository>()
        val issues = linkedMapOf<String, String>()
        try {
            descriptors.filter { it.id in found }.forEach { descriptor ->
                try { opened[descriptor.id] = engine(descriptor.id).open(profile, credentials, scope) }
                catch (cancel: CancellationException) { throw cancel }
                catch (trust: HostKeyRequired) { throw trust }
                catch (error: Exception) { issues[descriptor.id] = error.message ?: "服务不可用" }
            }
            check(opened.isNotEmpty()) { issues.entries.joinToString("；") { "${agentLabel(it.key)}：${it.value}" } }
            return MultiAgentRepository(opened, scope, issues)
        } catch (error: Throwable) { opened.values.forEach { it.close() }; throw error }
    }
}

object AgentEngines {
    val default = AgentEngineRegistry(listOf(
        CodexEngine(EngineDescriptor("codex", "Codex")),
        CodexEngine(EngineDescriptor("traex", "traex")),
    ), discover = true)
}
