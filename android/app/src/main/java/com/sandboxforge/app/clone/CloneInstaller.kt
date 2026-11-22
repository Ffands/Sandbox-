package com.sandboxforge.app.clone

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import com.sandboxforge.app.logging.AppLogger
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.FileInputStream

sealed class InstallEvent {
    data class UserConfirmationRequired(val confirmIntent: Intent, val clonePackage: String) : InstallEvent()
    data class Success(val clonePackage: String, val originalPackage: String) : InstallEvent()
    data class Failure(val clonePackage: String, val status: Int, val message: String) : InstallEvent()
}

object CloneInstallBus {
    private val _events = MutableSharedFlow<InstallEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<InstallEvent> = _events.asSharedFlow()

    fun emit(event: InstallEvent) {
        _events.tryEmit(event)
    }
}

class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val statusMsg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
        val clonePkg = intent.getStringExtra(CloneInstaller.EXTRA_CLONE_PACKAGE).orEmpty()
        val origPkg = intent.getStringExtra(CloneInstaller.EXTRA_ORIGINAL_PACKAGE).orEmpty()

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirmIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirmIntent != null) {
                    AppLogger.i("CloneInstaller", "Запрос подтверждения установки у пользователя: $clonePkg")
                    CloneInstallBus.emit(InstallEvent.UserConfirmationRequired(confirmIntent, clonePkg))
                } else {
                    AppLogger.e("CloneInstaller", "Отсутствует confirmIntent для $clonePkg")
                    CloneInstallBus.emit(InstallEvent.Failure(clonePkg, status, "Нет Intent подтверждения"))
                }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                AppLogger.i("CloneInstaller", "Клон успешно установлен в систему: $clonePkg")
                CloneInstallBus.emit(InstallEvent.Success(clonePkg, origPkg))
            }

            else -> {
                val readable = mapStatusName(status) + if (statusMsg.isNotBlank()) " ($statusMsg)" else ""
                AppLogger.e("CloneInstaller", "Ошибка установки $clonePkg: $readable")
                CloneInstallBus.emit(InstallEvent.Failure(clonePkg, status, readable))
            }
        }
    }

    private fun mapStatusName(status: Int): String = when (status) {
        PackageInstaller.STATUS_FAILURE_ABORTED -> "Отменено пользователем"
        PackageInstaller.STATUS_FAILURE_BLOCKED -> "Заблокировано системой"
        PackageInstaller.STATUS_FAILURE_CONFLICT -> "Конфликт пакета или провайдера"
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "Несовместимая архитектура (ABI) или SDK"
        PackageInstaller.STATUS_FAILURE_INVALID -> "Некорректный APK / подпись / выравнивание"
        PackageInstaller.STATUS_FAILURE_STORAGE -> "Недостаточно памяти"
        else -> "Ошибка установки (код $status)"
    }
}

/**
 * Streams a signed clone bundle (single APK or Split APKs) into Android's PackageInstaller.Session
 * and launches installed clones explicitly via ComponentName or LaunchIntent.
 */
class CloneInstaller(private val context: Context) {
    companion object {
        const val EXTRA_CLONE_PACKAGE = "sf_clone_package"
        const val EXTRA_ORIGINAL_PACKAGE = "sf_original_package"
    }

    fun canRequestPackageInstalls(): Boolean {
        return context.packageManager.canRequestPackageInstalls()
    }

    fun isPackageInstalled(packageName: String): Boolean {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(packageName, 0)
            }
            true
        }.getOrDefault(false)
    }

    fun commitInstallSession(bundle: BuiltCloneBundle): Result<Int> = runCatching {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(bundle.clonePackage)

        val sessionId = installer.createSession(params)
        AppLogger.i(
            "CloneInstaller",
            "Создана PackageInstaller сессия #$sessionId для ${bundle.clonePackage} " +
                "(APK: ${bundle.signedApks.size})"
        )

        installer.openSession(sessionId).use { session ->
            bundle.signedApks.forEachIndexed { index, apkFile ->
                val entryName = if (index == 0) "base.apk" else "split_$index.apk"
                session.openWrite(entryName, 0, apkFile.length()).use { out ->
                    FileInputStream(apkFile).use { input ->
                        input.copyTo(out, bufferSize = 64 * 1024)
                    }
                    session.fsync(out)
                }
            }

            // Explicit Intent + FLAG_MUTABLE is required on Android 14–17 for PackageInstaller callbacks
            val callbackIntent = Intent(context, InstallResultReceiver::class.java).apply {
                putExtra(EXTRA_CLONE_PACKAGE, bundle.clonePackage)
                putExtra(EXTRA_ORIGINAL_PACKAGE, bundle.originalPackage)
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                sessionId,
                callbackIntent,
                flags
            )
            session.commit(pendingIntent.intentSender)
        }
        sessionId
    }.onFailure { err ->
        AppLogger.e("CloneInstaller", "Сбой сессии PackageInstaller для ${bundle.clonePackage}", err)
    }

    fun launchClone(clonePackage: String, launcherActivity: String?): Result<Unit> = runCatching {
        val pm = context.packageManager
        val launchIntent = pm.getLaunchIntentForPackage(clonePackage)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } ?: if (!launcherActivity.isNullOrBlank()) {
            Intent(Intent.ACTION_MAIN).apply {
                component = ComponentName(clonePackage, launcherActivity)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            error("Не найдена стартовая Activity для $clonePackage")
        }

        AppLogger.i(
            "CloneLauncher",
            "Запуск клона: $clonePackage (component=${launchIntent.component?.className ?: "default"})"
        )
        context.startActivity(launchIntent)
    }.onFailure { err ->
        AppLogger.e("CloneLauncher", "Не удалось запустить клон $clonePackage", err)
    }
}
