package ai.nidhoggfgg.phoneuse.ui

import ai.nidhoggfgg.phoneuse.R
import ai.nidhoggfgg.phoneuse.device.KeepAliveGuidance
import ai.nidhoggfgg.phoneuse.device.KeepAliveSettings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
internal fun KeepAliveCard(onChanged: () -> Unit) {
    val context = LocalContext.current
    val settings = remember(context) { KeepAliveSettings(context) }
    val guidance = remember(context) { KeepAliveGuidance(context) }
    var enabled by remember(settings) { mutableStateOf(settings.enabled) }
    var batteryExempt by remember(guidance) { mutableStateOf(guidance.batteryExempt) }
    var notificationsEnabled by remember(guidance) { mutableStateOf(guidance.notificationsEnabled) }
    var unavailable by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, guidance) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                enabled = settings.enabled
                batteryExempt = guidance.batteryExempt
                notificationsEnabled = guidance.notificationsEnabled
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    SectionCard {
        Row(Modifier.fillMaxWidth().toggleable(value = enabled, role = Role.Switch, onValueChange = {
            settings.enabled = it
            enabled = it
            unavailable = false
            // Deliver a new start command so START_STICKY changes immediately without disconnecting clients.
            onChanged()
        }).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.keep_alive_title), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Switch(checked = enabled, onCheckedChange = null)
        }
        Text(stringResource(R.string.keep_alive_connection_help), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(if (enabled) R.string.keep_alive_enabled_help else R.string.keep_alive_disabled_help),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (enabled) {
            Text(stringResource(if (batteryExempt) R.string.keep_alive_battery_exempt else R.string.keep_alive_battery_restricted),
                style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { unavailable = !guidance.openBatterySettings() }) {
                    Text(stringResource(R.string.keep_alive_battery_settings))
                }
                OutlinedButton(onClick = { unavailable = !guidance.openAutoStartSettings() }) {
                    Text(stringResource(R.string.keep_alive_autostart_settings))
                }
            }
            Text(stringResource(guidance.vendorHelp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!notificationsEnabled) {
                Text(stringResource(R.string.keep_alive_notifications_help), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { unavailable = !guidance.openNotificationSettings() }) {
                    Text(stringResource(R.string.keep_alive_notification_settings))
                }
            }
            Text(stringResource(R.string.keep_alive_limits), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (unavailable) Text(stringResource(R.string.keep_alive_settings_unavailable), color = MaterialTheme.colorScheme.error)
    }
}
