package io.github.alextherajunction.codexweb.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.alextherajunction.codexweb.MainActivity
import io.github.alextherajunction.codexweb.R

object PushNotifier {
    const val EXTRA_THREAD_ID = "codex_web_push_thread_id"
    private const val CHANNEL_ID = "codex_web_events"

    fun show(context: Context, payload: PushPayload?) {
        if (payload == null) return
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        createChannel(context)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            payload.threadId?.let { putExtra(EXTRA_THREAD_ID, it) }
        }
        val notificationId = notificationId(payload)
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(payload.title)
            .setContentText(payload.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(payload.body))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setGroup("codex-web")
            .apply {
                if (payload.createdAt > 0) setWhen(payload.createdAt)
            }
            .build()
        NotificationManagerCompat.from(context).notify(notificationId, notification)
    }

    private fun notificationId(payload: PushPayload): Int {
        val identity = payload.requestId
            ?: payload.turnId
            ?: payload.threadId
            ?: payload.kind
        return (payload.kind.hashCode() * 31 + identity.hashCode()) and 0x7fffffff
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Codex events",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Turn and goal completions, approvals, and questions from Codex"
        }
        manager.createNotificationChannel(channel)
    }
}
