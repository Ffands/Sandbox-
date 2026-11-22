package com.sandboxforge.app.ui

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.sandboxforge.app.logging.AppLogger
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Extracts, downscales (96x96 PNG), caches on disk inside the container meta directory,
 * and serves app icons for both host device apps and container apps.
 */
class AppIconCache(
    private val context: Context,
    private val iconsDir: File
) {
    private val memoryCache = ConcurrentHashMap<String, ImageBitmap>()

    init {
        if (!iconsDir.exists()) iconsDir.mkdirs()
    }

    fun iconFileFor(packageName: String): File {
        val safe = packageName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return File(iconsDir, "$safe.png")
    }

    fun saveIconFromInstalledPackage(packageName: String) {
        runCatching {
            val drawable = context.packageManager.getApplicationIcon(packageName)
            saveDrawable(packageName, drawable)
        }.onFailure { err ->
            AppLogger.d("IconCache", "Не удалось извлечь иконку $packageName: ${err.message}")
        }
    }

    fun saveIconFromApkFile(packageName: String, apkFile: File) {
        runCatching {
            val pm = context.packageManager
            @Suppress("DEPRECATION")
            val pkgInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.PackageInfoFlags.of(0L))
            } else {
                pm.getPackageArchiveInfo(apkFile.absolutePath, 0)
            } ?: return
            val appInfo = pkgInfo.applicationInfo ?: return
            appInfo.sourceDir = apkFile.absolutePath
            appInfo.publicSourceDir = apkFile.absolutePath
            val drawable = pm.getApplicationIcon(appInfo)
            saveDrawable(packageName, drawable)
        }.onFailure { err ->
            AppLogger.d("IconCache", "Не удалось извлечь иконку из APK $packageName: ${err.message}")
        }
    }

    fun loadCachedIcon(packageName: String): ImageBitmap? {
        memoryCache[packageName]?.let { return it }
        val file = iconFileFor(packageName)
        if (!file.exists()) return null
        return runCatching {
            val bmp = BitmapFactory.decodeFile(file.absolutePath) ?: return null
            val image = bmp.asImageBitmap()
            memoryCache[packageName] = image
            image
        }.getOrNull()
    }

    fun loadHostAppIcon(packageName: String): ImageBitmap? {
        memoryCache["host:$packageName"]?.let { return it }
        return runCatching {
            val drawable = context.packageManager.getApplicationIcon(packageName)
            val bmp = drawableToBitmap(drawable, sizePx = 96)
            val image = bmp.asImageBitmap()
            memoryCache["host:$packageName"] = image
            image
        }.getOrNull()
    }

    fun deleteIcon(packageName: String) {
        memoryCache.remove(packageName)
        iconFileFor(packageName).delete()
    }

    private fun saveDrawable(packageName: String, drawable: Drawable) {
        val bmp = drawableToBitmap(drawable, sizePx = 96)
        val outFile = iconFileFor(packageName)
        FileOutputStream(outFile).use { fos ->
            bmp.compress(Bitmap.CompressFormat.PNG, 92, fos)
        }
        memoryCache[packageName] = bmp.asImageBitmap()
    }

    private fun drawableToBitmap(drawable: Drawable, sizePx: Int): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            return Bitmap.createScaledBitmap(drawable.bitmap, sizePx, sizePx, true)
        }
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bmp
    }
}
