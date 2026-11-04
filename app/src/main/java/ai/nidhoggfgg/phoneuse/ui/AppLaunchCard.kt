package ai.nidhoggfgg.phoneuse.ui

import ai.nidhoggfgg.phoneuse.R
import ai.nidhoggfgg.phoneuse.device.AppLaunchGuidance
import ai.nidhoggfgg.phoneuse.device.BackgroundLaunchPermission
import ai.nidhoggfgg.phoneuse.device.BackgroundLaunchPermissionChecker
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
internal fun AppLaunchCard(
    unconfirmedPackage: String? = null,
    onDismiss: () -> Unit = {},
    alwaysShow: Boolean = false,
    permissionChecker: BackgroundLaunchPermissionChecker? = null
) {
    val context = LocalContext.current
    val checker = remember(context, permissionChecker) { permissionChecker ?: BackgroundLaunchPermissionChecker(context) }
    var permission by remember(checker) { mutableStateOf(checker.read()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, checker) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permission = checker.read()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // Keep observing even while hidden, so a later revocation is picked up on resume.
    if (!alwaysShow && permission != BackgroundLaunchPermission.DENIED && unconfirmedPackage == null) return

    var settingsUnavailable by remember { mutableStateOf(false) }
    SectionCard {
        Text(stringResource(R.string.background_launch_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(when (permission) {
            BackgroundLaunchPermission.ALLOWED -> R.string.background_launch_allowed
            BackgroundLaunchPermission.DENIED -> R.string.background_launch_denied
            BackgroundLaunchPermission.UNKNOWN -> R.string.background_launch_unknown
        }), style = MaterialTheme.typography.bodyMedium,
            color = if (permission == BackgroundLaunchPermission.DENIED) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant)
        if (unconfirmedPackage != null) {
            Text(stringResource(R.string.launch_unconfirmed, unconfirmedPackage), color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium)
        }
        Text(stringResource(if (AppLaunchGuidance.isXiaomi) R.string.background_launch_xiaomi_help else R.string.background_launch_help),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { settingsUnavailable = !AppLaunchGuidance.openSettings(context) }) {
                Text(stringResource(R.string.open_launch_settings))
            }
            if (unconfirmedPackage != null) TextButton(onClick = onDismiss) { Text(stringResource(R.string.dismiss_launch_warning)) }
        }
        if (settingsUnavailable) Text(stringResource(R.string.launch_settings_unavailable), color = MaterialTheme.colorScheme.error)
    }
}
