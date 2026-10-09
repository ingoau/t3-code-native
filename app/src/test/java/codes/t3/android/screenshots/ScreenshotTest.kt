package codes.t3.android.screenshots

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import codes.t3.android.data.AppSettings
import codes.t3.android.data.ConnectionStatus
import codes.t3.android.data.model.RuntimeMode
import codes.t3.android.data.state.ThreadState
import codes.t3.android.ui.environments.AddEnvironmentScreen
import codes.t3.android.ui.environments.EnvironmentsScreen
import codes.t3.android.ui.home.HomeScreen
import codes.t3.android.ui.home.HomeUiState
import codes.t3.android.ui.newthread.BranchRef
import codes.t3.android.ui.newthread.NewThreadCallbacks
import codes.t3.android.ui.newthread.NewThreadScreen
import codes.t3.android.ui.newthread.NewThreadUiState
import codes.t3.android.ui.settings.SettingsScreen
import codes.t3.android.ui.theme.T3Theme
import codes.t3.android.ui.theme.ThemeMode
import codes.t3.android.ui.thread.ComposerModel
import codes.t3.android.ui.thread.ThreadCallbacks
import codes.t3.android.ui.thread.ThreadScreen
import codes.t3.android.ui.thread.ThreadUiState
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = RobolectricDeviceQualifiers.Pixel7)
class ScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val dir = "../docs/screenshots"

    private fun render(dark: Boolean, content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent { T3Theme(mode = if (dark) ThemeMode.Dark else ThemeMode.Light, dynamicColor = false) { content() } }
        compose.mainClock.advanceTimeBy(1_500)
    }

    private fun capture(name: String) {
        compose.mainClock.advanceTimeBy(800)
        compose.onRoot().captureRoboImage("$dir/$name.png")
    }

    private fun captureScreen(name: String) {
        compose.mainClock.advanceTimeBy(1_200)
        captureScreenRoboImage("$dir/$name.png")
    }

    private fun home() = HomeUiState(true, Fixtures.environments, Fixtures.threads, Fixtures.projectEntries, showSettled = true)

    @Composable
    private fun Home(state: HomeUiState = home()) = HomeScreen(state, {}, {}, {}, {}, {}, { _, _ -> }, {})

    private fun threadState(detail: ThreadState, title: String, subtitle: String) = ThreadUiState(
        title = title,
        subtitle = subtitle,
        shell = detail.thread,
        detail = detail,
        connection = ConnectionStatus.Connected,
        environmentLabel = Fixtures.saved.label,
        composer = ComposerModel(
            providers = Fixtures.providers,
            selection = Fixtures.selection,
            runtimeMode = RuntimeMode.of(detail.thread?.runtimeMode),
            planMode = detail.thread?.interactionMode == "plan",
            running = detail.activeRun != null,
            canStop = detail.activeRun != null,
        ),
        showPlanToggle = detail.thread?.interactionMode == "plan",
    )

    @Test fun home_light() { render(false) { Home() }; capture("01_home_light") }
    @Test fun home_dark() { render(true) { Home() }; capture("02_home_dark") }

    @Test fun home_empty() {
        render(false) { Home(HomeUiState(environmentsLoaded = true)) }
        capture("03_home_no_environments")
    }

    @Test fun thread_working_light() {
        render(false) { ThreadScreen(threadState(Fixtures.conversationState(), "Fix flaky websocket reconnect test", "t3code · Julius's MacBook Pro"), ThreadCallbacks(), "t1") }
        capture("04_thread_working_light")
    }

    @Test fun thread_working_dark() {
        render(true) { ThreadScreen(threadState(Fixtures.conversationState(), "Fix flaky websocket reconnect test", "t3code · Julius's MacBook Pro"), ThreadCallbacks(), "t1") }
        capture("05_thread_working_dark")
    }

    @Test fun thread_finished_expanded() {
        render(true) { ThreadScreen(threadState(Fixtures.conversationState(live = false), "Fix flaky websocket reconnect test", "t3code · Julius's MacBook Pro"), ThreadCallbacks(), "t1") }
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(3) // feed is reversed: [user, answer, checkpoint, fold, …]
        compose.mainClock.advanceTimeBy(500)
        compose.mainClock.autoAdvance = true
        compose.onNodeWithText("Worked for", substring = true).performClick()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithContentDescription("Collapse").assertExists()
        capture("06_thread_worked_for_expanded")
    }

    @Test fun thread_approval() {
        render(false) { ThreadScreen(threadState(Fixtures.approvalState(), "Add rate limiting to the public webhooks endpoint", "acme-api · Julius's MacBook Pro"), ThreadCallbacks(), "t2") }
        capture("07_thread_approval")
    }

    @Test fun thread_question() {
        render(true) { ThreadScreen(threadState(Fixtures.questionState(), "Redesign the pricing page hero", "marketing-site · Julius's MacBook Pro"), ThreadCallbacks(), "t3") }
        compose.onNodeWithText("Centered").performClick()
        capture("08_thread_question")
    }

    @Test fun thread_plan() {
        render(false) { ThreadScreen(threadState(Fixtures.planState(), "Plan the billing service split", "acme-api · Julius's MacBook Pro"), ThreadCallbacks(), "t5") }
        capture("09_thread_plan")
    }

    @Test fun model_picker() {
        render(false) { ThreadScreen(threadState(Fixtures.conversationState(live = false), "Fix flaky websocket reconnect test", "t3code · Julius's MacBook Pro"), ThreadCallbacks(), "t1") }
        compose.mainClock.autoAdvance = true
        compose.onNodeWithText("Opus 5.5", substring = true).performClick()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithText("Model & settings").assertExists()
        captureScreen("10_model_picker")
    }

    @Test fun new_thread() {
        val state = NewThreadUiState(
            projects = Fixtures.projectEntries,
            selected = Fixtures.projectEntries.first(),
            showEnvironment = false,
            composer = ComposerModel(Fixtures.providers, Fixtures.selection, RuntimeMode.AutoEdits),
            showPlanToggle = false,
            branch = "main",
            branches = listOf(BranchRef("main", true, true, false), BranchRef("t3/8f2a91c4", false, false, false)),
        )
        render(true) { NewThreadScreen(state, NewThreadCallbacks()) }
        capture("11_new_thread")
    }

    @Test fun add_environment() {
        render(false) { AddEnvironmentScreen(initialLink = null, connecting = false, error = null, onBack = {}, onConnect = {}) }
        capture("12_add_environment")
    }

    @Test fun environments() {
        render(true) { EnvironmentsScreen(Fixtures.environments, {}, {}, { _, _ -> }, { _, _ -> }, {}, {}) }
        capture("13_environments")
    }

    @Test fun settings() {
        render(false) { SettingsScreen(AppSettings(), 2, {}, {}, {}, {}) }
        capture("14_settings")
    }

    @Test fun home_long_press_menu() {
        render(false) { Home() }
        compose.onNodeWithContentDescription("Search threads").assertExists()
        captureScreen("15_home_overview")
    }
}
