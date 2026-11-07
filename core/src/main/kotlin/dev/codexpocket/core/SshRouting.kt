package dev.codexpocket.core

import kotlinx.serialization.Serializable
import java.io.InputStream
import java.io.IOException

@Serializable data class JumpHost(val id: String, val host: String, val port: Int = 22, val username: String = "", val authType: String = "password", val fingerprint: String = "")
@Serializable data class JumpCredentials(val password: String = "", val privateKey: String = "", val passphrase: String = "")
data class SshConfigRoute(val alias: String, val host: String, val port: Int, val username: String, val keyFile: String?, val jumps: List<JumpHost>)

/** A portable config import: no local commands, Include expansion or filesystem access. */
class SshConfigImport(private val source: String) {
    private data class Block(val patterns: List<String>, val values: MutableMap<String, String> = linkedMapOf())
    private val blocks = mutableListOf(Block(listOf("*")))
    init {
        require(source.toByteArray().size <= 256 * 1024) { "SSH 配置超过 256 KB" }
        source.lineSequence().forEachIndexed { index, line ->
            val parts = tokens(line)
            if (parts.isEmpty()) return@forEachIndexed
            val key = parts.first().lowercase()
            require(parts.size > 1) { "SSH 配置第 ${index + 1} 行缺少值" }
            when (key) {
                "include", "match" -> throw IllegalArgumentException("暂不支持 $key，请先将需要的 Host 配置展开后导入")
                "host" -> blocks += Block(parts.drop(1))
                else -> blocks.last().values.putIfAbsent(key, parts.drop(1).joinToString(" "))
            }
        }
    }
    val aliases: List<String> get() = blocks.flatMap { it.patterns }.filterNot { it.startsWith('!') || '*' in it || '?' in it }.distinct()
    private fun config(alias: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        fun matches(pattern: String) = Regex("^" + pattern.map { when (it) { '*' -> ".*"; '?' -> "."; else -> Regex.escape(it.toString()) } }.joinToString("") + "$", RegexOption.IGNORE_CASE).matches(alias)
        blocks.filter { block -> block.patterns.none { it.startsWith('!') && matches(it.drop(1)) } && block.patterns.any { !it.startsWith('!') && matches(it) } }.forEach { block -> block.values.forEach { (k, v) -> result.putIfAbsent(k, v) } }
        require(result["proxycommand"].isNullOrBlank() || result["proxycommand"].equals("none", true)) { "此 Host 使用 ProxyCommand，请改用 ProxyJump 或远端 SSH 别名模式" }
        return result
    }
    fun resolve(alias: String, defaultUser: String = ""): SshConfigRoute {
        require(alias.isNotBlank() && alias.none(Char::isWhitespace)) { "请选择 SSH Host 别名" }
        val visiting = mutableSetOf<String>()
        fun endpoint(name: String, explicitUser: String? = null, explicitPort: Int? = null): Pair<JumpHost, Map<String, String>> {
            val values = config(name)
            val host = values["hostname"]?.replace("%h", name) ?: name
            require('%' !in host) { "暂不支持 HostName 中的此类变量" }
            val port = explicitPort ?: values["port"]?.let { it.toIntOrNull() ?: error("SSH Port 无效") } ?: 22
            require(port in 1..65535) { "SSH Port 无效" }
            val user = explicitUser ?: values["user"] ?: defaultUser
            return JumpHost("config:$name", host, port, user, if (values.containsKey("identityfile")) "key" else "password") to values
        }
        fun chain(name: String, explicitUser: String? = null, explicitPort: Int? = null): List<JumpHost> {
            require(visiting.add(name.lowercase()) && visiting.size <= 8) { "ProxyJump 存在循环或超过 8 层" }
            val (host, values) = endpoint(name, explicitUser, explicitPort)
            val proxy = values["proxyjump"]?.takeUnless { it.equals("none", true) }
            val preceding = proxy?.split(',')?.flatMap { raw ->
                val (user, target, port) = jumpAddress(raw.trim())
                chain(target, user, port)
            }.orEmpty()
            visiting.remove(name.lowercase())
            require(preceding.size < 8) { "ProxyJump 超过 8 层" }
            return preceding + host
        }
        val route = chain(alias)
        val target = route.last()
        return SshConfigRoute(alias, target.host, target.port, target.username, config(alias)["identityfile"], route.dropLast(1))
    }
    private fun jumpAddress(value: String): Triple<String?, String, Int?> {
        require(value.isNotBlank() && !value.startsWith('-') && value.none { it.isWhitespace() || it in "\n\r\u0000" }) { "ProxyJump 地址无效" }
        val user = value.substringBeforeLast('@', "").takeIf(String::isNotBlank)
        val address = value.substringAfterLast('@')
        val host: String; val port: Int?
        if (address.startsWith('[')) {
            require(']' in address) { "ProxyJump IPv6 地址无效" }
            host = address.substringAfter('[').substringBefore(']')
            val suffix = address.substringAfter(']')
            require(suffix.isEmpty() || suffix.startsWith(':')) { "ProxyJump 地址无效" }
            port = suffix.takeIf(String::isNotEmpty)?.drop(1)?.let { it.toIntOrNull() ?: error("ProxyJump 端口无效") }
        } else {
            require(address.count { it == ':' } <= 1) { "IPv6 ProxyJump 地址请使用 [地址]:端口" }
            host = address.substringBefore(':')
            port = address.substringAfter(':', "").takeIf(String::isNotEmpty)?.let { it.toIntOrNull() ?: error("ProxyJump 端口无效") }
        }
        require(host.isNotBlank()) { "ProxyJump 地址无效" }
        return Triple(user, host, port)
    }
    private fun tokens(line: String): List<String> {
        val result = mutableListOf<String>(); val word = StringBuilder(); var quote: Char? = null; var escape = false
        fun flush() { if (word.isNotEmpty()) { result += word.toString(); word.clear() } }
        for (c in line) {
            when {
                escape -> { word.append(c); escape = false }
                c == '\\' -> escape = true
                quote != null -> if (c == quote) quote = null else word.append(c)
                c == '\'' || c == '"' -> quote = c
                c == '#' -> break
                c.isWhitespace() || c == '=' && (result.isEmpty() || result.size == 1 && word.isEmpty()) -> flush()
                else -> word.append(c)
            }
        }
        require(quote == null && !escape) { "SSH 配置引号或转义不完整" }; flush(); return result
    }
}

fun routedCommand(mode: String, target: String, command: String): String = when (mode) {
    "direct", "loginShell" -> command
    "remoteSsh" -> {
        require(target.isNotBlank() && !target.startsWith('-') && target.none { it.isWhitespace() || it == '\u0000' }) { "请填写 A 上可免密 SSH 的目标别名，例如 user@host-b" }
        "exec ssh -T -o BatchMode=yes -o StrictHostKeyChecking=yes -- ${shellQuote(target)} ${shellQuote(command)}"
    }
    else -> error("未知 SSH 连接方式")
}

/** Startup banners/prompts never reach the WebSocket parser; no PTY alters binary frames. */
internal fun skipShellPreamble(input: InputStream, marker: ByteArray, maxBytes: Int = 256 * 1024) {
    var matched = 0
    repeat(maxBytes) {
        val next = input.read()
        if (next < 0) throw IOException("登录跳转未就绪：请确认 A→B 可免密登录，或改用远端 SSH 别名模式")
        matched = if (next == marker[matched].toInt().and(255)) matched + 1 else if (next == marker[0].toInt().and(255)) 1 else 0
        if (matched == marker.size) return
    }
    throw IOException("登录跳转输出过多，未等到最终目标就绪")
}
