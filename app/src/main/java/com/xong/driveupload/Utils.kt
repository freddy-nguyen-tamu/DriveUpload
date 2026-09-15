package com.xong.driveupload

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.ln
import kotlin.math.pow

object Utils {
    private const val FIVE_TB = 5_000_000_000_000L

    fun maxDriveFileBytes(): Long = FIVE_TB

    fun formatBytes(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB", "PB")
        val exponent = (ln(bytes.toDouble()) / ln(1024.0)).toInt().coerceIn(1, units.size)
        val value = bytes / 1024.0.pow(exponent.toDouble())
        return if (value >= 100) String.format(Locale.getDefault(), "%.0f %s", value, units[exponent - 1])
        else String.format(Locale.getDefault(), "%.1f %s", value, units[exponent - 1])
    }

    fun localFileMeta(resolver: ContentResolver, uri: Uri): LocalFileMeta? {
        var name = "tệp"
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
            }
        }
        if (size < 0L) {
            size = runCatching {
                resolver.openFileDescriptor(uri, "r")?.use { it.statSize }
            }.getOrNull() ?: -1L
        }
        if (size < 0L) {
            // Some document providers don't expose DISPLAY_SIZE/statSize. Count bytes once in a
            // streaming pass so even those files can still be preflight-checked without loading
            // the file into memory.
            size = runCatching {
                resolver.openInputStream(uri)?.use { input ->
                    val buffer = ByteArray(1024 * 1024)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read.toLong()
                    }
                    total
                } ?: -1L
            }.getOrDefault(-1L)
        }
        if (size < 0L) return null
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        return LocalFileMeta(uri.toString(), name, mime, size)
    }

    fun formatDriveTime(iso: String?): String {
        if (iso.isNullOrBlank()) return ""
        return runCatching {
            val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val date = parser.parse(iso) ?: Date()
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault()).format(date)
        }.getOrDefault(iso)
    }

    private val emailRegex = Regex("^[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}$", RegexOption.IGNORE_CASE)

    fun emailTokens(raw: String): List<String> = raw.split(',', ';', '\n', '\r', ' ')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinctBy { it.lowercase(Locale.US) }

    fun parseEmails(raw: String): List<String> = emailTokens(raw).filter { emailRegex.matches(it) }

    fun invalidEmails(raw: String): List<String> = emailTokens(raw).filterNot { emailRegex.matches(it) }

}
