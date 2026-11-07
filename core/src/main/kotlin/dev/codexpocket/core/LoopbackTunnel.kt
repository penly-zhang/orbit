package dev.codexpocket.core

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.*
import java.net.*
import java.util.concurrent.ConcurrentHashMap

/** Raw loopback forwarding preserves HTTP methods, binary assets and WebSocket upgrades. */
class LoopbackTunnel internal constructor(private val connect: suspend () -> TunnelStream) : Closeable {
    private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val streams = ConcurrentHashMap.newKeySet<TunnelStream>()
    private val permits = Semaphore(8)
    val port: Int get() = server.localPort
    init { scope.launch {
        while (isActive) {
            val socket = try { server.accept() } catch (_: IOException) { break }
            if (sockets.size >= 32) { socket.close(); continue }
            sockets += socket
            launch { try { permits.withPermit {
                val stream = connect(); streams += stream
                try { coroutineScope {
                    val outgoing = launch { try { pump(socket.getInputStream(), stream.output) } finally { runCatching { stream.output.close() } } }
                    try { pump(stream.input, socket.getOutputStream()) } finally { socket.close(); outgoing.cancel() }
                } } finally { streams -= stream; stream.close() }
            } } catch (_: Exception) { } finally { sockets -= socket; runCatching { socket.close() } } }
        }
    } }
    override fun close() { runCatching { server.close() }; sockets.forEach { runCatching { it.close() } }; streams.forEach { runCatching { it.close() } }; scope.cancel() }
    private fun pump(input: InputStream, output: OutputStream) {
        val bytes = ByteArray(16384)
        while (true) { val n = input.read(bytes); if (n < 0) break; output.write(bytes, 0, n); output.flush() }
    }
}
internal class TunnelStream(val input: InputStream, val output: OutputStream, private val stop: () -> Unit) : Closeable { override fun close() = stop() }
