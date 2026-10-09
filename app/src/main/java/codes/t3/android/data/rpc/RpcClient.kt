package codes.t3.android.data.rpc

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Error surfaced from an RPC `Exit` failure (or a transport failure). */
class RpcException(message: String, val tag: String? = null, val payload: JsonElement? = null) : Exception(message)

/**
 * Minimal client for the Effect RPC JSON-over-WebSocket protocol spoken by the T3 Code server.
 *
 * Frames (one JSON object per text message):
 *  - client → server: `Request`, `Ack` (stream back-pressure), `Interrupt`, `Ping`
 *  - server → client: `Chunk` (stream values), `Exit` (completion), `Pong`, `Defect`, `ClientProtocolError`
 */
class RpcClient(
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
) {
    enum class State { Idle, Connecting, Open, Closed }

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val nextId = AtomicLong(1)
    private val unary = ConcurrentHashMap<String, CompletableDeferred<JsonElement>>()
    private val streams = ConcurrentHashMap<String, Channel<StreamEvent>>()

    private val _state = MutableStateFlow(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile private var socket: WebSocket? = null
    private var openSignal = CompletableDeferred<Unit>()

    /** Called whenever the socket closes; lets the owner schedule reconnects. */
    var onClosed: ((Throwable?) -> Unit)? = null

    private sealed interface StreamEvent {
        data class Values(val values: JsonArray) : StreamEvent
        data class Done(val error: Throwable?) : StreamEvent
    }

    fun connect(url: String, headers: Map<String, String> = emptyMap()) {
        close()
        openSignal = CompletableDeferred()
        _state.value = State.Connecting
        val req = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        socket = http.newWebSocket(req, Listener())
    }

    suspend fun awaitOpen() = openSignal.await()

    fun close() {
        socket?.close(1000, null)
        socket = null
        failAll(RpcException("Connection closed"))
    }

    private fun failAll(error: Throwable) {
        unary.values.forEach { it.completeExceptionally(error) }
        unary.clear()
        streams.values.forEach { it.trySend(StreamEvent.Done(error)) }
        streams.clear()
    }

    private fun send(obj: JsonObject): Boolean = socket?.send(obj.toString()) ?: false

    private fun requestFrame(id: String, tag: String, payload: JsonElement) = buildJsonObject {
        put("_tag", "Request")
        put("id", id)
        put("tag", tag)
        put("payload", payload)
        put("headers", JsonArray(emptyList()))
    }

    /** Unary call: resolves with the `Exit` success value. */
    suspend fun call(tag: String, payload: JsonElement = JsonObject(emptyMap())): JsonElement {
        awaitOpen()
        val id = nextId.getAndIncrement().toString()
        val deferred = CompletableDeferred<JsonElement>()
        unary[id] = deferred
        if (!send(requestFrame(id, tag, payload))) {
            unary.remove(id)
            throw RpcException("Not connected")
        }
        return deferred.await()
    }

    /** Streaming call: emits each chunk value; completes on `Exit`. Cancelling the flow interrupts the stream. */
    fun stream(tag: String, payload: JsonElement = JsonObject(emptyMap())): Flow<JsonElement> = callbackFlow {
        awaitOpen()
        val id = nextId.getAndIncrement().toString()
        val inbox = Channel<StreamEvent>(Channel.UNLIMITED)
        streams[id] = inbox
        if (!this@RpcClient.send(requestFrame(id, tag, payload))) {
            streams.remove(id)
            throw RpcException("Not connected")
        }
        val pump = launch {
            for (event in inbox) {
                when (event) {
                    is StreamEvent.Values -> {
                        event.values.forEach { send(it) }
                        this@RpcClient.send(buildJsonObject { put("_tag", "Ack"); put("requestId", id) })
                    }
                    is StreamEvent.Done -> {
                        close(event.error)
                        return@launch
                    }
                }
            }
        }
        awaitClose {
            pump.cancel()
            if (streams.remove(id) != null) {
                this@RpcClient.send(buildJsonObject { put("_tag", "Interrupt"); put("requestId", id) })
            }
        }
    }

    fun ping() = send(buildJsonObject { put("_tag", "Ping") })

    private fun handle(text: String) {
        val element = runCatching { json.parseToJsonElement(text) }.getOrNull() ?: return
        val frames = if (element is JsonArray) element.map { it.jsonObject } else listOf(element.jsonObject)
        frames.forEach(::handleFrame)
    }

    private fun handleFrame(frame: JsonObject) {
        val requestId = frame["requestId"]?.jsonPrimitive?.content
        when (frame["_tag"]?.jsonPrimitive?.content) {
            "Chunk" -> {
                val values = frame["values"]?.jsonArray ?: return
                streams[requestId]?.trySend(StreamEvent.Values(values))
            }
            "Exit" -> {
                val exit = frame["exit"]?.jsonObject ?: return
                val error = if (exit["_tag"]?.jsonPrimitive?.content == "Success") null else exitError(exit)
                unary.remove(requestId)?.let { d ->
                    if (error == null) d.complete(exit["value"] ?: JsonNull) else d.completeExceptionally(error)
                }
                streams.remove(requestId)?.trySend(StreamEvent.Done(error))
            }
            "Defect", "ClientProtocolError" -> {
                val err = RpcException("Server protocol error: ${frame["defect"] ?: frame["error"] ?: frame}")
                failAll(err)
            }
            else -> Unit // Pong and unknown frames
        }
    }

    private fun exitError(exit: JsonObject): RpcException {
        val failure = findFailure(exit["cause"])
        val tag = (failure as? JsonObject)?.get("_tag")?.jsonPrimitive?.content
        val message = (failure as? JsonObject)?.get("message")?.let { (it as? JsonPrimitive)?.content }
            ?: (failure as? JsonObject)?.get("detail")?.let { (it as? JsonPrimitive)?.content }
            ?: tag
            ?: "Request failed"
        return RpcException(message, tag, failure)
    }

    /** Walk an Effect `Cause` JSON tree to find the first `Fail`/`Die` payload. */
    private fun findFailure(cause: JsonElement?): JsonElement? {
        if (cause == null) return null
        if (cause is JsonArray) return cause.firstNotNullOfOrNull(::findFailure)
        if (cause !is JsonObject) return cause
        return when (cause["_tag"]?.jsonPrimitive?.content) {
            "Fail" -> cause["error"]
            "Die" -> cause["defect"]?.let { d -> if (d is JsonPrimitive) buildJsonObject { put("message", d.content) } else d }
            "Interrupt" -> buildJsonObject { put("message", "Interrupted") }
            else -> cause["failures"]?.let(::findFailure)
                ?: cause["left"]?.let(::findFailure)
                ?: cause["right"]?.let(::findFailure)
                ?: cause
        }
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (webSocket !== socket) return
            _state.value = State.Open
            openSignal.complete(Unit)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (webSocket === socket) handle(text)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = closed(webSocket, null)

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val detail = response?.let { " (HTTP ${it.code})" }.orEmpty()
            closed(webSocket, RpcException((t.message ?: "Connection failed") + detail))
        }

        private fun closed(webSocket: WebSocket, error: Throwable?) {
            if (webSocket !== socket) return
            socket = null
            _state.value = State.Closed
            val err = error ?: RpcException("Connection closed")
            if (!openSignal.isCompleted) openSignal.completeExceptionally(err)
            failAll(err)
            scope.launch { onClosed?.invoke(error) }
        }
    }
}
