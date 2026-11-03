package com.oaurumo.davsrv.ui

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oaurumo.davsrv.BuildConfig
import com.oaurumo.davsrv.LocaleHelper
import com.oaurumo.davsrv.R
import com.oaurumo.davsrv.data.AppSettings
import com.oaurumo.davsrv.server.Storage

@Composable
fun SettingsScreen(
    vm: AppViewModel,
    settings: AppSettings,
    onPickFolder: () -> Unit,
    onRequestStorage: () -> Unit,
    onOpenAllFilesSettings: () -> Unit
) {
    val context = LocalContext.current
    var portText by remember(settings.port) { mutableStateOf(settings.port.toString()) }
    var pathText by remember(settings.rootPath) { mutableStateOf(settings.rootPath) }
    var portError by remember { mutableStateOf<String?>(null) }
    var showLanguage by remember { mutableStateOf(false) }
    val currentTag = LocaleHelper.preference(context)
    val systemName = LocaleHelper.systemDisplayName()
    val currentLabel = if (currentTag == LocaleHelper.SYSTEM) {
        stringResource(R.string.language_system_value, systemName)
    } else {
        LocaleHelper.languages.firstOrNull { it.first == currentTag }?.second ?: "English"
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            SectionCard(stringResource(R.string.section_server)) {
                OutlinedTextField(
                    value = portText,
                    onValueChange = { portText = it.filter(Char::isDigit); portError = null },
                    label = { Text(stringResource(R.string.label_port)) },
                    singleLine = true,
                    isError = portError != null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                if (portError != null) {
                    Text(
                        portError!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            val port = portText.toIntOrNull()
                            if (port == null || port !in 1024..65535) {
                                portError = context.getString(R.string.err_port_range)
                            } else {
                                vm.savePort(port)
                                portError = null
                            }
                        },
                        shape = RoundedCornerShape(12.dp)
                    ) { Text(stringResource(R.string.action_save_port)) }
                    Text(
                        stringResource(R.string.port_restart_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterVertically)
                    )
                }
            }
        }

        item {
            SectionCard(stringResource(R.string.section_storage)) {
                Text(
                    stringResource(R.string.current_shared_folder),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    vm.rootLabel(),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = onPickFolder, shape = RoundedCornerShape(12.dp)) {
                        Text(stringResource(R.string.action_pick_folder))
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        OutlinedButton(onClick = onOpenAllFilesSettings, shape = RoundedCornerShape(12.dp)) {
                            Text(stringResource(R.string.action_all_files))
                        }
                    } else {
                        OutlinedButton(onClick = onRequestStorage, shape = RoundedCornerShape(12.dp)) {
                            Text(stringResource(R.string.action_request_storage))
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.path_mode_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = pathText,
                    onValueChange = { pathText = it },
                    label = { Text(stringResource(R.string.label_shared_path)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = { vm.usePathRoot(pathText) },
                        shape = RoundedCornerShape(12.dp)
                    ) { Text(stringResource(R.string.action_use_path)) }
                    if (!settings.useSaf && !Storage.canUsePath(context)) {
                        Text(
                            stringResource(R.string.no_permission),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.align(Alignment.CenterVertically)
                        )
                    }
                }
            }
        }

        item {
            SectionCard(stringResource(R.string.section_runtime)) {
                SettingRow(
                    title = stringResource(R.string.pref_boot_title),
                    subtitle = stringResource(R.string.pref_boot_desc),
                    checked = settings.bootStart,
                    onChange = { vm.setBootStart(it) }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingRow(
                    title = stringResource(R.string.pref_wifi_title),
                    subtitle = stringResource(R.string.pref_wifi_desc),
                    checked = settings.wifiLock,
                    onChange = { vm.setWifiLock(it) }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingRow(
                    title = stringResource(R.string.pref_hidden_title),
                    subtitle = stringResource(R.string.pref_hidden_desc),
                    checked = settings.showHidden,
                    onChange = { vm.setShowHidden(it) }
                )
            }
        }

        item {
            SectionCard(stringResource(R.string.section_language)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { showLanguage = true }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.pref_language_title),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                    Text(
                        currentLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        item {
            SectionCard(stringResource(R.string.section_about)) {
                AboutRow(stringResource(R.string.about_name), "DavSrv")
                AboutRow(stringResource(R.string.about_version), BuildConfig.VERSION_NAME)
                AboutRow(stringResource(R.string.about_package), BuildConfig.APPLICATION_ID)
                AboutRow(stringResource(R.string.about_protocol), "WebDAV (RFC 4918) · HTTP Basic")
                AboutRow(stringResource(R.string.about_support), "Android 6.0 – 17 (API 23 – 37)")
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.about_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            Text(
                stringResource(R.string.default_account_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
            )
        }
    }

    if (showLanguage) {
        val systemLabel = stringResource(R.string.language_system_value, systemName)
        val pick: (String) -> Unit = { tag ->
            showLanguage = false
            if (tag != currentTag) {
                LocaleHelper.apply(context, tag)
                (context as? Activity)?.recreate()
            }
        }
        AlertDialog(
            onDismissRequest = { showLanguage = false },
            title = { Text(stringResource(R.string.pref_language_title)) },
            text = {
                // 16 languages do not fit on a short screen, so the list scrolls.
                Column(
                    Modifier
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    LanguageRow(systemLabel, currentTag == LocaleHelper.SYSTEM) {
                        pick(LocaleHelper.SYSTEM)
                    }
                    LocaleHelper.languages.forEach { (tag, label) ->
                        LanguageRow(label, tag == currentTag) { pick(tag) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showLanguage = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

}

@Composable
private fun LanguageRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun SettingRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(10.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun AboutRow(label: String, value: String) {
    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(84.dp)
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
