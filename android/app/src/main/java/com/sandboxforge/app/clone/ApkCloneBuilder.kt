package com.sandboxforge.app.clone

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import com.sandboxforge.app.container.ContainerApp
import com.sandboxforge.app.container.ContainerStorage
import com.sandboxforge.app.container.formatBytes
import com.sandboxforge.app.logging.AppLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.zip.ZipFile

data class BuiltCloneBundle(
    val originalPackage: String,
    val clonePackage: String,
    val launcherActivity: String?,
    val bridgeToken: String,
    val shimVersion: String,
    val signedApks: List<File>,
    val workDir: File
)

/**
 * Builds an installable, v1/v2/v3-signed clone bundle (base.apk + all split_*.apk)
 * with SandboxShim v0.2.2 (Compatibility Shield + Live File Bridge) injected
 * and Android 15–17 16 KB native-lib ZIP alignment applied.
 */
class ApkCloneBuilder(
    private val context: Context,
    private val storage: ContainerStorage,
    private val keystoreManager: LocalKeystoreManager
) {
    companion object {
        const val CURRENT_SHIM_VERSION = "0.2.2"
        private const val BUILD_DIR_PREFIX = "clone_build_"
    }

    fun buildClone(
        app: ContainerApp,
        hideFromLauncher: Boolean = false,
        onProgress: (String) -> Unit = {}
    ): Result<BuiltCloneBundle> = runCatching {
        val srcApkDir = storage.appApkDir(app.packageName)
        val baseSrc = File(srcApkDir, "base.apk")
        require(baseSrc.exists()) { "Не найден base.apk в контейнере для ${app.packageName}" }

        val clonePackage = ApkManifestPatcher.buildClonePackageName(app.packageName, "sf.c1")
        val workDir = File(context.cacheDir, "$BUILD_DIR_PREFIX${app.packageName}").apply {
            deleteRecursively()
            mkdirs()
        }

        AppLogger.i(
            "CloneBuilder",
            "Сборка клона ${app.appName}: ${app.packageName} -> $clonePackage " +
                "(APK файлов: ${app.apkFileNames.size}, hideLauncher=$hideFromLauncher, shim=$CURRENT_SHIM_VERSION)"
        )

        val shimDexBytes = context.assets.open("sandbox_shim.dex").use { it.readBytes() }
        val signerConfig = keystoreManager.getSignerConfig()

        // Extract original developer certs (for SandboxShield signature spoofing)
        onProgress("Извлечение оригинальной подписи APK...")
        val origCertsBase64 = extractOriginalCertsBase64(baseSrc)
        val hostCertSha256 = computeHostCertSha256()
        val bridgeToken = app.bridgeToken?.takeIf { it.length >= 32 } ?: generateBridgeToken()

        AppLogger.d(
            "CloneBuilder",
            "Щит и мост: origCerts=${origCertsBase64.size}, hostCertSha256=${hostCertSha256.take(12)}..."
        )

        // 1. Patch & align base.apk
        onProgress("Патчинг AndroidManifest.xml (base.apk)...")
        val baseManifestRaw = readEntryBytes(baseSrc, "AndroidManifest.xml")
            ?: error("AndroidManifest.xml отсутствует в base.apk")
        val patchResult = ApkManifestPatcher.patchBaseManifest(
            manifestBytes = baseManifestRaw,
            clonePackage = clonePackage,
            hideFromLauncher = hideFromLauncher,
            hostPackage = context.packageName,
            neutralizePairIp = true
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
            put("hostCertSha256", hostCertSha256)
            put("bridgeToken", bridgeToken)
            put("containerId", storage.containerId)
            put("originalPackage", app.packageName)
            put("clonePackage", clonePackage)
            put("originalAppComponentFactory", patchResult.originalAppComponentFactory.orEmpty())
            put("shimVersion", CURRENT_SHIM_VERSION)
            put("spoofInstaller", "com.android.vending")
            put("shieldEnabled", true)
            put("origCertsBase64", JSONArray(origCertsBase64))
            put("pairIpDisabledCount", patchResult.disabledPairIpComponents.size)
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
                "launcher=${patchResult.launcherActivity ?: "auto"}, " +
                "pairipOff=${patchResult.disabledPairIpComponents.size})"
        )

        BuiltCloneBundle(
            originalPackage = app.packageName,
            clonePackage = clonePackage,
            launcherActivity = patchResult.launcherActivity,
            bridgeToken = bridgeToken,
            shimVersion = CURRENT_SHIM_VERSION,
            signedApks = signedList,
            workDir = workDir
        )
    }.onFailure { err ->
        AppLogger.e("CloneBuilder", "Ошибка сборки клона для ${app.packageName}", err)
        cleanupBuildCache(app.packageName)
    }

    /**
     * Deletes temporary signed APK staging folders inside cacheDir/clone_build_*
     * so large split APKs (100–300 MB) never accumulate in storage.
     */
    fun cleanupBuildCache(originalPackage: String? = null): Long {
        val targets = if (originalPackage != null) {
            listOf(File(context.cacheDir, "$BUILD_DIR_PREFIX$originalPackage"))
        } else {
            context.cacheDir.listFiles { f ->
                f.isDirectory && f.name.startsWith(BUILD_DIR_PREFIX)
            }?.toList().orEmpty()
        }

        var freedBytes = 0L
        var removedDirs = 0
        targets.forEach { dir ->
            if (dir.exists()) {
                freedBytes += calculateDirSize(dir)
                if (dir.deleteRecursively()) {
                    removedDirs++
                }
            }
        }
        if (removedDirs > 0) {
            AppLogger.d(
                "CloneBuilder",
                "Очищен временный кэш сборки ($removedDirs кат., освобождено ${formatBytes(freedBytes)})"
            )
        }
        return freedBytes
    }

    private fun extractOriginalCertsBase64(baseApk: File): List<String> {
        return runCatching {
            val result = ApkVerifier.Builder(baseApk).build().verify()
            val certs = result.signerCertificates
            if (!certs.isNullOrEmpty()) {
                certs.map { cert -> Base64.getEncoder().encodeToString(cert.encoded) }
            } else {
                emptyList()
            }
        }.onFailure { err ->
            AppLogger.w("CloneBuilder", "Не удалось извлечь оригинальную подпись из base.apk: ${err.message}")
        }.getOrDefault(emptyList())
    }

    @Suppress("DEPRECATION")
    private fun computeHostCertSha256(): String {
        return runCatching {
            val pm = context.packageManager
            val sigBytes: ByteArray? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                val si = info.signingInfo
                val sigs = if (si != null) {
                    if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
                } else null
                sigs?.firstOrNull()?.toByteArray()
            } else {
                val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
                info.signatures?.firstOrNull()?.toByteArray()
            }
            if (sigBytes == null) return@runCatching ""
            val digest = MessageDigest.getInstance("SHA-256").digest(sigBytes)
            digest.joinToString("") { "%02x".format(it) }
        }.onFailure { err ->
            AppLogger.w("CloneBuilder", "Не удалось вычислить SHA-256 сертификата хоста: ${err.message}")
        }.getOrDefault("")
    }

    private fun generateBridgeToken(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun calculateDirSize(dir: File): Long {
        if (!dir.exists()) return 0L
        if (dir.isFile) return dir.length()
        return dir.listFiles()?.sumOf { calculateDirSize(it) } ?: 0L
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
