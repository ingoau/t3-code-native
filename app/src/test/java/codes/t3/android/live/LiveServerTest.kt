package codes.t3.android.live

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import codes.t3.android.data.Commands
import codes.t3.android.data.ConnectionStatus
import codes.t3.android.data.EnvironmentRepository
import codes.t3.android.data.PlainTokenCipher
import codes.t3.android.data.T3Repository
import codes.t3.android.data.model.ModelSelection
import codes.t3.android.data.pairing.Pairing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

/**
 * End-to-end check against a real `t3` server. Skipped unless `T3_PAIRING_URL` is set, e.g.
 * `T3_PAIRING_URL=http://127.0.0.1:3773/pair#token=XXXX ./gradlew :app:testDebugUnitTest --tests '*LiveServerTest*'`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LiveServerTest {
    @Test
    fun pairConnectAndManageThread() = runBlocking {
        val pairingUrl = System.getProperty("t3.pairingUrl").orEmpty()
        assumeTrue("T3_PAIRING_URL not set", pairingUrl.isNotBlank())
        val dir = File(System.getProperty("java.io.tmpdir"), "t3-live-${UUID.randomUUID()}").apply { mkdirs() }
        val store = PreferenceDataStoreFactory.create { File(dir, "envs.preferences_pb") }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val repo = T3Repository(EnvironmentRepository(store, PlainTokenCipher), scope)
        try {
            val target = Pairing.parse(pairingUrl)!!
            val saved = repo.pair(target)
            println("Paired with ${saved.label} (${saved.environmentId}) at ${saved.httpBaseUrl}")

            val conn = withTimeout(15_000) { repo.connections.filter { it.isNotEmpty() }.first().values.first() }
            withTimeout(20_000) { conn.status.first { it is ConnectionStatus.Connected || it is ConnectionStatus.Blocked } }
            assertEquals(ConnectionStatus.Connected, conn.status.value)
            val config = conn.config.filterNotNull().first()
            println("Providers: " + config.providers.joinToString { "${it.instanceId}/${it.driver} status=${it.status} models=${it.models.size}" })

            val shell = withTimeout(10_000) { conn.shell.first { it.loaded } }
            println("Shell seq=${shell.sequence} projects=${shell.projects.values.map { it.title }} threads=${shell.threads.size}")
            val workspace = System.getProperty("t3.workspace").orEmpty().ifBlank { "/tmp/claude-0/demo-proj2" }
            val project = shell.projects.values.firstOrNull() ?: run {
                val projectId = UUID.randomUUID().toString()
                conn.call("projects.mutate", buildJsonObject {
                    put("type", "project.create")
                    put("commandId", Commands.newId())
                    put("projectId", projectId)
                    put("title", "demo-proj")
                    put("workspaceRoot", workspace)
                })
                withTimeout(10_000) { conn.shell.first { projectId in it.projects } }.projects.getValue(projectId)
            }
            println("Using project ${project.title} at ${project.workspaceRoot}")

            // Create a thread, then exercise housekeeping commands and watch the shell follow along.
            val threadId = UUID.randomUUID().toString()
            // Deliberately pick a provider that can't run, so the test never spends real model usage.
            val selection = config.providers.firstOrNull { !it.selectable || it.status == "error" }?.let { p -> ModelSelection(p.instanceId, p.models.firstOrNull()?.slug ?: "default") }
                ?: ModelSelection("codex", "gpt-5.4")
            conn.dispatch(buildJsonObject {
                put("type", "thread.create")
                put("commandId", Commands.newId())
                put("createdBy", "user")
                put("creationSource", "mobile")
                put("threadId", threadId)
                put("projectId", project.id)
                put("title", "Native client smoke test")
                put("modelSelection", Commands.modelSelectionJson(selection))
                put("runtimeMode", "full-access")
                put("interactionMode", "default")
                put("branch", null as String?)
                put("worktreePath", null as String?)
            })
            withTimeout(10_000) { conn.shell.first { threadId in it.threads } }
            println("Created thread $threadId")

            conn.dispatch(Commands.rename(threadId, "Renamed from Android"))
            withTimeout(10_000) { conn.shell.first { it.threads[threadId]?.title == "Renamed from Android" } }
            conn.dispatch(Commands.pin(threadId))
            withTimeout(10_000) { conn.shell.first { it.threads[threadId]?.pinnedAt != null } }
            println("Renamed + pinned")

            val detail = withTimeout(10_000) { conn.observeThread(threadId).first { it.loaded } }
            println("Thread detail loaded: seq=${detail.sequence} items=${detail.items.size} title=${detail.thread?.title}")
            assertEquals("Renamed from Android", detail.thread?.title)

            val sendResult = runCatching {
                conn.dispatch(Commands.sendMessage(threadId, "hello from the native client", null, Commands.DispatchMode.StartImmediately, true))
            }
            println("Send message result: ${sendResult.getOrNull() ?: sendResult.exceptionOrNull()?.message}")
            val afterSend = withTimeout(10_000) { conn.observeThread(threadId).first { s -> s.items.values.any { it.type == "user_message" } } }
            println("Timeline after send: " + afterSend.timeline.joinToString { "${it.type}[${it.status}]" })
            assertTrue(afterSend.timeline.any { it.type == "user_message" && it.text == "hello from the native client" })

            // Image attachment round trip: upload → attach to a message → download through a signed URL.
            val bmp = android.graphics.Bitmap.createBitmap(64, 48, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.MAGENTA) }
            val png = java.io.ByteArrayOutputStream().also { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            val ref = codes.t3.android.data.Attachments.upload(conn, repo.http, codes.t3.android.data.PreparedImage("pixel.png", "image/png", png, bmp))
            val sendRes = conn.dispatch(Commands.sendMessage(threadId, "see attached", null, Commands.DispatchMode.StartImmediately, true, attachments = kotlinx.serialization.json.JsonArray(listOf(ref))))
            println("Attach send: $sendRes")
            // The first run can't start (broken provider), so this one waits in the queue as a message + queued run.
            val withImage = withTimeout(10_000) { conn.observeThread(threadId).first { s -> s.queued.any { (_, m) -> m?.attachments?.isNotEmpty() == true } } }
            println("Queued: " + withImage.queued.map { (r, m) -> "${r.status}:${m?.text}" })
            val att = withImage.queued.firstNotNullOf { (_, m) -> m?.attachments?.firstOrNull() } as kotlinx.serialization.json.JsonObject
            val downloaded = codes.t3.android.data.Attachments.download(conn, repo.http, att)
            println("Attachment round trip: uploaded ${png.size} bytes, downloaded ${downloaded?.width}x${downloaded?.height}")
            assertEquals(64, downloaded?.width)
            conn.dispatch(Commands.cancelQueued(threadId, withImage.queued.first().first.id))
            withTimeout(10_000) { conn.observeThread(threadId).first { it.queued.isEmpty() } }
            println("Cancelled queued run")

            conn.dispatch(Commands.simple("thread.delete", threadId))
            withTimeout(10_000) { conn.shell.first { threadId !in it.threads } }
            println("Deleted thread")
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }
}
