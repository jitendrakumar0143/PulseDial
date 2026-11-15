package com.example.recording

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.example.data.DialerPreferences
import com.example.data.RecordingEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Scans both the user-selected SAF recording folder (DocumentFile) and
 * MediaStore.Audio.Media.EXTERNAL_CONTENT_URI so all call recordings appear
 * in a single vertical song-like playlist.
 */
class RecordingsRepository(private val context: Context) {

    fun hasAudioReadPermission(): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
    }

    suspend fun getRecordings(prefs: DialerPreferences): List<RecordingEntry> = withContext(Dispatchers.IO) {
        val byName = LinkedHashMap<String, RecordingEntry>()

        // 1. Scan user-selected SAF folder URI (requires no runtime storage permission)
        val folderUriStr = prefs.recordingFolderUri
        if (!folderUriStr.isNullOrBlank()) {
            val folderLabel = prefs.recordingFolderName ?: "Custom Folder"
            runCatching {
                val tree = DocumentFile.fromTreeUri(context, Uri.parse(folderUriStr))
                tree?.listFiles()?.forEach { doc ->
                    val name = doc.name ?: return@forEach
                    if (doc.isFile && (name.endsWith(".mp3", ignoreCase = true) ||
                            doc.type?.startsWith("audio/") == true) &&
                        doc.length() > 0L
                    ) {
                        val duration = extractDurationMs(doc.uri)
                        byName[name] = RecordingEntry(
                            uri = doc.uri,
                            displayName = name,
                            timestampMillis = doc.lastModified(),
                            durationMs = duration,
                            sizeBytes = doc.length(),
                            sourceLabel = folderLabel
                        )
                    }
                }
            }
        }

        // 2. Scan MediaStore.Audio.Media.EXTERNAL_CONTENT_URI for call recordings
        if (hasAudioReadPermission()) {
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.DATE_MODIFIED,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.SIZE
            )
            val selection = "${MediaStore.Audio.Media.DISPLAY_NAME} LIKE ?"
            val selectionArgs = arrayOf("Call_%")

            runCatching {
                context.contentResolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    selectionArgs,
                    "${MediaStore.Audio.Media.DATE_MODIFIED} DESC"
                )?.use { cursor ->
                    val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                    val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                    val dateIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
                    val durIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                    val sizeIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)

                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idIdx)
                        val name = cursor.getString(nameIdx) ?: continue
                        if (byName.containsKey(name)) continue
                        val contentUri = ContentUris.withAppendedId(
                            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                            id
                        )
                        val size = cursor.getLong(sizeIdx)
                        if (size <= 0L) continue
                        byName[name] = RecordingEntry(
                            uri = contentUri,
                            displayName = name,
                            timestampMillis = cursor.getLong(dateIdx) * 1000L,
                            durationMs = cursor.getLong(durIdx),
                            sizeBytes = size,
                            sourceLabel = "Music"
                        )
                    }
                }
            }
        }

        byName.values.sortedByDescending { it.timestampMillis }
    }

    private fun extractDurationMs(uri: Uri): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            runCatching { retriever.release() }
        }
    }
}
