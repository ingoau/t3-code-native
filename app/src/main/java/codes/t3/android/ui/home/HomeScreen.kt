package codes.t3.android.ui.home

import codes.t3.android.ui.components.rememberHaptics
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.CallSplit
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.MarkEmailUnread
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import codes.t3.android.R
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.launch
import codes.t3.android.data.ConnectionStatus
import codes.t3.android.data.EnvironmentSnapshot
import codes.t3.android.data.ProjectEntry
import codes.t3.android.data.ThreadEntry
import codes.t3.android.ui.components.BrandLockup
import codes.t3.android.ui.components.EmptyState
import codes.t3.android.ui.util.ThreadBadge
import codes.t3.android.ui.util.badge
import codes.t3.android.ui.util.color
import codes.t3.android.ui.util.containerColor
import codes.t3.android.ui.util.relativeAge

data class HomeUiState(
    val environmentsLoaded: Boolean = false,
    val environments: List<EnvironmentSnapshot> = emptyList(),
    val threads: List<ThreadEntry> = emptyList(),
    val projects: List<ProjectEntry> = emptyList(),
    val showSettled: Boolean = true,
)

sealed interface ThreadAction {
    data object Pin : ThreadAction
    data object Unpin : ThreadAction
    data object Settle : ThreadAction
    data object Unsettle : ThreadAction
    data object Archive : ThreadAction
    data object Delete : ThreadAction
    data object MarkUnread : ThreadAction
    data class Rename(val title: String) : ThreadAction
}

private fun ThreadEntry.key() = "$environmentId/${thread.id}"

private fun ThreadEntry.sortTime(): String = thread.latestUserMessageAt ?: thread.updatedAt ?: thread.createdAt ?: ""

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    state: HomeUiState,
    onOpenThread: (ThreadEntry) -> Unit,
    onNewThread: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenEnvironments: () -> Unit,
    onAddEnvironment: () -> Unit,
    onThreadAction: (ThreadEntry, ThreadAction) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var projectFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var settledExpanded by rememberSaveable { mutableStateOf(true) }
    var renaming by remember { mutableStateOf<ThreadEntry?>(null) }
    var deleting by remember { mutableStateOf<ThreadEntry?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    val haptics = rememberHaptics()
    LaunchedEffect(refreshing) { if (refreshing) { kotlinx.coroutines.delay(900); refreshing = false } }

    val listState = rememberLazyListState()
    val fabExpanded by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }
    val scroll = TopAppBarDefaults.enterAlwaysScrollBehavior()

    val visible = state.threads
        .filter { it.thread.archivedAt == null && !it.thread.isSubagent }
        .filter { projectFilter == null || it.project?.id == projectFilter }
        .filter {
            query.isBlank() || it.thread.title.contains(query, true) ||
                (it.project?.title?.contains(query, true) == true) ||
                (it.thread.latestVisibleMessage?.text?.contains(query, true) == true)
        }
    val pinned = visible.filter { it.thread.isPinned }.sortedBy { it.thread.pinOrderKey ?: it.thread.pinnedAt }
    val settled = visible.filter { !it.thread.isPinned && it.thread.isSettled }.sortedByDescending { it.thread.settledAt }
    val active = visible.filter { !it.thread.isPinned && !it.thread.isSettled }.sortedByDescending { it.sortTime() }

    Scaffold(
        modifier = modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            TopAppBar(
                scrollBehavior = scroll,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                navigationIcon = {
                    if (searching) IconButton(onClick = { searching = false; query = "" }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Close search")
                    }
                },
                title = {
                    if (searching) {
                        val focus = remember { FocusRequester() }
                        LaunchedEffect(Unit) { focus.requestFocus() }
                        TextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = { Text("Search threads") },
                            singleLine = true,
                            shape = CircleShape,
                            colors = TextFieldDefaults.colors(
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            ),
                            trailingIcon = {
                                if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "Clear search") }
                            },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            modifier = Modifier.fillMaxWidth().padding(end = 8.dp).focusRequester(focus),
                        )
                    } else {
                        ConnectionTitle(state.environments, onOpenEnvironments)
                    }
                },
                actions = {
                    if (!searching) {
                        IconButton(onClick = { searching = true }) { Icon(Icons.Rounded.Search, "Search threads") }
                        IconButton(onClick = onOpenSettings) { Icon(Icons.Rounded.Settings, "Open settings") }
                    }
                },
            )
        },
        floatingActionButton = {
            if (state.environments.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = onNewThread,
                    expanded = fabExpanded,
                    modifier = Modifier.semantics { contentDescription = "New thread" },
                    icon = { Icon(painterResource(R.drawable.ic_compose), null) },
                    text = { Text("New thread") },
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) { padding ->
        Surface(
            modifier = Modifier.padding(top = padding.calculateTopPadding()).fillMaxSize(),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            val empty = emptyStateFor(state, onAddEnvironment, onOpenEnvironments, onNewThread)
            if (empty != null) {
                empty()
                return@Surface
            }
            PullToRefreshBox(isRefreshing = refreshing, onRefresh = { refreshing = true; haptics.threshold(); onRefresh() }) {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(top = 8.dp, bottom = 112.dp + padding.calculateBottomPadding()),
                    // Keep rows readable on tablets/foldables.
                    modifier = Modifier.fillMaxSize().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = 840.dp),
                ) {
                    val projectOptions = state.projects.distinctBy { it.project.id }
                    if (projectOptions.size > 1) {
                        item(key = "filters") {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(bottom = 4.dp),
                            ) {
                                item {
                                    FilterChip(
                                        selected = projectFilter == null,
                                        onClick = { projectFilter = null; haptics.tick() },
                                        label = { Text("All projects") },
                                    )
                                }
                                items(projectOptions, key = { it.project.id }) { p ->
                                    FilterChip(
                                        selected = projectFilter == p.project.id,
                                        onClick = { projectFilter = if (projectFilter == p.project.id) null else p.project.id; haptics.tick() },
                                        label = { Text(p.project.title) },
                                        leadingIcon = if (projectFilter == p.project.id) {
                                            { Icon(Icons.Rounded.FolderOpen, null, Modifier.size(FilterChipDefaults.IconSize)) }
                                        } else null,
                                    )
                                }
                            }
                        }
                    }
                    if (visible.isEmpty()) {
                        item(key = "no-results") {
                            EmptyState(
                                icon = if (query.isNotBlank()) Icons.Rounded.Search else Icons.Rounded.Forum,
                                title = if (query.isNotBlank()) "No results" else "No threads yet",
                                body = if (query.isNotBlank()) "No threads matching \"$query\"." else "Start a new thread to put an agent to work in one of your projects.",
                                actionLabel = if (query.isBlank()) "New thread" else null,
                                onAction = onNewThread,
                                modifier = Modifier.height(420.dp),
                            )
                        }
                    }
                    threadGroup("Pinned", pinned, state.environments.size > 1, onOpenThread, onThreadAction, { renaming = it }, { deleting = it })
                    threadGroup(if (pinned.isEmpty()) null else "Active", active, state.environments.size > 1, onOpenThread, onThreadAction, { renaming = it }, { deleting = it })
                    if (state.showSettled && settled.isNotEmpty()) {
                        item(key = "settled-header") {
                            ShelfHeader("Settled", settled.size, settledExpanded) { settledExpanded = !settledExpanded; haptics.tick() }
                        }
                        if (settledExpanded) {
                            threadGroup(null, settled, state.environments.size > 1, onOpenThread, onThreadAction, { renaming = it }, { deleting = it }, settledGroup = true)
                        }
                    }
                }
            }
        }
    }

    renaming?.let { entry ->
        RenameDialog(entry.thread.title, onDismiss = { renaming = null }) { title ->
            onThreadAction(entry, ThreadAction.Rename(title)); renaming = null
        }
    }
    deleting?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            icon = { Icon(Icons.Rounded.Delete, null) },
            title = { Text("Delete thread?") },
            text = { Text("“${entry.thread.title}” will be permanently deleted, including its terminal history.") },
            confirmButton = {
                TextButton(onClick = { haptics.reject(); onThreadAction(entry, ThreadAction.Delete); deleting = null }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun emptyStateFor(
    state: HomeUiState,
    onAddEnvironment: () -> Unit,
    onOpenEnvironments: () -> Unit,
    onNewThread: () -> Unit,
): (@Composable () -> Unit)? {
    if (!state.environmentsLoaded) return { EmptyState(Icons.Rounded.Computer, "Loading environments", "Checking saved environments on this device.", loading = true) }
    if (state.environments.isEmpty()) return {
        EmptyState(
            Icons.Rounded.Computer,
            "No environments connected",
            "Pair this phone with a machine running T3 Code to load its projects and start coding sessions.",
            actionLabel = "Add environment",
            onAction = onAddEnvironment,
        )
    }
    val enabled = state.environments.filter { it.saved.enabled }
    if (enabled.isEmpty()) return {
        EmptyState(Icons.Rounded.CloudOff, "All environments are off", "Turn an environment back on to see its threads.", actionLabel = "Manage environments", onAction = onOpenEnvironments)
    }
    if (state.threads.isEmpty() && enabled.none { it.shellLoaded }) {
        val blocked = enabled.firstNotNullOfOrNull { (it.status as? ConnectionStatus.Blocked)?.error }
        val failing = enabled.firstNotNullOfOrNull { (it.status as? ConnectionStatus.Reconnecting)?.error }
        return when {
            blocked != null -> {
                { EmptyState(Icons.Rounded.CloudOff, "Environment unavailable", blocked, actionLabel = "Manage environments", onAction = onOpenEnvironments) }
            }
            failing != null -> {
                { EmptyState(Icons.Rounded.CloudOff, "Environment offline", "$failing\nRetrying automatically.", actionLabel = "Manage environments", onAction = onOpenEnvironments) }
            }
            else -> {
                { EmptyState(Icons.Rounded.Computer, "Connecting to environment", "Loading projects and threads from ${enabled.first().saved.label}.", loading = true) }
            }
        }
    }
    if (state.threads.isEmpty() && state.projects.isEmpty()) return {
        EmptyState(Icons.Rounded.FolderOpen, "No projects yet", "Add a project folder to start your first thread.", actionLabel = "New thread", onAction = onNewThread)
    }
    return null
}

@Composable
private fun ConnectionTitle(envs: List<EnvironmentSnapshot>, onClick: () -> Unit) {
    val enabled = envs.filter { it.saved.enabled }
    val reconnecting = enabled.filter { it.status is ConnectionStatus.Reconnecting || it.status is ConnectionStatus.Blocked }
    val connecting = enabled.filter { it.status is ConnectionStatus.Connecting }
    val label = when {
        reconnecting.size == 1 -> "Reconnecting to ${reconnecting.first().saved.label}"
        reconnecting.size > 1 -> "Reconnecting ${reconnecting.size} environments"
        connecting.isNotEmpty() && enabled.none { it.status is ConnectionStatus.Connected } -> "Connecting…"
        else -> null
    }
    Box(Modifier.clip(CircleShape).combinedClickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 6.dp)) {
        if (label == null) {
            BrandLockup()
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (reconnecting.isEmpty()) LoadingIndicator(Modifier.size(24.dp))
                else Icon(Icons.Rounded.CloudOff, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun ShelfHeader(label: String, count: Int, expanded: Boolean, onToggle: () -> Unit) {
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(onClick = onToggle)
            .padding(start = 28.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$label · $count", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
        Icon(Icons.Rounded.ExpandMore, if (expanded) "Collapse" else "Expand", Modifier.rotate(rotation), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun LazyListScope.threadGroup(
    title: String?,
    entries: List<ThreadEntry>,
    showEnvironment: Boolean,
    onOpen: (ThreadEntry) -> Unit,
    onAction: (ThreadEntry, ThreadAction) -> Unit,
    onRename: (ThreadEntry) -> Unit,
    onDelete: (ThreadEntry) -> Unit,
    settledGroup: Boolean = false,
) {
    if (entries.isEmpty()) return
    if (title != null) item(key = "h-$title") {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 28.dp, top = 16.dp, bottom = 8.dp),
        )
    }
    itemsIndexed(entries, key = { _, e -> e.key() }) { index, entry ->
        val shape = segmentShape(index, entries.size)
        Box(Modifier.animateItem().padding(horizontal = 12.dp, vertical = 1.5.dp)) {
            SwipeableThreadRow(entry, shape, showEnvironment, settledGroup, onOpen, onAction, onRename, onDelete)
        }
    }
}

/** Expressive "segmented" list: big outer corners, small inner corners. */
fun segmentShape(index: Int, count: Int): RoundedCornerShape {
    val big = 24.dp
    val small = 6.dp
    return RoundedCornerShape(
        topStart = if (index == 0) big else small,
        topEnd = if (index == 0) big else small,
        bottomStart = if (index == count - 1) big else small,
        bottomEnd = if (index == count - 1) big else small,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SwipeableThreadRow(
    entry: ThreadEntry,
    shape: RoundedCornerShape,
    showEnvironment: Boolean,
    settled: Boolean,
    onOpen: (ThreadEntry) -> Unit,
    onAction: (ThreadEntry, ThreadAction) -> Unit,
    onRename: (ThreadEntry) -> Unit,
    onDelete: (ThreadEntry) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val h = rememberHaptics()
    val swipe = rememberSwipeToDismissBoxState()
    // Tick as the swipe crosses the point where releasing will commit.
    LaunchedEffect(swipe.targetValue) { if (swipe.targetValue != SwipeToDismissBoxValue.Settled) h.threshold() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val primaryAction = if (settled) ThreadAction.Unsettle else ThreadAction.Settle
    SwipeToDismissBox(
        state = swipe,
        enableDismissFromStartToEnd = true,
        enableDismissFromEndToStart = true,
        onDismiss = { value ->
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            when (value) {
                SwipeToDismissBoxValue.EndToStart -> onAction(entry, primaryAction)
                SwipeToDismissBoxValue.StartToEnd -> onAction(entry, if (entry.thread.isPinned) ThreadAction.Unpin else ThreadAction.Pin)
                else -> Unit
            }
            scope.launch { swipe.reset() }
        },
        backgroundContent = {
            val direction = swipe.dismissDirection
            val bg by animateColorAsState(
                when (direction) {
                    SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.tertiaryContainer
                    SwipeToDismissBoxValue.StartToEnd -> MaterialTheme.colorScheme.primaryContainer
                    else -> Color.Transparent
                },
                label = "swipe-bg",
            )
            Row(
                Modifier.fillMaxSize().clip(shape).background(bg).padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (direction == SwipeToDismissBoxValue.StartToEnd) Arrangement.Start else Arrangement.End,
            ) {
                when (direction) {
                    SwipeToDismissBoxValue.EndToStart -> SwipeLabel(if (settled) Icons.Rounded.Unarchive else Icons.Rounded.CheckCircle, if (settled) "Un-settle" else "Settle")
                    SwipeToDismissBoxValue.StartToEnd -> SwipeLabel(Icons.Rounded.PushPin, if (entry.thread.isPinned) "Unpin" else "Pin")
                    else -> Unit
                }
            }
        },
    ) {
        ThreadRow(entry, shape, showEnvironment, settled, onOpen, onAction, onRename, onDelete)
    }
}

@Composable
private fun SwipeLabel(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ThreadRow(
    entry: ThreadEntry,
    shape: RoundedCornerShape,
    showEnvironment: Boolean,
    settled: Boolean,
    onOpen: (ThreadEntry) -> Unit,
    onAction: (ThreadEntry, ThreadAction) -> Unit,
    onRename: (ThreadEntry) -> Unit,
    onDelete: (ThreadEntry) -> Unit,
) {
    val thread = entry.thread
    val badge = thread.badge()
    var menu by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val h = rememberHaptics()
    Surface(
        shape = shape,
        color = if (settled) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box {
            Column(
                Modifier
                    .combinedClickable(
                        onClick = { onOpen(entry) },
                        onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); menu = true },
                    )
                    .padding(horizontal = 18.dp, vertical = if (settled) 12.dp else 14.dp)
                    .alpha(if (settled) 0.75f else 1f),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (thread.isPinned) {
                        Icon(Icons.Rounded.PushPin, "Pinned", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        entry.project?.title ?: "No project",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (badge != null && !settled) StatusPill(badge)
                    else Text(
                        relativeAge(thread.latestUserMessageAt ?: thread.updatedAt),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    thread.title.ifBlank { "Untitled thread" },
                    style = if (settled) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.titleMedium,
                    maxLines = if (settled) 1 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!settled) {
                    val error = thread.lastError?.takeIf { thread.status == "failed" }
                    val preview = thread.latestVisibleMessage?.text?.lineSequence()?.firstOrNull { it.isNotBlank() }
                    if (error != null || preview != null) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            error ?: preview!!,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    val envLabel = entry.environmentLabel.takeIf { showEnvironment }
                    if (thread.branch != null || envLabel != null) {
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (thread.branch != null) {
                                Icon(Icons.Rounded.CallSplit, null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    thread.branch,
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                            }
                            if (envLabel != null) {
                                if (thread.branch != null) Text("  ·  ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Icon(Icons.Rounded.Computer, null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(4.dp))
                                Text(envLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, shape = RoundedCornerShape(20.dp)) {
                fun act(a: ThreadAction) { menu = false; h.contextClick(); onAction(entry, a) }
                DropdownMenuItem(
                    text = { Text(if (thread.isPinned) "Unpin" else "Pin") },
                    leadingIcon = { Icon(Icons.Rounded.PushPin, null) },
                    onClick = { act(if (thread.isPinned) ThreadAction.Unpin else ThreadAction.Pin) },
                )
                DropdownMenuItem(
                    text = { Text(if (settled) "Un-settle" else "Settle") },
                    leadingIcon = { Icon(Icons.Rounded.CheckCircle, null) },
                    onClick = { act(if (settled) ThreadAction.Unsettle else ThreadAction.Settle) },
                )
                DropdownMenuItem(
                    text = { Text("Rename") },
                    leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                    onClick = { menu = false; onRename(entry) },
                )
                DropdownMenuItem(
                    text = { Text("Mark as unread") },
                    leadingIcon = { Icon(Icons.Rounded.MarkEmailUnread, null) },
                    onClick = { act(ThreadAction.MarkUnread) },
                )
                DropdownMenuItem(
                    text = { Text("Archive") },
                    leadingIcon = { Icon(Icons.Rounded.Archive, null) },
                    onClick = { act(ThreadAction.Archive) },
                )
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                    leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) },
                    onClick = { menu = false; onDelete(entry) },
                )
            }
        }
    }
}

@Composable
fun StatusPill(badge: ThreadBadge, modifier: Modifier = Modifier) {
    Surface(shape = CircleShape, color = badge.containerColor(), modifier = modifier) {
        Row(Modifier.padding(start = 6.dp, end = 10.dp, top = 3.dp, bottom = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            if (badge == ThreadBadge.Working) {
                LoadingIndicator(Modifier.size(18.dp), color = badge.color())
            } else {
                Icon(badge.icon, null, Modifier.size(14.dp).padding(start = 0.dp), tint = badge.color())
            }
            Spacer(Modifier.width(4.dp))
            Text(badge.label, style = MaterialTheme.typography.labelMedium, color = badge.color())
        }
    }
}

@Composable
fun RenameDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename thread") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                isError = text.isBlank(),
                supportingText = if (text.isBlank()) { { Text("Thread title cannot be empty.") } } else null,
                shape = RoundedCornerShape(16.dp),
            )
        },
        confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { onConfirm(text.trim()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

