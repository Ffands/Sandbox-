package com.sandboxforge.app.clone

import java.io.File
import java.util.zip.ZipFile

enum class EngineMode(val displayName: String, val shortTag: String) {
    MOD_SHIM("Мод-контейнер (SandboxShim)", "SHIM"),
    CLEAN_PROFILE("Чистый профиль (Банки / Защита)", "CLEAN")
}

data class EngineRecommendation(
    val recommendedMode: EngineMode,
    val reason: String,
    val signals: List<String>
)

/**
 * Inspects an APK (or split set) on ANY Android device and recommends whether it should run
 * under the Mod-Shim engine (time/memory/modules/files) or the Clean Native Profile
 * (strict banking, Play Integrity, heavy native anti-tamper).
 * Users can always override the engine manually.
 */
object EngineAdvisor {
    private val bankingPackageKeywords = listOf(
        "sberbank", "tinkoff", "tbank", "vtb", "alfabank", "raiffeisen", "gazprombank",
        "sovcombank", "psbank", "yoomoney", "qiwi", "gosuslugi", "nalog", "bank", "wallet",
        "pay.", ".pay", "finance"
    )

    private val heavyProtectionLibs = mapOf(
        "libpairipcore.so" to "Google Play PairIP (проверка подписи)",
        "libDexHelper.so" to "SecNeo / Bangcle защита",
        "libjiagu" to "360 Jiagu упаковщик",
        "libexec.so" to "Нативный упаковщик DEX",
        "libil2cpp.so" to "Unity IL2CPP (подходит для мод-контейнера и памяти)"
    )

    fun analyze(
        packageName: String,
        apkFiles: List<File>,
        nativeAbis: List<String>
    ): EngineRecommendation {
        val pkgLower = packageName.lowercase()
        val signals = mutableListOf<String>()
        var forceClean = false

        if (bankingPackageKeywords.any { pkgLower.contains(it) }) {
            forceClean = true
            signals.add("Пакет похож на банковский / финтех / госсервис")
        }

        val foundLibs = linkedSetOf<String>()
        apkFiles.filter { it.exists() && it.isFile }.forEach { apk ->
            runCatching {
                ZipFile(apk).use { zip ->
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val name = entries.nextElement().name
                        if (name.startsWith("lib/") && name.endsWith(".so")) {
                            val fileName = name.substringAfterLast('/')
                            heavyProtectionLibs.forEach { (needle, label) ->
                                if (fileName.contains(needle, ignoreCase = true)) {
                                    foundLibs.add(label)
                                    if (needle != "libil2cpp.so") {
                                        forceClean = true
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        signals.addAll(foundLibs)

        if (nativeAbis.isEmpty()) {
            signals.add("Pure DEX/Java — высокая совместимость со всеми устройствами")
        } else if (nativeAbis.contains("armeabi-v7a") && !nativeAbis.contains("arm64-v8a")) {
            signals.add("Только 32-bit (armeabi-v7a) — на 64-bit устройствах требует системного моста/Tango")
        }

        return if (forceClean) {
            EngineRecommendation(
                recommendedMode = EngineMode.CLEAN_PROFILE,
                reason = "Обнаружены признаки банковской/анти-тампер защиты: переподпись может заблокировать вход. Рекомендуется Чистый профиль (Shizuku).",
                signals = signals
            )
        } else {
            EngineRecommendation(
                recommendedMode = EngineMode.MOD_SHIM,
                reason = "Обычное или игровое приложение без жёсткой привязки к аппаратной подписи. Подходит для Мод-контейнера с SandboxShim.",
                signals = signals.ifEmpty { listOf("Стандартный APK без жёсткого анти-тампера") }
            )
        }
    }
}
