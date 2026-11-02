package io.github.alextherajunction.codexweb.data

import java.io.IOException
import java.net.URI
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

class SessionExpiredException(message: String = "Codex Web session expired") : IOException(message)

const val WEB_AUTH_COOKIE_NAME = "codex_web_session"

fun hasWebAuthSessionCookie(cookieHeader: String?): Boolean =
    cookieHeader.orEmpty().split(';').any { cookie ->
        cookie.substringBefore('=').trim() == WEB_AUTH_COOKIE_NAME
    }

data class WebUser(
    val email: String,
    val home: String,
    val cwd: String,
)

data class DeploymentSession(
    val deploymentUrl: String,
    val bridgeUrl: String,
    val origin: String,
    val cookieHeader: String?,
    val user: WebUser,
    val homeDirectory: String,
)

class WebSessionGateway(
    private val httpClient: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun resolve(
        rawDeploymentUrl: String,
        cookieHeader: String?,
    ): DeploymentSession = withContext(Dispatchers.IO) {
        val deployment = normalizeDeploymentUrl(rawDeploymentUrl)
        val base = deployment.toString().trimEnd('/')
        val me = getJson("$base/api/auth/me", cookieHeader)
        val user = parseUser(me)

        val bridge = getJson("$base/api/codex/bridge-url", cookieHeader)
        val rawBridge = bridge.string("bridgeUrl")
            ?: throw IOException("Codex Web did not expose a bridge URL. Set CODEX_WEB_BRIDGE_URL on the server.")
        val homeDirectory = bridge.string("homeDirectory").orEmpty().ifBlank { user.home }
        DeploymentSession(
            deploymentUrl = base,
            bridgeUrl = resolveBridgeEndpoint(rawBridge, deployment),
            origin = originOf(deployment),
            cookieHeader = cookieHeader,
            user = user,
            homeDirectory = homeDirectory,
        )
    }

    suspend fun logout(deploymentUrl: String, cookieHeader: String?) = withContext(Dispatchers.IO) {
        val base = normalizeDeploymentUrl(deploymentUrl).toString().trimEnd('/')
        val request = Request.Builder()
            .url("$base/api/auth/logout")
            .header("Origin", originOf(URI.create(base).toURL()))
            .apply {
                if (!cookieHeader.isNullOrBlank()) header("Cookie", cookieHeader)
            }
            .post("{}".toRequestBody(jsonMediaType))
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Logout failed: HTTP ${response.code}")
            }
        }
    }

    suspend fun screenShareCapability(session: DeploymentSession): ScreenShareCapability =
        withContext(Dispatchers.IO) {
            val base = session.deploymentUrl.trimEnd('/')
            parseScreenShareCapability(
                getJson("$base/api/codex/screen-share", session.cookieHeader),
            )
        }

    private fun getJson(url: String, cookieHeader: String?): JsonObject {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .apply {
                if (!cookieHeader.isNullOrBlank()) header("Cookie", cookieHeader)
            }
            .get()
            .build()
        val response: Response = httpClient.newCall(request).execute()
        response.use {
            val body = it.body?.string().orEmpty()
            if (it.code == 401) {
                throw SessionExpiredException(body.ifBlank { "Codex Web login expired" })
            }
            if (!it.isSuccessful) {
                throw IOException("HTTP ${it.code} from $url${if (body.isBlank()) "" else ": ${body.take(400)}"}")
            }
            return json.parseToJsonElement(body) as? JsonObject
                ?: throw IOException("Codex Web returned a non-object JSON response from $url")
        }
    }

    private fun parseUser(json: JsonObject): WebUser {
        val user = json["user"] as? JsonObject
            ?: throw IOException("Codex Web auth response did not include user")
        return WebUser(
            email = user.string("email").orEmpty(),
            home = user.string("home").orEmpty(),
            cwd = user.string("cwd").orEmpty(),
        )
    }
}

fun normalizeDeploymentUrl(raw: String): URL {
    val value = raw.trim()
    if (value.isEmpty()) throw IOException("Enter the URL of your Codex Web deployment")
    val withScheme = if ("://" in value) value else "https://$value"
    val url = try {
        URI(withScheme).toURL()
    } catch (error: Exception) {
        throw IOException("Invalid URL: ${error.message}")
    }
    if (url.protocol != "http" && url.protocol != "https") {
        throw IOException("Only HTTP and HTTPS deployment URLs are supported")
    }
    if (url.userInfo != null) throw IOException("Deployment URLs must not contain credentials")
    if (url.query != null || url.ref != null) {
        throw IOException("Enter the deployment base URL without a query or fragment")
    }
    if (url.host.isNullOrBlank()) throw IOException("Deployment URL must include a host")
    return url
}

fun resolveBridgeEndpoint(endpoint: String, deploymentUrl: URL): String {
    if (endpoint.startsWith("ws://") || endpoint.startsWith("wss://")) return endpoint
    if (!endpoint.startsWith("/")) {
        throw IOException("Bridge URL must be an absolute ws/wss URL or same-origin path")
    }
    val scheme = if (deploymentUrl.protocol == "https") "wss" else "ws"
    val port = if (deploymentUrl.port == -1) "" else ":${deploymentUrl.port}"
    return "$scheme://${deploymentUrl.host}$port$endpoint"
}

fun originOf(url: URL): String {
    val port = if (url.port == -1) "" else ":${url.port}"
    return "${url.protocol}://${url.host}$port"
}

private fun JsonObject.string(name: String): String? =
    (this[name] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
