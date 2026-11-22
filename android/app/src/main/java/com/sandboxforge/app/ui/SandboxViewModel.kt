package com.sandboxforge.app.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sandboxforge.app.clone.ApkCloneBuilder
import com.sandboxforge.app.clone.BuiltCloneBundle
import com.sandboxforge.app.clone.CloneInstallBus
import com.sandboxforge.app.clone.CloneInstaller
import com.sandboxforge.app.clone.EngineMode
import com.sandboxforge.app.clone.InstallEvent
import com.sandboxforge.app.clone.LocalKeystoreManager
import com.sandboxforge.app.container.ApkImporter
import com.sandboxforge.app.container.ApkInspectionReport
import com.sandboxforge.app.container.ContainerApp
import com.sandboxforge.app.container.ContainerFileItem
import com.sandboxforge.app.container.ContainerFileManager
import com.sandboxforge.app.container.ContainerRuntime
import com.sandboxforge.app.container.ContainerStorage
import com.sandboxforge.app.container.DeviceAppInfo
import com.sandboxforge.app.container.ShimContainerRuntime
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

sealed class UiActionEffect {
    data class LaunchInstallConfirmation(val intent: Intent) : UiActionEffect()
    data object RequestUnknownSourcesPermission : UiActionEffect()
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
    val currentDir: File? = null,
    val currentRelativePath: String = "/",
    val fileItems: List<ContainerFileItem> = emptyList(),
    val filePreview: FilePreviewState? = null,
    val pendingExportFile: File? = null
)

class SandboxViewModel(application: Application) : AndroidViewModel(application) {
    private val storage = ContainerStorage(application.filesDir, containerId = "default")
    private val iconCache = AppIconCache(application.applicationContext, storage.iconsDir)
    private val importer = ApkImporter(application.applicationContext, storage, iconCache)
    private val fileManager = ContainerFileManager(storage.rootDir)
    private val keystoreManager = LocalKeystoreManager(application.filesDir)
    private val cloneBuilder = ApkCloneBuilder(application.applicationContext, storage, keystoreManager)
    private val cloneInstaller = CloneInstaller(application.applicationContext)
    private val runtime: ContainerRuntime = ShimContainerRuntime(storage, cloneInstaller)

    private val _uiState = MutableStateFlow(SandboxUiState())
    val uiState: StateFlow<SandboxUiState> = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<UiActionEffect>(extraBufferCapacity = 8)
    val effects: SharedFlow<UiActionEffect> = _effects.asSharedFlow()

    private var lastBuiltBundle: BuiltCloneBundle? = null

    init {
        refreshContainerApps(backfillIcons = true)
        navigateToDirectory(fileManager.getRoot())
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
                        if (event.originalPackage.isNotBlank()) {
                            storage.updateApp(event.originalPackage) { app ->
                                app.copy(
                                    clonePackageName = event.clonePackage,
                                    launcherActivity = bundle?.launcherActivity ?: app.launcherActivity
                                )
                            }
                        }
                        refreshContainerApps()
                        _uiState.update {
                            it.copy(
                                isBusy = false,
                                busyMessage = null,
                                statusBanner = "Клон установлен: ${event.clonePackage}"
                            )
                        }
                    }

                    is InstallEvent.Failure -> {
                        _uiState.update {
                            it.copy(
                                isBusy = false,
                                busyMessage = null,
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
            refreshCurrentDirectory()
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
            refreshCurrentDirectory()
        }
    }

    fun buildAndInstallOrLaunchClone(app: ContainerApp, forceRebuild: Boolean = false) {
        if (app.selectedEngine == EngineMode.CLEAN_PROFILE) {
            AppLogger.w(
                "EngineAdvisor",
                "Для ${app.packageName} выбран «Чистый профиль (Банки)» — этот движок (Shizuku/Work Profile) подключается на следующем шаге. Переключи вручную на SHIM для теста патчера."
            )
            _uiState.update {
                it.copy(
                    statusBanner = "Чистый профиль (Shizuku) подключим на Шаге 2.2. Можно переключить на SHIM вручную."
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
                it.copy(statusBanner = "Разреши установку из SandboxForge и нажми «Клон» ещё раз")
            }
            return
        }

        val hideIcon = _uiState.value.hideCloneFromLauncher
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isBusy = true, busyMessage = "Сборка клона ${app.appName}...") }
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
                            launcherActivity = bundle.launcherActivity
                        )
                    }
                    _uiState.update { it.copy(busyMessage = "Передача клона в PackageInstaller...") }
                    val commitRes = cloneInstaller.commitInstallSession(bundle)
                    if (commitRes.isFailure) {
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
            _uiState.update {
                it.copy(statusBanner = "Удалено из контейнера: $packageName")
            }
            refreshContainerApps()
            refreshCurrentDirectory()
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

    fun openAppDataFolder(packageName: String) {
        val dir = storage.ensureAppDataSkeleton(packageName)
        navigateToDirectory(dir)
        AppLogger.i("FileManager", "Переход в папку данных приложения: $packageName")
    }

    fun navigateToDirectory(dir: File) {
        viewModelScope.launch(Dispatchers.IO) {
            val target = if (fileManager.isInsideContainer(dir) && dir.exists() && dir.isDirectory) {
                dir
            } else {
                fileManager.getRoot()
            }
            val result = fileManager.listDirectory(target)
            result.fold(
                onSuccess = { items ->
                    _uiState.update {
                        it.copy(
                            currentDir = target,
                            currentRelativePath = fileManager.relativePath(target),
                            fileItems = items
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

    fun navigateUp() {
        val current = _uiState.value.currentDir ?: fileManager.getRoot()
        navigateToDirectory(fileManager.parentOrRoot(current))
    }

    fun refreshCurrentDirectory() {
        val current = _uiState.value.currentDir ?: fileManager.getRoot()
        navigateToDirectory(current)
    }

    fun createFolder(name: String) {
        val parent = _uiState.value.currentDir ?: fileManager.getRoot()
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

    fun openFilePreview(file: File) {
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

    fun requestExportFile(file: File) {
        _uiState.update { it.copy(pendingExportFile = file) }
    }

    fun completeExportFile(destinationUri: Uri?) {
        val source = _uiState.value.pendingExportFile
        _uiState.update { it.copy(pendingExportFile = null) }
        if (source == null || destinationUri == null) return

        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val out = getApplication<Application>().contentResolver.openOutputStream(destinationUri)
                    ?: error("Не удалось открыть файл назначения")
                out.use { stream ->
                    fileManager.exportFile(source, stream).getOrThrow()
                }
            }
            result.fold(
                onSuccess = {
                    _uiState.update { it.copy(statusBanner = "Экспортирован: ${source.name}") }
                },
                onFailure = { err ->
                    AppLogger.e("FileManager", "Ошибка экспорта ${source.name}", err)
                    _uiState.update { it.copy(statusBanner = "Ошибка экспорта: ${err.message}") }
                }
            )
        }
    }

    fun importFileIntoCurrentDir(uri: Uri) {
        val targetDir = _uiState.value.currentDir ?: fileManager.getRoot()
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

    fun deleteFileItem(item: ContainerFileItem) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = fileManager.deleteTarget(item.file)
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
