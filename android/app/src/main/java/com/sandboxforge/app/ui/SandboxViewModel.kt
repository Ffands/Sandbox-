package com.sandboxforge.app.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sandboxforge.app.clone.ApkCloneBuilder
import com.sandboxforge.app.clone.BridgeNeedsRebuildException
import com.sandboxforge.app.clone.BridgeProcessBlockedException
import com.sandboxforge.app.clone.BuiltCloneBundle
import com.sandboxforge.app.clone.CloneFileBridgeClient
import com.sandboxforge.app.clone.CloneInstallBus
import com.sandboxforge.app.clone.CloneInstaller
import com.sandboxforge.app.clone.EngineMode
import com.sandboxforge.app.clone.InstallEvent
import com.sandboxforge.app.clone.LocalKeystoreManager
import com.sandboxforge.app.container.ApkImporter
import com.sandboxforge.app.container.ApkInspectionReport
import com.sandboxforge.app.container.ContainerApp
import com.sandboxforge.app.container.ContainerFileManager
import com.sandboxforge.app.container.ContainerRuntime
import com.sandboxforge.app.container.ContainerStorage
import com.sandboxforge.app.container.DeviceAppInfo
import com.sandboxforge.app.container.ShimContainerRuntime
import com.sandboxforge.app.container.formatBytes
import com.sandboxforge.app.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class FilePreviewState(
    val fileName: String,
    val relativePath: String,
    val content: String,
    val truncated: Boolean
)

data class BrowserFileItem(
    val id: String,
    val name: String,
    val relativePath: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val isReadOnly: Boolean,
    val localFile: File? = null
)

data class ActiveCloneBridge(
    val originalPackage: String,
    val appName: String,
    val clonePackage: String,
    val bridgeToken: String?,
    val rootKey: String = "internal"
)

sealed class PendingExportTarget(val suggestedName: String) {
    data class LocalFile(val file: File) : PendingExportTarget(file.name)
    data class LiveCloneFile(
        val clonePackage: String,
        val bridgeToken: String,
        val rootKey: String,
        val relativePath: String,
        val fileName: String
    ) : PendingExportTarget(fileName)
}

sealed class UiActionEffect {
    data class LaunchInstallConfirmation(val intent: Intent) : UiActionEffect()
    data object RequestUnknownSourcesPermission : UiActionEffect()
    data class RequestUninstallPackage(val packageName: String) : UiActionEffect()
}

data class SandboxUiState(
    val containerApps: List<ContainerApp> = emptyList(),
    val installedClones: Set<String> = emptySet(),
    val hideCloneFromLauncher: Boolean = true,
    val deviceApps: List<DeviceAppInfo> = emptyList(),
    val isLoadingDeviceApps: Boolean = false,
    val isBusy: Boolean = false,
    val busyMessage: String? = null,
    val statusBanner: String? = null,
    val inspectionReport: ApkInspectionReport? = null,
    val currentLocalDir: File? = null,
    val activeCloneBridge: ActiveCloneBridge? = null,
    val bridgeNeedsRebuildApp: ContainerApp? = null,
    val bridgeNeedsLaunchApp: ContainerApp? = null,
    val bridgeErrorMessage: String? = null,
    val conflictingClonePackage: String? = null,
    val currentRelativePath: String = "/",
    val fileItems: List<BrowserFileItem> = emptyList(),
    val filePreview: FilePreviewState? = null,
    val pendingExportTarget: PendingExportTarget? = null
)

class SandboxViewModel(application: Application) : AndroidViewModel(application) {
    private val storage = ContainerStorage(application.filesDir, containerId = "default")
    private val iconCache = AppIconCache(application.applicationContext, storage.iconsDir)
    private val importer = ApkImporter(application.applicationContext, storage, iconCache)
    private val fileManager = ContainerFileManager(storage.rootDir)
    private val keystoreManager = LocalKeystoreManager(application.filesDir)
    private val cloneBuilder = ApkCloneBuilder(application.applicationContext, storage, keystoreManager)
    private val cloneInstaller = CloneInstaller(application.applicationContext)
    private val bridgeClient = CloneFileBridgeClient(application.applicationContext)
    private val runtime: ContainerRuntime = ShimContainerRuntime(storage, cloneInstaller)

    private val _uiState = MutableStateFlow(SandboxUiState())
    val uiState: StateFlow<SandboxUiState> = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<UiActionEffect>(extraBufferCapacity = 8)
    val effects: SharedFlow<UiActionEffect> = _effects.asSharedFlow()

    private var lastBuiltBundle: BuiltCloneBundle? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            cloneBuilder.cleanupBuildCache()
        }
        refreshContainerApps(backfillIcons = true)
        navigateToLocalDirectory(fileManager.getRoot())
        observeInstallEvents()
        AppLogger.i("SandboxVM", "Контейнер default готов: ${storage.rootDir.absolutePath}")
    }

    private fun observeInstallEvents() {
        viewModelScope.launch {
            CloneInstallBus.events.collect { event ->
                when (event) {
                    is InstallEvent.UserConfirmationRequired -> {
                        _effects.tryEmit(UiActionEffect.LaunchInstallConfirmation(event.confirmIntent))
                    }

                    is InstallEvent.Success -> {
                        val bundle = lastBuiltBundle
                        viewModelScope.launch(Dispatchers.IO) {
                            cloneBuilder.cleanupBuildCache(event.originalPackage.ifBlank { null })
                            if (event.originalPackage.isNotBlank()) {
                                storage.updateApp(event.originalPackage) { app ->
                                    app.copy(
                                        clonePackageName = event.clonePackage,
                                        launcherActivity = bundle?.launcherActivity ?: app.launcherActivity,
                                        bridgeToken = bundle?.bridgeToken ?: app.bridgeToken,
                                        shimVersion = bundle?.shimVersion ?: ApkCloneBuilder.CURRENT_SHIM_VERSION
                                    )
                                }
                            }
                            refreshContainerApps()
                            // If user was waiting to browse this clone's live files, auto-reopen bridge
                            val waitingRebuild = _uiState.value.bridgeNeedsRebuildApp
                            if (waitingRebuild != null && waitingRebuild.packageName == event.originalPackage) {
                                val fresh = storage.loadApps().firstOrNull { it.packageName == event.originalPackage }
                                if (fresh != null) {
                                    openAppFiles(fresh)
                                }
                            }
                        }
                        _uiState.update {
                            it.copy(
                                isBusy = false,
                                busyMessage = null,
                                statusBanner = "Клон установлен: ${event.clonePackage}"
                            )
                        }
                    }

                    is InstallEvent.Failure -> {
                        val conflictPkg = event.clonePackage.takeIf {
                            it.isNotBlank() && cloneInstaller.isPackageInstalled(it)
                        }
                        viewModelScope.launch(Dispatchers.IO) {
                            cloneBuilder.cleanupBuildCache()
                        }
                        _uiState.update {
                            it.copy(
                                isBusy = false,
                                busyMessage = null,
                                conflictingClonePackage = conflictPkg,
                                statusBanner = "Ошибка установки: ${event.message}"
                            )
                        }
                    }
                }
            }
        }
    }

    fun getContainerIcon(packageName: String): ImageBitmap? = iconCache.loadCachedIcon(packageName)

    fun getHostIcon(packageName: String): ImageBitmap? = iconCache.loadHostAppIcon(packageName)

    fun setHideCloneFromLauncher(hide: Boolean) {
        _uiState.update { it.copy(hideCloneFromLauncher = hide) }
        AppLogger.d("SandboxVM", "Опция скрытия иконки клона из лаунчера: $hide")
    }

    fun setSelectedEngine(packageName: String, mode: EngineMode) {
        viewModelScope.launch(Dispatchers.IO) {
            storage.updateApp(packageName) { it.copy(selectedEngine = mode) }
            AppLogger.i("EngineAdvisor", "Ручной выбор движка для $packageName -> ${mode.displayName}")
            refreshContainerApps()
        }
    }

    fun refreshContainerApps(backfillIcons: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            val apps = storage.loadApps()
            if (backfillIcons && apps.isNotEmpty()) {
                importer.backfillMissingIcons(apps)
            }
            val installedSet = apps.mapNotNull { app ->
                val clonePkg = app.clonePackageName
                if (clonePkg != null && cloneInstaller.isPackageInstalled(clonePkg)) {
                    app.packageName
                } else {
                    null
                }
            }.toSet()
            _uiState.update {
                it.copy(
                    containerApps = apps,
                    installedClones = installedSet
                )
            }
        }
    }

    fun loadDeviceApps(includeSystem: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isLoadingDeviceApps = true) }
            AppLogger.d("SandboxVM", "Сканирование приложений устройства (includeSystem=$includeSystem)")
            val list = runCatching {
                importer.listInstalledDeviceApps(includeSystem)
            }.onFailure { err ->
                AppLogger.e("SandboxVM", "Не удалось получить список приложений телефона", err)
            }.getOrDefault(emptyList())
            AppLogger.i("SandboxVM", "Найдено приложений на устройстве: ${list.size}")
            _uiState.update {
                it.copy(
                    deviceApps = list,
                    isLoadingDeviceApps = false
                )
            }
        }
    }

    fun importDeviceApp(app: DeviceAppInfo) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isBusy = true, busyMessage = "Импорт ${app.appName}...") }
            val result = importer.importFromDevice(app)
            val message = result.fold(
                onSuccess = { "Импортировано: ${it.appName} (${it.apkFileNames.size} APK)" },
                onFailure = { "Ошибка импорта: ${it.message}" }
            )
            _uiState.update {
                it.copy(
                    isBusy = false,
                    busyMessage = null,
                    statusBanner = message
                )
            }
            refreshContainerApps()
            if (_uiState.value.activeCloneBridge == null) {
                refreshCurrentDirectory()
            }
        }
    }

    fun installExternalApk(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isBusy = true, busyMessage = "Установка APK в контейнер...") }
            val result = importer.installFromApkUri(uri)
            val message = result.fold(
                onSuccess = { "Установлен APK: ${it.appName} (${it.packageName})" },
                onFailure = { "Ошибка установки APK: ${it.message}" }
            )
            _uiState.update {
                it.copy(
                    isBusy = false,
                    busyMessage = null,
                    statusBanner = message
                )
            }
            refreshContainerApps()
            if (_uiState.value.activeCloneBridge == null) {
                refreshCurrentDirectory()
            }
        }
    }

    fun buildAndInstallOrLaunchClone(app: ContainerApp, forceRebuild: Boolean = false) {
        if (app.selectedEngine == EngineMode.CLEAN_PROFILE) {
            AppLogger.w(
                "EngineAdvisor",
                "Для ${app.packageName} выбран «Чистый профиль (Банки)» — этот движок (Shizuku/Work Profile) подключается на Шаге 2.3. Переключи вручную на SHIM для теста патчера и щита."
            )
            _uiState.update {
                it.copy(
                    statusBanner = "Чистый профиль (Shizuku) подключим на Шаге 2.3. Можно переключить на SHIM вручную."
                )
            }
            return
        }

        val clonePkg = app.clonePackageName
        if (!forceRebuild && clonePkg != null && cloneInstaller.isPackageInstalled(clonePkg)) {
            val launchRes = cloneInstaller.launchClone(clonePkg, app.launcherActivity)
            if (launchRes.isFailure) {
                _uiState.update {
                    it.copy(statusBanner = "Ошибка запуска клона: ${launchRes.exceptionOrNull()?.message}")
                }
            }
            return
        }

        if (!cloneInstaller.canRequestPackageInstalls()) {
            AppLogger.w("CloneInstaller", "Нет разрешения REQUEST_INSTALL_PACKAGES — открываю настройки")
            _effects.tryEmit(UiActionEffect.RequestUnknownSourcesPermission)
            _uiState.update {
                it.copy(statusBanner = "Разреши установку из SandboxForge и нажми «Собрать клон» ещё раз")
            }
            return
        }

        val hideIcon = _uiState.value.hideCloneFromLauncher
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update {
                it.copy(
                    isBusy = true,
                    busyMessage = if (forceRebuild) "Пересборка клона ${app.appName}..." else "Сборка клона ${app.appName}..."
                )
            }
            val buildResult = cloneBuilder.buildClone(
                app = app,
                hideFromLauncher = hideIcon,
                onProgress = { step ->
                    _uiState.update { it.copy(busyMessage = step) }
                }
            )
            buildResult.fold(
                onSuccess = { bundle ->
                    lastBuiltBundle = bundle
                    storage.updateApp(app.packageName) {
                        it.copy(
                            clonePackageName = bundle.clonePackage,
                            launcherActivity = bundle.launcherActivity,
                            bridgeToken = bundle.bridgeToken,
                            shimVersion = bundle.shimVersion
                        )
                    }
                    _uiState.update { it.copy(busyMessage = "Передача клона в PackageInstaller...") }
                    val commitRes = cloneInstaller.commitInstallSession(bundle)
                    if (commitRes.isFailure) {
                        cloneBuilder.cleanupBuildCache(app.packageName)
                        _uiState.update {
                            it.copy(
                                isBusy = false,
                                busyMessage = null,
                                statusBanner = "Сбой установщика: ${commitRes.exceptionOrNull()?.message}"
                            )
                        }
                    }
                },
                onFailure = { err ->
                    cloneBuilder.cleanupBuildCache(app.packageName)
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            busyMessage = null,
                            statusBanner = "Ошибка сборки клона: ${err.message}"
                        )
                    }
                }
            )
        }
    }

    fun removeContainerApp(packageName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            storage.removeApp(packageName)
            iconCache.deleteIcon(packageName)
            cloneBuilder.cleanupBuildCache(packageName)
            if (_uiState.value.activeCloneBridge?.originalPackage == packageName) {
                switchToLocalContainer()
            }
            _uiState.update {
                it.copy(statusBanner = "Удалено из контейнера: $packageName")
            }
            refreshContainerApps()
            if (_uiState.value.activeCloneBridge == null) {
                refreshCurrentDirectory()
            }
        }
    }

    fun inspectContainerApp(app: ContainerApp) {
        viewModelScope.launch(Dispatchers.IO) {
            val report = runtime.inspect(app)
            _uiState.update { it.copy(inspectionReport = report) }
        }
    }

    fun dismissInspectionReport() {
        _uiState.update { it.copy(inspectionReport = null) }
    }

    // -------------------------------------------------------------------------
    // Unified File Browser (Local Container + Live Clone IPC Bridge)
    // -------------------------------------------------------------------------

    fun openAppFiles(app: ContainerApp) {
        val clonePkg = app.clonePackageName
        if (clonePkg != null && cloneInstaller.isPackageInstalled(clonePkg)) {
            val bridge = ActiveCloneBridge(
                originalPackage = app.packageName,
                appName = app.appName,
                clonePackage = clonePkg,
                bridgeToken = app.bridgeToken,
                rootKey = "internal"
            )
            AppLogger.i("FileBridge", "Открытие живых файлов клона: $clonePkg (root=internal)")
            loadLiveCloneDirectory(bridge, relativePath = "", sourceApp = app)
        } else {
            openLocalAppDataFolder(app.packageName)
        }
    }

    fun openAppFilesByPackage(packageName: String) {
        val app = _uiState.value.containerApps.firstOrNull { it.packageName == packageName }
        if (app != null) {
            openAppFiles(app)
        } else {
            openLocalAppDataFolder(packageName)
        }
    }

    fun dismissConflictDialog() {
        _uiState.update { it.copy(conflictingClonePackage = null) }
    }

    fun requestUninstallClone(clonePackage: String) {
        _uiState.update { it.copy(conflictingClonePackage = null) }
        AppLogger.i("CloneInstaller", "Запрос системного удаления клона: $clonePackage")
        _effects.tryEmit(UiActionEffect.RequestUninstallPackage(clonePackage))
    }

    fun launchInstalledClone(app: ContainerApp) {
        val clonePkg = app.clonePackageName ?: return
        val res = cloneInstaller.launchClone(clonePkg, app.launcherActivity)
        if (res.isFailure) {
            _uiState.update {
                it.copy(statusBanner = "Ошибка запуска клона: ${res.exceptionOrNull()?.message}")
            }
        }
    }

    fun switchToLocalContainer() {
        _uiState.update {
            it.copy(
                activeCloneBridge = null,
                bridgeNeedsRebuildApp = null,
                bridgeNeedsLaunchApp = null,
                bridgeErrorMessage = null
            )
        }
        navigateToLocalDirectory(fileManager.getRoot())
    }

    fun switchCloneRoot(rootKey: String) {
        val current = _uiState.value.activeCloneBridge ?: return
        val updated = current.copy(rootKey = rootKey)
        val app = _uiState.value.containerApps.firstOrNull { it.packageName == current.originalPackage }
        loadLiveCloneDirectory(updated, relativePath = "", sourceApp = app)
    }

    private fun openLocalAppDataFolder(packageName: String) {
        val dir = storage.ensureAppDataSkeleton(packageName)
        _uiState.update {
            it.copy(
                activeCloneBridge = null,
                bridgeNeedsRebuildApp = null,
                bridgeNeedsLaunchApp = null,
                bridgeErrorMessage = null
            )
        }
        navigateToLocalDirectory(dir)
        AppLogger.i("FileManager", "Переход в локальную папку контейнера: $packageName")
    }

    private fun loadLiveCloneDirectory(
        bridge: ActiveCloneBridge,
        relativePath: String,
        sourceApp: ContainerApp? = null
    ) {
        val token = bridge.bridgeToken
        if (token.isNullOrBlank()) {
            val app = sourceApp ?: _uiState.value.containerApps.firstOrNull { it.packageName == bridge.originalPackage }
            _uiState.update {
                it.copy(
                    activeCloneBridge = bridge,
                    bridgeNeedsRebuildApp = app,
                    bridgeNeedsLaunchApp = null,
                    bridgeErrorMessage = "Клон ${bridge.clonePackage} собран без токена файлового моста (Shim v0.2.0). Пересобери клон 1 нажатием, чтобы открыть живые файлы.",
                    currentRelativePath = "/",
                    fileItems = emptyList()
                )
            }
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            val res = bridgeClient.listDirectory(
                clonePackage = bridge.clonePackage,
                bridgeToken = token,
                rootKey = bridge.rootKey,
                relativePath = relativePath
            )
            res.fold(
                onSuccess = { listRes ->
                    val items = listRes.items.map { entry ->
                        BrowserFileItem(
                            id = "${bridge.clonePackage}:${bridge.rootKey}:${entry.relativePath}",
                            name = entry.name,
                            relativePath = entry.relativePath,
                            isDirectory = entry.isDirectory,
                            sizeBytes = entry.sizeBytes,
                            isReadOnly = entry.isReadOnly,
                            localFile = null
                        )
                    }
                    _uiState.update {
                        it.copy(
                            activeCloneBridge = bridge,
                            bridgeNeedsRebuildApp = null,
                            bridgeNeedsLaunchApp = null,
                            bridgeErrorMessage = null,
                            currentRelativePath = listRes.currentPath,
                            fileItems = items
                        )
                    }
                },
                onFailure = { err ->
                    val app = sourceApp ?: _uiState.value.containerApps.firstOrNull { it.packageName == bridge.originalPackage }
                    when (err) {
                        is BridgeNeedsRebuildException -> {
                            AppLogger.w("FileBridge", err.message ?: "Требуется пересборка клона")
                            _uiState.update {
                                it.copy(
                                    activeCloneBridge = bridge,
                                    bridgeNeedsRebuildApp = app,
                                    bridgeNeedsLaunchApp = null,
                                    bridgeErrorMessage = err.message,
                                    currentRelativePath = "/",
                                    fileItems = emptyList()
                                )
                            }
                        }

                        is BridgeProcessBlockedException -> {
                            AppLogger.w("FileBridge", err.message ?: "Процесс клона остановлен")
                            _uiState.update {
                                it.copy(
                                    activeCloneBridge = bridge,
                                    bridgeNeedsRebuildApp = null,
                                    bridgeNeedsLaunchApp = app,
                                    bridgeErrorMessage = err.message,
                                    currentRelativePath = "/",
                                    fileItems = emptyList()
                                )
                            }
                        }

                        else -> {
                            AppLogger.e("FileBridge", "Ошибка чтения моста ${bridge.clonePackage}", err)
                            _uiState.update {
                                it.copy(
                                    activeCloneBridge = bridge,
                                    bridgeNeedsRebuildApp = null,
                                    bridgeNeedsLaunchApp = null,
                                    bridgeErrorMessage = err.message,
                                    fileItems = emptyList(),
                                    statusBanner = "Ошибка моста файлов: ${err.message}"
                                )
                            }
                        }
                    }
                }
            )
        }
    }

    private fun navigateToLocalDirectory(dir: File) {
        viewModelScope.launch(Dispatchers.IO) {
            val target = if (fileManager.isInsideContainer(dir) && dir.exists() && dir.isDirectory) {
                dir
            } else {
                fileManager.getRoot()
            }
            val result = fileManager.listDirectory(target)
            result.fold(
                onSuccess = { items ->
                    val mapped = items.map { item ->
                        BrowserFileItem(
                            id = item.file.absolutePath,
                            name = item.name,
                            relativePath = fileManager.relativePath(item.file),
                            isDirectory = item.isDirectory,
                            sizeBytes = item.sizeBytes,
                            isReadOnly = item.isReadOnly,
                            localFile = item.file
                        )
                    }
                    _uiState.update {
                        it.copy(
                            activeCloneBridge = null,
                            bridgeNeedsRebuildApp = null,
                            bridgeErrorMessage = null,
                            currentLocalDir = target,
                            currentRelativePath = fileManager.relativePath(target),
                            fileItems = mapped
                        )
                    }
                },
                onFailure = { err ->
                    AppLogger.e("FileManager", "Ошибка открытия каталога ${target.name}", err)
                    _uiState.update { it.copy(statusBanner = err.message) }
                }
            )
        }
    }

    fun onBrowserItemClick(item: BrowserFileItem) {
        val bridge = _uiState.value.activeCloneBridge
        if (bridge != null) {
            if (item.isDirectory) {
                loadLiveCloneDirectory(bridge, item.relativePath)
            } else {
                openLiveClonePreview(bridge, item)
            }
        } else {
            val localFile = item.localFile ?: return
            if (item.isDirectory) {
                navigateToLocalDirectory(localFile)
            } else {
                openLocalFilePreview(localFile)
            }
        }
    }

    fun navigateUp() {
        val bridge = _uiState.value.activeCloneBridge
        if (bridge != null) {
            val cur = _uiState.value.currentRelativePath.trimEnd('/')
            val parent = if (cur.isEmpty() || cur == "/") "" else cur.substringBeforeLast('/', "")
            loadLiveCloneDirectory(bridge, parent)
        } else {
            val current = _uiState.value.currentLocalDir ?: fileManager.getRoot()
            navigateToLocalDirectory(fileManager.parentOrRoot(current))
        }
    }

    fun refreshCurrentDirectory() {
        val bridge = _uiState.value.activeCloneBridge
        if (bridge != null) {
            val freshApp = _uiState.value.containerApps.firstOrNull { it.packageName == bridge.originalPackage }
            val updatedBridge = if (freshApp?.bridgeToken != null) {
                bridge.copy(bridgeToken = freshApp.bridgeToken)
            } else {
                bridge
            }
            loadLiveCloneDirectory(updatedBridge, _uiState.value.currentRelativePath, freshApp)
        } else {
            val current = _uiState.value.currentLocalDir ?: fileManager.getRoot()
            navigateToLocalDirectory(current)
        }
    }

    fun createFolder(name: String) {
        val bridge = _uiState.value.activeCloneBridge
        if (bridge != null) {
            val token = bridge.bridgeToken ?: return
            val parentPath = _uiState.value.currentRelativePath
            viewModelScope.launch(Dispatchers.IO) {
                val res = bridgeClient.createFolder(bridge.clonePackage, token, bridge.rootKey, parentPath, name)
                res.fold(
                    onSuccess = {
                        _uiState.update { it.copy(statusBanner = "Папка «$name» создана в клоне") }
                        refreshCurrentDirectory()
                    },
                    onFailure = { err ->
                        AppLogger.w("FileBridge", "Не удалось создать папку «$name»: ${err.message}")
                        _uiState.update { it.copy(statusBanner = err.message) }
                    }
                )
            }
            return
        }

        val parent = _uiState.value.currentLocalDir ?: fileManager.getRoot()
        viewModelScope.launch(Dispatchers.IO) {
            val result = fileManager.createFolder(parent, name)
            result.fold(
                onSuccess = {
                    _uiState.update { it.copy(statusBanner = "Папка «$name» создана") }
                    refreshCurrentDirectory()
                },
                onFailure = { err ->
                    AppLogger.w("FileManager", "Не удалось создать папку «$name»: ${err.message}")
                    _uiState.update { it.copy(statusBanner = err.message) }
                }
            )
        }
    }

    fun clearActiveCloneCache() {
        val bridge = _uiState.value.activeCloneBridge ?: return
        val token = bridge.bridgeToken ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val res = bridgeClient.clearCache(bridge.clonePackage, token)
            res.fold(
                onSuccess = { stats ->
                    _uiState.update {
                        it.copy(
                            statusBanner = "Кэш очищен: удалено ${stats.deletedCount} объектов (${formatBytes(stats.freedBytes)})"
                        )
                    }
                    refreshCurrentDirectory()
                },
                onFailure = { err ->
                    AppLogger.e("FileBridge", "Ошибка очистки кэша ${bridge.clonePackage}", err)
                    _uiState.update { it.copy(statusBanner = "Ошибка очистки кэша: ${err.message}") }
                }
            )
        }
    }

    private fun openLiveClonePreview(bridge: ActiveCloneBridge, item: BrowserFileItem) {
        val token = bridge.bridgeToken ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val res = bridgeClient.readPreview(bridge.clonePackage, token, bridge.rootKey, item.relativePath)
            res.fold(
                onSuccess = { preview ->
                    _uiState.update {
                        it.copy(
                            filePreview = FilePreviewState(
                                fileName = item.name,
                                relativePath = "${bridge.rootKey}:${item.relativePath}",
                                content = preview.content,
                                truncated = preview.truncated
                            )
                        )
                    }
                },
                onFailure = { err ->
                    AppLogger.e("FileBridge", "Ошибка чтения ${item.name}", err)
                    _uiState.update { it.copy(statusBanner = "Не удалось открыть файл: ${err.message}") }
                }
            )
        }
    }

    private fun openLocalFilePreview(file: File) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = fileManager.readTextPreview(file)
            result.fold(
                onSuccess = { (content, truncated) ->
                    _uiState.update {
                        it.copy(
                            filePreview = FilePreviewState(
                                fileName = file.name,
                                relativePath = fileManager.relativePath(file),
                                content = content,
                                truncated = truncated
                            )
                        )
                    }
                },
                onFailure = { err ->
                    AppLogger.e("FileManager", "Ошибка чтения ${file.name}", err)
                    _uiState.update { it.copy(statusBanner = "Не удалось открыть файл: ${err.message}") }
                }
            )
        }
    }

    fun dismissFilePreview() {
        _uiState.update { it.copy(filePreview = null) }
    }

    fun requestExportItem(item: BrowserFileItem) {
        val bridge = _uiState.value.activeCloneBridge
        if (bridge != null) {
            val token = bridge.bridgeToken ?: return
            _uiState.update {
                it.copy(
                    pendingExportTarget = PendingExportTarget.LiveCloneFile(
                        clonePackage = bridge.clonePackage,
                        bridgeToken = token,
                        rootKey = bridge.rootKey,
                        relativePath = item.relativePath,
                        fileName = item.name
                    )
                )
            }
        } else {
            val localFile = item.localFile ?: return
            _uiState.update {
                it.copy(pendingExportTarget = PendingExportTarget.LocalFile(localFile))
            }
        }
    }

    fun completeExportFile(destinationUri: Uri?) {
        val target = _uiState.value.pendingExportTarget
        _uiState.update { it.copy(pendingExportTarget = null) }
        if (target == null || destinationUri == null) return

        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val out = getApplication<Application>().contentResolver.openOutputStream(destinationUri)
                    ?: error("Не удалось открыть файл назначения")
                out.use { stream ->
                    when (target) {
                        is PendingExportTarget.LocalFile -> fileManager.exportFile(target.file, stream).getOrThrow()
                        is PendingExportTarget.LiveCloneFile -> bridgeClient.exportFile(
                            clonePackage = target.clonePackage,
                            bridgeToken = target.bridgeToken,
                            rootKey = target.rootKey,
                            relativePath = target.relativePath,
                            destination = stream
                        ).getOrThrow()
                    }
                }
            }
            result.fold(
                onSuccess = {
                    _uiState.update { it.copy(statusBanner = "Экспортирован: ${target.suggestedName}") }
                },
                onFailure = { err ->
                    AppLogger.e("FileManager", "Ошибка экспорта ${target.suggestedName}", err)
                    _uiState.update { it.copy(statusBanner = "Ошибка экспорта: ${err.message}") }
                }
            )
        }
    }

    fun importFileIntoCurrentDir(uri: Uri) {
        val bridge = _uiState.value.activeCloneBridge
        if (bridge != null) {
            val token = bridge.bridgeToken ?: return
            val dirRel = _uiState.value.currentRelativePath
            viewModelScope.launch(Dispatchers.IO) {
                val fileName = resolveUriFileName(uri)
                val result = runCatching {
                    val input = getApplication<Application>().contentResolver.openInputStream(uri)
                        ?: error("Не удалось прочитать выбранный файл")
                    input.use { stream ->
                        bridgeClient.importFile(
                            clonePackage = bridge.clonePackage,
                            bridgeToken = token,
                            rootKey = bridge.rootKey,
                            dirRelativePath = dirRel,
                            fileName = fileName,
                            source = stream
                        ).getOrThrow()
                    }
                }
                result.fold(
                    onSuccess = {
                        _uiState.update { it.copy(statusBanner = "Файл загружен в клон: $fileName") }
                        refreshCurrentDirectory()
                    },
                    onFailure = { err ->
                        AppLogger.e("FileBridge", "Ошибка загрузки файла в клон", err)
                        _uiState.update { it.copy(statusBanner = "Ошибка загрузки файла: ${err.message}") }
                    }
                )
            }
            return
        }

        val targetDir = _uiState.value.currentLocalDir ?: fileManager.getRoot()
        viewModelScope.launch(Dispatchers.IO) {
            val fileName = resolveUriFileName(uri)
            val result = runCatching {
                val input = getApplication<Application>().contentResolver.openInputStream(uri)
                    ?: error("Не удалось прочитать выбранный файл")
                input.use { stream ->
                    fileManager.importFile(targetDir, fileName, stream).getOrThrow()
                }
            }
            result.fold(
                onSuccess = { file ->
                    _uiState.update { it.copy(statusBanner = "Файл добавлен: ${file.name}") }
                    refreshCurrentDirectory()
                },
                onFailure = { err ->
                    AppLogger.e("FileManager", "Ошибка импорта файла", err)
                    _uiState.update { it.copy(statusBanner = "Ошибка импорта файла: ${err.message}") }
                }
            )
        }
    }

    fun deleteBrowserItem(item: BrowserFileItem) {
        val bridge = _uiState.value.activeCloneBridge
        if (bridge != null) {
            val token = bridge.bridgeToken ?: return
            viewModelScope.launch(Dispatchers.IO) {
                val res = bridgeClient.deleteTarget(bridge.clonePackage, token, bridge.rootKey, item.relativePath)
                res.fold(
                    onSuccess = {
                        _uiState.update { it.copy(statusBanner = "Удалено из клона: ${item.name}") }
                        refreshCurrentDirectory()
                    },
                    onFailure = { err ->
                        AppLogger.e("FileBridge", "Ошибка удаления ${item.name}", err)
                        _uiState.update { it.copy(statusBanner = err.message) }
                    }
                )
            }
            return
        }

        val localFile = item.localFile ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val result = fileManager.deleteTarget(localFile)
            result.fold(
                onSuccess = {
                    _uiState.update { it.copy(statusBanner = "Удалено: ${item.name}") }
                    refreshCurrentDirectory()
                },
                onFailure = { err ->
                    AppLogger.e("FileManager", "Ошибка удаления ${item.name}", err)
                    _uiState.update { it.copy(statusBanner = err.message) }
                }
            )
        }
    }

    fun exportLogsToUri(uri: Uri, content: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val out = getApplication<Application>().contentResolver.openOutputStream(uri)
                    ?: error("Не удалось открыть поток для записи лога")
                out.use { it.write(content.toByteArray(Charsets.UTF_8)) }
            }
            result.fold(
                onSuccess = {
                    AppLogger.i("AppLogger", "Лог сохранён пользователем во внешний файл")
                    _uiState.update { it.copy(statusBanner = "Лог сохранён в файл") }
                },
                onFailure = { err ->
                    AppLogger.e("AppLogger", "Ошибка сохранения лога во внешний файл", err)
                    _uiState.update { it.copy(statusBanner = "Ошибка сохранения лога: ${err.message}") }
                }
            )
        }
    }

    fun clearStatusBanner() {
        _uiState.update { it.copy(statusBanner = null) }
    }

    private fun resolveUriFileName(uri: Uri): String {
        val resolver = getApplication<Application>().contentResolver
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) {
                    val name = cursor.getString(idx)
                    if (!name.isNullOrBlank()) return name
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "file_${System.currentTimeMillis()}"
    }
}
