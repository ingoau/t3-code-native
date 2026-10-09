package codes.t3.android.data

import android.os.Build
import android.util.Log
import codes.t3.android.BuildConfig
import codes.t3.android.data.model.ServerConfig
import codes.t3.android.data.model.T3Json
import codes.t3.android.data.rpc.RpcClient
import codes.t3.android.data.rpc.RpcException
import codes.t3.android.data.state.ShellState
import codes.t3.android.data.state.ThreadState
import codes.t3.android.data.v1.V1ThreadState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
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

private const val TAG = "T3Connection"

/** Backstop so a bug in one background job is logged instead of taking the whole app down. */
val LoggingExceptionHandler = CoroutineExceptionHandler { _, e -> Log.w(TAG, "Unhandled error in background job", e) }

/** `withTimeout` throws a CancellationException, which would silently end loops that rethrow cancellation. */
private suspend fun <T> withTimeoutOrFail(ms: Long, what: String, block: suspend () -> T): T =
    withTimeoutOrNull(ms) { block() } ?: throw RpcException("Timed out $what")

/**
 * One live connection to a paired T3 Code server: authorizes, opens the RPC socket, keeps the server config and the
 * shell (projects + threads) in sync, reconnects with backoff, and lets screens subscribe to thread detail.
 *
 * [accessToken] is null when the stored token can't be decrypted (e.g. restored onto a new device); the connection
 * then stays [ConnectionStatus.Blocked] until the device is paired again.
 */
class EnvironmentConnection(
    initial: SavedEnvironment,
    private val accessToken: String?,
    private val http: OkHttpClient,
    private val api: ServerApi,
    parentScope: CoroutineScope,
) {
    private val scope = CoroutineScope(
        parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]) + LoggingExceptionHandler,
    )

    @Volatile var environment: SavedEnvironment = initial
        private set
    val environmentId: String get() = environment.environmentId

    private val _status = MutableStateFlow<ConnectionStatus>(
        if (accessToken == null) ConnectionStatus.Blocked("This device's saved credentials can't be read. Pair it again.") else ConnectionStatus.Connecting,
    )
    val status: StateFlow<ConnectionStatus> = _status.asStateFlow()

    private val _config = MutableStateFlow<ServerConfig?>(null)
    val config: StateFlow<ServerConfig?> = _config.asStateFlow()

    private val _shell = MutableStateFlow(ShellState())
    val shell: StateFlow<ShellState> = _shell.asStateFlow()

    /** Orchestration protocol spoken by the server, learned from its descriptor on each connect. */
    @Volatile var protocol: Int = ORCHESTRATION_PROTOCOL
        private set

    /** Command builders matching [protocol]. */
    val commands: ProtocolCommands
        get() = if (protocol == 1) V1Commands
        else V2Commands(_config.value?.environment?.capability("serverResolvedCommandContext") == true)

    /** The RPC session once it is ready (config snapshot received), else null. */
    private val session = MutableStateFlow<RpcClient?>(null)

    private val threadCache = ConcurrentHashMap<String, ThreadState>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    /** Nudges thread subscriptions that gave up (or are backing off) to try again now. */
    private val threadRetry = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    @Volatile private var loop: Job? = null
    @Volatile private var stopped = false

    fun start() {
        if (stopped || accessToken == null || loop?.isActive == true) return
        loop = scope.launch { runLoop() }
    }

    suspend fun stop() {
        stopped = true
        loop?.cancelAndJoin()
        loop = null
        session.value?.close()
        session.value = null
        _status.value = ConnectionStatus.Disabled
        scope.cancel()
    }

    fun rename(env: SavedEnvironment) { environment = env }

    /** Skip any pending backoff (app foregrounded, network back, user tapped retry). */
    fun reconnectNow() {
        if (stopped || accessToken == null) return
        threadRetry.tryEmit(Unit)
        if (_status.value is ConnectionStatus.Blocked || loop?.isActive != true) {
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
        val light = _config.value?.environment?.capability("connectionProbe") == true
        scope.launch {
            val ok = withTimeoutOrNull(if (light) 6_000 else 12_000) {
                runCatching { rpc.call(if (light) "server.probe" else "server.getConfig") }.isSuccess
            }
            if (ok != true) rpc.close()
        }
    }

    private suspend fun runLoop() {
        var failures = 0
        while (true) {
            val startedAt = System.currentTimeMillis()
            var error: Throwable? = null
            try {
                if (failures == 0) _status.value = ConnectionStatus.Connecting
                runSession()
            } catch (e: CancellationException) {
                // Only a real cancellation (stop()) ends the loop; stray timeouts are ordinary failures.
                if (!currentCoroutineContext().isActive) throw e
                error = e
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
        val token = accessToken ?: throw ServerApiException("Pair this device again.", blocked = true)
        val descriptor = api.descriptor(env.httpBaseUrl)
        if (descriptor.environmentId != env.environmentId) {
            throw ServerApiException("A different T3 Code server now answers at ${env.displayHost}.", blocked = true)
        }
        val serverProtocol = descriptor.orchestrationProtocolVersion ?: 1
        if (serverProtocol != protocol || _shell.value.protocol != serverProtocol) {
            // Server was upgraded/downgraded: drop cached state from the other protocol.
            protocol = serverProtocol
            _shell.value = ShellState(protocol = serverProtocol)
            threadCache.clear()
        }
        val ticket = api.webSocketTicket(env.httpBaseUrl, token)
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
            .addQueryParameter("orchestrationProtocol", serverProtocol.toString())
            .build()
            .toString()
            .replaceFirst("http", "ws")

        val closed = CompletableDeferred<Throwable?>()
        val rpc = RpcClient(http, scope)
        rpc.onClosed = { closed.complete(it) }
        rpc.connect(wsUrl)
        val sessionJobs = mutableListOf<Job>()
        try {
            withTimeoutOrFail(15_000, "opening the connection") { rpc.awaitOpen() }

            // Keepalive: JSON Ping every 5 s; no Pong since the last one means the socket is dead.
            sessionJobs += scope.launch {
                while (true) {
                    delay(5_000)
                    if (!rpc.pongSeen) { closed.complete(RpcException("Connection timed out")); rpc.close(); break }
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
            val config = withTimeoutOrFail(30_000, "waiting for the server config") { firstConfig.await() }
            if (config.environment.environmentId != env.environmentId) {
                throw ServerApiException("Environment mismatch", blocked = true)
            }

            sessionJobs += scope.launch { subscribeShell(rpc, config, closed) }
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
        runCatching {
            when ((obj["type"] as? JsonPrimitive)?.contentOrNull) {
                "snapshot" -> {
                    val config = T3Json.decodeFromJsonElement<ServerConfig>(obj["config"] ?: return)
                    _config.value = config
                    first.complete(config)
                }
                "providerStatuses" -> {
                    val providers = (obj["payload"] as? JsonObject)?.get("providers") ?: return
                    _config.update { it?.copy(providers = T3Json.decodeFromJsonElement(providers)) }
                }
                "settingsUpdated" -> {
                    val settings = (obj["payload"] as? JsonObject)?.get("settings") ?: return
                    _config.update { it?.copy(settings = T3Json.decodeFromJsonElement(settings)) }
                }
            }
        }.onFailure { e ->
            Log.w(TAG, "Couldn't decode server config event", e)
            if (!first.isCompleted) first.completeExceptionally(RpcException("Couldn't read the server config: ${e.message}"))
        }
    }

    /** Keeps the shell in sync; never throws (errors that end the session complete [closed]). */
    private suspend fun subscribeShell(rpc: RpcClient, config: ServerConfig, closed: CompletableDeferred<Throwable?>) {
        var backoff = 250L
        while (currentCoroutineContext().isActive) {
            try {
                val payload = buildJsonObject {
                    val seq = _shell.value.sequence
                    if (seq >= 0) put("afterSequence", seq)
                    if (config.shellResumeCompletionMarker == true) put("requestCompletionMarker", true)
                }
                rpc.stream("orchestration.subscribeShell", payload).collect { item ->
                    runCatching { _shell.update { it.apply(item) } }
                        .onFailure { Log.w(TAG, "Skipping undecodable shell item", it) }
                    backoff = 250L
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val fatal = (e as? RpcException)?.tag == "EnvironmentAuthorizationError"
                if (fatal || rpc.state.value != RpcClient.State.Open) {
                    closed.complete(e)
                    return
                }
            }
            delay(backoff)
            backoff = min(backoff * 2, 30_000)
        }
    }

    /** Live thread detail. Survives reconnects (resubscribing with `afterSequence`) and protocol changes. */
    /** Last known detail for a thread, if it has been opened during this session. */
    fun cachedThread(threadId: String): ThreadState? = threadCache[threadId]

    fun observeThread(threadId: String): Flow<ThreadState> = channelFlow {
        val state = MutableStateFlow(threadCache[threadId] ?: ThreadState())
        launch {
            session.collectLatest { rpc ->
                if (rpc == null) return@collectLatest
                // Decided per session: the protocol is only known once the server's descriptor has been read.
                if (protocol == 1) followV1(rpc, threadId) { state.value = it } else followV2(rpc, threadId, state)
            }
        }
        state.collect {
            threadCache[threadId] = it
            send(it)
        }
    }.flowOn(Dispatchers.Default)

    /** Ask thread subscriptions to retry immediately (e.g. the user tapped Retry). */
    fun retryThreads() { threadRetry.tryEmit(Unit) }

    private suspend fun waitForRetry(ms: Long) { withTimeoutOrNull(ms) { threadRetry.first() } }

    private suspend fun followV2(rpc: RpcClient, threadId: String, state: MutableStateFlow<ThreadState>) {
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
            } catch (e: Throwable) {
                if (rpc.state.value != RpcClient.State.Open) return
                state.update { it.copy(error = (e as? RpcException)?.message ?: "Couldn't read this thread: ${e.message}") }
                // Auth errors won't fix themselves; wait for an explicit retry instead of hammering the server.
                if ((e as? RpcException)?.tag == "EnvironmentAuthorizationError") { threadRetry.first(); backoff = 250L; continue }
            }
            waitForRetry(backoff)
            backoff = min(backoff * 2, 15_000)
        }
    }

    private suspend fun followV1(rpc: RpcClient, threadId: String, emit: (ThreadState) -> Unit) {
        var state = V1ThreadState()
        var backoff = 250L
        while (true) {
            try {
                val payload = buildJsonObject {
                    put("threadId", threadId)
                    if (state.sequence >= 0) put("afterSequence", state.sequence)
                    if (_config.value?.threadResumeCompletionMarker == true) put("requestCompletionMarker", true)
                    put("reasoningMessages", true)
                }
                rpc.stream("orchestration.subscribeThread", payload).collect { item ->
                    state = state.apply(item).copy(error = null)
                    emit(state.toThreadState())
                    backoff = 250L
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (rpc.state.value != RpcClient.State.Open) return
                // A brand-new thread may not exist yet ("not found"): keep retrying quietly until it does.
                if (state.thread != null) {
                    state = state.copy(error = (e as? RpcException)?.message ?: e.message)
                    emit(state.toThreadState())
                }
                if ((e as? RpcException)?.tag == "EnvironmentAuthorizationError") { threadRetry.first(); backoff = 250L; continue }
            }
            waitForRetry(backoff)
            backoff = min(backoff * 2, 5_000)
        }
    }

    suspend fun awaitSession(): RpcClient = session.filterNotNull().first()

    suspend fun call(tag: String, payload: JsonElement = JsonObject(emptyMap())): JsonElement {
        val rpc = session.value ?: withTimeoutOrFail(10_000, "waiting for the connection") { awaitSession() }
        return rpc.call(tag, payload)
    }

    suspend fun dispatch(command: JsonObject): JsonElement = call("orchestration.dispatchCommand", command)

    suspend fun run(op: Op): JsonElement = call(op.method, op.payload)
}
