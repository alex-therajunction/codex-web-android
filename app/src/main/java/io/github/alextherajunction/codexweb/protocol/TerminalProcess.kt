package io.github.alextherajunction.codexweb.protocol

import java.util.Base64
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

data class TerminalSize(
    val columns: Int = 80,
    val rows: Int = 24,
) {
    fun normalized(): TerminalSize = copy(
        columns = columns.coerceIn(2, 500),
        rows = rows.coerceIn(1, 500),
    )
}

sealed interface TerminalProcessEvent {
    val processHandle: String

    data class Output(
        override val processHandle: String,
        val bytes: ByteArray,
    ) : TerminalProcessEvent

    data class Exited(
        override val processHandle: String,
        val exitCode: Int,
    ) : TerminalProcessEvent
}

val terminalProcessNotificationMethods = setOf(
    "process/outputDelta",
    "process/exited",
)

fun terminalShellCommand(platformFamily: String): List<String> =
    if (platformFamily.equals("windows", ignoreCase = true)) {
        listOf("powershell.exe")
    } else {
        listOf("/bin/sh", "-lc", "exec \"${'$'}{SHELL:-/bin/sh}\" -i")
    }

fun terminalEnvironment(): Map<String, String> = mapOf(
    "TERM" to "xterm-256color",
    "COLORTERM" to "truecolor",
    "CLICOLOR" to "1",
)

fun encodeTerminalInput(bytes: ByteArray): String =
    Base64.getEncoder().encodeToString(bytes)

fun decodeTerminalOutput(value: String): ByteArray? = runCatching {
    Base64.getDecoder().decode(value)
}.getOrNull()

fun parseTerminalProcessEvent(notification: RpcNotification): TerminalProcessEvent? {
    if (notification.method !in terminalProcessNotificationMethods) return null
    val params = notification.params ?: return null
    val processHandle = params.string("processHandle")?.takeIf { it.isNotBlank() } ?: return null
    return when (notification.method) {
        "process/outputDelta" -> {
            val encoded = params.string("deltaBase64") ?: return null
            val bytes = decodeTerminalOutput(encoded) ?: return null
            TerminalProcessEvent.Output(processHandle, bytes)
        }
        "process/exited" -> TerminalProcessEvent.Exited(
            processHandle = processHandle,
            exitCode = params.int("exitCode") ?: 0,
        )
        else -> null
    }
}

fun terminalSpawnParams(
    processHandle: String,
    cwd: String,
    platformFamily: String,
    size: TerminalSize,
): JsonObject {
    val normalizedSize = size.normalized()
    return buildJsonObject {
        putJsonArray("command") {
            terminalShellCommand(platformFamily).forEach { add(JsonPrimitive(it)) }
        }
        put("processHandle", processHandle)
        put("cwd", cwd)
        put("tty", true)
        put("streamStdin", true)
        put("streamStdoutStderr", true)
        put("outputBytesCap", JsonNull)
        put("timeoutMs", JsonNull)
        putJsonObject("env") {
            terminalEnvironment().forEach { (key, value) -> put(key, value) }
        }
        putJsonObject("size") {
            put("cols", normalizedSize.columns)
            put("rows", normalizedSize.rows)
        }
    }
}

fun terminalWriteParams(processHandle: String, input: ByteArray): JsonObject = buildJsonObject {
    put("processHandle", processHandle)
    put("deltaBase64", encodeTerminalInput(input))
}

fun terminalResizeParams(processHandle: String, size: TerminalSize): JsonObject {
    val normalizedSize = size.normalized()
    return buildJsonObject {
        put("processHandle", processHandle)
        putJsonObject("size") {
            put("cols", normalizedSize.columns)
            put("rows", normalizedSize.rows)
        }
    }
}

fun terminalKillParams(processHandle: String): JsonObject = buildJsonObject {
    put("processHandle", processHandle)
}
