package io.github.alextherajunction.codexweb.protocol

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingRequestLifecycleTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `failed response removes the pending request`() {
        val request = pendingRequest()
        val state = MutableStateFlow(AppServerSnapshot(pendingRequests = listOf(request)))

        val error = runCatching {
            state.respondAndResolve(request) {
                throw IllegalStateException("Web bridge is not connected")
            }
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertTrue(state.value.pendingRequests.isEmpty())
    }

    @Test
    fun `successful response removes the pending request`() {
        val request = pendingRequest()
        val state = MutableStateFlow(AppServerSnapshot(pendingRequests = listOf(request)))

        val result = state.respondAndResolve(request) { "sent" }

        assertEquals("sent", result)
        assertTrue(state.value.pendingRequests.isEmpty())
    }

    @Test
    fun `answering a user input request removes duplicate item prompts`() {
        val first = pendingRequest(itemId = "item-1", turnId = "turn-1")
        val replayed = pendingRequest(id = 2, itemId = "item-1", turnId = "turn-2")
        val state = MutableStateFlow(AppServerSnapshot(pendingRequests = listOf(first, replayed)))

        state.update { it.removePendingRequest(first) }

        assertTrue(state.value.pendingRequests.isEmpty())
    }

    @Test
    fun `a replay with the same item replaces the existing prompt`() {
        val first = pendingRequest(itemId = "item-1", turnId = "turn-1")
        val replayed = pendingRequest(id = 2, itemId = "item-1", turnId = "turn-2")

        val state = AppServerSnapshot(pendingRequests = listOf(first)).addPendingRequest(replayed)

        assertEquals(listOf(replayed), state.pendingRequests)
    }

    private fun pendingRequest(
        id: Int = 1,
        itemId: String = "item-1",
        turnId: String = "turn-1",
    ): PendingRequest = parsePendingRequest(
        JsonPrimitive("bridge-server-request:$id"),
        "item/tool/requestUserInput",
        json.parseToJsonElement(
            """{"threadId":"thread-1","turnId":"$turnId","itemId":"$itemId","questions":[{"id":"choice","header":"Choice","question":"Pick one"}]}""",
        ).jsonObject,
    )
}
