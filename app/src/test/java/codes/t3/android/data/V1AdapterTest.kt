package codes.t3.android.data

import codes.t3.android.data.model.T3Json
import codes.t3.android.data.state.ShellState
import codes.t3.android.data.v1.V1ThreadState
import codes.t3.android.ui.thread.FeedEntry
import codes.t3.android.ui.thread.buildFeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Protocol v1 (t3 ≤ 0.0.45) translation, using a thread captured from a live 0.0.45 server. */
class V1AdapterTest {
    private fun j(s: String) = T3Json.parseToJsonElement(s)

    private fun liveSnapshot(): V1ThreadState {
        val raw = javaClass.classLoader!!.getResource("live-thread-v1.json")!!.readText()
        return V1ThreadState().apply(j("""{"kind":"snapshot","snapshot":$raw}"""))
    }

    @Test fun realThreadBecomesTimeline() {
        val state = liveSnapshot().toThreadState()
        assertEquals("Count entries", state.thread?.title)
        assertEquals(
            listOf(
                "user_message", "command_execution", "assistant_message", "checkpoint",
                "user_message", "dynamic_tool", "user_input_request", "file_change", "approval_request", "assistant_message", "checkpoint",
                "user_message", "command_execution", "checkpoint",
            ),
            state.timeline.map { it.type },
        )
        val ls = state.timeline[1]
        assertEquals("ls -1", ls.commandInput)
        assertEquals("README.md", ls.output)
        assertEquals("completed", ls.status) // a late "inProgress" update must not resurrect a finished tool
        assertEquals("/tmp/claude-0/demo-proj/a.txt", state.timeline.first { it.type == "file_change" }.fileName)
        assertTrue(state.pendingApprovals.isEmpty())
        assertNull(state.pendingUserInput)
        assertEquals(listOf(1, 2, 3), state.checkpoints.values.mapNotNull { it.appRunOrdinal }.sorted())
        // Last turn was interrupted and its sleep command failed.
        assertTrue(state.timeline.last { it.type == "command_execution" }.isFailed)
        val feed = buildFeed(state.timeline, state.runs, null)
        assertEquals(3, feed.count { it is FeedEntry.User })
        assertTrue(feed.any { it is FeedEntry.Fold && it.label.startsWith("You stopped") })
    }

    @Test fun assistantDeltasAccumulateAndPendingRequestsDerive() {
        val tid = "392b738d-7379-4741-89fa-7927f6ade0c3"
        var s = liveSnapshot()
        fun event(seq: Int, type: String, payload: String) =
            j("""{"kind":"event","event":{"sequence":$seq,"eventId":"e$seq","aggregateKind":"thread","aggregateId":"$tid","occurredAt":"2026-10-09T02:00:0${seq % 10}.000Z","commandId":null,"causationEventId":null,"correlationId":null,"metadata":{},"type":"$type","payload":$payload}}""")
        s = s.apply(event(101, "thread.message-sent", """{"threadId":"$tid","messageId":"u9","role":"user","text":"next","turnId":null,"streaming":false,"createdAt":"2026-10-09T02:00:00.000Z","updatedAt":"2026-10-09T02:00:00.000Z"}"""))
        s = s.apply(event(102, "thread.session-set", """{"threadId":"$tid","session":{"threadId":"$tid","status":"running","providerName":"claudeAgent","runtimeMode":"approval-required","activeTurnId":"t9","lastError":null,"updatedAt":"2026-10-09T02:00:01.000Z"}}"""))
        s = s.apply(event(103, "thread.message-sent", """{"threadId":"$tid","messageId":"a9","role":"assistant","text":"Hel","turnId":"t9","streaming":true,"createdAt":"2026-10-09T02:00:02.000Z","updatedAt":"2026-10-09T02:00:02.000Z"}"""))
        s = s.apply(event(104, "thread.message-sent", """{"threadId":"$tid","messageId":"a9","role":"assistant","text":"lo","turnId":"t9","streaming":true,"createdAt":"2026-10-09T02:00:02.000Z","updatedAt":"2026-10-09T02:00:03.000Z"}"""))
        var view = s.toThreadState()
        assertEquals("Hello", view.timeline.last().text)
        assertTrue(view.timeline.last().streaming)
        assertEquals("running", view.thread?.status)
        assertEquals("t9", view.activeRun?.id)

        s = s.apply(event(105, "thread.activity-appended", """{"threadId":"$tid","activity":{"id":"ap1","tone":"approval","kind":"approval.requested","summary":"x","payload":{"requestId":"r9","requestType":"command_execution_approval","detail":"Bash: rm -rf build"},"turnId":"t9","createdAt":"2026-10-09T02:00:04.000Z"}}"""))
        view = s.toThreadState()
        assertEquals("command", view.pendingApprovals.single().kind)
        assertEquals("Bash: rm -rf build", view.pendingApprovals.single().detail)

        s = s.apply(event(106, "thread.activity-appended", """{"threadId":"$tid","activity":{"id":"ap2","tone":"approval","kind":"approval.resolved","summary":"x","payload":{"requestId":"r9","decision":"accept"},"turnId":"t9","createdAt":"2026-10-09T02:00:05.000Z"}}"""))
        s = s.apply(event(107, "thread.message-sent", """{"threadId":"$tid","messageId":"a9","role":"assistant","text":"","turnId":"t9","streaming":false,"createdAt":"2026-10-09T02:00:02.000Z","updatedAt":"2026-10-09T02:00:06.000Z"}"""))
        s = s.apply(event(108, "thread.session-set", """{"threadId":"$tid","session":{"threadId":"$tid","status":"ready","providerName":"claudeAgent","runtimeMode":"approval-required","activeTurnId":null,"lastError":null,"updatedAt":"2026-10-09T02:00:07.000Z"}}"""))
        view = s.toThreadState()
        assertTrue(view.pendingApprovals.isEmpty())
        val answer = view.timeline.last { it.type == "assistant_message" }
        assertEquals("Hello", answer.text) // empty final keeps the accumulated deltas
        assertFalse(answer.streaming)
        assertEquals("completed", view.thread?.status)
        assertNull(view.activeRun)
        // Older/duplicate sequences are ignored.
        assertEquals(s, s.apply(event(103, "thread.message-sent", """{"threadId":"$tid","messageId":"a9","role":"assistant","text":"X","turnId":"t9","streaming":true}""")))
    }

    @Test fun shellV1Items() {
        var s = ShellState(protocol = 1)
        val row = """{"id":"t1","projectId":"p1","title":"Count entries","modelSelection":{"instanceId":"claudeAgent","model":"claude-sonnet-5-5"},"runtimeMode":"approval-required","interactionMode":"default","branch":null,"worktreePath":null,"latestTurn":{"turnId":"x","state":"running","requestedAt":"2026-10-09T01:09:06.436Z","startedAt":"2026-10-09T01:09:06.436Z","completedAt":null},"createdAt":"2026-10-09T01:05:20.791Z","updatedAt":"2026-10-09T01:09:09.490Z","archivedAt":null,"session":{"status":"running","activeTurnId":"x"},"hasPendingApprovals":true,"hasPendingUserInput":false,"hasActionableProposedPlan":false}"""
        s = s.apply(j("""{"kind":"snapshot","snapshot":{"snapshotSequence":5,"projects":[{"id":"p1","title":"demo","workspaceRoot":"/w","scripts":[],"createdAt":"x","updatedAt":"x"}],"threads":[$row],"updatedAt":"x"}}"""))
        val t = s.threads.getValue("t1")
        assertTrue(t.isWorking)
        assertEquals("command", t.pendingRuntimeRequest?.kind)
        s = s.apply(j("""{"kind":"thread-upserted","sequence":6,"thread":${row.replace("\"archivedAt\":null", "\"archivedAt\":\"2026-10-09T02:00:00.000Z\"")}}"""))
        assertTrue(s.threads.isEmpty())
        s = s.apply(j("""{"kind":"project-removed","sequence":7,"projectId":"p1"}"""))
        assertTrue(s.projects.isEmpty())
    }
}
