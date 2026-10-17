package codes.t3.android.data

import codes.t3.android.data.model.ForkedFrom
import codes.t3.android.data.model.ProjectShell
import codes.t3.android.data.model.Subagent
import codes.t3.android.data.model.T3Json
import codes.t3.android.data.model.ThreadLineage
import codes.t3.android.data.model.ThreadShell
import codes.t3.android.data.state.LineageKind
import codes.t3.android.data.state.ThreadState
import codes.t3.android.data.state.deriveLineage
import codes.t3.android.data.state.runningAgentsByParent
import codes.t3.android.data.state.subagentTitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LineageTest {
    private fun thread(id: String, parent: String? = null, relationship: String? = null, status: String = "idle", created: String = "2026-10-01T00:00:00Z", source: String? = null) =
        ThreadShell(
            id = id, projectId = "p", title = "Thread $id", status = status, createdAt = created, creationSource = source,
            activeRunId = if (status == "running") "r-$id" else null,
            activityRunStatus = if (status == "running") "running" else null,
            lineage = ThreadLineage(parentThreadId = parent, relationshipToParent = relationship, rootThreadId = parent ?: id),
        )

    @Test fun groupsParentAgentsAndForks() {
        val threads = listOf(
            thread("root"),
            thread("main", parent = "root", relationship = "fork"),
            thread("a1", parent = "main", relationship = "subagent", status = "running", created = "2026-10-02T00:00:00Z", source = "provider"),
            thread("a2", parent = "main", relationship = "subagent", status = "completed", created = "2026-10-03T00:00:00Z", source = "provider"),
            thread("f1", parent = "main", relationship = "fork"),
            thread("other", parent = "root", relationship = "fork"),
        ).associateBy { it.id }
        val lineage = deriveLineage("main", threads, emptyList())

        assertEquals(LineageKind.Parent, lineage.parent?.kind)
        assertEquals("root", lineage.parent?.threadId)
        assertEquals(listOf("a1"), lineage.agents.map { it.threadId })
        assertEquals(listOf("a2"), lineage.previousAgents.map { it.threadId })
        assertEquals(listOf("f1"), lineage.forks.map { it.threadId })
        assertEquals(1, lineage.runningCount)
    }

    @Test fun projectedSubagentsIncludeAgentsWithoutThreads() {
        val threads = listOf(thread("main"), thread("child", parent = "main", relationship = "subagent", status = "running")).associateBy { it.id }
        val agents = listOf(
            Subagent("n1", threadId = "main", childThreadId = "child", prompt = "Find the reconnect logic", title = "Explore: reconnect", status = "running", startedAt = "2026-10-02T00:00:00Z"),
            Subagent("n2", threadId = "main", childThreadId = null, prompt = "Summarise the test failures\nmore", status = "completed", startedAt = "2026-10-01T00:00:00Z", completedAt = "2026-10-01T00:01:00Z"),
        )
        val lineage = deriveLineage("main", threads, agents)

        // The child thread isn't listed twice (once from the projection, once from the shell).
        assertEquals(1, lineage.agents.size)
        assertEquals("Explore: reconnect", lineage.agents.single().title)
        assertTrue(lineage.agents.single().openable)
        val inline = lineage.previousAgents.single()
        assertNull(inline.threadId)
        assertFalse(inline.openable)
        assertEquals("Summarise the test failures", inline.title)
        assertNull(lineage.parent)
    }

    @Test fun subagentThreadPointsAtParentAgent() {
        val threads = listOf(thread("main"), thread("child", parent = "main", relationship = "subagent", source = "provider")).associateBy { it.id }
        val lineage = deriveLineage("child", threads, emptyList())
        assertEquals(LineageKind.ParentAgent, lineage.parent?.kind)
        assertTrue(threads.getValue("child").isProviderSubagent)
    }

    @Test fun runForkParentComesFromForkedFrom() {
        val fork = thread("fork", relationship = "fork").copy(forkedFrom = ForkedFrom("run", threadId = "src", runId = "r1"))
        assertEquals("src", fork.parentThreadId)
    }

    @Test fun runningAgentsCountedPerParent() {
        val counts = runningAgentsByParent(
            listOf(
                thread("a", parent = "p1", relationship = "subagent", status = "running"),
                thread("b", parent = "p1", relationship = "subagent", status = "running"),
                thread("c", parent = "p1", relationship = "subagent", status = "completed"),
                thread("d", parent = "p2", relationship = "fork", status = "running"),
            ),
        )
        assertEquals(mapOf("p1" to 2), counts)
    }

    @Test fun subagentEventsUpdateThreadState() {
        var s = ThreadState()
        s = s.applyEvent(T3Json.parseToJsonElement("""{"type":"subagent.updated","payload":{"id":"n1","threadId":"t1","origin":"provider_native","childThreadId":null,"prompt":"go","title":null,"status":"running","result":null,"startedAt":null,"completedAt":null,"updatedAt":"2026-10-01T00:00:00Z","someFutureField":1}}""") as kotlinx.serialization.json.JsonObject)
        assertEquals("running", s.subagents["n1"]?.status)
    }

    @Test fun titlesDropSubagentPrefix() {
        assertEquals("find it", subagentTitle("Subagent: find it", ""))
        assertEquals("Agent", subagentTitle(null, ""))
    }

    @Test fun scratchProjectMatchesServerRoot() {
        val p = ProjectShell("s", "No project", "/home/me/.t3/scratch/")
        assertTrue(isScratchProject(p, "/home/me/.t3/scratch"))
        assertFalse(isScratchProject(p, null))
        assertFalse(isScratchProject(ProjectShell("x", "x", "/home/me/code"), "/home/me/.t3/scratch"))
    }
}
