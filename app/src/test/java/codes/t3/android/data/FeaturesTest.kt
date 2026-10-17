package codes.t3.android.data

import codes.t3.android.data.model.PendingRuntimeRequestRef
import codes.t3.android.data.model.ProviderSkill
import codes.t3.android.data.model.Run
import codes.t3.android.data.model.ServerProvider
import codes.t3.android.data.model.SlashCommand
import codes.t3.android.data.model.ThreadLineage
import codes.t3.android.data.model.ThreadShell
import codes.t3.android.data.model.T3Json
import codes.t3.android.data.model.WorkflowAgent
import codes.t3.android.data.model.WorkflowPhase
import codes.t3.android.data.state.groupWorkflow
import codes.t3.android.data.state.latestMergeBackRun
import codes.t3.android.ui.components.resolveCustomSnooze
import codes.t3.android.ui.components.snoozePresets
import codes.t3.android.ui.thread.CommandKind
import codes.t3.android.ui.thread.builtinCommands
import codes.t3.android.ui.thread.commandSuggestions
import codes.t3.android.ui.thread.formatResetsIn
import codes.t3.android.ui.thread.formatTokens
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class FeaturesTest {
    private fun shell(id: String, status: String = "idle", run: String? = "r1", request: String? = null, completed: String? = null, error: String? = null, parent: String? = null) = ThreadShell(
        id = id, projectId = "p", title = "T $id", status = status, latestRunId = run,
        activeRunId = if (status == "running") run else null,
        pendingRuntimeRequest = request?.let { PendingRuntimeRequestRef("q-$id", it) },
        latestRunCompletedAt = completed, lastErrorClass = error,
        lineage = parent?.let { ThreadLineage(parentThreadId = it, relationshipToParent = "subagent") },
    )

    @Test fun alertsFollowDesktopRules() {
        val t = ThreadAlertTracker()
        // First sight never alerts.
        assertTrue(t.update("e", listOf(shell("a", status = "running"))).isEmpty())
        val approval = t.update("e", listOf(shell("a", status = "waiting", request = "command")))
        assertEquals(listOf(AlertKind.Approval), approval.map { it.kind })
        // Same run and state: no repeat.
        assertTrue(t.update("e", listOf(shell("a", status = "waiting", request = "command"))).isEmpty())
        val done = t.update("e", listOf(shell("a", status = "completed", completed = "2026-10-17T10:00:00Z")))
        assertEquals(listOf(AlertKind.Completed), done.map { it.kind })
        assertTrue(t.update("e", listOf(shell("a", status = "completed", completed = "2026-10-17T10:00:00Z"))).isEmpty())
        val limited = t.update("e", listOf(shell("a", status = "failed", run = "r2", error = "usage_limit")))
        assertEquals(listOf(AlertKind.Limited), limited.map { it.kind })
    }

    @Test fun parentInheritsSubagentInputAndSubagentsAreSilent() {
        val t = ThreadAlertTracker()
        t.update("e", listOf(shell("p", status = "running"), shell("c", status = "running", parent = "p")))
        val alerts = t.update("e", listOf(shell("p", status = "running"), shell("c", status = "waiting", request = "user_input", parent = "p")))
        assertEquals(listOf("p" to AlertKind.Input), alerts.map { it.threadId to it.kind })
    }

    @Test fun gitStatusParsesAndExplainsActions() {
        val json = T3Json.parseToJsonElement(
            """{"isRepo":true,"hasPrimaryRemote":true,"isDefaultRef":false,"refName":"feat/x","hasWorkingTreeChanges":true,
               "workingTree":{"files":[{"path":"src/a.ts","insertions":12,"deletions":3}],"insertions":12,"deletions":3},
               "hasUpstream":true,"aheadCount":1,"behindCount":0,"aheadOfDefaultCount":2,
               "pr":{"number":42,"title":"Add x","url":"https://github.com/o/r/pull/42","baseRef":"main","headRef":"feat/x","state":"open"},"future":1}""",
        ).jsonObject
        val s = GitStatus.parse(json)
        assertEquals("1 file changed · 1 ahead", s.summary)
        assertNull(s.commitBlocked)
        assertEquals("Commit your changes before pushing.", s.pushBlocked)
        assertEquals(42, s.openPr?.number)
        assertEquals(GitAction.CommitPush, s.bestCommitAction)
        assertEquals(GitAction.CommitPushPr, s.copy(pr = null).bestCommitAction)
    }

    @Test fun gitProgressEvents() {
        fun ev(s: String) = T3Json.parseToJsonElement(s) as JsonObject
        assertEquals(GitProgress.Phase("Pushing"), Git.progress(ev("""{"kind":"phase_started","phase":"push","label":"Pushing"}""")))
        val done = Git.progress(ev("""{"kind":"action_finished","result":{"branch":{"status":"created","name":"t3/x"},"toast":{"title":"Created PR #43","cta":{"kind":"open_pr","label":"View PR","url":"https://github.com/o/r/pull/43"}}}}""")) as GitProgress.Finished
        assertEquals("Created PR #43", done.title)
        assertEquals("t3/x", done.createdBranch)
        assertEquals("https://github.com/o/r/pull/43", done.prUrl)
    }

    @Test fun pullRequestUrls() {
        assertEquals(Git.PrUrl("github.com", "owner/repo", 42, "https://github.com/Owner/Repo/pull/42"), Git.parsePrUrl("https://github.com/Owner/Repo/pull/42/"))
        assertEquals(7, Git.parsePrUrl("https://gitlab.com/g/sub/proj/-/merge_requests/7")?.number)
        assertNull(Git.parsePrUrl("https://github.com/owner/repo/issues/42"))
    }

    @Test fun mergeBackPicksNewestFinishedRun() {
        val runs = listOf(Run("a", ordinal = 1, status = "completed"), Run("b", ordinal = 2, status = "waiting"))
        assertEquals("b", latestMergeBackRun(runs)?.id)
        assertNull(latestMergeBackRun(runs + Run("c", ordinal = 3, status = "running")))
        assertNull(latestMergeBackRun(listOf(Run("a", ordinal = 1, status = "running"))))
    }

    @Test fun workflowGroupsByPhase() {
        val g = groupWorkflow(
            listOf(WorkflowPhase(0, "Research"), WorkflowPhase(2, "Unused")),
            listOf(
                WorkflowAgent(1, "b", state = "completed", phaseIndex = 0),
                WorkflowAgent(0, "a", state = "running", phaseIndex = 0),
                WorkflowAgent(2, "c", state = "queued", phaseIndex = 1, phaseTitle = "Write"),
                WorkflowAgent(3, "d"),
            ),
        )
        assertEquals(listOf("Research", "Write"), g.phases.map { it.title })
        assertEquals(listOf("a", "b"), g.phases[0].agents.map { it.label })
        assertEquals(1, g.phases[0].settled)
        assertEquals(listOf("d"), g.unphased.map { it.label })
        assertEquals(4, g.agentCount)
    }

    @Test fun snoozePresetsMatchUpstream() {
        val zone = ZoneId.of("Australia/Sydney")
        val friday2pm = ZonedDateTime.of(2026, 10, 16, 14, 0, 0, 0, zone)
        assertEquals(listOf("hour", "three-hours", "evening", "tomorrow", "next-week"), snoozePresets(friday2pm).map { it.id })
        // Sunday night: no evening, and next Monday is tomorrow.
        val sunday9pm = ZonedDateTime.of(2026, 10, 18, 21, 0, 0, 0, zone)
        assertEquals(listOf("hour", "three-hours", "tomorrow"), snoozePresets(sunday9pm).map { it.id })
        val now = friday2pm.toInstant()
        assertNull(resolveCustomSnooze(LocalDate.of(2026, 10, 16), LocalTime.of(13, 0), zone, now))
        assertNotNull(resolveCustomSnooze(LocalDate.of(2026, 10, 16), LocalTime.of(15, 0), zone, now))
        // 2:30am on DST day doesn't exist in Sydney.
        assertNull(resolveCustomSnooze(LocalDate.of(2026, 10, 4), LocalTime.of(2, 30), zone, Instant.parse("2026-01-01T00:00:00Z")))
    }

    @Test fun slashSuggestions() {
        val provider = ServerProvider(
            "claudeAgent", driver = "claudeAgent",
            slashCommands = listOf(SlashCommand("compact", "Compact the conversation"), SlashCommand("review")),
            skills = listOf(ProviderSkill("review", "Review a diff"), ProviderSkill("hidden", userInvocable = false)),
        )
        val builtins = builtinCommands(provider, hasThread = true)
        assertNull(commandSuggestions("hello", provider, builtins))
        assertNull(commandSuggestions("/compact now", provider, builtins))
        val all = commandSuggestions("/", provider, builtins)!!
        assertTrue(all.any { it.id == "compact" && it.kind == CommandKind.Provider })
        // A command shadowed by a skill of the same name shows as the skill only.
        assertEquals(listOf(CommandKind.Skill), all.filter { it.id == "review" }.map { it.kind })
        assertTrue(all.none { it.id == "hidden" })
        assertEquals("compact", commandSuggestions("/comp", provider, builtins)!!.first().id)
        assertEquals(listOf("review"), commandSuggestions("\$rev", provider, builtins)!!.map { it.id })
        assertEquals("\$review ", commandSuggestions("\$rev", provider, builtins)!!.first().insertText)
    }

    @Test fun usageFormatting() {
        assertEquals("840", formatTokens(840))
        assertEquals("8.4k", formatTokens(8_400))
        assertEquals("84k", formatTokens(84_000))
        assertEquals("1.2m", formatTokens(1_200_000))
        val now = Instant.parse("2026-10-17T10:00:00Z")
        assertEquals("resets in 2h 14m", formatResetsIn("2026-10-17T12:14:00Z", now))
        assertEquals("resets in 3d 4h", formatResetsIn("2026-10-20T14:00:00Z", now))
    }

    @Test fun commandShapes() {
        val fork = Commands.fork("src", "dst", "r1", "Title fork")
        assertEquals("thread.fork", fork["type"].toString().trim('"'))
        assertNull(fork["threadId"])
        val secret = (V2Commands(false).answerSecret("t", "item", null) as Op).payload
        assertEquals("""{"threadId":"t","turnItemId":"item","answer":{"type":"decline"}}""", secret.toString())
        assertNull(V1Commands.fork("a", "b", "r", null))
    }
}
