package io.github.alextherajunction.codexweb.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionReducerTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `streams agent deltas and completes the turn`() {
        var state = AppServerSnapshot(selectedThreadId = "t1")
        state = state.applyNotification(notification("thread/started", """{"thread":{"id":"t1","preview":"Hi","name":null,"cwd":"/work","updatedAt":100,"status":{"type":"idle"}}}"""))
        state = state.applyNotification(notification("turn/started", """{"threadId":"t1","turn":{"id":"turn1","status":"inProgress","items":[]}}"""))
        state = state.applyNotification(notification("item/started", """{"threadId":"t1","turnId":"turn1","item":{"type":"agentMessage","id":"item1","text":"","phase":null,"memoryCitation":null}}"""))
        state = state.applyNotification(notification("item/agentMessage/delta", """{"threadId":"t1","turnId":"turn1","itemId":"item1","delta":"Hello "}"""))
        state = state.applyNotification(notification("item/agentMessage/delta", """{"threadId":"t1","turnId":"turn1","itemId":"item1","delta":"world"}"""))
        state = state.applyNotification(notification("item/completed", """{"threadId":"t1","turnId":"turn1","item":{"type":"agentMessage","id":"item1","text":"Hello world","phase":null,"memoryCitation":null}}"""))
        state = state.applyNotification(notification("turn/completed", """{"threadId":"t1","turn":{"id":"turn1","status":"completed","items":[{"type":"agentMessage","id":"item1","text":"Hello world","phase":null,"memoryCitation":null}]}}"""))

        assertEquals("Hello world", state.selectedTurns.single().items.single().text)
        assertEquals("completed", state.selectedTurns.single().status)
        assertEquals(null, state.activeTurnByThread["t1"])
    }

    @Test
    fun `keeps command output while streaming and preserves unknown notifications`() {
        var state = AppServerSnapshot(
            threadStatusByThread = mapOf("t1" to "active"),
            turnsByThread = mapOf("t1" to listOf(TurnInfo(id = "turn1", status = "inProgress"))),
        )
        state = state.applyNotification(notification("item/started", """{"threadId":"t1","turnId":"turn1","item":{"type":"commandExecution","id":"cmd1","command":"ls","cwd":"/work","status":"inProgress","aggregatedOutput":null,"exitCode":null,"processId":null,"source":"agent","commandActions":[]}}"""))
        state = state.applyNotification(notification("item/commandExecution/outputDelta", """{"threadId":"t1","turnId":"turn1","itemId":"cmd1","delta":"a.txt\n"}"""))
        state = state.applyNotification(notification("item/commandExecution/outputDelta", """{"threadId":"t1","turnId":"turn1","itemId":"cmd1","delta":"b.txt\n"}"""))
        state = state.applyNotification(notification("future/capability", """{"value":42}"""))

        val command = state.turnsByThread.getValue("t1").single().items.single()
        assertEquals("ls", command.title)
        assertEquals("a.txt\nb.txt\n", command.detail)
        assertTrue(state.diagnostics.any { it.method == "unhandled:future/capability" })
    }

    @Test
    fun `tracks plan usage goal and thread state`() {
        var state = AppServerSnapshot(selectedThreadId = "t1")
        state = state.applyNotification(notification("turn/plan/updated", """{"threadId":"t1","turnId":"turn1","explanation":"working","plan":[{"step":"Inspect","status":"completed"},{"step":"Patch","status":"inProgress"}]}"""))
        state = state.applyNotification(notification("thread/tokenUsage/updated", """{"threadId":"t1","turnId":"turn1","tokenUsage":{"total":{"totalTokens":1200,"inputTokens":1000,"cachedInputTokens":0,"outputTokens":200,"reasoningOutputTokens":50},"last":{"totalTokens":100,"inputTokens":80,"cachedInputTokens":0,"outputTokens":20,"reasoningOutputTokens":5},"modelContextWindow":128000}}"""))
        state = state.applyNotification(notification("thread/goal/updated", """{"threadId":"t1","turnId":"turn1","goal":{"threadId":"t1","objective":"Ship Android","status":"active","tokenBudget":5000,"tokensUsed":1200,"timeUsedSeconds":30,"createdAt":1,"updatedAt":2}}"""))
        state = state.applyNotification(notification("thread/status/changed", """{"threadId":"t1","status":{"type":"active","activeFlags":["waitingOnApproval"]}}"""))

        assertEquals(2, state.planByThread.getValue("t1").steps.size)
        assertEquals(1200L, state.usageByThread.getValue("t1").totalTokens)
        assertEquals(128000L, state.usageByThread.getValue("t1").contextWindow)
        assertEquals("Ship Android", state.goalByThread.getValue("t1").objective)
        assertEquals("active", state.threadStatusByThread["t1"])
    }

    @Test
    fun `terminal interaction is shown on the command item and is not a diagnostic`() {
        var state = AppServerSnapshot(
            turnsByThread = mapOf(
                "t1" to listOf(
                    TurnInfo(
                        id = "turn1",
                        status = "inProgress",
                        items = listOf(
                            ChatItem(
                                id = "cmd1",
                                kind = "commandExecution",
                                title = "npm login",
                                status = "inProgress",
                            ),
                        ),
                    ),
                ),
            ),
        )
        state = state.applyNotification(
            notification(
                "item/commandExecution/terminalInteraction",
                """{"threadId":"t1","turnId":"turn1","itemId":"cmd1","processId":"42","stdin":"user@example.com\n"}""",
            ),
        )
        state = state.applyNotification(
            notification(
                "item/completed",
                """{"threadId":"t1","turnId":"turn1","item":{"type":"commandExecution","id":"cmd1","command":"npm login","status":"completed","aggregatedOutput":"logged in\n","exitCode":0}}""",
            ),
        )

        val command = state.turnsByThread.getValue("t1").single().items.single()
        assertEquals("user@example.com\n", command.terminalInput)
        assertEquals("logged in\n\nexit code: 0", command.detail)
        assertTrue(state.diagnostics.none { it.method.contains("terminalInteraction") })
    }

    @Test
    fun `deprecation notice is preserved as a sourced diagnostic without unhandled prefix`() {
        val state = AppServerSnapshot().applyNotification(
            notification(
                "deprecationNotice",
                """{"summary":"Full-history hydration is deprecated","details":"Use thread/turns/list instead."}""",
            ),
        )

        val diagnostic = state.diagnostics.single()
        assertEquals("app-server.deprecationNotice", diagnostic.method)
        assertTrue(diagnostic.summary.contains("Full-history hydration is deprecated"))
        assertTrue(diagnostic.summary.contains("Use thread/turns/list instead."))
        assertTrue(state.diagnostics.none { it.method.startsWith("unhandled:") })
    }

    private fun notification(method: String, params: String): RpcNotification =
        RpcNotification(method, json.parseToJsonElement(params).jsonObject as JsonObject)
}
