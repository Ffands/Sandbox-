package com.sandboxforge.app.container

import com.sandboxforge.app.clone.CloneInstaller
import com.sandboxforge.app.clone.EngineAdvisor
import com.sandboxforge.app.logging.AppLogger
import java.io.File

interface ContainerRuntime {
    fun inspect(app: ContainerApp): ApkInspectionReport
}

class ShimContainerRuntime(
    private val storage: ContainerStorage,
    private val installer: CloneInstaller
) : ContainerRuntime {
    override fun inspect(app: ContainerApp): ApkInspectionReport {
        val apkDir = storage.appApkDir(app.packageName)
        val dataDir = storage.ensureAppDataSkeleton(app.packageName)

        val apkFiles = apkDir.listFiles { f -> f.isFile && f.name.endsWith(".apk") }.orEmpty().toList()
        val allReadOnly = apkFiles.isNotEmpty() && apkFiles.all { !it.canWrite() }
        val dataFilesCount = countFiles(dataDir)
        val advice = EngineAdvisor.analyze(app.packageName, apkFiles, app.nativeAbis)
        val clonePkg = app.clonePackageName
        val isInstalled = clonePkg != null && installer.isPackageInstalled(clonePkg)

        val report = ApkInspectionReport(
            packageName = app.packageName,
            appName = app.appName,
            versionLabel = "${app.versionName} (${app.versionCode})",
            apkFilesCount = apkFiles.size,
            totalApkBytes = apkFiles.sumOf { it.length() },
            nativeAbis = app.nativeAbis,
            readOnlyEnforced = allReadOnly,
            dataDirectoryPath = dataDir.absolutePath,
            dataFilesCount = dataFilesCount,
            recommendedEngine = advice.recommendedMode,
            selectedEngine = app.selectedEngine,
            engineReason = app.engineReason.ifBlank { advice.reason },
            clonePackageName = clonePkg,
            isCloneInstalled = isInstalled
        )

        AppLogger.i(
            "ContainerRuntime",
            "Инспекция ${app.packageName}: apk=${report.apkFilesCount}, " +
                "режим=${report.selectedEngine.shortTag} (рек: ${report.recommendedEngine.shortTag}), " +
                "клон=${clonePkg ?: "не собран"} (установлен=$isInstalled)"
        )
        return report
    }

    private fun countFiles(dir: File): Int {
        if (!dir.exists()) return 0
        var count = 0
        dir.walkTopDown().forEach { f ->
            if (f.isFile) count++
        }
        return count
    }
}
