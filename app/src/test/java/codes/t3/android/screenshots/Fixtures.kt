package codes.t3.android.screenshots

import codes.t3.android.data.ConnectionStatus
import codes.t3.android.data.EnvironmentSnapshot
import codes.t3.android.data.ProjectEntry
import codes.t3.android.data.SavedEnvironment
import codes.t3.android.data.ThreadEntry
import codes.t3.android.data.model.EnvironmentDescriptor
import codes.t3.android.data.model.EnvironmentPlatform
import codes.t3.android.data.model.LatestVisibleMessage
import codes.t3.android.data.model.ModelCapabilities
import codes.t3.android.data.model.ModelSelection
import codes.t3.android.data.model.ModelOptionValue
import codes.t3.android.data.model.OptionChoice
import codes.t3.android.data.model.OptionDescriptor
import codes.t3.android.data.model.PendingRuntimeRequestRef
import codes.t3.android.data.model.ProjectShell
import codes.t3.android.data.model.ProviderAuth
import codes.t3.android.data.model.ProviderModel
import codes.t3.android.data.model.Run
import codes.t3.android.data.model.RuntimeRequest
import codes.t3.android.data.model.ServerConfig
import codes.t3.android.data.model.ServerProvider
import codes.t3.android.data.model.ThreadShell
import codes.t3.android.data.model.TurnItem
import codes.t3.android.data.state.ThreadState
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

/** Deterministic-looking demo data, with timestamps relative to "now" so relative labels stay stable. */
object Fixtures {
    private val now: Instant = Instant.now()
    fun ago(seconds: Long): String = now.minusSeconds(seconds).toString()

    const val ENV = "env-macbook"

    val saved = SavedEnvironment(ENV, "Julius's MacBook Pro", "http://192.168.1.20:3773/", "token")
    val savedServer = SavedEnvironment("env-box", "build-box", "http://build-box.tail1234.ts.net:3773/", "token")

    private val opus = ProviderModel(
        slug = "claude-opus-5-5", name = "Claude Opus 5.5", shortName = "Opus 5.5", isDefault = true,
        capabilities = ModelCapabilities(
            listOf(
                OptionDescriptor(
                    type = "select", id = "effort", label = "Reasoning effort",
                    options = listOf(OptionChoice("low", "Low"), OptionChoice("medium", "Medium"), OptionChoice("high", "High", isDefault = true), OptionChoice("max", "Max")),
                ),
                OptionDescriptor(type = "boolean", id = "thinking", label = "Extended thinking", description = "Let the model think before answering"),
            ),
        ),
    )
    val providers = listOf(
        ServerProvider(
            instanceId = "claudeAgent", driver = "claudeAgent", displayName = "Claude", showInteractionModeToggle = true,
            supportedRuntimeModes = listOf("approval-required", "auto-accept-edits", "auto", "full-access"),
            auth = ProviderAuth("authenticated", email = "julius@t3.gg"),
            models = listOf(
                opus,
                ProviderModel("claude-sonnet-5-5", "Claude Sonnet 5.5", shortName = "Sonnet 5.5", capabilities = opus.capabilities),
                ProviderModel("claude-haiku-5-5", "Claude Haiku 5.5", shortName = "Haiku 5.5", badge = "fast"),
            ),
        ),
        ServerProvider(
            instanceId = "codex", driver = "codex", displayName = "Codex",
            models = listOf(ProviderModel("gpt-6-astra", "GPT-6 Astra", badge = "new"), ProviderModel("gpt-5.4-codex", "GPT-5.4 Codex")),
        ),
    )
    val selection = ModelSelection("claudeAgent", "claude-opus-5-5", listOf(ModelOptionValue("effort", JsonPrimitive("high"))))

    val config = ServerConfig(
        environment = EnvironmentDescriptor(ENV, "Julius's MacBook Pro", EnvironmentPlatform("darwin", "arm64", "laptop"), "0.0.46", 2),
        providers = providers,
    )

    val projects = listOf(
        ProjectShell("p-t3", "t3code", "/Users/julius/code/t3code"),
        ProjectShell("p-api", "acme-api", "/Users/julius/code/acme-api"),
        ProjectShell("p-web", "marketing-site", "/Users/julius/code/marketing-site"),
        ProjectShell("p-dot", "dotfiles", "/Users/julius/dotfiles"),
    )
    private fun project(id: String) = projects.first { it.id == id }

    private fun thread(
        id: String, project: String, title: String, ageSec: Long, status: String = "completed",
        preview: String? = null, branch: String? = null, pinned: Boolean = false, settled: Boolean = false,
        pending: String? = null, error: String? = null, errorClass: String? = null, unread: Boolean = false,
        plan: Boolean = false,
    ) = ThreadShell(
        id = id, projectId = project, title = title, status = status,
        activeRunId = if (status == "running") "run-$id" else null,
        activityRunStartedAt = if (status == "running") ago(83) else null,
        branch = branch, modelSelection = selection, providerInstanceId = "claudeAgent",
        latestVisibleMessage = preview?.let { LatestVisibleMessage("m-$id", "assistant", it) },
        latestUserMessageAt = ago(ageSec), updatedAt = ago(ageSec), createdAt = ago(ageSec + 600),
        latestRunCompletedAt = if (status == "completed") ago(ageSec - 30) else null,
        lastVisitedAt = if (unread) ago(ageSec + 60) else ago(0),
        pinnedAt = if (pinned) ago(86400) else null,
        settledAt = if (settled) ago(ageSec - 60) else null,
        pendingRuntimeRequest = pending?.let { PendingRuntimeRequestRef("req-$id", it, ago(30)) },
        lastError = error, lastErrorClass = errorClass,
        hasActionableProposedPlan = plan,
    )

    val threads: List<ThreadEntry> = listOf(
        thread("t1", "p-t3", "Fix flaky websocket reconnect test", 95, status = "running", branch = "t3/8f2a91c4", pinned = true,
            preview = "The reconnect test races the keepalive timer. Patching the fake clock now."),
        thread("t2", "p-api", "Add rate limiting to the public webhooks endpoint", 240, status = "waiting", pending = "command", branch = "feat/rate-limit",
            preview = "I need to run the integration suite against the local Redis."),
        thread("t3", "p-web", "Redesign the pricing page hero", 900, status = "waiting", pending = "user_input",
            preview = "Which layout should I go with for the hero section?"),
        thread("t4", "p-t3", "Migrate pairing to OAuth token exchange", 3600 * 2, unread = true, branch = "t3/c41e07aa",
            preview = "Done. Pairing now exchanges the one-time code for a 30-day bearer token."),
        thread("t5", "p-api", "Plan the billing service split", 3600 * 5, plan = true,
            preview = "Here's a three-phase plan to split billing out of the monolith."),
        thread("t6", "p-dot", "Set up zsh completions for t3", 3600 * 26, status = "failed", error = "Usage limit reached. Retry after 4:00 PM.", errorClass = "usage_limit"),
        thread("t7", "p-t3", "Bump Effect to 4.0.1 across the workspace", 3600 * 49,
            preview = "All packages build and the test suite passes on 4.0.1."),
        thread("t8", "p-web", "Write release notes for 0.0.46", 3600 * 72, settled = true),
        thread("t9", "p-t3", "Investigate memory growth in the terminal service", 3600 * 120, settled = true),
        thread("t10", "p-dot", "Configure Tailscale Serve for the build box", 3600 * 200, settled = true),
    ).map { ThreadEntry(ENV, saved.label, it, project(it.projectId)) }

    val projectEntries = projects.map { ProjectEntry(ENV, saved.label, it) }

    val environments = listOf(
        EnvironmentSnapshot(saved, ConnectionStatus.Connected, config, true),
        EnvironmentSnapshot(
            savedServer,
            ConnectionStatus.Reconnecting("Couldn't reach the server. Is `t3` running and reachable from this device?", 4000),
            null,
            false,
        ),
    )

    // ------------------------------------------------------------------ timeline

    private var ordinal = 0L

    fun item(type: String, runId: String?, status: String = "completed", startedSec: Long = 0, durationSec: Long = 2, extra: Map<String, JsonElement> = emptyMap()): TurnItem {
        val obj = buildJsonObject {
            put("id", "i-${ordinal}")
            put("type", type)
            put("threadId", "t1")
            put("runId", runId)
            put("ordinal", ordinal++)
            put("status", status)
            put("startedAt", ago(startedSec))
            put("completedAt", if (status == "completed" || status == "failed") ago(startedSec - durationSec) else null)
            put("updatedAt", ago(startedSec - durationSec))
            extra.forEach { (k, v) -> put(k, v) }
        }
        return TurnItem(obj)
    }

    private fun s(v: String) = JsonPrimitive(v)
    private fun b(v: Boolean) = JsonPrimitive(v)
    private fun n(v: Int) = JsonPrimitive(v)

    fun user(runId: String, text: String, sec: Long, intent: String = "turn_start") =
        item("user_message", runId, startedSec = sec, extra = mapOf("text" to s(text), "inputIntent" to s(intent), "attachments" to JsonArray(emptyList())))

    fun assistant(runId: String, text: String, sec: Long, streaming: Boolean = false) =
        item("assistant_message", runId, status = if (streaming) "running" else "completed", startedSec = sec, extra = mapOf("text" to s(text), "streaming" to b(streaming)))

    fun reasoning(runId: String, text: String, sec: Long, running: Boolean = false) =
        item("reasoning", runId, status = if (running) "running" else "completed", startedSec = sec, extra = mapOf("text" to s(text), "streaming" to b(running)))

    fun command(runId: String, cmd: String, sec: Long, output: String? = null, exit: Int? = 0, running: Boolean = false) =
        item(
            "command_execution", runId, status = if (running) "running" else "completed", startedSec = sec,
            extra = buildMap {
                put("input", s(cmd))
                output?.let { put("output", s(it)) }
                if (!running && exit != null) put("exitCode", n(exit))
            },
        )

    fun fileChange(runId: String, path: String, add: Int, del: Int, sec: Long, diff: String) =
        item("file_change", runId, startedSec = sec, extra = mapOf("fileName" to s(path), "additions" to n(add), "deletions" to n(del), "diffStr" to s(diff)))

    fun search(runId: String, pattern: String, sec: Long) =
        item("file_search", runId, startedSec = sec, extra = mapOf("pattern" to s(pattern)))

    val ANSWER = """
        Found it. The reconnect test is racing the **keepalive timer**: the fake clock advances by 5 s, which fires the ping
        check *before* the mock server has sent its `Pong`, so the client tears down a perfectly healthy socket.

        The fix is to only declare the socket dead after a full interval **without** a pong:

        ```ts
        const pinger = Effect.gen(function* () {
          while (true) {
            yield* Effect.sleep("5 seconds")
            if (!pongSeen) return yield* socket.close("keepalive timeout")
            pongSeen = false
            yield* socket.send({ _tag: "Ping" })
          }
        })
        ```

        - `rpc/session.ts` — reset `pongSeen` only after sending a ping
        - `session.test.ts` — advance the clock in two steps so the pong lands first

        The suite now passes **50/50** runs locally.
    """.trimIndent()

    fun conversationState(live: Boolean = true): ThreadState {
        ordinal = 0
        val r1 = "run-1"
        val r2 = "run-2"
        val items = mutableListOf(
            user(r1, "The websocket reconnect test is flaky on CI. Can you figure out why and fix it?", 600),
            reasoning(r1, "Let me look at the reconnect test and the session keepalive logic.", 598),
            search(r1, "makePinger", 596),
            command(r1, "rg -n \"pongSeen\" packages/client-runtime/src", 594, "rpc/session.ts:88:  let pongSeen = true\nrpc/session.ts:131:      pongSeen = false"),
            command(r1, "pnpm vitest run rpc/session.test.ts --repeat 50", 590, " ✓ rpc/session.test.ts (50 runs)\n   ✗ reconnects after keepalive timeout  (7/50 failed)\n\n Test Files  1 failed (1)", exit = 1),
            fileChange(r1, "packages/client-runtime/src/rpc/session.ts", 6, 3, 560, "@@ -128,9 +128,12 @@\n-      if (!pongSeen) return yield* close()\n-      yield* send(ping)\n+      if (!pongSeen) {\n+        return yield* socket.close(\"keepalive timeout\")\n+      }\n+      pongSeen = false\n+      yield* socket.send({ _tag: \"Ping\" })"),
            fileChange(r1, "packages/client-runtime/src/rpc/session.test.ts", 9, 2, 550, "@@ -40,6 +40,13 @@\n+  yield* TestClock.adjust(\"4 seconds\")\n+  yield* server.pong()\n+  yield* TestClock.adjust(\"1 second\")"),
            command(r1, "pnpm vitest run rpc/session.test.ts --repeat 50", 520, " ✓ rpc/session.test.ts (50 runs)\n\n Test Files  1 passed (1)\n      Tests  50 passed (50)"),
            assistant(r1, ANSWER, 500),
            item("checkpoint", r1, startedSec = 499, extra = mapOf("files" to buildJsonArray {
                add(buildJsonObject { put("path", "packages/client-runtime/src/rpc/session.ts"); put("kind", "modified"); put("additions", 6); put("deletions", 3) })
                add(buildJsonObject { put("path", "packages/client-runtime/src/rpc/session.test.ts"); put("kind", "modified"); put("additions", 9); put("deletions", 2) })
            })),
            user(r2, "Nice. Can you add a regression test that fails without the fix?", 95),
        )
        val runs = mutableMapOf(
            r1 to Run(r1, "t1", 1, status = "completed", startedAt = ago(600), completedAt = ago(480)),
        )
        if (live) {
            items += reasoning(r2, "I'll write a test that holds the pong back until just after the ping deadline.", 90)
            items += fileChange(r2, "packages/client-runtime/src/rpc/session.test.ts", 24, 0, 70, "+it.effect(\"keeps a healthy socket open\", () => …)")
            items += command(r2, "pnpm vitest run rpc/session.test.ts", 20, running = true)
            runs[r2] = Run(r2, "t1", 2, status = "running", startedAt = ago(83), workStartedAt = ago(83))
        }
        val thread = threads.first().thread.let { if (live) it else it.copy(status = "completed", activeRunId = null) }
        return ThreadState(sequence = 10, thread = thread, runs = runs, items = items.associateBy { it.id }, synchronized = true)
    }

    fun approvalState(): ThreadState {
        ordinal = 100
        val r = "run-a"
        val items = listOf(
            user(r, "Add rate limiting to POST /webhooks — 100 req/min per API key, backed by Redis.", 300),
            reasoning(r, "I'll add a sliding-window limiter middleware and wire it into the webhooks router.", 295),
            fileChange(r, "src/middleware/rateLimit.ts", 58, 0, 280, "+export const rateLimit = (opts: RateLimitOptions) => …"),
            fileChange(r, "src/routes/webhooks.ts", 4, 1, 270, "-router.post(\"/webhooks\", handle)\n+router.post(\"/webhooks\", rateLimit({ perMinute: 100 }), handle)"),
            assistant(r, "The middleware is in place. I'd like to run the integration suite against your local Redis to make sure the limits hold under load.", 250),
            item("approval_request", r, status = "waiting", startedSec = 240, extra = mapOf(
                "requestId" to s("req-1"), "requestKind" to s("command"),
                "prompt" to s("docker compose up -d redis && pnpm test:integration --grep \"rate limit\""),
                "options" to buildJsonArray {
                    add(buildJsonObject { put("decision", "accept"); put("label", "Allow once") })
                    add(buildJsonObject { put("decision", "acceptForSession"); put("label", "Allow for session") })
                    add(buildJsonObject { put("decision", "decline"); put("label", "Decline") })
                },
            )),
        )
        val req = RuntimeRequest("req-1", kind = "command", status = "pending", responseCapability = buildJsonObject { put("type", "live") })
        val thread = threads[1].thread
        return ThreadState(
            sequence = 5, thread = thread,
            runs = mapOf(r to Run(r, thread.id, 1, status = "waiting", startedAt = ago(300))),
            requests = mapOf(req.id to req), items = items.associateBy { it.id }, synchronized = true,
        )
    }

    fun questionState(): ThreadState {
        ordinal = 200
        val r = "run-q"
        val items = listOf(
            user(r, "Redesign the pricing page hero so it converts better on mobile.", 900),
            reasoning(r, "Looking at the current hero and analytics notes before proposing options.", 890),
            command(r, "ls src/components/pricing", 880, "Hero.tsx\nPlans.tsx\nFaq.tsx"),
            item("user_input_request", r, status = "waiting", startedSec = 870, extra = mapOf(
                "requestId" to s("req-q"),
                "questions" to buildJsonArray {
                    add(buildJsonObject {
                        put("id", "layout"); put("header", "Layout"); put("question", "Which hero layout should I build?")
                        put("options", buildJsonArray {
                            add(buildJsonObject { put("label", "Split"); put("description", "Copy on the left, product shot on the right") })
                            add(buildJsonObject { put("label", "Centered"); put("description", "Big headline, CTA, screenshot underneath") })
                            add(buildJsonObject { put("label", "Video"); put("description", "Autoplaying product loop behind the headline") })
                        })
                    })
                },
            )),
        )
        val req = RuntimeRequest("req-q", kind = "user_input", status = "pending", responseCapability = buildJsonObject { put("type", "live") })
        return ThreadState(sequence = 5, thread = threads[2].thread, runs = mapOf(r to Run(r, "t3", 1, status = "waiting")), requests = mapOf(req.id to req), items = items.associateBy { it.id }, synchronized = true)
    }

    fun planState(): ThreadState {
        ordinal = 300
        val r = "run-p"
        val plan = """
            ## Split billing into its own service

            1. **Extract the domain** — move `invoices/`, `subscriptions/` and the Stripe webhook handlers into `services/billing`.
            2. **Introduce an API boundary** — the monolith calls billing over an internal RPC client; no shared tables.
            3. **Migrate data** — dual-write for one release, backfill, then cut reads over behind a flag.

            Risks: webhook ordering during the cutover, and the reporting jobs that join across invoices and users.
        """.trimIndent()
        val items = listOf(
            user(r, "Plan how we'd split the billing code out of the monolith. Don't change anything yet.", 3600 * 5 + 200),
            reasoning(r, "Mapping billing touch points across the codebase.", 3600 * 5 + 190),
            search(r, "stripe.webhooks", 3600 * 5 + 180),
            command(r, "rg -l \"from '../billing\" src | wc -l", 3600 * 5 + 170, "37"),
            item("proposed_plan", r, startedSec = 3600 * 5, extra = mapOf("planId" to s("plan-1"), "markdown" to s(plan), "streaming" to b(false))),
            item("todo_list", r, startedSec = 3600 * 5 - 5, extra = mapOf("planId" to s("todo-1"), "steps" to buildJsonArray {
                add(buildJsonObject { put("id", "1"); put("text", "Inventory billing modules and their callers"); put("status", "completed") })
                add(buildJsonObject { put("id", "2"); put("text", "Draft the internal RPC contract"); put("status", "completed") })
                add(buildJsonObject { put("id", "3"); put("text", "Write the migration runbook"); put("status", "pending") })
            })),
        )
        val thread = threads[4].thread.copy(interactionMode = "plan")
        return ThreadState(sequence = 5, thread = thread, runs = mapOf(r to Run(r, "t5", 1, status = "completed", startedAt = ago(3600 * 5 + 200), completedAt = ago(3600 * 5 - 10))), items = items.associateBy { it.id }, synchronized = true)
    }

}
