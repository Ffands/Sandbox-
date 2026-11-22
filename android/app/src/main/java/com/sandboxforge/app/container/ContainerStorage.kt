package com.sandboxforge.app.container

import com.sandboxforge.app.logging.AppLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Manages the isolated filesystem structure for the single MVP container ("default").
 * Layout:
 *   <filesDir>/containers/default/
 *     apks/<packageName>/base.apk (+ split_*.apk, marked read-only for Android 14+)
 *     data/<packageName>/{files,cache,shared_prefs,databases}
 *     sdcard/
 *     meta/apps.json
 */
class ContainerStorage(baseFilesDir: File, val containerId: String = "default") {
    val rootDir: File = File(File(baseFilesDir, "containers"), containerId)
    val apksDir: File = File(rootDir, "apks")
    val dataDir: File = File(rootDir, "data")
    val sdcardDir: File = File(rootDir, "sdcard")
    val metaDir: File = File(rootDir, "meta")
    private val manifestFile: File = File(metaDir, "apps.json")

    init {
        ensureLayout()
    }

    fun ensureLayout() {
        listOf(rootDir, apksDir, dataDir, sdcardDir, metaDir).forEach { dir ->
            if (!dir.exists()) {
                dir.mkdirs()
            }
        }
    }

    fun appApkDir(packageName: String): File = File(apksDir, sanitizeSegment(packageName))

    fun appDataDir(packageName: String): File = File(dataDir, sanitizeSegment(packageName))

    fun ensureAppDataSkeleton(packageName: String): File {
        val base = appDataDir(packageName)
        listOf("files", "cache", "shared_prefs", "databases").forEach { sub ->
            File(base, sub).mkdirs()
        }
        return base
    }

    fun loadApps(): List<ContainerApp> {
        ensureLayout()
        if (!manifestFile.exists()) return emptyList()
        return runCatching {
            val raw = manifestFile.readText(Charsets.UTF_8)
            if (raw.isBlank()) return emptyList()
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val apksJson = obj.optJSONArray("apkFileNames") ?: JSONArray()
                    val apkList = List(apksJson.length()) { idx -> apksJson.getString(idx) }
                    val abisJson = obj.optJSONArray("nativeAbis") ?: JSONArray()
                    val abiList = List(abisJson.length()) { idx -> abisJson.getString(idx) }
                    val source = runCatching {
                        InstallSource.valueOf(obj.optString("source", InstallSource.INSTALLED_FROM_APK.name))
                    }.getOrDefault(InstallSource.INSTALLED_FROM_APK)

                    add(
                        ContainerApp(
                            packageName = obj.getString("packageName"),
                            appName = obj.optString("appName", obj.getString("packageName")),
                            versionName = obj.optString("versionName", "1.0"),
                            versionCode = obj.optLong("versionCode", 1L),
                            source = source,
                            apkFileNames = apkList,
                            totalApkBytes = obj.optLong("totalApkBytes", 0L),
                            nativeAbis = abiList,
                            installedAtMillis = obj.optLong("installedAtMillis", 0L)
                        )
                    )
                }
            }.sortedByDescending { it.installedAtMillis }
        }.onFailure { err ->
            AppLogger.e("ContainerStorage", "Ошибка чтения apps.json", err)
        }.getOrDefault(emptyList())
    }

    fun upsertApp(app: ContainerApp) {
        val existing = loadApps().filterNot { it.packageName == app.packageName }
        saveApps(listOf(app) + existing)
    }

    fun removeApp(packageName: String): Boolean {
        val apkDeleted = deleteDirAllowingReadOnly(appApkDir(packageName))
        val dataDeleted = deleteDirAllowingReadOnly(appDataDir(packageName))
        val updated = loadApps().filterNot { it.packageName == packageName }
        saveApps(updated)
        AppLogger.i(
            "ContainerStorage",
            "Удалено из контейнера: $packageName (apks=$apkDeleted, data=$dataDeleted)"
        )
        return apkDeleted || dataDeleted
    }

    fun prepareCleanApkDir(packageName: String): File {
        val dir = appApkDir(packageName)
        if (dir.exists()) {
            deleteDirAllowingReadOnly(dir)
        }
        dir.mkdirs()
        return dir
    }

    private fun saveApps(apps: List<ContainerApp>) {
        ensureLayout()
        val array = JSONArray()
        apps.forEach { app ->
            val obj = JSONObject().apply {
                put("packageName", app.packageName)
                put("appName", app.appName)
                put("versionName", app.versionName)
                put("versionCode", app.versionCode)
                put("source", app.source.name)
                put("apkFileNames", JSONArray(app.apkFileNames))
                put("totalApkBytes", app.totalApkBytes)
                put("nativeAbis", JSONArray(app.nativeAbis))
                put("installedAtMillis", app.installedAtMillis)
            }
            array.put(obj)
        }
        val tmp = File(metaDir, "apps.json.tmp")
        tmp.writeText(array.toString(2), Charsets.UTF_8)
        if (manifestFile.exists()) manifestFile.delete()
        tmp.renameTo(manifestFile)
    }

    private fun deleteDirAllowingReadOnly(target: File): Boolean {
        if (!target.exists()) return true
        if (target.isDirectory) {
            target.listFiles()?.forEach { child ->
                deleteDirAllowingReadOnly(child)
            }
        }
        target.setWritable(true)
        return target.delete()
    }

    private fun sanitizeSegment(raw: String): String {
        val cleaned = raw.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        require(cleaned.isNotBlank() && cleaned != "." && cleaned != "..") {
            "Некорректное имя пакета: $raw"
        }
        return cleaned
    }
}
