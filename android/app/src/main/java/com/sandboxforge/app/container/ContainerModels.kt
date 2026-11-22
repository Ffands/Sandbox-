package com.sandboxforge.app.container

import com.sandboxforge.app.clone.EngineMode

enum class InstallSource(val displayName: String) {
    IMPORTED_FROM_DEVICE("Импорт с телефона"),
    INSTALLED_FROM_APK("Установлен из APK")
}

data class ContainerApp(
    val packageName: String,
    val appName: String,
    val versionName: String,
    val versionCode: Long,
    val source: InstallSource,
    val apkFileNames: List<String>,
    val totalApkBytes: Long,
    val nativeAbis: List<String>,
    val installedAtMillis: Long,
    val recommendedEngine: EngineMode = EngineMode.MOD_SHIM,
    val selectedEngine: EngineMode = EngineMode.MOD_SHIM,
    val engineReason: String = "",
    val clonePackageName: String? = null,
    val launcherActivity: String? = null,
    val bridgeToken: String? = null,
    val shimVersion: String? = null
) {
    val splitCount: Int
        get() = (apkFileNames.size - 1).coerceAtLeast(0)
}

data class DeviceAppInfo(
    val packageName: String,
    val appName: String,
    val versionName: String,
    val versionCode: Long,
    val isSystemApp: Boolean,
    val baseApkPath: String,
    val splitApkPaths: List<String>
) {
    val splitCount: Int
        get() = splitApkPaths.size
}

data class ApkInspectionReport(
    val packageName: String,
    val appName: String,
    val versionLabel: String,
    val apkFilesCount: Int,
    val totalApkBytes: Long,
    val nativeAbis: List<String>,
    val readOnlyEnforced: Boolean,
    val dataDirectoryPath: String,
    val dataFilesCount: Int,
    val recommendedEngine: EngineMode,
    val selectedEngine: EngineMode,
    val engineReason: String,
    val clonePackageName: String?,
    val isCloneInstalled: Boolean
)
