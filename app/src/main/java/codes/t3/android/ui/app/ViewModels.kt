package codes.t3.android.ui.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import codes.t3.android.data.AppSettings
import codes.t3.android.data.AppSettingsRepository
import codes.t3.android.data.Commands
import codes.t3.android.data.ConnectionStatus
import codes.t3.android.data.EnvironmentConnection
import codes.t3.android.data.FollowUpBehavior
import codes.t3.android.data.ProjectEntry
import codes.t3.android.data.T3Repository
import codes.t3.android.data.ThreadEntry
import codes.t3.android.data.model.ModelSelection
import codes.t3.android.data.model.RuntimeMode
import codes.t3.android.data.model.ServerConfig
import codes.t3.android.data.model.ServerProvider
import codes.t3.android.data.model.T3Json
import codes.t3.android.data.model.TurnItem
import codes.t3.android.data.pairing.PairingTarget
import codes.t3.android.data.rpc.RpcException
import codes.t3.android.data.state.ThreadState
import codes.t3.android.ui.home.HomeUiState
import codes.t3.android.ui.home.ThreadAction
import codes.t3.android.ui.newthread.BranchRef
import codes.t3.android.ui.newthread.NewThreadUiState
import codes.t3.android.ui.newthread.WorkspaceMode
import codes.t3.android.ui.thread.ComposerModel
import codes.t3.android.ui.thread.SendMode
import codes.t3.android.ui.thread.ThreadUiState
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.util.UUID
import androidx.compose.ui.graphics.asImageBitmap

/** User-visible one-off messages (snackbars). */
object UiEvents {
    private val channel = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = channel.receiveAsFlow()
    fun show(message: String) { channel.trySend(message) }
}

fun errorText(e: Throwable): String = when (e) {
    is RpcException -> e.message ?: "Request failed"
    else -> e.message ?: e::class.java.simpleName
}

fun threadActionOp(commands: codes.t3.android.data.ProtocolCommands, threadId: String, action: ThreadAction): codes.t3.android.data.Op? = when (action) {
    ThreadAction.Pin -> commands.housekeeping(threadId, codes.t3.android.data.ThreadOp.Pin)
    ThreadAction.Unpin -> commands.housekeeping(threadId, codes.t3.android.data.ThreadOp.Unpin)
    ThreadAction.Settle -> commands.housekeeping(threadId, codes.t3.android.data.ThreadOp.Settle)
    ThreadAction.Unsettle -> commands.housekeeping(threadId, codes.t3.android.data.ThreadOp.Unsettle)
    ThreadAction.Archive -> commands.housekeeping(threadId, codes.t3.android.data.ThreadOp.Archive)
    ThreadAction.Delete -> commands.housekeeping(threadId, codes.t3.android.data.ThreadOp.Delete)
    ThreadAction.MarkUnread -> commands.housekeeping(threadId, codes.t3.android.data.ThreadOp.MarkUnread)
    is ThreadAction.Rename -> commands.rename(threadId, action.title)
}

private fun actionVerb(action: ThreadAction) = when (action) {
    ThreadAction.Pin -> "pin"; ThreadAction.Unpin -> "unpin"; ThreadAction.Settle -> "settle"; ThreadAction.Unsettle -> "un-settle"
    ThreadAction.Archive -> "archive"; ThreadAction.Delete -> "delete"; ThreadAction.MarkUnread -> "mark"; is ThreadAction.Rename -> "rename"
}

fun ServerConfig?.selectableProviders(): List<ServerProvider> = this?.providers.orEmpty().filter { it.selectable && it.models.isNotEmpty() }

/** A sensible default model when nothing is configured: the provider's default model on the first ready provider. */
fun ServerConfig?.defaultSelection(projectDefault: ModelSelection? = null): ModelSelection? {
    val providers = selectableProviders()
    fun valid(s: ModelSelection?) = s?.takeIf { sel -> providers.any { p -> p.instanceId == sel.instanceId && p.models.any { it.slug == sel.model } } }
    valid(projectDefault)?.let { return it }
    valid(this?.settings?.defaultModelSelection)?.let { return it }
    val p = providers.firstOrNull { it.status == "ready" } ?: providers.firstOrNull() ?: return null
    val m = p.models.firstOrNull { it.isDefault == true } ?: p.models.first()
    return ModelSelection(p.instanceId, m.slug)
}

class AppViewModel(val repository: T3Repository, private val settingsRepo: AppSettingsRepository) : ViewModel() {
    val settings: StateFlow<AppSettings?> = settingsRepo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val environments = repository.environmentSnapshots.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val home: StateFlow<HomeUiState> = combine(
        repository.savedEnvironments, environments, repository.threads, repository.projects, settingsRepo.settings,
    ) { saved, envs, threads, projects, settings ->
        HomeUiState(saved != null, envs, threads, projects, settings.showSettled)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, HomeUiState())

    private val _pairing = MutableStateFlow<Pair<Boolean, String?>>(false to null)
    val pairing: StateFlow<Pair<Boolean, String?>> = _pairing

    fun updateSettings(transform: (AppSettings) -> AppSettings) = viewModelScope.launch { settingsRepo.update(transform) }

    fun threadAction(entry: ThreadEntry, action: ThreadAction) = runThreadAction(entry.environmentId, entry.thread.id, action)

    fun runThreadAction(environmentId: String, threadId: String, action: ThreadAction) {
        val conn = repository.connection(environmentId) ?: return UiEvents.show("Environment is not connected")
        val op = threadActionOp(conn.commands, threadId, action) ?: return UiEvents.show("This server doesn't support that yet. Update T3 Code on the host.")
        viewModelScope.launch {
            runCatching { conn.run(op) }
                .onFailure { UiEvents.show("Could not ${actionVerb(action)} thread: ${errorText(it)}") }
        }
    }

    fun pair(target: PairingTarget, onDone: () -> Unit) {
        if (_pairing.value.first) return
        _pairing.value = true to null
        viewModelScope.launch {
            runCatching { repository.pair(target) }
                .onSuccess { _pairing.value = false to null; UiEvents.show("Connected to ${it.label}"); onDone() }
                .onFailure { _pairing.value = false to errorText(it) }
        }
    }

    fun clearPairingError() { _pairing.value = false to null }

    fun setEnabled(id: String, enabled: Boolean) = viewModelScope.launch { repository.setEnabled(id, enabled) }
    fun renameEnvironment(id: String, label: String) = viewModelScope.launch { repository.rename(id, label) }
    fun removeEnvironment(id: String) = viewModelScope.launch { repository.remove(id) }
    fun reconnect(id: String) = repository.connection(id)?.reconnectNow()
    fun reconnectAll() = repository.reconnectAll()
}

class ThreadViewModel(
    private val repository: T3Repository,
    settingsRepo: AppSettingsRepository,
    val environmentId: String,
    val threadId: String,
) : ViewModel() {
    private val connection: Flow<EnvironmentConnection?> = repository.connections.map { it[environmentId] }

    private val detail: Flow<ThreadState> = connection.flatMapLatest { it?.observeThread(threadId) ?: flowOf(ThreadState(error = "Environment is not connected")) }
    private val shellEntry = connection.flatMapLatest { c -> c?.shell?.map { s -> s.threads[threadId] to s.projects } ?: flowOf(null to emptyMap()) }
    private val status: Flow<ConnectionStatus> = connection.flatMapLatest { it?.status ?: flowOf(ConnectionStatus.Disabled) }
    private val config: Flow<ServerConfig?> = connection.flatMapLatest { it?.config ?: flowOf(null) }

    val tray = AttachmentTray(viewModelScope, repository)

    private val baseState: Flow<ThreadUiState> = combine(detail, shellEntry, status, config, settingsRepo.settings) { detail, (shell, projects), status, config, settings ->
        val thread = shell ?: detail.thread
        val project = thread?.projectId?.let { projects[it] }
        val envLabel = repository.connection(environmentId)?.environment?.label ?: "environment"
        val providers = config.selectableProviders()
        val selection = thread?.modelSelection ?: config.defaultSelection(project?.defaultModelSelection)
        val provider = providers.firstOrNull { it.instanceId == selection?.instanceId }
        val running = shell?.isWorking == true || detail.activeRun != null
        ThreadUiState(
            title = thread?.title.orEmpty(),
            subtitle = listOfNotNull(project?.title, envLabel).joinToString(" · "),
            shell = shell,
            detail = detail,
            connection = status,
            environmentLabel = envLabel,
            composer = ComposerModel(
                providers = providers,
                selection = selection,
                runtimeMode = RuntimeMode.of(thread?.runtimeMode),
                planMode = thread?.interactionMode == "plan",
                running = running,
                canStop = running && (shell?.activeRunId ?: detail.activeRun?.id) != null,
                defaultFollowUp = if (settings.followUp == FollowUpBehavior.Steer) SendMode.Steer else SendMode.Queue,
                enabled = status == ConnectionStatus.Connected,
            ),
            wrapCode = settings.wrapCode,
            enterToSend = settings.enterToSend,
            showPlanToggle = thread?.interactionMode == "plan" || (settings.legacyPlanMode && provider?.showInteractionModeToggle == true),
            canAttach = config?.environment?.capability("attachmentUploads") == true,
        )
    }

    val state: StateFlow<ThreadUiState?> = combine(baseState, tray.items) { s, items -> s.copy(attachments = items) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun addImages(resolver: android.content.ContentResolver, uris: List<android.net.Uri>) = tray.add(environmentId, resolver, uris)

    suspend fun loadImage(attachment: JsonObject): androidx.compose.ui.graphics.ImageBitmap? {
        val conn = repository.connection(environmentId) ?: return null
        return codes.t3.android.data.Attachments.download(conn, repository.http, attachment)?.asImageBitmap()
    }

    private fun run(what: String, build: (codes.t3.android.data.ProtocolCommands) -> codes.t3.android.data.Op?) {
        val conn = repository.connection(environmentId) ?: return UiEvents.show("Environment is not connected")
        val op = build(conn.commands) ?: return UiEvents.show("This server doesn't support that yet. Update T3 Code on the host.")
        viewModelScope.launch {
            runCatching { conn.run(op) }.onFailure { UiEvents.show("Could not $what: ${errorText(it)}") }
        }
    }

    fun markVisited() {
        val conn = repository.connection(environmentId) ?: return
        val at = conn.shell.value.threads[threadId]?.updatedAt
        val op = conn.commands.visit(threadId, at) ?: return
        viewModelScope.launch { runCatching { conn.run(op) } }
    }

    fun send(text: String, mode: SendMode, sourcePlan: Pair<String, String>? = null) {
        val s = state.value ?: return
        if (text.isBlank() && s.attachments.none { it.ref != null }) return
        if (!tray.ready) return UiEvents.show("Wait for images to finish uploading")
        when (text.trim().lowercase()) {
            "/plan" -> return run("switch to plan mode") { it.setInteractionMode(threadId, "plan") }
            "/default", "/build" -> return run("switch to default mode") { it.setInteractionMode(threadId, "default") }
        }
        val runId = s.shell?.activeRunId ?: s.detail.activeRun?.id
        val dispatchMode = when {
            !s.composer.running || runId == null -> Commands.DispatchMode.StartImmediately
            mode == SendMode.Steer -> Commands.DispatchMode.Steer(runId)
            else -> Commands.DispatchMode.Queue
        }
        val attachments = tray.take()
        val interaction = if (sourcePlan != null) "default" else if (s.composer.planMode) "plan" else "default"
        run("send message") { it.send(threadId, text, s.composer.selection, dispatchMode, attachments, sourcePlan, s.composer.runtimeMode.wire, interaction) }
    }

    fun stop() {
        val s = state.value ?: return
        val runId = s.shell?.activeRunId ?: s.detail.activeRun?.id ?: return
        run("stop the agent") { it.interrupt(threadId, runId) }
    }

    fun cancelQueued(runId: String) = run("remove queued message") { it.cancelQueued(threadId, runId) }
    fun steerQueued(runId: String) {
        val target = state.value?.detail?.activeRun?.id ?: return
        run("steer") { it.promoteToSteer(threadId, runId, target) }
    }
    fun resumeQueue() = run("resume queue") { it.resumeQueue(threadId) }

    fun respondApproval(requestId: String, decision: String) = run("respond") { it.respondApproval(threadId, requestId, decision) }

    fun answer(requestId: String, answers: Map<String, List<String>>, multi: Set<String>) =
        run("submit answers") { it.respondAnswers(threadId, requestId, answers, multi) }

    fun dismissQuestion(requestId: String) = run("dismiss") { it.dismissQuestion(threadId, requestId) }

    fun changeModel(selection: ModelSelection, runtime: RuntimeMode) {
        val s = state.value ?: return
        val current = s.composer.selection
        if (selection != current) run("change model") { it.setModel(threadId, current, selection) }
        if (runtime != s.composer.runtimeMode) setRuntime(runtime)
    }

    fun setRuntime(mode: RuntimeMode) = run("change runtime mode") { it.setRuntimeMode(threadId, mode.wire) }

    fun togglePlan() {
        val plan = state.value?.composer?.planMode ?: return
        run("switch mode") { it.setInteractionMode(threadId, if (plan) "default" else "plan") }
    }

    fun implementPlan(item: TurnItem) {
        val planId = item.str("planId") ?: return
        if (state.value?.composer?.planMode == true) run("switch mode") { it.setInteractionMode(threadId, "default") }
        send("Implement the plan.", SendMode.Send, sourcePlan = threadId to planId)
    }

    suspend fun loadFullItem(itemId: String): TurnItem? {
        val conn = repository.connection(environmentId) ?: return null
        val result = conn.call("orchestration.getTurnItem", buildJsonObject { put("threadId", threadId); put("itemId", itemId) })
        val item = (result as? JsonObject)?.get("item") as? JsonObject ?: return null
        return T3Json.decodeFromJsonElement<TurnItem>(item)
    }

    fun reconnect() = repository.connection(environmentId)?.reconnectNow()
}

class NewThreadViewModel(
    private val repository: T3Repository,
    private val settingsRepo: AppSettingsRepository,
    private val preferredProjectId: String?,
    private val preferredEnvironmentId: String?,
) : ViewModel() {
    private val selectedKey = MutableStateFlow<Pair<String, String>?>(null) // env, project
    private val selectionOverride = MutableStateFlow<ModelSelection?>(null)
    private val runtime = MutableStateFlow<RuntimeMode?>(null)
    private val plan = MutableStateFlow(false)
    private val workspace = MutableStateFlow(WorkspaceMode.Local)
    private val branch = MutableStateFlow<String?>(null)
    private val branches = MutableStateFlow<List<BranchRef>?>(null)
    private val isRepo = MutableStateFlow(true)
    private val starting = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)

    private val configs: Flow<Map<String, ServerConfig?>> = repository.connections.flatMapLatest { conns ->
        if (conns.isEmpty()) flowOf(emptyMap())
        else combine(conns.values.map { c -> c.config.map { c.environmentId to it } }) { it.toMap() }
    }

    private data class Local(
        val key: Pair<String, String>?, val override: ModelSelection?, val runtime: RuntimeMode?, val plan: Boolean,
        val workspace: WorkspaceMode, val branch: String?, val branches: List<BranchRef>?, val isRepo: Boolean,
        val starting: Boolean, val error: String?,
    )

    private val local: Flow<Local> = combine(
        combine(selectedKey, selectionOverride, runtime, plan, workspace) { a, b, c, d, e -> listOf(a, b, c, d, e) },
        combine(branch, branches, isRepo, starting, error) { a, b, c, d, e -> listOf(a, b, c, d, e) },
    ) { x, y ->
        @Suppress("UNCHECKED_CAST")
        Local(
            x[0] as Pair<String, String>?, x[1] as ModelSelection?, x[2] as RuntimeMode?, x[3] as Boolean, x[4] as WorkspaceMode,
            y[0] as String?, y[1] as List<BranchRef>?, y[2] as Boolean, y[3] as Boolean, y[4] as String?,
        )
    }

    val tray = AttachmentTray(viewModelScope, repository)

    private val baseState: Flow<NewThreadUiState> = combine(repository.projects, configs, local, repository.savedEnvironments, settingsRepo.settings) { projects, configs, l, saved, settings ->
        val sorted = projects.sortedByDescending { it.project.updatedAt }
        val selected = l.key?.let { (e, p) -> sorted.firstOrNull { it.environmentId == e && it.project.id == p } }
            ?: sorted.firstOrNull { it.project.id == preferredProjectId && (preferredEnvironmentId == null || it.environmentId == preferredEnvironmentId) }
            ?: sorted.firstOrNull()
        val config = selected?.let { configs[it.environmentId] }
        val providers = config.selectableProviders()
        val selection = l.override?.takeIf { o -> providers.any { it.instanceId == o.instanceId } } ?: config.defaultSelection(selected?.project?.defaultModelSelection)
        val provider = providers.firstOrNull { it.instanceId == selection?.instanceId }
        val defaultRuntime = RuntimeMode.of(config?.settings?.defaultRuntimeMode)
        NewThreadUiState(
            projects = sorted,
            selected = selected,
            showEnvironment = (saved?.size ?: 0) > 1,
            composer = ComposerModel(
                providers = providers,
                selection = selection,
                runtimeMode = l.runtime ?: defaultRuntime,
                planMode = l.plan,
            ),
            showPlanToggle = l.plan || (settings.legacyPlanMode && provider?.showInteractionModeToggle == true),
            workspace = l.workspace,
            branch = l.branch,
            branches = l.branches,
            isRepo = l.isRepo,
            starting = l.starting,
            error = l.error,
            canAttach = config?.environment?.capability("attachmentUploads") == true,
        )
    }

    val state: StateFlow<NewThreadUiState?> = combine(baseState, tray.items) { s, items -> s.copy(attachments = items) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun addImages(resolver: android.content.ContentResolver, uris: List<android.net.Uri>) {
        val env = state.value?.selected?.environmentId ?: return
        tray.add(env, resolver, uris)
    }

    fun selectProject(entry: ProjectEntry) {
        selectedKey.value = entry.environmentId to entry.project.id
        branch.value = null
        branches.value = null
        selectionOverride.value = null
    }

    fun setWorkspace(mode: WorkspaceMode) { workspace.value = mode }
    fun setBranch(name: String) { branch.value = name }
    fun setModel(selection: ModelSelection, mode: RuntimeMode) { selectionOverride.value = selection; runtime.value = mode }
    fun setRuntime(mode: RuntimeMode) { runtime.value = mode }
    fun togglePlan() { plan.update { !it } }

    fun loadBranches() {
        val selected = state.value?.selected ?: return
        val conn = repository.connection(selected.environmentId) ?: return
        viewModelScope.launch {
            runCatching {
                conn.call("vcs.listRefs", buildJsonObject { put("cwd", selected.project.workspaceRoot); put("limit", 100) })
            }.onSuccess { result ->
                val obj = result as? JsonObject ?: return@onSuccess
                isRepo.value = (obj["isRepo"] as? JsonPrimitive)?.booleanOrNull != false
                val refs = (obj["refs"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.map { r ->
                    BranchRef(
                        name = (r["name"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                        current = (r["current"] as? JsonPrimitive)?.booleanOrNull == true,
                        isDefault = (r["isDefault"] as? JsonPrimitive)?.booleanOrNull == true,
                        isRemote = (r["isRemote"] as? JsonPrimitive)?.booleanOrNull == true,
                    )
                }.filter { it.name.isNotEmpty() }
                branches.value = refs.sortedWith(compareByDescending<BranchRef> { it.current }.thenByDescending { it.isDefault }.thenBy { it.isRemote })
                if (branch.value == null) branch.value = refs.firstOrNull { it.current }?.name ?: refs.firstOrNull { it.isDefault }?.name
            }.onFailure {
                isRepo.value = false
                branches.value = emptyList()
            }
        }
    }

    fun addProject(path: String, title: String) {
        val envId = state.value?.selected?.environmentId ?: repository.connections.value.keys.firstOrNull() ?: return
        val conn = repository.connection(envId) ?: return
        val projectId = UUID.randomUUID().toString()
        viewModelScope.launch {
            runCatching {
                conn.run(conn.commands.createProject(projectId, title.ifBlank { path.trimEnd('/').substringAfterLast('/') }, path))
            }.onSuccess {
                selectedKey.value = envId to projectId
                branches.value = null
                branch.value = null
            }.onFailure { UiEvents.show("Project not added: ${errorText(it)}") }
        }
    }

    fun start(text: String, onStarted: (environmentId: String, threadId: String) -> Unit) {
        val s = state.value ?: return
        val selected = s.selected ?: return
        val selection = s.composer.selection ?: return UiEvents.show("Choose a model first. Set up a provider on the host if none are listed.")
        if ((text.isBlank() && s.attachments.none { it.ref != null }) || starting.value) return
        if (!tray.ready) return UiEvents.show("Wait for images to finish uploading")
        if (text.trim().lowercase() == "/plan") { plan.value = true; return }
        if (text.trim().lowercase() in setOf("/default", "/build")) { plan.value = false; return }
        val conn = repository.connection(selected.environmentId) ?: return UiEvents.show("Environment is not connected")
        val threadId = UUID.randomUUID().toString()
        val workspaceStrategy = when (s.workspace) {
            WorkspaceMode.Local -> Commands.Workspace.Root(s.branch)
            WorkspaceMode.Worktree -> Commands.Workspace.NewWorktree(s.branch ?: "HEAD", Commands.worktreeBranch())
        }
        starting.value = true
        error.value = null
        viewModelScope.launch {
            runCatching {
                conn.run(
                    conn.commands.launch(
                        threadId, selected.project.id, selected.project.workspaceRoot, text, selection, s.composer.runtimeMode.wire,
                        if (s.composer.planMode) "plan" else "default", workspaceStrategy, tray.take(),
                    ),
                )
            }.onSuccess { result ->
                starting.value = false
                val id = ((result as? JsonObject)?.get("threadId") as? JsonPrimitive)?.contentOrNull ?: threadId
                onStarted(selected.environmentId, id)
            }.onFailure {
                starting.value = false
                error.value = "Could not start task: ${errorText(it)}"
            }
        }
    }
}

