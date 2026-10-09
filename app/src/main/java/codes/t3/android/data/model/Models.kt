package codes.t3.android.data.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** Lenient JSON shared across the app: the server adds fields and union members over time. */
val T3Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
    isLenient = true
    encodeDefaults = true
}

// ---------------------------------------------------------------- environment descriptor

@Serializable
data class EnvironmentPlatform(val os: String? = null, val arch: String? = null, val machine: String? = null)

@Serializable
data class EnvironmentDescriptor(
    val environmentId: String,
    val label: String = "",
    val platform: EnvironmentPlatform? = null,
    val serverVersion: String? = null,
    val orchestrationProtocolVersion: Int? = null,
    val capabilities: JsonObject = JsonObject(emptyMap()),
) {
    fun capability(name: String): Boolean = (capabilities[name] as? JsonPrimitive)?.booleanOrNull == true
}

// ---------------------------------------------------------------- server config / providers

@Serializable
data class ModelOptionValue(val id: String, val value: JsonPrimitive)

@Serializable
data class ModelSelection(
    val instanceId: String,
    val model: String,
    val options: List<ModelOptionValue>? = null,
) {
    fun option(id: String): JsonPrimitive? = options?.firstOrNull { it.id == id }?.value

    fun withOption(id: String, value: JsonPrimitive): ModelSelection {
        val rest = options.orEmpty().filterNot { it.id == id }
        return copy(options = rest + ModelOptionValue(id, value))
    }
}

@Serializable
data class OptionChoice(val id: String, val label: String = "", val isDefault: Boolean? = null, val description: String? = null)

@Serializable
data class OptionDescriptor(
    val type: String,
    val id: String,
    val label: String = "",
    val description: String? = null,
    val options: List<OptionChoice> = emptyList(),
    val currentValue: JsonElement? = null,
)

@Serializable
data class ModelCapabilities(val optionDescriptors: List<OptionDescriptor> = emptyList())

@Serializable
data class ProviderModel(
    val slug: String,
    val name: String = slug,
    val shortName: String? = null,
    val subProvider: String? = null,
    val badge: String? = null,
    val isCustom: Boolean = false,
    val isDefault: Boolean? = null,
    val isLegacy: Boolean? = null,
    val capabilities: ModelCapabilities? = null,
)

@Serializable
data class ProviderAuth(val status: String = "unknown", val label: String? = null, val email: String? = null)

@Serializable
data class SlashCommand(val name: String, val description: String? = null)

@Serializable
data class ServerProvider(
    val instanceId: String,
    val driver: String = "",
    val displayName: String? = null,
    val accentColor: String? = null,
    val badgeLabel: String? = null,
    val showInteractionModeToggle: Boolean? = null,
    val supportedRuntimeModes: List<String>? = null,
    val enabled: Boolean = true,
    val installed: Boolean = true,
    val version: String? = null,
    val status: String = "ready",
    val auth: ProviderAuth? = null,
    val message: String? = null,
    val availability: String? = null,
    val unavailableReason: String? = null,
    val models: List<ProviderModel> = emptyList(),
    val slashCommands: List<SlashCommand> = emptyList(),
) {
    val label: String get() = displayName ?: driverLabel(driver, instanceId)
    val selectable: Boolean get() = enabled && installed && availability != "unavailable" && status != "disabled"
}

fun driverLabel(driver: String, fallback: String = driver): String = when (driver) {
    "codex" -> "Codex"
    "claudeAgent", "claude" -> "Claude"
    "cursor" -> "Cursor"
    "grok" -> "Grok"
    "opencode" -> "OpenCode"
    "antigravity" -> "Antigravity"
    "pi" -> "Pi"
    "muse" -> "Muse"
    else -> fallback.replaceFirstChar { it.uppercase() }
}

@Serializable
data class ServerSettingsLite(
    val defaultModelSelection: ModelSelection? = null,
    val defaultRuntimeMode: String? = null,
    val defaultThreadEnvMode: String? = null,
)

@Serializable
data class ServerConfig(
    val environment: EnvironmentDescriptor,
    val cwd: String? = null,
    val providers: List<ServerProvider> = emptyList(),
    val settings: ServerSettingsLite? = null,
    val shellResumeCompletionMarker: Boolean? = null,
    val threadResumeCompletionMarker: Boolean? = null,
    val scratchWorkspaceRoot: String? = null,
)

// ---------------------------------------------------------------- shell

@Serializable
data class ProjectShell(
    val id: String,
    val title: String,
    val workspaceRoot: String = "",
    val defaultModelSelection: ModelSelection? = null,
    val defaultThreadEnvMode: String? = null,
    val faviconPath: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

@Serializable
data class PendingRuntimeRequestRef(val id: String, val kind: String, val createdAt: String? = null)

@Serializable
data class LatestVisibleMessage(val id: String, val role: String, val text: String = "", val updatedAt: String? = null)

@Serializable
data class ThreadLineage(val parentThreadId: String? = null, val relationshipToParent: String? = null, val rootThreadId: String? = null)

@Serializable
data class PullRequestRef(val number: Int? = null, val url: String? = null, val state: String? = null)

@Serializable
data class ThreadShell(
    val id: String,
    val projectId: String,
    val title: String = "",
    val createdBy: String? = null,
    val creationSource: String? = null,
    val providerInstanceId: String? = null,
    val modelSelection: ModelSelection? = null,
    val runtimeMode: String = "full-access",
    val interactionMode: String = "default",
    val branch: String? = null,
    val worktreePath: String? = null,
    val linkedPullRequest: PullRequestRef? = null,
    val lineage: ThreadLineage? = null,
    val latestRunId: String? = null,
    val latestRunStartedAt: String? = null,
    val latestRunCompletedAt: String? = null,
    val activeRunId: String? = null,
    val activityRunStartedAt: String? = null,
    val activityRunStatus: String? = null,
    val status: String = "idle",
    val lastError: String? = null,
    val lastErrorClass: String? = null,
    val pendingRuntimeRequest: PendingRuntimeRequestRef? = null,
    val latestVisibleMessage: LatestVisibleMessage? = null,
    val latestUserMessageAt: String? = null,
    val hasActionableProposedPlan: Boolean = false,
    val itemCount: Int = 0,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val archivedAt: String? = null,
    val settledOverride: String? = null,
    val settledAt: String? = null,
    val snoozedUntil: String? = null,
    val pinnedAt: String? = null,
    val pinOrderKey: String? = null,
    val activeOrderKey: String? = null,
    val lastVisitedAt: String? = null,
    val deletedAt: String? = null,
) {
    val isWorking: Boolean get() = activeRunId != null || status in WorkingStatuses
    val isPinned: Boolean get() = pinnedAt != null
    val isSettled: Boolean get() = settledAt != null && settledOverride != "active"
    val isSnoozed: Boolean get() = snoozedUntil != null
    val isSubagent: Boolean get() = lineage?.relationshipToParent == "subagent"
}

val WorkingStatuses = setOf("preparing", "queued", "starting", "running", "waiting")

@Serializable
data class ShellSnapshot(
    val schemaVersion: Int = 1,
    val snapshotSequence: Long = 0,
    val projects: List<ProjectShell> = emptyList(),
    val threads: List<ThreadShell> = emptyList(),
)

// ---------------------------------------------------------------- thread projection

@Serializable
data class Run(
    val id: String,
    val threadId: String = "",
    val ordinal: Int = 0,
    val providerInstanceId: String? = null,
    val modelSelection: ModelSelection? = null,
    val userMessageId: String? = null,
    val status: String = "queued",
    val queuePosition: Int? = null,
    val queueHeld: Boolean? = null,
    val requestedAt: String? = null,
    val startedAt: String? = null,
    val completedAt: String? = null,
    val workStartedAt: String? = null,
)

@Serializable
data class RunAttempt(val id: String, val runId: String = "", val rootNodeId: String? = null, val status: String = "")

@Serializable
data class TurnCapabilities(
    val supportsInterrupt: Boolean? = null,
    val supportsActiveSteering: Boolean? = null,
    val supportsQueuedMessages: Boolean? = null,
)

@Serializable
data class SessionCapabilities(val turns: TurnCapabilities? = null)

@Serializable
data class ProviderSession(
    val id: String,
    val driver: String? = null,
    val providerInstanceId: String? = null,
    val status: String = "",
    val model: String? = null,
    val capabilities: SessionCapabilities? = null,
)

@Serializable
data class TokenUsage(val usedTokens: Long? = null, val maxTokens: Long? = null)

@Serializable
data class ProviderThread(val id: String, val status: String = "", val contextUsage: TokenUsage? = null)

@Serializable
data class RuntimeRequest(
    val id: String,
    val nodeId: String? = null,
    val kind: String,
    val status: String,
    val responseCapability: JsonObject? = null,
    val createdAt: String? = null,
    val resolvedAt: String? = null,
    val decision: String? = null,
)

@Serializable
data class ProjectedTurnItem(
    val position: Int = 0,
    val visibility: String = "local",
    val sourceThreadId: String? = null,
    val sourceItemId: String? = null,
    val item: TurnItem,
)

@Serializable
data class ThreadProjection(
    val thread: ThreadShell,
    val runs: List<Run> = emptyList(),
    val attempts: List<RunAttempt> = emptyList(),
    val providerSessions: List<ProviderSession> = emptyList(),
    val providerThreads: List<ProviderThread> = emptyList(),
    val runtimeRequests: List<RuntimeRequest> = emptyList(),
    val turnItems: List<TurnItem> = emptyList(),
    val visibleTurnItems: List<ProjectedTurnItem> = emptyList(),
    val updatedAt: String? = null,
)

/**
 * A timeline item. The server's union has ~20 `type`s and grows over time, so we keep the raw object and expose
 * typed accessors for the fields the UI needs.
 */
@Serializable(with = TurnItemSerializer::class)
data class TurnItem(val raw: JsonObject) {
    val id: String get() = str("id") ?: ""
    val type: String get() = str("type") ?: "unknown"
    val threadId: String? get() = str("threadId")
    val runId: String? get() = str("runId")
    val nodeId: String? get() = str("nodeId")
    val parentItemId: String? get() = str("parentItemId")
    val ordinal: Long get() = (raw["ordinal"] as? JsonPrimitive)?.longOrNull ?: 0
    val status: String get() = str("status") ?: "completed"
    val title: String? get() = str("title")
    val startedAt: String? get() = str("startedAt")
    val completedAt: String? get() = str("completedAt")
    val updatedAt: String? get() = str("updatedAt")

    // message-like
    val text: String get() = str("text") ?: str("markdown") ?: str("message") ?: ""
    val streaming: Boolean get() = bool("streaming") == true
    val inputIntent: String? get() = str("inputIntent")
    val messageId: String? get() = str("messageId")
    val attachments: JsonArray get() = raw["attachments"] as? JsonArray ?: JsonArray(emptyList())

    // command_execution
    val commandInput: String? get() = str("input")
    val output: String? get() = str("output")
    val outputOmitted: Boolean get() = bool("outputOmitted") == true
    val exitCode: Int? get() = (raw["exitCode"] as? JsonPrimitive)?.intOrNull
    val outputIndicatesFailure: Boolean get() = bool("outputIndicatesFailure") == true

    // file_change
    val fileName: String? get() = str("fileName")
    val additions: Int? get() = (raw["additions"] as? JsonPrimitive)?.intOrNull
    val deletions: Int? get() = (raw["deletions"] as? JsonPrimitive)?.intOrNull
    val diff: String? get() = str("diffStr")

    // requests
    val requestId: String? get() = str("requestId")
    val requestKind: String? get() = str("requestKind")
    val prompt: String? get() = str("prompt")
    val appName: String? get() = str("appName")

    // dynamic_tool
    val toolName: String? get() = str("toolName")

    fun str(key: String): String? = (raw[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
    fun bool(key: String): Boolean? = (raw[key] as? JsonPrimitive)?.booleanOrNull
    fun obj(key: String): JsonObject? = raw[key] as? JsonObject
    fun arr(key: String): JsonArray? = raw[key] as? JsonArray

    val isRunning: Boolean get() = status == "running" || status == "pending" || status == "waiting" || streaming
    val isFailed: Boolean get() = status == "failed" || outputIndicatesFailure || (exitCode != null && exitCode != 0)
}

object TurnItemSerializer : KSerializer<TurnItem> {
    override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor
    override fun deserialize(decoder: Decoder): TurnItem =
        TurnItem((decoder as JsonDecoder).decodeJsonElement() as JsonObject)
    override fun serialize(encoder: Encoder, value: TurnItem) =
        (encoder as JsonEncoder).encodeJsonElement(value.raw)
}

// ---------------------------------------------------------------- questions / approvals

@Serializable
data class QuestionOption(val label: String, val description: String? = null, val value: String? = null)

@Serializable
data class UserInputQuestion(
    val id: String,
    val header: String? = null,
    val question: String = "",
    val options: List<QuestionOption> = emptyList(),
    val multiSelect: Boolean? = null,
    val allowCustomAnswer: Boolean? = null,
    val required: Boolean? = null,
)

@Serializable
data class ApprovalOption(val decision: String, val label: String = decision, val warning: String? = null)

data class PendingApproval(
    val requestId: String,
    val kind: String,
    val title: String,
    val detail: String?,
    val options: List<ApprovalOption>,
    val stale: Boolean,
)

data class PendingUserInput(
    val requestId: String,
    val questions: List<UserInputQuestion>,
    val dismissible: Boolean,
    val stale: Boolean,
)

// ---------------------------------------------------------------- enums used by the UI

enum class RuntimeMode(val wire: String, val label: String, val description: String) {
    Supervised("approval-required", "Supervised", "Ask before commands and file changes."),
    AutoEdits("auto-accept-edits", "Auto-accept edits", "Auto-approve edits, ask before other actions."),
    Auto("auto", "Auto", "Supported providers approve routine actions; others still ask."),
    FullAccess("full-access", "Full access", "Allow commands and edits without prompts.");

    companion object {
        fun of(wire: String?) = entries.firstOrNull { it.wire == wire } ?: FullAccess
    }
}
