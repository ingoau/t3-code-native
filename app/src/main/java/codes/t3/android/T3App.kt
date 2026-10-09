package codes.t3.android

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import codes.t3.android.data.AppSettingsRepository
import codes.t3.android.data.EnvironmentRepository
import codes.t3.android.data.KeystoreTokenCipher
import codes.t3.android.data.T3Repository
import codes.t3.android.data.TokenCipher
import codes.t3.android.data.environmentStore
import codes.t3.android.data.settingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

open class T3App : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var repository: T3Repository
        private set
    lateinit var settings: AppSettingsRepository
        private set

    /** Overridden in JVM tests, where the Android Keystore isn't available. */
    protected open fun createCipher(): TokenCipher = KeystoreTokenCipher()

    override fun onCreate() {
        super.onCreate()
        settings = AppSettingsRepository(settingsStore)
        repository = T3Repository(EnvironmentRepository(environmentStore, createCipher()), appScope)
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = repository.probeAll()
        })
    }
}
