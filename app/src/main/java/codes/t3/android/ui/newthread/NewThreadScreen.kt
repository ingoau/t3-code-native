package codes.t3.android.ui.newthread

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CallSplit
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import codes.t3.android.data.ProjectEntry
import codes.t3.android.data.model.ModelSelection
import codes.t3.android.data.model.RuntimeMode
import codes.t3.android.ui.thread.Composer
import codes.t3.android.ui.thread.ComposerModel
import codes.t3.android.ui.thread.ModelPickerSheet
import codes.t3.android.ui.thread.SendMode

data class BranchRef(val name: String, val current: Boolean, val isDefault: Boolean, val isRemote: Boolean)

enum class WorkspaceMode { Local, Worktree }

data class NewThreadUiState(
    val projects: List<ProjectEntry>,
    val selected: ProjectEntry?,
    val showEnvironment: Boolean,
    val composer: ComposerModel,
    val showPlanToggle: Boolean,
    val workspace: WorkspaceMode = WorkspaceMode.Local,
    val branch: String? = null,
    val branches: List<BranchRef>? = null,
    val isRepo: Boolean = true,
    val starting: Boolean = false,
    val error: String? = null,
    val attachments: List<codes.t3.android.ui.app.PendingAttachment> = emptyList(),
    val canAttach: Boolean = false,
)

class NewThreadCallbacks(
    val onClose: () -> Unit = {},
    val onSelectProject: (ProjectEntry) -> Unit = {},
    val onWorkspace: (WorkspaceMode) -> Unit = {},
    val onBranch: (String) -> Unit = {},
    val onLoadBranches: () -> Unit = {},
    val onAddProject: (path: String, title: String) -> Unit = { _, _ -> },
    val onModelChange: (ModelSelection, RuntimeMode) -> Unit = { _, _ -> },
    val onRuntimeMode: (RuntimeMode) -> Unit = {},
    val onTogglePlan: () -> Unit = {},
    val onStart: (String) -> Unit = {},
    val onPickImages: (List<android.net.Uri>) -> Unit = {},
    val onRemoveAttachment: (String) -> Unit = {},
)

@Composable
fun NewThreadScreen(state: NewThreadUiState, callbacks: NewThreadCallbacks) {
    var text by rememberSaveable { mutableStateOf("") }
    var projectSheet by remember { mutableStateOf(false) }
    var branchSheet by remember { mutableStateOf(false) }
    var pickerOpen by remember { mutableStateOf(false) }
    var addProject by remember { mutableStateOf(false) }
    LaunchedEffect(state.selected?.project?.id) { if (state.selected != null) callbacks.onLoadBranches() }
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia(6),
    ) { uris -> if (uris.isNotEmpty()) callbacks.onPickImages(uris) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New thread") },
                navigationIcon = { IconButton(onClick = callbacks.onClose) { Icon(Icons.Rounded.Close, "Close") } },
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
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Box(Modifier.size(64.dp).clip(MaterialShapes.Sunny.toShape()), contentAlignment = Alignment.Center) {
                        Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxSize()) {}
                        Icon(Icons.Rounded.FolderOpen, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    Text("What should the agent work on?", style = MaterialTheme.typography.headlineSmall)

                    ProjectCard(state, onClick = { projectSheet = true })

                    if (state.selected != null && state.isRepo) {
                        Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween), modifier = Modifier.fillMaxWidth()) {
                            ToggleButton(
                                checked = state.workspace == WorkspaceMode.Local,
                                onCheckedChange = { callbacks.onWorkspace(WorkspaceMode.Local) },
                                shapes = ButtonGroupDefaults.connectedLeadingButtonShapes(),
                                modifier = Modifier.weight(1f),
                            ) { Text("Current checkout") }
                            ToggleButton(
                                checked = state.workspace == WorkspaceMode.Worktree,
                                onCheckedChange = { callbacks.onWorkspace(WorkspaceMode.Worktree) },
                                shapes = ButtonGroupDefaults.connectedTrailingButtonShapes(),
                                modifier = Modifier.weight(1f),
                            ) { Text("New worktree") }
                        }
                        Surface(onClick = { branchSheet = true; callbacks.onLoadBranches() }, shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                            Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.CallSplit, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                val label = when {
                                    state.branches == null && state.branch == null -> "Loading branches…"
                                    state.workspace == WorkspaceMode.Worktree -> "From ${state.branch ?: "default branch"}"
                                    else -> state.branch ?: "Choose branch"
                                }
                                Text(label, style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Icon(Icons.Rounded.UnfoldMore, null, Modifier.size(16.dp))
                            }
                        }
                    }
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                }
                Box(Modifier.navigationBarsPadding().padding(start = 10.dp, end = 10.dp, bottom = 8.dp)) {
                    Composer(
                        text = text,
                        onTextChange = { text = it },
                        model = state.composer.copy(
                            enabled = state.selected != null && !state.starting,
                            placeholder = if (state.selected == null) "Choose a project first" else "Ask anything…",
                        ),
                        onSend = { _: SendMode -> callbacks.onStart(text.trim()) },
                        onStop = {},
                        onOpenModelPicker = { pickerOpen = true },
                        onTogglePlan = if (state.showPlanToggle) callbacks.onTogglePlan else null,
                        onRuntimeMode = callbacks.onRuntimeMode,
                        enterToSend = false,
                        sendLabel = "Start task",
                        attachments = state.attachments,
                        onAddAttachment = if (state.canAttach) ({
                            picker.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }) else null,
                        onRemoveAttachment = callbacks.onRemoveAttachment,
                    )
                    if (state.starting) LoadingIndicator(Modifier.align(Alignment.TopCenter).padding(top = 4.dp))
                }
            }
        }
    }

    if (projectSheet) {
        ProjectSheet(
            state,
            onPick = { projectSheet = false; callbacks.onSelectProject(it) },
            onAdd = { projectSheet = false; addProject = true },
            onDismiss = { projectSheet = false },
        )
    }
    if (branchSheet) {
        BranchSheet(state, onPick = { branchSheet = false; callbacks.onBranch(it) }, onDismiss = { branchSheet = false })
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
    if (addProject) {
        var path by remember { mutableStateOf("") }
        var title by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addProject = false },
            icon = { Icon(Icons.Rounded.Folder, null) },
            title = { Text("Add project") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Add an existing folder on ${state.selected?.environmentLabel ?: state.projects.firstOrNull()?.environmentLabel ?: "the host"}. It's created if missing.", style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(path, { path = it }, label = { Text("Folder path") }, placeholder = { Text("~/projects/my-app") }, singleLine = true, shape = RoundedCornerShape(16.dp))
                    OutlinedTextField(title, { title = it }, label = { Text("Name (optional)") }, singleLine = true, shape = RoundedCornerShape(16.dp))
                }
            },
            confirmButton = { TextButton(enabled = path.isNotBlank(), onClick = { addProject = false; callbacks.onAddProject(path.trim(), title.trim()) }) { Text("Add project") } },
            dismissButton = { TextButton(onClick = { addProject = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ProjectCard(state: NewThreadUiState, onClick: () -> Unit) {
    val selected = state.selected
    Surface(onClick = onClick, shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Folder, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(selected?.project?.title ?: "Choose a project", style = MaterialTheme.typography.titleMedium)
                val sub = listOfNotNull(selected?.project?.workspaceRoot, selected?.environmentLabel?.takeIf { state.showEnvironment }).joinToString("  ·  ")
                if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Rounded.UnfoldMore, null)
        }
    }
}

@Composable
private fun ProjectSheet(state: NewThreadUiState, onPick: (ProjectEntry) -> Unit, onAdd: () -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Choose project", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onAdd) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(4.dp)); Text("Add") }
        }
        SearchField(query, { query = it }, "Search projects")
        val filtered = state.projects.filter { query.isBlank() || it.project.title.contains(query, true) || it.project.workspaceRoot.contains(query, true) }
        LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
            if (filtered.isEmpty()) item {
                Text("No projects found. Add a folder on the host to get started.", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(filtered, key = { "${it.environmentId}/${it.project.id}" }) { p ->
                val chosen = state.selected?.project?.id == p.project.id && state.selected.environmentId == p.environmentId
                ListItem(
                    modifier = Modifier.padding(horizontal = 12.dp).clip(RoundedCornerShape(20.dp)).clickable { onPick(p) },
                    headlineContent = { Text(p.project.title) },
                    supportingContent = {
                        Text(
                            listOfNotNull(p.project.workspaceRoot, p.environmentLabel.takeIf { state.showEnvironment }).joinToString("  ·  "),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    leadingContent = { Icon(Icons.Rounded.Folder, null) },
                    trailingContent = { if (chosen) Icon(Icons.Rounded.Check, "Selected", tint = MaterialTheme.colorScheme.primary) },
                    colors = ListItemDefaults.colors(containerColor = if (chosen) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent),
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
                )
            }
        }
    }
}

@Composable
private fun BranchSheet(state: NewThreadUiState, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Text(
            if (state.workspace == WorkspaceMode.Worktree) "Base branch" else "Branch",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        SearchField(query, { query = it }, "Find a branch")
        val branches = state.branches
        if (branches == null) {
            Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) { LoadingIndicator() }
            return@ModalBottomSheet
        }
        val filtered = branches.filter { query.isBlank() || it.name.contains(query, true) }
        LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
            if (filtered.isEmpty()) item { Text("No matching branches", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(filtered, key = { it.name }) { b ->
                Surface(
                    onClick = { onPick(b.name) },
                    shape = RoundedCornerShape(20.dp),
                    color = if (b.name == state.branch) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                ) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.CallSplit, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(12.dp))
                        Text(b.name, style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (b.isDefault) Tag("default")
                        if (b.current) Tag("current")
                        if (b.isRemote) Tag("remote")
                    }
                }
            }
        }
    }
}

@Composable
private fun Tag(text: String) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.padding(start = 6.dp)) {
        Text(text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit, placeholder: String) {
    TextField(
        value = value,
        onValueChange = onChange,
        placeholder = { Text(placeholder) },
        leadingIcon = { Icon(Icons.Rounded.Search, null) },
        singleLine = true,
        shape = CircleShape,
        colors = TextFieldDefaults.colors(
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
    )
}
