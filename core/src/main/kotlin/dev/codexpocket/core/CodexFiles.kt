package dev.codexpocket.core

import kotlinx.serialization.json.*
import java.util.Base64

internal class CodexFiles(private val rpc: RpcClient, private val ssh: SshConnection) : RemoteFiles {
    override suspend fun files(path: String): List<FileEntry> = rpc.request("fs/readDirectory", obj("path" to path)).array("entries").mapNotNull {
        (it as? JsonObject)?.let { entry -> FileEntry(entry.text("fileName"), entry["isDirectory"]?.jsonPrimitive?.booleanOrNull == true) }
    }.filter { it.name != "." && it.name != ".." && !it.name.contains('/') }.sortedWith(compareByDescending<FileEntry> { it.directory }.thenBy { it.name.lowercase() })
    override suspend fun file(path: String, maxBytes: Int): FilePreview {
        // Check the size over SSH before loading the file via RPC.
        val sizeResult = ssh.execute("wc -c < " + shellQuote(path), 4096)
        require(sizeResult.code == 0) { "无法读取文件大小：${sizeResult.stderr.take(200)}" }
        val size = sizeResult.stdout.trim().toLongOrNull() ?: error("无法读取文件大小")
        require(size in 0..maxBytes.toLong()) { "文件超过 ${maxBytes / 1024 / 1024} MB，无法预览" }
        val result = rpc.request("fs/readFile", obj("path" to path))
        val encoded = result.text("dataBase64")
        require(encoded.length <= ((maxBytes.toLong() + 2) / 3 * 4)) { "文件大小在读取期间发生变化，超过预览限制" }
        val bytes = Base64.getDecoder().decode(encoded)
        require(bytes.size <= maxBytes) { "文件超过预览限制" }
        return FilePreview(path, bytes)
    }

}
