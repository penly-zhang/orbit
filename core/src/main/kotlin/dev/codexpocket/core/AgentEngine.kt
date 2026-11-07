package dev.codexpocket.core

import kotlinx.coroutines.CoroutineScope

data class EngineDescriptor(val id: String, val name: String, val requiresExecutable: Boolean = false)

/** Register an adapter here; protocol-specific construction never belongs in the app. */
interface AgentEngine {
    val descriptor: EngineDescriptor
    suspend fun open(profile: HostProfile, credentials: Credentials, scope: CoroutineScope): AgentRepository
}

class AgentEngineRegistry(engines: List<AgentEngine>) {
    private val engines = engines.associateBy { it.descriptor.id }
    init {
        require(this.engines.size == engines.size) { "Agent 引擎 ID 重复" }
        require(engines.all { it.descriptor.id.isNotBlank() }) { "Agent 引擎 ID 不能为空" }
    }
    val descriptors: List<EngineDescriptor> get() = engines.values.map { it.descriptor }
    fun engine(id: String): AgentEngine = engines[id] ?: error("未安装 Agent 适配器：$id")
    suspend fun open(profile: HostProfile, credentials: Credentials, scope: CoroutineScope): AgentRepository =
        engine(profile.engineId).open(profile, credentials, scope)
}

object AgentEngines {
    val default = AgentEngineRegistry(listOf(
        CodexEngine(EngineDescriptor("codex", "Codex")),
        CodexEngine(EngineDescriptor("codex-compatible", "Codex 兼容 Agent", requiresExecutable = true)),
    ))
}
