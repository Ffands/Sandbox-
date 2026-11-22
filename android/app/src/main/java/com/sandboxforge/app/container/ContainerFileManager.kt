package com.sandboxforge.app.container

import com.sandboxforge.app.logging.AppLogger
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

data class ContainerFileItem(
    val file: File,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModifiedMillis: Long,
    val isReadOnly: Boolean
)

/**
 * Container-jailed file manager.
 * Guarantees all paths stay strictly within [containerRoot] via canonicalPath checks.
 */
class ContainerFileManager(private val containerRoot: File) {
    companion object {
        const val MAX_TEXT_PREVIEW_BYTES = 128 * 1024 // 128 KB safe cap
    }

    private val canonicalRoot: String
        get() = containerRoot.canonicalPath

    fun getRoot(): File {
        if (!containerRoot.exists()) containerRoot.mkdirs()
        return containerRoot
    }

    fun isInsideContainer(target: File): Boolean {
        val rootPath = canonicalRoot
        val targetPath = target.canonicalPath
        return targetPath == rootPath || targetPath.startsWith("$rootPath${File.separator}")
    }

    fun relativePath(target: File): String {
        if (!isInsideContainer(target)) return "/"
        val rootPath = canonicalRoot
        val targetPath = target.canonicalPath
        val rel = targetPath.removePrefix(rootPath).trimStart(File.separatorChar)
        return if (rel.isEmpty()) "/" else "/$rel"
    }

    fun parentOrRoot(current: File): File {
        val parent = current.parentFile ?: return getRoot()
        return if (isInsideContainer(parent)) parent else getRoot()
    }

    fun listDirectory(directory: File): Result<List<ContainerFileItem>> = runCatching {
        require(isInsideContainer(directory)) {
            "Доступ запрещён: путь за пределами контейнера"
        }
        require(directory.exists() && directory.isDirectory) {
            "Каталог не найден: ${directory.name}"
        }
        val children = directory.listFiles().orEmpty()
        children.map { f ->
            ContainerFileItem(
                file = f,
                name = f.name,
                isDirectory = f.isDirectory,
                sizeBytes = if (f.isDirectory) f.listFiles()?.size?.toLong() ?: 0L else f.length(),
                lastModifiedMillis = f.lastModified(),
                isReadOnly = !f.canWrite()
            )
        }.sortedWith(compareByDescending<ContainerFileItem> { it.isDirectory }.thenBy { it.name.lowercase() })
    }

    fun createFolder(parent: File, folderName: String): Result<File> = runCatching {
        val cleanName = folderName.trim()
        require(cleanName.isNotEmpty()) { "Имя папки не может быть пустым" }
        require(!cleanName.contains('/') && !cleanName.contains('\\') && cleanName != "." && cleanName != "..") {
            "Недопустимое имя папки: $cleanName"
        }
        val target = File(parent, cleanName)
        require(isInsideContainer(target)) { "Попытка выхода за пределы контейнера" }
        check(!target.exists()) { "Папка или файл «$cleanName» уже существует" }
        check(target.mkdir()) { "Не удалось создать папку «$cleanName»" }
        AppLogger.i("FileManager", "Создана папка: ${relativePath(target)}")
        target
    }

    fun readTextPreview(file: File): Result<Pair<String, Boolean>> = runCatching {
        require(isInsideContainer(file)) { "Доступ за пределы контейнера запрещён" }
        require(file.exists() && file.isFile) { "Файл не найден" }
        val totalLength = file.length()
        val toRead = minOf(totalLength, MAX_TEXT_PREVIEW_BYTES.toLong()).toInt()
        val buffer = ByteArray(toRead)
        FileInputStream(file).use { fis ->
            var offset = 0
            while (offset < toRead) {
                val read = fis.read(buffer, offset, toRead - offset)
                if (read <= 0) break
                offset += read
            }
        }
        val text = String(buffer, Charsets.UTF_8)
        val truncated = totalLength > MAX_TEXT_PREVIEW_BYTES
        AppLogger.d("FileManager", "Открыт предпросмотр файла: ${relativePath(file)} ($toRead Б)")
        text to truncated
    }

    fun exportFile(source: File, destination: OutputStream): Result<Long> = runCatching {
        require(isInsideContainer(source)) { "Доступ за пределы контейнера запрещён" }
        require(source.exists() && source.isFile) { "Файл не найден" }
        val copied = FileInputStream(source).use { input ->
            input.copyTo(destination, bufferSize = 64 * 1024)
        }
        AppLogger.i("FileManager", "Экспорт файла: ${relativePath(source)} (${formatBytes(copied)})")
        copied
    }

    fun importFile(targetDir: File, fileName: String, source: InputStream): Result<File> = runCatching {
        val safeName = fileName.substringAfterLast('/').substringAfterLast('\\').ifBlank {
            "imported_${System.currentTimeMillis()}.bin"
        }
        val target = File(targetDir, safeName)
        require(isInsideContainer(target)) { "Путь за пределами контейнера" }
        val bytes = FileOutputStream(target).use { output ->
            source.copyTo(output, bufferSize = 64 * 1024)
        }
        AppLogger.i("FileManager", "Импорт файла в контейнер: ${relativePath(target)} (${formatBytes(bytes)})")
        target
    }

    fun deleteTarget(target: File): Result<Unit> = runCatching {
        require(isInsideContainer(target)) { "Доступ за пределы контейнера запрещён" }
        require(target.canonicalPath != canonicalRoot) { "Нельзя удалить корень контейнера" }
        val rel = relativePath(target)
        check(deleteRecursivelyAllowingReadOnly(target)) { "Не удалось удалить $rel" }
        AppLogger.i("FileManager", "Удалён объект контейнера: $rel")
    }

    private fun deleteRecursivelyAllowingReadOnly(file: File): Boolean {
        if (file.isDirectory) {
            file.listFiles()?.forEach { child ->
                if (!deleteRecursivelyAllowingReadOnly(child)) return false
            }
        }
        file.setWritable(true)
        return file.delete()
    }
}
