package ai.nidhoggfgg.phoneuse.ui

import ai.nidhoggfgg.phoneuse.R
import ai.nidhoggfgg.phoneuse.ui.theme.PhoneUseTheme
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

@Composable
internal fun AccessibilityPermissionCard(enabled: Boolean, onOpenSettings: () -> Unit) {
    // Use semantic colors even when the system wallpaper changes the app's dynamic palette.
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val accent = when {
        enabled && dark -> Color(0xFF8ED99E)
        enabled -> Color(0xFF146C2E)
        dark -> Color(0xFFFFCF78)
        else -> Color(0xFF7D4E00)
    }
    val container = when {
        enabled && dark -> Color(0xFF172D20)
        enabled -> Color(0xFFEDF8EE)
        dark -> Color(0xFF342814)
        else -> Color(0xFFFFF4DC)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.35f))
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Surface(shape = CircleShape, color = accent, modifier = Modifier.size(48.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painterResource(if (enabled) R.drawable.ic_check else R.drawable.ic_priority_high),
                            contentDescription = null,
                            tint = container,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.accessibility_core_permission),
                        style = MaterialTheme.typography.labelMedium, color = accent)
                    Text(stringResource(R.string.accessibility_title),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.semantics { heading() })
                }
                if (enabled) {
                    TextButton(
                        onClick = onOpenSettings,
                        colors = ButtonDefaults.textButtonColors(contentColor = accent)
                    ) { Text(stringResource(R.string.manage)) }
                }
            }
            Text(
                stringResource(if (enabled) R.string.accessibility_ready else R.string.accessibility_needed),
                style = MaterialTheme.typography.titleSmall,
                color = accent,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )
            if (!enabled) {
                Text(
                    stringResource(R.string.accessibility_enable_help),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = onOpenSettings,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = container)
                ) { Text(stringResource(R.string.enable)) }
            }
        }
    }
}

@Preview(name = "Enabled", locale = "zh", showBackground = true)
@Composable
private fun AccessibilityEnabledPreview() {
    PhoneUseTheme {
        Surface {
            Box(Modifier.padding(20.dp)) { AccessibilityPermissionCard(enabled = true, onOpenSettings = {}) }
        }
    }
}

@Preview(name = "Required", locale = "zh", showBackground = true)
@Composable
private fun AccessibilityRequiredPreview() {
    PhoneUseTheme {
        Surface {
            Box(Modifier.padding(20.dp)) { AccessibilityPermissionCard(enabled = false, onOpenSettings = {}) }
        }
    }
}
