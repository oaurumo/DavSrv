package com.oaurumo.davsrv.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.oaurumo.davsrv.R
import com.oaurumo.davsrv.data.AppSettings
import com.oaurumo.davsrv.data.ServerState
import com.oaurumo.davsrv.server.Storage
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(
    vm: AppViewModel,
    state: ServerState,
    settings: AppSettings,
    onShowQr: () -> Unit,
    onPickFolder: () -> Unit,
    onRequestStorage: () -> Unit,
    onOpenAllFilesSettings: () -> Unit,
    onRequestNotifications: () -> Unit
) {
    val context = LocalContext.current
    var resumeTick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumeTick++
        onPauseOrDispose { }
    }

    val needsStorage = remember(resumeTick, settings.useSaf) {
        !settings.useSaf && !Storage.canUsePath(context)
    }
    val needsNotification = remember(resumeTick) {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { HeroCard(state, onShowQr, onToggle = { vm.toggleServer(state.running) }, onRefreshIp = { vm.refreshIp() }) }

        if (state.error != null) {
            item { ErrorCard(state.error) }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard(stringResource(R.string.stat_requests), state.requests.toString(), Modifier.weight(1f))
                StatCard(
                    stringResource(R.string.stat_uptime),
                    if (state.running) formatUptime(context, state.uptimeMillis) else "—",
                    Modifier.weight(1f)
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard(stringResource(R.string.stat_in), humanSize(state.bytesIn), Modifier.weight(1f))
                StatCard(stringResource(R.string.stat_out), humanSize(state.bytesOut), Modifier.weight(1f))
            }
        }

        if (needsStorage || needsNotification) {
            item {
                WarningCard(
                    needsStorage = needsStorage,
                    needsNotification = needsNotification,
                    onFixStorage = { if (settings.useSaf) onPickFolder() else onRequestStorage() },
                    onFixNotification = onRequestNotifications
                )
            }
        }

        item {
            StorageCard(
                settings = settings,
                onPickFolder = onPickFolder,
                onAllFiles = onOpenAllFilesSettings,
                onSelfTest = {
                    vm.selfTest { error ->
                        Toast.makeText(
                            context,
                            error ?: context.getString(R.string.self_test_ok),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            )
        }

        item { ClientsCard() }
    }
}

@Composable
private fun HeroCard(
    state: ServerState,
    onShowQr: () -> Unit,
    onToggle: () -> Unit,
    onRefreshIp: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.running) {
        while (state.running) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val uptime = if (state.running && state.startedAt > 0) (now - state.startedAt).coerceAtLeast(0) else 0

    if (state.running) {
        val gradient = Brush.linearGradient(
            listOf(Color(0xFF00897B), Color(0xFF00695C), Color(0xFF00504A))
        )
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(26.dp))
                .background(gradient)
                .padding(20.dp)
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PulsingDot()
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.server_running),
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        formatUptime(context, uptime),
                        color = Color.White.copy(alpha = 0.75f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    state.address,
                    color = Color.White,
                    fontSize = 25.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.lan_hint),
                    color = Color.White.copy(alpha = 0.78f),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    HeroAction(stringResource(R.string.action_qr), onShowQr)
                    HeroAction(stringResource(R.string.action_copy)) {
                        clipboard.setText(AnnotatedString(state.address))
                        Toast.makeText(context, context.getString(R.string.toast_copied), Toast.LENGTH_SHORT).show()
                    }
                    HeroAction(stringResource(R.string.action_refresh_ip), onRefreshIp)
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onToggle,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White.copy(alpha = 0.16f),
                        contentColor = Color.White
                    )
                ) {
                    Text(stringResource(R.string.action_stop), fontWeight = FontWeight.SemiBold)
                }
            }
        }
    } else {
        Card(
            shape = RoundedCornerShape(26.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant) {
                        Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Filled.PlayArrow,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(stringResource(R.string.server_stopped), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.server_stopped_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(18.dp))
                Button(
                    onClick = onToggle,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.action_start), fontWeight = FontWeight.SemiBold)
                }
                state.address.takeIf { it != "—" }?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        stringResource(R.string.last_address, it),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun PulsingDot() {
    val transition = rememberInfiniteTransition(label = "pulse")
    val alpha by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )
    Box(
        Modifier
            .size(9.dp)
            .clip(CircleShape)
            .background(Color(0xFF7CF7E4).copy(alpha = alpha))
    )
}

@Composable
private fun HeroAction(label: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = Color.White.copy(alpha = 0.14f)
    ) {
        TextButton(onClick = onClick, colors = ButtonDefaults.textButtonColors(contentColor = Color.White)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            Text(value, style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun ErrorCard(message: String) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    stringResource(R.string.error_start_failed),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}

@Composable
private fun WarningCard(
    needsStorage: Boolean,
    needsNotification: Boolean,
    onFixStorage: () -> Unit,
    onFixNotification: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(Modifier.padding(16.dp)) {
            if (needsStorage) {
                Text(stringResource(R.string.warn_storage_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.warn_storage_desc),
                    style = MaterialTheme.typography.bodyMedium
                )
                TextButton(onClick = onFixStorage, contentPadding = PaddingValues(0.dp)) {
                    Text(stringResource(R.string.action_grant))
                }
            }
            if (needsNotification) {
                if (needsStorage) Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.warn_notif_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.warn_notif_desc),
                    style = MaterialTheme.typography.bodyMedium
                )
                TextButton(onClick = onFixNotification, contentPadding = PaddingValues(0.dp)) {
                    Text(stringResource(R.string.action_enable_notif))
                }
            }
        }
    }
}

@Composable
private fun StorageCard(
    settings: AppSettings,
    onPickFolder: () -> Unit,
    onAllFiles: () -> Unit,
    onSelfTest: () -> Unit
) {
    val context = LocalContext.current
    var resumeTick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumeTick++
        onPauseOrDispose { }
    }
    val access = remember(resumeTick, settings.useSaf, settings.treeUri, settings.rootPath) {
        Storage.accessState(context, settings)
    }
    val accessLabel = when (access) {
        Storage.StorageAccess.ReadWrite -> stringResource(R.string.access_rw)
        Storage.StorageAccess.ReadOnly -> stringResource(R.string.access_ro)
        Storage.StorageAccess.Unavailable -> stringResource(R.string.access_none)
    }
    val accessColor = when (access) {
        Storage.StorageAccess.ReadWrite -> Color(0xFF00695C)
        Storage.StorageAccess.ReadOnly -> Color(0xFFB26A00)
        Storage.StorageAccess.Unavailable -> MaterialTheme.colorScheme.error
    }
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.storage_card_title), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                Surface(
                    shape = RoundedCornerShape(50),
                    color = accessColor.copy(alpha = 0.12f)
                ) {
                    Text(
                        accessLabel,
                        style = MaterialTheme.typography.labelLarge,
                        color = accessColor,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                if (settings.useSaf) settings.treeUri else settings.rootPath.ifBlank { "/storage/emulated/0" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onPickFolder,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.action_pick_folder))
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !settings.useSaf) {
                    OutlinedButton(
                        onClick = onAllFiles,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.action_all_files))
                    }
                }
                OutlinedButton(
                    onClick = onSelfTest,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.action_self_test))
                }
            }
            if (access != Storage.StorageAccess.ReadWrite) {
                Spacer(Modifier.height(6.dp))
                val hint = when {
                    access == Storage.StorageAccess.Unavailable && !settings.useSaf ->
                        stringResource(R.string.hint_no_access_path)
                    access == Storage.StorageAccess.Unavailable && settings.useSaf ->
                        stringResource(R.string.hint_no_access_saf)
                    access == Storage.StorageAccess.ReadOnly && !settings.useSaf ->
                        stringResource(R.string.hint_read_only_path)
                    else ->
                        stringResource(R.string.hint_read_only_saf)
                }
                Text(
                    hint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun ClientsCard() {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.how_to_connect), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))
            ClientRow(stringResource(R.string.client_windows), stringResource(R.string.client_windows_hint))
            ClientRow(stringResource(R.string.client_macos), stringResource(R.string.client_macos_hint))
            ClientRow(stringResource(R.string.client_ios), stringResource(R.string.client_ios_hint))
            ClientRow(stringResource(R.string.client_android), stringResource(R.string.client_android_hint))
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.connect_footer),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ClientRow(name: String, hint: String) {
    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Text(
            name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(96.dp)
        )
        Text(
            hint,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
