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

    override fun onCreate(savedInstanceState: Bundle?) {
        val app = application as T3App
        val splash = installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        pendingLink = pairingLink(intent)
        splash.setKeepOnScreenCondition { app.repository.savedEnvironments.value == null }
        setContent {
            val vm: AppViewModel = viewModel { AppViewModel(app.repository, app.settings) }
            val settings by vm.settings.collectAsStateWithLifecycle()
            val s = settings ?: return@setContent
            T3Theme(mode = s.themeMode, dynamicColor = s.colorTheme == ColorTheme.MaterialYou, pureBlack = s.pureBlack) {
                T3NavHost(vm, app.settings, pendingLink) { pendingLink = null }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pairingLink(intent)?.let { pendingLink = it }
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
