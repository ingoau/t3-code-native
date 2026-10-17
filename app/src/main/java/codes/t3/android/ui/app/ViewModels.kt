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
import codes.t3.android.data.SavedEnvironment
import codes.t3.android.data.T3Repository
import codes.t3.android.data.ThreadEntry
import codes.t3.android.data.model.ModelSelection
import codes.t3.android.data.model.ProjectShell
import codes.t3.android.data.model.RuntimeMode
import codes.t3.android.data.model.ServerConfig
import codes.t3.android.data.model.ServerProvider
import codes.t3.android.data.model.T3Json
import codes.t3.android.data.model.ThreadShell
import codes.t3.android.data.model.TurnItem
import codes.t3.android.data.pairing.PairingTarget
import codes.t3.android.data.rpc.RpcException
import codes.t3.android.data.state.ThreadState
import codes.t3.android.data.state.deriveLineage
import codes.t3.android.data.Git
import codes.t3.android.data.GitAction
import codes.t3.android.data.GitProgress
import codes.t3.android.data.GitStatus
import codes.t3.android.ui.thread.GitUiState
import codes.t3.android.ui.thread.LinkedPr
import codes.t3.android.data.state.latestMergeBackRun
import codes.t3.android.data.state.LineageRow
import codes.t3.android.data.isScratchProject
import codes.t3.android.ui.home.HomeUiState
import codes.t3.android.ui.home.ThreadAction
import codes.t3.android.ui.newthread.BranchRef
import codes.t3.android.ui.newthread.NewThreadUiState
import codes.t3.android.ui.newthread.ScratchOption
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
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
    ThreadAction.Unsnooze -> commands.housekeeping(threadId, codes.t3.android.data.ThreadOp.Unsnooze)
    ThreadAction.Archive -> commands.housekeeping(threadId, codes.t3.android.data.ThreadOp.Archive)
    ThreadAction.Delete -> commands.housekeeping(threadId, codes.t3.android.data.ThreadOp.Delete)
    ThreadAction.MarkUnread -> commands.housekeeping(threadId, codes.t3.android.data.ThreadOp.MarkUnread)
    is ThreadAction.Rename -> commands.rename(threadId, action.title)
    is ThreadAction.Snooze -> commands.snooze(threadId, action.until)
}

private fun actionVerb(action: ThreadAction) = when (action) {
    ThreadAction.Pin -> "pin"; ThreadAction.Unpin -> "unpin"; ThreadAction.Settle -> "settle"; ThreadAction.Unsettle -> "un-settle"; ThreadAction.Unsnooze -> "wake"
    ThreadAction.Archive -> "archive"; ThreadAction.Delete -> "delete"; ThreadAction.MarkUnread -> "mark"; is ThreadAction.Rename -> "rename"; is ThreadAction.Snooze -> "snooze"
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
    /** Settings already loaded by the app, so the first frame doesn't wait on DataStore. */
    initialSettings: AppSettings? = null,
) : ViewModel() {
    private val connection: Flow<EnvironmentConnection?> = repository.connections.map { it[environmentId] }.distinctUntilChanged()

    private val detail: Flow<ThreadState> = connection.flatMapLatest { it?.observeThread(threadId) ?: flowOf(ThreadState(error = "Environment is not connected")) }
    private val shellEntry = connection.flatMapLatest { c -> c?.shell?.map { s -> s.threads to s.projects } ?: flowOf(emptyMap<String, ThreadShell>() to emptyMap()) }
    private val status: Flow<ConnectionStatus> = connection.flatMapLatest { it?.status ?: flowOf(ConnectionStatus.Disabled) }
    private val config: Flow<ServerConfig?> = connection.flatMapLatest { it?.config ?: flowOf(null) }

    val tray = AttachmentTray(viewModelScope, repository)

    private val baseState: Flow<ThreadUiState> = combine(detail, shellEntry, status, config, settingsRepo.settings) { detail, (threads, projects), status, config, settings ->
        buildState(detail, threads, projects, status, config, settings)
    }

    private fun buildState(
        detail: ThreadState, threads: Map<String, ThreadShell>, projects: Map<String, ProjectShell>, status: ConnectionStatus,
        config: ServerConfig?, settings: AppSettings,
    ): ThreadUiState {
        val shell = threads[threadId]
        val thread = shell ?: detail.thread
        val project = thread?.projectId?.let { projects[it] }
        val envLabel = repository.connection(environmentId)?.environment?.label ?: "environment"
        val providers = config.selectableProviders()
        val selection = thread?.modelSelection ?: config.defaultSelection(project?.defaultModelSelection)
        val provider = providers.firstOrNull { it.instanceId == selection?.instanceId }
        val running = shell?.isWorking == true || detail.activeRun != null
        val lineage = deriveLineage(threadId, if (shell == null && thread != null) threads + (threadId to thread) else threads, detail.subagents.values)
        val projectLabel = project?.title?.takeUnless { isScratchProject(project, config?.scratchWorkspaceRoot) } ?: project?.let { "No project" }
        val isFork = thread?.isFork == true && thread.parentThreadId != null
        val sessions = detail.sessions.values
        val env = config?.environment
        val multiLink = env?.capability("threadPullRequests") == true
        val links = thread?.pullRequests?.filter { it.source != "stack-dismissed" }?.map {
            LinkedPr(it.host, it.repository, it.number, it.url, it.snapshot?.title, it.snapshot?.let { s -> if (s.isDraft == true) "draft" else s.state })
        } ?: thread?.linkedPullRequest?.let { pr ->
            pr.number?.let { LinkedPr("", pr.repository.orEmpty(), it, pr.url.orEmpty(), null, pr.state) }
        }?.let { listOf(it) }.orEmpty()
        val gitCwd = (thread?.worktreePath ?: project?.workspaceRoot)?.takeIf { it.isNotBlank() && !repository.isV1(environmentId) && !isScratchProject(project, config?.scratchWorkspaceRoot) }
        return ThreadUiState(
            git = GitUiState(
                cwd = gitCwd,
                links = links,
                canLink = multiLink || env?.capability("threadPullRequestLinking") == true,
                worktreePath = thread?.worktreePath,
            ),
            mergeBack = if (isFork && !repository.isV1(environmentId)) latestMergeBackRun(detail.runs.values) != null else null,
            canFork = !repository.isV1(environmentId) && (sessions.isEmpty() || sessions.any { it.capabilities == null || it.capabilities.canForkFromRun }),
            title = thread?.title.orEmpty(),
            subtitle = listOfNotNull(if (thread?.isSubagent == true) "Subagent" else null, projectLabel, envLabel).joinToString(" · "),
            lineage = lineage,
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
                context = detail.contextUsage?.usedTokens?.let { codes.t3.android.ui.thread.ContextUsage(it, detail.contextUsage?.maxTokens) },
                builtins = codes.t3.android.ui.thread.builtinCommands(provider, hasThread = true),
            ),
            wrapCode = settings.wrapCode,
            enterToSend = settings.enterToSend,
            showPlanToggle = thread?.interactionMode == "plan" || (settings.legacyPlanMode && provider?.showInteractionModeToggle == true),
            canAttach = config?.environment?.capability("attachmentUploads") == true,
        )
    }

    private val restoredDraft = MutableStateFlow<String?>(null)
    fun draftRestored() { restoredDraft.value = null }

    /**
     * Built synchronously from what's already in memory (shell, cached detail, connection state) so the page has
     * content on its very first frame and the enter transition animates the real screen instead of a blank one.
     */
    private fun snapshot(settings: AppSettings?): ThreadUiState? {
        val conn = repository.connection(environmentId) ?: return null
        val shell = conn.shell.value
        return buildState(
            conn.cachedThread(threadId) ?: ThreadState(), shell.threads, shell.projects,
            conn.status.value, conn.config.value, settings ?: AppSettings(),
        )
    }

    private val git = MutableStateFlow(GitUiState())

    val state: StateFlow<ThreadUiState?> = combine(baseState, tray.items, restoredDraft, git) { s, items, draft, g ->
        s.copy(attachments = items, restoredDraft = draft, git = g.copy(cwd = s.git.cwd, links = s.git.links, canLink = s.git.canLink, worktreePath = s.git.worktreePath))
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), snapshot(initialSettings))

    fun addImages(resolver: android.content.ContentResolver, uris: List<android.net.Uri>) = tray.add(environmentId, resolver, uris)

    suspend fun loadImage(attachment: JsonObject): androidx.compose.ui.graphics.ImageBitmap? {
        val conn = repository.connection(environmentId) ?: return null
        return codes.t3.android.data.Attachments.download(conn, repository.http, attachment)?.asImageBitmap()
    }

    private fun run(what: String, onFailure: () -> Unit = {}, build: (codes.t3.android.data.ProtocolCommands) -> codes.t3.android.data.Op?) {
        val conn = repository.connection(environmentId) ?: run { onFailure(); return UiEvents.show("Environment is not connected") }
        val op = build(conn.commands) ?: run { onFailure(); return UiEvents.show("This server doesn't support that yet. Update T3 Code on the host.") }
        viewModelScope.launch {
            runCatching { conn.run(op) }.onFailure { onFailure(); UiEvents.show("Could not $what: ${errorText(it)}") }
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
        // Like the web app, replying to a settled or snoozed thread brings it back to Active.
        repository.connection(environmentId)?.let { conn ->
            val shell = s.shell
            val reopen = listOfNotNull(
                codes.t3.android.data.ThreadOp.Unsettle.takeIf { shell?.isSettled == true },
                codes.t3.android.data.ThreadOp.Unsnooze.takeIf { shell?.isSnoozed == true },
            )
            reopen.forEach { op -> conn.commands.housekeeping(threadId, op)?.let { viewModelScope.launch { runCatching { conn.run(it) } } } }
        }
        val taken = tray.takeItems()
        val attachments = JsonArray(taken.mapNotNull { it.ref })
        val interaction = if (sourcePlan != null) "default" else if (s.composer.planMode) "plan" else "default"
        // On failure, put the text and images back so nothing the user wrote is lost.
        val restore = { tray.restore(taken); if (sourcePlan == null) restoredDraft.value = text }
        run("send message", restore) { it.send(threadId, text, s.composer.selection, dispatchMode, attachments, sourcePlan, s.composer.runtimeMode.wire, interaction) }
    }

    fun stop() {
        val s = state.value ?: return
        val runId = s.shell?.activeRunId ?: s.detail.activeRun?.id ?: return
        run("stop the agent") { it.interrupt(threadId, runId) }
    }

    fun cancelQueued(runId: String) = run("remove queued message") { it.cancelQueued(threadId, runId) }
    fun editQueued(runId: String, text: String) {
        if (text.isBlank()) return
        run("edit queued message") { it.editQueued(threadId, runId, text.trim()) }
    }

    /** Saves (or, with a null [secret], declines) an agent's secret request. The secret goes to the host's store only. */
    fun answerSecret(itemId: String, secret: String?) = run(if (secret == null) "decline" else "save the secret") { it.answerSecret(threadId, itemId, secret) }
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

    fun reconnect() {
        repository.connection(environmentId)?.let { it.retryThreads(); it.reconnectNow() }
    }

    // ------------------------------------------------------------------ git

    fun refreshGit() {
        val cwd = state.value?.git?.cwd ?: return
        val conn = repository.connection(environmentId) ?: return
        git.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            runCatching { GitStatus.parse(conn.call("vcs.refreshStatus", Git.statusRequest(cwd)) as JsonObject) }
                .onSuccess { s -> git.update { it.copy(status = s, loading = false) } }
                .onFailure { e -> git.update { it.copy(loading = false, error = "Couldn't read git status: ${errorText(e)}") } }
        }
    }

    /** Runs a stacked git action, following its progress stream; a branch it creates becomes the thread's branch. */
    fun runGit(action: GitAction, message: String?, featureBranch: Boolean) {
        val cwd = state.value?.git?.cwd ?: return
        val conn = repository.connection(environmentId) ?: return UiEvents.show("Environment is not connected")
        if (git.value.busy != null) return
        git.update { it.copy(busy = "${action.label}…", error = null) }
        viewModelScope.launch {
            var outcome: GitProgress? = null
            runCatching {
                conn.stream("git.runStackedAction", Git.actionRequest(UUID.randomUUID().toString(), cwd, action, threadId, message, featureBranch)).collect { e ->
                    when (val p = Git.progress(e as? JsonObject ?: return@collect)) {
                        is GitProgress.Phase -> git.update { it.copy(busy = p.label) }
                        is GitProgress.Finished, is GitProgress.Failed -> outcome = p
                        null -> {}
                    }
                }
            }.onFailure { outcome = GitProgress.Failed(errorText(it)) }
            when (val o = outcome) {
                is GitProgress.Finished -> {
                    UiEvents.show(listOfNotNull(o.title, o.description).joinToString(" · "))
                    o.createdBranch?.let { branch ->
                        runCatching {
                            conn.dispatch(buildJsonObject {
                                put("type", "thread.metadata.update"); put("commandId", Commands.newId()); put("threadId", threadId)
                                put("branch", branch); put("worktreePath", state.value?.git?.worktreePath)
                            })
                        }
                    }
                    git.update { it.copy(busy = null) }
                }
                is GitProgress.Failed -> git.update { it.copy(busy = null, error = o.message) }
                else -> git.update { it.copy(busy = null, error = "The git action ended without a result") }
            }
            refreshGit()
        }
    }

    fun pullGit() {
        val cwd = state.value?.git?.cwd ?: return
        val conn = repository.connection(environmentId) ?: return
        git.update { it.copy(busy = "Pulling…", error = null) }
        viewModelScope.launch {
            runCatching { conn.call("vcs.pull", Git.statusRequest(cwd)) as JsonObject }
                .onSuccess { r ->
                    val pulled = (r["status"] as? JsonPrimitive)?.contentOrNull == "pulled"
                    UiEvents.show(if (pulled) "Pulled latest on ${(r["refName"] as? JsonPrimitive)?.contentOrNull ?: "branch"}" else "Already up to date")
                    git.update { it.copy(busy = null) }
                }
                .onFailure { e -> git.update { it.copy(busy = null, error = "Pull failed: ${errorText(e)}") } }
            refreshGit()
        }
    }

    fun linkPullRequest(url: String) {
        val pr = Git.parsePrUrl(url) ?: return UiEvents.show("That isn't a pull request URL")
        val conn = repository.connection(environmentId) ?: return
        val projectId = state.value?.shell?.projectId ?: return
        val multiple = conn.config.value?.environment?.capability("threadPullRequests") == true
        viewModelScope.launch {
            runCatching { conn.dispatch(Git.linkPullRequest(threadId, projectId, pr, multiple)) }
                .onSuccess { UiEvents.show("Linked #${pr.number}") }
                .onFailure { UiEvents.show("Could not link the pull request: ${errorText(it)}") }
        }
    }

    fun unlinkPullRequest(link: LinkedPr) {
        val conn = repository.connection(environmentId) ?: return
        val multiple = conn.config.value?.environment?.capability("threadPullRequests") == true
        viewModelScope.launch {
            runCatching { conn.dispatch(Git.unlinkPullRequest(threadId, link.host, link.repository, link.number, multiple)) }
                .onFailure { UiEvents.show("Could not unlink: ${errorText(it)}") }
        }
    }

    /** Provider-native agents stop by subagent id inside the parent's run; app-owned ones interrupt their own thread. */
    fun stopAgent(row: LineageRow) {
        val agent = row.subagent ?: return
        if (agent.origin == "provider_native") {
            val runId = agent.runId ?: return
            run("stop the agent") { it.stopSubagent(threadId, runId, agent.id) }
        } else {
            val child = row.threadId ?: return
            val runId = row.thread?.activeRunId ?: return UiEvents.show("That agent isn't running")
            run("stop the agent") { it.interrupt(child, runId) }
        }
    }

    fun stopWorkflow(row: LineageRow) {
        val id = row.subagent?.id ?: return
        run("stop the workflow") { it.stopWorkflow(threadId, id) }
    }

    /** Forks after [item]'s run, then opens the new thread once the shell has it (as the upstream apps do). */
    fun fork(item: TurnItem, onOpen: (String) -> Unit) {
        val runId = item.runId ?: return
        val conn = repository.connection(environmentId) ?: return UiEvents.show("Environment is not connected")
        val target = UUID.randomUUID().toString()
        val title = state.value?.title?.ifBlank { null }?.let { "$it fork" }
        val op = conn.commands.fork(threadId, target, runId, title) ?: return UiEvents.show("This server doesn't support forking. Update T3 Code on the host.")
        viewModelScope.launch {
            runCatching {
                conn.run(op)
                kotlinx.coroutines.withTimeout(15_000) { conn.shell.first { it.threads.containsKey(target) } }
            }.onSuccess { onOpen(target) }.onFailure { UiEvents.show("Could not fork: ${errorText(it)}") }
        }
    }

    fun mergeBack(onOpen: (String) -> Unit) {
        val s = state.value ?: return
        val target = s.shell?.parentThreadId ?: s.detail.thread?.parentThreadId ?: return
        val run = latestMergeBackRun(s.detail.runs.values) ?: return UiEvents.show("Complete a run in this fork before merging it back")
        val conn = repository.connection(environmentId) ?: return UiEvents.show("Environment is not connected")
        val op = conn.commands.mergeBack(threadId, target, run.id) ?: return
        viewModelScope.launch {
            runCatching { conn.run(op) }.onSuccess { onOpen(target) }.onFailure { UiEvents.show("Could not merge back: ${errorText(it)}") }
        }
    }
}

class NewThreadViewModel(
    private val repository: T3Repository,
    private val settingsRepo: AppSettingsRepository,
    private val preferredProjectId: String?,
    private val preferredEnvironmentId: String?,
    /** Settings already loaded by the app, so the first frame doesn't wait on DataStore. */
    initialSettings: AppSettings? = null,
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
        buildState(projects, configs, l, saved, settings)
    }

    private fun buildState(
        projects: List<ProjectEntry>, configs: Map<String, ServerConfig?>, l: Local, saved: List<SavedEnvironment>?, settings: AppSettings,
    ): NewThreadUiState {
        val sorted = projects.sortedByDescending { it.project.updatedAt }
        val selected = l.key?.let { (e, p) -> sorted.firstOrNull { it.environmentId == e && it.project.id == p } }
            ?: sorted.firstOrNull { it.project.id == preferredProjectId && (preferredEnvironmentId == null || it.environmentId == preferredEnvironmentId) }
            ?: sorted.firstOrNull { !it.isScratch }
            ?: sorted.firstOrNull()
        val labels = saved.orEmpty().associate { it.environmentId to it.label }
        val scratch = configs.filterValues { it?.scratchWorkspaceRoot != null }.keys
            .map { env -> ScratchOption(env, labels[env] ?: sorted.firstOrNull { it.environmentId == env }?.environmentLabel ?: "environment") }
            .sortedBy { it.environmentLabel }
        val config = selected?.let { configs[it.environmentId] }
        val providers = config.selectableProviders()
        val selection = l.override?.takeIf { o -> providers.any { it.instanceId == o.instanceId } } ?: config.defaultSelection(selected?.project?.defaultModelSelection)
        val provider = providers.firstOrNull { it.instanceId == selection?.instanceId }
        val defaultRuntime = RuntimeMode.of(config?.settings?.defaultRuntimeMode)
        return NewThreadUiState(
            projects = sorted.filterNot { it.isScratch },
            selected = selected,
            scratch = scratch,
            showEnvironment = (saved?.size ?: 0) > 1,
            composer = ComposerModel(
                providers = providers,
                selection = selection,
                runtimeMode = l.runtime ?: defaultRuntime,
                planMode = l.plan,
                builtins = codes.t3.android.ui.thread.builtinCommands(provider, hasThread = false),
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

    /** Built from what's already in memory so the enter transition animates the real screen, not a blank one. */
    private fun snapshot(settings: AppSettings?): NewThreadUiState = buildState(
        repository.currentProjects(),
        repository.connections.value.mapValues { it.value.config.value },
        Local(null, null, null, false, WorkspaceMode.Local, null, null, true, false, null),
        repository.savedEnvironments.value,
        settings ?: AppSettings(),
    )

    val state: StateFlow<NewThreadUiState?> = combine(baseState, tray.items) { s, items -> s.copy(attachments = items) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), snapshot(initialSettings))

    fun addImages(resolver: android.content.ContentResolver, uris: List<android.net.Uri>) {
        val env = state.value?.selected?.environmentId ?: return
        tray.add(env, resolver, uris)
    }

    fun selectProject(entry: ProjectEntry) {
        // Uploaded attachments live on one server; don't send their ids to another.
        if (state.value?.selected?.environmentId != entry.environmentId) tray.clear()
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
        // The scratch folder isn't a repository; there's no checkout or worktree to choose.
        if (selected.isScratch) { isRepo.value = false; branches.value = emptyList(); return }
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

    /**
     * "No project": like the desktop app, finds or creates the environment's scratch project (`projects.ensureScratch`)
     * and selects it once it shows up in the shell, since the thread is launched into its folder.
     */
    fun selectScratch(environmentId: String) {
        repository.currentProjects().firstOrNull { it.environmentId == environmentId && it.isScratch }?.let { return selectProject(it) }
        val conn = repository.connection(environmentId) ?: return UiEvents.show("Environment is not connected")
        viewModelScope.launch {
            runCatching {
                val result = conn.call("projects.ensureScratch") as JsonObject
                val projectId = (result["projectId"] as JsonPrimitive).content
                kotlinx.coroutines.withTimeout(10_000) { conn.shell.first { it.projects.containsKey(projectId) } }
                projectId
            }.onSuccess { projectId ->
                if (state.value?.selected?.environmentId != environmentId) tray.clear()
                selectedKey.value = environmentId to projectId
                branch.value = null
                branches.value = null
                selectionOverride.value = null
            }.onFailure { UiEvents.show("Could not start without a project: ${errorText(it)}") }
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
        val workspaceStrategy = when {
            selected.isScratch -> Commands.Workspace.Root(null)
            s.workspace == WorkspaceMode.Local -> Commands.Workspace.Root(s.branch)
            else -> Commands.Workspace.NewWorktree(s.branch ?: "HEAD", Commands.worktreeBranch())
        }
        starting.value = true
        error.value = null
        val taken = tray.takeItems()
        viewModelScope.launch {
            runCatching {
                conn.run(
                    conn.commands.launch(
                        threadId, selected.project.id, selected.project.workspaceRoot, text, selection, s.composer.runtimeMode.wire,
                        if (s.composer.planMode) "plan" else "default", workspaceStrategy, JsonArray(taken.mapNotNull { it.ref }),
                    ),
                )
            }.onSuccess { result ->
                starting.value = false
                val id = ((result as? JsonObject)?.get("threadId") as? JsonPrimitive)?.contentOrNull ?: threadId
                onStarted(selected.environmentId, id)
            }.onFailure {
                starting.value = false
                tray.restore(taken)
                error.value = "Could not start task: ${errorText(it)}"
            }
        }
    }
}

