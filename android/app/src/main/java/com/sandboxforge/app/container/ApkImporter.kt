package com.sandboxforge.app.container

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.sandboxforge.app.logging.AppLogger
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipFile

/**
 * Handles importing installed host apps (base.apk + all split_*.apk)
 * and installing external APK files into the isolated container storage.
 */
class ApkImporter(
    private val context: Context,
    private val storage: ContainerStorage
) {
    private val pm: PackageManager = context.packageManager

    fun listInstalledDeviceApps(includeSystem: Boolean = false): List<DeviceAppInfo> {
        val apps = getInstalledApplicationsCompat()
        val selfPkg = context.packageName

        return apps.mapNotNull { appInfo ->
            if (appInfo.packageName == selfPkg) return@mapNotNull null
            val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            if (isSystem && !includeSystem) return@mapNotNull null

            val sourceDir = appInfo.sourceDir ?: return@mapNotNull null
            val pkgInfo = getPackageInfoCompat(appInfo.packageName)
            val label = runCatching { pm.getApplicationLabel(appInfo).toString() }
                .getOrDefault(appInfo.packageName)
            val splits = appInfo.splitSourceDirs?.toList().orEmpty()

            DeviceAppInfo(
                packageName = appInfo.packageName,
                appName = label,
                versionName = pkgInfo?.versionName ?: "1.0",
                versionCode = pkgInfo?.versionCodeCompat() ?: 1L,
                isSystemApp = isSystem,
                baseApkPath = sourceDir,
                splitApkPaths = splits
            )
        }.sortedBy { it.appName.lowercase() }
    }

    fun importFromDevice(deviceApp: DeviceAppInfo): Result<ContainerApp> = runCatching {
        AppLogger.i(
            "ApkImporter",
            "Начат импорт с телефона: ${deviceApp.appName} (${deviceApp.packageName}), " +
                "сплитов: ${deviceApp.splitCount}"
        )

        val targetDir = storage.prepareCleanApkDir(deviceApp.packageName)
        val copiedNames = mutableListOf<String>()
        var totalBytes = 0L
        val detectedAbis = linkedSetOf<String>()

        // 1. Copy base.apk
        val baseSrc = File(deviceApp.baseApkPath)
        require(baseSrc.exists() && baseSrc.canRead()) {
            "Нет доступа к base.apk: ${deviceApp.baseApkPath}"
        }
        val baseDst = File(targetDir, "base.apk")
        copyFile(baseSrc, baseDst)
        baseDst.setReadOnly()
        copiedNames.add(baseDst.name)
        totalBytes += baseDst.length()
        detectedAbis.addAll(inspectNativeAbis(baseDst))
        AppLogger.d("ApkImporter", "Скопирован base.apk (${formatBytes(baseDst.length())})")

        // 2. Copy all split APKs (critical for modern App Bundles!)
        deviceApp.splitApkPaths.forEachIndexed { index, splitPath ->
            val splitSrc = File(splitPath)
            if (splitSrc.exists() && splitSrc.canRead()) {
                val safeName = splitSrc.name.ifBlank { "split_$index.apk" }
                val splitDst = File(targetDir, safeName)
                copyFile(splitSrc, splitDst)
                splitDst.setReadOnly()
                copiedNames.add(splitDst.name)
                totalBytes += splitDst.length()
                detectedAbis.addAll(inspectNativeAbis(splitDst))
                AppLogger.d("ApkImporter", "Скопирован сплит $safeName (${formatBytes(splitDst.length())})")
            } else {
                AppLogger.w("ApkImporter", "Пропущен недоступный сплит: $splitPath")
            }
        }

        storage.ensureAppDataSkeleton(deviceApp.packageName)

        val containerApp = ContainerApp(
            packageName = deviceApp.packageName,
            appName = deviceApp.appName,
            versionName = deviceApp.versionName,
            versionCode = deviceApp.versionCode,
            source = InstallSource.IMPORTED_FROM_DEVICE,
            apkFileNames = copiedNames,
            totalApkBytes = totalBytes,
            nativeAbis = detectedAbis.toList(),
            installedAtMillis = System.currentTimeMillis()
        )
        storage.upsertApp(containerApp)

        AppLogger.i(
            "ApkImporter",
            "Импорт завершён: ${containerApp.packageName} | файлов: ${copiedNames.size} | " +
                "размер: ${formatBytes(totalBytes)} | ABIs: ${detectedAbis.ifEmpty { setOf("pure-dex") }.joinToString(",")}"
        )
        containerApp
    }.onFailure { err ->
        AppLogger.e("ApkImporter", "Ошибка импорта ${deviceApp.packageName}", err)
    }

    fun installFromApkUri(uri: Uri): Result<ContainerApp> = runCatching {
        AppLogger.i("ApkImporter", "Начата установка из внешнего APK: $uri")

        val tempFile = File(context.cacheDir, "import_${System.currentTimeMillis()}.apk")
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: error("Не удалось открыть поток для URI: $uri")
            input.use { src ->
                FileOutputStream(tempFile).use { dst ->
                    src.copyTo(dst, bufferSize = 64 * 1024)
                }
            }

            require(tempFile.length() > 0L) { "Выбранный файл пуст" }

            val archiveInfo = getPackageArchiveInfoCompat(tempFile.absolutePath)
                ?: error("Файл не является корректным Android APK (архив не распознан)")

            val pkgName = archiveInfo.packageName
            val appInfo = archiveInfo.applicationInfo
            val appLabel = if (appInfo != null) {
                appInfo.sourceDir = tempFile.absolutePath
                appInfo.publicSourceDir = tempFile.absolutePath
                runCatching { pm.getApplicationLabel(appInfo).toString() }.getOrDefault(pkgName)
            } else {
                pkgName
            }

            val targetDir = storage.prepareCleanApkDir(pkgName)
            val baseDst = File(targetDir, "base.apk")
            copyFile(tempFile, baseDst)
            baseDst.setReadOnly()

            val abis = inspectNativeAbis(baseDst)
            storage.ensureAppDataSkeleton(pkgName)

            val containerApp = ContainerApp(
                packageName = pkgName,
                appName = appLabel,
                versionName = archiveInfo.versionName ?: "1.0",
                versionCode = archiveInfo.versionCodeCompat(),
                source = InstallSource.INSTALLED_FROM_APK,
                apkFileNames = listOf(baseDst.name),
                totalApkBytes = baseDst.length(),
                nativeAbis = abis,
                installedAtMillis = System.currentTimeMillis()
            )
            storage.upsertApp(containerApp)

            AppLogger.i(
                "ApkImporter",
                "APK установлен в контейнер: $appLabel ($pkgName) | " +
                    "v${containerApp.versionName} | ${formatBytes(baseDst.length())} | " +
                    "ABIs: ${abis.ifEmpty { listOf("pure-dex") }.joinToString(",")}"
            )
            containerApp
        } finally {
            if (tempFile.exists()) tempFile.delete()
        }
    }.onFailure { err ->
        AppLogger.e("ApkImporter", "Ошибка установки APK из $uri", err)
    }

    private fun inspectNativeAbis(apkFile: File): List<String> {
        val abis = linkedSetOf<String>()
        runCatching {
            ZipFile(apkFile).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val name = entries.nextElement().name
                    if (name.startsWith("lib/") && name.endsWith(".so")) {
                        val parts = name.split('/')
                        if (parts.size >= 3) {
                            abis.add(parts[1])
                        }
                    }
                }
            }
        }
        return abis.toList()
    }

    private fun copyFile(src: File, dst: File) {
        FileInputStream(src).use { input ->
            FileOutputStream(dst).use { output ->
                input.copyTo(output, bufferSize = 64 * 1024)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun getInstalledApplicationsCompat(): List<ApplicationInfo> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0L))
        } else {
            pm.getInstalledApplications(0)
        }
    }

    @Suppress("DEPRECATION")
    private fun getPackageInfoCompat(packageName: String): PackageInfo? {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0L))
            } else {
                pm.getPackageInfo(packageName, 0)
            }
        }.getOrNull()
    }

    @Suppress("DEPRECATION")
    private fun getPackageArchiveInfoCompat(archivePath: String): PackageInfo? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageArchiveInfo(archivePath, PackageManager.PackageInfoFlags.of(0L))
        } else {
            pm.getPackageArchiveInfo(archivePath, 0)
        }
    }

    @Suppress("DEPRECATION")
    private fun PackageInfo.versionCodeCompat(): Long {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            longVersionCode
        } else {
            versionCode.toLong()
        }
    }
}

fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes Б"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format("%.1f КБ", kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return String.format("%.2f МБ", mb)
    val gb = mb / 1024.0
    return String.format("%.2f ГБ", gb)
}
