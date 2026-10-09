package codes.t3.android.ui

import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.AnnotatedString
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import codes.t3.android.data.pairing.PairingTarget
import codes.t3.android.ui.app.SharedAxis
import codes.t3.android.ui.environments.AddEnvironmentScreen
import codes.t3.android.ui.theme.T3Theme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import androidx.compose.ui.test.onRoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = RobolectricDeviceQualifiers.Pixel7)
class NavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    /** Predictive back scrubs the pop transition, which is the forward shared-axis motion played in reverse. */
    @Test fun predictiveBackScrubsTheTransitionBackwards() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            T3Theme(dynamicColor = false) {
                val nav = rememberNavController()
                NavHost(
                    nav, startDestination = "list",
                    enterTransition = { SharedAxis.enter }, exitTransition = { SharedAxis.exit },
                    popEnterTransition = { SharedAxis.popEnter }, popExitTransition = { SharedAxis.popExit },
                    predictivePopEnterTransition = { SharedAxis.popEnter }, predictivePopExitTransition = { SharedAxis.popExit },
                ) {
                    composable("list") {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer), contentAlignment = Alignment.Center) {
                            Button(onClick = { nav.navigate("detail") }) { Text("Open detail") }
                        }
                    }
                    composable("detail") {
                        Box(Modifier.fillMaxSize().background(Color(0xFF346BF1)), contentAlignment = Alignment.Center) {
                            Text("Detail", color = Color.White, style = MaterialTheme.typography.displayMedium)
                        }
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("Open detail").performClick()
        compose.mainClock.advanceTimeBy(130)
        compose.onRoot().captureRoboImage("../docs/screenshots/transitions/forward_30pct.png")
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("Detail").assertExists()

        // Simulate a system back swipe from the left edge, 40% of the way across.
        val input = androidx.navigationevent.DirectNavigationEventInput()
        compose.mainClock.autoAdvance = true
        compose.runOnUiThread {
            compose.activity.navigationEventDispatcher.addInput(input)
            input.backStarted(androidx.navigationevent.NavigationEvent(androidx.navigationevent.NavigationEvent.EDGE_LEFT, 0f, 0f, 1000f))
        }
        compose.waitForIdle()
        compose.runOnUiThread {
            input.backProgressed(androidx.navigationevent.NavigationEvent(androidx.navigationevent.NavigationEvent.EDGE_LEFT, 0.4f, 400f, 1000f))
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("../docs/screenshots/transitions/predictive_back_40pct.png")
        // Both pages are on screen mid-gesture: the gesture is driving the pop transition.
        compose.onNodeWithText("Detail").assertExists()
        compose.onNodeWithText("Open detail").assertExists()
        compose.runOnUiThread { input.backCompleted() }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("Open detail").assertExists()
    }

    @Test fun pastingAPairingLinkConnects() {
        var connected: PairingTarget? = null
        compose.setContent {
            T3Theme(dynamicColor = false) {
                LocalClipboardManager.current.setText(AnnotatedString("http://192.168.1.20:3773/pair#token=7QK3M9X2HJ4P"))
                AddEnvironmentScreen(initialLink = null, connecting = false, error = null, onBack = {}, onConnect = { connected = it })
            }
        }
        compose.onNodeWithText("Paste pairing link").performClick()
        compose.waitForIdle()
        assertEquals("http://192.168.1.20:3773/", connected?.httpBaseUrl)
        assertEquals("7QK3M9X2HJ4P", connected?.token)
        assertTrue(true)
    }
}
