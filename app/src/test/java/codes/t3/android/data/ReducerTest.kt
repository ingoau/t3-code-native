package codes.t3.android.data

import codes.t3.android.data.model.T3Json
import codes.t3.android.data.state.ShellState
import codes.t3.android.data.state.ThreadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReducerTest {
    private fun j(s: String) = T3Json.parseToJsonElement(s)

    private val threadJson = """{"id":"t1","projectId":"p1","title":"Thread","providerInstanceId":"codex","modelSelection":{"instanceId":"codex","model":"gpt-5.4"},"runtimeMode":"full-access","interactionMode":"default","branch":null,"worktreePath":null,"activeProviderThreadId":null,"lineage":{"rootThreadId":"t1","parentThreadId":null,"relationshipToParent":null},"forkedFrom":null,"createdBy":"user","creationSource":"web","latestRunId":null,"activeRunId":null,"status":"idle","pendingRuntimeRequest":null,"latestVisibleMessage":null,"latestUserMessageAt":null,"hasActionableProposedPlan":false,"itemCount":0,"visibleItemCount":0,"createdAt":"2026-06-20T00:00:00.000Z","updatedAt":"2026-06-20T00:00:00.000Z","archivedAt":null,"settledOverride":null,"settledAt":null,"lastVisitedAt":null,"deletedAt":null,"someFutureField":{"x":1}}"""

    @Test fun shellSnapshotThenDeltas() {
        var s = ShellState()
        s = s.apply(j("""{"kind":"snapshot","snapshot":{"schemaVersion":1,"snapshotSequence":10,"projects":[{"id":"p1","title":"Project","workspaceRoot":"/w","scripts":[],"createdAt":"x","updatedAt":"x"}],"threads":[$threadJson],"archivedThreads":[]}}"""))
        assertEquals(10, s.sequence)
        assertEquals("Thread", s.threads["t1"]!!.title)

        // stale delta ignored
        s = s.apply(j("""{"kind":"thread.removed","sequence":9,"location":"active","threadId":"t1"}"""))
        assertNotNull(s.threads["t1"])

        s = s.apply(j("""{"kind":"thread.updated","sequence":11,"location":"active","thread":${threadJson.replace("\"status\":\"idle\"", "\"status\":\"running\"").replace("\"activeRunId\":null", "\"activeRunId\":\"r1\"")}}"""))
        assertTrue(s.threads["t1"]!!.isWorking)
        assertEquals(11, s.sequence)

        s = s.apply(j("""{"kind":"thread.updated","sequence":12,"location":"archive","thread":$threadJson}"""))
        assertNull(s.threads["t1"])

        s = s.apply(j("""{"kind":"synchronized"}"""))
        assertTrue(s.synchronized)
        // Unknown kinds still advance the cursor so resubscribes don't replay them.
        s = s.apply(j("""{"kind":"some.future.kind","sequence":13}"""))
        assertEquals(13, s.sequence)
    }

    private fun item(id: String, type: String, ordinal: Int, extra: String = "", runId: String = "r1") =
        """{"id":"$id","threadId":"t1","runId":"$runId","nodeId":"n-$id","providerThreadId":null,"providerTurnId":null,"nativeItemRef":null,"parentItemId":null,"ordinal":$ordinal,"status":"completed","title":null,"startedAt":null,"completedAt":null,"updatedAt":"2026-10-09T12:00:00.000Z","type":"$type"$extra}"""

    @Test fun threadSnapshotEventsAndStreaming() {
        var s = ThreadState()
        s = s.apply(j("""{"kind":"snapshot","snapshotSequence":5,"projection":{"thread":$threadJson,"runs":[{"id":"r1","threadId":"t1","ordinal":1,"status":"running","userMessageId":"m1"}],"turnItems":[${item("u1", "user_message", 1, ""","text":"hi","inputIntent":"turn_start","attachments":[]""")}],"visibleTurnItems":[],"runtimeRequests":[]}}"""))
        assertEquals(1, s.timeline.size)
        assertEquals("r1", s.activeRun?.id)

        // Assistant streams full text each update
        s = s.apply(j("""{"kind":"event","sequence":6,"event":{"id":"e1","threadId":"t1","occurredAt":"x","type":"turn-item.updated","payload":${item("a1", "assistant_message", 2, ""","messageId":"m2","text":"Sure","streaming":true""")}}}"""))
        s = s.apply(j("""{"kind":"event","sequence":7,"event":{"id":"e2","threadId":"t1","occurredAt":"x","type":"turn-item.updated","payload":${item("a1", "assistant_message", 2, ""","messageId":"m2","text":"Sure — done.","streaming":false""")}}}"""))
        assertEquals(listOf("user_message", "assistant_message"), s.timeline.map { it.type })
        assertEquals("Sure — done.", s.timeline.last().text)
        assertFalse(s.timeline.last().streaming)

        // Duplicate/old sequence ignored
        s = s.apply(j("""{"kind":"event","sequence":7,"event":{"id":"e3","threadId":"t1","occurredAt":"x","type":"turn-item.updated","payload":${item("a1", "assistant_message", 2, ""","text":"OLD","streaming":false""")}}}"""))
        assertEquals("Sure — done.", s.timeline.last().text)

        // Unknown event types advance the cursor without breaking
        s = s.apply(j("""{"kind":"event","sequence":8,"event":{"id":"e4","threadId":"t1","occurredAt":"x","type":"brand.new-event","payload":{}}}"""))
        assertEquals(8, s.sequence)

        // Pending approval derived from request + approval_request item
        s = s.apply(j("""{"kind":"event","sequence":9,"event":{"id":"e5","threadId":"t1","occurredAt":"x","type":"turn-item.updated","payload":${item("ap", "approval_request", 3, ""","requestId":"q1","requestKind":"command","prompt":"rm -rf build","options":[{"decision":"accept","label":"Allow"},{"decision":"decline","label":"Deny"}]""")}}}"""))
        s = s.apply(j("""{"kind":"event","sequence":10,"event":{"id":"e6","threadId":"t1","occurredAt":"x","type":"runtime-request.updated","payload":{"id":"q1","nodeId":"n","providerTurnId":null,"nativeRequestRef":null,"kind":"command","status":"pending","responseCapability":{"type":"live","providerSessionId":"s"},"createdAt":"x","resolvedAt":null}}}"""))
        val approval = s.pendingApprovals.single()
        assertEquals("rm -rf build", approval.detail)
        assertEquals(listOf("accept", "decline"), approval.options.map { it.decision })

        // Rolled back runs hide their items
        s = s.apply(j("""{"kind":"event","sequence":11,"event":{"id":"e7","threadId":"t1","occurredAt":"x","type":"run.updated","payload":{"id":"r1","threadId":"t1","ordinal":1,"status":"rolled_back"}}}"""))
        assertTrue(s.timeline.isEmpty())
    }

    @Test fun userInputQuestionDerivation() {
        var s = ThreadState()
        s = s.apply(j("""{"kind":"snapshot","snapshotSequence":1,"projection":{"thread":$threadJson,"turnItems":[${item("qi", "user_input_request", 1, ""","requestId":"q2","questions":[{"id":"layout","header":"Layout","question":"Which?","options":[{"label":"A"},{"label":"B","value":"b"}],"multiSelect":false}],"responseMode":"message"""")}],"runtimeRequests":[{"id":"q2","kind":"user_input","status":"pending","responseCapability":{"type":"message"}}]}}"""))
        val q = s.pendingUserInput!!
        assertEquals("Which?", q.questions.single().question)
        assertTrue(q.dismissible)
        assertEquals(2, q.questions.single().options.size)
    }
}
