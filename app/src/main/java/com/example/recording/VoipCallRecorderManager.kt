package com.example.recording

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.util.Log
import com.example.billing.SubscriptionEnforcementService
import com.example.billing.SubscriptionManager
import com.example.data.DialerPreferences
import com.example.data.firebase.RealtimeSyncManager
import com.example.telecom.CallManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Coordinates automatic call recording for VoIP apps:
 * - WhatsApp (`com.whatsapp`)
 * - WhatsApp Business (`com.whatsapp.w4b`)
 * - Google Meet (`com.google.android.apps.meetings` / `com.google.android.apps.tachyon`)
 * - Telegram (`org.telegram.messenger` / variants)
 * - Instagram (`com.instagram.android` / `com.instagram.lite`)
 *
 * Recordings start only when `AudioManager.mode == AudioManager.MODE_IN_COMMUNICATION`
 * alongside a recent VoIP call window or call notification, stop automatically when
 * `MODE_IN_COMMUNICATION` ends (with a 1.5s debounce for route switching), and save
 * strictly into separate File Manager folders (never inside the Call app UI).
 */
object VoipCallRecorderManager {

    private const val TAG = "VoipCallRecMgr"
    private const val RECENT_VOIP_WINDOW_MS = 180_000L
    private const val MODE_NORMAL_STOP_DEBOUNCE_MS = 1_500L
    private const val POLL_INTERVAL_MS = 1_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var initialized: Boolean = false

    @Volatile
    private var voipRecorder: CallRecorder? = null

    @Volatile
    private var lastVoipSource: CallRecordingSource? = null

    @Volatile
    private var lastVoipEventMs: Long = 0L

    @Volatile
    private var lastVoipCallHintMs: Long = 0L

    @Volatile
    private var lastVoipLabelHint: String? = null

    @Volatile
    private var activeRecordingSource: CallRecordingSource? = null

    @Volatile
    private var nonCommModeSinceMs: Long = 0L

    private var monitorJob: Job? = null
    private var modeChangedListener: Any? = null

    fun init(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        if (voipRecorder == null) {
            voipRecorder = CallRecorder(ctx)
        }
        if (initialized) return
        initialized = true

        registerAudioModeListener(ctx)

        scope.launch {
            SubscriptionEnforcementService.state.collect { state ->
                val recorder = voipRecorder ?: return@collect
                if (recorder.isRecording) {
                    val prefs = DialerPreferences(ctx)
                    val sub = SubscriptionManager(ctx)
                    val activeSrc = activeRecordingSource
                    if (activeSrc == null ||
                        !shouldRecordVoipSource(
                            prefs = prefs,
                            source = activeSrc,
                            subscriptionActive = sub.isSubscriptionActive() && !state.shouldRestrictUiToAccountTab,
                            policyAllowed = prefs.allowCallRecordingConfig && state.isRecordingAllowed,
                            adminBlocked = prefs.adminBlockedUser,
                            cellularCallActive = CallManager.uiState.value.hasActiveCall
                        )
                    ) {
                        stopRecordingIfNeeded()
                    }
                }
            }
        }

        scope.launch {
            RealtimeSyncManager.syncState.collect {
                val recorder = voipRecorder ?: return@collect
                val activeSrc = activeRecordingSource ?: return@collect
                if (recorder.isRecording) {
                    val prefs = DialerPreferences(ctx)
                    val sub = SubscriptionManager(ctx)
                    if (!shouldRecordVoipSource(
                            prefs = prefs,
                            source = activeSrc,
                            subscriptionActive = sub.isSubscriptionActive(),
                            policyAllowed = prefs.allowCallRecordingConfig,
                            adminBlocked = prefs.adminBlockedUser,
                            cellularCallActive = CallManager.uiState.value.hasActiveCall
                        )
                    ) {
                        stopRecordingIfNeeded()
                    }
                }
            }
        }
    }

    private fun registerAudioModeListener(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val listener = AudioManager.OnModeChangedListener { mode ->
            evaluateAudioMode(ctx, mode)
        }
        modeChangedListener = listener
        runCatching {
            am.addOnModeChangedListener(ctx.mainExecutor, listener)
        }
    }

    fun onVoipAppEvent(
        context: Context,
        source: CallRecordingSource,
        isCallHint: Boolean,
        labelHint: String? = null,
        nowMs: Long = System.currentTimeMillis()
    ) {
        if (source == CallRecordingSource.CELLULAR) return
        init(context)
        val ctx = context.applicationContext
        lastVoipSource = source
        lastVoipEventMs = nowMs
        if (isCallHint) {
            lastVoipCallHintMs = nowMs
        }
        if (!labelHint.isNullOrBlank()) {
            lastVoipLabelHint = labelHint.trim().take(24)
        }
        ensureMonitorLoopRunning(ctx)
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (am != null) {
            evaluateAudioMode(ctx, am.mode, nowMs)
        }
    }

    fun onCellularCallStarted() {
        stopRecordingIfNeeded()
    }

    fun stopRecordingIfNeeded(): String? {
        val recorder = voipRecorder ?: return null
        nonCommModeSinceMs = 0L
        if (!recorder.isRecording) {
            activeRecordingSource = null
            return null
        }
        val saved = recorder.stopRecording()
        activeRecordingSource = null
        Log.i(TAG, "Stopped VoIP recording cleanly (saved=${!saved.isNullOrBlank()})")
        return saved
    }

    private fun ensureMonitorLoopRunning(ctx: Context) {
        if (monitorJob?.isActive == true) return
        monitorJob = scope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                val mode = am?.mode ?: AudioManager.MODE_NORMAL
                evaluateAudioMode(ctx, mode, now)

                val recActive = voipRecorder?.isRecording == true
                val recentlySeen = (now - lastVoipEventMs) <= RECENT_VOIP_WINDOW_MS
                if (!recActive && !recentlySeen && mode != AudioManager.MODE_IN_COMMUNICATION) {
                    break
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    @Synchronized
    private fun evaluateAudioMode(
        ctx: Context,
        audioMode: Int,
        nowMs: Long = System.currentTimeMillis()
    ) {
        val recorder = voipRecorder ?: return
        val prefs = DialerPreferences(ctx)
        val sub = SubscriptionManager(ctx)
        val cellularActive = CallManager.uiState.value.hasActiveCall

        if (cellularActive) {
            if (recorder.isRecording) {
                stopRecordingIfNeeded()
            }
            return
        }

        val currentActiveSrc = activeRecordingSource
        if (recorder.isRecording && currentActiveSrc != null) {
            val stillEntitled = shouldRecordVoipSource(
                prefs = prefs,
                source = currentActiveSrc,
                subscriptionActive = sub.isSubscriptionActive() &&
                    !SubscriptionEnforcementService.shouldRestrictUi(ctx),
                policyAllowed = prefs.allowCallRecordingConfig,
                adminBlocked = prefs.adminBlockedUser,
                cellularCallActive = false
            )
            if (!stillEntitled) {
                stopRecordingIfNeeded()
                return
            }

            if (audioMode == AudioManager.MODE_IN_COMMUNICATION) {
                nonCommModeSinceMs = 0L
            } else {
                if (nonCommModeSinceMs == 0L) {
                    nonCommModeSinceMs = nowMs
                } else if (nowMs - nonCommModeSinceMs >= MODE_NORMAL_STOP_DEBOUNCE_MS) {
                    stopRecordingIfNeeded()
                }
            }
            return
        }

        if (audioMode != AudioManager.MODE_IN_COMMUNICATION) {
            nonCommModeSinceMs = 0L
            return
        }

        val candidateSource = lastVoipSource ?: return
        val withinEventWindow = (nowMs - lastVoipEventMs) <= RECENT_VOIP_WINDOW_MS ||
            (nowMs - lastVoipCallHintMs) <= RECENT_VOIP_WINDOW_MS
        if (!withinEventWindow) return

        val allowed = shouldRecordVoipSource(
            prefs = prefs,
            source = candidateSource,
            subscriptionActive = sub.isSubscriptionActive() &&
                !SubscriptionEnforcementService.shouldRestrictUi(ctx),
            policyAllowed = prefs.allowCallRecordingConfig,
            adminBlocked = prefs.adminBlockedUser,
            cellularCallActive = false
        )
        if (!allowed) return

        val label = lastVoipLabelHint?.takeIf { it.isNotBlank() } ?: candidateSource.filePrefix
        val started = recorder.startRecording(label, candidateSource)
        if (started) {
            activeRecordingSource = candidateSource
            nonCommModeSinceMs = 0L
            ensureMonitorLoopRunning(ctx)
            Log.i(TAG, "Started VoIP recording for ${candidateSource.name}")
        }
    }

    fun isActivelyRecording(): Boolean = voipRecorder?.isRecording == true

    fun shouldRecordVoipSource(
        prefs: DialerPreferences,
        source: CallRecordingSource,
        subscriptionActive: Boolean,
        policyAllowed: Boolean,
        adminBlocked: Boolean,
        cellularCallActive: Boolean
    ): Boolean {
        if (source == CallRecordingSource.CELLULAR) return false
        if (cellularCallActive) return false
        if (adminBlocked || !policyAllowed || !subscriptionActive) return false
        return prefs.isVoipSourceEnabled(source)
    }
}
