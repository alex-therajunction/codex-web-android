package io.github.alextherajunction.codexweb.data

import java.io.IOException
import java.net.URL
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSessionTest {
    @Test
    fun `normalizes deployment urls and rejects embedded credentials`() {
        assertEquals("https://codex.example.com", normalizeDeploymentUrl("codex.example.com").toString())
        assertEquals("http://192.168.1.4:3000", normalizeDeploymentUrl("http://192.168.1.4:3000/").toString().trimEnd('/'))
        val error = runCatching { normalizeDeploymentUrl("https://user:pass@example.com") }.exceptionOrNull()
        assertTrue(error is IOException)
        assertTrue(error!!.message!!.contains("credentials"))
    }

    @Test
    fun `only the codex web session cookie starts the authenticated handoff`() {
        assertTrue(hasWebAuthSessionCookie("codex_web_session=session-cookie"))
        assertTrue(hasWebAuthSessionCookie("theme=dark; codex_web_session=session-cookie; locale=en"))
        assertFalse(hasWebAuthSessionCookie(null))
        assertFalse(hasWebAuthSessionCookie("theme=dark"))
        assertFalse(hasWebAuthSessionCookie("not_codex_web_session=value"))
        assertFalse(hasWebAuthSessionCookie("prefix_codex_web_session=value"))
    }

    @Test
    fun `resolves same-origin bridge paths for http and https`() {
        assertEquals(
            "wss://codex.example.com/codex-bridge",
            resolveBridgeEndpoint("/codex-bridge", URL("https://codex.example.com")),
        )
        assertEquals(
            "ws://localhost:3000/codex-bridge",
            resolveBridgeEndpoint("/codex-bridge", URL("http://localhost:3000")),
        )
    }

    @Test
    fun `authenticated session discovers bridge url without persisting a token`() = runTest {
        val server = MockWebServer()
        try {
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"user":{"email":"test@example.com","home":"/home/test","cwd":"/home/test/work"},"source":"web-auth.session"}""",
                ),
            )
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"bridgeUrl":"/codex-bridge","homeDirectory":"/home/test"}""",
                ),
            )
            val base = server.url("/").toString().trimEnd('/')
            val session = WebSessionGateway(OkHttpClient()).resolve(base, "codex_web_session=session-cookie")

            assertEquals("test@example.com", session.user.email)
            assertEquals("/home/test/work", session.user.cwd)
            assertEquals("ws://${server.hostName}:${server.port}/codex-bridge", session.bridgeUrl)
            assertEquals("codex_web_session=session-cookie", session.cookieHeader)
            assertTrue(server.takeRequest().getHeader("Cookie")!!.contains("codex_web_session=session-cookie"))
            assertTrue(server.takeRequest().getHeader("Cookie")!!.contains("codex_web_session=session-cookie"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `screen share capability uses the authenticated deployment`() = runTest {
        val server = MockWebServer()
        try {
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"capability":{"status":"available","display":":0","wsPath":"/screen-share/ws"}}""",
                ),
            )
            val base = server.url("/").toString().trimEnd('/')
            val session = DeploymentSession(
                deploymentUrl = base,
                bridgeUrl = "ws://${server.hostName}:${server.port}/codex-bridge?token=secret",
                origin = "http://${server.hostName}:${server.port}",
                cookieHeader = "codex_web_session=session-cookie",
                user = WebUser("test@example.com", "/home/test", "/home/test"),
                homeDirectory = "/home/test",
            )

            val capability = WebSessionGateway(OkHttpClient()).screenShareCapability(session)

            assertEquals(
                ScreenShareCapability.Available(display = ":0", wsPath = "/screen-share/ws"),
                capability,
            )
            val request = server.takeRequest()
            assertEquals("/api/codex/screen-share", request.path)
            assertEquals("codex_web_session=session-cookie", request.getHeader("Cookie"))
        } finally {
            server.shutdown()
        }
    }

    @Test(expected = SessionExpiredException::class)
    fun `expired screen share capability request is treated as a session expiry`() = runTest {
        val server = MockWebServer()
        try {
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"expired"}"""))
            val base = server.url("/").toString().trimEnd('/')
            val session = DeploymentSession(
                deploymentUrl = base,
                bridgeUrl = "ws://${server.hostName}:${server.port}/codex-bridge",
                origin = "http://${server.hostName}:${server.port}",
                cookieHeader = "codex_web_session=expired",
                user = WebUser("test@example.com", "/home/test", "/home/test"),
                homeDirectory = "/home/test",
            )
            WebSessionGateway(OkHttpClient()).screenShareCapability(session)
        } finally {
            server.shutdown()
        }
    }

    @Test(expected = SessionExpiredException::class)
    fun `expired web session is surfaced separately`() = runTest {
        val server = MockWebServer()
        try {
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"登录已失效"}"""))
            WebSessionGateway(OkHttpClient()).resolve(server.url("/").toString(), "codex_web_session=stale")
        } finally {
            server.shutdown()
        }
    }
}
