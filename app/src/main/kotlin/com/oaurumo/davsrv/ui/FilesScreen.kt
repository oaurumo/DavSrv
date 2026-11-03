package com.oaurumo.davsrv.ui

import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.res.stringResource
import com.oaurumo.davsrv.R
@Composable
fun FilesScreen(vm: AppViewModel) {
    val state by vm.files.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showNewFolder by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<FileItem?>(null) }
    var newFolderName by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { vm.goUp() }, enabled = state.path.isNotEmpty()) {
                Icon(Icons.Filled.KeyboardArrowLeft, contentDescription = stringResource(R.string.cd_up))
            }
            Text(
                text = if (state.path.isEmpty()) "/" else "/" + state.path.joinToString("/"),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { showNewFolder = true }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.cd_new_folder))
            }
            IconButton(onClick = { vm.refreshFiles() }) {
                Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.cd_refresh))
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(strokeWidth = 2.dp)
            }
            state.error != null -> Box(
                Modifier
                    .fillMaxSize()
                    .padding(28.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        state.error ?: "",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { vm.refreshFiles() }) { Text(stringResource(R.string.action_retry)) }
                }
            }
            state.items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.empty_folder),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            else -> LazyColumn(
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.items, key = { it.name }) { item ->
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable(enabled = item.isDirectory) { vm.openFolder(item.name) }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FileGlyph(item.isDirectory)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    item.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    if (item.isDirectory) stringResource(R.string.label_folder) else "${humanSize(item.size)} · ${formatDate(item.modified)}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { pendingDelete = item }) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = stringResource(R.string.cd_delete),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showNewFolder) {
        AlertDialog(
            onDismissRequest = { showNewFolder = false },
            title = { Text(stringResource(R.string.new_folder_title)) },
            text = {
                OutlinedTextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    label = { Text(stringResource(R.string.label_name)) },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.createFolder(newFolderName) { error ->
                        if (error != null) Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
                    }
                    newFolderName = ""
                    showNewFolder = false
                }) { Text(stringResource(R.string.action_create)) }
            },
            dismissButton = { TextButton(onClick = { showNewFolder = false }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }

    pendingDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.delete_confirm_title)) },
            text = { Text(stringResource(R.string.delete_confirm_text, item.name)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteEntry(item.name) { error ->
                        if (error != null) Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
                    }
                    pendingDelete = null
                }) { Text(stringResource(R.string.cd_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }
}

@Composable
private fun FileGlyph(isDirectory: Boolean) {
    val tint = if (isDirectory) Color(0xFFE0A11B) else MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(30.dp)) {
        val w = size.width
        val h = size.height
        if (isDirectory) {
            val path = Path().apply {
                moveTo(w * 0.08f, h * 0.34f)
                lineTo(w * 0.08f, h * 0.82f)
                lineTo(w * 0.92f, h * 0.82f)
                lineTo(w * 0.92f, h * 0.34f)
                close()
            }
            drawPath(path, tint.copy(alpha = 0.18f))
            drawPath(path, tint, style = Stroke(width = h * 0.07f))
            val tab = Path().apply {
                moveTo(w * 0.08f, h * 0.34f)
                lineTo(w * 0.08f, h * 0.2f)
                lineTo(w * 0.4f, h * 0.2f)
                lineTo(w * 0.48f, h * 0.34f)
                close()
            }
            drawPath(tab, tint.copy(alpha = 0.28f))
            drawPath(tab, tint, style = Stroke(width = h * 0.07f))
        } else {
            drawRoundRect(
                color = tint.copy(alpha = 0.14f),
                topLeft = Offset(w * 0.18f, h * 0.12f),
                size = Size(w * 0.64f, h * 0.76f),
                cornerRadius = CornerRadius(w * 0.12f)
            )
            drawRoundRect(
                color = tint,
                topLeft = Offset(w * 0.18f, h * 0.12f),
                size = Size(w * 0.64f, h * 0.76f),
                cornerRadius = CornerRadius(w * 0.12f),
                style = Stroke(width = h * 0.07f)
            )
            drawLine(
                color = tint.copy(alpha = 0.55f),
                start = Offset(w * 0.32f, h * 0.44f),
                end = Offset(w * 0.68f, h * 0.44f),
                strokeWidth = h * 0.055f
            )
            drawLine(
                color = tint.copy(alpha = 0.55f),
                start = Offset(w * 0.32f, h * 0.62f),
                end = Offset(w * 0.58f, h * 0.62f),
                strokeWidth = h * 0.055f
            )
        }
    }
}
