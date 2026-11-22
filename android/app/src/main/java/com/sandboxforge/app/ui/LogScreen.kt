package com.sandboxforge.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sandboxforge.app.logging.AppLogger
import com.sandboxforge.app.logging.LogEntry
import com.sandboxforge.app.logging.LogLevel
import com.sandboxforge.app.ui.theme.AccentCyan
import com.sandboxforge.app.ui.theme.BorderDark
import com.sandboxforge.app.ui.theme.StatusAmber
import com.sandboxforge.app.ui.theme.StatusGreen
import com.sandboxforge.app.ui.theme.StatusRed
import com.sandboxforge.app.ui.theme.SurfaceDark
import com.sandboxforge.app.ui.theme.SurfaceElevated
import com.sandboxforge.app.ui.theme.TextMuted

@Composable
fun LogScreen(
    onSaveLogsToUri: (Uri, String) -> Unit,
    onNotify: (String) -> Unit
) {
    val entries by AppLogger.entries.collectAsState()
    val lastCrash by AppLogger.lastCrashSummary.collectAsState()
    var minLevel by remember { mutableStateOf<LogLevel?>(null) }
    val clipboardManager = LocalClipboardManager.current
    val listState = rememberLazyListState()

    val visibleEntries = remember(entries, minLevel) {
        val level = minLevel
        if (level == null) entries else entries.filter { it.level.ordinal >= level.ordinal }
    }

    val saveLogLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            onSaveLogsToUri(uri, AppLogger.exportAllText(minLevel))
        }
    }

    LaunchedEffect(visibleEntries.size) {
        if (visibleEntries.isNotEmpty()) {
            listState.animateScrollToItem(visibleEntries.lastIndex)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // Crash recovery banner if previous session crashed
        if (!lastCrash.isNullOrBlank()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF2B1418)),
                border = BorderStroke(1.dp, StatusRed),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.ErrorOutline,
                            contentDescription = null,
                            tint = StatusRed,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Обнаружен краш-лог предыдущего запуска",
                            style = MaterialTheme.typography.titleSmall,
                            color = StatusRed,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = lastCrash.orEmpty().lines().take(5).joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = {
                                clipboardManager.setText(AnnotatedString(lastCrash.orEmpty()))
                                onNotify("Краш-лог скопирован в буфер")
                            }
                        ) {
                            Text("Копировать краш")
                        }
                        TextButton(onClick = { AppLogger.dismissCrashBanner(deleteCrashFile = true) }) {
                            Text("Скрыть и очистить")
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
        }

        // Actions bar
        Card(
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            border = BorderStroke(1.dp, BorderDark),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Button(
                        onClick = {
                            val text = AppLogger.exportAllText(minLevel)
                            clipboardManager.setText(AnnotatedString(text))
                            onNotify("Лог скопирован (${visibleEntries.size} строк)")
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Копировать")
                    }
                    OutlinedButton(
                        onClick = {
                            val defaultName = AppLogger.getCurrentSessionFile()?.name ?: "sandboxforge_log.txt"
                            saveLogLauncher.launch(defaultName)
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Save,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("В файл")
                    }
                    IconButton(
                        onClick = {
                            AppLogger.w("LogTest", "Тестовое событие проверки записи лога и буфера")
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.BugReport,
                            contentDescription = "Тест лога",
                            tint = AccentCyan
                        )
                    }
                    IconButton(onClick = { AppLogger.clearMemoryLogs() }) {
                        Icon(
                            imageVector = Icons.Outlined.Delete,
                            contentDescription = "Очистить окно",
                            tint = TextMuted
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = minLevel == null,
                        onClick = { minLevel = null },
                        label = { Text("Все (${entries.size})") }
                    )
                    FilterChip(
                        selected = minLevel == LogLevel.INFO,
                        onClick = { minLevel = LogLevel.INFO },
                        label = { Text("INFO+") }
                    )
                    FilterChip(
                        selected = minLevel == LogLevel.WARN,
                        onClick = { minLevel = LogLevel.WARN },
                        label = { Text("WARN+") }
                    )
                    FilterChip(
                        selected = minLevel == LogLevel.ERROR,
                        onClick = { minLevel = LogLevel.ERROR },
                        label = { Text("ERR") }
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Автозапись на диск: ${AppLogger.getCurrentSessionFile()?.name ?: "—"} (краши → last_crash.log)",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = TextMuted
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Scrollable console output
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(SurfaceElevated, RoundedCornerShape(10.dp))
                .padding(8.dp)
        ) {
            if (visibleEntries.isEmpty()) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Text("Нет записей по выбранному фильтру", color = TextMuted)
                }
            } else {
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = PaddingValues(4.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(visibleEntries, key = { it.id }) { entry ->
                        LogLineItem(entry)
                    }
                }
            }
        }
    }
}

@Composable
private fun LogLineItem(entry: LogEntry) {
    val accent = when (entry.level) {
        LogLevel.DEBUG -> TextMuted
        LogLevel.INFO -> StatusGreen
        LogLevel.WARN -> StatusAmber
        LogLevel.ERROR, LogLevel.CRASH -> StatusRed
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "${entry.timestamp.substringAfter(' ')} [${entry.level.shortLabel}] [${entry.tag}] ${entry.message}",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            color = accent
        )
        if (!entry.throwableTrace.isNullOrBlank()) {
            Text(
                text = entry.throwableTrace,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                lineHeight = 14.sp,
                color = StatusRed,
                modifier = Modifier.padding(start = 8.dp, top = 2.dp)
            )
        }
    }
}
