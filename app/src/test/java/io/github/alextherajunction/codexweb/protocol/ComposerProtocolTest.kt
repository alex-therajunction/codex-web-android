package io.github.alextherajunction.codexweb.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerProtocolTest {
    @Test
    fun `turn collaboration mode matches the web request shape`() {
        val request = buildJsonObject {
            putCollaborationMode(mode = "plan", model = "gpt-test")
        }

        val collaboration = request.getValue("collaborationMode").jsonObject
        val settings = collaboration.getValue("settings").jsonObject
        assertEquals("plan", collaboration.getValue("mode").jsonPrimitive.content)
        assertEquals("gpt-test", settings.getValue("model").jsonPrimitive.content)
        assertTrue(settings.getValue("reasoning_effort") is JsonNull)
        assertTrue(settings.getValue("developer_instructions") is JsonNull)
    }

    @Test
    fun `non plan collaboration mode is explicit default`() {
        val request = buildJsonObject {
            putCollaborationMode(mode = "default", model = "gpt-test")
        }

        assertEquals(
            "default",
            request.getValue("collaborationMode").jsonObject
                .getValue("mode").jsonPrimitive.content,
        )
    }

    @Test
    fun `parses mcp server summaries`() {
        val servers = parseMcpServerSummaries(
            Json.parseToJsonElement(
                """{"data":[{"name":"repo","tools":{"read":{},"write":{}},"authStatus":"notLoggedIn","runtimeStatus":"ready"}]}""",
            ),
        )

        assertEquals(1, servers.size)
        assertEquals("repo", servers.single().name)
        assertEquals(2, servers.single().toolCount)
        assertEquals("notLoggedIn", servers.single().authStatus)
        assertEquals("ready", servers.single().runtimeStatus)
    }

    @Test
    fun `parses primary and secondary rate limits`() {
        val limits = parseRateLimitSummary(
            Json.parseToJsonElement(
                """{"rateLimits":{"primary":{"usedPercent":42,"resetsAt":100},"secondary":{"usedPercent":7,"resetsAt":200}}}""",
            ),
        )

        assertEquals(42, limits.primaryUsedPercent)
        assertEquals(7, limits.secondaryUsedPercent)
        assertEquals(100L, limits.primaryResetsAt)
        assertEquals(200L, limits.secondaryResetsAt)
    }

    @Test
    fun `memory settings default enabled and preserve explicit false`() {
        val defaults = parseMemorySettings(Json.parseToJsonElement("""{"config":{}}"""))
        val disabled = parseMemorySettings(
            Json.parseToJsonElement(
                """{"config":{"memories":{"use_memories":false,"generate_memories":true}}}""",
            ),
        )

        assertTrue(defaults.useMemories)
        assertTrue(defaults.generateMemories)
        assertEquals(false, disabled.useMemories)
        assertEquals(true, disabled.generateMemories)
    }
}
