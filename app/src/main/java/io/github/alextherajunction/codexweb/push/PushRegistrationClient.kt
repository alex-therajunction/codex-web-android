package io.github.alextherajunction.codexweb.push

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import io.github.alextherajunction.codexweb.data.AppPreferences
import io.github.alextherajunction.codexweb.data.WebSessionGateway
import io.github.alextherajunction.codexweb.data.hasWebAuthSessionCookie
import io.github.alextherajunction.codexweb.protocol.CodexRpcClient
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import org.unifiedpush.android.connector.data.PushEndpoint
import kotlin.coroutines.resume

object PushRegistrationClient {
    private const val REGISTER_METHOD = "codexWeb/push/register"
    private const val UNREGISTER_METHOD = "codexWeb/push/unregister"
    private const val CONFIG_METHOD = "codexWeb/push/config"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()
    private val gateway = WebSessionGateway(httpClient)

    suspend fun register(context: Context, endpoint: PushEndpoint, instance: String): Result<Unit> = runCatching {
        val keys = endpoint.pubKeySet ?: throw IOException(
            "This UnifiedPush distributor did not provide Web Push encryption keys.",
        )
        val result = withBridge(context) { rpc ->
            rpc.request(REGISTER_METHOD, buildJsonObject {
                put("instance", instance)
                put("endpoint", endpoint.url)
                putJsonObject("keys") {
                    put("p256dh", keys.pubKey)
                    put("auth", keys.auth)
                }
            })
        }
        val response = result as? JsonObject
        if (response?.get("registered")?.jsonPrimitive?.contentOrNull != "true") {
            throw IOException("Codex Web did not confirm the push subscription")
        }
        val test = response["testNotification"] as? JsonObject
        if (test?.get("delivered")?.jsonPrimitive?.contentOrNull != "true") {
            val reason = test?.get("error")?.jsonPrimitive?.contentOrNull?.trim()
                ?.take(200)
                ?.takeIf { it.isNotEmpty() }
            throw IOException(
                if (reason == null) {
                    "Codex Web did not deliver a test notification"
                } else {
                    "Codex Web could not deliver a test notification: $reason"
                },
            )
        }
    }

    suspend fun unregister(
        context: Context,
        instance: String,
        cookieOverride: String? = null,
    ): Result<Unit> = runCatching {
        withBridge(context, cookieOverride) { rpc ->
            rpc.request(UNREGISTER_METHOD, buildJsonObject {
                put("instance", instance)
            })
        }
        Unit
    }

    suspend fun fetchVapidPublicKey(context: Context): Result<String> = runCatching {
        val result = withBridge(context) { rpc -> rpc.request(CONFIG_METHOD) }
        (result as? JsonObject)
            ?.get("vapidPublicKey")
            ?.jsonPrimitive
            ?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?: throw IOException("Codex Web did not return a VAPID public key")
    }

    private suspend fun <T> withBridge(
        context: Context,
        cookieOverride: String? = null,
        block: suspend (CodexRpcClient) -> T,
    ): T {
        val preferences = AppPreferences(context)
        val deploymentUrl = preferences.deploymentUrl
            ?: throw IOException("Open Codex Web and sign in before enabling notifications")
        val cookie = cookieOverride ?: cookieFor(deploymentUrl)
        if (cookie == null || !hasWebAuthSessionCookie(cookie)) {
            throw IOException("Sign in to Codex Web again to register notifications")
        }
        val session = gateway.resolve(deploymentUrl, cookie)
        val rpc = CodexRpcClient(httpClient)
        return try {
            rpc.connect(
                endpoint = session.bridgeUrl,
                cookieHeader = session.cookieHeader,
                origin = session.origin,
            )
            block(rpc)
        } finally {
            rpc.close()
        }
    }

    private suspend fun cookieFor(url: String): String? = withContext(Dispatchers.Main.immediate) {
        suspendCancellableCoroutine { continuation ->
            Handler(Looper.getMainLooper()).post {
                continuation.resume(
                    runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull(),
                )
            }
        }
    }
}
