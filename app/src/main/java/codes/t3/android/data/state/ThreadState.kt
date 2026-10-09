package codes.t3.android.data.state

import codes.t3.android.data.model.ApprovalOption
import codes.t3.android.data.model.Checkpoint
import codes.t3.android.data.model.ConversationMessage
import codes.t3.android.data.model.PendingApproval
import codes.t3.android.data.model.PendingUserInput
import codes.t3.android.data.model.ProviderSession
import codes.t3.android.data.model.ProviderThread
import codes.t3.android.data.model.Run
import codes.t3.android.data.model.RunAttempt
import codes.t3.android.data.model.RuntimeRequest
import codes.t3.android.data.model.T3Json
import codes.t3.android.data.model.ThreadProjection
import codes.t3.android.data.model.ThreadShell
import codes.t3.android.data.model.TurnItem
import codes.t3.android.data.model.UserInputQuestion
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

private val ActiveRunStatuses = setOf("preparing", "starting", "running", "waiting")
private val ApprovalKinds = setOf("command", "file-read", "file-change", "mcp-elicitation", "permission")

/** Detail state for one open thread, kept in sync with `orchestration.subscribeThread`. */
data class ThreadState(
    val sequence: Long = -1,
    val thread: ThreadShell? = null,
    val runs: Map<String, Run> = emptyMap(),
    val attempts: Map<String, RunAttempt> = emptyMap(),
    val sessions: Map<String, ProviderSession> = emptyMap(),
    val providerThreads: Map<String, ProviderThread> = emptyMap(),
    val requests: Map<String, RuntimeRequest> = emptyMap(),
    val messages: Map<String, ConversationMessage> = emptyMap(),
    val checkpoints: Map<String, Checkpoint> = emptyMap(),
    val items: Map<String, TurnItem> = emptyMap(),
    /** Rows from parent threads (forks) that precede local items. */
    val inherited: List<TurnItem> = emptyList(),
    val synchronized: Boolean = false,
    val error: String? = null,
) {
    val loaded: Boolean get() = sequence >= 0

    val activeRun: Run? get() = runs.values.filter { it.status in ActiveRunStatuses }.maxByOrNull { it.ordinal }
    val queuedRuns: List<Run> get() = runs.values.filter { it.status == "queued" }.sortedBy { it.queuePosition ?: it.ordinal }

    /** Follow-ups waiting behind the active run, with their message text (they have no timeline item yet). */
    val queued: List<Pair<Run, ConversationMessage?>> get() = queuedRuns.map { it to it.userMessageId?.let { id -> messages[id] } }

    /** Ordered, visibility-filtered timeline. */
    val timeline: List<TurnItem> by lazy {
        val local = items.values.filter { isVisible(it) }.sortedWith(compareBy<TurnItem> { it.ordinal }.thenBy { it.id })
        inherited + local
    }

    private fun isVisible(item: TurnItem): Boolean {
        val run = item.runId?.let { runs[it] }
        if (run?.status == "rolled_back") return false
        if (item.type == "user_message" && item.inputIntent == "queued_turn" && run?.status == "cancelled") return false
        if (item.type == "run_interrupt_result" && run != null) {
            val superseded = attempts.values.any { it.runId == run.id && it.status == "superseded" && it.rootNodeId == item.nodeId }
            val hasRequest = items.values.any { it.runId == run.id && it.type == "run_interrupt_request" }
            if (superseded && !hasRequest) return false
        }
        return true
    }

    val pendingApprovals: List<PendingApproval>
        get() = requests.values.filter { it.status == "pending" && it.kind in ApprovalKinds }.map { req ->
            val item = items.values.lastOrNull { it.type == "approval_request" && it.requestId == req.id }
            val options = item?.arr("options")?.mapNotNull {
                runCatching { T3Json.decodeFromJsonElement<ApprovalOption>(it) }.getOrNull()
            }.orEmpty().ifEmpty {
                listOf(
                    ApprovalOption("accept", "Allow once"),
                    ApprovalOption("acceptForSession", "Allow session"),
                    ApprovalOption("decline", "Decline"),
                )
            }
            PendingApproval(
                requestId = req.id,
                kind = req.kind,
                title = item?.appName ?: requestKindLabel(item?.requestKind ?: req.kind),
                detail = item?.prompt ?: item?.title,
                options = options,
                stale = req.responseCapability?.get("type")?.let { (it as? JsonPrimitive)?.contentOrNull } == "not_resumable",
            )
        }

    val pendingUserInput: PendingUserInput?
        get() = requests.values.firstOrNull { it.status == "pending" && it.kind == "user_input" }?.let { req ->
            val item = items.values.lastOrNull { it.type == "user_input_request" && it.requestId == req.id }
            val questions = item?.arr("questions")?.mapNotNull {
                runCatching { T3Json.decodeFromJsonElement<UserInputQuestion>(it) }.getOrNull()
            }.orEmpty()
            val capType = (req.responseCapability?.get("type") as? JsonPrimitive)?.contentOrNull
            PendingUserInput(
                requestId = req.id,
                questions = questions,
                dismissible = item?.str("responseMode") == "message" || capType == "message",
                stale = capType == "not_resumable",
            )
        }

    fun applySnapshot(sequence: Long, projection: ThreadProjection) = ThreadState(
        sequence = sequence,
        thread = projection.thread,
        runs = projection.runs.associateBy { it.id },
        attempts = projection.attempts.associateBy { it.id },
        sessions = projection.providerSessions.associateBy { it.id },
        providerThreads = projection.providerThreads.associateBy { it.id },
        requests = projection.runtimeRequests.associateBy { it.id },
        messages = projection.messages.associateBy { it.id },
        checkpoints = projection.checkpoints.associateBy { it.id },
        items = projection.turnItems.associateBy { it.id },
        inherited = projection.visibleTurnItems.filter { it.visibility != "local" }.sortedBy { it.position }.map { it.item },
        synchronized = synchronized,
    )

    /** Apply one `subscribeThread` stream item. */
    fun apply(element: JsonElement): ThreadState {
        val obj = element as? JsonObject ?: return this
        return when ((obj["kind"] as? JsonPrimitive)?.contentOrNull) {
            "snapshot" -> {
                val projection = T3Json.decodeFromJsonElement<ThreadProjection>(obj["projection"] ?: return this)
                val seq = (obj["snapshotSequence"] as? JsonPrimitive)?.longOrNull ?: 0
                applySnapshot(seq, projection)
            }
            "synchronized" -> copy(synchronized = true)
            "event" -> {
                val seq = (obj["sequence"] as? JsonPrimitive)?.longOrNull ?: return this
                if (seq <= sequence) return this
                val event = obj["event"]?.jsonObject ?: return copy(sequence = seq)
                runCatching { applyEvent(event) }.getOrDefault(this).copy(sequence = seq)
            }
            else -> this
        }
    }

    fun applyEvent(event: JsonObject): ThreadState {
        val type = (event["type"] as? JsonPrimitive)?.contentOrNull ?: return this
        val payload = event["payload"] ?: return this
        return when {
            type.startsWith("thread.") -> copy(thread = T3Json.decodeFromJsonElement<ThreadShell>(payload).mergeShell(thread))
            type == "run.created" || type == "run.updated" -> {
                val run = T3Json.decodeFromJsonElement<Run>(payload)
                copy(runs = runs + (run.id to run))
            }
            type == "run-attempt.created" || type == "run-attempt.updated" -> {
                val attempt = T3Json.decodeFromJsonElement<RunAttempt>(payload)
                copy(attempts = attempts + (attempt.id to attempt))
            }
            type == "provider-session.attached" || type == "provider-session.updated" -> {
                val s = T3Json.decodeFromJsonElement<ProviderSession>(payload)
                copy(sessions = sessions + (s.id to s))
            }
            type == "provider-session.detached" -> {
                val id = (payload.jsonObject["providerSessionId"] as? JsonPrimitive)?.contentOrNull ?: return this
                copy(sessions = sessions - id)
            }
            type == "provider-thread.updated" -> {
                val t = T3Json.decodeFromJsonElement<ProviderThread>(payload)
                copy(providerThreads = providerThreads + (t.id to t))
            }
            type == "runtime-request.updated" -> {
                val r = T3Json.decodeFromJsonElement<RuntimeRequest>(payload)
                copy(requests = requests + (r.id to r))
            }
            type == "checkpoint.captured" -> {
                val c = T3Json.decodeFromJsonElement<Checkpoint>(payload)
                copy(checkpoints = checkpoints + (c.id to c))
            }
            type == "message.updated" -> {
                val m = T3Json.decodeFromJsonElement<ConversationMessage>(payload)
                copy(messages = messages + (m.id to m))
            }
            type == "turn-item.updated" -> {
                val item = T3Json.decodeFromJsonElement<TurnItem>(payload)
                if (item.id.isEmpty()) this else copy(items = items + (item.id to item))
            }
            else -> this
        }
    }

    /** Thread events carry the app thread, which lacks the shell-only summary fields; keep those from before. */
    private fun ThreadShell.mergeShell(previous: ThreadShell?): ThreadShell = if (previous == null) this else copy(
        status = previous.status,
        activeRunId = previous.activeRunId,
        latestVisibleMessage = previous.latestVisibleMessage,
        pendingRuntimeRequest = previous.pendingRuntimeRequest,
    )

    /** Highest turn count with a captured checkpoint (for the whole-thread diff). */
    val latestTurnCount: Int? get() = checkpoints.values.mapNotNull { it.appRunOrdinal }.maxOrNull()

    val contextUsage get() = providerThreads.values.mapNotNull { it.contextUsage }.lastOrNull()

}

fun requestKindLabel(kind: String): String = when (kind) {
    "command" -> "Run command"
    "file-read" -> "Read file"
    "file-change" -> "Edit files"
    "mcp-elicitation" -> "Tool request"
    "permission" -> "Permission"
    else -> kind.replaceFirstChar { it.uppercase() }
}
