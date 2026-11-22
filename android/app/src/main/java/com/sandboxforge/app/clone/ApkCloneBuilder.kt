package com.sandboxforge.app.clone

import android.content.Context
import com.android.apksig.ApkSigner
import com.sandboxforge.app.container.ContainerApp
import com.sandboxforge.app.container.ContainerStorage
import com.sandboxforge.app.container.formatBytes
import com.sandboxforge.app.logging.AppLogger
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

data class BuiltCloneBundle(
    val originalPackage: String,
    val clonePackage: String,
    val launcherActivity: String?,
    val signedApks: List<File>
)

/**
 * Builds an installable, v1/v2/v3-signed clone bundle (base.apk + all split_*.apk)
 * with SandboxShim injected and Android 15–17 16 KB native-lib ZIP alignment applied.
 */
class ApkCloneBuilder(
    private val context: Context,
    private val storage: ContainerStorage,
    private val keystoreManager: LocalKeystoreManager
) {
    fun buildClone(
        app: ContainerApp,
        hideFromLauncher: Boolean = false,
        onProgress: (String) -> Unit = {}
    ): Result<BuiltCloneBundle> = runCatching {
        val srcApkDir = storage.appApkDir(app.packageName)
        val baseSrc = File(srcApkDir, "base.apk")
        require(baseSrc.exists()) { "Не найден base.apk в контейнере для ${app.packageName}" }

        val clonePackage = ApkManifestPatcher.buildClonePackageName(app.packageName, "sf.c1")
        val workDir = File(context.cacheDir, "clone_build_${app.packageName}").apply {
            deleteRecursively()
            mkdirs()
        }

        AppLogger.i(
            "CloneBuilder",
            "Сборка клона ${app.appName}: ${app.packageName} -> $clonePackage " +
                "(APK файлов: ${app.apkFileNames.size}, hideLauncher=$hideFromLauncher)"
        )

        val shimDexBytes = context.assets.open("sandbox_shim.dex").use { it.readBytes() }
        val signerConfig = keystoreManager.getSignerConfig()

        // 1. Patch & align base.apk
        onProgress("Патчинг AndroidManifest.xml (base.apk)...")
        val baseManifestRaw = readEntryBytes(baseSrc, "AndroidManifest.xml")
            ?: error("AndroidManifest.xml отсутствует в base.apk")
        val patchResult = ApkManifestPatcher.patchBaseManifest(
            manifestBytes = baseManifestRaw,
            clonePackage = clonePackage,
            hideFromLauncher = hideFromLauncher
        )

        if (patchResult.strippedSharedUserId != null) {
            AppLogger.w(
                "CloneBuilder",
                "Удалён sharedUserId=${patchResult.strippedSharedUserId} для совместимости установки"
            )
        }

        val nextDexName = findNextDexFileName(baseSrc)
        val shimConfigJson = JSONObject().apply {
            put("hostPackage", context.packageName)
            put("containerId", storage.containerId)
            put("originalPackage", app.packageName)
            put("clonePackage", clonePackage)
            put("originalAppComponentFactory", patchResult.originalAppComponentFactory.orEmpty())
            put("shimVersion", "0.2.0")
        }.toString(2).toByteArray(Charsets.UTF_8)

        onProgress("Упаковка и 16 КБ выравнивание base.apk...")
        val alignedBase = File(workDir, "base_aligned.apk")
        ApkZipAligner.repackAndAlign(
            sourceApk = baseSrc,
            outputApk = alignedBase,
            replacements = mapOf("AndroidManifest.xml" to patchResult.patchedBytes),
            additions = mapOf(
                nextDexName to shimDexBytes,
                "assets/sandbox_config.json" to shimConfigJson
            )
        )

        onProgress("Подпись base.apk (APK Sig v1/v2/v3)...")
        val signedBase = File(workDir, "base_signed.apk")
        signApk(alignedBase, signedBase, signerConfig)
        alignedBase.delete()
        AppLogger.d(
            "CloneBuilder",
            "Готов base_signed.apk (${formatBytes(signedBase.length())}), shim=$nextDexName"
        )

        val signedList = mutableListOf(signedBase)

        // 2. Patch package name, 16 KB align, and sign EVERY split APK with the SAME key
        val splitFiles = app.apkFileNames
            .filter { it != "base.apk" }
            .map { File(srcApkDir, it) }
            .filter { it.exists() }

        splitFiles.forEachIndexed { idx, splitSrc ->
            onProgress("Сплит ${idx + 1}/${splitFiles.size}: ${splitSrc.name}...")
            val splitManifestRaw = readEntryBytes(splitSrc, "AndroidManifest.xml")
            val replacements = if (splitManifestRaw != null) {
                mapOf(
                    "AndroidManifest.xml" to ApkManifestPatcher.patchSplitManifest(
                        splitManifestRaw,
                        clonePackage
                    )
                )
            } else {
                emptyMap()
            }

            val alignedSplit = File(workDir, "split_${idx}_aligned.apk")
            ApkZipAligner.repackAndAlign(
                sourceApk = splitSrc,
                outputApk = alignedSplit,
                replacements = replacements
            )
            val signedSplit = File(workDir, "split_${idx}_signed.apk")
            signApk(alignedSplit, signedSplit, signerConfig)
            alignedSplit.delete()
            signedList.add(signedSplit)
            AppLogger.d(
                "CloneBuilder",
                "Готов сплит ${splitSrc.name} (${formatBytes(signedSplit.length())})"
            )
        }

        AppLogger.i(
            "CloneBuilder",
            "Клон собран и подписан: $clonePackage (файлов: ${signedList.size}, " +
                "launcher=${patchResult.launcherActivity ?: "auto"})"
        )

        BuiltCloneBundle(
            originalPackage = app.packageName,
            clonePackage = clonePackage,
            launcherActivity = patchResult.launcherActivity,
            signedApks = signedList
        )
    }.onFailure { err ->
        AppLogger.e("CloneBuilder", "Ошибка сборки клона для ${app.packageName}", err)
    }

    private fun signApk(inputApk: File, outputApk: File, signerConfig: ApkSigner.SignerConfig) {
        val signer = ApkSigner.Builder(listOf(signerConfig))
            .setInputApk(inputApk)
            .setOutputApk(outputApk)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .setMinSdkVersion(26)
            .build()
        signer.sign()
    }

    private fun readEntryBytes(apk: File, entryName: String): ByteArray? {
        ZipFile(apk).use { zip ->
            val entry = zip.getEntry(entryName) ?: return null
            return zip.getInputStream(entry).use { it.readBytes() }
        }
    }

    private fun findNextDexFileName(apk: File): String {
        val dexNumbers = hashSetOf<Int>()
        ZipFile(apk).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val name = entries.nextElement().name
                if (name == "classes.dex") {
                    dexNumbers.add(1)
                } else if (name.startsWith("classes") && name.endsWith(".dex")) {
                    val num = name.removePrefix("classes").removeSuffix(".dex").toIntOrNull()
                    if (num != null) dexNumbers.add(num)
                }
            }
        }
        var next = 2
        while (dexNumbers.contains(next)) {
            next++
        }
        return "classes$next.dex"
    }
}
