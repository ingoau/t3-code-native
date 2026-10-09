package codes.t3.android.data

import android.os.Build
import codes.t3.android.BuildConfig
import codes.t3.android.data.model.ServerConfig
import codes.t3.android.data.model.T3Json
import codes.t3.android.data.rpc.RpcClient
import codes.t3.android.data.rpc.RpcException
import codes.t3.android.data.state.ShellState
import codes.t3.android.data.state.ThreadState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min
import kotlin.random.Random

sealed interface ConnectionStatus {
    data object Connecting : ConnectionStatus
    data object Connected : ConnectionStatus
    data class Reconnecting(val error: String?, val retryInMs: Long) : ConnectionStatus
    /** Won't retry on its own: auth revoked, wrong machine, incompatible protocol. */
    data class Blocked(val error: String) : ConnectionStatus
    data object Disabled : ConnectionStatus
}

/**
 * One live connection to a paired T3 Code server: authorizes, opens the RPC socket, keeps the server config and the
 * shell (projects + threads) in sync, reconnects with backoff, and lets screens subscribe to thread detail.
 */
class EnvironmentConnection(
    initial: SavedEnvironment,
    private val accessToken: String,
    private val http: OkHttpClient,
    private val api: ServerApi,
    parentScope: CoroutineScope,
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))

    @Volatile var environment: SavedEnvironment = initial
        private set
    val environmentId: String get() = environment.environmentId

    private val _status = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Connecting)
    val status: StateFlow<ConnectionStatus> = _status.asStateFlow()

    private val _config = MutableStateFlow<ServerConfig?>(null)
    val config: StateFlow<ServerConfig?> = _config.asStateFlow()

    private val _shell = MutableStateFlow(ShellState())
    val shell: StateFlow<ShellState> = _shell.asStateFlow()

    /** The RPC session once it is ready (config snapshot received), else null. */
    private val session = MutableStateFlow<RpcClient?>(null)

    private val threadCache = ConcurrentHashMap<String, ThreadState>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var loop: Job? = null

    fun start() {
        if (loop?.isActive == true) return
        loop = scope.launch { runLoop() }
    }

    suspend fun stop() {
        loop?.cancelAndJoin()
        loop = null
        session.value?.close()
        session.value = null
        _status.value = ConnectionStatus.Disabled
    }

    fun rename(env: SavedEnvironment) { environment = env }

    /** Skip any pending backoff (app foregrounded, network back, user tapped retry). */
    fun reconnectNow() {
        if (_status.value is ConnectionStatus.Blocked) {
            loop?.cancel()
            loop = null
            _status.value = ConnectionStatus.Connecting
            start()
        } else {
            wake.trySend(Unit)
        }
    }

    /** Quick liveness check after returning to the foreground. */
    fun probe() {
        val rpc = session.value ?: return reconnectNow()
        scope.launch {
            runCatching { withTimeout(4_000) { rpc.call("server.getConfig") } }.onFailure { rpc.close() }
        }
    }

    private suspend fun runLoop() {
        var failures = 0
        while (true) {
            val startedAt = System.currentTimeMillis()
            var error: Throwable? = null
            try {
                _status.value = if (failures == 0) ConnectionStatus.Connecting else _status.value
                runSession()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = e
            }
            session.value = null
            if (error is ServerApiException && error.blocked) {
                _status.value = ConnectionStatus.Blocked(error.message ?: "Connection blocked")
                return
            }
            if (System.currentTimeMillis() - startedAt > 30_000) failures = 0
            val ceiling = min(300_000L, 1000L * (1L shl min(failures + 1, 20)))
            val wait = ceiling / 2 + (Random.nextDouble() * ceiling / 2).toLong()
            failures++
            _status.value = ConnectionStatus.Reconnecting(error?.let { describe(it) }, wait)
            select {
                wake.onReceive { }
                onTimeout(wait) { }
            }
            _status.value = ConnectionStatus.Connecting
        }
    }

    private fun describe(e: Throwable): String = when (e) {
        is ServerApiException -> e.message ?: "Connection failed"
        is RpcException -> e.message ?: "Connection lost"
        else -> friendlyNetworkError(e)
    }

    /** One socket lifetime. Returns/throws when the socket closes. */
    private suspend fun runSession() {
        val env = environment
        val descriptor = api.descriptor(env.httpBaseUrl)
        if (descriptor.environmentId != env.environmentId) {
            throw ServerApiException("A different T3 Code server now answers at ${env.displayHost}.", blocked = true)
        }
        val ticket = api.webSocketTicket(env.httpBaseUrl, accessToken)
        val url = env.httpBaseUrl.trimEnd('/') + "/ws"
        val wsUrl = url.toHttpUrl().newBuilder()
            .addQueryParameter("wsTicket", ticket.ticket)
            .addQueryParameter("clientSurface", "mobile")
            .addQueryParameter("clientAppVersion", BuildConfig.VERSION_NAME)
            .addQueryParameter("clientDeviceType", "phone")
            .addQueryParameter("clientOs", "Android")
            .addQueryParameter("clientOsMajorVersion", Build.VERSION.RELEASE?.substringBefore('.') ?: "0")
            .addQueryParameter("clientDeviceModel", (Build.MODEL ?: "Android").take(80))
            .addQueryParameter("connectionMethod", "direct")
            .addQueryParameter("orchestrationProtocol", ORCHESTRATION_PROTOCOL.toString())
            .build()
            .toString()
            .replaceFirst("http", "ws")

        val closed = CompletableDeferred<Throwable?>()
        val rpc = RpcClient(http, scope)
        rpc.onClosed = { closed.complete(it) }
        rpc.connect(wsUrl)
        val sessionJobs = mutableListOf<Job>()
        try {
            withTimeout(15_000) { rpc.awaitOpen() }

            // Keepalive: JSON Ping every 5 s; no Pong since the last one means the socket is dead.
            sessionJobs += scope.launch {
                while (true) {
                    delay(5_000)
                    if (!rpc.pongSeen) { rpc.close(); closed.complete(RpcException("Connection timed out")); break }
                    rpc.ping()
                }
            }

            val firstConfig = CompletableDeferred<ServerConfig>()
            sessionJobs += scope.launch {
                try {
                    rpc.stream("subscribeServerConfig", JsonObject(emptyMap())).collect { item -> handleConfigEvent(item, firstConfig) }
                    closed.complete(RpcException("Server config stream ended"))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    firstConfig.completeExceptionally(e)
                    closed.complete(e)
                }
            }
            val config = withTimeout(15_000) { firstConfig.await() }
            if (config.environment.environmentId != env.environmentId) {
                throw ServerApiException("Environment mismatch", blocked = true)
            }

            sessionJobs += scope.launch { subscribeShell(rpc, config) }
            session.value = rpc
            _status.value = ConnectionStatus.Connected
            closed.await()?.let { throw it }
        } finally {
            session.value = null
            sessionJobs.forEach { it.cancel() }
            rpc.close()
        }
    }

    private fun handleConfigEvent(item: JsonElement, first: CompletableDeferred<ServerConfig>) {
        val obj = item as? JsonObject ?: return
        when ((obj["type"] as? JsonPrimitive)?.contentOrNull) {
            "snapshot" -> {
                val config = T3Json.decodeFromJsonElement<ServerConfig>(obj["config"] ?: return)
                _config.value = config
                first.complete(config)
            }
            "providerStatuses" -> {
                val providers = obj["payload"]?.jsonObject?.get("providers") ?: return
                _config.update { it?.copy(providers = T3Json.decodeFromJsonElement(providers)) }
            }
            "settingsUpdated" -> {
                val settings = obj["payload"]?.jsonObject?.get("settings") ?: return
                _config.update { it?.copy(settings = T3Json.decodeFromJsonElement(settings)) }
            }
        }
    }

    private suspend fun subscribeShell(rpc: RpcClient, config: ServerConfig) {
        var backoff = 250L
        while (true) {
            try {
                val payload = buildJsonObject {
                    val seq = _shell.value.sequence
                    if (seq >= 0) put("afterSequence", seq)
                    if (config.shellResumeCompletionMarker == true) put("requestCompletionMarker", true)
                }
                rpc.stream("orchestration.subscribeShell", payload).collect { item ->
                    _shell.update { it.apply(item) }
                    backoff = 250L
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: RpcException) {
                if (e.tag == "EnvironmentAuthorizationError" || rpc.state.value != RpcClient.State.Open) throw e
            }
            delay(backoff)
            backoff = min(backoff * 2, 30_000)
        }
    }

    /** Live thread detail. Survives reconnects by resubscribing with `afterSequence`. */
    fun observeThread(threadId: String): Flow<ThreadState> = channelFlow {
        val state = MutableStateFlow(threadCache[threadId] ?: ThreadState())
        launch {
            session.collectLatest { rpc ->
                if (rpc == null) return@collectLatest
                var backoff = 250L
                while (true) {
                    try {
                        val payload = buildJsonObject {
                            put("threadId", threadId)
                            val seq = state.value.sequence
                            if (seq >= 0) put("afterSequence", seq)
                            if (_config.value?.threadResumeCompletionMarker == true) put("requestCompletionMarker", true)
                        }
                        rpc.stream("orchestration.subscribeThread", payload).collect { item ->
                            state.update { it.apply(item).copy(error = null) }
                            backoff = 250L
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: RpcException) {
                        if (rpc.state.value != RpcClient.State.Open) return@collectLatest
                        state.update { it.copy(error = e.message) }
                        if (e.tag == "EnvironmentAuthorizationError" || e.tag == "OrchestrationV2GetThreadProjectionError") return@collectLatest
                    }
                    delay(backoff)
                    backoff = min(backoff * 2, 30_000)
                }
            }
        }
        state.collect {
            threadCache[threadId] = it
            send(it)
        }
    }

    suspend fun awaitSession(): RpcClient = session.filterNotNull().first()

    suspend fun call(tag: String, payload: JsonElement = JsonObject(emptyMap())): JsonElement {
        val rpc = session.value ?: withTimeout(10_000) { awaitSession() }
        return rpc.call(tag, payload)
    }

    suspend fun dispatch(command: JsonObject): JsonElement = call("orchestration.dispatchCommand", command)
}
