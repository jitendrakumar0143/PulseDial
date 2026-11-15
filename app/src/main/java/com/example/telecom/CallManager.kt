package com.example.telecom

import android.content.Context
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.VideoProfile
import com.example.recording.CallRecorder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.lang.ref.WeakReference

data class CallUiState(
    val hasActiveCall: Boolean = false,
    val callerName: String = "",
    val callerNumber: String = "",
    val callState: Int = Call.STATE_NEW,
    val isMuted: Boolean = false,
    val isSpeakerOn: Boolean = false,
    val isRecording: Boolean = false,
    val connectTimeMillis: Long = 0L,
    val lastSavedRecordingName: String? = null
)

object CallManager {

    private val _uiState = MutableStateFlow(CallUiState())
    val uiState: StateFlow<CallUiState> = _uiState.asStateFlow()

    private var activeCall: Call? = null
    private var serviceRef: WeakReference<InCallService>? = null
    private var recorder: CallRecorder? = null

    fun attachService(service: InCallService) {
        serviceRef = WeakReference(service)
        if (recorder == null) {
            recorder = CallRecorder(service.applicationContext)
        }
    }

    fun detachService() {
        stopRecordingIfNeeded()
        serviceRef = null
    }

    fun setCurrentCall(call: Call?, callerName: String, callerNumber: String) {
        activeCall = call
        if (call == null) {
            stopRecordingIfNeeded()
            _uiState.value = CallUiState()
        } else {
            val state = getCompatState(call)
            _uiState.update {
                it.copy(
                    hasActiveCall = true,
                    callerName = callerName,
                    callerNumber = callerNumber,
                    callState = state,
                    connectTimeMillis = call.details.connectTimeMillis
                )
            }
        }
    }

    fun updateCallState(call: Call, state: Int) {
        if (call != activeCall) return
        val connectTime = call.details.connectTimeMillis
        _uiState.update {
            it.copy(
                callState = state,
                connectTimeMillis = if (connectTime > 0L) connectTime else it.connectTimeMillis
            )
        }
    }

    fun updateAudioState(isMuted: Boolean, isSpeakerOn: Boolean) {
        _uiState.update {
            it.copy(isMuted = isMuted, isSpeakerOn = isSpeakerOn)
        }
    }

    fun answerCall() {
        activeCall?.answer(VideoProfile.STATE_AUDIO_ONLY)
    }

    fun declineOrEndCall() {
        val call = activeCall ?: return
        val state = getCompatState(call)
        if (state == Call.STATE_RINGING) {
            call.reject(false, null)
        } else {
            call.disconnect()
        }
    }

    fun rejectWithQuickMessage(message: String) {
        activeCall?.reject(true, message)
    }

    fun toggleMute() {
        val service = serviceRef?.get() ?: return
        val nextMuted = !_uiState.value.isMuted
        service.setMuted(nextMuted)
        _uiState.update { it.copy(isMuted = nextMuted) }
    }

    @Suppress("DEPRECATION")
    fun toggleSpeaker() {
        val service = serviceRef?.get() ?: return
        val nextSpeaker = !_uiState.value.isSpeakerOn
        val route = if (nextSpeaker) CallAudioState.ROUTE_SPEAKER else CallAudioState.ROUTE_EARPIECE
        service.setAudioRoute(route)
        _uiState.update { it.copy(isSpeakerOn = nextSpeaker) }
    }

    fun playDtmfTone(digit: Char) {
        activeCall?.playDtmfTone(digit)
        activeCall?.stopDtmfTone()
    }

    fun toggleRecording(context: Context): Boolean {
        val mgr = recorder ?: CallRecorder(context.applicationContext).also {
            recorder = it
        }
        return if (mgr.isRecording) {
            val savedName = mgr.stopRecording()
            _uiState.update {
                it.copy(isRecording = false, lastSavedRecordingName = savedName)
            }
            false
        } else {
            val numberOrLabel = _uiState.value.callerNumber.ifBlank { _uiState.value.callerName }
            val started = mgr.startRecording(numberOrLabel)
            _uiState.update { it.copy(isRecording = started) }
            started
        }
    }

    fun startAutoRecordingIfEnabled(context: Context) {
        val mgr = recorder ?: CallRecorder(context.applicationContext).also {
            recorder = it
        }
        if (!mgr.isRecording) {
            val numberOrLabel = _uiState.value.callerNumber.ifBlank { _uiState.value.callerName }
            val started = mgr.startRecording(numberOrLabel)
            _uiState.update { it.copy(isRecording = started) }
        }
    }

    fun stopRecordingIfNeeded(): String? {
        val mgr = recorder ?: return null
        if (!mgr.isRecording) return null
        val savedName = mgr.stopRecording()
        _uiState.update {
            it.copy(isRecording = false, lastSavedRecordingName = savedName)
        }
        return savedName
    }

    @Suppress("DEPRECATION")
    private fun getCompatState(call: Call): Int {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            call.details.state
        } else {
            call.state
        }
    }
}
