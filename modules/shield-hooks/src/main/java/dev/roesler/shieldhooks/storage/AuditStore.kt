package dev.roesler.shieldhooks.storage

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class AuditEntry(
    val timestamp: Long,
    val level: String,
    val source: String,
    val message: String,
) {
    fun displayTime(): String =
        TIME_FORMAT.get()?.format(Date(timestamp)) ?: timestamp.toString()

    companion object {
        private val TIME_FORMAT = ThreadLocal.withInitial {
            SimpleDateFormat("HH:mm:ss", Locale.US)
        }
    }
}

object AuditStore {
    private const val DIRECTORY = "audit"
    private const val FILE_NAME = "shield-hooks.log"
    private const val ROTATED_FILE_NAME = "shield-hooks.1.log"
    private const val MAX_BYTES = 512 * 1024L
    private const val MAX_FIELD_LENGTH = 480
    private const val MAX_VISIBLE_ENTRIES = 160
    private val lock = Any()
    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

    fun append(context: Context, level: String, source: String, message: String) {
        val entry = listOf(
            System.currentTimeMillis().toString(),
            sanitize(level),
            sanitize(source),
            sanitize(message),
        ).joinToString("\t") + "\n"

        synchronized(lock) {
            val file = logFile(context)
            if (file.exists() && file.length() + entry.toByteArray().size > MAX_BYTES) {
                val rotated = File(file.parentFile, ROTATED_FILE_NAME)
                if (rotated.exists()) rotated.delete()
                file.renameTo(rotated)
            }
            file.appendText(entry, Charsets.UTF_8)
            _revision.value += 1L
        }
    }

    fun readRecent(context: Context, limit: Int = MAX_VISIBLE_ENTRIES): List<AuditEntry> =
        synchronized(lock) {
            val file = logFile(context)
            if (!file.exists()) return@synchronized emptyList()
            file.readLines(Charsets.UTF_8)
                .takeLast(limit.coerceIn(1, MAX_VISIBLE_ENTRIES))
                .mapNotNull(::parse)
                .reversed()
        }

    fun clear(context: Context) {
        synchronized(lock) {
            val directory = File(context.filesDir, DIRECTORY)
            File(directory, FILE_NAME).delete()
            File(directory, ROTATED_FILE_NAME).delete()
            _revision.value += 1L
        }
    }

    private fun logFile(context: Context): File {
        val directory = File(context.filesDir, DIRECTORY)
        if (!directory.exists()) directory.mkdirs()
        return File(directory, FILE_NAME)
    }

    private fun parse(line: String): AuditEntry? {
        val fields = line.split('\t', limit = 4)
        if (fields.size != 4) return null
        return AuditEntry(
            timestamp = fields[0].toLongOrNull() ?: return null,
            level = fields[1],
            source = fields[2],
            message = fields[3],
        )
    }

    private fun sanitize(value: String): String =
        value.replace('\t', ' ')
            .replace('\r', ' ')
            .replace('\n', ' ')
            .take(MAX_FIELD_LENGTH)
}
