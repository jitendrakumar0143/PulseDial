package com.example.recording

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaRecorder
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.example.billing.SubscriptionManager
import com.example.data.DialerPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Multi-source Call Recorder for Android 7–16+ (API 24–36).
 *
 * - Runs under CallRecorderForegroundService (microphone|phoneCall).
 * - Configures AudioManager to MODE_IN_COMMUNICATION / MODE_IN_CALL and ensures unmuted voice stream.
 * - Tries MediaRecorder.AudioSource in exact fallback order:
 *   1. VOICE_RECOGNITION (optimal on Android 10–16 with AccessibilityService)
 *   2. VOICE_COMMUNICATION
 *   3. MIC
 * - Automatically detects silent HAL streams (maxAmplitude == 0) over the initial window and switches
 *   to the next AudioSource cleanly without user-audible beeps or announcements.
 * - Exports seekable finalized AAC audio named Number_yyyyMMdd_HHmmss.mp3 to the user's SAF folder
 *   or Scoped Storage MediaStore (IS_PENDING) and triggers MediaScannerConnection.scanFile().
 */
class CallRecorder(private val context: Context) {

    private val appContext = context.applicationContext
    private val recorderLock = Any()
    private val monitorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var isRecordingActive: Boolean = false
    private var mediaRecorder: MediaRecorder? = null
    private var currentTempFile: File? = null
    private var currentTargetFolderUri: String? = null
    private var currentCreateSubfolderForSaf: Boolean = true
    private var currentFileName: String? = null
    private var currentSource: CallRecordingSource = CallRecordingSource.CELLULAR
    private var activeSessionId: Long = 0L
    private var silenceMonitorJob: Job? = null

    private var savedAudioMode: Int? = null
    private var savedVoiceStreamVolume: Int? = null

    val isRecording: Boolean
        get() = isRecordingActive

    fun getActiveSource(): CallRecordingSource? = if (isRecordingActive) currentSource else null

    fun startRecording(
        phoneNumber: String,
        source: CallRecordingSource = CallRecordingSource.CELLULAR
    ): Boolean {
        synchronized(recorderLock) {
            if (isRecordingActive) return true

            val prefs = DialerPreferences(appContext)
            if (!prefs.allowCallRecordingConfig) {
                Log.w(TAG, "Call recording disabled globally by Admin")
                return false
            }
            val subscriptionManager = SubscriptionManager(appContext)
            if (!subscriptionManager.isSubscriptionActive() ||
                com.example.billing.SubscriptionEnforcementService.shouldRestrictUi(appContext)
            ) {
                Log.w(TAG, "Trial or monthly subscription expired and user is not subscribed")
                return false
            }
            if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                Log.w(TAG, "RECORD_AUDIO permission not granted")
                return false
            }

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val fileName = buildFileName(phoneNumber, source, timestamp)
            val isCellular = (source == CallRecordingSource.CELLULAR)

            // 1. Start Android 14/15/16+ Foreground Service silently
            CallRecorderForegroundService.start(appContext, isCellularCall = isCellular)

            // 2. Configure AudioManager only for cellular calls (VoIP apps manage their own audio mode)
            if (isCellular) {
                configureAudioManagerForCall()
            }

            // 3. Try AudioSource fallback chain from index 0
            val tempFile = createTempRecordingFile()
            val startedIndex = startRecorderFromIndex(0, tempFile)
            if (startedIndex >= 0) {
                val folderTarget = prefs.resolveFolderTarget(source)
                val sessionId = System.nanoTime()
                activeSessionId = sessionId
                currentTempFile = tempFile
                currentTargetFolderUri = folderTarget.treeUri
                currentCreateSubfolderForSaf = folderTarget.createSubfolder
                currentFileName = fileName
                currentSource = source
                isRecordingActive = true

                // Monitor initial amplitude and fall back automatically if HAL outputs silence (0)
                if (startedIndex < AUDIO_SOURCE_FALLBACK_ORDER.lastIndex) {
                    launchSilenceFallbackMonitor(sessionId, startedIndex)
                }
                return true
            } else {
                if (isCellular) {
                    restoreAudioManagerState()
                }
                CallRecorderForegroundService.stop(appContext)
                runCatching { tempFile.delete() }
                cleanupHandlesLocked()
                return false
            }
        }
    }

    private fun startRecorderFromIndex(startIndex: Int, tempFile: File): Int {
        for (index in startIndex until AUDIO_SOURCE_FALLBACK_ORDER.size) {
            val source = AUDIO_SOURCE_FALLBACK_ORDER[index]
            var recorder: MediaRecorder? = null
            try {
                recorder = createMediaRecorder().apply {
                    setAudioSource(source)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioChannels(1)
                    setAudioSamplingRate(44100)
                    setAudioEncodingBitRate(128000)
                    setOutputFile(tempFile.absolutePath)
                    prepare()
                    start()
                    // Prime peak meter
                    runCatching { maxAmplitude }
                }
                mediaRecorder = recorder
                Log.i(TAG, "Recording started cleanly with AudioSource=$source (priority #$index)")
                return index
            } catch (e: Exception) {
                Log.w(TAG, "AudioSource=$source failed to prepare/start; trying next fallback", e)
                runCatching { recorder?.reset() }
                runCatching { recorder?.release() }
            }
        }
        return -1
    }

    private fun launchSilenceFallbackMonitor(sessionId: Long, currentSourceIndex: Int) {
        silenceMonitorJob?.cancel()
        silenceMonitorJob = monitorScope.launch {
            var peakAmplitude = 0
            repeat(SILENCE_CHECK_SAMPLES) {
                delay(SILENCE_CHECK_INTERVAL_MS)
                if (!isActive || !isRecordingActive || activeSessionId != sessionId) return@launch
                val sample = synchronized(recorderLock) {
                    runCatching { mediaRecorder?.maxAmplitude ?: 0 }.getOrDefault(0)
                }
                if (sample > peakAmplitude) {
                    peakAmplitude = sample
                }
                if (peakAmplitude > 0) {
                    Log.d(TAG, "Verified non-silent audio on source #${AUDIO_SOURCE_FALLBACK_ORDER[currentSourceIndex]} (peak=$peakAmplitude)")
                    return@launch
                }
            }

            if (peakAmplitude == 0 && currentSourceIndex + 1 < AUDIO_SOURCE_FALLBACK_ORDER.size) {
                Log.w(
                    TAG,
                    "Detected silent stream (peak=0) on source #${AUDIO_SOURCE_FALLBACK_ORDER[currentSourceIndex]}; " +
                        "switching to fallback #${AUDIO_SOURCE_FALLBACK_ORDER[currentSourceIndex + 1]}"
                )
                switchToNextAudioSource(sessionId, currentSourceIndex + 1)
            }
        }
    }

    private fun switchToNextAudioSource(sessionId: Long, nextSourceIndex: Int) {
        var startedNextIndex = -1
        synchronized(recorderLock) {
            if (!isRecordingActive || activeSessionId != sessionId) return
            runCatching { mediaRecorder?.stop() }
            runCatching { mediaRecorder?.reset() }
            runCatching { mediaRecorder?.release() }
            mediaRecorder = null

            val oldTemp = currentTempFile
            val nextTemp = createTempRecordingFile()
            startedNextIndex = startRecorderFromIndex(nextSourceIndex, nextTemp)
            if (startedNextIndex >= 0) {
                currentTempFile = nextTemp
                runCatching { oldTemp?.delete() }
            } else {
                // Keep old temp if fallback failed completely
                runCatching { nextTemp.delete() }
            }
        }
        if (startedNextIndex >= 0 && startedNextIndex < AUDIO_SOURCE_FALLBACK_ORDER.lastIndex) {
            launchSilenceFallbackMonitor(sessionId, startedNextIndex)
        }
    }

    private fun configureAudioManagerForCall() {
        val am = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        runCatching {
            if (savedAudioMode == null) {
                savedAudioMode = am.mode
            }
            if (am.mode != AudioManager.MODE_IN_CALL) {
                am.mode = AudioManager.MODE_IN_COMMUNICATION
            }
            if (am.isMicrophoneMute) {
                am.isMicrophoneMute = false
            }
            val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
            val currentVol = am.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
            if (maxVol > 0) {
                if (savedVoiceStreamVolume == null) {
                    savedVoiceStreamVolume = currentVol
                }
                val boostedVol = ((maxVol * 85) / 100).coerceAtLeast(1)
                if (currentVol < boostedVol) {
                    am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, boostedVol, 0)
                }
            }
        }.onFailure {
            Log.w(TAG, "Unable to adjust AudioManager settings before recording", it)
        }
    }

    private fun restoreAudioManagerState() {
        val am = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        runCatching {
            savedAudioMode?.let { prevMode ->
                if (am.mode != prevMode) {
                    am.mode = prevMode
                }
            }
            savedVoiceStreamVolume?.let { prevVol ->
                val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
                if (prevVol in 0..maxVol) {
                    am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, prevVol, 0)
                }
            }
        }
        savedAudioMode = null
        savedVoiceStreamVolume = null
    }

    private fun createTempRecordingFile(): File {
        return File(appContext.cacheDir, "rec_tmp_${System.currentTimeMillis()}.mp3")
    }

    private fun createMediaRecorder(): MediaRecorder {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(appContext)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
    }

    fun stopRecording(): String? {
        val tempFile: File?
        val fileName: String?
        val safFolderUri: String?
        val createSubfolderForSaf: Boolean
        val recordingSource: CallRecordingSource

        synchronized(recorderLock) {
            if (!isRecordingActive) return null
            silenceMonitorJob?.cancel()
            silenceMonitorJob = null
            activeSessionId = 0L

            tempFile = currentTempFile
            fileName = currentFileName
            safFolderUri = currentTargetFolderUri
            createSubfolderForSaf = currentCreateSubfolderForSaf
            recordingSource = currentSource

            try {
                mediaRecorder?.stop()
            } catch (e: RuntimeException) {
                Log.w(TAG, "MediaRecorder.stop() threw RuntimeException (very short call)", e)
            } finally {
                runCatching { mediaRecorder?.reset() }
                runCatching { mediaRecorder?.release() }
                mediaRecorder = null
                isRecordingActive = false
                if (recordingSource == CallRecordingSource.CELLULAR) {
                    restoreAudioManagerState()
                }
                CallRecorderForegroundService.stop(appContext)
            }

            currentTempFile = null
            currentTargetFolderUri = null
            currentCreateSubfolderForSaf = true
            currentFileName = null
            currentSource = CallRecordingSource.CELLULAR
        }

        var exportedName: String? = null
        try {
            if (tempFile != null && fileName != null && tempFile.exists() && tempFile.length() > 0L) {
                exportedName = exportTempRecordingToFileManager(
                    tempFile = tempFile,
                    fileName = fileName,
                    safFolderUri = safFolderUri,
                    createSubfolder = createSubfolderForSaf,
                    source = recordingSource
                )
            } else {
                Log.w(TAG, "Finalized temp recording missing or empty: size=${tempFile?.length() ?: -1L}")
            }
        } finally {
            runCatching { tempFile?.delete() }
        }
        return exportedName
    }

    private fun exportTempRecordingToFileManager(
        tempFile: File,
        fileName: String,
        safFolderUri: String?,
        createSubfolder: Boolean,
        source: CallRecordingSource
    ): String? {
        if (!safFolderUri.isNullOrBlank()) {
            val safSaved = exportToSafFolder(
                tempFile = tempFile,
                fileName = fileName,
                treeUri = Uri.parse(safFolderUri),
                source = source,
                createSubfolder = createSubfolder
            )
            if (safSaved != null) return safSaved
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            exportToScopedMediaStore(tempFile, fileName, source)
        } else {
            exportToLegacyMusicFolder(tempFile, fileName, source)
        }
    }

    private fun exportToSafFolder(
        tempFile: File,
        fileName: String,
        treeUri: Uri,
        source: CallRecordingSource,
        createSubfolder: Boolean = true
    ): String? {
        return try {
            val rootDir = DocumentFile.fromTreeUri(appContext, treeUri)
            if (rootDir == null || !rootDir.canWrite()) {
                Log.w(TAG, "Selected SAF folder not writable: $treeUri")
                return null
            }
            val targetDir = resolveSafTargetDirectory(rootDir, source, createSubfolder)

            val targetDoc = targetDir.createFile(MIME_TYPE_MP3, fileName) ?: return null
            val outStream = appContext.contentResolver.openOutputStream(targetDoc.uri, "w") ?: run {
                targetDoc.delete()
                return null
            }
            tempFile.inputStream().use { input ->
                outStream.use { output ->
                    input.copyTo(output)
                    output.flush()
                }
            }
            val resolvedName = targetDoc.name ?: fileName
            val subRel = if (targetDir != rootDir) "${source.folderName}/$resolvedName" else resolvedName
            resolvePrimaryStoragePath(treeUri, subRel)?.let { filePath ->
                MediaScannerConnection.scanFile(
                    appContext,
                    arrayOf(filePath),
                    arrayOf(MIME_TYPE_MP3),
                    null
                )
            }
            resolvedName
        } catch (e: Exception) {
            Log.e(TAG, "SAF recording export failed, falling back to MediaStore", e)
            null
        }
    }

    private fun resolvePrimaryStoragePath(treeUri: Uri, fileName: String): String? {
        if (treeUri.authority != "com.android.externalstorage.documents") return null
        val segments = treeUri.pathSegments
        val treeIndex = segments.indexOf("tree")
        val docId = if (treeIndex >= 0 && treeIndex + 1 < segments.size) segments[treeIndex + 1] else return null
        if (!docId.startsWith("primary:")) return null
        val relative = docId.removePrefix("primary:").trim('/')
        @Suppress("DEPRECATION")
        val root = Environment.getExternalStorageDirectory().absolutePath
        return if (relative.isEmpty()) "$root/$fileName" else "$root/$relative/$fileName"
    }

    private fun exportToScopedMediaStore(
        tempFile: File,
        fileName: String,
        source: CallRecordingSource
    ): String? {
        val resolver = appContext.contentResolver
        val relativePath = source.relativePublicPath()
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Audio.Media.TITLE, fileName.removeSuffix(".mp3"))
            put(MediaStore.Audio.Media.MIME_TYPE, MIME_TYPE_MP3)
            put(MediaStore.Audio.Media.RELATIVE_PATH, relativePath)
            put(MediaStore.Audio.Media.IS_MUSIC, 1)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val audioUri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            ?: return null

        return try {
            val outStream = resolver.openOutputStream(audioUri, "w")
                ?: throw IllegalStateException("Unable to open OutputStream for $audioUri")
            tempFile.inputStream().use { input ->
                outStream.use { output ->
                    input.copyTo(output)
                    output.flush()
                }
            }
            val finalizeValues = ContentValues().apply {
                put(MediaStore.Audio.Media.IS_PENDING, 0)
                put(MediaStore.Audio.Media.SIZE, tempFile.length())
            }
            resolver.update(audioUri, finalizeValues, null, null)

            queryMediaStoreFilePath(audioUri)?.let { absPath ->
                MediaScannerConnection.scanFile(
                    appContext,
                    arrayOf(absPath),
                    arrayOf(MIME_TYPE_MP3),
                    null
                )
            }
            fileName
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export recording to MediaStore", e)
            runCatching { resolver.delete(audioUri, null, null) }
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun queryMediaStoreFilePath(uri: Uri): String? {
        return runCatching {
            appContext.contentResolver.query(
                uri,
                arrayOf(MediaStore.Audio.Media.DATA),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
    }

    @Suppress("DEPRECATION")
    private fun exportToLegacyMusicFolder(
        tempFile: File,
        fileName: String,
        source: CallRecordingSource
    ): String? {
        return try {
            val musicDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                "${CallRecordingSource.ROOT_FOLDER_NAME}/${source.folderName}"
            )
            if (!musicDir.exists()) {
                musicDir.mkdirs()
            }
            val outFile = File(musicDir, fileName)
            tempFile.copyTo(outFile, overwrite = true)
            MediaScannerConnection.scanFile(
                appContext,
                arrayOf(outFile.absolutePath),
                arrayOf(MIME_TYPE_MP3),
                null
            )
            fileName
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export recording to legacy Music folder", e)
            null
        }
    }

    private fun cleanupHandlesLocked() {
        silenceMonitorJob?.cancel()
        silenceMonitorJob = null
        activeSessionId = 0L
        runCatching { mediaRecorder?.reset() }
        runCatching { mediaRecorder?.release() }
        mediaRecorder = null
        runCatching { currentTempFile?.delete() }
        currentTempFile = null
        currentTargetFolderUri = null
        currentCreateSubfolderForSaf = true
        currentFileName = null
        currentSource = CallRecordingSource.CELLULAR
        isRecordingActive = false
    }

    companion object {
        private const val TAG = "CallRecorder"
        private const val MIME_TYPE_MP3 = "audio/mpeg"
        private const val SILENCE_CHECK_INTERVAL_MS = 250L
        private const val SILENCE_CHECK_SAMPLES = 6

        private val AUDIO_SOURCE_FALLBACK_ORDER = intArrayOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.MIC
        )

        fun resolveSafTargetDirectory(
            rootDir: DocumentFile,
            source: CallRecordingSource,
            createSubfolder: Boolean
        ): DocumentFile {
            if (!createSubfolder) {
                return rootDir
            }
            val subFolderName = source.folderName
            return rootDir.findFile(subFolderName)?.takeIf { it.isDirectory }
                ?: rootDir.createDirectory(subFolderName)
                ?: rootDir
        }

        fun buildFileName(
            labelOrNumber: String,
            source: CallRecordingSource,
            timestamp: String
        ): String {
            val sanitized = labelOrNumber
                .replace(Regex("[^+0-9a-zA-Z_-]"), "_")
                .replace(Regex("_+"), "_")
                .trim('_')
                .take(24)
                .ifEmpty { "Unknown" }
            return if (source.filePrefix.isBlank()) {
                "${sanitized}_$timestamp.mp3"
            } else {
                "${source.filePrefix}_${sanitized}_$timestamp.mp3"
            }
        }
    }
}
