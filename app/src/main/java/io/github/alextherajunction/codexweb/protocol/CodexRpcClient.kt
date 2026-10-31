package io.github.alextherajunction.codexweb.protocol

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import kotlin.coroutines.resume

data class RpcNotification(
    val method: String,
    val params: JsonObject?,
)

data class RpcServerRequest(
    val id: JsonElement,
    val method: String,
    val params: JsonObject,
)

class RpcException(
    message: String,
    val code: Int? = null,
) : IOException(message)

class CodexRpcClient(
    private val httpClient: OkHttpClient = OkHttpClient(),
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonElement>>()
    private val notifications = MutableSharedFlow<RpcNotification>(
        replay = 0,
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val serverRequests = MutableSharedFlow<RpcServerRequest>(
        replay = 0,
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val malformedMessages = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val closeEvents = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var opened = false

    val notificationFlow: SharedFlow<RpcNotification> = notifications.asSharedFlow()
    val serverRequestFlow: SharedFlow<RpcServerRequest> = serverRequests.asSharedFlow()
    val malformedMessageFlow: SharedFlow<String> = malformedMessages.asSharedFlow()
    val closeEventFlow: SharedFlow<String> = closeEvents.asSharedFlow()

    suspend fun connect(
        endpoint: String,
        cookieHeader: String?,
        origin: String,
    ) {
        check(!opened) { "Web bridge is already connected" }
        val builder = Request.Builder()
            .url(endpoint)
            .header("Origin", origin)
            .header("User-Agent", "CodexWebAndroid/0.1")
        if (!cookieHeader.isNullOrBlank()) {
            builder.header("Cookie", cookieHeader)
        }

        suspendCancellableCoroutine { continuation ->
            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    opened = true
                    socket = webSocket
                    if (continuation.isActive) continuation.resume(Unit)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleMessage(text)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    handleClosed("Web bridge closed ($code${if (reason.isBlank()) "" else ": $reason"})")
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val detail = t.message ?: "WebSocket failure"
                    handleClosed(detail)
                    if (continuation.isActive) {
                        continuation.resumeWith(Result.failure(RpcException(detail)))
                    }
                }
            }

            val created = httpClient.newWebSocket(builder.build(), listener)
            socket = created
            continuation.invokeOnCancellation {
                created.cancel()
                handleClosed("Connection cancelled")
            }
        }

        withTimeout(25_000) {
            request("initialize", buildJsonObject {
                put("clientInfo", buildJsonObject {
                    put("name", "codex_web_android")
                    put("title", "Codex Web Android")
                    put("version", "0.1.0")
                })
                put("capabilities", buildJsonObject {
                    put("experimentalApi", true)
                    put("requestAttestation", false)
                    put("mcpServerOpenaiFormElicitation", false)
                })
            })
        }
        notify("initialized")
    }

    suspend fun request(method: String, params: JsonObject? = null): JsonElement {
        val id = nextId.getAndIncrement()
        val deferred = CompletableDeferred<JsonElement>()
        pending[id] = deferred
        val message = buildJsonObject {
            put("id", JsonPrimitive(id))
            put("method", JsonPrimitive(method))
            if (params != null) put("params", params)
        }
        return try {
            send(message.toString())
            withTimeout(60_000) { deferred.await() }
        } finally {
            pending.remove(id)
        }
    }

    fun notify(method: String, params: JsonObject? = null) {
        val message = buildJsonObject {
            put("method", JsonPrimitive(method))
            if (params != null) put("params", params)
        }
        send(message.toString())
    }

    fun respond(requestId: JsonElement, result: JsonElement) {
        val message = buildJsonObject {
            put("id", requestId)
            put("result", result)
        }
        send(message.toString())
    }

    fun respondError(requestId: JsonElement, message: String, code: Int = -32601) {
        val payload = buildJsonObject {
            put("id", requestId)
            put("error", buildJsonObject {
                put("code", JsonPrimitive(code))
                put("message", JsonPrimitive(message))
            })
        }
        send(payload.toString())
    }

    fun close() {
        opened = false
        socket?.close(1000, "Client closed")
        socket = null
        failPending(RpcException("Web bridge disconnected"))
    }

    private fun send(text: String) {
        val active = socket
        if (!opened || active == null || !active.send(text)) {
            throw RpcException("Web bridge is not connected")
        }
    }

    private fun handleMessage(text: String) {
        val element = try {
            json.parseToJsonElement(text)
        } catch (error: Exception) {
            malformedMessages.tryEmit("Malformed app-server message: ${error.message}")
            return
        }
        val obj = element as? JsonObject ?: run {
            malformedMessages.tryEmit("Non-object app-server message")
            return
        }
        val method = obj.string("method")
        val id = obj["id"]
        val params = obj.obj("params")

        if (method != null && id != null) {
            serverRequests.tryEmit(
                RpcServerRequest(
                    id = id,
                    method = method,
                    params = params ?: JsonObject(emptyMap()),
                ),
            )
            return
        }

        if (method == null && id != null) {
            val pendingId = (id as? JsonPrimitive)?.content?.toLongOrNull()
            val deferred = pendingId?.let(pending::get)
            if (deferred == null) {
                malformedMessages.tryEmit("Response for unknown request id: $id")
                return
            }
            pending.remove(pendingId)
            val error = obj.obj("error")
            if (error != null) {
                val message = error.string("message") ?: "app-server request failed"
                val code = error.int("code")
                deferred.completeExceptionally(RpcException(message, code))
            } else {
                deferred.complete(obj["result"] ?: JsonObject(emptyMap()))
            }
            return
        }

        if (method != null) {
            notifications.tryEmit(RpcNotification(method, params))
            return
        }

        malformedMessages.tryEmit("Unrecognized app-server JSON-RPC message")
    }

    private fun handleClosed(reason: String) {
        if (!opened && pending.isEmpty()) return
        opened = false
        socket = null
        failPending(RpcException(reason))
        closeEvents.tryEmit(reason)
    }

    private fun failPending(error: RpcException) {
        val snapshot = pending.toMap()
        pending.clear()
        snapshot.values.forEach { it.completeExceptionally(error) }
    }
}
