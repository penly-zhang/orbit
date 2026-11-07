package dev.codexpocket.core

import java.io.*
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** RFC 6455 over an SSH exec stream carrying `codex app-server proxy`. */
class WebSocketWire(private val input: InputStream, private val output: OutputStream) : Closeable {
    private val random = SecureRandom()
    private val writeLock = Any()
    private val limit = 16 * 1024 * 1024

    fun handshake() {
        val key = Base64.getEncoder().encodeToString(ByteArray(16).also(random::nextBytes))
        val request = "GET / HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: $key\r\n\r\n"
        synchronized(writeLock) { output.write(request.toByteArray(Charsets.US_ASCII)); output.flush() }
        val headers = ByteArrayOutputStream()
        var tail = 0
        while (headers.size() < 16384) {
            val byte = input.read()
            if (byte < 0) throw EOFException("Agent 服务未响应。请确认电脑上的服务已启动，以及可执行文件路径正确。")
            headers.write(byte)
            tail = (tail shl 8) or byte
            if (tail == 0x0d0a0d0a) break
        }
        val lines = headers.toString("US-ASCII").split("\r\n")
        require(lines.first().matches(Regex("HTTP/1\\.[01] 101(?: .*|)"))) { "Agent WebSocket 握手失败" }
        val accept = lines.drop(1).mapNotNull { line -> line.indexOf(':').takeIf { it > 0 }?.let { line.take(it).lowercase() to line.substring(it + 1).trim() } }.toMap()["sec-websocket-accept"]
        val expected = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
        require(accept == expected) { "Agent WebSocket 握手校验失败" }
    }

    fun send(text: String) = frame(1, text.toByteArray(Charsets.UTF_8))

    fun read(): String {
        val message = ByteArrayOutputStream()
        var started = false
        while (true) {
            val first = octet()
            val second = octet()
            require(first and 0x70 == 0) { "Unsupported WebSocket extension" }
            val final = first and 0x80 != 0
            val opcode = first and 15
            require(second and 0x80 == 0) { "Server frames must not be masked" }
            val size = when (val short = second and 127) {
                126 -> (octet().toLong() shl 8) or octet().toLong()
                127 -> { var n = 0L; repeat(8) { n = (n shl 8) or octet().toLong() }; n }
                else -> short.toLong()
            }
            require(size in 0..limit.toLong() && message.size().toLong() + size <= limit) { "消息超过 16 MB，请缩小读取范围" }
            if (opcode >= 8) require(final && size <= 125) { "Invalid WebSocket control frame" }
            val payload = exact(size.toInt())
            when (opcode) {
                8 -> throw EOFException("电脑上的 Agent 连接已关闭")
                9 -> frame(10, payload)
                10 -> Unit
                1, 0 -> {
                    require((opcode == 1 && !started) || (opcode == 0 && started)) { "Invalid WebSocket continuation" }
                    started = true
                    message.write(payload)
                    if (final) return message.toString("UTF-8")
                }
                else -> error("Unsupported WebSocket opcode $opcode")
            }
        }
    }

    private fun frame(opcode: Int, payload: ByteArray) = synchronized(writeLock) {
        require(payload.size <= limit)
        output.write(0x80 or opcode)
        when {
            payload.size < 126 -> output.write(0x80 or payload.size)
            payload.size <= 65535 -> { output.write(0xfe); output.write(payload.size ushr 8); output.write(payload.size) }
            else -> { output.write(0xff); for (i in 7 downTo 0) output.write((payload.size.toLong() ushr (8 * i)).toInt()) }
        }
        val mask = ByteArray(4).also(random::nextBytes)
        output.write(mask)
        output.write(ByteArray(payload.size) { i -> (payload[i].toInt() xor mask[i % 4].toInt()).toByte() })
        output.flush()
    }
    private fun octet(): Int = input.read().also { if (it < 0) throw EOFException("SSH 连接已断开") }
    private fun exact(n: Int): ByteArray = ByteArray(n).also { bytes -> var offset = 0; while (offset < n) { val read = input.read(bytes, offset, n - offset); if (read < 0) throw EOFException(); offset += read } }
    override fun close() { runCatching { input.close() }; runCatching { output.close() } }
}
