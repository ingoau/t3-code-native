package codes.t3.android.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.click
import codes.t3.android.screenshots.Fixtures
import codes.t3.android.ui.home.HomeScreen
import codes.t3.android.ui.home.HomeUiState
import codes.t3.android.ui.home.ThreadAction
import codes.t3.android.ui.theme.T3Theme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Regression: swiping used to fire its action repeatedly (pin ⇄ unpin forever) as the row recomposed. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = RobolectricDeviceQualifiers.Pixel7)
class SwipeActionTest {
    @get:Rule val compose = createComposeRule()

    private val title = "Add rate limiting to the public webhooks endpoint"

    @Test fun swipeRightPinsExactlyOnce() {
        val actions = mutableListOf<ThreadAction>()
        var threads by mutableStateOf(Fixtures.threads)
        compose.setContent {
            T3Theme(dynamicColor = false) {
                HomeScreen(
                    HomeUiState(true, Fixtures.environments, threads, Fixtures.projectEntries),
                    {}, {}, {}, {}, {},
                    onThreadAction = { entry, action ->
                        actions += action
                        // Simulate the server echo: the row moves to the Pinned section.
                        threads = threads.map {
                            if (it.thread.id != entry.thread.id) it
                            else it.copy(thread = it.thread.copy(pinnedAt = if (action == ThreadAction.Pin) Fixtures.ago(0) else null))
                        }
                    },
                    onRefresh = {},
                )
            }
        }
        compose.onNodeWithText(title).performTouchInput { swipeRight(startX = centerX - width * 0.4f, endX = centerX + width * 0.6f) }
        compose.mainClock.advanceTimeBy(3_000)
        compose.waitForIdle()
        assertEquals(listOf(ThreadAction.Pin), actions)
    }

    @Test fun swipeLeftOnSettledUnsettlesExactlyOnce() {
        val actions = mutableListOf<ThreadAction>()
        var threads by mutableStateOf(Fixtures.threads.filter { it.thread.isSettled || it.thread.id == "t2" })
        val settledTitle = "Write release notes for 0.0.46"
        compose.setContent {
            T3Theme(dynamicColor = false) {
                HomeScreen(
                    HomeUiState(true, Fixtures.environments, threads, Fixtures.projectEntries),
                    {}, {}, {}, {}, {},
                    onThreadAction = { entry, action ->
                        actions += action
                        threads = threads.map {
                            if (it.thread.id != entry.thread.id) it
                            else it.copy(thread = it.thread.copy(settledAt = if (action == ThreadAction.Settle) Fixtures.ago(0) else null))
                        }
                    },
                    onRefresh = {},
                )
            }
        }
        // Settled shelf is collapsed by default: expand it first.
        compose.onNodeWithText("Settled", substring = true).performTouchInput { click() }
        compose.waitForIdle()
        compose.onNodeWithText(settledTitle).performTouchInput { swipeLeft(startX = centerX + width * 0.4f, endX = centerX - width * 0.6f) }
        compose.mainClock.advanceTimeBy(3_000)
        compose.waitForIdle()
        assertEquals(listOf(ThreadAction.Unsettle), actions)
    }
}
