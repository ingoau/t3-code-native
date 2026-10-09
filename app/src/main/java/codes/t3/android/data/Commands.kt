package codes.t3.android.data

import codes.t3.android.data.model.ModelSelection
import codes.t3.android.data.model.T3Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.UUID

/** Builders for `orchestration.dispatchCommand` payloads (see contracts `OrchestrationV2Command`). */
object Commands {
    fun newId(): String = UUID.randomUUID().toString()

    private fun command(type: String, threadId: String, extra: JsonObjectBuilder.() -> Unit = {}) = buildJsonObject {
        put("type", type)
        put("commandId", newId())
        put("threadId", threadId)
        extra()
    }

    fun modelSelectionJson(selection: ModelSelection): JsonElement = T3Json.encodeToJsonElement(selection)

    sealed interface DispatchMode {
        data object StartImmediately : DispatchMode
        data object Queue : DispatchMode
        data class Steer(val runId: String) : DispatchMode
    }

    fun sendMessage(
        threadId: String,
        text: String,
        modelSelection: ModelSelection?,
        mode: DispatchMode,
        serverResolvesContext: Boolean,
        attachments: JsonArray = JsonArray(emptyList()),
        sourcePlan: Pair<String, String>? = null,
    ) = buildJsonObject {
        put("type", "message.dispatch")
        put("commandId", newId())
        put("createdBy", "user")
        put("creationSource", "mobile")
        put("threadId", threadId)
        put("messageId", newId())
        put("text", text)
        put("attachments", attachments)
        modelSelection?.let { put("modelSelection", modelSelectionJson(it)) }
        sourcePlan?.let { (tid, planId) -> put("sourcePlanRef", buildJsonObject { put("threadId", tid); put("planId", planId) }) }
        when (mode) {
            DispatchMode.StartImmediately -> {
                if (serverResolvesContext) put("deliveryIntent", "auto")
                put("dispatchMode", buildJsonObject { put("type", "start_immediately") })
            }
            DispatchMode.Queue -> put("dispatchMode", buildJsonObject { put("type", "queue_after_active") })
            is DispatchMode.Steer -> {
                if (serverResolvesContext) {
                    put("deliveryIntent", "steer")
                    put("dispatchMode", buildJsonObject { put("type", "start_immediately") })
                } else {
                    put("dispatchMode", buildJsonObject { put("type", "steer_active"); put("targetRunId", mode.runId) })
                }
            }
        }
    }

    fun interrupt(threadId: String, runId: String) = command("run.interrupt", threadId) {
        put("runId", runId)
        put("holdQueue", true)
    }

    fun respondApproval(threadId: String, requestId: String, decision: String) = command("runtime-request.respond", threadId) {
        put("requestId", requestId)
        put("decision", decision)
    }

    fun respondAnswers(threadId: String, requestId: String, answers: Map<String, List<String>>, multi: Set<String>) =
        command("runtime-request.respond", threadId) {
            put("requestId", requestId)
            put("answers", buildJsonObject {
                answers.forEach { (questionId, values) ->
                    if (questionId in multi) put(questionId, buildJsonArray { values.forEach { add(JsonPrimitive(it)) } })
                    else put(questionId, values.firstOrNull() ?: "")
                }
            })
        }

    fun dismissQuestion(threadId: String, requestId: String) = command("thread.user-input.dismiss", threadId) {
        put("requestId", requestId)
    }

    fun setModel(threadId: String, selection: ModelSelection) = command("thread.model-selection.set", threadId) {
        put("modelSelection", modelSelectionJson(selection))
    }

    fun switchProvider(threadId: String, selection: ModelSelection) = command("provider.switch", threadId) {
        put("modelSelection", modelSelectionJson(selection))
    }

    fun setRuntimeMode(threadId: String, mode: String) = command("thread.runtime-mode.set", threadId) { put("runtimeMode", mode) }

    fun setInteractionMode(threadId: String, mode: String) = command("thread.interaction-mode.set", threadId) {
        put("interactionMode", mode)
    }

    fun simple(type: String, threadId: String) = command(type, threadId)

    fun pin(threadId: String) = command("thread.pin", threadId)
    fun unsettle(threadId: String) = command("thread.unsettle", threadId) { put("reason", "user") }
    fun unsnooze(threadId: String) = command("thread.unsnooze", threadId) { put("reason", "user") }
    fun snooze(threadId: String, until: Instant) = command("thread.snooze", threadId) { put("snoozedUntil", until.toString()) }
    fun visit(threadId: String, at: String?) = command("thread.visit", threadId) { put("visitedAt", at ?: Instant.now().toString()) }
    fun rename(threadId: String, title: String) = command("thread.metadata.update", threadId) { put("title", title) }
    fun cancelQueued(threadId: String, runId: String) = command("queued-run.cancel", threadId) { put("runId", runId) }

    sealed interface Workspace {
        data class Root(val branch: String? = null) : Workspace
        data class NewWorktree(val baseRef: String, val branch: String) : Workspace
    }

    fun launchThread(
        threadId: String,
        projectId: String,
        text: String,
        modelSelection: ModelSelection,
        runtimeMode: String,
        interactionMode: String,
        workspace: Workspace,
    ) = buildJsonObject {
        put("commandId", newId())
        put("creationSource", "mobile")
        put("threadId", threadId)
        put("projectId", projectId)
        put("title", titleSeed(text))
        put("generateTitle", true)
        put("modelSelection", modelSelectionJson(modelSelection))
        put("runtimeMode", runtimeMode)
        put("interactionMode", interactionMode)
        put("workspaceStrategy", when (workspace) {
            is Workspace.Root -> buildJsonObject {
                put("type", "root")
                workspace.branch?.let { put("branch", it) }
            }
            is Workspace.NewWorktree -> buildJsonObject {
                put("type", "worktree")
                put("baseRef", workspace.baseRef)
                put("branch", workspace.branch)
            }
        })
        put("initialMessage", buildJsonObject {
            put("messageId", newId())
            put("text", text)
            put("attachments", JsonArray(emptyList()))
        })
    }

    fun titleSeed(text: String): String {
        val firstLine = text.trim().lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        return if (firstLine.length <= 60) firstLine.ifEmpty { "New thread" } else firstLine.take(57).trimEnd() + "…"
    }

    fun worktreeBranch(): String = "t3/" + (1..8).map { "0123456789abcdef".random() }.joinToString("")

}
