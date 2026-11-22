package com.sandboxforge.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import com.sandboxforge.app.logging.AppLogger
import com.sandboxforge.app.logging.LogLevel
import com.sandboxforge.app.ui.ContainerScreen
import com.sandboxforge.app.ui.FileManagerScreen
import com.sandboxforge.app.ui.LogScreen
import com.sandboxforge.app.ui.SandboxViewModel
import com.sandboxforge.app.ui.UiActionEffect
import com.sandboxforge.app.ui.theme.BgDark
import com.sandboxforge.app.ui.theme.SandboxForgeTheme
import com.sandboxforge.app.ui.theme.SurfaceDark
import com.sandboxforge.app.ui.theme.SurfaceElevated
import com.sandboxforge.app.ui.theme.TextPrimary
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: SandboxViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLogger.d("MainActivity", "onCreate")
        setContent {
            SandboxForgeTheme {
                SandboxRootScreen(viewModel = viewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshContainerApps()
        if (viewModel.uiState.value.activeCloneBridge != null) {
            viewModel.refreshCurrentDirectory()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SandboxRootScreen(viewModel: SandboxViewModel) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val logEntries by AppLogger.entries.collectAsState()
    val crashSummary by AppLogger.lastCrashSummary.collectAsState()

    var selectedTab by remember { mutableIntStateOf(0) }
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    val errorCount = remember(logEntries, crashSummary) {
        logEntries.count { it.level == LogLevel.ERROR || it.level == LogLevel.CRASH } +
            if (!crashSummary.isNullOrBlank()) 1 else 0
    }

    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is UiActionEffect.LaunchInstallConfirmation -> {
                    runCatching {
                        context.startActivity(effect.intent)
                    }.onFailure { err ->
                        AppLogger.e("MainActivity", "Не удалось открыть диалог установки", err)
                    }
                }

                UiActionEffect.RequestUnknownSourcesPermission -> {
                    val settingsIntent = Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}")
                    )
                    runCatching {
                        context.startActivity(settingsIntent)
                    }.onFailure { err ->
                        AppLogger.e("MainActivity", "Не удалось открыть настройки установки APK", err)
                    }
                }

                is UiActionEffect.RequestUninstallPackage -> {
                    val uninstallIntent = Intent(
                        Intent.ACTION_DELETE,
                        Uri.parse("package:${effect.packageName}")
                    )
                    runCatching {
                        context.startActivity(uninstallIntent)
                    }.onFailure { err ->
                        AppLogger.e("MainActivity", "Не удалось открыть диалог удаления ${effect.packageName}", err)
                    }
                }
            }
        }
    }

    LaunchedEffect(uiState.statusBanner) {
        val msg = uiState.statusBanner
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearStatusBanner()
        }
    }

    Scaffold(
        containerColor = BgDark,
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = SurfaceElevated,
                    contentColor = TextPrimary
                )
            }
        },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = when (selectedTab) {
                            0 -> "SandboxForge — Контейнер"
                            1 -> if (uiState.activeCloneBridge != null) "Живые файлы клона" else "Файлы контейнера"
                            else -> "Логи и краши"
                        },
                        fontWeight = FontWeight.SemiBold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark)
            )
        },
        bottomBar = {
            NavigationBar(containerColor = SurfaceDark) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Outlined.Inventory2, contentDescription = null) },
                    label = { Text("Контейнер") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = {
                        selectedTab = 1
                        viewModel.refreshCurrentDirectory()
                    },
                    icon = { Icon(Icons.Outlined.Folder, contentDescription = null) },
                    label = { Text("Файлы") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = {
                        BadgedBox(
                            badge = {
                                if (errorCount > 0) {
                                    Badge { Text(errorCount.coerceAtMost(99).toString()) }
                                }
                            }
                        ) {
                            Icon(Icons.Outlined.Terminal, contentDescription = null)
                        }
                    },
                    label = { Text("Логи") }
                )
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            when (selectedTab) {
                0 -> ContainerScreen(
                    uiState = uiState,
                    loadContainerIcon = { pkg -> viewModel.getContainerIcon(pkg) },
                    loadHostIcon = { pkg -> viewModel.getHostIcon(pkg) },
                    onToggleHideLauncher = { hide -> viewModel.setHideCloneFromLauncher(hide) },
                    onSelectEngine = { pkg, mode -> viewModel.setSelectedEngine(pkg, mode) },
                    onOpenDeviceImport = { includeSystem -> viewModel.loadDeviceApps(includeSystem) },
                    onSelectDeviceApp = { deviceApp -> viewModel.importDeviceApp(deviceApp) },
                    onInstallApkUri = { uri -> viewModel.installExternalApk(uri) },
                    onLaunchOrBuildClone = { app -> viewModel.buildAndInstallOrLaunchClone(app, forceRebuild = false) },
                    onRebuildClone = { app -> viewModel.buildAndInstallOrLaunchClone(app, forceRebuild = true) },
                    onInspectApp = { app -> viewModel.inspectContainerApp(app) },
                    onDismissReport = { viewModel.dismissInspectionReport() },
                    onOpenAppFiles = { app ->
                        viewModel.openAppFiles(app)
                        selectedTab = 1
                    },
                    onOpenAppFolderByPackage = { pkg ->
                        viewModel.openAppFilesByPackage(pkg)
                        selectedTab = 1
                    },
                    onDeleteApp = { pkg -> viewModel.removeContainerApp(pkg) },
                    onUninstallClone = { clonePkg -> viewModel.requestUninstallClone(clonePkg) },
                    onDismissConflict = { viewModel.dismissConflictDialog() }
                )

                1 -> FileManagerScreen(
                    uiState = uiState,
                    onItemClick = { item -> viewModel.onBrowserItemClick(item) },
                    onNavigateUp = { viewModel.navigateUp() },
                    onRefresh = { viewModel.refreshCurrentDirectory() },
                    onCreateFolder = { name -> viewModel.createFolder(name) },
                    onDismissPreview = { viewModel.dismissFilePreview() },
                    onRequestExportItem = { item -> viewModel.requestExportItem(item) },
                    onCompleteExportFile = { uri -> viewModel.completeExportFile(uri) },
                    onImportFileIntoDir = { uri -> viewModel.importFileIntoCurrentDir(uri) },
                    onDeleteItem = { item -> viewModel.deleteBrowserItem(item) },
                    onSwitchCloneRoot = { rootKey -> viewModel.switchCloneRoot(rootKey) },
                    onSwitchToLocalContainer = { viewModel.switchToLocalContainer() },
                    onClearCloneCache = { viewModel.clearActiveCloneCache() },
                    onRebuildCloneForBridge = { app ->
                        viewModel.buildAndInstallOrLaunchClone(app, forceRebuild = true)
                    },
                    onLaunchCloneForBridge = { app ->
                        viewModel.launchInstalledClone(app)
                    }
                )

                else -> LogScreen(
                    onSaveLogsToUri = { uri, text -> viewModel.exportLogsToUri(uri, text) },
                    onNotify = { text ->
                        coroutineScope.launch { snackbarHostState.showSnackbar(text) }
                    }
                )
            }
        }
    }
}
