package dev.codexpocket.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.Base64

@Serializable data class ChatImage(val source: String, val label: String = "图片附件")
const val MAX_CHAT_IMAGE_BYTES = 8 * 1024 * 1024

fun contentImage(part: JsonObject): ChatImage? {
    val source = when (part.text("type")) {
        "localImage" -> part.text("path")
        "image", "input_image" -> part.text("url").ifBlank { part.text("image_url") }.ifBlank {
            part.text("data").takeIf(String::isNotBlank)?.let { "data:${part.text("mimeType", "image/png")};base64,$it" }.orEmpty()
        }.ifBlank { part.text("fileId").ifBlank { part.text("file_id") }.takeIf(String::isNotBlank)?.let { "codex-file:$it" }.orEmpty() }
        "inputImage" -> part.text("imageUrl")
        else -> ""
    }
    return source.takeIf(String::isNotBlank)?.let(::ChatImage)
}

/** Only unwrap actual protocol messages; ordinary prose and malformed replies stay intact. */
fun displayUserText(raw: String, images: List<ChatImage> = emptyList()): String {
    val trimmed = raw.trim()
    val opening = "<send_user_message_question_reply>"
    val closing = "</send_user_message_question_reply>"
    if (trimmed.startsWith(opening) && trimmed.endsWith(closing)) {
        val answers = runCatching {
            val array = json.parseToJsonElement(trimmed.removePrefix(opening).removeSuffix(closing).trim()).jsonArray
            require(array.isNotEmpty())
            array.map { it.jsonObject["answer"]?.jsonPrimitive?.content ?: error("Missing answer") }
        }.getOrNull()
        if (answers != null) return answers.joinToString("\n\n")
    }
    // Native remote attachments repeat their paths in a generated text header.
    // Remove that header only when every file is represented by an image thumbnail.
    val marker = "## My request for Codex:"
    if (raw.startsWith("# Files mentioned by the user:") && marker in raw && images.isNotEmpty()) {
        val header = raw.substringBefore(marker)
        val files = Regex("(?m)^## [^\\n]+?: (.+)$").findAll(header).map { it.groupValues[1].trim() }.toList()
        if (files.isNotEmpty() && files.all { path -> images.any { it.source == path } }) return raw.substringAfter(marker).trim()
    }
    return raw
}

sealed interface MessageContent {
    data class Text(val text: String) : MessageContent
    data class Image(val image: ChatImage) : MessageContent
    data class Document(val source: String, val format: String) : MessageContent
    data class Artifact(val link: String, val label: String) : MessageContent
}

/** Split inline Markdown images while leaving code examples and their source text untouched. */
fun messageContent(text: String): List<MessageContent> {
    if (Regex("(?is)^\\s*(?:<!doctype\\s+html\\b|<(?:html|svg|table|div|section|article|main|p|a|h[1-6]|ul|ol|figure|canvas|button)[\\s>])").containsMatchIn(text) && Regex("(?is)</(?:html|svg|table|div|section|article|main|p|a|h[1-6]|ul|ol|figure|canvas|button)>\\s*$").containsMatchIn(text)) return listOf(MessageContent.Document(text, "html"))
    val parts = mutableListOf<MessageContent>()
    var start = 0
    var i = 0
    var fence: String? = null
    while (i < text.length) {
        if (i == 0 || text[i - 1] == '\n') {
            val lineEnd = text.indexOf('\n', i).let { if (it < 0) text.length else it }
            val line = text.substring(i, lineEnd)
            val run = Regex("^ {0,3}(`{3,}|~{3,})").find(line)?.groupValues?.get(1)
            if (run != null) {
                val current = fence
                if (current == null) {
                    val language = line.trim().removePrefix(run).trim().lowercase()
                    if (language in setOf("html", "svg", "markdown", "md", "mermaid")) {
                        val closing = Regex("(?m)^ {0,3}" + Regex.escape(run.first().toString()) + "{" + run.length + ",}[ \t]*\\r?$").find(text, (lineEnd + 1).coerceAtMost(text.length))
                        if (closing != null) {
                            if (i > start) parts += MessageContent.Text(text.substring(start, i))
                            parts += MessageContent.Document(text.substring((lineEnd + 1).coerceAtMost(text.length), closing.range.first), language)
                            i = closing.range.last + 1; start = i; continue
                        }
                    }
                    fence = run
                }
                else if (run[0] == current[0] && run.length >= current.length && line.trim().all { it == current[0] }) fence = null
                i = (lineEnd + 1).coerceAtMost(text.length)
                continue
            }
        }
        if (fence != null) { i++; continue }
        if (text[i] == '\\' && i + 1 < text.length) { i += 2; continue }
        if (text[i] == '`') {
            val end = text.indexOfFirstFrom(i) { it != '`' }
            val delimiter = text.substring(i, end)
            val closing = text.indexOf(delimiter, end)
            i = if (closing >= 0) closing + delimiter.length else end
            continue
        }
        if (text.startsWith("<img ", i, ignoreCase = true)) {
            val end = text.indexOf('>', i)
            if (end > i) {
                val tag = text.substring(i, end + 1)
                val src = Regex("(?i)\\bsrc\\s*=\\s*([\"'])(.*?)\\1").find(tag)?.groupValues?.get(2)
                if (!src.isNullOrBlank()) {
                    val alt = Regex("(?i)\\balt\\s*=\\s*([\"'])(.*?)\\1").find(tag)?.groupValues?.get(2).orEmpty()
                    if (i > start) parts += MessageContent.Text(text.substring(start, i))
                    parts += MessageContent.Image(ChatImage(src.replace("&amp;", "&"), alt.ifBlank { "回复中的图片" }))
                    i = end + 1; start = i; continue
                }
            }
        }
        if ((text.startsWith("https://", i) || text.startsWith("http://", i)) && (i == 0 || text[i - 1].isWhitespace())) {
            val end = text.indexOfFirstFrom(i) { it.isWhitespace() || it in "<>\"'" }
            val candidate = text.substring(i, end).trimEnd('.', ',', ';', '!', '。', '，', '；')
            if (isWebLink(candidate) && runCatching { URI(candidate).path.substringAfterLast('.').lowercase() in setOf("png", "jpg", "jpeg", "webp", "gif", "bmp") }.getOrDefault(false)) {
                if (i > start) parts += MessageContent.Text(text.substring(start, i))
                parts += MessageContent.Image(ChatImage(candidate, "回复中的图片"))
                i += candidate.length; start = i; continue
            }
        }
        val imageSyntax = text.startsWith("![", i)
        if (!imageSyntax && text[i] == '[' && i > 0 && text[i - 1] == '!') { i++; continue }
        if (!imageSyntax && text[i] != '[') { i++; continue }
        val labelStart = i + if (imageSyntax) 2 else 1
        val labelEnd = text.indexOf("](", labelStart)
        if (labelEnd < 0 || '\n' in text.substring(labelStart, labelEnd)) { i++; continue }
        var cursor = labelEnd + 2
        var depth = 1
        var angle = false
        var quote: Char? = null
        while (cursor < text.length && text[cursor] != '\n') {
            val c = text[cursor]
            if (c == '\\' && cursor + 1 < text.length) { cursor += 2; continue }
            when {
                quote != null -> if (c == quote) quote = null
                angle -> if (c == '>') angle = false
                c == '<' -> angle = true
                (c == '"' || c == '\'') && cursor > labelEnd + 2 && text[cursor - 1].isWhitespace() -> quote = c
                c == '(' -> depth++
                c == ')' -> { depth--; if (depth == 0) break }
            }
            cursor++
        }
        if (depth != 0) { i++; continue }
        val raw = text.substring(labelEnd + 2, cursor).trim()
        val source = if (raw.startsWith('<')) raw.substringAfter('<').substringBefore('>')
            else raw.replace(Regex("\\s+[\"'][^\\n]*[\"']$"), "").trim().replace(Regex("\\\\([()\\\\ ])"), "$1")
        if (source.isBlank()) { i++; continue }
        val label = text.substring(labelStart, labelEnd).ifBlank { "回复中的图片" }
        val artifact = !imageSyntax && isRemoteFileLink(source) && Regex("(?i)\\.(html?|md|markdown|svg|mermaid|mmd)(?:[?#].*)?$").containsMatchIn(source)
        if (!imageSyntax && !artifact && !Regex("(?i)\\.(png|jpe?g|webp|gif|bmp)(?:[?#].*)?$").containsMatchIn(source)) { i++; continue }
        if (i > start) parts += MessageContent.Text(text.substring(start, i))
        if (artifact) parts += MessageContent.Artifact(source, label) else parts += MessageContent.Image(ChatImage(source, label))
        i = cursor + 1
        start = i
    }
    if (start < text.length) parts += MessageContent.Text(text.substring(start))
    return parts
}

private inline fun String.indexOfFirstFrom(start: Int, predicate: (Char) -> Boolean): Int {
    for (i in start until length) if (predicate(this[i])) return i
    return length
}

suspend fun readChatImage(source: String, cwd: String, readFile: suspend (String) -> ByteArray): ByteArray = withContext(Dispatchers.IO) {
    when {
        source.startsWith("data:") -> {
            val header = source.substringBefore(',')
            require(header.startsWith("data:image/") && header.endsWith(";base64")) { "不支持此内嵌图片格式" }
            val encoded = source.substringAfter(',', "")
            require(encoded.length <= (MAX_CHAT_IMAGE_BYTES.toLong() + 2) / 3 * 4) { "图片超过 8 MB" }
            Base64.getDecoder().decode(encoded)
        }
        source.startsWith("https://") || (source.startsWith("http://") && isLoopbackLink(source)) -> downloadWebResource(source, accept = "image/*")
        source.startsWith("codex-file:") -> error("此图片只有云端文件标识，SSH 接口未提供可读取的图片地址")
        else -> {
            val path = when {
                source.startsWith("file:") -> URI(source).let { require(it.authority.isNullOrBlank()) { "不支持此文件地址" }; it.path }
                source.startsWith("sandbox:") -> source.removePrefix("sandbox:")
                Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(source) -> error("不支持此图片地址；网络图片需要 HTTPS")
                else -> source
            }
            require(path.isNotBlank() && !path.contains('\u0000')) { "图片路径无效" }
            val absolute = if (path.startsWith('/')) path else {
                require(cwd.startsWith('/')) { "无法确定图片所在的项目目录" }
                "$cwd/$path"
            }
            readFile(absolute)
        }
    }.also { require(it.isNotEmpty() && it.size <= MAX_CHAT_IMAGE_BYTES) { "图片为空或超过 8 MB" } }
}

fun downloadWebResource(source: String, maxBytes: Int = MAX_CHAT_IMAGE_BYTES, accept: String = "*/*"): ByteArray {
    var address = URI(source)
    val local = address.scheme == "http" && isLoopbackLink(source)
    val localPort = address.port
    repeat(6) {
        require((address.scheme == "https" && !local || local && address.scheme == "http" && isLoopbackLink(address.toString()) && address.port == localPort) && address.userInfo == null) { "图片跳转到了不支持的地址" }
        val connection = address.toURL().openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10000
            connection.readTimeout = 15000
            connection.setRequestProperty("Accept", accept)
            val code = connection.responseCode
            if (code in listOf(301, 302, 303, 307, 308)) {
                address = address.resolve(connection.getHeaderField("Location") ?: error("图片跳转地址为空"))
            } else {
                require(code == 200) { "资源读取失败（HTTP $code）" }
                require(connection.contentLengthLong <= maxBytes) { "资源超过 ${maxBytes / 1024 / 1024} MB" }
                return connection.inputStream.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        require(output.size().toLong() + n <= maxBytes) { "资源超过 ${maxBytes / 1024 / 1024} MB" }
                        output.write(buffer, 0, n)
                    }
                    output.toByteArray()
                }
            }
        } finally { connection.disconnect() }
    }
    error("图片跳转次数过多")
}
