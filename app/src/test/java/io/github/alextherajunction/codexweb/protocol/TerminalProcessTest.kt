package io.github.alextherajunction.codexweb.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalProcessTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `unix terminal spawn requests an interactive pty in the project directory`() {
        val params = terminalSpawnParams(
            processHandle = "terminal-1",
            cwd = "/home/test/project",
            platformFamily = "unix",
            size = TerminalSize(columns = 132, rows = 40),
        )

        assertEquals("terminal-1", params.string("processHandle"))
        assertEquals("/home/test/project", params.string("cwd"))
        assertEquals(true, params.boolean("tty"))
        assertEquals(true, params.boolean("streamStdin"))
        assertEquals(true, params.boolean("streamStdoutStderr"))
        assertEquals(
            listOf("/bin/sh", "-lc", "exec \"${'$'}{SHELL:-/bin/sh}\" -i"),
            params.array("command")!!.map { it.jsonPrimitive.content },
        )
        assertEquals("xterm-256color", params.obj("env")!!.string("TERM"))
        assertEquals(132, params.obj("size")!!.int("cols"))
        assertEquals(40, params.obj("size")!!.int("rows"))
    }

    @Test
    fun `windows terminal spawn uses powershell`() {
        val params = terminalSpawnParams("terminal-win", "C:\\work", "windows", TerminalSize())

        assertEquals(
            listOf("powershell.exe"),
            params.array("command")!!.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `input is base64 encoded without losing bytes`() {
        val input = byteArrayOf(
            0x03,
            0x1b,
            '['.code.toByte(),
            'A'.code.toByte(),
            '\n'.code.toByte(),
        )
        val params = terminalWriteParams("terminal-1", input)

        assertArrayEquals(input, decodeTerminalOutput(params.string("deltaBase64")!!))
    }

    @Test
    fun `output deltas and exits are routed by process handle`() {
        val output = parseTerminalProcessEvent(
            RpcNotification(
                "process/outputDelta",
                json.parseToJsonElement(
                    """{"processHandle":"terminal-1","stream":"stdout","deltaBase64":"aGVsbG8=","capReached":false}""",
                ).jsonObject,
            ),
        ) as TerminalProcessEvent.Output
        val exited = parseTerminalProcessEvent(
            RpcNotification(
                "process/exited",
                json.parseToJsonElement("""{"processHandle":"terminal-1","exitCode":7}""").jsonObject,
            ),
        ) as TerminalProcessEvent.Exited

        assertEquals("terminal-1", output.processHandle)
        assertArrayEquals("hello".toByteArray(), output.bytes)
        assertEquals(7, exited.exitCode)
    }

    @Test
    fun `malformed terminal notifications do not produce events`() {
        assertNull(
            parseTerminalProcessEvent(
                RpcNotification(
                    "process/outputDelta",
                    JsonObject(mapOf("processHandle" to kotlinx.serialization.json.JsonPrimitive("terminal-1"))),
                ),
            ),
        )
        assertNull(
            parseTerminalProcessEvent(
                RpcNotification("process/exited", JsonObject(emptyMap())),
            ),
        )
    }

    @Test
    fun `terminal notifications are known app-server events`() {
        assertTrue("process/outputDelta" in terminalProcessNotificationMethods)
        assertTrue("process/exited" in terminalProcessNotificationMethods)
    }
}
