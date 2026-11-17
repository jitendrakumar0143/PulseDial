package com.example.data

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.CallLog
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class CallHistoryExportResult(
    val success: Boolean,
    val fileName: String = "",
    val relativePath: String = "",
    val exportedCount: Int = 0,
    val fileUri: Uri? = null,
    val errorMessage: String? = null
)

/**
 * Exports call history entries into an RFC-4180 CSV backup file inside the device's
 * public Documents directory (`Documents/PulseDial_CallHistory_yyyyMMdd_HHmmss.csv`).
 *
 * - Android 10–16+ (API 29+): Writes directly via Scoped Storage `MediaStore.Files`
 *   using `Environment.DIRECTORY_DOCUMENTS` (zero storage permissions needed).
 * - Android 7–9 (API 24–28): Writes to the public Documents folder (`Environment.DIRECTORY_DOCUMENTS`)
 *   and indexes with `MediaScannerConnection`.
 */
class CallHistoryCsvExporter(private val context: Context) {

    private val appContext = context.applicationContext

    fun needsLegacyWritePermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return false
        return ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) != PackageManager.PERMISSION_GRANTED
    }

    suspend fun exportToDocuments(
        calls: List<CallLogEntry>,
        nowMs: Long = System.currentTimeMillis()
    ): CallHistoryExportResult = withContext(Dispatchers.IO) {
        if (calls.isEmpty()) {
            return@withContext CallHistoryExportResult(
                success = false,
                errorMessage = "No call history entries to export."
            )
        }

        val fileName = buildBackupFileName(nowMs)
        val csvContent = buildCsvContent(calls)
        val csvBytes = csvContent.toByteArray(StandardCharsets.UTF_8)

        return@withContext if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            exportViaMediaStore(fileName, csvBytes, calls.size)
        } else {
            exportToLegacyDocumentsDir(fileName, csvBytes, calls.size)
        }
    }

    private fun exportViaMediaStore(
        fileName: String,
        csvBytes: ByteArray,
        count: Int
    ): CallHistoryExportResult {
        val resolver = appContext.contentResolver
        val relativeDir = Environment.DIRECTORY_DOCUMENTS
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, MIME_TYPE_CSV)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDir)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val collectionUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Files.getContentUri("external")
        }

        val itemUri = try {
            resolver.insert(collectionUri, values)
        } catch (e: Exception) {
            Log.e(TAG, "Failed inserting CSV record into MediaStore Documents", e)
            null
        } ?: return CallHistoryExportResult(
            success = false,
            errorMessage = "Unable to create CSV file in Documents folder."
        )

        return try {
            val outStream = resolver.openOutputStream(itemUri, "w")
                ?: throw IllegalStateException("Unable to open OutputStream for $itemUri")
            outStream.use { output ->
                output.write(csvBytes)
                output.flush()
            }

            val finalizeValues = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
                put(MediaStore.MediaColumns.SIZE, csvBytes.size.toLong())
            }
            resolver.update(itemUri, finalizeValues, null, null)

            CallHistoryExportResult(
                success = true,
                fileName = fileName,
                relativePath = "$relativeDir/$fileName",
                exportedCount = count,
                fileUri = itemUri
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed writing CSV bytes to MediaStore Documents", e)
            runCatching { resolver.delete(itemUri, null, null) }
            CallHistoryExportResult(
                success = false,
                errorMessage = e.localizedMessage ?: "Failed writing backup CSV file."
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun exportToLegacyDocumentsDir(
        fileName: String,
        csvBytes: ByteArray,
        count: Int
    ): CallHistoryExportResult {
        if (needsLegacyWritePermission()) {
            return CallHistoryExportResult(
                success = false,
                errorMessage = "Storage permission is required to save backup to Documents."
            )
        }
        return try {
            val docsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            if (!docsDir.exists()) {
                docsDir.mkdirs()
            }
            val outFile = File(docsDir, fileName)
            FileOutputStream(outFile).use { output ->
                output.write(csvBytes)
                output.flush()
            }
            MediaScannerConnection.scanFile(
                appContext,
                arrayOf(outFile.absolutePath),
                arrayOf(MIME_TYPE_CSV),
                null
            )
            CallHistoryExportResult(
                success = true,
                fileName = fileName,
                relativePath = "${Environment.DIRECTORY_DOCUMENTS}/$fileName",
                exportedCount = count,
                fileUri = Uri.fromFile(outFile)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed exporting CSV to legacy Documents folder", e)
            CallHistoryExportResult(
                success = false,
                errorMessage = e.localizedMessage ?: "Failed writing backup CSV file."
            )
        }
    }

    companion object {
        private const val TAG = "CallHistoryCsvExporter"
        const val MIME_TYPE_CSV = "text/csv"

        private val CSV_HEADERS = listOf(
            "ID",
            "Contact Name",
            "Phone Number",
            "Call Type",
            "Date Time",
            "Timestamp (ms)",
            "Duration (seconds)",
            "Duration (Formatted)"
        )

        fun buildBackupFileName(
            nowMs: Long = System.currentTimeMillis(),
            timeZone: TimeZone = TimeZone.getDefault()
        ): String {
            val formatter = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).apply {
                this.timeZone = timeZone
            }
            return "PulseDial_CallHistory_${formatter.format(Date(nowMs))}.csv"
        }

        fun buildCsvContent(
            calls: List<CallLogEntry>,
            timeZone: TimeZone = TimeZone.getDefault()
        ): String {
            val dateFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
                this.timeZone = timeZone
            }
            val sb = StringBuilder()
            sb.append(CSV_HEADERS.joinToString(",") { escapeCsvField(it) }).append('\n')

            for (entry in calls) {
                val formattedDate = dateFormatter.format(Date(entry.timestampMillis))
                val row = listOf(
                    entry.id.toString(),
                    entry.name.orEmpty(),
                    entry.number,
                    formatCallType(entry.type),
                    formattedDate,
                    entry.timestampMillis.toString(),
                    entry.durationSeconds.coerceAtLeast(0L).toString(),
                    formatDuration(entry.durationSeconds)
                )
                sb.append(row.joinToString(",") { escapeCsvField(it) }).append('\n')
            }
            return sb.toString()
        }

        fun escapeCsvField(value: String): String {
            val needsQuoting = value.any { ch ->
                ch == ',' || ch == '"' || ch == '\n' || ch == '\r'
            }
            if (!needsQuoting) return value
            val escaped = value.replace("\"", "\"\"")
            return "\"$escaped\""
        }

        fun formatCallType(type: Int): String = when (type) {
            CallLog.Calls.INCOMING_TYPE -> "Incoming"
            CallLog.Calls.OUTGOING_TYPE -> "Outgoing"
            CallLog.Calls.MISSED_TYPE -> "Missed"
            CallLog.Calls.REJECTED_TYPE -> "Rejected"
            CallLog.Calls.VOICEMAIL_TYPE -> "Voicemail"
            CallLog.Calls.BLOCKED_TYPE -> "Blocked"
            else -> "Other ($type)"
        }

        fun formatDuration(durationSeconds: Long): String {
            val safe = durationSeconds.coerceAtLeast(0L)
            val hours = safe / 3600L
            val minutes = (safe % 3600L) / 60L
            val seconds = safe % 60L
            return when {
                hours > 0L -> "${hours}h ${minutes}m ${seconds}s"
                minutes > 0L -> "${minutes}m ${seconds}s"
                else -> "${seconds}s"
            }
        }
    }
}
