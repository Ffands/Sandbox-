package com.sandboxforge.app.container

import com.sandboxforge.app.logging.AppLogger
import java.io.File

/**
 * Clean abstraction for container execution.
 * Step 1 uses [DiagnosticStubRuntime] to verify container integrity, APK splits,
 * read-only flags (Android 14+), and ABIs before attaching the virtualization engine in Step 2.
 */
interface ContainerRuntime {
    fun inspect(app: ContainerApp): ApkInspectionReport
}

class DiagnosticStubRuntime(private val storage: ContainerStorage) : ContainerRuntime {
    override fun inspect(app: ContainerApp): ApkInspectionReport {
        val apkDir = storage.appApkDir(app.packageName)
        val dataDir = storage.ensureAppDataSkeleton(app.packageName)

        val apkFiles = apkDir.listFiles { f -> f.isFile && f.name.endsWith(".apk") }.orEmpty()
        val allReadOnly = apkFiles.isNotEmpty() && apkFiles.all { !it.canWrite() }
        val dataFilesCount = countFiles(dataDir)

        val notice = "Шаг 1 (Каркас v0.1): APK, сплиты и изолированные папки готовы. " +
            "Движок запуска гостевого процесса подключается на Шаге 2."

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
            runtimeNotice = notice
        )

        AppLogger.i(
            "ContainerRuntime",
            "Инспекция ${app.packageName}: apk=${report.apkFilesCount}, " +
                "readOnly=$allReadOnly, ABIs=${report.nativeAbis.ifEmpty { listOf("pure-dex") }.joinToString(",")}, " +
                "dataDir=${dataDir.name}"
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
