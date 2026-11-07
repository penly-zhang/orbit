package dev.codexpocket

import dev.codexpocket.core.*
import kotlinx.coroutines.*
import java.net.URI

internal fun composerContextActions(vm: PocketViewModel, state: PocketState, tab: SessionTab, picker: (String, Int) -> Unit): ContextActions {
    val profile = tab.profileId; val root = state.root; val thread = tab.thread.id
    fun repo() = vm.contextRepository(profile)
    return ContextActions(
        addDocument = { picker(profile, tab.number) },
        add = { vm.addContext(profile, tab.number, it) }, remove = { vm.removeContext(profile, tab.number, it.id) },
        files = { path, query ->
            val repository = repo()
            withTimeout(15000) {
                if (query.isBlank()) repository.files(path).map { ContextFile(it.name, path.trimEnd('/') + "/" + it.name, it.directory) }
                else repository.searchFiles(root, query).map { ContextFile(it.name, it.path, it.directory) }
            }
        },
        preview = { path ->
            val bytes = withTimeout(15000) { repo().file(path, 512 * 1024).bytes }
            require(bytes.none { it == 0.toByte() }) { "二进制文件不支持文本预览" }
            bytes.toString(Charsets.UTF_8)
        },
        catalog = { repo().toolCatalog(root, thread) },
        plugin = { plugin -> withTimeout(20000) { repo().pluginTools(plugin) } },
        enableSkill = { skill -> repo().enableSkill(skill.path) },
        reloadMcp = { repo().reloadMcp() },
        authorize = { name -> repo().authorizeMcp(name, thread) },
        finishAuthorization = { expected, callback ->
            val target = URI(callback.trim()); val redirect = URI(expected)
            require(isLoopbackLink(expected) && target.scheme == redirect.scheme && target.host == redirect.host && target.port == redirect.port && target.path == redirect.path && target.userInfo == null) { "回调地址与本次授权不匹配" }
            require(target.rawQuery.orEmpty().isNotBlank()) { "请粘贴授权完成后的完整回调地址" }
            val repository = repo()
            repository.webTunnel(loopbackPort(expected)).use { tunnel ->
                withContext(Dispatchers.IO) { downloadWebResource(tunneledUrl(callback.trim(), tunnel.port), 64 * 1024) }
            }
            Unit
        },
    )
}
