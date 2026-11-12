package dev.codexpocket.core

import java.net.URI
import java.nio.file.Paths

fun isWebLink(value: String): Boolean = runCatching { URI(value).let { it.scheme?.lowercase() in setOf("http", "https") && it.host != null && it.userInfo == null } }.getOrDefault(false)
fun isLoopbackLink(value: String): Boolean = runCatching { val u = URI(value); isWebLink(value) && u.host.lowercase().removeSurrounding("[", "]") in setOf("localhost", "127.0.0.1", "::1", "0.0.0.0") }.getOrDefault(false)
fun loopbackPort(value: String): Int { val u = URI(value); require(isLoopbackLink(value)); return (if (u.port >= 0) u.port else if (u.scheme == "https") 443 else 80).also { require(it in 1..65535) } }
fun tunneledUrl(value: String, port: Int): String {
    require(isLoopbackLink(value) && URI(value).scheme == "http") { "电脑本地预览需使用 HTTP 地址" }
    val u = URI(value)
    return "http://127.0.0.1:$port${u.rawPath.orEmpty().ifBlank { "/" }}${u.rawQuery?.let { "?$it" }.orEmpty()}${u.rawFragment?.let { "#$it" }.orEmpty()}"
}
fun artifactMime(path: String): String = when (path.substringBefore('?').substringAfterLast('.').lowercase()) {
    "html", "htm" -> "text/html"; "css" -> "text/css"; "js", "mjs" -> "application/javascript"; "json" -> "application/json"
    "svg" -> "image/svg+xml"; "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "webp" -> "image/webp"; "gif" -> "image/gif"; "ico" -> "image/x-icon"
    "woff" -> "font/woff"; "woff2" -> "font/woff2"; "ttf" -> "font/ttf"; "otf" -> "font/otf"; "txt", "md", "markdown", "mermaid", "mmd" -> "text/plain"
    else -> "application/octet-stream"
}
/** A rendered document can read web assets in its own directory, never arbitrary host files. */
fun artifactResourcePath(root: String, encodedPath: String): String {
    val decoded = URI("https://pocket.invalid$encodedPath").path
    require(decoded.startsWith('/') && !decoded.contains('\\') && !decoded.contains('\u0000'))
    val relative = decoded.removePrefix("/")
    require(relative.split('/').none { it.startsWith('.') || it.isEmpty() }) { "不允许读取此预览资源" }
    require(artifactMime(relative) != "application/octet-stream") { "不支持此预览资源类型" }
    val base = Paths.get(root).normalize(); require(base.isAbsolute)
    val result = base.resolve(relative).normalize(); require(result.startsWith(base))
    return result.toString()
}
