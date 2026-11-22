package com.sandboxforge.app.clone

import android.content.Context
import android.net.Uri
import android.os.Bundle
import com.sandboxforge.app.container.formatBytes
import com.sandboxforge.app.logging.AppLogger
import org.json.JSONArray
import java.io.InputStream
import java.io.OutputStream

data class BridgeFileEntry(
    val name: String,
    val relativePath: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModifiedMillis: Long,
    val isReadOnly: Boolean
)

data class BridgeListResult(
    val currentPath: String,
    val items: List<BridgeFileEntry>
)

data class BridgePreviewResult(
    val content: String,
    val truncated: Boolean,
    val fileSize: Long
)

data class BridgeClearCacheResult(
    val deletedCount: Long,
    val freedBytes: Long
)

class BridgeNeedsRebuildException(message: String) : IllegalStateException(message)

/**
 * Host-side IPC client for SandboxFileBridgeProvider running inside installed cloned apps.
 * Uses Android ContentResolver.call() for metadata/preview/delete and openInputStream/openOutputStream
 * (ParcelFileDescriptor) for zero-limit streaming export/import of saves and databases.
 */
class CloneFileBridgeClient(private val context: Context) {

    fun listDirectory(
        clonePackage: String,
        bridgeToken: String,
        rootKey: String = "internal",
        relativePath: String = ""
    ): Result<BridgeListResult> = runCatching {
        val extras = Bundle().apply {
            putString("token", bridgeToken)
            putString("root", rootKey)
            putString("path", relativePath)
        }
        val reply = callBridge(clonePackage, "list", extras)
        val currentPath = reply.getString("currentPath") ?: "/"
        val jsonStr = reply.getString("itemsJson") ?: "[]"
        val arr = JSONArray(jsonStr)
        val items = buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                add(
                    BridgeFileEntry(
                        name = obj.getString("name"),
                        relativePath = obj.getString("relativePath"),
                        isDirectory = obj.getBoolean("isDirectory"),
                        sizeBytes = obj.optLong("sizeBytes", 0L),
                        lastModifiedMillis = obj.optLong("lastModified", 0L),
                        isReadOnly = obj.optBoolean("isReadOnly", false)
                    )
                )
            }
        }
        BridgeListResult(currentPath = currentPath, items = items)
    }

    fun createFolder(
        clonePackage: String,
        bridgeToken: String,
        rootKey: String,
        parentRelativePath: String,
        folderName: String
    ): Result<Unit> = runCatching {
        val extras = Bundle().apply {
            putString("token", bridgeToken)
            putString("root", rootKey)
            putString("path", parentRelativePath)
            putString("name", folderName)
        }
        callBridge(clonePackage, "mkdir", extras)
        AppLogger.i("FileBridge", "Создана папка в клоне $clonePackage ($rootKey): $parentRelativePath/$folderName")
    }

    fun deleteTarget(
        clonePackage: String,
        bridgeToken: String,
        rootKey: String,
        relativePath: String
    ): Result<Unit> = runCatching {
        val extras = Bundle().apply {
            putString("token", bridgeToken)
            putString("root", rootKey)
            putString("path", relativePath)
        }
        callBridge(clonePackage, "delete", extras)
        AppLogger.i("FileBridge", "Удалено в клоне $clonePackage ($rootKey): $relativePath")
    }

    fun clearCache(
        clonePackage: String,
        bridgeToken: String
    ): Result<BridgeClearCacheResult> = runCatching {
        val extras = Bundle().apply {
            putString("token", bridgeToken)
        }
        val reply = callBridge(clonePackage, "clearCache", extras)
        val res = BridgeClearCacheResult(
            deletedCount = reply.getLong("deletedCount", 0L),
            freedBytes = reply.getLong("freedBytes", 0L)
        )
        AppLogger.i(
            "FileBridge",
            "Очищен кэш клона $clonePackage: удалено ${res.deletedCount} объектов, освобождено ${formatBytes(res.freedBytes)}"
        )
        res
    }

    fun readPreview(
        clonePackage: String,
        bridgeToken: String,
        rootKey: String,
        relativePath: String
    ): Result<BridgePreviewResult> = runCatching {
        val extras = Bundle().apply {
            putString("token", bridgeToken)
            putString("root", rootKey)
            putString("path", relativePath)
        }
        val reply = callBridge(clonePackage, "readPreview", extras)
        BridgePreviewResult(
            content = reply.getString("content").orEmpty(),
            truncated = reply.getBoolean("truncated", false),
            fileSize = reply.getLong("fileSize", 0L)
        )
    }

    fun exportFile(
        clonePackage: String,
        bridgeToken: String,
        rootKey: String,
        relativePath: String,
        destination: OutputStream
    ): Result<Long> = runCatching {
        val uri = buildFileUri(clonePackage, bridgeToken, rootKey, relativePath)
        val input = context.contentResolver.openInputStream(uri)
            ?: throw BridgeNeedsRebuildException("Не удалось открыть поток чтения из моста клона")
        val copied = input.use { src ->
            src.copyTo(destination, bufferSize = 64 * 1024)
        }
        AppLogger.i(
            "FileBridge",
            "Экспортирован файл из клона $clonePackage: $relativePath (${formatBytes(copied)})"
        )
        copied
    }

    fun importFile(
        clonePackage: String,
        bridgeToken: String,
        rootKey: String,
        dirRelativePath: String,
        fileName: String,
        source: InputStream
    ): Result<Long> = runCatching {
        val cleanName = fileName.replace(Regex("[/\\\\]"), "_").trim()
        require(cleanName.isNotEmpty() && cleanName != "." && cleanName != "..") {
            "Некорректное имя файла: $fileName"
        }
        val targetRel = if (dirRelativePath.isBlank() || dirRelativePath == "/") {
            cleanName
        } else {
            "${dirRelativePath.trimEnd('/')}/$cleanName"
        }
        val uri = buildFileUri(clonePackage, bridgeToken, rootKey, targetRel)
        val output = context.contentResolver.openOutputStream(uri, "wt")
            ?: throw BridgeNeedsRebuildException("Не удалось открыть поток записи в мост клона")
        val copied = output.use { dst ->
            source.copyTo(dst, bufferSize = 64 * 1024)
        }
        AppLogger.i(
            "FileBridge",
            "Загружен файл внутрь клона $clonePackage ($rootKey): $targetRel (${formatBytes(copied)})"
        )
        copied
    }

    private fun callBridge(clonePackage: String, method: String, extras: Bundle): Bundle {
        val authority = ApkManifestPatcher.fileBridgeAuthority(clonePackage)
        val uri = Uri.Builder()
            .scheme("content")
            .authority(authority)
            .build()

        val reply = try {
            context.contentResolver.call(uri, method, null, extras)
        } catch (se: SecurityException) {
            throw SecurityException("Мост клона отклонил доступ: ${se.message}", se)
        } catch (iae: IllegalArgumentException) {
            throw BridgeNeedsRebuildException(
                "Клон $clonePackage собран старой версией Shim без файлового моста. Нажми «Пересобрать клон»."
            )
        }

        if (reply == null) {
            throw BridgeNeedsRebuildException(
                "Файловый мост не найден в клоне $clonePackage. Нажми «Пересобрать клон»."
            )
        }
        if (!reply.getBoolean("ok", false)) {
            val errMsg = reply.getString("error") ?: "Неизвестная ошибка моста ($method)"
            error(errMsg)
        }
        return reply
    }

    private fun buildFileUri(
        clonePackage: String,
        bridgeToken: String,
        rootKey: String,
        relativePath: String
    ): Uri {
        val authority = ApkManifestPatcher.fileBridgeAuthority(clonePackage)
        return Uri.Builder()
            .scheme("content")
            .authority(authority)
            .appendPath("file")
            .appendQueryParameter("root", rootKey)
            .appendQueryParameter("path", relativePath)
            .appendQueryParameter("token", bridgeToken)
            .build()
    }
}
