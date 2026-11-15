package com.example.recording

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import com.example.data.RecordingEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class AudioPlaybackState(
    val activeUri: Uri? = null,
    val title: String = "",
    val isPlaying: Boolean = false,
    val positionMs: Int = 0,
    val durationMs: Int = 0
)

class RecordingAudioPlayer(private val context: Context) {

    private var mediaPlayer: MediaPlayer? = null

    private val _playbackState = MutableStateFlow(AudioPlaybackState())
    val playbackState: StateFlow<AudioPlaybackState> = _playbackState.asStateFlow()

    fun togglePlayPause(entry: RecordingEntry) {
        val current = _playbackState.value
        if (current.activeUri == entry.uri) {
            val mp = mediaPlayer
            if (mp != null) {
                if (mp.isPlaying) {
                    mp.pause()
                    _playbackState.update { it.copy(isPlaying = false, positionMs = mp.currentPosition) }
                } else {
                    mp.start()
                    _playbackState.update { it.copy(isPlaying = true) }
                }
                return
            }
        }

        stop()
        try {
            val mp = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(context, entry.uri)
                prepare()
                start()
            }
            mp.setOnCompletionListener {
                _playbackState.update { it.copy(isPlaying = false, positionMs = 0) }
                runCatching { mp.seekTo(0) }
            }
            mediaPlayer = mp
            _playbackState.value = AudioPlaybackState(
                activeUri = entry.uri,
                title = entry.displayName.removeSuffix(".mp3"),
                isPlaying = true,
                positionMs = 0,
                durationMs = mp.duration.coerceAtLeast(entry.durationMs.toInt())
            )
        } catch (_: Exception) {
            stop()
        }
    }

    fun toggleActivePlayPause() {
        val mp = mediaPlayer ?: return
        if (mp.isPlaying) {
            mp.pause()
            _playbackState.update { it.copy(isPlaying = false, positionMs = mp.currentPosition) }
        } else {
            mp.start()
            _playbackState.update { it.copy(isPlaying = true) }
        }
    }

    fun refreshProgress() {
        val mp = mediaPlayer ?: return
        if (mp.isPlaying) {
            _playbackState.update {
                it.copy(
                    positionMs = mp.currentPosition,
                    durationMs = mp.duration.coerceAtLeast(it.durationMs)
                )
            }
        }
    }

    fun stop() {
        runCatching { mediaPlayer?.stop() }
        runCatching { mediaPlayer?.release() }
        mediaPlayer = null
        _playbackState.value = AudioPlaybackState()
    }
}
