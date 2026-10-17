package codes.t3.android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import codes.t3.android.data.AppSettingsRepository
import codes.t3.android.data.ThreadAlert
import codes.t3.android.data.ThreadAlertTracker
import codes.t3.android.data.T3Repository
import codes.t3.android.ui.app.UiEvents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The thread on screen right now (environment id to thread id), so its own alerts aren't shown. */
object OpenThread {
    val current = MutableStateFlow<Pair<String, String>?>(null)
}

/**
 * Notifies when a thread needs approval or input, fails, hits a usage limit or finishes, using the same rules as the
 * desktop app. In the foreground it's a snackbar (skipped for the thread on screen); in the background it's a system
 * notification that opens the thread. The upstream mobile app gets pushes through the T3 Connect relay, which this
 * client doesn't use, so alerts arrive while the app's connections are alive.
 */
class ThreadNotifier(
    private val context: Context,
    private val repository: T3Repository,
    private val settings: AppSettingsRepository,
    private val scope: CoroutineScope,
) {
    private val tracker = ThreadAlertTracker()

    fun start() {
        createChannel()
        scope.launch {
            repository.connections.collectLatest { conns ->
                synchronized(tracker) { conns.keys.forEach(tracker::reset) }
                kotlinx.coroutines.coroutineScope {
                    conns.values.forEach { conn ->
                        launch {
                            conn.shell.collect { shell ->
                                if (!shell.loaded) return@collect
                                val alerts = synchronized(tracker) { tracker.update(conn.environmentId, shell.threads.values) }
                                alerts.forEach { deliver(it) }
                            }
                        }
                    }
                }
            }
        }
        // Opening a thread clears its notification.
        scope.launch {
            OpenThread.current.collect { open -> open?.let { (env, thread) -> NotificationManagerCompat.from(context).cancel(tag(env, thread), ID) } }
        }
    }

    private suspend fun deliver(alert: ThreadAlert) {
        if (!settings.settings.first().notifications) return
        val foreground = withContext(Dispatchers.Main) { ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
        if (foreground) {
            if (OpenThread.current.value != alert.environmentId to alert.threadId) UiEvents.show("${alert.kind.title} · ${alert.threadTitle.ifBlank { "Untitled thread" }}")
            return
        }
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        val intent = Intent(context, MainActivity::class.java)
            .setAction(ACTION_OPEN_THREAD)
            .putExtra(EXTRA_ENVIRONMENT, alert.environmentId)
            .putExtra(EXTRA_THREAD, alert.threadId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(
            context, tag(alert.environmentId, alert.threadId).hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(alert.kind.title)
            .setContentText(alert.threadTitle.ifBlank { "Untitled thread" })
            .setCategory(if (alert.kind == codes.t3.android.data.AlertKind.Completed) NotificationCompat.CATEGORY_STATUS else NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        // One notification per thread: a newer state replaces the older one.
        runCatching { manager.notify(tag(alert.environmentId, alert.threadId), ID, notification) }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL, "Agent alerts", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "When a thread needs approval or input, fails, or finishes"
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_OPEN_THREAD = "codes.t3.android.OPEN_THREAD"
        const val EXTRA_ENVIRONMENT = "environmentId"
        const val EXTRA_THREAD = "threadId"
        private const val CHANNEL = "agent-alerts"
        private const val ID = 1
        private fun tag(env: String, thread: String) = "$env:$thread"
    }
}
