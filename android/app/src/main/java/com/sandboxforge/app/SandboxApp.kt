package com.sandboxforge.app

import android.app.Application
import android.os.Build
import com.sandboxforge.app.logging.AppLogger

class SandboxApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLogger.init(filesDir)
        AppLogger.i(
            "SandboxApp",
            "Запуск SandboxForge v${BuildConfig.VERSION_NAME} | " +
                "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) | " +
                "Устройство: ${Build.MANUFACTURER} ${Build.MODEL} | " +
                "ABIs: ${Build.SUPPORTED_ABIS.joinToString(",")}"
        )
    }
}
