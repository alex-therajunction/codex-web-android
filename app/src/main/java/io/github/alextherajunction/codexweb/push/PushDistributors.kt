package io.github.alextherajunction.codexweb.push

import android.content.Context
import io.github.alextherajunction.codexweb.data.AppPreferences
import java.util.UUID
import org.unifiedpush.android.connector.UnifiedPush

data class PushDistributor(
    val packageName: String,
    val label: String,
)

object PushDistributors {
    fun available(context: Context): List<PushDistributor> =
        runCatching { UnifiedPush.getDistributors(context) }
            .getOrDefault(emptyList())
            .map { packageName ->
                PushDistributor(packageName, label(context, packageName))
            }
            .sortedBy { it.label.lowercase() }

    fun current(context: Context): String? =
        runCatching { UnifiedPush.getAckDistributor(context) }.getOrNull()

    fun label(context: Context, packageName: String): String = runCatching {
        val info = context.packageManager.getApplicationInfo(packageName, 0)
        context.packageManager.getApplicationLabel(info).toString()
    }.getOrDefault(packageName)

    fun save(context: Context, packageName: String) {
        UnifiedPush.saveDistributor(context, packageName)
    }

    @Synchronized
    fun ensureInstance(context: Context): String {
        val preferences = AppPreferences(context)
        return preferences.pushInstance?.takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString().also { preferences.pushInstance = it }
    }

    fun register(context: Context, instance: String, vapidPublicKey: String? = null) {
        UnifiedPush.register(
            context,
            instance,
            "Codex Web notifications",
            vapidPublicKey?.takeIf { it.isNotBlank() },
        )
    }

    fun unregister(context: Context, instance: String) {
        UnifiedPush.unregister(context, instance)
    }
}
