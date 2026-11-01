package io.github.alextherajunction.codexweb.protocol

import io.github.alextherajunction.codexweb.data.DeploymentSession
import io.github.alextherajunction.codexweb.data.WebUser
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalRepositoryTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun `terminal requests use app-server process methods and detected platform`() = runBlocking {
        val server = MockWebServer()
        server.start()
        val requests = Channel<RpcNotification>(Channel.UNLIMITED)
        val serverClosed = CountDownLatch(1)
        var serverSocket: WebSocket? = null
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val repository = AppServerRepository(scope)
        try {
            server.enqueue(
                MockResponse().withWebSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            serverSocket = webSocket
                        }

                        override fun onMessage(webSocket: WebSocket, text: String) {
                            val message = json.parseToJsonElement(text).jsonObject
                            val method = message["method"]?.jsonPrimitive?.content ?: return
                            val id = message["id"]?.jsonPrimitive?.content
                            val params = (message["params"] as? JsonObject) ?: JsonObject(emptyMap())
                            requests.trySend(RpcNotification(method, params))
                            if (id == null) return
                            val result = when (method) {
                                "initialize" -> """{"platformFamily":"unix"}"""
                                "model/list" -> """{"data":[]}"""
                                "account/read" -> """{"account":null,"requiresOpenaiAuth":false}"""
                                "thread/list" -> """{"data":[]}"""
                                "config/read" -> """{"config":{}}"""
                                else -> "{}"
                            }
                            webSocket.send("""{"id":$id,"result":$result}""")
                        }

                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                            webSocket.close(code, reason)
                        }

                        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                            serverClosed.countDown()
                        }

                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                            serverClosed.countDown()
                        }
                    },
                ),
            )
            val session = DeploymentSession(
                deploymentUrl = "https://codex.example",
                bridgeUrl = server.url("/bridge").toString().replaceFirst("http", "ws"),
                origin = "https://codex.example",
                cookieHeader = "codex_web_session=test",
                user = WebUser("test@example.com", "/home/test", "/home/test"),
                homeDirectory = "/home/test",
            )

            repository.connect(session, openMostRecentThread = false)
            assertEquals("unix", repository.state.value.platformFamily)

            repository.spawnTerminal("terminal-1", "/home/test/project", TerminalSize(100, 30))
            repository.writeTerminalInput("terminal-1", "ls\n".toByteArray())
            repository.resizeTerminal("terminal-1", TerminalSize(120, 40))
            repository.killTerminal("terminal-1")

            val processRequests = mutableListOf<RpcNotification>()
            withTimeout(5_000) {
                while (processRequests.size < 4) {
                    val request = requests.receive()
                    if (request.method.startsWith("process/")) processRequests += request
                }
            }

            assertEquals(
                listOf("process/spawn", "process/writeStdin", "process/resizePty", "process/kill"),
                processRequests.map { it.method },
            )
            val spawn = processRequests[0].params!!
            assertEquals("terminal-1", spawn.string("processHandle"))
            assertEquals("/home/test/project", spawn.string("cwd"))
            assertEquals(listOf("/bin/sh", "-lc", "exec \"${'$'}{SHELL:-/bin/sh}\" -i"), spawn
                .array("command")!!
                .map { it.jsonPrimitive.content })
            assertEquals(100, spawn.obj("size")!!.int("cols"))
            assertEquals(30, spawn.obj("size")!!.int("rows"))
            assertArrayEquals(
                "ls\n".toByteArray(),
                decodeTerminalOutput(processRequests[1].params!!.string("deltaBase64")!!),
            )
            assertEquals(120, processRequests[2].params!!.obj("size")!!.int("cols"))
            assertEquals(40, processRequests[2].params!!.obj("size")!!.int("rows"))
        } finally {
            serverSocket?.close(1000, "done")
            serverClosed.await(5, TimeUnit.SECONDS)
            repository.close()
            scope.cancel()
            server.shutdown()
        }
    }
}
