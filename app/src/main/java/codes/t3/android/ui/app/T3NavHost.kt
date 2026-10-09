package codes.t3.android.ui.app

import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.background
import androidx.compose.ui.unit.IntOffset
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import codes.t3.android.data.AppSettingsRepository
import codes.t3.android.data.Commands
import codes.t3.android.data.ThreadEntry
import codes.t3.android.data.model.T3Json
import codes.t3.android.data.model.ThreadShell
import codes.t3.android.ui.archive.ArchiveScreen
import codes.t3.android.ui.environments.AddEnvironmentScreen
import codes.t3.android.ui.environments.EnvironmentsScreen
import codes.t3.android.ui.home.HomeScreen
import codes.t3.android.ui.home.ThreadAction
import codes.t3.android.ui.newthread.NewThreadCallbacks
import codes.t3.android.ui.newthread.NewThreadScreen
import codes.t3.android.ui.settings.SettingsScreen
import codes.t3.android.ui.thread.ThreadCallbacks
import codes.t3.android.ui.thread.ThreadScreen
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

@Serializable data object HomeRoute
@Serializable data class ThreadRoute(val environmentId: String, val threadId: String)
@Serializable data class NewThreadRoute(val environmentId: String? = null, val projectId: String? = null)
@Serializable data object SettingsRoute
@Serializable data object EnvironmentsRoute
@Serializable data class AddEnvironmentRoute(val link: String? = null)
@Serializable data object ArchiveRoute
@Serializable data class DiffRoute(val environmentId: String, val threadId: String, val fromTurn: Int, val toTurn: Int, val title: String, val subtitle: String)

@Composable
fun T3NavHost(app: AppViewModel, settingsRepo: AppSettingsRepository, pendingLink: String?, onLinkConsumed: () -> Unit) {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { UiEvents.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(pendingLink) {
        if (pendingLink != null) {
            nav.navigate(AddEnvironmentRoute(pendingLink))
            onLinkConsumed()
        }
    }

    // Opaque backdrop so cross-fading pages never reveal the window background.
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer)) {
        NavHost(
            navController = nav,
            startDestination = HomeRoute,
            enterTransition = { SharedAxis.enter },
            exitTransition = { SharedAxis.exit },
            // Back (and predictive back, which scrubs these with the gesture) is the forward motion in reverse.
            popEnterTransition = { SharedAxis.popEnter },
            popExitTransition = { SharedAxis.popExit },
            // NavHost uses separate transitions while a predictive back gesture is in progress; use the same reverse
            // shared-axis motion so the gesture scrubs exactly what tapping back would play.
            predictivePopEnterTransition = { SharedAxis.popEnter },
            predictivePopExitTransition = { SharedAxis.popExit },
        ) {
            composable<HomeRoute> {
                val state by app.home.collectAsStateWithLifecycle()
                HomeScreen(
                    state = state,
                    onOpenThread = { nav.navigate(ThreadRoute(it.environmentId, it.thread.id)) },
                    onNewThread = { nav.navigate(NewThreadRoute()) },
                    onOpenSettings = { nav.navigate(SettingsRoute) },
                    onOpenEnvironments = { nav.navigate(EnvironmentsRoute) },
                    onAddEnvironment = { nav.navigate(AddEnvironmentRoute()) },
                    onThreadAction = app::threadAction,
                    onRefresh = app::reconnectAll,
                )
            }
            composable<ThreadRoute> { entry ->
                val route = entry.toRoute<ThreadRoute>()
                val vm: ThreadViewModel = viewModel(key = "${route.environmentId}/${route.threadId}") {
                    ThreadViewModel(app.repository, settingsRepo, route.environmentId, route.threadId, app.settings.value)
                }
                val state by vm.state.collectAsStateWithLifecycle()
                val context = androidx.compose.ui.platform.LocalContext.current
                LaunchedEffect(Unit) { vm.markVisited() }
                DisposableEffect(Unit) { onDispose { vm.markVisited() } }
                state?.let { s ->
                    ThreadScreen(
                        state = s,
                        draftKey = route.threadId,
                        callbacks = remember(vm) {
                            ThreadCallbacks(
                                onBack = { nav.popBackStack() },
                                onSend = vm::send,
                                onStop = vm::stop,
                                onApproval = vm::respondApproval,
                                onAnswer = vm::answer,
                                onDismissQuestion = vm::dismissQuestion,
                                onModelChange = vm::changeModel,
                                onRuntimeMode = vm::setRuntime,
                                onTogglePlan = vm::togglePlan,
                                onThreadAction = { action ->
                                    app.runThreadAction(route.environmentId, route.threadId, action)
                                    if (action == ThreadAction.Delete || action == ThreadAction.Archive) nav.popBackStack()
                                },
                                onImplementPlan = vm::implementPlan,
                                loadFullItem = vm::loadFullItem,
                                onReconnect = { vm.reconnect() },
                                onPickImages = { uris -> vm.addImages(context.contentResolver, uris) },
                                onRemoveAttachment = vm.tray::remove,
                                loadImage = vm::loadImage,
                                onCancelQueued = vm::cancelQueued,
                                onSteerQueued = vm::steerQueued,
                                onResumeQueue = vm::resumeQueue,
                                onDraftRestored = vm::draftRestored,
                                onViewDiff = { from, to, title ->
                                    nav.navigate(DiffRoute(route.environmentId, route.threadId, from, to, title, vm.state.value?.title.orEmpty()))
                                },
                            )
                        },
                    )
                }
            }
            composable<NewThreadRoute> { entry ->
                val route = entry.toRoute<NewThreadRoute>()
                val vm: NewThreadViewModel = viewModel { NewThreadViewModel(app.repository, settingsRepo, route.projectId, route.environmentId, app.settings.value) }
                val state by vm.state.collectAsStateWithLifecycle()
                val context = androidx.compose.ui.platform.LocalContext.current
                state?.let { s ->
                    NewThreadScreen(
                        s,
                        remember(vm) {
                            NewThreadCallbacks(
                                onClose = { nav.popBackStack() },
                                onSelectProject = vm::selectProject,
                                onWorkspace = vm::setWorkspace,
                                onBranch = vm::setBranch,
                                onLoadBranches = vm::loadBranches,
                                onAddProject = vm::addProject,
                                onModelChange = vm::setModel,
                                onRuntimeMode = vm::setRuntime,
                                onTogglePlan = vm::togglePlan,
                                onPickImages = { uris -> vm.addImages(context.contentResolver, uris) },
                                onRemoveAttachment = vm.tray::remove,
                                onStart = { text ->
                                    vm.start(text) { envId, threadId ->
                                        nav.navigate(ThreadRoute(envId, threadId)) { popUpTo<NewThreadRoute> { inclusive = true } }
                                    }
                                },
                            )
                        },
                    )
                }
            }
            composable<SettingsRoute> {
                val settings by app.settings.collectAsStateWithLifecycle()
                val envs by app.environments.collectAsStateWithLifecycle()
                settings?.let {
                    SettingsScreen(
                        settings = it,
                        environmentCount = envs.size,
                        onUpdate = { t -> app.updateSettings(t) },
                        onOpenEnvironments = { nav.navigate(EnvironmentsRoute) },
                        onOpenArchive = { nav.navigate(ArchiveRoute) },
                        onBack = { nav.popBackStack() },
                    )
                }
            }
            composable<EnvironmentsRoute> {
                val envs by app.environments.collectAsStateWithLifecycle()
                EnvironmentsScreen(
                    environments = envs,
                    onBack = { nav.popBackStack() },
                    onAdd = { nav.navigate(AddEnvironmentRoute()) },
                    onToggle = { id, on -> app.setEnabled(id, on) },
                    onRename = { id, label -> app.renameEnvironment(id, label) },
                    onReconnect = { app.reconnect(it) },
                    onRemove = { app.removeEnvironment(it) },
                )
            }
            composable<AddEnvironmentRoute> { entry ->
                val route = entry.toRoute<AddEnvironmentRoute>()
                val pairing by app.pairing.collectAsStateWithLifecycle()
                DisposableEffect(Unit) { onDispose { app.clearPairingError() } }
                val haptics = codes.t3.android.ui.components.rememberHaptics()
                AddEnvironmentScreen(
                    initialLink = route.link,
                    connecting = pairing.first,
                    error = pairing.second,
                    onBack = { nav.popBackStack() },
                    onConnect = { target -> app.pair(target) { haptics.confirm(); nav.popBackStack(HomeRoute, inclusive = false) } },
                )
            }
            composable<DiffRoute> { entry ->
                val route = entry.toRoute<DiffRoute>()
                var diff by remember { mutableStateOf<String?>(null) }
                var error by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(route) {
                    val conn = app.repository.connection(route.environmentId)
                    if (conn == null) { error = "Environment is not connected"; return@LaunchedEffect }
                    runCatching {
                        val payload = kotlinx.serialization.json.buildJsonObject {
                            put("threadId", kotlinx.serialization.json.JsonPrimitive(route.threadId))
                            if (route.fromTurn > 0) put("fromTurnCount", kotlinx.serialization.json.JsonPrimitive(route.fromTurn))
                            put("toTurnCount", kotlinx.serialization.json.JsonPrimitive(route.toTurn))
                        }
                        val method = if (route.fromTurn > 0) "orchestration.getTurnDiff" else "orchestration.getFullThreadDiff"
                        val result = conn.call(method, payload) as JsonObject
                        (result["diff"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
                    }.onSuccess { diff = it }.onFailure { error = errorText(it) }
                }
                codes.t3.android.ui.diff.DiffScreen(route.title, route.subtitle, diff, error, onBack = { nav.popBackStack() })
            }
            composable<ArchiveRoute> {
                ArchiveRoute(app, nav)
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().imePadding().padding(bottom = 96.dp))
    }
}

@Composable
private fun ArchiveRoute(app: AppViewModel, nav: NavHostController) {
    var threads by remember { mutableStateOf<List<ThreadEntry>?>(null) }
    var reload by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(reload) {
        val all = mutableListOf<ThreadEntry>()
        app.repository.connections.value.values.forEach { conn ->
            runCatching {
                val snap = conn.call("orchestration.getArchivedShellSnapshot") as JsonObject
                val rows = snap["threads"] as JsonArray
                val list = if (conn.protocol == 1) rows.mapNotNull { it as? JsonObject }.map { codes.t3.android.data.v1.V1.threadShell(it) }
                else T3Json.decodeFromJsonElement<List<ThreadShell>>(rows)
                val projects = conn.shell.value.projects
                list.filter { it.deletedAt == null }.forEach { all += ThreadEntry(conn.environmentId, conn.environment.label, it, projects[it.projectId]) }
            }
        }
        threads = all.sortedByDescending { it.thread.archivedAt }
    }
    ArchiveScreen(
        threads = threads,
        onBack = { nav.popBackStack() },
        onUnarchive = { t ->
            scope.launch {
                runCatching { app.repository.connection(t.environmentId)?.let { c -> c.commands.housekeeping(t.thread.id, codes.t3.android.data.ThreadOp.Unarchive)?.let { c.run(it) } } }
                reload++
            }
        },
        onDelete = { t ->
            scope.launch {
                runCatching { app.repository.connection(t.environmentId)?.let { c -> c.commands.housekeeping(t.thread.id, codes.t3.android.data.ThreadOp.Delete)?.let { c.run(it) } } }
                reload++
            }
        },
    )
}

/**
 * Material shared-axis X, as used by the Android Settings app: the incoming page slides 10% in from the trailing
 * edge while fading in; the outgoing page slides 10% toward the leading edge while fading out. Popping is the exact
 * reverse, so a predictive back gesture literally plays the forward transition backwards.
 */
internal object SharedAxis {
    private const val DURATION = 450
    private val emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    private fun slide() = tween<IntOffset>(DURATION, easing = emphasized)
    private const val SHIFT = 0.1f

    val enter: EnterTransition = slideInHorizontally(slide()) { (it * SHIFT).toInt() } +
        fadeIn(tween(150, delayMillis = 50, easing = LinearEasing))
    val exit: ExitTransition = slideOutHorizontally(slide()) { -(it * SHIFT).toInt() } +
        fadeOut(tween(150, delayMillis = 35, easing = LinearEasing))
    val popEnter: EnterTransition = slideInHorizontally(slide()) { -(it * SHIFT).toInt() } +
        fadeIn(tween(150, delayMillis = 50, easing = LinearEasing))
    val popExit: ExitTransition = slideOutHorizontally(slide()) { (it * SHIFT).toInt() } +
        fadeOut(tween(150, delayMillis = 35, easing = LinearEasing))
}
