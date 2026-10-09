package codes.t3.android.ui.thread

import codes.t3.android.ui.components.rememberHaptics
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FormatListNumbered
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Difference
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import codes.t3.android.data.ConnectionStatus
import codes.t3.android.data.model.ModelSelection
import codes.t3.android.data.model.RuntimeMode
import codes.t3.android.data.model.ThreadShell
import codes.t3.android.data.model.TurnItem
import codes.t3.android.data.state.ThreadState
import codes.t3.android.ui.components.EmptyState
import codes.t3.android.ui.home.RenameDialog
import codes.t3.android.ui.home.ThreadAction
import codes.t3.android.ui.util.formatElapsed
import codes.t3.android.ui.util.parseInstant
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

data class ThreadUiState(
    val title: String,
    val subtitle: String,
    val shell: ThreadShell?,
    val detail: ThreadState,
    val connection: ConnectionStatus,
    val environmentLabel: String,
    val composer: ComposerModel,
    val wrapCode: Boolean = false,
    val enterToSend: Boolean = false,
    val showPlanToggle: Boolean = false,
    val attachments: List<codes.t3.android.ui.app.PendingAttachment> = emptyList(),
    val canAttach: Boolean = false,
    /** Text of a message that failed to send, to put back into the composer. */
    val restoredDraft: String? = null,
)

class ThreadCallbacks(
    val onBack: () -> Unit = {},
    val onSend: (String, SendMode) -> Unit = { _, _ -> },
    val onStop: () -> Unit = {},
    val onApproval: (String, String) -> Unit = { _, _ -> },
    val onAnswer: (String, Map<String, List<String>>, Set<String>) -> Unit = { _, _, _ -> },
    val onDismissQuestion: (String) -> Unit = {},
    val onModelChange: (ModelSelection, RuntimeMode) -> Unit = { _, _ -> },
    val onRuntimeMode: (RuntimeMode) -> Unit = {},
    val onTogglePlan: () -> Unit = {},
    val onThreadAction: (ThreadAction) -> Unit = {},
    val onImplementPlan: (TurnItem) -> Unit = {},
    val loadFullItem: suspend (String) -> TurnItem? = { null },
    val onReconnect: () -> Unit = {},
    val onPickImages: (List<android.net.Uri>) -> Unit = {},
    val onRemoveAttachment: (String) -> Unit = {},
    val onCancelQueued: (String) -> Unit = {},
    val onSteerQueued: (String) -> Unit = {},
    val onResumeQueue: () -> Unit = {},
    val onViewDiff: (fromTurn: Int, toTurn: Int, title: String) -> Unit = { _, _, _ -> },
    val onDraftRestored: () -> Unit = {},
    val loadImage: suspend (kotlinx.serialization.json.JsonObject) -> androidx.compose.ui.graphics.ImageBitmap? = { null },
)

@Composable
fun ThreadScreen(state: ThreadUiState, callbacks: ThreadCallbacks, draftKey: String, modifier: Modifier = Modifier) {
    var text by rememberSaveable(draftKey) { mutableStateOf("") }
    LaunchedEffect(state.restoredDraft) {
        state.restoredDraft?.let { if (text.isBlank()) text = it; callbacks.onDraftRestored() }
    }
    var pickerOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val detail = state.detail
    val activeRunId = state.shell?.activeRunId ?: detail.activeRun?.id
    val feed = remember(detail, activeRunId) { buildFeed(detail.timeline, detail.runs, activeRunId).asReversed() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val atBottom by remember { derivedStateOf { listState.firstVisibleItemIndex <= 1 } }
    val approvals = detail.pendingApprovals
    val question = detail.pendingUserInput
    val checkpoints = detail.checkpoints
    val actions = remember(callbacks, checkpoints) {
        FeedActions(
            onImplementPlan = callbacks.onImplementPlan,
            loadFullItem = callbacks.loadFullItem,
            loadImage = callbacks.loadImage,
            onViewDiff = { id ->
                checkpoints[id]?.appRunOrdinal?.let { turn -> callbacks.onViewDiff((turn - 1).coerceAtLeast(0), turn, "Turn $turn changes") }
            },
        )
    }
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia(6),
    ) { uris -> if (uris.isNotEmpty()) callbacks.onPickImages(uris) }

    // A satisfying tap when the agent finishes a turn.
    val haptics = rememberHaptics()
    val working = state.shell?.isWorking == true || detail.activeRun != null
    var wasWorking by remember { mutableStateOf(working) }
    LaunchedEffect(working) {
        if (wasWorking && !working && detail.loaded) haptics.confirm()
        wasWorking = working
    }

    // Keep following new content while the user is at the bottom.
    LaunchedEffect(feed.firstOrNull()?.key, feed.size) { if (atBottom) listState.animateScrollToItem(0) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = callbacks.onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                title = {
                    Column {
                        Text(state.title.ifBlank { "New thread" }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(state.subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                actions = {
                    detail.latestTurnCount?.let { turn ->
                        IconButton(onClick = { callbacks.onViewDiff(0, turn, "All changes") }) { Icon(Icons.Rounded.Difference, "View all changes") }
                    }
                    ThreadOverflow(state.shell, callbacks, onRename = { renaming = true }, onDelete = { confirmDelete = true })
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            )
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) { padding ->
        Surface(
            modifier = Modifier.padding(top = padding.calculateTopPadding()).fillMaxSize(),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.fillMaxSize().imePadding()) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        !detail.loaded && detail.error != null -> EmptyState(Icons.Rounded.CloudOff, "Could not load conversation", detail.error, actionLabel = "Retry", onAction = callbacks.onReconnect)
                        !detail.loaded -> EmptyState(Icons.Rounded.Forum, "Opening thread…", "Loading messages from ${state.environmentLabel}.", loading = true)
                        feed.isEmpty() -> EmptyState(Icons.Rounded.Forum, "No conversation yet", "Ask the agent to inspect the repo, run a command, or make a change.")
                        else -> LazyColumn(
                            state = listState,
                            reverseLayout = true,
                            contentPadding = PaddingValues(top = 12.dp, bottom = 56.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(feed, key = { it.key }, contentType = { it::class }) { entry ->
                                Box(Modifier.fillMaxWidth().animateItem(), contentAlignment = Alignment.TopCenter) {
                                    Box(Modifier.widthIn(max = 840.dp)) { FeedRow(entry, state.wrapCode, actions) }
                                }
                            }
                        }
                    }
                    StatusPill(
                        state = state,
                        showScrollDown = !atBottom && feed.isNotEmpty(),
                        onScrollDown = { scope.launch { listState.animateScrollToItem(0) } },
                        onReconnect = callbacks.onReconnect,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
                    )
                }
                Column(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 10.dp, end = 10.dp, bottom = 8.dp, top = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AnimatedVisibility(detail.queued.isNotEmpty(), enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
                        QueueCard(detail, canSteer = detail.activeRun != null, callbacks)
                    }
                    AnimatedVisibility(approvals.isNotEmpty(), enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
                        approvals.firstOrNull()?.let { a -> ApprovalCard(a, onDecision = { callbacks.onApproval(a.requestId, it) }) }
                    }
                    if (question != null) {
                        UserInputCard(
                            question,
                            onSubmit = { answers, multi -> callbacks.onAnswer(question.requestId, answers, multi) },
                            onDismiss = if (question.dismissible) ({ callbacks.onDismissQuestion(question.requestId) }) else null,
                        )
                    } else {
                        Composer(
                            text = text,
                            onTextChange = { text = it },
                            model = state.composer,
                            onSend = { mode -> callbacks.onSend(text.trim(), mode); text = "" },
                            onStop = callbacks.onStop,
                            onOpenModelPicker = { pickerOpen = true },
                            onTogglePlan = if (state.showPlanToggle) callbacks.onTogglePlan else null,
                            onRuntimeMode = callbacks.onRuntimeMode,
                            enterToSend = state.enterToSend,
                            attachments = state.attachments,
                            onAddAttachment = if (state.canAttach) ({
                                picker.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
                            }) else null,
                            onRemoveAttachment = callbacks.onRemoveAttachment,
                        )
                    }
                }
            }
        }
    }

    if (pickerOpen) {
        ModelPickerSheet(
            providers = state.composer.providers,
            selection = state.composer.selection,
            runtimeMode = state.composer.runtimeMode,
            onConfirm = { sel, rt -> pickerOpen = false; callbacks.onModelChange(sel, rt) },
            onDismiss = { pickerOpen = false },
        )
    }
    if (renaming) {
        RenameDialog(state.title, onDismiss = { renaming = false }) { callbacks.onThreadAction(ThreadAction.Rename(it)); renaming = false }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            icon = { Icon(Icons.Rounded.Delete, null) },
            title = { Text("Delete thread?") },
            text = { Text("“${state.title}” will be permanently deleted, including its terminal history.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; callbacks.onThreadAction(ThreadAction.Delete) }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ThreadOverflow(shell: ThreadShell?, callbacks: ThreadCallbacks, onRename: () -> Unit, onDelete: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Rounded.MoreVert, "Thread options") }
        DropdownMenu(open, onDismissRequest = { open = false }, shape = RoundedCornerShape(20.dp)) {
            fun act(a: ThreadAction) { open = false; callbacks.onThreadAction(a) }
            DropdownMenuItem(
                text = { Text(if (shell?.isPinned == true) "Unpin" else "Pin") },
                leadingIcon = { Icon(Icons.Rounded.PushPin, null) },
                onClick = { act(if (shell?.isPinned == true) ThreadAction.Unpin else ThreadAction.Pin) },
            )
            DropdownMenuItem(
                text = { Text(if (shell?.isSettled == true) "Un-settle" else "Settle") },
                leadingIcon = { Icon(Icons.Rounded.CheckCircle, null) },
                onClick = { act(if (shell?.isSettled == true) ThreadAction.Unsettle else ThreadAction.Settle) },
            )
            DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Rounded.Edit, null) }, onClick = { open = false; onRename() })
            DropdownMenuItem(
                text = { Text("Copy thread ID") },
                leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) },
                onClick = { open = false; shell?.id?.let { clipboard.setText(AnnotatedString(it)) } },
            )
            DropdownMenuItem(text = { Text("Archive") }, leadingIcon = { Icon(Icons.Rounded.Archive, null) }, onClick = { act(ThreadAction.Archive) })
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) },
                onClick = { open = false; onDelete() },
            )
        }
    }
}

/** Floating pill above the composer: connection trouble, or "Working 1m 23s", plus a jump-to-latest button. */
@Composable
private fun StatusPill(
    state: ThreadUiState,
    showScrollDown: Boolean,
    onScrollDown: () -> Unit,
    onReconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shell = state.shell
    val run = state.detail.activeRun
    val working = shell?.isWorking == true || run != null
    val startedAt = parseInstant(shell?.activityRunStartedAt ?: run?.workStartedAt ?: run?.startedAt ?: run?.requestedAt)
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(working) { while (working) { now = System.currentTimeMillis(); delay(1000) } }
    val connectionLabel = when (val c = state.connection) {
        is ConnectionStatus.Reconnecting -> "Reconnecting to ${state.environmentLabel}…"
        is ConnectionStatus.Blocked -> c.error
        ConnectionStatus.Connecting -> "Connecting to ${state.environmentLabel}…"
        ConnectionStatus.Disabled -> "${state.environmentLabel} is turned off"
        // Connected but this thread's live updates failed: say so instead of silently going stale.
        ConnectionStatus.Connected -> state.detail.error?.takeIf { state.detail.loaded }?.let { "Not syncing · tap to retry" }
    }
    val queued = state.detail.queuedRuns.size
    val label: String? = when {
        connectionLabel != null -> connectionLabel
        state.detail.pendingApprovals.isNotEmpty() || state.detail.pendingUserInput != null -> null
        working -> buildString {
            append(if (shell?.activityRunStatus == "preparing" || run?.status == "preparing") "Preparing" else "Working")
            startedAt?.let { append(" ").append(formatElapsed(Duration.between(it, Instant.ofEpochMilli(now)))) }
            if (queued > 0) append(" · $queued queued")
        }
        else -> null
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AnimatedContent(
            label,
            transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.9f)) togetherWith (fadeOut() + scaleOut(targetScale = 0.9f)) using SizeTransform(clip = false) },
            label = "status",
        ) { text ->
            if (text != null) {
                Surface(
                    onClick = { if (connectionLabel != null) onReconnect() },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.inverseSurface,
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                    shadowElevation = 4.dp,
                ) {
                    Row(Modifier.padding(start = 8.dp, end = 16.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (connectionLabel != null && state.connection !is ConnectionStatus.Connecting) {
                            Icon(Icons.Rounded.CloudOff, null, Modifier.padding(start = 4.dp).size(18.dp))
                        } else {
                            LoadingIndicator(Modifier.size(24.dp), color = MaterialTheme.colorScheme.inversePrimary)
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 260.dp))
                    }
                }
            }
        }
        AnimatedVisibility(showScrollDown, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
            SmallFloatingActionButton(onClick = onScrollDown, containerColor = MaterialTheme.colorScheme.secondaryContainer) {
                Icon(Icons.Rounded.KeyboardArrowDown, "Scroll to latest")
            }
        }
    }
}

@Composable
private fun QueueCard(detail: ThreadState, canSteer: Boolean, callbacks: ThreadCallbacks) {
    val held = detail.queuedRuns.any { it.queueHeld == true }
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.FormatListNumbered, null, Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (held) "Queue paused" else "Queued · runs after the current turn",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                if (held) TextButton(onClick = callbacks.onResumeQueue) { Text("Resume") }
            }
            detail.queued.forEach { (run, message) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        message?.text?.ifBlank { null } ?: if ((message?.attachments?.size ?: 0) > 0) "Attachments" else "Queued message",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(vertical = 4.dp),
                    )
                    if (canSteer) IconButton(onClick = { callbacks.onSteerQueued(run.id) }) { Icon(Icons.Rounded.Bolt, "Steer now") }
                    IconButton(onClick = { callbacks.onCancelQueued(run.id) }) { Icon(Icons.Rounded.Close, "Remove from queue") }
                }
            }
        }
    }
}
