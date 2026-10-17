package codes.t3.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import codes.t3.android.data.ColorTheme
import codes.t3.android.ui.app.AppViewModel
import codes.t3.android.ui.app.T3NavHost
import codes.t3.android.ui.theme.T3Theme

class MainActivity : ComponentActivity() {
    private var pendingLink by mutableStateOf<String?>(null)
    private var pendingThread by mutableStateOf<Pair<String, String>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        val app = application as T3App
        val splash = installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        pendingLink = pairingLink(intent)
        pendingThread = threadToOpen(intent)
        splash.setKeepOnScreenCondition { app.repository.savedEnvironments.value == null }
        setContent {
            val vm: AppViewModel = viewModel { AppViewModel(app.repository, app.settings) }
            val settings by vm.settings.collectAsStateWithLifecycle()
            val s = settings ?: return@setContent
            // Ask for the notification permission once, on Android 13+, while alerts are on.
            val permission = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {}
            androidx.compose.runtime.LaunchedEffect(s.notifications, s.notificationsAsked) {
                if (s.notifications && !s.notificationsAsked && android.os.Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    vm.updateSettings { it.copy(notificationsAsked = true) }
                    permission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            T3Theme(mode = s.themeMode, dynamicColor = s.colorTheme == ColorTheme.MaterialYou, pureBlack = s.pureBlack) {
                T3NavHost(vm, app.settings, pendingLink, onLinkConsumed = { pendingLink = null }, pendingThread = pendingThread, onThreadConsumed = { pendingThread = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pairingLink(intent)?.let { pendingLink = it }
        threadToOpen(intent)?.let { pendingThread = it }
    }

    /** A tapped alert notification names the thread to open. */
    private fun threadToOpen(intent: Intent?): Pair<String, String>? {
        if (intent?.action != ThreadNotifier.ACTION_OPEN_THREAD) return null
        val env = intent.getStringExtra(ThreadNotifier.EXTRA_ENVIRONMENT) ?: return null
        val thread = intent.getStringExtra(ThreadNotifier.EXTRA_THREAD) ?: return null
        return env to thread
    }

    private fun pairingLink(intent: Intent?): String? {
        val data = intent?.data ?: return null
        return when (data.scheme) {
            "t3code" -> data.toString()
            "http", "https" -> data.toString().takeIf { it.contains("token=") }
            else -> null
        }
    }
}
