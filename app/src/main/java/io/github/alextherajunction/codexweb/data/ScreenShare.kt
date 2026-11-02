package io.github.alextherajunction.codexweb.data

import java.io.IOException
import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

sealed interface ScreenShareCapability {
    data object Disabled : ScreenShareCapability

    data class Unsupported(val reason: String) : ScreenShareCapability

    data class Available(
        val display: String,
        val wsPath: String,
    ) : ScreenShareCapability
}

enum class ScreenShareFrameState {
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
}

sealed interface ScreenShareFrameMessage {
    data object Ready : ScreenShareFrameMessage

    data class Status(
        val state: ScreenShareFrameState,
        val width: Int? = null,
        val height: Int? = null,
        val code: Int? = null,
        val reason: String? = null,
    ) : ScreenShareFrameMessage

    data class Error(val message: String) : ScreenShareFrameMessage
}

private val screenShareJson = Json { ignoreUnknownKeys = true; isLenient = true }

internal fun parseScreenShareCapability(payload: JsonObject): ScreenShareCapability {
    val value = payload["capability"] as? JsonObject
        ?: throw IOException("Screen share response did not include a capability")
    return when (value.string("status")) {
        "disabled" -> ScreenShareCapability.Disabled
        "unsupported" -> ScreenShareCapability.Unsupported(
            value.string("reason")?.takeIf { it.isNotBlank() }
                ?: "Screen share is unsupported on this system",
        )
        "available" -> ScreenShareCapability.Available(
            display = value.string("display").orEmpty(),
            wsPath = value.string("wsPath")?.takeIf(::isValidScreenSharePath)
                ?: throw IOException("Screen share response did not include a valid WebSocket path"),
        )
        else -> throw IOException("Screen share response contained an unknown capability status")
    }
}

fun parseScreenShareFrameMessage(raw: String): ScreenShareFrameMessage? {
    val value = runCatching {
        screenShareJson.parseToJsonElement(raw) as? JsonObject
    }.getOrNull() ?: return null
    return when (value.string("type")) {
        "screen-frame/ready" -> ScreenShareFrameMessage.Ready
        "screen-frame/error" -> {
            val message = value.string("message")?.takeIf { it.isNotBlank() } ?: return null
            ScreenShareFrameMessage.Error(message)
        }
        "screen-frame/status" -> {
            val state = when (value.string("state")) {
                "connecting" -> ScreenShareFrameState.CONNECTING
                "connected" -> ScreenShareFrameState.CONNECTED
                "disconnected" -> ScreenShareFrameState.DISCONNECTED
                else -> return null
            }
            if (value.hasInvalidPositiveInt("width") || value.hasInvalidPositiveInt("height")) {
                return null
            }
            val width = value.positiveInt("width")
            val height = value.positiveInt("height")
            val codeElement = value["code"]
            if (codeElement != null && (codeElement as? JsonPrimitive)?.intOrNull == null) return null
            val code = (codeElement as? JsonPrimitive)?.intOrNull
            ScreenShareFrameMessage.Status(
                state = state,
                width = width,
                height = height,
                code = code,
                reason = value.string("reason")?.takeIf { it.isNotBlank() },
            )
        }
        else -> null
    }
}

fun buildScreenShareWebSocketUrl(
    bridgeUrl: String,
    wsPath: String,
    takeover: Boolean = false,
): String {
    if (!isValidScreenSharePath(wsPath)) {
        throw IOException("Screen share WebSocket path must be a same-origin absolute path")
    }
    val bridge = try {
        URI(bridgeUrl)
    } catch (error: Exception) {
        throw IOException("Invalid bridge URL: ${error.message}")
    }
    if (bridge.scheme != "ws" && bridge.scheme != "wss") {
        throw IOException("Bridge URL must use ws or wss")
    }
    if (bridge.rawUserInfo != null || bridge.rawAuthority.isNullOrBlank()) {
        throw IOException("Bridge URL must not contain credentials")
    }
    val query = bridge.rawQuery.orEmpty()
        .split('&')
        .filter { it.isNotBlank() && it.substringBefore('=') != "takeover" }
        .toMutableList()
    if (takeover) query += "takeover=1"
    val suffix = if (query.isEmpty()) "" else "?${query.joinToString("&")}"
    return "${bridge.scheme}://${bridge.rawAuthority}$wsPath$suffix"
}

private fun isValidScreenSharePath(path: String): Boolean =
    path.startsWith("/") &&
        !path.startsWith("//") &&
        path.none { it == '?' || it == '#' || it == '\\' }

private fun JsonObject.string(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.positiveInt(name: String): Int? =
    (this[name] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }

private fun JsonObject.hasInvalidPositiveInt(name: String): Boolean {
    val element = this[name] ?: return false
    return (element as? JsonPrimitive)?.intOrNull?.let { it > 0 } != true
}
