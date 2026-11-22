package com.sandboxforge.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sandboxforge.app.clone.ActiveShimTelemetry
import com.sandboxforge.app.clone.ApkCloneBuilder
import com.sandboxforge.app.clone.EngineMode
import com.sandboxforge.app.clone.ShimTelemetryStore
import com.sandboxforge.app.container.ApkInspectionReport
import com.sandboxforge.app.container.ContainerApp
import com.sandboxforge.app.container.DeviceAppInfo
import com.sandboxforge.app.container.formatBytes
import com.sandboxforge.app.ui.theme.AccentCyan
import com.sandboxforge.app.ui.theme.BorderDark
import com.sandboxforge.app.ui.theme.StatusAmber
import com.sandboxforge.app.ui.theme.StatusGreen
import com.sandboxforge.app.ui.theme.SurfaceDark
import com.sandboxforge.app.ui.theme.SurfaceElevated
import com.sandboxforge.app.ui.theme.TextMuted

@Composable
fun ContainerScreen(
    uiState: SandboxUiState,
    loadContainerIcon: (String) -> ImageBitmap?,
    loadHostIcon: (String) -> ImageBitmap?,
    onToggleHideLauncher: (Boolean) -> Unit,
    onSelectEngine: (String, EngineMode) -> Unit,
    onOpenDeviceImport: (Boolean) -> Unit,
    onSelectDeviceApp: (DeviceAppInfo) -> Unit,
    onInstallApkUri: (android.net.Uri) -> Unit,
    onLaunchOrBuildClone: (ContainerApp) -> Unit,
    onRebuildClone: (ContainerApp) -> Unit,
    onInspectApp: (ContainerApp) -> Unit,
    onDismissReport: () -> Unit,
    onOpenAppFiles: (ContainerApp) -> Unit,
    onOpenAppFolderByPackage: (String) -> Unit,
    onDeleteApp: (String) -> Unit
) {
    val activeShims by ShimTelemetryStore.activeByOriginalPkg.collectAsState()
    var showDevicePicker by remember { mutableStateOf(false) }
    var includeSystemApps by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var appPendingDelete by remember { mutableStateOf<ContainerApp?>(null) }

    val apkPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            onInstallApkUri(uri)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // Container header card
        Card(
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            border = BorderStroke(1.dp, BorderDark),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.Inventory2,
                            contentDescription = null,
                            tint = AccentCyan,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Контейнер #1 (Shim v0.2.2)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Text(
                        text = "${uiState.containerApps.size} прил.",
                        style = MaterialTheme.typography.labelMedium,
                        color = AccentCyan,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Скрывать иконку клона на рабочем столе",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = uiState.hideCloneFromLauncher,
                        onCheckedChange = onToggleHideLauncher
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Button(
                        onClick = {
                            searchQuery = ""
                            showDevicePicker = true
                            onOpenDeviceImport(includeSystemApps)
                        },
                        enabled = !uiState.isBusy,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Apps,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("С телефона", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    OutlinedButton(
                        onClick = {
                            apkPickerLauncher.launch(
                                arrayOf("application/vnd.android.package-archive", "*/*")
                            )
                        },
                        enabled = !uiState.isBusy,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.FileOpen,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Из APK", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }

        if (uiState.isBusy) {
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp)
            ) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = uiState.busyMessage ?: "Выполняется операция...",
                    style = MaterialTheme.typography.bodySmall,
                    color = AccentCyan
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (uiState.containerApps.isEmpty()) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Outlined.Inventory2,
                        contentDescription = null,
                        tint = TextMuted,
                        modifier = Modifier.size(44.dp)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "В контейнере пока пусто",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Импортируй приложение с телефона или выбери APK-файл.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted
                    )
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 16.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(uiState.containerApps, key = { it.packageName }) { app ->
                    val isInstalled = uiState.installedClones.contains(app.packageName)
                    val shimInfo = activeShims[app.packageName]
                    ContainerAppCard(
                        app = app,
                        iconBitmap = remember(app.packageName, uiState.containerApps.size) {
                            loadContainerIcon(app.packageName)
                        },
                        isCloneInstalled = isInstalled,
                        shimTelemetry = shimInfo,
                        isBusy = uiState.isBusy,
                        onSelectEngine = { mode -> onSelectEngine(app.packageName, mode) },
                        onLaunchOrBuild = { onLaunchOrBuildClone(app) },
                        onRebuild = { onRebuildClone(app) },
                        onInspect = { onInspectApp(app) },
                        onOpenFiles = { onOpenAppFiles(app) },
                        onDelete = { appPendingDelete = app }
                    )
                }
            }
        }
    }

    if (showDevicePicker) {
        DeviceAppPickerDialog(
            apps = uiState.deviceApps,
            isLoading = uiState.isLoadingDeviceApps,
            includeSystem = includeSystemApps,
            searchQuery = searchQuery,
            loadHostIcon = loadHostIcon,
            onSearchChange = { searchQuery = it },
            onToggleSystem = { checked ->
                includeSystemApps = checked
                onOpenDeviceImport(checked)
            },
            onSelectApp = { deviceApp ->
                showDevicePicker = false
                onSelectDeviceApp(deviceApp)
            },
            onDismiss = { showDevicePicker = false }
        )
    }

    uiState.inspectionReport?.let { report ->
        InspectionReportDialog(
            report = report,
            onOpenFolder = {
                onDismissReport()
                onOpenAppFolderByPackage(report.packageName)
            },
            onDismiss = onDismissReport
        )
    }

    appPendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { appPendingDelete = null },
            title = { Text("Удалить из контейнера?") },
            text = {
                Text("Будут удалены скопированные APK и папка данных приложения «${target.appName}» (${target.packageName}). Оригинал на телефоне не пострадает.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        appPendingDelete = null
                        onDeleteApp(target.packageName)
                    }
                ) {
                    Text("Удалить", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { appPendingDelete = null }) {
                    Text("Отмена")
                }
            }
        )
    }
}

@Composable
private fun AppIconBox(iconBitmap: ImageBitmap?, fallbackLetter: String, sizeDp: Int = 44) {
    val shape = RoundedCornerShape(10.dp)
    if (iconBitmap != null) {
        Image(
            bitmap = iconBitmap,
            contentDescription = null,
            modifier = Modifier
                .size(sizeDp.dp)
                .clip(shape)
        )
    } else {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(sizeDp.dp)
                .clip(shape)
                .background(SurfaceDark)
        ) {
            Text(
                text = fallbackLetter.take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                color = AccentCyan,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun ContainerAppCard(
    app: ContainerApp,
    iconBitmap: ImageBitmap?,
    isCloneInstalled: Boolean,
    shimTelemetry: ActiveShimTelemetry?,
    isBusy: Boolean,
    onSelectEngine: (EngineMode) -> Unit,
    onLaunchOrBuild: () -> Unit,
    onRebuild: () -> Unit,
    onInspect: () -> Unit,
    onOpenFiles: () -> Unit,
    onDelete: () -> Unit
) {
    val needsShimUpdate = isCloneInstalled && app.shimVersion != ApkCloneBuilder.CURRENT_SHIM_VERSION

    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceElevated),
        border = BorderStroke(1.dp, if (isCloneInstalled) AccentCyan else BorderDark),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                AppIconBox(iconBitmap = iconBitmap, fallbackLetter = app.appName)
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = app.appName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = app.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (isCloneInstalled) {
                    IconButton(onClick = onRebuild, enabled = !isBusy) {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = "Пересобрать клон",
                            tint = if (needsShimUpdate) StatusAmber else AccentCyan
                        )
                    }
                }
                IconButton(onClick = onInspect) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = "Инспекция",
                        tint = TextMuted
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = "Удалить",
                        tint = TextMuted
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "v${app.versionName} • ${formatBytes(app.totalApkBytes)} • " +
                    "APK: ${app.apkFileNames.size}" +
                    (if (app.splitCount > 0) " (сплитов: ${app.splitCount})" else "") +
                    " • ABI: ${app.nativeAbis.ifEmpty { listOf("pure-dex") }.joinToString(",")}",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted
            )

            // Engine auto-advisor + manual override chips
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = app.selectedEngine == EngineMode.MOD_SHIM,
                    onClick = { onSelectEngine(EngineMode.MOD_SHIM) },
                    label = {
                        Text(
                            text = if (app.recommendedEngine == EngineMode.MOD_SHIM) "SHIM (рек.)" else "SHIM",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                )
                FilterChip(
                    selected = app.selectedEngine == EngineMode.CLEAN_PROFILE,
                    onClick = { onSelectEngine(EngineMode.CLEAN_PROFILE) },
                    label = {
                        Text(
                            text = if (app.recommendedEngine == EngineMode.CLEAN_PROFILE) "Чистый (рек.)" else "Чистый",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                )
            }

            if (shimTelemetry != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Shim v${shimTelemetry.shimVersion.ifBlank { "0.2" }} OK • PID ${shimTelemetry.pid} • " +
                        (if (shimTelemetry.is64Bit) "64-bit" else "32-bit (Tango/ARM32)") +
                        if (shimTelemetry.bridgeReady) " • Мост OK" else "",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = StatusGreen
                )
                if (shimTelemetry.shieldStatus.isNotBlank()) {
                    Text(
                        text = "Щит: ${shimTelemetry.shieldStatus}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            } else if (isCloneInstalled && app.clonePackageName != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (needsShimUpdate) {
                        "Клон v${app.shimVersion ?: "0.2.0"} • нажми ↻ для моста файлов и щита"
                    } else {
                        "Клон установлен: ${app.clonePackageName}"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = if (needsShimUpdate) StatusAmber else AccentCyan
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onLaunchOrBuild,
                    enabled = !isBusy,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = if (isCloneInstalled) Icons.Outlined.PlayArrow else Icons.Outlined.Build,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (isCloneInstalled) "Запустить клон" else "Собрать клон")
                }
                OutlinedButton(
                    onClick = onOpenFiles,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Folder,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(if (isCloneInstalled) "Файлы клона" else "Файлы")
                }
            }
        }
    }
}

@Composable
private fun DeviceAppPickerDialog(
    apps: List<DeviceAppInfo>,
    isLoading: Boolean,
    includeSystem: Boolean,
    searchQuery: String,
    loadHostIcon: (String) -> ImageBitmap?,
    onSearchChange: (String) -> Unit,
    onToggleSystem: (Boolean) -> Unit,
    onSelectApp: (DeviceAppInfo) -> Unit,
    onDismiss: () -> Unit
) {
    val filtered = remember(apps, searchQuery) {
        val q = searchQuery.trim().lowercase()
        if (q.isEmpty()) apps
        else apps.filter {
            it.appName.lowercase().contains(q) || it.packageName.lowercase().contains(q)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Импорт с телефона") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchChange,
                    singleLine = true,
                    placeholder = { Text("Поиск по имени или пакету") },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Показать системные", style = MaterialTheme.typography.bodySmall)
                    Switch(checked = includeSystem, onCheckedChange = onToggleSystem)
                }
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = BorderDark)
                Spacer(modifier = Modifier.height(8.dp))

                if (isLoading) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                    ) {
                        CircularProgressIndicator()
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(320.dp)
                    ) {
                        items(filtered, key = { it.packageName }) { item ->
                            val hostIcon = remember(item.packageName) {
                                loadHostIcon(item.packageName)
                            }
                            Card(
                                onClick = { onSelectApp(item) },
                                colors = CardDefaults.cardColors(containerColor = SurfaceElevated),
                                border = BorderStroke(1.dp, BorderDark),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(10.dp)
                                ) {
                                    AppIconBox(
                                        iconBitmap = hostIcon,
                                        fallbackLetter = item.appName,
                                        sizeDp = 36
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = item.appName,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = item.packageName,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontFamily = FontFamily.Monospace,
                                            color = TextMuted,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (item.splitCount > 0) {
                                            Text(
                                                text = "Split APK: base + ${item.splitCount}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = AccentCyan
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Закрыть")
            }
        }
    )
}

@Composable
private fun InspectionReportDialog(
    report: ApkInspectionReport,
    onOpenFolder: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Инспекция контейнера") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(report.appName, fontWeight = FontWeight.SemiBold)
                Text(
                    text = report.packageName,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted
                )
                HorizontalDivider(color = BorderDark)
                Text("Версия: ${report.versionLabel}", style = MaterialTheme.typography.bodySmall)
                Text(
                    "Файлов APK: ${report.apkFilesCount} (${formatBytes(report.totalApkBytes)})",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "Нативные ABI: ${report.nativeAbis.ifEmpty { listOf("Только DEX/Java") }.joinToString(", ")}",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = if (report.readOnlyEnforced) "Защита Android 14+ (read-only): OK" else "Защита read-only: НЕ применена",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (report.readOnlyEnforced) StatusGreen else StatusAmber
                )
                Text(
                    text = "Рекомендация: ${report.recommendedEngine.displayName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = AccentCyan
                )
                Text(
                    text = report.engineReason,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted
                )
                Text(
                    text = if (report.isCloneInstalled) {
                        "Клон установлен: ${report.clonePackageName}"
                    } else {
                        "Клон ещё не установлен"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (report.isCloneInstalled) StatusGreen else StatusAmber
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onOpenFolder) {
                Text("Открыть файлы")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Закрыть")
            }
        }
    )
}
