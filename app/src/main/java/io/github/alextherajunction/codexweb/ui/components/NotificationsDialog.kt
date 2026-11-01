package io.github.alextherajunction.codexweb.ui.components

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.alextherajunction.codexweb.push.PushDistributor

@Composable
fun NotificationsDialog(
    enabled: Boolean,
    distributors: List<PushDistributor>,
    selectedDistributor: String?,
    status: String?,
    error: String?,
    onDismiss: () -> Unit,
    onSelectDistributor: (String) -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onRetry: () -> Unit,
) {
    val context = LocalContext.current
    val selected = selectedDistributor ?: distributors.firstOrNull()?.packageName
    val permissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
    val notificationsAllowed = NotificationManagerCompat.from(context).areNotificationsEnabled()
    var permissionError by remember { mutableStateOf<String?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            permissionError = null
            onEnabledChange(true)
        } else {
            permissionError = "Android notification permission was not granted."
        }
    }

    fun enable() {
        if (distributors.isEmpty()) {
            permissionError = "Install a UnifiedPush distributor such as Sunup or ntfy first."
            return
        }
        if (!notificationsAllowed) {
            permissionError = "Android notifications are disabled. Open system settings and allow notifications for Codex Web."
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !permissionGranted) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            onEnabledChange(true)
        }
    }

    fun openNotificationSettings() {
        context.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            },
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Notifications") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    "UnifiedPush delivers notifications for completed turns, achieved goals, approvals, and questions. Regular messages are not pushed.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Enable notifications", style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (!permissionGranted || !notificationsAllowed) {
                                "Android notification permission is off"
                            } else {
                                "Uses your selected UnifiedPush distributor"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = enabled,
                        onCheckedChange = { checked -> if (checked) enable() else onEnabledChange(false) },
                    )
                }
                if (enabled) {
                    Spacer(Modifier.height(10.dp))
                    TextButton(onClick = onRetry) {
                        Text("Retry registration")
                    }
                }
                if (!notificationsAllowed) {
                    TextButton(onClick = ::openNotificationSettings) {
                        Text("Open notification settings")
                    }
                }
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                Text("UnifiedPush distributor", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                if (distributors.isEmpty()) {
                    Text(
                        "No UnifiedPush distributor found. Install Sunup, ntfy, or another compatible distributor, then reopen this dialog.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    distributors.forEach { distributor ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSelectDistributor(distributor.packageName)
                                    if (enabled) enable()
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = distributor.packageName == selected,
                                onClick = {
                                    onSelectDistributor(distributor.packageName)
                                    if (enabled) enable()
                                },
                            )
                            Column(Modifier.weight(1f)) {
                                Text(distributor.label, style = MaterialTheme.typography.bodyLarge)
                                if (distributor.label != distributor.packageName) {
                                    Text(
                                        distributor.packageName,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
                val message = permissionError ?: error ?: status
                if (!message.isNullOrBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (permissionError != null || error != null) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}
