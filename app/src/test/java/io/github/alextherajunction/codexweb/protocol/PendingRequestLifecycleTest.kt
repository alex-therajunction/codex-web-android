package io.github.alextherajunction.codexweb.protocol

import kotlinx.coroutines.flow.MutableStateFlow
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
            state.respondAndResolve(request.id) {
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

        val result = state.respondAndResolve(request.id) { "sent" }

        assertEquals("sent", result)
        assertTrue(state.value.pendingRequests.isEmpty())
    }

    private fun pendingRequest(): PendingRequest = parsePendingRequest(
        JsonPrimitive("bridge-server-request:1"),
        "item/tool/requestUserInput",
        json.parseToJsonElement(
            """{"threadId":"thread-1","questions":[{"id":"choice","header":"Choice","question":"Pick one"}]}""",
        ).jsonObject,
    )
}
