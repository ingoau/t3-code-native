package codes.t3.android.live

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import codes.t3.android.data.Attachments
import codes.t3.android.data.Commands
import codes.t3.android.data.ConnectionStatus
import codes.t3.android.data.EnvironmentRepository
import codes.t3.android.data.PlainTokenCipher
import codes.t3.android.data.PreparedImage
import codes.t3.android.data.T3Repository
import codes.t3.android.data.ThreadOp
import codes.t3.android.data.model.ModelSelection
import codes.t3.android.data.pairing.Pairing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

/**
 * End-to-end check against a real `t3` server (protocol v1 or v2). Skipped unless `T3_PAIRING_URL` is set, e.g.
 * `T3_PAIRING_URL=http://127.0.0.1:3773/pair#token=XXXX ./gradlew :app:testDebugUnitTest --tests '*LiveServerTest*'`.
 * Never starts a real agent turn: threads use a provider that can't run.
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
            val saved = repo.pair(Pairing.parse(pairingUrl)!!)
            println("Paired with ${saved.label} (${saved.environmentId}) at ${saved.httpBaseUrl}")

            val conn = withTimeout(15_000) { repo.connections.filter { it.isNotEmpty() }.first().values.first() }
            withTimeout(20_000) { conn.status.first { it is ConnectionStatus.Connected || it is ConnectionStatus.Blocked } }
            assertEquals(ConnectionStatus.Connected, conn.status.value)
            println("Protocol v${conn.protocol}")
            val config = conn.config.filterNotNull().first()
            println("Providers: " + config.providers.joinToString { "${it.instanceId}/${it.driver} status=${it.status} models=${it.models.size}" })
            val cmds = conn.commands

            val shell = withTimeout(10_000) { conn.shell.first { it.loaded } }
            println("Shell seq=${shell.sequence} projects=${shell.projects.values.map { it.title }} threads=${shell.threads.size}")
            val workspace = System.getProperty("t3.workspace").orEmpty().ifBlank { "/tmp/claude-0/demo-proj2" }
            val project = shell.projects.values.firstOrNull() ?: run {
                val projectId = UUID.randomUUID().toString()
                conn.run(cmds.createProject(projectId, "demo-proj", workspace))
                withTimeout(10_000) { conn.shell.first { projectId in it.projects } }.projects.getValue(projectId)
            }
            println("Using project ${project.title} at ${project.workspaceRoot}")

            // Deliberately pick a provider that can't run, so the test never spends real model usage.
            val selection = config.providers.firstOrNull { !it.selectable || it.status == "error" }?.let { p -> ModelSelection(p.instanceId, p.models.firstOrNull()?.slug ?: "default") }
                ?: ModelSelection("codex", "gpt-5.4")
            val threadId = UUID.randomUUID().toString()
            conn.run(cmds.createThread(threadId, project.id, "Native client smoke test", selection, "full-access", "default"))
            withTimeout(10_000) { conn.shell.first { threadId in it.threads } }
            println("Created thread $threadId")

            conn.run(cmds.rename(threadId, "Renamed from Android"))
            withTimeout(10_000) { conn.shell.first { it.threads[threadId]?.title == "Renamed from Android" } }
            conn.run(cmds.housekeeping(threadId, ThreadOp.Pin)!!)
            withTimeout(10_000) { conn.shell.first { it.threads[threadId]?.pinnedAt != null } }
            println("Renamed + pinned")

            val detail = withTimeout(10_000) { conn.observeThread(threadId).first { it.loaded } }
            println("Thread detail loaded: seq=${detail.sequence} items=${detail.items.size} title=${detail.thread?.title}")
            assertEquals("Renamed from Android", detail.thread?.title)

            val sendResult = runCatching {
                conn.run(cmds.send(threadId, "hello from the native client", null, Commands.DispatchMode.StartImmediately, JsonArray(emptyList()), null, "full-access", "default"))
            }
            println("Send message result: ${sendResult.getOrNull() ?: sendResult.exceptionOrNull()?.message}")
            val afterSend = withTimeout(10_000) { conn.observeThread(threadId).first { s -> s.items.values.any { it.type == "user_message" } } }
            println("Timeline after send: " + afterSend.timeline.joinToString { "${it.type}[${it.status}]" })
            assertTrue(afterSend.timeline.any { it.type == "user_message" && it.text == "hello from the native client" })

            // Image attachment round trip: upload → attach to a message → download through a signed URL.
            val bmp = android.graphics.Bitmap.createBitmap(64, 48, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.MAGENTA) }
            val png = java.io.ByteArrayOutputStream().also { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            val ref = Attachments.upload(conn, repo.http, PreparedImage("pixel.png", "image/png", png, bmp))
            println("Attach send: " + conn.run(cmds.send(threadId, "see attached", null, Commands.DispatchMode.StartImmediately, JsonArray(listOf(ref)), null, "full-access", "default")))
            // v2: waits in the queue behind the stuck first run (message + queued run). v1: becomes a timeline message.
            val withImage = withTimeout(15_000) {
                conn.observeThread(threadId).first { s ->
                    s.queued.any { (_, m) -> m?.attachments?.isNotEmpty() == true } || s.timeline.any { it.type == "user_message" && it.attachments.isNotEmpty() }
                }
            }
            val att = (withImage.queued.firstNotNullOfOrNull { (_, m) -> m?.attachments?.firstOrNull() }
                ?: withImage.timeline.first { it.type == "user_message" && it.attachments.isNotEmpty() }.attachments.first()) as JsonObject
            val downloaded = Attachments.download(conn, repo.http, att)
            println("Attachment round trip: uploaded ${png.size} bytes, downloaded ${downloaded?.width}x${downloaded?.height}")
            assertEquals(64, downloaded?.width)
            withImage.queued.firstOrNull()?.let { (run, _) ->
                conn.run(cmds.cancelQueued(threadId, run.id)!!)
                withTimeout(10_000) { conn.observeThread(threadId).first { it.queued.isEmpty() } }
                println("Cancelled queued run")
            }

            // Diff RPCs against any thread that has a captured checkpoint (e.g. from an E2E agent turn).
            shell.threads.values.firstOrNull { it.latestRunCompletedAt != null }?.let { t ->
                val st = withTimeout(10_000) { conn.observeThread(t.id).first { it.loaded } }
                println("Thread '${t.title}': " + st.timeline.joinToString { it.type } + " · checkpoints ${st.checkpoints.values.map { it.appRunOrdinal }}")
                st.latestTurnCount?.let { turn ->
                    val diff = conn.call("orchestration.getFullThreadDiff", buildJsonObject { put("threadId", t.id); put("toTurnCount", turn) })
                    println("Full diff ok: ${diff.toString().take(160)}")
                }
            }

            conn.run(cmds.housekeeping(threadId, ThreadOp.Delete)!!)
            withTimeout(10_000) { conn.shell.first { threadId !in it.threads } }
            println("Deleted thread")
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }
}
