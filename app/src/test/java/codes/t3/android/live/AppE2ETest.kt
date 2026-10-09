package codes.t3.android.live

import android.provider.Settings
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import codes.t3.android.MainActivity
import codes.t3.android.T3App
import codes.t3.android.data.PlainTokenCipher
import codes.t3.android.data.TokenCipher
import codes.t3.android.data.pairing.Pairing
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Application used under Robolectric: the Android Keystore isn't available on the JVM. */
class TestT3App : T3App() {
    override fun createCipher(): TokenCipher = PlainTokenCipher
}

/**
 * Drives the real app UI against a live `t3` server. Skipped unless `T3_PAIRING_URL` is set.
 * Set `T3_E2E_TURN=1` to also run a real (tiny) agent turn, which uses the host's provider account.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = RobolectricDeviceQualifiers.Pixel7, application = TestT3App::class)
class AppE2ETest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val dir = "../docs/screenshots/live"

    @Before fun noAnimations() {
        val resolver = ApplicationProvider.getApplicationContext<T3App>().contentResolver
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
    }

    private fun waitForText(text: String, timeout: Long = 20_000, substring: Boolean = false) =
        compose.waitUntilAtLeastOneExists(hasText(text, substring = substring), timeout)

    @Test fun pairBrowseAndStartThread() {
        val link = System.getProperty("t3.pairingUrl").orEmpty()
        assumeTrue("T3_PAIRING_URL not set", link.isNotBlank())
        val target = Pairing.parse(link)!!

        waitForText("No environments connected")
        captureScreenRoboImage("$dir/01_first_launch.png")

        compose.onNodeWithText("Add environment").performClick()
        waitForText("Tap to scan")
        compose.onNode(hasSetTextAction() and hasText("Address")).performTextInput(target.httpBaseUrl.substringAfter("://").trimEnd('/'))
        compose.onNode(hasSetTextAction() and hasText("Pairing code")).performTextInput(target.token!!)
        captureScreenRoboImage("$dir/02_add_environment_filled.png")
        compose.onNodeWithText("Connect").performClick()

        // Back on Home once paired and the shell has loaded.
        compose.waitUntilAtLeastOneExists(hasContentDescription("Open settings"), 30_000)
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText("Connecting to environment").fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithText("Loading environments").fetchSemanticsNodes().isEmpty()
        }
        compose.mainClock.advanceTimeBy(1_000)
        captureScreenRoboImage("$dir/03_home_connected.png")

        compose.onNodeWithContentDescription("Open settings").performClick()
        waitForText("Environments")
        compose.onAllNodesWithText("Environments").onFirst().performClick()
        waitForText("Connected")
        captureScreenRoboImage("$dir/04_environments_connected.png")
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

        compose.waitUntilAtLeastOneExists(hasText("New thread"), 10_000)
        compose.onAllNodesWithText("New thread").onFirst().performClick()
        waitForText("What should the agent work on?")
        // Branches load from the real repository via vcs.listRefs.
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Loading branches…").fetchSemanticsNodes().isEmpty() }
        captureScreenRoboImage("$dir/05_new_thread_live.png")

        if (System.getenv("T3_E2E_TURN") != "1") return
        compose.onNode(hasSetTextAction()).performTextInput("Reply with exactly one word: pong")
        compose.onNodeWithContentDescription("Start task").performClick()
        waitForText("pong", timeout = 120_000)
        compose.mainClock.advanceTimeBy(1_000)
        captureScreenRoboImage("$dir/06_thread_live_reply.png")
    }
}
