package io.github.alextherajunction.codexweb.data

import io.github.alextherajunction.codexweb.ScreenShareStatus
import io.github.alextherajunction.codexweb.ScreenShareUiState
import io.github.alextherajunction.codexweb.reduceFrameMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class ScreenShareTest {
    @Test
    fun `parses screen share capability states`() {
        val available = parseScreenShareCapability(
            jsonObject(
                """{"capability":{"status":"available","display":":0","wsPath":"/screen-share/ws"}}""",
            ),
        )
        assertEquals(
            ScreenShareCapability.Available(display = ":0", wsPath = "/screen-share/ws"),
            available,
        )
        assertEquals(
            ScreenShareCapability.Disabled,
            parseScreenShareCapability(jsonObject("""{"capability":{"status":"disabled"}}""")),
        )
        assertEquals(
            ScreenShareCapability.Unsupported("No X11 DISPLAY found"),
            parseScreenShareCapability(
                jsonObject(
                    """{"capability":{"status":"unsupported","reason":"No X11 DISPLAY found"}}""",
                ),
            ),
        )
    }

    @Test
    fun `builds screen share websocket url without losing bridge token`() {
        assertEquals(
            "wss://codex.example/screen-share/ws?token=secret&tenant=one",
            buildScreenShareWebSocketUrl(
                bridgeUrl = "wss://codex.example/codex-bridge?token=secret&tenant=one",
                wsPath = "/screen-share/ws",
            ),
        )
        assertEquals(
            "ws://192.168.1.20:3001/screen-share/ws?token=secret&takeover=1",
            buildScreenShareWebSocketUrl(
                bridgeUrl = "ws://192.168.1.20:3001/bridge?token=secret&takeover=1",
                wsPath = "/screen-share/ws",
                takeover = true,
            ),
        )
    }

    @Test
    fun `rejects unsafe screen share paths`() {
        listOf("https://evil.example/ws", "//evil.example/ws", "/ws?takeover=1", "ws").forEach { path ->
            assertTrue(
                runCatching {
                    buildScreenShareWebSocketUrl("wss://codex.example/bridge", path)
                }.isFailure,
            )
        }
    }

    @Test
    fun `parses ready status and error frame messages`() {
        assertEquals(
            ScreenShareFrameMessage.Ready,
            parseScreenShareFrameMessage("""{"type":"screen-frame/ready"}"""),
        )
        assertEquals(
            ScreenShareFrameMessage.Status(
                state = ScreenShareFrameState.CONNECTED,
                width = 1920,
                height = 1080,
            ),
            parseScreenShareFrameMessage(
                """{"type":"screen-frame/status","state":"connected","width":1920,"height":1080}""",
            ),
        )
        assertEquals(
            ScreenShareFrameMessage.Status(
                state = ScreenShareFrameState.DISCONNECTED,
                code = 4002,
                reason = "already-active",
            ),
            parseScreenShareFrameMessage(
                """{"type":"screen-frame/status","state":"disconnected","code":4002,"reason":"already-active"}""",
            ),
        )
        assertEquals(
            ScreenShareFrameMessage.Error("Security handshake failure"),
            parseScreenShareFrameMessage(
                """{"type":"screen-frame/error","message":"Security handshake failure"}""",
            ),
        )
        assertNull(parseScreenShareFrameMessage("not json"))
        assertNull(
            parseScreenShareFrameMessage(
                """{"type":"screen-frame/status","state":"connected","width":0}""",
            ),
        )
    }

    @Test
    fun `maps screen share close codes and display metadata`() {
        val base = ScreenShareUiState(
            deploymentUrl = "https://codex.example",
            origin = "https://codex.example",
        )
        val connected = base.reduceFrameMessage(
            ScreenShareFrameMessage.Status(
                state = ScreenShareFrameState.CONNECTED,
                width = 1280,
                height = 720,
            ),
        )
        assertEquals(ScreenShareStatus.CONNECTED, connected.status)
        assertEquals(1280, connected.width)
        assertEquals(720, connected.height)

        val alreadyActive = connected.reduceFrameMessage(
            ScreenShareFrameMessage.Status(
                state = ScreenShareFrameState.DISCONNECTED,
                code = 4002,
                reason = "already-active",
            ),
        )
        assertEquals(ScreenShareStatus.ALREADY_ACTIVE, alreadyActive.status)
        assertNull(alreadyActive.width)
        assertNull(alreadyActive.height)

        val takenOver = connected.reduceFrameMessage(
            ScreenShareFrameMessage.Status(
                state = ScreenShareFrameState.DISCONNECTED,
                code = 4001,
                reason = "taken-over",
            ),
        )
        assertEquals(ScreenShareStatus.TAKEN_OVER, takenOver.status)

        assertEquals(
            ScreenShareStatus.ERROR,
            base.reduceFrameMessage(ScreenShareFrameMessage.Error("VNC server launch failed")).status,
        )
    }

    private fun jsonObject(raw: String) =
        Json.parseToJsonElement(raw).jsonObject
}
