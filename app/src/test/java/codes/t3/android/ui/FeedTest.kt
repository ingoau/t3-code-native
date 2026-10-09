package codes.t3.android.ui

import codes.t3.android.screenshots.Fixtures
import codes.t3.android.ui.thread.FeedEntry
import codes.t3.android.ui.thread.buildFeed
import codes.t3.android.ui.thread.summarizeWork
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedTest {
    @Test fun finishedTurnFoldsWorkAndKeepsAnswer() {
        val state = Fixtures.conversationState(live = false)
        val feed = buildFeed(state.timeline, state.runs, activeRunId = null)
        assertEquals(
            listOf(FeedEntry.User::class, FeedEntry.Fold::class, FeedEntry.Checkpoint::class, FeedEntry.Assistant::class, FeedEntry.User::class),
            feed.map { it::class },
        )
        val fold = feed[1] as FeedEntry.Fold
        assertTrue(fold.label, fold.label.startsWith("Worked for 2m"))
        assertTrue((feed[3] as FeedEntry.Assistant).final)
    }

    @Test fun liveTurnShowsWorkInline() {
        val state = Fixtures.conversationState(live = true)
        val feed = buildFeed(state.timeline, state.runs, activeRunId = "run-2")
        val last = feed.last()
        assertTrue(last is FeedEntry.Work && last.live)
        assertEquals(3, (last as FeedEntry.Work).items.size)
    }

    @Test fun pendingApprovalRowIsHiddenInFavorOfCard() {
        val state = Fixtures.approvalState()
        val feed = buildFeed(state.timeline, state.runs, activeRunId = "run-a")
        assertTrue(feed.none { it is FeedEntry.Work && it.items.any { i -> i.type == "approval_request" } })
    }

    @Test fun summaries() {
        val state = Fixtures.conversationState(live = false)
        val work = state.timeline.filter { it.type in setOf("command_execution", "file_change") }
        assertEquals("Ran 3 commands, edited 2 files", summarizeWork(work))
    }
}
