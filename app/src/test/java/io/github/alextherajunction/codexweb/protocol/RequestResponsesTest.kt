package io.github.alextherajunction.codexweb.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RequestResponsesTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `command approval uses the app-server decision union`() {
        val request = request(
            "item/commandExecution/requestApproval",
            """{"threadId":"t","turnId":"u","itemId":"i","command":"rm -rf build"}""",
        )

        assertEquals("accept", jsonField(buildApprovalResponse(request, ApprovalDecision.ALLOW), "decision"))
        assertEquals("acceptForSession", jsonField(buildApprovalResponse(request, ApprovalDecision.ALLOW_SESSION), "decision"))
        assertEquals("decline", jsonField(buildApprovalResponse(request, ApprovalDecision.DENY), "decision"))
    }

    @Test
    fun `permissions approval does not reuse a generic decision shape`() {
        val request = request(
            "item/permissions/requestApproval",
            """{"threadId":"t","turnId":"u","itemId":"i","permissions":{"network":{"enabled":true},"fileSystem":{"read":["/srv"],"write":["/srv/project"]}}}""",
        )
        val allowed = buildApprovalResponse(request, ApprovalDecision.ALLOW_SESSION)!!.jsonObject
        assertEquals("session", allowed["scope"].toString().trim('"'))
        assertEquals(true, allowed["permissions"]!!.jsonObject["network"]!!.jsonObject["enabled"].toString().toBoolean())
        assertEquals(
            "/srv/project",
            allowed["permissions"]!!.jsonObject["fileSystem"]!!.jsonObject["write"].toString()
                .trim('[', ']', '"'),
        )
        assertNull(allowed["decision"])

        val denied = buildApprovalResponse(request, ApprovalDecision.DENY)!!.jsonObject
        assertEquals("turn", denied["scope"].toString().trim('"'))
        assertEquals("{}", denied["permissions"].toString())
    }

    @Test
    fun `user input answers are keyed by question id`() {
        val payload = buildUserInputResponse(mapOf("region" to listOf("EU"), "name" to listOf("Ada"))).jsonObject
        val answers = payload["answers"]!!.jsonObject
        assertEquals("[\"EU\"]", answers["region"]!!.jsonObject["answers"].toString())
        assertEquals("[\"Ada\"]", answers["name"]!!.jsonObject["answers"].toString())
    }

    private fun request(method: String, params: String): PendingRequest =
        parsePendingRequest(kotlinx.serialization.json.JsonPrimitive(1), method, json.parseToJsonElement(params).jsonObject)

    private fun jsonField(value: kotlinx.serialization.json.JsonElement?, name: String): String? =
        (value as? JsonObject)?.get(name)?.toString()?.trim('"')
}
