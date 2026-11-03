package com.oaurumo.davsrv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oaurumo.davsrv.data.ServerState

private enum class Tab(val labelRes: Int, val icon: ImageVector) {
    Home(com.oaurumo.davsrv.R.string.tab_home, Icons.Filled.Home),
    Files(com.oaurumo.davsrv.R.string.tab_files, Icons.Filled.List),
    Users(com.oaurumo.davsrv.R.string.tab_users, Icons.Filled.Person),
    Logs(com.oaurumo.davsrv.R.string.tab_logs, Icons.Filled.Info),
    Settings(com.oaurumo.davsrv.R.string.tab_settings, Icons.Filled.Settings)
}

@Composable
fun AppRoot(
    vm: AppViewModel,
    onPickFolder: () -> Unit,
    onRequestStorage: () -> Unit,
    onRequestNotifications: () -> Unit,
    onOpenAllFilesSettings: () -> Unit
) {
    var tab by remember { mutableStateOf(Tab.Home) }
    var showQr by remember { mutableStateOf(false) }

    val state by vm.server.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()

    if (showQr) {
        QrSheet(state = state, onDismiss = { showQr = false })
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("D", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            if (tab == Tab.Home) "DavSrv"
                            else stringResource(tab.labelRes),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                },
                actions = {
                    StatusPill(state)
                    Spacer(Modifier.width(12.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                Tab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item },
                        icon = {
                            Icon(
                                item.icon,
                                contentDescription = stringResource(item.labelRes),
                                modifier = Modifier.size(22.dp)
                            )
                        },
                        label = { Text(stringResource(item.labelRes)) }
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                Tab.Home -> HomeScreen(
                    vm = vm,
                    state = state,
                    settings = settings,
                    onShowQr = { showQr = true },
                    onPickFolder = onPickFolder,
                    onRequestStorage = onRequestStorage,
                    onOpenAllFilesSettings = onOpenAllFilesSettings,
                    onRequestNotifications = onRequestNotifications
                )
                Tab.Files -> FilesScreen(vm)
                Tab.Users -> UsersScreen(vm)
                Tab.Logs -> LogsScreen(vm)
                Tab.Settings -> SettingsScreen(
                    vm = vm,
                    settings = settings,
                    onPickFolder = onPickFolder,
                    onRequestStorage = onRequestStorage,
                    onOpenAllFilesSettings = onOpenAllFilesSettings
                )
            }
        }
    }
}

@Composable
private fun StatusPill(state: ServerState) {
    val color = when {
        state.error != null -> MaterialTheme.colorScheme.error
        state.running -> Color(0xFF1B9E77)
        else -> MaterialTheme.colorScheme.outline
    }
    Surface(
        shape = CircleShape,
        color = color.copy(alpha = 0.12f)
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Text(
                when {
                    state.error != null -> stringResource(com.oaurumo.davsrv.R.string.status_error)
                    state.running -> stringResource(com.oaurumo.davsrv.R.string.status_running)
                    else -> stringResource(com.oaurumo.davsrv.R.string.status_stopped)
                },
                style = MaterialTheme.typography.labelLarge,
                color = color
            )
        }
    }
}
