package io.github.alextherajunction.codexweb.protocol

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexRpcClientTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun `same client reconnects after the server closes the socket`() = runBlocking {
        val server = MockWebServer()
        server.start()
        val client = CodexRpcClient()
        try {
            server.enqueue(rpcResponse(closeAfterInitialize = true))
            server.enqueue(rpcResponse(closeAfterInitialize = false))

            val closed = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(5_000) { client.closeEventFlow.first() }
            }
            val endpoint = server.url("/codex-bridge").toString().replaceFirst("http", "ws")
            client.connect(endpoint, cookieHeader = null, origin = "http://localhost")
            assertTrue(closed.await().contains("restarting"))

            val closedAgain = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(5_000) { client.closeEventFlow.first() }
            }
            client.connect(endpoint, cookieHeader = null, origin = "http://localhost")
            val response = client.request("ping")
            assertEquals("pong", response.jsonObject["value"]?.jsonPrimitive?.content)
            assertTrue(closedAgain.await().contains("done"))
        } finally {
            client.close()
            server.shutdown()
        }
    }

    @Test
    fun `connect is idempotent while the bridge remains open`() = runBlocking {
        val server = MockWebServer()
        server.start()
        val client = CodexRpcClient()
        try {
            server.enqueue(rpcResponse(closeAfterInitialize = false))
            val endpoint = server.url("/codex-bridge").toString().replaceFirst("http", "ws")

            client.connect(endpoint, cookieHeader = null, origin = "http://localhost")
            client.connect(endpoint, cookieHeader = null, origin = "http://localhost")
            val response = client.request("ping")

            assertEquals("pong", response.jsonObject["value"]?.jsonPrimitive?.content)
            assertEquals(1, server.requestCount)
        } finally {
            client.close()
            server.shutdown()
        }
    }

    @Test
    fun `concurrent connects reuse one bridge socket`() = runBlocking {
        val server = MockWebServer()
        server.start()
        val client = CodexRpcClient()
        try {
            server.enqueue(rpcResponse(closeAfterInitialize = false))
            val endpoint = server.url("/codex-bridge").toString().replaceFirst("http", "ws")

            coroutineScope {
                val first = async { client.connect(endpoint, cookieHeader = null, origin = "http://localhost") }
                val second = async { client.connect(endpoint, cookieHeader = null, origin = "http://localhost") }
                first.await()
                second.await()
            }
            val response = client.request("ping")

            assertEquals("pong", response.jsonObject["value"]?.jsonPrimitive?.content)
            assertEquals(1, server.requestCount)
        } finally {
            client.close()
            server.shutdown()
        }
    }

    private fun rpcResponse(closeAfterInitialize: Boolean): MockResponse =
        MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val message = json.parseToJsonElement(text).jsonObject
                val id = message["id"]?.jsonPrimitive?.content
                when (message["method"]?.jsonPrimitive?.content) {
                    "initialize" -> webSocket.send("""{"id":$id,"result":{}}""")
                    "initialized" -> {
                        if (closeAfterInitialize) {
                            webSocket.close(1012, "restarting")
                        }
                    }
                    "ping" -> {
                        webSocket.send("""{"id":$id,"result":{"value":"pong"}}""")
                        webSocket.close(1000, "done")
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // The client is expected to observe this failure and reconnect on the next call.
            }
        })
}
