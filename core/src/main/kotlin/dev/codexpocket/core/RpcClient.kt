package dev.codexpocket.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class RpcError(val code: Int, override val message: String) : Exception(message)
class RpcClient(private val connection: SshConnection, parentScope: CoroutineScope) : Closeable {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private val sequence = AtomicLong()
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonObject>>()
    private val incoming = MutableSharedFlow<JsonObject>(extraBufferCapacity = 128)
    val events: SharedFlow<JsonObject> = incoming
    private val mutableFailure = MutableStateFlow<String?>(null)
    val failure = mutableFailure.asStateFlow()
    @Volatile private var closed = false
    init {
        scope.launch(Dispatchers.IO) {
            try {
                while (isActive) {
                    val message = json.parseToJsonElement(connection.wire.read()).jsonObject
                    if (message["method"] == null && message["id"] is JsonPrimitive) {
                        val id = message["id"]?.jsonPrimitive?.longOrNull
                        if (id != null) pending.remove(id)?.complete(message)
                    } else incoming.emit(message)
                }
            } catch (error: Exception) {
                if (!closed) mutableFailure.value = error.message ?: "连接中断"
                pending.values.forEach { it.completeExceptionally(error) }; pending.clear()
            } finally { closed = true; connection.close() }
        }
    }
    suspend fun request(method: String, params: JsonObject? = null): JsonObject {
        check(!closed) { "连接已关闭，请重新连接" }
        val id = sequence.incrementAndGet()
        val deferred = CompletableDeferred<JsonObject>()
        pending[id] = deferred
        try {
            write(obj("id" to id, "method" to method).let { if (params != null) JsonObject(it + ("params" to params)) else it })
            val response = withTimeout(30000) { deferred.await() }
            (response["error"] as? JsonObject)?.let { throw RpcError(it["code"]?.jsonPrimitive?.intOrNull ?: -1, it.text("message")) }
            return response["result"] as? JsonObject ?: obj()
        } finally { pending.remove(id) }
    }
    suspend fun notify(method: String, params: JsonObject? = null) = write(obj("method" to method).let { if (params != null) JsonObject(it + ("params" to params)) else it })
    suspend fun answer(id: JsonElement, result: JsonObject) = write(obj("id" to id, "result" to result))
    suspend fun unsupported(id: JsonElement) = write(obj("id" to id, "error" to obj("code" to -32601, "message" to "This client does not support this request")))
    private suspend fun write(message: JsonObject) = withContext(Dispatchers.IO) { connection.wire.send(message.toString()) }
    override fun close() {
        closed = true; connection.close()
        pending.values.forEach { it.completeExceptionally(CancellationException("Disconnected")) }; pending.clear(); scope.cancel()
    }
}
