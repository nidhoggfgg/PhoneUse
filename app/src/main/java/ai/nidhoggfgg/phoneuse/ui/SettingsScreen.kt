package ai.nidhoggfgg.phoneuse.ui

import ai.nidhoggfgg.phoneuse.R
import ai.nidhoggfgg.phoneuse.ui.theme.ThemePreference
import ai.nidhoggfgg.phoneuse.server.ConnectionSettings
import ai.nidhoggfgg.phoneuse.server.ConnectionAddresses
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat

@Composable
internal fun SettingsScreen(onKeepAliveChanged: () -> Unit) {
    val context = LocalContext.current
    var selectedTheme by remember { mutableIntStateOf(ThemePreference.read(context)) }
    val selectedLanguage = AppCompatDelegate.getApplicationLocales()[0]?.language.orEmpty()
    val connections = remember(context) { ConnectionSettings(context) }
    var allowHttp by remember { mutableStateOf(connections.allowInsecureHttp) }
    var allowedHosts by rememberSaveable { mutableStateOf(connections.allowedHosts.joinToString("\n")) }
    var savedHosts by remember { mutableStateOf(connections.allowedHosts.joinToString("\n")) }
    var addressError by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        KeepAliveCard(onKeepAliveChanged)
        SectionCard {
            Text(stringResource(R.string.theme), style = MaterialTheme.typography.titleMedium)
            Column(Modifier.selectableGroup()) {
                listOf(
                    AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM to R.string.theme_system,
                    AppCompatDelegate.MODE_NIGHT_NO to R.string.theme_light,
                    AppCompatDelegate.MODE_NIGHT_YES to R.string.theme_dark
                ).forEach { (mode, label) ->
                    val selected = selectedTheme == mode
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = {
                            if (!selected) {
                                selectedTheme = mode
                                ThemePreference.save(context, mode)
                            }
                        }).padding(vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        RadioButton(selected = selected, onClick = null)
                        Text(stringResource(label), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
        SectionCard {
            Text(stringResource(R.string.language), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.language_help), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.selectableGroup()) {
                listOf("" to R.string.language_system, "zh" to R.string.language_chinese, "en" to R.string.language_english).forEach { (tag, label) ->
                    val selected = selectedLanguage == tag
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = {
                            if (!selected) AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
                        }).padding(vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        RadioButton(selected = selected, onClick = null)
                        Text(stringResource(label), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
        SectionCard {
            Row(Modifier.fillMaxWidth().toggleable(value = allowHttp, role = Role.Switch, onValueChange = {
                connections.allowInsecureHttp = it
                allowHttp = it
            }).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.allow_insecure_http), Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                Switch(checked = allowHttp, onCheckedChange = null)
            }
            Text(stringResource(R.string.insecure_http_help), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SectionCard {
            Text(stringResource(R.string.access_allowlist), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(value = allowedHosts, onValueChange = { allowedHosts = it; addressError = false },
                modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 6, isError = addressError,
                label = { Text(stringResource(R.string.access_allowlist_entries)) },
                supportingText = { Text(stringResource(if (addressError) R.string.access_allowlist_invalid else R.string.access_allowlist_hint)) })
            Text(stringResource(R.string.access_allowlist_help), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(enabled = allowedHosts != savedHosts, onClick = {
                runCatching { connections.allowedHosts = ConnectionAddresses.parseAllowlist(allowedHosts) }.onSuccess {
                    savedHosts = connections.allowedHosts.joinToString("\n"); allowedHosts = savedHosts; addressError = false
                }.onFailure { addressError = true }
            }) { Text(stringResource(R.string.save_allowlist)) }
        }
        AppLaunchCard(alwaysShow = true)
    }
}
