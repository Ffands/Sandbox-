package com.sandboxforge.app.clone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.sandboxforge.app.logging.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ActiveShimTelemetry(
    val clonePackage: String,
    val originalPackage: String,
    val pid: Int,
    val is64Bit: Boolean,
    val dataDir: String,
    val shimVersion: String,
    val shieldStatus: String,
    val bridgeReady: Boolean,
    val lastBootMillis: Long
)

object ShimTelemetryStore {
    private val _activeByOriginalPkg = MutableStateFlow<Map<String, ActiveShimTelemetry>>(emptyMap())
    val activeByOriginalPkg: StateFlow<Map<String, ActiveShimTelemetry>> = _activeByOriginalPkg.asStateFlow()

    fun record(info: ActiveShimTelemetry) {
        _activeByOriginalPkg.value = _activeByOriginalPkg.value + (info.originalPackage to info)
    }
}

/**
 * Receives live startup broadcasts from SandboxShimInitProvider inside running cloned apps.
 */
class ShimEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "com.sandboxforge.action.SHIM_EVENT") return

        val event = intent.getStringExtra("event") ?: "UNKNOWN"
        val origPkg = intent.getStringExtra("originalPackage").orEmpty()
        val clonePkg = intent.getStringExtra("clonePackage").orEmpty()
        val pid = intent.getIntExtra("pid", -1)
        val is64 = intent.getBooleanExtra("is64Bit", true)
        val api = intent.getIntExtra("androidApi", 0)
        val dataDir = intent.getStringExtra("dataDir").orEmpty()
        val shimVer = intent.getStringExtra("shimVersion").orEmpty()
        val shieldStatus = intent.getStringExtra("shieldStatus").orEmpty()
        val bridgeReady = intent.getBooleanExtra("bridgeReady", false)

        val bitness = if (is64) "64-bit" else "32-bit (Tango/ARM32)"
        val shieldPart = if (shieldStatus.isNotBlank()) " | shield=[$shieldStatus]" else ""
        val bridgePart = if (bridgeReady) " | bridge=OK" else ""
        AppLogger.i(
            "ShimBridge",
            "[$event] Клон активен: $clonePkg (оригинал: $origPkg) | PID=$pid | $bitness | API $api | shim v$shimVer$shieldPart$bridgePart | dataDir=$dataDir"
        )

        if (origPkg.isNotBlank()) {
            ShimTelemetryStore.record(
                ActiveShimTelemetry(
                    clonePackage = clonePkg,
                    originalPackage = origPkg,
                    pid = pid,
                    is64Bit = is64,
                    dataDir = dataDir,
                    shimVersion = shimVer,
                    shieldStatus = shieldStatus,
                    bridgeReady = bridgeReady,
                    lastBootMillis = System.currentTimeMillis()
                )
            )
        }
    }
}
