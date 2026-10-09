package codes.t3.android.data

import codes.t3.android.data.model.ModelSelection
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

/** One RPC to run: method + payload. */
data class Op(val method: String, val payload: JsonObject)

/** Housekeeping actions that exist in both protocols (some are v2-only). */
enum class ThreadOp { Pin, Unpin, Settle, Unsettle, Archive, Unarchive, Delete, MarkUnread }

/** Builds the right command shapes for the server's orchestration protocol. */
interface ProtocolCommands {
    val supportsQueue: Boolean
    fun send(threadId: String, text: String, selection: ModelSelection?, mode: Commands.DispatchMode, attachments: JsonArray, sourcePlan: Pair<String, String>?, runtimeMode: String, interactionMode: String): Op
    fun interrupt(threadId: String, runId: String): Op
    fun respondApproval(threadId: String, requestId: String, decision: String): Op
    fun respondAnswers(threadId: String, requestId: String, answers: Map<String, List<String>>, multi: Set<String>): Op
    fun dismissQuestion(threadId: String, requestId: String): Op
    fun setModel(threadId: String, current: ModelSelection?, selection: ModelSelection): Op
    fun setRuntimeMode(threadId: String, mode: String): Op
    fun setInteractionMode(threadId: String, mode: String): Op
    fun rename(threadId: String, title: String): Op
    fun housekeeping(threadId: String, op: ThreadOp): Op?
    fun visit(threadId: String, at: String?): Op?
    fun launch(threadId: String, projectId: String, workspaceRoot: String, text: String, selection: ModelSelection, runtimeMode: String, interactionMode: String, workspace: Commands.Workspace, attachments: JsonArray): Op
    fun createProject(projectId: String, title: String, workspaceRoot: String): Op
    /** An empty thread (no first message). */
    fun createThread(threadId: String, projectId: String, title: String, selection: ModelSelection, runtimeMode: String, interactionMode: String): Op
    fun cancelQueued(threadId: String, runId: String): Op? = null
    fun promoteToSteer(threadId: String, queuedRunId: String, targetRunId: String): Op? = null
    fun resumeQueue(threadId: String): Op? = null
}

private fun dispatch(payload: JsonObject) = Op("orchestration.dispatchCommand", payload)

class V2Commands(private val serverResolvesContext: Boolean) : ProtocolCommands {
    override val supportsQueue = true
    override fun send(threadId: String, text: String, selection: ModelSelection?, mode: Commands.DispatchMode, attachments: JsonArray, sourcePlan: Pair<String, String>?, runtimeMode: String, interactionMode: String) =
        dispatch(Commands.sendMessage(threadId, text, selection, mode, serverResolvesContext, attachments, sourcePlan))
    override fun interrupt(threadId: String, runId: String) = dispatch(Commands.interrupt(threadId, runId))
    override fun respondApproval(threadId: String, requestId: String, decision: String) = dispatch(Commands.respondApproval(threadId, requestId, decision))
    override fun respondAnswers(threadId: String, requestId: String, answers: Map<String, List<String>>, multi: Set<String>) =
        dispatch(Commands.respondAnswers(threadId, requestId, answers, multi))
    override fun dismissQuestion(threadId: String, requestId: String) = dispatch(Commands.dismissQuestion(threadId, requestId))
    override fun setModel(threadId: String, current: ModelSelection?, selection: ModelSelection) = dispatch(
        if (current != null && current.instanceId != selection.instanceId && !serverResolvesContext) Commands.switchProvider(threadId, selection)
        else Commands.setModel(threadId, selection),
    )
    override fun setRuntimeMode(threadId: String, mode: String) = dispatch(Commands.setRuntimeMode(threadId, mode))
    override fun setInteractionMode(threadId: String, mode: String) = dispatch(Commands.setInteractionMode(threadId, mode))
    override fun rename(threadId: String, title: String) = dispatch(Commands.rename(threadId, title))
    override fun housekeeping(threadId: String, op: ThreadOp) = dispatch(
        when (op) {
            ThreadOp.Pin -> Commands.pin(threadId)
            ThreadOp.Unpin -> Commands.simple("thread.unpin", threadId)
            ThreadOp.Settle -> Commands.simple("thread.settle", threadId)
            ThreadOp.Unsettle -> Commands.unsettle(threadId)
            ThreadOp.Archive -> Commands.simple("thread.archive", threadId)
            ThreadOp.Unarchive -> Commands.simple("thread.unarchive", threadId)
            ThreadOp.Delete -> Commands.simple("thread.delete", threadId)
            ThreadOp.MarkUnread -> Commands.simple("thread.mark-unread", threadId)
        },
    )
    override fun visit(threadId: String, at: String?) = dispatch(Commands.visit(threadId, at))
    override fun launch(threadId: String, projectId: String, workspaceRoot: String, text: String, selection: ModelSelection, runtimeMode: String, interactionMode: String, workspace: Commands.Workspace, attachments: JsonArray) =
        Op("orchestration.launchThread", Commands.launchThread(threadId, projectId, text, selection, runtimeMode, interactionMode, workspace, attachments))
    override fun createProject(projectId: String, title: String, workspaceRoot: String) = Op("projects.mutate", buildJsonObject {
        put("type", "project.create")
        put("commandId", Commands.newId())
        put("projectId", projectId)
        put("title", title)
        put("workspaceRoot", workspaceRoot)
        put("createWorkspaceRootIfMissing", true)
    })
    override fun createThread(threadId: String, projectId: String, title: String, selection: ModelSelection, runtimeMode: String, interactionMode: String) = dispatch(buildJsonObject {
        put("type", "thread.create")
        put("commandId", Commands.newId())
        put("createdBy", "user")
        put("creationSource", "mobile")
        put("threadId", threadId)
        put("projectId", projectId)
        put("title", title)
        put("modelSelection", Commands.modelSelectionJson(selection))
        put("runtimeMode", runtimeMode)
        put("interactionMode", interactionMode)
        put("branch", null as String?)
        put("worktreePath", null as String?)
    })
    override fun cancelQueued(threadId: String, runId: String) = dispatch(Commands.cancelQueued(threadId, runId))
    override fun promoteToSteer(threadId: String, queuedRunId: String, targetRunId: String) = dispatch(Commands.promoteToSteer(threadId, queuedRunId, targetRunId))
    override fun resumeQueue(threadId: String) = dispatch(Commands.resumeQueue(threadId))
}

/** Command shapes for t3 ≤ 0.0.45 (orchestration protocol v1). */
object V1Commands : ProtocolCommands {
    override val supportsQueue = false
    private fun now() = Instant.now().toString()

    private fun cmd(type: String, threadId: String, withTime: Boolean = true, extra: JsonObjectBuilder.() -> Unit = {}) = dispatch(buildJsonObject {
        put("type", type)
        put("commandId", Commands.newId())
        put("threadId", threadId)
        extra()
        if (withTime) put("createdAt", now())
    })

    override fun send(threadId: String, text: String, selection: ModelSelection?, mode: Commands.DispatchMode, attachments: JsonArray, sourcePlan: Pair<String, String>?, runtimeMode: String, interactionMode: String) =
        cmd("thread.turn.start", threadId) {
            put("message", buildJsonObject {
                put("messageId", Commands.newId())
                put("role", "user")
                put("text", text)
                put("attachments", attachments)
            })
            selection?.let { put("modelSelection", Commands.modelSelectionJson(it)) }
            put("runtimeMode", runtimeMode)
            put("interactionMode", interactionMode)
            sourcePlan?.let { (tid, pid) -> put("sourceProposedPlan", buildJsonObject { put("threadId", tid); put("planId", pid) }) }
        }

    override fun interrupt(threadId: String, runId: String) = cmd("thread.turn.interrupt", threadId) {
        if (!runId.startsWith("v1-") && !runId.startsWith("pending:")) put("turnId", runId)
    }
    override fun respondApproval(threadId: String, requestId: String, decision: String) = cmd("thread.approval.respond", threadId) {
        put("requestId", requestId); put("decision", decision)
    }
    override fun respondAnswers(threadId: String, requestId: String, answers: Map<String, List<String>>, multi: Set<String>) = cmd("thread.user-input.respond", threadId) {
        put("requestId", requestId)
        put("answers", buildJsonObject {
            answers.forEach { (q, v) ->
                if (q in multi) put(q, buildJsonArray { v.forEach { add(JsonPrimitive(it)) } }) else put(q, v.firstOrNull() ?: "")
            }
        })
    }
    override fun dismissQuestion(threadId: String, requestId: String) = cmd("thread.user-input.dismiss", threadId) { put("requestId", requestId) }
    override fun setModel(threadId: String, current: ModelSelection?, selection: ModelSelection) = cmd("thread.meta.update", threadId, withTime = false) {
        put("modelSelection", Commands.modelSelectionJson(selection))
    }
    override fun setRuntimeMode(threadId: String, mode: String) = cmd("thread.runtime-mode.set", threadId) { put("runtimeMode", mode) }
    override fun setInteractionMode(threadId: String, mode: String) = cmd("thread.interaction-mode.set", threadId) { put("interactionMode", mode) }
    override fun rename(threadId: String, title: String) = cmd("thread.meta.update", threadId, withTime = false) { put("title", title) }
    override fun housekeeping(threadId: String, op: ThreadOp): Op? = when (op) {
        ThreadOp.Pin -> cmd("thread.pin", threadId, withTime = false)
        ThreadOp.Unpin -> cmd("thread.unpin", threadId, withTime = false)
        ThreadOp.Settle -> cmd("thread.settle", threadId, withTime = false)
        ThreadOp.Unsettle -> cmd("thread.unsettle", threadId, withTime = false) { put("reason", "user") }
        ThreadOp.Archive -> cmd("thread.archive", threadId, withTime = false)
        ThreadOp.Unarchive -> cmd("thread.unarchive", threadId, withTime = false)
        ThreadOp.Delete -> cmd("thread.delete", threadId, withTime = false)
        ThreadOp.MarkUnread -> null
    }
    override fun visit(threadId: String, at: String?): Op? = null

    override fun launch(threadId: String, projectId: String, workspaceRoot: String, text: String, selection: ModelSelection, runtimeMode: String, interactionMode: String, workspace: Commands.Workspace, attachments: JsonArray): Op {
        val title = Commands.titleSeed(text)
        val created = now()
        return dispatch(buildJsonObject {
            put("type", "thread.turn.start")
            put("commandId", Commands.newId())
            put("threadId", threadId)
            put("message", buildJsonObject {
                put("messageId", Commands.newId()); put("role", "user"); put("text", text); put("attachments", attachments)
            })
            put("modelSelection", Commands.modelSelectionJson(selection))
            put("titleSeed", title)
            put("runtimeMode", runtimeMode)
            put("interactionMode", interactionMode)
            put("bootstrap", buildJsonObject {
                put("createThread", buildJsonObject {
                    put("projectId", projectId)
                    put("title", title)
                    put("modelSelection", Commands.modelSelectionJson(selection))
                    put("runtimeMode", runtimeMode)
                    put("interactionMode", interactionMode)
                    put("branch", (workspace as? Commands.Workspace.Root)?.branch)
                    put("worktreePath", null as String?)
                    put("createdAt", created)
                })
                if (workspace is Commands.Workspace.NewWorktree) {
                    put("prepareWorktree", buildJsonObject {
                        put("projectCwd", workspaceRoot)
                        put("baseBranch", workspace.baseRef)
                        put("branch", workspace.branch)
                    })
                }
            })
            put("createdAt", created)
        })
    }

    override fun createProject(projectId: String, title: String, workspaceRoot: String) = dispatch(buildJsonObject {
        put("type", "project.create")
        put("commandId", Commands.newId())
        put("projectId", projectId)
        put("title", title)
        put("workspaceRoot", workspaceRoot)
        put("createWorkspaceRootIfMissing", true)
        put("createdAt", now())
    })

    override fun createThread(threadId: String, projectId: String, title: String, selection: ModelSelection, runtimeMode: String, interactionMode: String) =
        cmd("thread.create", threadId) {
            put("projectId", projectId)
            put("title", title)
            put("modelSelection", Commands.modelSelectionJson(selection))
            put("runtimeMode", runtimeMode)
            put("interactionMode", interactionMode)
            put("branch", null as String?)
            put("worktreePath", null as String?)
        }
}
