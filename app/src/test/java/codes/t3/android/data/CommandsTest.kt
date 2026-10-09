package codes.t3.android.data

import codes.t3.android.data.model.ModelSelection
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandsTest {
    private val sel = ModelSelection("claudeAgent", "claude-opus-5-5")

    @Test fun sendStartImmediately() {
        val c = Commands.sendMessage("t1", "hi", sel, Commands.DispatchMode.StartImmediately, serverResolvesContext = true)
        assertEquals("message.dispatch", c["type"]!!.jsonPrimitive.content)
        assertEquals("mobile", c["creationSource"]!!.jsonPrimitive.content)
        assertEquals("auto", c["deliveryIntent"]!!.jsonPrimitive.content)
        assertEquals("start_immediately", c["dispatchMode"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertTrue(c["attachments"] is JsonArray)
        assertEquals("claudeAgent", c["modelSelection"]!!.jsonObject["instanceId"]!!.jsonPrimitive.content)
    }

    @Test fun steerOnOldServerTargetsRun() {
        val c = Commands.sendMessage("t1", "hi", null, Commands.DispatchMode.Steer("r9"), serverResolvesContext = false)
        val mode = c["dispatchMode"]!!.jsonObject
        assertEquals("steer_active", mode["type"]!!.jsonPrimitive.content)
        assertEquals("r9", mode["targetRunId"]!!.jsonPrimitive.content)
        assertFalse(c.containsKey("modelSelection"))
    }

    @Test fun answersShapeSingleVsMulti() {
        val c = Commands.respondAnswers("t1", "q", mapOf("a" to listOf("x"), "b" to listOf("1", "2")), setOf("b"))
        val answers = c["answers"] as JsonObject
        assertEquals("x", answers["a"]!!.jsonPrimitive.content)
        assertEquals(2, (answers["b"] as JsonArray).size)
    }

    @Test fun launchThreadWorktree() {
        val c = Commands.launchThread("t1", "p1", "Add dark mode to the settings screen", sel, "auto", "default", Commands.Workspace.NewWorktree("main", "t3/abcd1234"))
        val ws = c["workspaceStrategy"]!!.jsonObject
        assertEquals("worktree", ws["type"]!!.jsonPrimitive.content)
        assertEquals("main", ws["baseRef"]!!.jsonPrimitive.content)
        assertEquals("Add dark mode to the settings screen", c["title"]!!.jsonPrimitive.content)
        assertEquals("Add dark mode to the settings screen", c["initialMessage"]!!.jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test fun titleSeedTruncates() {
        assertEquals("New thread", Commands.titleSeed("   "))
        assertTrue(Commands.titleSeed("x".repeat(200)).length <= 60)
        assertTrue(Commands.worktreeBranch().matches(Regex("t3/[0-9a-f]{8}")))
    }
}
