package codes.t3.android.data.v1

import codes.t3.android.data.model.Checkpoint
import codes.t3.android.data.model.ModelSelection
import codes.t3.android.data.model.PendingRuntimeRequestRef
import codes.t3.android.data.model.Run
import codes.t3.android.data.model.RuntimeRequest
import codes.t3.android.data.model.T3Json
import codes.t3.android.data.model.ThreadShell
import codes.t3.android.data.model.TurnItem
import codes.t3.android.data.state.ThreadState
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/*
 * Orchestration protocol v1 (t3 ≤ 0.0.45) support. v1 models a thread as messages + activities + plans +
 * checkpoints; we translate that into the v2-shaped models ([ThreadShell], [ThreadState], [TurnItem]) the UI uses.
 */

internal fun JsonObject.s(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
internal fun JsonObject.o(key: String): JsonObject? = this[key] as? JsonObject
internal fun JsonObject.a(key: String): JsonArray? = this[key] as? JsonArray
internal fun JsonObject.b(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

object V1 {
    private val runningSession = setOf("starting", "running")

    /** v1 `OrchestrationThreadShell` (or full thread) row → [ThreadShell]. */
    fun threadShell(row: JsonObject): ThreadShell {
        val session = row.o("session")
        val turn = row.o("latestTurn")
        val turnState = turn?.s("state")
        val running = session?.s("status") in runningSession || turnState == "running"
        val status = when {
            running -> "running"
            turnState == "error" || session?.s("status") == "error" -> "failed"
            turnState == "interrupted" -> "interrupted"
            turnState == "completed" -> "completed"
            else -> "idle"
        }
        val selection = row["modelSelection"]?.let { runCatching { T3Json.decodeFromJsonElement<ModelSelection>(it) }.getOrNull() }
        val pending = when {
            row.b("hasPendingApprovals") == true -> PendingRuntimeRequestRef("v1-approval", "command")
            row.b("hasPendingUserInput") == true -> PendingRuntimeRequestRef("v1-input", "user_input")
            else -> null
        }
        return ThreadShell(
            id = row.s("id") ?: "",
            projectId = row.s("projectId") ?: "",
            title = row.s("title") ?: "",
            providerInstanceId = selection?.instanceId ?: session?.s("providerInstanceId"),
            modelSelection = selection,
            runtimeMode = row.s("runtimeMode") ?: "full-access",
            interactionMode = row.s("interactionMode") ?: "default",
            branch = row.s("branch"),
            worktreePath = row.s("worktreePath"),
            latestRunId = turn?.s("turnId"),
            latestRunStartedAt = turn?.s("startedAt"),
            latestRunCompletedAt = turn?.s("completedAt"),
            activeRunId = if (running) session?.s("activeTurnId") ?: turn?.s("turnId") ?: "v1-active" else null,
            activityRunStartedAt = if (running) turn?.s("startedAt") ?: turn?.s("requestedAt") else null,
            status = status,
            lastError = session?.s("lastError"),
            pendingRuntimeRequest = pending,
            latestUserMessageAt = row.s("latestUserMessageAt"),
            hasActionableProposedPlan = row.b("hasActionableProposedPlan") == true,
            createdAt = row.s("createdAt"),
            updatedAt = row.s("updatedAt"),
            archivedAt = row.s("archivedAt"),
            settledOverride = row.s("settledOverride"),
            settledAt = row.s("settledAt"),
            snoozedUntil = row.s("snoozedUntil"),
            pinnedAt = row.s("pinnedAt"),
            pinOrderKey = row.s("pinOrderKey"),
            activeOrderKey = row.s("activeOrderKey"),
            // v1 has no visit tracking; treat finished turns as seen so "Done" doesn't stick forever.
            lastVisitedAt = turn?.s("completedAt") ?: row.s("updatedAt"),
            deletedAt = row.s("deletedAt"),
        )
    }

    fun requestKind(payload: JsonObject): String = payload.s("requestKind") ?: when (payload.s("requestType")) {
        "file_read_approval" -> "file-read"
        "file_change_approval", "apply_patch_approval" -> "file-change"
        "mcp_elicitation_approval" -> "mcp-elicitation"
        "permission_approval" -> "permission"
        else -> "command"
    }
}

/** Raw v1 thread detail, kept in sync with `orchestration.subscribeThread` (v1), projected to [ThreadState]. */
data class V1ThreadState(
    val sequence: Long = -1,
    val thread: JsonObject? = null,
    val messages: List<JsonObject> = emptyList(),
    val activities: List<JsonObject> = emptyList(),
    val plans: List<JsonObject> = emptyList(),
    val checkpoints: List<JsonObject> = emptyList(),
    val synchronized: Boolean = false,
    val error: String? = null,
) {
    fun apply(element: JsonElement): V1ThreadState {
        val obj = element as? JsonObject ?: return this
        return when (obj.s("kind")) {
            "snapshot" -> {
                val snap = obj.o("snapshot") ?: return this
                val t = snap.o("thread") ?: return this
                V1ThreadState(
                    sequence = (snap["snapshotSequence"] as? JsonPrimitive)?.longOrNull ?: 0,
                    thread = t,
                    messages = t.a("messages").orEmpty().mapNotNull { it as? JsonObject },
                    activities = t.a("activities").orEmpty().mapNotNull { it as? JsonObject },
                    plans = t.a("proposedPlans").orEmpty().mapNotNull { it as? JsonObject },
                    checkpoints = t.a("checkpoints").orEmpty().mapNotNull { it as? JsonObject },
                    synchronized = synchronized,
                )
            }
            "synchronized" -> copy(synchronized = true)
            "event" -> {
                val event = obj.o("event") ?: return this
                val seq = (event["sequence"] as? JsonPrimitive)?.longOrNull ?: return this
                if (seq <= sequence) return this
                runCatching { applyEvent(event) }.getOrDefault(this).copy(sequence = seq)
            }
            else -> this
        }
    }

    private fun patchThread(fields: JsonObject, vararg keys: String): V1ThreadState {
        val base = thread ?: return this
        val updated = base.toMutableMap()
        (if (keys.isEmpty()) fields.keys else keys.toSet()).forEach { k -> fields[k]?.let { updated[k] = it } }
        return copy(thread = JsonObject(updated))
    }

    private fun applyEvent(event: JsonObject): V1ThreadState {
        val p = event.o("payload") ?: return this
        return when (event.s("type")) {
            "thread.message-sent" -> {
                val id = p.s("messageId") ?: return this
                val existing = messages.indexOfFirst { it.s("id") == id }
                val streaming = p.b("streaming") == true
                val incoming = p.s("text").orEmpty()
                val text = when {
                    existing < 0 -> incoming
                    streaming -> messages[existing].s("text").orEmpty() + incoming // deltas
                    incoming.isEmpty() -> messages[existing].s("text").orEmpty()   // final with no text: keep accumulated
                    else -> incoming
                }
                val msg = buildJsonObject {
                    put("id", id)
                    put("role", p.s("role") ?: "assistant")
                    put("text", text)
                    put("turnId", p.s("turnId"))
                    put("streaming", streaming)
                    put("attachments", p.a("attachments") ?: JsonArray(emptyList()))
                    put("createdAt", (if (existing >= 0) messages[existing].s("createdAt") else null) ?: p.s("createdAt"))
                    put("updatedAt", p.s("updatedAt"))
                }
                val next = if (existing >= 0) messages.toMutableList().also { it[existing] = msg } else messages + msg
                // latestTurn is driven by session-set events, not messages.
                copy(messages = next)
            }
            "thread.activity-appended" -> {
                val activity = p.o("activity") ?: return this
                val id = activity.s("id")
                if (activities.any { it.s("id") == id }) this else copy(activities = activities + activity)
            }
            "thread.session-set" -> {
                val session = p.o("session") ?: return this
                val base = thread ?: return this
                val m = base.toMutableMap()
                m["session"] = session
                val status = session.s("status")
                val active = session.s("activeTurnId")
                val lt = base.o("latestTurn")
                if (status == "running" && active != null) {
                    m["latestTurn"] = buildJsonObject {
                        put("turnId", active)
                        put("state", "running")
                        put("requestedAt", lt?.takeIf { it.s("turnId") == active }?.s("requestedAt") ?: session.s("updatedAt"))
                        put("startedAt", lt?.takeIf { it.s("turnId") == active }?.s("startedAt") ?: session.s("updatedAt"))
                        put("completedAt", null as String?)
                    }
                } else if (lt?.s("state") == "running" && status != "starting") {
                    val settled = when (status) { "interrupted" -> "interrupted"; "error" -> "error"; else -> "completed" }
                    m["latestTurn"] = JsonObject(lt.toMutableMap().apply {
                        put("state", JsonPrimitive(settled)); put("completedAt", JsonPrimitive(session.s("updatedAt")))
                    })
                }
                copy(thread = JsonObject(m))
            }
            "thread.session-stop-requested" -> {
                val base = thread ?: return this
                val session = base.o("session") ?: return this
                patchThread(buildJsonObject {
                    put("session", JsonObject(session.toMutableMap().apply { put("status", JsonPrimitive("stopped")); put("activeTurnId", JsonNull) }))
                })
            }
            "thread.turn-interrupt-requested" -> {
                val lt = thread?.o("latestTurn") ?: return this
                if (p.s("turnId") != null && p.s("turnId") != lt.s("turnId")) this
                else patchThread(buildJsonObject { put("latestTurn", JsonObject(lt.toMutableMap().apply { put("state", JsonPrimitive("interrupted")) })) })
            }
            "thread.proposed-plan-upserted" -> {
                val plan = p.o("proposedPlan") ?: return this
                copy(plans = (plans.filterNot { it.s("id") == plan.s("id") } + plan).sortedBy { it.s("createdAt") })
            }
            "thread.turn-diff-completed" -> {
                val turnId = p.s("turnId") ?: return this
                val prev = checkpoints.firstOrNull { it.s("turnId") == turnId }
                if (prev != null && prev.s("status") != "missing" && p.s("status") == "missing") this
                else copy(checkpoints = checkpoints.filterNot { it.s("turnId") == turnId } + p)
            }
            "thread.reverted" -> {
                val count = (p["turnCount"] as? JsonPrimitive)?.intOrNull ?: return this
                val dropped = checkpoints.filter { ((it["checkpointTurnCount"] as? JsonPrimitive)?.intOrNull ?: 0) > count }.mapNotNull { it.s("turnId") }.toSet()
                copy(
                    checkpoints = checkpoints.filterNot { it.s("turnId") in dropped },
                    messages = messages.filterNot { it.s("turnId") in dropped },
                    activities = activities.filterNot { it.s("turnId") in dropped },
                    plans = plans.filterNot { it.s("turnId") in dropped },
                )
            }
            "thread.meta-updated", "thread.runtime-mode-set", "thread.interaction-mode-set", "thread.archived", "thread.unarchived",
            "thread.settled", "thread.unsettled", "thread.snoozed", "thread.unsnoozed", "thread.pinned", "thread.unpinned",
            "thread.pin-reordered", "thread.auto-settle-set" -> patchThread(JsonObject(p.filterKeys { it != "threadId" }))
            "thread.turn-start-requested" -> patchThread(JsonObject(p.filterKeys { it in setOf("modelSelection", "runtimeMode", "interactionMode") }))
            "thread.created" -> if (thread == null) copy(thread = p) else this
            else -> this
        }
    }

    /** Project into the v2-shaped state the UI renders. */
    fun toThreadState(): ThreadState {
        val t = thread ?: return ThreadState(sequence = sequence, error = error)
        val items = mutableListOf<Pair<String, TurnItem>>() // sort key → item
        val requests = mutableMapOf<String, RuntimeRequest>()
        var seq = 0

        fun key(time: String?, bump: Int = 0) = (time ?: "") + "#" + (seq++ + bump).toString().padStart(6, '0')

        // Messages
        messages.forEach { m ->
            val role = m.s("role")
            val type = when (role) { "user" -> "user_message"; "assistant" -> "assistant_message"; "reasoning" -> "reasoning"; else -> return@forEach }
            items += key(m.s("createdAt")) to item(type, m.s("id")!!, m.s("turnId"), if (m.b("streaming") == true) "running" else "completed", m.s("createdAt"), m.s("updatedAt")) {
                put("text", m.s("text").orEmpty())
                put("streaming", m.b("streaming") == true)
                put("attachments", m.a("attachments") ?: JsonArray(emptyList()))
                put("inputIntent", "turn_start")
            }
        }

        // Tools: group by toolCallId; a terminal status wins over a late "inProgress" update.
        val tools = linkedMapOf<String, MutableList<JsonObject>>()
        val resolved = mutableMapOf<String, JsonObject>()
        activities.forEach { a ->
            val p = a.o("payload") ?: return@forEach
            when (a.s("kind")) {
                "approval.resolved", "user-input.resolved" -> p.s("requestId")?.let { resolved[it] = p }
                "provider.approval.respond.failed", "provider.user-input.respond.failed" -> {
                    val d = p.s("detail").orEmpty()
                    if ("stale pending" in d || "unknown pending" in d) p.s("requestId")?.let { resolved[it] = p }
                }
            }
            if (a.s("kind")?.startsWith("tool.") == true) p.s("toolCallId")?.let { tools.getOrPut(it) { mutableListOf() } += a }
        }
        tools.forEach { (callId, acts) ->
            val payloads = acts.mapNotNull { it.o("payload") }
            val terminal = payloads.lastOrNull { it.s("status") in setOf("completed", "failed", "declined") }
            val latest = terminal ?: payloads.last()
            val richest = payloads.lastOrNull { (it.o("data")?.size ?: 0) > 1 } ?: latest
            val data = richest.o("data")
            val toolName = data?.s("toolName")
            val detail = (terminal ?: payloads.lastOrNull { it.s("detail")?.endsWith(": {}") == false } ?: latest).s("detail")
            val detailArgs = detail?.substringAfter(": ", "")?.takeIf { it.startsWith("{") }?.let { runCatching { T3Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
            val status = when (latest.s("status")) { "inProgress" -> "running"; "completed" -> "completed"; else -> "failed" }
            val first = acts.first()
            val last = acts.last()
            val type = when (latest.s("itemType")) {
                "command_execution" -> "command_execution"
                "file_change" -> "file_change"
                "web_search" -> "web_search"
                else -> "dynamic_tool"
            }
            items += key(first.s("createdAt")) to item(type, "tool:$callId", first.s("turnId"), status, first.s("createdAt"), if (status == "running") null else last.s("createdAt")) {
                when (type) {
                    "command_execution" -> {
                        put("input", data?.s("command") ?: detailArgs?.s("command") ?: detail?.substringAfter(": ")?.takeUnless { it == "{}" } ?: toolName ?: "command")
                        rawOutput(data)?.let { put("output", it) }
                        if (latest.s("status") == "failed") put("outputIndicatesFailure", true)
                    }
                    "file_change" -> {
                        put("fileName", detailArgs?.s("file_path") ?: detailArgs?.s("path") ?: data?.s("file_path") ?: toolName ?: "file")
                        detailArgs?.s("content")?.let { c -> put("diffStr", c.lines().joinToString("\n") { "+$it" }) }
                    }
                    "web_search" -> detailArgs?.s("query")?.let { q -> put("patterns", buildJsonArray { add(JsonPrimitive(q)) }) }
                    else -> {
                        put("toolName", toolName)
                        put("title", toolName ?: latest.s("title"))
                        detailArgs?.let { put("input", it) }
                        rawOutput(data)?.let { put("output", it) }
                    }
                }
            }
        }

        // Approvals / questions / errors / notices
        activities.forEach { a ->
            val p = a.o("payload") ?: return@forEach
            val time = a.s("createdAt")
            when (val kind = a.s("kind")) {
                "approval.requested" -> {
                    if (p.s("requestType") in setOf("tool_user_input", "auth_tokens_refresh")) return@forEach
                    val rid = p.s("requestId") ?: return@forEach
                    val done = resolved[rid]
                    requests[rid] = RuntimeRequest(rid, kind = V1.requestKind(p), status = if (done != null) "resolved" else "pending",
                        responseCapability = buildJsonObject { put("type", "live") }, createdAt = time, decision = done?.s("decision"))
                    items += key(time) to item("approval_request", "approval:$rid", a.s("turnId"), if (done != null) "completed" else "waiting", time, null) {
                        put("requestId", rid)
                        put("requestKind", V1.requestKind(p))
                        p.s("detail")?.let { put("prompt", it) }
                        p.s("appName")?.let { put("appName", it) }
                        p.a("options")?.let { put("options", it) }
                    }
                }
                "user-input.requested" -> {
                    val rid = p.s("requestId") ?: return@forEach
                    val done = resolved[rid]
                    requests[rid] = RuntimeRequest(rid, kind = "user_input", status = if (done != null) "resolved" else "pending",
                        responseCapability = buildJsonObject { put("type", if (p.s("responseMode") == "message") "message" else "live") }, createdAt = time)
                    items += key(time) to item("user_input_request", "input:$rid", a.s("turnId"), if (done != null) "completed" else "waiting", time, null) {
                        put("requestId", rid)
                        put("questions", p.a("questions") ?: JsonArray(emptyList()))
                        p.s("responseMode")?.let { put("responseMode", it) }
                    }
                }
                "runtime.error", "provider.turn.start.failed", "provider.turn.interrupt.failed", "provider.session.stop.failed", "provider.auth.signed-out" -> {
                    items += key(time) to item("error", "act:${a.s("id")}", a.s("turnId"), "completed", time, time) {
                        put("failure", buildJsonObject {
                            put("class", if ((p.s("detail") ?: a.s("summary")).orEmpty().contains("limit", true)) "usage_limit" else "provider_error")
                            put("message", p.s("detail") ?: p.s("message") ?: a.s("summary") ?: kind)
                        })
                    }
                }
                "checkpoint.capture.failed", "checkpoint.revert.failed", "setup-script.failed" -> {
                    items += key(time) to item("system_notice", "act:${a.s("id")}", a.s("turnId"), "completed", time, time) {
                        put("message", a.s("summary") ?: kind)
                    }
                }
            }
        }

        // Plans and checkpoints
        plans.forEach { pl ->
            items += key(pl.s("createdAt")) to item("proposed_plan", "plan:${pl.s("id")}", pl.s("turnId"), "completed", pl.s("createdAt"), pl.s("updatedAt")) {
                put("planId", pl.s("id"))
                put("markdown", pl.s("planMarkdown").orEmpty())
                put("streaming", false)
            }
        }
        val checkpointModels = mutableMapOf<String, Checkpoint>()
        checkpoints.forEach { c ->
            val turnId = c.s("turnId") ?: return@forEach
            val count = (c["checkpointTurnCount"] as? JsonPrimitive)?.intOrNull
            checkpointModels[turnId] = Checkpoint(turnId, count, c.s("status") ?: "ready", turnId)
            items += key(c.s("completedAt"), bump = 900000) to item("checkpoint", "checkpoint:$turnId", turnId, "completed", c.s("completedAt"), c.s("completedAt")) {
                put("checkpointId", turnId)
                put("files", c.a("files") ?: JsonArray(emptyList()))
            }
        }

        // Order by time, then attach user messages to the turn that follows them so turns group correctly.
        val ordered = items.sortedBy { it.first }.map { it.second }.toMutableList()
        for (i in ordered.indices) {
            val it = ordered[i]
            if (it.type == "user_message" && it.runId == null) {
                val next = ordered.drop(i + 1).takeWhile { n -> n.type != "user_message" }.firstNotNullOfOrNull { n -> n.runId }
                ordered[i] = TurnItem(JsonObject(it.raw.toMutableMap().apply { put("runId", next?.let(::JsonPrimitive) ?: JsonPrimitive("pending:${it.id}")) }))
            }
        }
        val finalItems = ordered.mapIndexed { idx, it -> TurnItem(JsonObject(it.raw.toMutableMap().apply { put("ordinal", JsonPrimitive(idx)) })) }

        // Runs: one per turn id.
        val shell = V1.threadShell(t)
        val latest = t.o("latestTurn")
        val runs = finalItems.mapNotNull { it.runId }.distinct().mapIndexed { idx, turnId ->
            val turnItems = finalItems.filter { it.runId == turnId }
            val isLatest = latest?.s("turnId") == turnId
            val status = when {
                isLatest && shell.status == "running" -> "running"
                isLatest && latest?.s("state") == "interrupted" -> "interrupted"
                isLatest && latest?.s("state") == "error" -> "failed"
                turnId.startsWith("pending:") && shell.status == "running" -> "running"
                else -> "completed"
            }
            turnId to Run(
                id = turnId, threadId = shell.id, ordinal = idx + 1, status = status,
                startedAt = (if (isLatest) latest?.s("startedAt") else null) ?: turnItems.firstOrNull()?.startedAt,
                completedAt = if (status == "running") null else (if (isLatest) latest?.s("completedAt") else null) ?: turnItems.lastOrNull()?.let { it.completedAt ?: it.updatedAt },
            )
        }.toMap()

        return ThreadState(
            sequence = sequence,
            thread = shell,
            runs = runs,
            requests = requests,
            checkpoints = checkpointModels,
            items = finalItems.associateBy { it.id },
            synchronized = synchronized,
            error = error,
        )
    }

    private fun rawOutput(data: JsonObject?): String? {
        val raw = data?.get("rawOutput") ?: return null
        return when (raw) {
            is JsonPrimitive -> raw.contentOrNull
            is JsonObject -> raw.s("content") ?: raw.s("stdout") ?: raw.s("output") ?: raw.toString()
            else -> raw.toString()
        }?.take(32_000)
    }

    private fun item(type: String, id: String, runId: String?, status: String, startedAt: String?, completedAt: String?, extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) =
        TurnItem(buildJsonObject {
            put("id", id)
            put("type", type)
            put("threadId", thread?.s("id"))
            put("runId", runId)
            put("status", status)
            put("startedAt", startedAt)
            put("completedAt", completedAt)
            put("updatedAt", completedAt ?: startedAt)
            extra()
        })
}
