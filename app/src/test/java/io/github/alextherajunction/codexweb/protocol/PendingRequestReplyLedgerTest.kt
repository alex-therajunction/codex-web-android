package io.github.alextherajunction.codexweb.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PendingRequestReplyLedgerTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `same request id resolves with the recorded reply`() {
        val ledger = PendingRequestReplyLedger()
        val request = userInputRequest("bridge-server-request:1", "item-1")
        val reply = PendingRequestReply.Result(buildUserInputResponse(mapOf("choice" to listOf("Yes"))))

        ledger.record(request, reply)

        assertEquals(reply, ledger.lookup(request))
    }

    @Test
    fun `reused request id with different content is still shown`() {
        val ledger = PendingRequestReplyLedger()
        ledger.record(
            userInputRequest("bridge-server-request:1", "item-1"),
            PendingRequestReply.Result(buildUserInputResponse(mapOf("choice" to listOf("Yes")))),
        )

        assertNull(ledger.lookup(userInputRequest("bridge-server-request:1", "item-2")))
    }

    @Test
    fun `reissued user input for the same item resolves with the recorded reply`() {
        val ledger = PendingRequestReplyLedger()
        val reply = PendingRequestReply.Result(buildUserInputResponse(mapOf("choice" to listOf("Yes"))))
        ledger.record(userInputRequest("bridge-server-request:1", "item-1"), reply)

        assertEquals(reply, ledger.lookup(userInputRequest("bridge-server-request:2", "item-1")))
    }

    @Test
    fun `new user input item is still shown`() {
        val ledger = PendingRequestReplyLedger()
        ledger.record(
            userInputRequest("bridge-server-request:1", "item-1"),
            PendingRequestReply.Result(buildUserInputResponse(mapOf("choice" to listOf("Yes")))),
        )

        assertNull(ledger.lookup(userInputRequest("bridge-server-request:2", "item-2")))
    }

    @Test
    fun `reissued user input in a later turn resolves for the same item`() {
        val ledger = PendingRequestReplyLedger()
        val reply = PendingRequestReply.Result(buildUserInputResponse(mapOf("choice" to listOf("Yes"))))
        ledger.record(userInputRequest("bridge-server-request:1", "item-1", "turn-1"), reply)

        assertEquals(reply, ledger.lookup(userInputRequest("bridge-server-request:2", "item-1", "turn-2")))
    }

    @Test
    fun `changed params do not hide the item match for a reused request id`() {
        val ledger = PendingRequestReplyLedger()
        val reply = PendingRequestReply.Result(buildUserInputResponse(mapOf("choice" to listOf("Yes"))))
        ledger.record(userInputRequest("bridge-server-request:1", "item-1", "turn-1"), reply)

        val replayed = userInputRequest("bridge-server-request:1", "item-1", "turn-2")
        assertEquals(reply, ledger.lookup(replayed))
    }

    @Test
    fun `approval with the same item id is not auto answered from a user input reply`() {
        val ledger = PendingRequestReplyLedger()
        ledger.record(
            userInputRequest("bridge-server-request:1", "item-1"),
            PendingRequestReply.Result(buildUserInputResponse(mapOf("choice" to listOf("Yes")))),
        )

        assertNull(ledger.lookup(approvalRequest("bridge-server-request:2", "item-1")))
    }

    private fun userInputRequest(
        id: String,
        itemId: String,
        turnId: String = "turn-1",
    ): PendingRequest = parsePendingRequest(
        JsonPrimitive(id),
        "item/tool/requestUserInput",
        json.parseToJsonElement(
            """{"threadId":"thread-1","turnId":"$turnId","itemId":"$itemId","questions":[{"id":"choice","header":"Choice","question":"Pick one"}]}""",
        ).jsonObject,
    )

    private fun approvalRequest(id: String, itemId: String): PendingRequest = parsePendingRequest(
        JsonPrimitive(id),
        "item/commandExecution/requestApproval",
        json.parseToJsonElement(
            """{"threadId":"thread-1","turnId":"turn-1","itemId":"$itemId","command":"echo ok"}""",
        ).jsonObject,
    )
}
