package com.sandboxforge.app.logging

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong

enum class LogLevel(val shortLabel: String) {
    DEBUG("DBG"),
    INFO("INF"),
    WARN("WRN"),
    ERROR("ERR"),
    CRASH("CRT")
}

data class LogEntry(
    val id: Long,
    val timestamp: String,
    val level: LogLevel,
    val tag: String,
    val message: String,
    val throwableTrace: String? = null
) {
    fun formatLine(): String {
        val base = "$timestamp [${level.shortLabel}] [$tag] $message"
        return if (throwableTrace.isNullOrBlank()) base else "$base\n$throwableTrace"
    }
}

/**
 * Thread-safe application logger with:
 * - In-memory StateFlow ring buffer for live UI log window
 * - Synchronous append to persistent session log file
 * - Uncaught exception handler writing crash dumps to disk before process termination
 */
object AppLogger {
    private const val MAX_MEMORY_ENTRIES = 2000
    private const val MAX_LOG_FILE_BYTES = 2 * 1024 * 1024L // 2 MB rotation cap

    private val idCounter = AtomicLong(0L)
    private val lock = Any()
    private val formatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault())
    private val fileDateFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(ZoneId.systemDefault())

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    private val _lastCrashSummary = MutableStateFlow<String?>(null)
    val lastCrashSummary: StateFlow<String?> = _lastCrashSummary.asStateFlow()

    @Volatile
    private var logsDir: File? = null

    @Volatile
    private var sessionLogFile: File? = null

    @Volatile
    private var crashLogFile: File? = null

    @Volatile
    private var crashHandlerInstalled = false

    fun init(baseFilesDir: File) {
        synchronized(lock) {
            val dir = File(baseFilesDir, "logs").apply { mkdirs() }
            logsDir = dir
            val sessionName = "session_${fileDateFormatter.format(Instant.now())}.log"
            sessionLogFile = File(dir, sessionName)
            crashLogFile = File(dir, "last_crash.log")

            // Keep only the 10 most recent session logs to avoid filling disk
            pruneOldLogs(dir, keepCount = 10)

            // Check if previous run ended with an uncaught crash
            val existingCrash = crashLogFile
            if (existingCrash != null && existingCrash.exists() && existingCrash.length() > 0L) {
                val crashText = runCatching { existingCrash.readText() }.getOrNull()
                if (!crashText.isNullOrBlank()) {
                    _lastCrashSummary.value = crashText
                }
            }
        }
        installCrashHandler()
        i("AppLogger", "Логгер инициализирован. Файл сессии: ${sessionLogFile?.name ?: "memory-only"}")
    }

    fun installCrashHandler() {
        if (crashHandlerInstalled) return
        synchronized(lock) {
            if (crashHandlerInstalled) return
            val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                recordCrashSync(thread.name, throwable)
                previousHandler?.uncaughtException(thread, throwable)
            }
            crashHandlerInstalled = true
        }
    }

    fun d(tag: String, message: String) = log(LogLevel.DEBUG, tag, message, null)
    fun i(tag: String, message: String) = log(LogLevel.INFO, tag, message, null)
    fun w(tag: String, message: String, error: Throwable? = null) = log(LogLevel.WARN, tag, message, error)
    fun e(tag: String, message: String, error: Throwable? = null) = log(LogLevel.ERROR, tag, message, error)

    fun log(level: LogLevel, tag: String, message: String, error: Throwable? = null) {
        val trace = error?.let { throwableToString(it) }
        val entry = LogEntry(
            id = idCounter.incrementAndGet(),
            timestamp = formatter.format(Instant.now()),
            level = level,
            tag = tag,
            message = message,
            throwableTrace = trace
        )

        synchronized(lock) {
            val current = _entries.value
            val updated = if (current.size >= MAX_MEMORY_ENTRIES) {
                current.drop(current.size - MAX_MEMORY_ENTRIES + 1) + entry
            } else {
                current + entry
            }
            _entries.value = updated
            appendToFileLocked(sessionLogFile, entry.formatLine() + "\n")
        }

        // Mirror to Android Logcat safely (ignored in local JVM unit tests)
        runCatching {
            when (level) {
                LogLevel.DEBUG -> Log.d(tag, message, error)
                LogLevel.INFO -> Log.i(tag, message, error)
                LogLevel.WARN -> Log.w(tag, message, error)
                LogLevel.ERROR, LogLevel.CRASH -> Log.e(tag, message, error)
            }
        }
    }

    fun recordCrashSync(threadName: String, throwable: Throwable) {
        val timestamp = formatter.format(Instant.now())
        val trace = throwableToString(throwable)
        val header = "=== UNCAUGHT CRASH @ $timestamp (thread: $threadName) ===\n"
        val fullDump = "$header$trace\n"

        synchronized(lock) {
            val entry = LogEntry(
                id = idCounter.incrementAndGet(),
                timestamp = timestamp,
                level = LogLevel.CRASH,
                tag = "CrashHandler",
                message = "Неперехваченное исключение в потоке $threadName",
                throwableTrace = trace
            )
            _entries.value = (_entries.value + entry).takeLast(MAX_MEMORY_ENTRIES)
            appendToFileLocked(sessionLogFile, fullDump, forceSync = true)
            crashLogFile?.let { file ->
                runCatching {
                    FileOutputStream(file, false).use { fos ->
                        fos.write(fullDump.toByteArray(Charsets.UTF_8))
                        fos.flush()
                        fos.fd.sync()
                    }
                }
            }
            _lastCrashSummary.value = fullDump
        }
    }

    fun dismissCrashBanner(deleteCrashFile: Boolean = false) {
        synchronized(lock) {
            _lastCrashSummary.value = null
            if (deleteCrashFile) {
                crashLogFile?.delete()
            }
        }
    }

    fun clearMemoryLogs() {
        synchronized(lock) {
            _entries.value = emptyList()
        }
        i("AppLogger", "Окно логов очищено (файловый лог сохранён на диске)")
    }

    fun exportAllText(filterLevel: LogLevel? = null): String {
        val snapshot = _entries.value
        val filtered = if (filterLevel == null) {
            snapshot
        } else {
            snapshot.filter { it.level.ordinal >= filterLevel.ordinal }
        }
        val crashHeader = _lastCrashSummary.value?.let {
            "--- ПОСЛЕДНИЙ КРАШ-ДАМП ---\n$it\n--- ЛОГ ТЕКУЩЕЙ СЕССИИ ---\n"
        }.orEmpty()
        return crashHeader + filtered.joinToString(separator = "\n") { it.formatLine() }
    }

    fun getCurrentSessionFile(): File? = sessionLogFile

    fun getLogsDirectory(): File? = logsDir

    /** Visible for tests to reset state cleanly */
    internal fun resetForTest() {
        synchronized(lock) {
            _entries.value = emptyList()
            _lastCrashSummary.value = null
            sessionLogFile = null
            crashLogFile = null
            logsDir = null
        }
    }

    private fun appendToFileLocked(file: File?, line: String, forceSync: Boolean = false) {
        if (file == null) return
        runCatching {
            if (file.exists() && file.length() > MAX_LOG_FILE_BYTES) {
                val rotated = File(file.parentFile, "${file.nameWithoutExtension}_prev.log")
                if (rotated.exists()) rotated.delete()
                file.renameTo(rotated)
            }
            FileOutputStream(file, true).use { fos ->
                fos.write(line.toByteArray(Charsets.UTF_8))
                fos.flush()
                if (forceSync) {
                    fos.fd.sync()
                }
            }
        }
    }

    private fun pruneOldLogs(dir: File, keepCount: Int) {
        val sessionFiles = dir.listFiles { f ->
            f.isFile && f.name.startsWith("session_") && f.name.endsWith(".log")
        } ?: return
        if (sessionFiles.size <= keepCount) return
        sessionFiles.sortedByDescending { it.lastModified() }
            .drop(keepCount)
            .forEach { it.delete() }
    }

    private fun throwableToString(t: Throwable): String {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        return sw.toString().trimEnd()
    }
}
