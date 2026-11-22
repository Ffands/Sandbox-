package com.sandboxforge.app

import android.os.Bundle
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
import androidx.compose.ui.text.font.FontWeight
import com.sandboxforge.app.logging.AppLogger
import com.sandboxforge.app.logging.LogLevel
import com.sandboxforge.app.ui.ContainerScreen
import com.sandboxforge.app.ui.FileManagerScreen
import com.sandboxforge.app.ui.LogScreen
import com.sandboxforge.app.ui.SandboxViewModel
import com.sandboxforge.app.ui.theme.BgDark
import com.sandboxforge.app.ui.theme.SandboxForgeTheme
import com.sandboxforge.app.ui.theme.SurfaceDark
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SandboxRootScreen(viewModel: SandboxViewModel) {
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

    LaunchedEffect(uiState.statusBanner) {
        val msg = uiState.statusBanner
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearStatusBanner()
        }
    }

    Scaffold(
        containerColor = BgDark,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = when (selectedTab) {
                            0 -> "SandboxForge — Контейнер"
                            1 -> "Файлы контейнера"
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
                    onOpenDeviceImport = { includeSystem -> viewModel.loadDeviceApps(includeSystem) },
                    onSelectDeviceApp = { deviceApp -> viewModel.importDeviceApp(deviceApp) },
                    onInstallApkUri = { uri -> viewModel.installExternalApk(uri) },
                    onInspectApp = { app -> viewModel.inspectContainerApp(app) },
                    onDismissReport = { viewModel.dismissInspectionReport() },
                    onOpenAppFolder = { pkg ->
                        viewModel.openAppDataFolder(pkg)
                        selectedTab = 1
                    },
                    onDeleteApp = { pkg -> viewModel.removeContainerApp(pkg) }
                )

                1 -> FileManagerScreen(
                    uiState = uiState,
                    onNavigateInto = { dir -> viewModel.navigateToDirectory(dir) },
                    onNavigateUp = { viewModel.navigateUp() },
                    onRefresh = { viewModel.refreshCurrentDirectory() },
                    onCreateFolder = { name -> viewModel.createFolder(name) },
                    onOpenFilePreview = { file -> viewModel.openFilePreview(file) },
                    onDismissPreview = { viewModel.dismissFilePreview() },
                    onRequestExportFile = { file -> viewModel.requestExportFile(file) },
                    onCompleteExportFile = { uri -> viewModel.completeExportFile(uri) },
                    onImportFileIntoDir = { uri -> viewModel.importFileIntoCurrentDir(uri) },
                    onDeleteItem = { item -> viewModel.deleteFileItem(item) }
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
