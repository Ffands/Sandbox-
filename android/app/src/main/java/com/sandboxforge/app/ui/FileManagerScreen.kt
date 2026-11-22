package com.sandboxforge.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sandboxforge.app.container.ContainerFileItem
import com.sandboxforge.app.container.formatBytes
import com.sandboxforge.app.ui.theme.AccentCyan
import com.sandboxforge.app.ui.theme.BorderDark
import com.sandboxforge.app.ui.theme.StatusAmber
import com.sandboxforge.app.ui.theme.SurfaceDark
import com.sandboxforge.app.ui.theme.SurfaceElevated
import com.sandboxforge.app.ui.theme.TextMuted
import java.io.File

@Composable
fun FileManagerScreen(
    uiState: SandboxUiState,
    onNavigateInto: (File) -> Unit,
    onNavigateUp: () -> Unit,
    onRefresh: () -> Unit,
    onCreateFolder: (String) -> Unit,
    onOpenFilePreview: (File) -> Unit,
    onDismissPreview: () -> Unit,
    onRequestExportFile: (File) -> Unit,
    onCompleteExportFile: (Uri?) -> Unit,
    onImportFileIntoDir: (Uri) -> Unit,
    onDeleteItem: (ContainerFileItem) -> Unit
) {
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    var itemPendingDelete by remember { mutableStateOf<ContainerFileItem?>(null) }

    val importFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            onImportFileIntoDir(uri)
        }
    }

    val exportFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        onCompleteExportFile(uri)
    }

    LaunchedEffect(uiState.pendingExportFile) {
        val file = uiState.pendingExportFile
        if (file != null) {
            exportFileLauncher.launch(file.name)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            border = BorderStroke(1.dp, BorderDark),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    IconButton(
                        onClick = onNavigateUp,
                        enabled = uiState.currentRelativePath != "/"
                    ) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "Вверх")
                    }
                    Text(
                        text = uiState.currentRelativePath,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = AccentCyan,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { showCreateFolderDialog = true }) {
                        Icon(Icons.Outlined.CreateNewFolder, contentDescription = "Новая папка")
                    }
                    IconButton(onClick = { importFileLauncher.launch(arrayOf("*/*")) }) {
                        Icon(Icons.Outlined.UploadFile, contentDescription = "Импорт файла")
                    }
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "Обновить")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (uiState.fileItems.isEmpty()) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                Text(
                    text = "Папка пуста",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMuted
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 16.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(uiState.fileItems, key = { it.file.absolutePath }) { item ->
                    Card(
                        onClick = {
                            if (item.isDirectory) {
                                onNavigateInto(item.file)
                            } else {
                                onOpenFilePreview(item.file)
                            }
                        },
                        colors = CardDefaults.cardColors(containerColor = SurfaceElevated),
                        border = BorderStroke(1.dp, BorderDark),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                        ) {
                            Icon(
                                imageVector = if (item.isDirectory) Icons.Outlined.Folder else Icons.Outlined.InsertDriveFile,
                                contentDescription = null,
                                tint = if (item.isDirectory) AccentCyan else TextMuted,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        text = if (item.isDirectory) "Элементов: ${item.sizeBytes}" else formatBytes(item.sizeBytes),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextMuted
                                    )
                                    if (item.isReadOnly) {
                                        Text(
                                            text = "RO",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontFamily = FontFamily.Monospace,
                                            color = StatusAmber
                                        )
                                    }
                                }
                            }
                            if (!item.isDirectory) {
                                IconButton(onClick = { onRequestExportFile(item.file) }) {
                                    Icon(
                                        imageVector = Icons.Outlined.Download,
                                        contentDescription = "Экспорт файла",
                                        tint = AccentCyan
                                    )
                                }
                            }
                            IconButton(onClick = { itemPendingDelete = item }) {
                                Icon(
                                    imageVector = Icons.Outlined.Delete,
                                    contentDescription = "Удалить",
                                    tint = TextMuted
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreateFolderDialog) {
        AlertDialog(
            onDismissRequest = { showCreateFolderDialog = false },
            title = { Text("Новая папка") },
            text = {
                OutlinedTextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    singleLine = true,
                    placeholder = { Text("Например: saves") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = newFolderName.trim()
                        showCreateFolderDialog = false
                        newFolderName = ""
                        if (name.isNotEmpty()) onCreateFolder(name)
                    }
                ) {
                    Text("Создать")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateFolderDialog = false }) {
                    Text("Отмена")
                }
            }
        )
    }

    uiState.filePreview?.let { preview ->
        AlertDialog(
            onDismissRequest = onDismissPreview,
            title = {
                Column {
                    Text(preview.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        text = preview.relativePath + if (preview.truncated) " (показаны первые 128 КБ)" else "",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = TextMuted
                    )
                }
            },
            text = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(320.dp)
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                ) {
                    Text(
                        text = preview.content.ifEmpty { "(пустой файл)" },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = onDismissPreview) {
                    Text("Закрыть")
                }
            }
        )
    }

    itemPendingDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { itemPendingDelete = null },
            title = { Text("Удалить ${if (item.isDirectory) "папку" else "файл"}?") },
            text = { Text("Объект «${item.name}» будет удалён из контейнера без возможности восстановления.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        itemPendingDelete = null
                        onDeleteItem(item)
                    }
                ) {
                    Text("Удалить", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { itemPendingDelete = null }) {
                    Text("Отмена")
                }
            }
        )
    }
}
