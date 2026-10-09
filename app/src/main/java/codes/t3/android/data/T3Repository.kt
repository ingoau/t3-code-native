package codes.t3.android.data

import android.os.Build
import codes.t3.android.data.model.ProjectShell
import codes.t3.android.data.model.ServerConfig
import codes.t3.android.data.model.ThreadShell
import codes.t3.android.data.pairing.PairingTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** A thread row joined with its project and environment, ready for list UIs. */
data class ThreadEntry(
    val environmentId: String,
    val environmentLabel: String,
    val thread: ThreadShell,
    val project: ProjectShell?,
)

data class ProjectEntry(val environmentId: String, val environmentLabel: String, val project: ProjectShell)

/** Snapshot of one environment for the UI. */
data class EnvironmentSnapshot(
    val saved: SavedEnvironment,
    val status: ConnectionStatus,
    val config: ServerConfig?,
    val shellLoaded: Boolean,
)

/** App-wide owner of paired environments and their live connections. */
class T3Repository(
    private val environments: EnvironmentRepository,
    private val scope: CoroutineScope,
    val http: OkHttpClient = defaultHttpClient(),
) {
    val api = ServerApi(http)
    private val mutex = Mutex()
    private val _connections = MutableStateFlow<Map<String, EnvironmentConnection>>(emptyMap())
    val connections: StateFlow<Map<String, EnvironmentConnection>> = _connections.asStateFlow()

    val savedEnvironments: StateFlow<List<SavedEnvironment>?> =
        environments.environments.stateIn(scope, SharingStarted.Eagerly, null)

    init {
        scope.launch { environments.environments.collect { sync(it) } }
    }

    private suspend fun sync(saved: List<SavedEnvironment>) {
        val retired = mutableListOf<EnvironmentConnection>()
        mutex.withLock {
            val current = _connections.value.toMutableMap()
            val wanted = saved.filter { it.enabled }.associateBy { it.environmentId }
            (current.keys - wanted.keys).forEach { id -> current.remove(id)?.let(retired::add) }
            wanted.forEach { (id, env) ->
                val existing = current[id]
                if (existing != null && existing.environment.httpBaseUrl == env.httpBaseUrl && existing.environment.encryptedToken == env.encryptedToken) {
                    existing.rename(env)
                } else {
                    existing?.let(retired::add)
                    // A token that can't be decrypted (e.g. restored onto a new device) yields a Blocked connection
                    // that asks the user to pair again, rather than silently disappearing.
                    val token = runCatching { environments.cipher.decrypt(env.encryptedToken) }.getOrNull()
                    current[id] = EnvironmentConnection(env, token, http, api, scope).also { it.start() }
                }
            }
            _connections.value = current
        }
        // Stopping can wait on in-flight network calls; don't hold up other environment changes for it.
        retired.forEach { conn -> scope.launch { conn.stop() } }
    }

    fun connection(environmentId: String): EnvironmentConnection? = _connections.value[environmentId]

    val environmentSnapshots: Flow<List<EnvironmentSnapshot>> =
        combine(savedEnvironments, _connections) { saved, conns -> saved.orEmpty() to conns }
            .flatMapLatest { (saved, conns) ->
                if (saved.isEmpty()) return@flatMapLatest flowOf(emptyList())
                val flows = saved.map { env ->
                    val conn = conns[env.environmentId]
                    if (conn == null) flowOf(EnvironmentSnapshot(env, ConnectionStatus.Disabled, null, false))
                    else combine(conn.status, conn.config, conn.shell) { status, config, shell ->
                        EnvironmentSnapshot(conn.environment.copy(enabled = env.enabled, label = env.label), status, config, shell.loaded)
                    }
                }
                combine(flows) { it.toList() }
            }

    /** All threads across enabled environments. */
    val threads: Flow<List<ThreadEntry>> = _connections.flatMapLatest { conns ->
        if (conns.isEmpty()) return@flatMapLatest flowOf(emptyList())
        combine(conns.values.map { conn ->
            combine(conn.shell, savedEnvironments) { shell, _ ->
                shell.threads.values.map { ThreadEntry(conn.environmentId, conn.environment.label, it, shell.projects[it.projectId]) }
            }
        }) { lists -> lists.flatMap { it } }
    }

    val projects: Flow<List<ProjectEntry>> = _connections.flatMapLatest { conns ->
        if (conns.isEmpty()) return@flatMapLatest flowOf(emptyList())
        combine(conns.values.map { conn ->
            combine(conn.shell, savedEnvironments) { shell, _ ->
                shell.projects.values.map { ProjectEntry(conn.environmentId, conn.environment.label, it) }
            }
        }) { lists -> lists.flatMap { it } }
    }

    /** Validate, exchange the one-time code, and save. Returns the saved environment. */
    suspend fun pair(target: PairingTarget): SavedEnvironment {
        val token = target.token ?: throw ServerApiException("Enter the pairing code shown by `t3 pair`.")
        val descriptor = api.descriptor(target.httpBaseUrl)
        val access = api.exchangePairingToken(target.httpBaseUrl, token, "T3 Code Android (${Build.MODEL ?: "device"})")
        val existing = savedEnvironments.value.orEmpty().firstOrNull { it.environmentId == descriptor.environmentId }
        val saved = SavedEnvironment(
            environmentId = descriptor.environmentId,
            label = existing?.label ?: descriptor.label.ifBlank { target.httpBaseUrl.substringAfter("://").trimEnd('/') },
            httpBaseUrl = target.httpBaseUrl,
            encryptedToken = environments.cipher.encrypt(access.access_token),
        )
        environments.upsert(saved)
        return saved
    }

    suspend fun setEnabled(environmentId: String, enabled: Boolean) =
        environments.update(environmentId) { it.copy(enabled = enabled) }

    suspend fun rename(environmentId: String, label: String) =
        environments.update(environmentId) { it.copy(label = label.trim().ifEmpty { it.label }) }

    suspend fun remove(environmentId: String) = environments.remove(environmentId)

    suspend fun awaitLoaded() = savedEnvironments.first { it != null }

    fun reconnectAll() = _connections.value.values.forEach { it.reconnectNow() }
    fun probeAll() = _connections.value.values.forEach { it.probe() }

    companion object {
        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
