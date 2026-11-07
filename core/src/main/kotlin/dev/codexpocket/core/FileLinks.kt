package dev.codexpocket.core

import java.net.URI

fun isRemoteFileLink(link: String): Boolean {
    val value = link.replace(Regex(":\\d+(?::\\d+)?$"), "")
    return value.isNotBlank() && !value.startsWith('#') &&
        (value.startsWith("file:") || value.startsWith("sandbox:") || !Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(value))
}

fun remoteFileLink(link: String, cwd: String): String {
    require(isRemoteFileLink(link)) { "此链接不是电脑上的项目文件" }
    val value = link.removePrefix("sandbox:").replace(Regex(":\\d+(?::\\d+)?$"), "")
    val uri = URI(value.replace(" ", "%20"))
    require(uri.authority.isNullOrBlank()) { "不支持其他电脑的文件链接" }
    val path = uri.path
    require(!path.isNullOrBlank()) { "文件路径为空" }
    return if (path.startsWith('/')) path else { require(cwd.startsWith('/')); "$cwd/$path" }
}
