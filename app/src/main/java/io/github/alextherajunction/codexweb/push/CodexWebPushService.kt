package io.github.alextherajunction.codexweb.push

import android.util.Log
import io.github.alextherajunction.codexweb.data.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.UnifiedPush
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

class CodexWebPushService : PushService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        val preferences = AppPreferences(this)
        if (!preferences.notificationsEnabled) {
            runCatching { UnifiedPush.unregister(this, instance) }
            return
        }
        preferences.pushStatus = "Registering with Codex Web…"
        preferences.pushError = null
        scope.launch {
            val result = PushRegistrationClient.register(this@CodexWebPushService, endpoint, instance)
            if (result.isSuccess) {
                preferences.pushStatus = "Notifications ready through ${PushDistributors.label(
                    this@CodexWebPushService,
                    PushDistributors.current(this@CodexWebPushService) ?: "the selected distributor",
                )}"
                preferences.pushError = null
            } else {
                preferences.pushStatus = null
                preferences.pushError = result.exceptionOrNull()?.message ?: "Could not register with Codex Web"
            }
        }
    }

    override fun onMessage(message: PushMessage, instance: String) {
        val preferences = AppPreferences(this)
        if (!preferences.notificationsEnabled) return
        if (!message.decrypted) {
            preferences.pushError = "The distributor delivered an unencrypted or undecryptable message."
            return
        }
        PushNotifier.show(this, PushPayloadParser.parse(message.content))
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        val preferences = AppPreferences(this)
        if (!preferences.notificationsEnabled) {
            runCatching { UnifiedPush.unregister(this, instance) }
            return
        }
        if (reason == FailedReason.VAPID_REQUIRED) {
            scope.launch {
                val config = PushRegistrationClient.fetchVapidPublicKey(this@CodexWebPushService)
                if (config.isSuccess) {
                    val key = config.getOrThrow()
                    preferences.pushVapidPublicKey = key
                    runCatching {
                        UnifiedPush.register(
                            this@CodexWebPushService,
                            instance,
                            "Codex Web notifications",
                            key,
                        )
                    }.onFailure { error ->
                        preferences.pushError = error.message ?: "VAPID registration failed"
                    }
                } else {
                    preferences.pushError = config.exceptionOrNull()?.message ?: "Could not get VAPID key"
                }
            }
        } else {
            preferences.pushStatus = null
            preferences.pushError = "UnifiedPush registration failed: ${reason.name.lowercase().replace('_', ' ')}"
            Log.w("CodexWebPush", "UnifiedPush registration failed: $reason")
        }
    }

    override fun onUnregistered(instance: String) {
        val preferences = AppPreferences(this)
        preferences.pushStatus = null
        preferences.pushError = null
        scope.launch {
            runCatching { PushRegistrationClient.unregister(this@CodexWebPushService, instance) }
        }
    }
}
