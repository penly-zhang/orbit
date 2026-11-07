package dev.codexpocket

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.staticCompositionLocalOf
import dev.codexpocket.core.*
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

internal val LocalDocumentPicker = staticCompositionLocalOf<(String, Int) -> Unit> { { _, _ -> } }

internal suspend fun prepareContextFile(context: Context, uri: Uri): ContextInput = withContext(Dispatchers.IO) {
    var created: File? = null
    try {
        require(uri.scheme in setOf("content", "file")) { "不支持此附件来源" }
        val name = runCatching { context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } }.getOrNull()
            ?.substringAfterLast('/')?.substringAfterLast('\\')?.filterNot(Char::isISOControl)?.take(120)?.takeIf(String::isNotBlank) ?: "attachment.txt"
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) { ensureActive(); val n = input.read(buffer); if (n < 0) break
                require(out.size() + n <= MAX_ATTACHMENT_BYTES) { "$name 超过 4 MB" }; out.write(buffer, 0, n) }
            out.toByteArray()
        } ?: error("无法读取 $name")
        require(bytes.isNotEmpty()) { "$name 是空文件" }
        val dir = File(context.filesDir, "composer-files").apply { require(isDirectory || mkdirs()) { "无法保存附件草稿" } }
        val id = UUID.randomUUID().toString(); val file = File(dir, id); created = file
        file.writeBytes(bytes)
        ContextInput(id, name, localPath = file.absolutePath, bytes = bytes.size)
    } catch (e: Exception) { created?.delete(); throw e }
}
