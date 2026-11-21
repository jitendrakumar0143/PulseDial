package com.example.ui.incall

import android.Manifest
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.TelecomManager
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.R
import com.example.billing.SubscriptionEnforcementService
import com.example.billing.SubscriptionManager
import com.example.data.DialerPreferences
import com.example.data.firebase.RealtimeSyncManager
import com.example.databinding.ActivityInCallBinding
import com.example.telecom.CallManager
import com.example.telecom.CallUiState
import com.example.ui.dialpad.DialpadBottomSheetFragment
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class InCallActivity : AppCompatActivity() {

    private lateinit var binding: ActivityInCallBinding
    private lateinit var prefs: DialerPreferences
    private lateinit var subscriptionManager: SubscriptionManager
    internal lateinit var proximityScreenLockController: ProximityScreenLockController

    private var toneGenerator: ToneGenerator? = null
    private var isKeypadVisible = false
    private var isAudioOutputSheetOpen = false
    private var isQuickReplyTrayOpen = false
    private var isRingerSilenced = false
    private var isMinimizedToTopBar = false
    private var isTopDrawerOpen = false
    private var isDismissingInCall = false
    private var isCallerPopupDismissed = false
    private var dtmfDigitsEntered: String = ""
    private var lastPopupCallerKey = ""
    private var lastRenderedCallState: Int = Call.STATE_NEW
    private var hasPlayedShowcaseEntrance = false
    private var lastRecordingActive = false
    private var recordingStartTimeMs: Long = 0L

    private var incomingShimmerAnimator: ObjectAnimator? = null
    private var recPulseAnimator: ObjectAnimator? = null
    private var speakerRippleAnimator: AnimatorSet? = null
    private var endCallGlowAnimator: AnimatorSet? = null

    private val recordingPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val recordGranted = results[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (recordGranted) {
            toggleCallRecording()
        } else {
            Toast.makeText(this, R.string.incall_recording_failed, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityInCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = DialerPreferences(this)
        subscriptionManager = SubscriptionManager(this)
        if (prefs.dialpadTonesEnabled) {
            toneGenerator = runCatching {
                ToneGenerator(AudioManager.STREAM_VOICE_CALL, 70)
            }.getOrNull()
        }

        val density = resources.displayMetrics.density
        ViewCompat.setOnApplyWindowInsetsListener(binding.incallRoot) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val targetTopPad = bars.top + (8f * density).toInt()
            val targetBottomPad = bars.bottom + (14f * density).toInt()
            if (binding.fullScreenCallContainer.paddingTop != targetTopPad ||
                binding.fullScreenCallContainer.paddingBottom != targetBottomPad
            ) {
                binding.fullScreenCallContainer.updatePadding(
                    top = targetTopPad,
                    bottom = targetBottomPad
                )
            }
            val targetPillTopMargin = bars.top + (6f * density).toInt()
            val pillLp = binding.floating3dCallPill.layoutParams as? ViewGroup.MarginLayoutParams
            if (pillLp != null && pillLp.topMargin != targetPillTopMargin) {
                binding.floating3dCallPill.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    topMargin = targetPillTopMargin
                }
            }
            insets
        }

        proximityScreenLockController = ProximityScreenLockController(
            context = this,
            onUiLockChanged = { isLocked ->
                applyProximityUiLock(isLocked)
            },
            onSpeakerChanged = { speakerOn ->
                applyProximitySpeakerStatus(speakerOn)
            }
        )

        setupControls()
        setupSidewaysDragDock()
        setupFloating3DCallPill()
        setupTopPullDownDrawer()
        populateQuickReplyTray()
        startEndCallBreathingGlow()
        observeCallState()
        observeRealtimePolicy()
        startDurationTicker()
        playCallerShowcase3DEntrance()
    }

    override fun onResume() {
        super.onResume()
        if (::proximityScreenLockController.isInitialized) {
            val state = CallManager.uiState.value
            proximityScreenLockController.updateCallState(
                hasActiveCall = state.hasActiveCall,
                callState = state.callState,
                audioRoute = state.audioRoute,
                isSpeakerOn = state.isSpeakerOn
            )
        }
    }

    override fun onStop() {
        super.onStop()
        if (::proximityScreenLockController.isInitialized &&
            (!CallManager.uiState.value.hasActiveCall || isFinishing)
        ) {
            proximityScreenLockController.stopListening(waitForNoProximity = false)
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (::proximityScreenLockController.isInitialized && proximityScreenLockController.isNearEarLocked) {
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun applyProximityUiLock(isLocked: Boolean) {
        if (!::binding.isInitialized || isDestroyed) return
        binding.viewProximityLockOverlay.visibility = if (isLocked) View.VISIBLE else View.GONE
        runCatching {
            val lp = window.attributes
            val desiredBrightness = if (isLocked) {
                0.01f
            } else {
                WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
            if (lp.screenBrightness != desiredBrightness) {
                lp.screenBrightness = desiredBrightness
                window.attributes = lp
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun applyProximitySpeakerStatus(speakerOn: Boolean) {
        if (isFinishing || isDestroyed) return
        val state = CallManager.uiState.value
        val isExternalHeadset =
            (state.audioRoute and (CallAudioState.ROUTE_BLUETOOTH or CallAudioState.ROUTE_WIRED_HEADSET)) != 0
        if (!state.hasActiveCall || isExternalHeadset) return

        CallManager.setSpeakerEnabled(speakerOn)

        // Fallback hardware speaker routing via AudioManager when InCallService is not bound
        if (!CallManager.isServiceAttached()) {
            runCatching {
                val am = getSystemService(AudioManager::class.java) ?: return@runCatching
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (speakerOn) {
                        val speakerDevice = am.availableCommunicationDevices
                            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                        if (speakerDevice != null) {
                            am.setCommunicationDevice(speakerDevice)
                        } else {
                            am.isSpeakerphoneOn = true
                        }
                    } else {
                        am.clearCommunicationDevice()
                        am.isSpeakerphoneOn = false
                    }
                } else {
                    am.isSpeakerphoneOn = speakerOn
                }
            }
        }
    }

    override fun onDestroy() {
        if (::proximityScreenLockController.isInitialized) {
            proximityScreenLockController.release()
        }
        stopIncomingShimmer()
        stopRecBadgePulse()
        stopSpeakerSoundWaveRipple()
        stopEndCallBreathingGlow()
        if (::binding.isInitialized) {
            binding.floating3dCallPill.hideImmediately()
            binding.auroraBackdrop.stopAnimation()
            binding.viewLiquidPlatinumAvatar.stopAmbientMotion()
            binding.viewLiveCallWaveform.stopWave()
            binding.viewLiveSignalBars.stopSignalAnimation()
        }
        super.onDestroy()
        toneGenerator?.release()
        toneGenerator = null
    }

    // --- Section 2: 3D Caller Showcase Entrance & Shimmer ---

    private fun playCallerShowcase3DEntrance() {
        if (hasPlayedShowcaseEntrance) return
        hasPlayedShowcaseEntrance = true
        val density = resources.displayMetrics.density
        val showcase = binding.layoutCallerHeader
        showcase.cameraDistance = 1000f * density * density
        showcase.alpha = 0f
        showcase.scaleX = 0.88f
        showcase.scaleY = 0.88f
        showcase.translationY = 24f * density
        showcase.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .translationY(0f)
            .setDuration(520L)
            .setInterpolator(PathInterpolator(0.22f, 1f, 0.36f, 1f))
            .start()
    }

    private fun startIncomingShimmer() {
        if (incomingShimmerAnimator?.isRunning == true) return
        incomingShimmerAnimator = ObjectAnimator.ofFloat(binding.tvIncallStatus, View.ALPHA, 0.48f, 1f).apply {
            duration = 950L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }
    }

    private fun stopIncomingShimmer() {
        incomingShimmerAnimator?.cancel()
        incomingShimmerAnimator = null
        if (::binding.isInitialized) {
            binding.tvIncallStatus.alpha = 1f
        }
    }

    private fun startRecBadgePulse() {
        if (recPulseAnimator?.isRunning == true) return
        recPulseAnimator = ObjectAnimator.ofFloat(binding.tvRecordGridRecBadge, View.ALPHA, 0.45f, 1f).apply {
            duration = 680L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }
    }

    private fun stopRecBadgePulse() {
        recPulseAnimator?.cancel()
        recPulseAnimator = null
        if (::binding.isInitialized) {
            binding.tvRecordGridRecBadge.alpha = 1f
        }
    }

    private fun startSpeakerSoundWaveRipple() {
        if (speakerRippleAnimator?.isRunning == true) return
        val ripple = binding.viewSpeakerRipple
        val sx = ObjectAnimator.ofFloat(ripple, View.SCALE_X, 1.0f, 1.48f).apply {
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
        }
        val sy = ObjectAnimator.ofFloat(ripple, View.SCALE_Y, 1.0f, 1.48f).apply {
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
        }
        val alpha = ObjectAnimator.ofFloat(ripple, View.ALPHA, 0.55f, 0f).apply {
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
        }
        speakerRippleAnimator = AnimatorSet().apply {
            playTogether(sx, sy, alpha)
            duration = 1400L
            interpolator = PathInterpolator(0.22f, 1f, 0.36f, 1f)
            start()
        }
    }

    private fun stopSpeakerSoundWaveRipple() {
        speakerRippleAnimator?.cancel()
        speakerRippleAnimator = null
        if (::binding.isInitialized) {
            binding.viewSpeakerRipple.alpha = 0f
            binding.viewSpeakerRipple.scaleX = 1f
            binding.viewSpeakerRipple.scaleY = 1f
        }
    }

    private fun startEndCallBreathingGlow() {
        if (endCallGlowAnimator?.isRunning == true) return
        val glow = binding.viewEndCallBreathingGlow
        val sx = ObjectAnimator.ofFloat(glow, View.SCALE_X, 0.94f, 1.15f).apply {
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
        }
        val sy = ObjectAnimator.ofFloat(glow, View.SCALE_Y, 0.94f, 1.15f).apply {
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
        }
        val alpha = ObjectAnimator.ofFloat(glow, View.ALPHA, 0.26f, 0.62f).apply {
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
        }
        endCallGlowAnimator = AnimatorSet().apply {
            playTogether(sx, sy, alpha)
            duration = 1750L
            start()
        }
    }

    private fun stopEndCallBreathingGlow() {
        endCallGlowAnimator?.cancel()
        endCallGlowAnimator = null
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachTactile3DPress(view: View, pressScale: Float = 0.92f, pushDownDp: Float = 2f) {
        val density = resources.displayMetrics.density
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate()
                        .scaleX(pressScale)
                        .scaleY(pressScale)
                        .translationY(pushDownDp * density)
                        .setDuration(90L)
                        .start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .translationY(0f)
                        .setDuration(150L)
                        .setInterpolator(OvershootInterpolator(1.2f))
                        .start()
                }
            }
            false
        }
    }

    private fun updateLiveRecordingPill(isRecording: Boolean) {
        val density = resources.displayMetrics.density
        if (isRecording && !lastRecordingActive) {
            recordingStartTimeMs = System.currentTimeMillis()
            binding.tvLiveRecTimer.text = formatRecordingTimer()
            binding.cardLiveRecPill.apply {
                visibility = View.VISIBLE
                alpha = 0f
                translationY = -16f * density
                animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(260L)
                    .setInterpolator(PathInterpolator(0.22f, 1f, 0.36f, 1f))
                    .start()
            }
        } else if (!isRecording && lastRecordingActive) {
            recordingStartTimeMs = 0L
            binding.cardLiveRecPill.animate()
                .alpha(0f)
                .translationY(-14f * density)
                .setDuration(190L)
                .withEndAction {
                    if (!lastRecordingActive) {
                        binding.cardLiveRecPill.visibility = View.GONE
                    }
                }
                .start()
        } else if (isRecording) {
            binding.cardLiveRecPill.visibility = View.VISIBLE
            binding.tvLiveRecTimer.text = formatRecordingTimer()
        }
        lastRecordingActive = isRecording
    }

    private fun formatRecordingTimer(): String {
        val start = recordingStartTimeMs
        val elapsedSec = if (start > 0L) ((System.currentTimeMillis() - start) / 1000L).coerceAtLeast(0L) else 0L
        val mins = elapsedSec / 60L
        val secs = elapsedSec % 60L
        return getString(R.string.incall_rec_live_format, String.format("%02d:%02d", mins, secs))
    }

    // --- Section 4: Bottom 3D Sideways Drag Dock ---

    private fun setupSidewaysDragDock() {
        binding.sidewaysDragDock.listener = object : SidewaysCallDragDockView.DragDockListener {
            override fun onAnswerCall() {
                triggerAnswerWithPremiumTransition()
            }

            override fun onDeclineCall() {
                triggerCallEndedAndDismiss(shouldInvokeCallManagerEnd = true)
            }
        }
    }

    // --- Section 6: Minimize-to-Top-Bar & Expand Switching ---

    private fun setupFloating3DCallPill() {
        binding.floating3dCallPill.listener = object : Floating3DCallPillView.CallPillListener {
            override fun onAnswerCall() {
                triggerAnswerWithPremiumTransition()
            }

            override fun onDeclineCall() {
                triggerCallEndedAndDismiss(shouldInvokeCallManagerEnd = true)
            }

            override fun onToggleMute() {
                CallManager.toggleMute()
            }

            override fun onToggleSpeaker() {
                handleAudioRouteClick()
            }

            override fun onEndCall() {
                triggerCallEndedAndDismiss(shouldInvokeCallManagerEnd = true)
            }

            override fun onPillTappedWhileActive() {
                expandFromTopBar()
            }
        }
    }

    internal fun minimizeToTopBar() {
        if (isFinishing || isDestroyed || isMinimizedToTopBar) return
        isMinimizedToTopBar = true
        closeQuickReplyTray(animated = false)
        closeAudioOutputSheet(animated = false)
        if (isKeypadVisible) toggleKeypadOverlay()
        if (isTopDrawerOpen) closeTop3DPopupDrawer()

        val density = resources.displayMetrics.density
        binding.fullScreenCallContainer.animate()
            .alpha(0f)
            .scaleX(0.86f)
            .scaleY(0.86f)
            .translationY(-48f * density)
            .setDuration(260L)
            .setInterpolator(PathInterpolator(0.22f, 1f, 0.36f, 1f))
            .withEndAction {
                if (isMinimizedToTopBar && !isFinishing && !isDestroyed) {
                    binding.fullScreenCallContainer.visibility = View.INVISIBLE
                }
            }
            .start()

        syncMinimizedTopBarState(CallManager.uiState.value)
    }

    internal fun expandFromTopBar() {
        if (isFinishing || isDestroyed || !isMinimizedToTopBar) return
        isMinimizedToTopBar = false
        binding.floating3dCallPill.hideImmediately()

        val density = resources.displayMetrics.density
        binding.fullScreenCallContainer.apply {
            visibility = View.VISIBLE
            alpha = 0f
            scaleX = 0.90f
            scaleY = 0.90f
            translationY = -28f * density
            animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .translationY(0f)
                .setDuration(300L)
                .setInterpolator(PathInterpolator(0.22f, 1f, 0.36f, 1f))
                .start()
        }
    }

    private fun syncMinimizedTopBarState(state: CallUiState) {
        if (!isMinimizedToTopBar || !state.hasActiveCall) return
        val displayName = resolveCallerDisplayName(state)
        val isBtActive = (state.audioRoute and CallAudioState.ROUTE_BLUETOOTH) != 0
        if (state.callState == Call.STATE_RINGING) {
            val subtitle = state.callerNumber.ifBlank {
                state.callerLocation.ifBlank { getString(R.string.incall_incoming_shimmer) }
            }
            binding.floating3dCallPill.showIncomingCall(displayName, subtitle)
        } else {
            binding.floating3dCallPill.morphToActiveCallBar(
                callerName = displayName,
                formattedTimer = formatDurationTimer(state),
                isMuted = state.isMuted,
                isSpeakerOn = state.isSpeakerOn,
                isBluetooth = isBtActive
            )
        }
    }

    // --- Section 3: Quick-Reply Tray & Silence Ringtone ---

    private fun populateQuickReplyTray() {
        val listContainer = binding.layoutQuickReplyList
        listContainer.removeAllViews()
        val defaults = listOf(
            getString(R.string.incall_reply_call_back),
            getString(R.string.incall_reply_in_meeting),
            getString(R.string.incall_reply_cant_talk)
        )
        val userReplies = prefs.getQuickDeclineResponses().filter { it.isNotBlank() }
        val merged = (defaults + userReplies).distinct().take(4)
        val density = resources.displayMetrics.density

        merged.forEachIndexed { index, replyText ->
            val row = TextView(this).apply {
                text = replyText
                setTextColor(Color.WHITE)
                textSize = 14.5f
                gravity = Gravity.CENTER_VERTICAL
                minHeight = (48f * density).toInt()
                setPadding(
                    (16f * density).toInt(),
                    (10f * density).toInt(),
                    (16f * density).toInt(),
                    (10f * density).toInt()
                )
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 16f * density
                    setColor(Color.argb(30, 255, 255, 255))
                    setStroke((1f * density).toInt().coerceAtLeast(1), Color.argb(56, 255, 255, 255))
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    if (index > 0) topMargin = (8f * density).toInt()
                }
                isClickable = true
                isFocusable = true
                setOnClickListener { view ->
                    view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    sendQuickReplyAndDismiss(replyText)
                }
            }
            listContainer.addView(row)
        }
    }

    internal fun toggleQuickReplyTray() {
        if (isQuickReplyTrayOpen) {
            closeQuickReplyTray(animated = true)
        } else {
            openQuickReplyTray()
        }
    }

    private fun openQuickReplyTray() {
        if (isQuickReplyTrayOpen) return
        populateQuickReplyTray()
        isQuickReplyTrayOpen = true
        val density = resources.displayMetrics.density
        binding.cardQuickReplyTray.apply {
            visibility = View.VISIBLE
            alpha = 0f
            translationY = 18f * density
            animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(220L)
                .setInterpolator(PathInterpolator(0.22f, 1f, 0.36f, 1f))
                .start()
        }
    }

    private fun closeQuickReplyTray(animated: Boolean) {
        if (!isQuickReplyTrayOpen) {
            binding.cardQuickReplyTray.visibility = View.GONE
            return
        }
        isQuickReplyTrayOpen = false
        if (!animated) {
            binding.cardQuickReplyTray.visibility = View.GONE
            return
        }
        val density = resources.displayMetrics.density
        binding.cardQuickReplyTray.animate()
            .alpha(0f)
            .translationY(14f * density)
            .setDuration(170L)
            .withEndAction {
                if (!isQuickReplyTrayOpen) {
                    binding.cardQuickReplyTray.visibility = View.GONE
                }
            }
            .start()
    }

    private fun sendQuickReplyAndDismiss(replyText: String) {
        closeQuickReplyTray(animated = false)
        Toast.makeText(
            this,
            getString(R.string.incall_quick_reply_sent_toast, replyText),
            Toast.LENGTH_SHORT
        ).show()
        CallManager.rejectWithQuickMessage(replyText)
        triggerCallEndedAndDismiss(shouldInvokeCallManagerEnd = false)
    }

    @SuppressLint("MissingPermission")
    internal fun toggleSilenceRingtone() {
        isRingerSilenced = !isRingerSilenced
        if (isRingerSilenced) {
            runCatching {
                val tm = getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    @Suppress("DEPRECATION")
                    tm?.silenceRinger()
                }
            }
            runCatching {
                val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                am?.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_MUTE, 0)
            }
        } else {
            runCatching {
                val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                am?.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_UNMUTE, 0)
            }
        }
        renderSilencePillState()
    }

    private fun renderSilencePillState() {
        val density = resources.displayMetrics.density
        if (isRingerSilenced) {
            binding.tvIncomingSilenceLabel.text = getString(R.string.incall_silenced)
            binding.tvIncomingSilenceLabel.setTextColor(Color.parseColor("#FDE68A"))
            binding.ivIncomingSilenceIcon.imageTintList = ColorStateList.valueOf(Color.parseColor("#FBBF24"))
            binding.btnIncomingSilence.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 26f * density
                setColor(Color.argb(66, 245, 158, 11))
                setStroke((1.3f * density).toInt().coerceAtLeast(1), Color.argb(180, 251, 191, 36))
            }
        } else {
            binding.tvIncomingSilenceLabel.text = getString(R.string.incall_silence)
            binding.tvIncomingSilenceLabel.setTextColor(Color.parseColor("#F8FAFC"))
            binding.ivIncomingSilenceIcon.imageTintList = ColorStateList.valueOf(Color.parseColor("#F8FAFC"))
            binding.btnIncomingSilence.setBackgroundResource(R.drawable.bg_frosted_glass_pill)
        }
    }

    // --- Section 5 & 6: Answer Morph & "Call Ended" 500ms 3D Scale-Down Dismiss ---

    private fun triggerAnswerWithPremiumTransition() {
        closeQuickReplyTray(animated = false)
        animateCallAnsweredMorph()
        CallManager.answerCall()
    }

    private fun animateCallAnsweredMorph() {
        if (isFinishing || isDestroyed) return
        val density = resources.displayMetrics.density
        binding.layoutActiveControls.apply {
            alpha = 0f
            scaleX = 0.92f
            scaleY = 0.92f
            translationY = 22f * density
            animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .translationY(0f)
                .setDuration(360L)
                .setInterpolator(PathInterpolator(0.22f, 1f, 0.36f, 1f))
                .start()
        }
    }

    internal fun triggerCallEndedAndDismiss(shouldInvokeCallManagerEnd: Boolean) {
        if (isDismissingInCall) {
            if (shouldInvokeCallManagerEnd) CallManager.declineOrEndCall()
            return
        }
        isDismissingInCall = true
        if (::proximityScreenLockController.isInitialized) {
            proximityScreenLockController.stopListening(waitForNoProximity = false)
        }
        stopIncomingShimmer()
        stopRecBadgePulse()
        stopSpeakerSoundWaveRipple()
        stopEndCallBreathingGlow()
        closeQuickReplyTray(animated = false)
        closeAudioOutputSheet(animated = false)
        binding.auroraBackdrop.setRecordingAmbient(false)
        binding.auroraBackdrop.setHoldAmbient(false)

        if (shouldInvokeCallManagerEnd) {
            CallManager.declineOrEndCall()
        }

        if (isMinimizedToTopBar && binding.floating3dCallPill.currentMode != Floating3DCallPillView.PillMode.HIDDEN) {
            binding.floating3dCallPill.dismissWithReverseAnimation {
                if (!isFinishing && !isDestroyed && !CallManager.uiState.value.hasActiveCall) {
                    finish()
                } else {
                    isDismissingInCall = false
                }
            }
            return
        }

        // Brief "Call Ended" (~500ms) state + smooth 3D scale-down & fade-out dismissal
        binding.tvIncallStatus.text = getString(R.string.incall_call_ended)
        binding.tvIncallStatus.setTextColor(Color.parseColor("#FDA4AF"))
        binding.viewLiquidPlatinumAvatar.setCallVisualState(ringing = false, active = false)
        binding.viewLiveCallWaveform.setPaused(true)
        binding.layoutActiveControls.animate().alpha(0.32f).setDuration(200L).start()
        binding.layoutIncomingControls.animate().alpha(0.32f).setDuration(200L).start()

        lifecycleScope.launch {
            delay(CALL_ENDED_HOLD_MS)
            if (isFinishing || isDestroyed) return@launch
            if (CallManager.uiState.value.hasActiveCall) {
                isDismissingInCall = false
                return@launch
            }
            binding.fullScreenCallContainer.animate()
                .scaleX(0.88f)
                .scaleY(0.88f)
                .alpha(0f)
                .setDuration(280L)
                .setInterpolator(PathInterpolator(0.4f, 0f, 1f, 1f))
                .withEndAction {
                    if (!isFinishing && !isDestroyed) finish()
                }
                .start()
            binding.auroraBackdrop.animate()
                .alpha(0f)
                .setDuration(280L)
                .start()
        }
    }

    // --- Top Pull-Down 3D Call Info Drawer ---

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTopPullDownDrawer() {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var pullStartY = 0f
        var pullTriggered = false

        binding.layoutTopPullHandle.setOnClickListener {
            if (isTopDrawerOpen) closeTop3DPopupDrawer() else openTop3DPopupDrawer()
        }
        binding.btnDrawerClose.setOnClickListener { closeTop3DPopupDrawer() }

        binding.scrollCallerHeader.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pullStartY = event.rawY
                    pullTriggered = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = event.rawY - pullStartY
                    if (!isTopDrawerOpen && !pullTriggered && v.scrollY <= 0 && dy > touchSlop * 3f) {
                        pullTriggered = true
                        v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                        openTop3DPopupDrawer()
                    }
                }
            }
            false
        }

        var drawerDragStartY = 0f
        binding.cardTop3dPopupDrawer.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    drawerDragStartY = event.rawY
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    if ((drawerDragStartY - event.rawY) > touchSlop * 2.5f) {
                        closeTop3DPopupDrawer()
                        true
                    } else {
                        false
                    }
                }
                else -> false
            }
        }
    }

    private fun openTop3DPopupDrawer() {
        if (isFinishing || isDestroyed || isTopDrawerOpen) return
        isTopDrawerOpen = true
        renderTopDrawerDetails(CallManager.uiState.value)
        val drawer = binding.cardTop3dPopupDrawer
        drawer.visibility = View.VISIBLE
        drawer.alpha = 0f
        drawer.translationY = -120f * resources.displayMetrics.density
        drawer.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(280L)
            .setInterpolator(OvershootInterpolator(0.95f))
            .start()
    }

    private fun closeTop3DPopupDrawer() {
        if (isFinishing || isDestroyed || !isTopDrawerOpen) return
        isTopDrawerOpen = false
        val drawer = binding.cardTop3dPopupDrawer
        drawer.animate()
            .translationY(-100f * resources.displayMetrics.density)
            .alpha(0f)
            .setDuration(200L)
            .withEndAction {
                if (!isFinishing && !isDestroyed && !isTopDrawerOpen) {
                    drawer.visibility = View.GONE
                    drawer.translationY = 0f
                    drawer.alpha = 1f
                }
            }
            .start()
    }

    // --- Existing Call Controls, Recording Policy & DTMF Keypad ---

    private fun canUseInCallRecording(): Boolean {
        return !prefs.adminBlockedUser &&
            subscriptionManager.isPremiumActive() &&
            SubscriptionEnforcementService.canStartCallRecording(this) &&
            prefs.allowCallRecordingConfig
    }

    private fun updateRecordButtonAvailability() {
        val canRecord = canUseInCallRecording()
        binding.btnIncallRecord.isEnabled = canRecord
        binding.btnIncallRecord.isClickable = canRecord
        binding.btnIncallRecord.alpha = if (canRecord) 1f else 0.45f
        binding.tvIncallRecordLabel.alpha = if (canRecord) 1f else 0.45f
        binding.btnDrawerRecord.isEnabled = canRecord
        binding.btnDrawerRecord.alpha = if (canRecord) 1f else 0.45f
    }

    private fun observeRealtimePolicy() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    SubscriptionEnforcementService.state.collect { enfState ->
                        if (enfState.lastEvaluatedAtMs == 0L) return@collect
                        if (!enfState.isRecordingAllowed ||
                            !prefs.allowCallRecordingConfig ||
                            !subscriptionManager.isPremiumActive()
                        ) {
                            CallManager.stopRecordingIfNeeded()
                        }
                        updateRecordButtonAvailability()
                    }
                }
                launch {
                    RealtimeSyncManager.syncState.collect { state ->
                        if (state.uid == null) return@collect
                        if (!prefs.allowCallRecordingConfig ||
                            !subscriptionManager.isPremiumActive()
                        ) {
                            CallManager.stopRecordingIfNeeded()
                        }
                        updateRecordButtonAvailability()
                    }
                }
            }
        }
    }

    private fun setupControls() {
        binding.btnIncallMinimize.setOnClickListener { view ->
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            minimizeToTopBar()
        }

        binding.btnIncallMute.setOnClickListener { CallManager.toggleMute() }
        binding.btnDrawerMute.setOnClickListener { CallManager.toggleMute() }
        binding.btnIncallSpeaker.setOnClickListener { toggleAudioOutputSheet() }
        binding.btnIncallSpeaker.setOnLongClickListener {
            toggleAudioOutputSheet()
            true
        }
        binding.btnDrawerSpeaker.setOnClickListener { toggleAudioOutputSheet() }

        // Audio Output Switcher Sheet actions
        binding.btnCloseAudioSheet.setOnClickListener { closeAudioOutputSheet(animated = true) }
        binding.btnAudioRouteSpeaker.setOnClickListener { view ->
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            if (::proximityScreenLockController.isInitialized) {
                proximityScreenLockController.notifyManualAudioRouteSelection(isSpeakerOn = true)
            }
            CallManager.setAudioRoute(CallAudioState.ROUTE_SPEAKER)
            closeAudioOutputSheet(animated = true)
        }
        binding.btnAudioRouteEarpiece.setOnClickListener { view ->
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            if (::proximityScreenLockController.isInitialized) {
                proximityScreenLockController.notifyManualAudioRouteSelection(isSpeakerOn = false)
            }
            CallManager.setAudioRoute(CallAudioState.ROUTE_EARPIECE)
            closeAudioOutputSheet(animated = true)
        }
        binding.btnAudioRouteBluetooth.setOnClickListener { view ->
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            val supported = CallManager.uiState.value.supportedAudioRoutes
            val targetRoute = when {
                (supported and CallAudioState.ROUTE_BLUETOOTH) != 0 -> CallAudioState.ROUTE_BLUETOOTH
                (supported and CallAudioState.ROUTE_WIRED_HEADSET) != 0 -> CallAudioState.ROUTE_WIRED_HEADSET
                else -> CallAudioState.ROUTE_BLUETOOTH
            }
            if (::proximityScreenLockController.isInitialized) {
                proximityScreenLockController.notifyManualAudioRouteSelection(
                    isSpeakerOn = false,
                    isExternalHeadset = true
                )
            }
            CallManager.setAudioRoute(targetRoute)
            closeAudioOutputSheet(animated = true)
        }

        binding.btnIncallKeypad.setOnClickListener { toggleKeypadOverlay() }
        binding.btnIncallKeypadClose.setOnClickListener {
            if (isKeypadVisible) toggleKeypadOverlay()
        }
        binding.btnIncallDtmfBackspace.setOnClickListener { view ->
            if (dtmfDigitsEntered.isNotEmpty()) {
                dtmfDigitsEntered = dtmfDigitsEntered.dropLast(1)
                binding.tvIncallDtmfDisplay.text = dtmfDigitsEntered
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }
        }

        // Tactile 3D push-down press feedback on control dock buttons & Ruby-Glass End Call pedestal
        attachTactile3DPress(binding.btnIncallEnd, pressScale = 0.92f, pushDownDp = 2f)
        attachTactile3DPress(binding.btnAddCall, pressScale = 0.92f, pushDownDp = 2f)
        attachTactile3DPress(binding.btnIncallMute, pressScale = 0.93f, pushDownDp = 1.5f)
        attachTactile3DPress(binding.btnIncallKeypad, pressScale = 0.93f, pushDownDp = 1.5f)
        attachTactile3DPress(binding.btnIncallSpeaker, pressScale = 0.93f, pushDownDp = 1.5f)
        attachTactile3DPress(binding.btnHoldSwap, pressScale = 0.93f, pushDownDp = 1.5f)
        attachTactile3DPress(binding.btnIncallRecord, pressScale = 0.93f, pushDownDp = 1.5f)

        val handleRecordClick = View.OnClickListener {
            if (!canUseInCallRecording()) {
                updateRecordButtonAvailability()
                Toast.makeText(
                    this@InCallActivity,
                    "Call Recording is a Premium feature. Upgrade to Pro to unlock.",
                    Toast.LENGTH_SHORT
                ).show()
                return@OnClickListener
            }
            val missing = buildList {
                if (ContextCompat.checkSelfPermission(this@InCallActivity, Manifest.permission.RECORD_AUDIO) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    add(Manifest.permission.RECORD_AUDIO)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(this@InCallActivity, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    add(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            if (missing.isEmpty()) {
                toggleCallRecording()
            } else {
                recordingPermissionsLauncher.launch(missing.toTypedArray())
            }
        }
        binding.btnIncallRecord.setOnClickListener(handleRecordClick)
        binding.btnDrawerRecord.setOnClickListener(handleRecordClick)

        // Multi-Party Conference & Dual-Call Controls
        binding.btnAddCall.setOnClickListener {
            CallManager.prepareForAddCall()
            if (supportFragmentManager.findFragmentByTag(DialpadBottomSheetFragment.TAG) == null) {
                DialpadBottomSheetFragment.newInstance(isAddCallMode = true)
                    .show(supportFragmentManager, DialpadBottomSheetFragment.TAG)
            }
        }
        binding.btnHoldSwap.setOnClickListener { CallManager.toggleHoldOrSwap() }
        binding.cardSecondaryCall.setOnClickListener { CallManager.toggleHoldOrSwap() }
        binding.btnMergeCalls.setOnClickListener {
            if (!CallManager.mergeCalls()) {
                Toast.makeText(this, R.string.incall_merge_unavailable, Toast.LENGTH_SHORT).show()
            }
        }
        binding.btnManageConference.setOnClickListener {
            if (supportFragmentManager.findFragmentByTag(ConferenceParticipantsSheet.TAG) == null) {
                ConferenceParticipantsSheet.newInstance()
                    .show(supportFragmentManager, ConferenceParticipantsSheet.TAG)
            }
        }
        binding.btnIncallSwitchSim.setOnClickListener { triggerSwitchSim() }
        binding.tvIncallSimBadge.setOnClickListener {
            if (CallManager.uiState.value.canSwitchSim) {
                triggerSwitchSim()
            }
        }

        binding.btnIncallEnd.setOnClickListener { view ->
            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            triggerCallEndedAndDismiss(shouldInvokeCallManagerEnd = true)
        }

        // Incoming quick-action frosted pills (Message tray & Silence ringtone)
        binding.btnIncomingQuickDecline.setOnClickListener { view ->
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            toggleQuickReplyTray()
        }
        binding.btnCloseQuickReplyTray.setOnClickListener { closeQuickReplyTray(animated = true) }
        binding.btnIncomingSilence.setOnClickListener { view ->
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            toggleSilenceRingtone()
        }

        // Compatibility listeners for hidden legacy anchors
        binding.btnIncomingAnswer.setOnClickListener { triggerAnswerWithPremiumTransition() }
        binding.btnIncomingDecline.setOnClickListener { triggerCallEndedAndDismiss(shouldInvokeCallManagerEnd = true) }

        binding.btnDismissCallerPopup.setOnClickListener {
            isCallerPopupDismissed = true
            binding.cardCallerIdPopup.visibility = View.GONE
        }

        val dtmfMap = mapOf(
            binding.dtmf1 to ('1' to ToneGenerator.TONE_DTMF_1),
            binding.dtmf2 to ('2' to ToneGenerator.TONE_DTMF_2),
            binding.dtmf3 to ('3' to ToneGenerator.TONE_DTMF_3),
            binding.dtmf4 to ('4' to ToneGenerator.TONE_DTMF_4),
            binding.dtmf5 to ('5' to ToneGenerator.TONE_DTMF_5),
            binding.dtmf6 to ('6' to ToneGenerator.TONE_DTMF_6),
            binding.dtmf7 to ('7' to ToneGenerator.TONE_DTMF_7),
            binding.dtmf8 to ('8' to ToneGenerator.TONE_DTMF_8),
            binding.dtmf9 to ('9' to ToneGenerator.TONE_DTMF_9),
            binding.dtmfStar to ('*' to ToneGenerator.TONE_DTMF_S),
            binding.dtmf0 to ('0' to ToneGenerator.TONE_DTMF_0),
            binding.dtmfHash to ('#' to ToneGenerator.TONE_DTMF_P)
        )
        dtmfMap.forEach { (button, pair) ->
            button.setOnClickListener { view ->
                dtmfDigitsEntered += pair.first
                binding.tvIncallDtmfDisplay.text = dtmfDigitsEntered
                CallManager.playDtmfTone(pair.first)
                if (prefs.dialpadTonesEnabled) {
                    toneGenerator?.startTone(pair.second, 120)
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                }
            }
        }
    }

    private fun toggleKeypadOverlay() {
        isKeypadVisible = !isKeypadVisible
        val panel = binding.layoutIncallKeypadPanel
        val density = resources.displayMetrics.density
        if (isKeypadVisible) {
            closeAudioOutputSheet(animated = false)
            panel.visibility = View.VISIBLE
            panel.alpha = 0f
            panel.translationY = 180f * density
            panel.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(260L)
                .setInterpolator(PathInterpolator(0.22f, 1f, 0.36f, 1f))
                .start()
        } else {
            panel.animate()
                .alpha(0f)
                .translationY(160f * density)
                .setDuration(200L)
                .withEndAction {
                    if (!isKeypadVisible) {
                        panel.visibility = View.GONE
                        panel.translationY = 0f
                    }
                }
                .start()
        }
        styleGridGlassButton(
            button = binding.btnIncallKeypad,
            isActive = isKeypadVisible,
            activeFillColor = Color.argb(105, 99, 102, 241),
            activeStrokeColor = Color.argb(220, 165, 180, 252)
        )
    }

    internal fun toggleAudioOutputSheet() {
        if (isAudioOutputSheetOpen) {
            closeAudioOutputSheet(animated = true)
        } else {
            openAudioOutputSheet()
        }
    }

    private fun openAudioOutputSheet() {
        if (isAudioOutputSheetOpen) return
        if (isKeypadVisible) toggleKeypadOverlay()
        isAudioOutputSheetOpen = true
        renderAudioOutputSheetState(CallManager.uiState.value)
        val density = resources.displayMetrics.density
        binding.cardAudioOutputSheet.apply {
            visibility = View.VISIBLE
            alpha = 0f
            translationY = 160f * density
            animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(250L)
                .setInterpolator(PathInterpolator(0.22f, 1f, 0.36f, 1f))
                .start()
        }
    }

    private fun closeAudioOutputSheet(animated: Boolean) {
        if (!isAudioOutputSheetOpen) {
            binding.cardAudioOutputSheet.visibility = View.GONE
            return
        }
        isAudioOutputSheetOpen = false
        val density = resources.displayMetrics.density
        if (!animated) {
            binding.cardAudioOutputSheet.visibility = View.GONE
            binding.cardAudioOutputSheet.translationY = 0f
            return
        }
        binding.cardAudioOutputSheet.animate()
            .alpha(0f)
            .translationY(140f * density)
            .setDuration(190L)
            .withEndAction {
                if (!isAudioOutputSheetOpen) {
                    binding.cardAudioOutputSheet.visibility = View.GONE
                    binding.cardAudioOutputSheet.translationY = 0f
                }
            }
            .start()
    }

    private fun renderAudioOutputSheetState(state: CallUiState) {
        val isSpeaker = state.isSpeakerOn || (state.audioRoute and CallAudioState.ROUTE_SPEAKER) != 0
        val isBtOrHeadset = (state.audioRoute and (CallAudioState.ROUTE_BLUETOOTH or CallAudioState.ROUTE_WIRED_HEADSET)) != 0
        val isEarpiece = !isSpeaker && !isBtOrHeadset

        val density = resources.displayMetrics.density
        fun styleRow(row: View, checkIcon: View, active: Boolean, activeTint: Int, activeStroke: Int) {
            checkIcon.visibility = if (active) View.VISIBLE else View.GONE
            if (active) {
                row.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 26f * density
                    setColor(activeTint)
                    setStroke((1.3f * density).toInt().coerceAtLeast(1), activeStroke)
                }
            } else {
                row.setBackgroundResource(R.drawable.bg_frosted_glass_pill)
            }
        }

        styleRow(
            binding.btnAudioRouteSpeaker,
            binding.ivAudioCheckSpeaker,
            isSpeaker,
            Color.argb(72, 37, 99, 235),
            Color.argb(200, 96, 165, 250)
        )
        styleRow(
            binding.btnAudioRouteEarpiece,
            binding.ivAudioCheckEarpiece,
            isEarpiece,
            Color.argb(66, 16, 185, 129),
            Color.argb(190, 110, 231, 183)
        )
        styleRow(
            binding.btnAudioRouteBluetooth,
            binding.ivAudioCheckBluetooth,
            isBtOrHeadset,
            Color.argb(72, 56, 189, 248),
            Color.argb(200, 125, 211, 252)
        )
    }

    private fun styleGridGlassButton(
        button: MaterialButton,
        isActive: Boolean,
        activeFillColor: Int,
        activeStrokeColor: Int
    ) {
        if (isActive) {
            button.backgroundTintList = ColorStateList.valueOf(activeFillColor)
            button.strokeColor = ColorStateList.valueOf(activeStrokeColor)
        } else {
            button.backgroundTintList = ColorStateList.valueOf(Color.argb(30, 255, 255, 255))
            button.strokeColor = ColorStateList.valueOf(Color.argb(56, 255, 255, 255))
        }
    }

    private fun toggleCallRecording() {
        val started = CallManager.toggleRecording(this)
        if (!started && !CallManager.uiState.value.isRecording) {
            Toast.makeText(this, R.string.incall_recording_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleAudioRouteClick() {
        val state = CallManager.uiState.value
        val supported = state.supportedAudioRoutes
        val hasBluetooth = (supported and CallAudioState.ROUTE_BLUETOOTH) != 0
        val hasWiredHeadset = (supported and CallAudioState.ROUTE_WIRED_HEADSET) != 0

        if (!hasBluetooth && !hasWiredHeadset) {
            CallManager.toggleSpeaker()
        } else {
            toggleAudioOutputSheet()
        }
    }

    private fun triggerSwitchSim() {
        val targetLabel = CallManager.uiState.value.alternateSimLabel
            .ifBlank { getString(R.string.incall_sim_badge_fallback) }
        val switched = CallManager.switchSimToAlternate(this)
        if (switched) {
            Toast.makeText(this, getString(R.string.incall_switch_sim_toast, targetLabel), Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, R.string.incall_switch_sim_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    // --- State Observation & 1-Second Live Call Timer ---

    private fun observeCallState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                CallManager.uiState.collect { state ->
                    if (!state.hasActiveCall) {
                        if (state.isSwitchingSim || CallManager.isSimSwitchInProgress()) {
                            val targetLabel = state.alternateSimLabel
                                .ifBlank { getString(R.string.incall_sim_badge_fallback) }
                            binding.tvIncallStatus.text =
                                getString(R.string.incall_switching_sim_status, targetLabel)
                            return@collect
                        }
                        if (lastRenderedCallState == Call.STATE_NEW) {
                            if (!isFinishing) finish()
                        } else {
                            triggerCallEndedAndDismiss(shouldInvokeCallManagerEnd = false)
                        }
                        return@collect
                    }
                    isDismissingInCall = false
                    renderState(state)
                }
            }
        }
    }

    private fun startDurationTicker() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    val state = CallManager.uiState.value
                    val formattedTime = formatDurationTimer(state)
                    if (state.callState == Call.STATE_ACTIVE && state.connectTimeMillis > 0L) {
                        binding.tvIncallStatus.text = formattedTime
                    }
                    binding.tvDrawerLiveTimer.text = formattedTime
                    if (state.isRecording) {
                        binding.tvLiveRecTimer.text = formatRecordingTimer()
                    }
                    if (isMinimizedToTopBar && state.hasActiveCall && state.callState != Call.STATE_RINGING) {
                        val isBtActive = (state.audioRoute and CallAudioState.ROUTE_BLUETOOTH) != 0
                        binding.floating3dCallPill.updateActiveCallState(
                            callerName = resolveCallerDisplayName(state),
                            formattedTimer = formattedTime,
                            isMuted = state.isMuted,
                            isSpeakerOn = state.isSpeakerOn,
                            isBluetooth = isBtActive
                        )
                    }
                    delay(1000L)
                }
            }
        }
    }

    private fun resolveCallerDisplayName(state: CallUiState): String {
        return when {
            state.isConference && state.conferenceParticipantCount > 0 ->
                getString(R.string.incall_conference_title, state.conferenceParticipantCount)
            state.isConference ->
                getString(R.string.incall_conference_title_generic)
            else -> state.callerName.ifBlank { getString(R.string.incall_unknown_caller) }
        }
    }

    private fun formatDurationTimer(state: CallUiState): String {
        if (state.connectTimeMillis <= 0L) {
            return getString(R.string.incall_connecting_timer)
        }
        val elapsedSeconds = ((System.currentTimeMillis() - state.connectTimeMillis) / 1000L)
            .coerceAtLeast(0L)
        val hours = elapsedSeconds / 3600L
        val mins = (elapsedSeconds % 3600L) / 60L
        val secs = elapsedSeconds % 60L
        return if (hours > 0L) {
            String.format("%02d:%02d:%02d", hours, mins, secs)
        } else {
            String.format("%02d:%02d", mins, secs)
        }
    }

    private fun renderTopDrawerDetails(state: CallUiState) {
        val displayName = resolveCallerDisplayName(state)
        binding.tvDrawerLiveTimer.text = formatDurationTimer(state)
        binding.tvDrawerCallState.text = if (state.callState == Call.STATE_ACTIVE) {
            getString(R.string.caller_id_direction_active)
        } else {
            formatCallStateLabel(state.callState)
        }
        val numSuffix = state.callerNumber.takeIf { it.isNotBlank() }?.let { " • $it" }.orEmpty()
        binding.tvDrawerCallerInfo.text = "$displayName$numSuffix"
        binding.tvDrawerCallerLocation.text = state.callerLocation
            .ifBlank { getString(R.string.caller_id_location_unknown) }

        binding.tvDrawerStartedAt.text = if (state.connectTimeMillis > 0L) {
            val fmt = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(state.connectTimeMillis))
            getString(R.string.incall_started_at_format, fmt)
        } else {
            formatCallStateLabel(state.callState)
        }

        binding.tvDrawerSimInfo.text = state.activeSimLabel
            .ifBlank { getString(R.string.incall_sim_badge_fallback) }
        val isBtActive = (state.audioRoute and CallAudioState.ROUTE_BLUETOOTH) != 0
        val isHeadsetActive = (state.audioRoute and CallAudioState.ROUTE_WIRED_HEADSET) != 0
        binding.tvDrawerAudioRoute.text = getString(
            when {
                isBtActive -> R.string.incall_audio_bluetooth
                isHeadsetActive -> R.string.incall_audio_headset
                state.isSpeakerOn -> R.string.incall_speaker_on
                else -> R.string.incall_audio_earpiece
            }
        )
        binding.tvDrawerMicStatus.text = getString(
            if (state.isMuted) R.string.incall_mic_muted else R.string.incall_mic_live
        )
        binding.tvDrawerRecordingStatus.text = getString(
            if (state.isRecording) R.string.incall_rec_badge else R.string.incall_rec_off
        )

        binding.btnDrawerMute.text = getString(if (state.isMuted) R.string.incall_unmute else R.string.incall_mute)
        binding.btnDrawerMute.setIconResource(if (state.isMuted) R.drawable.ic_mic_off else R.drawable.ic_mic)
        binding.btnDrawerSpeaker.text = getString(
            when {
                isBtActive -> R.string.incall_audio_bluetooth
                isHeadsetActive -> R.string.incall_audio_headset
                state.isSpeakerOn -> R.string.incall_speaker_on
                else -> R.string.incall_speaker
            }
        )
        binding.btnDrawerRecord.text = getString(
            if (state.isRecording) R.string.incall_recording_active else R.string.incall_record
        )
    }

    private fun renderState(state: CallUiState) {
        val displayName = resolveCallerDisplayName(state)
        binding.tvIncallName.text = displayName
        binding.tvIncallNumber.text = state.callerNumber
        binding.tvIncallAvatar.text = if (state.isConference) {
            state.conferenceParticipantCount.coerceAtLeast(2).toString()
        } else {
            displayName.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        }

        val resolvedLocation = state.callerGeoLine.trim().ifBlank { state.callerLocation.trim() }
        val socialBadgeLine = state.callerSocialSummary.trim()
        binding.tvIncallLocation.text = if (socialBadgeLine.isNotEmpty() && resolvedLocation.isNotEmpty()) {
            "$resolvedLocation\n$socialBadgeLine"
        } else {
            resolvedLocation.ifBlank { socialBadgeLine }
        }
        binding.tvIncallLocation.visibility =
            if (!state.isConference && (resolvedLocation.isNotBlank() || socialBadgeLine.isNotBlank())) {
                View.VISIBLE
            } else {
                View.GONE
            }

        val popupKey = "${state.callerNumber}|${state.callState == Call.STATE_RINGING}"
        if (popupKey != lastPopupCallerKey && state.callerNumber.isNotBlank()) {
            lastPopupCallerKey = popupKey
            isCallerPopupDismissed = false
        }

        val showCallerPopup = !state.isConference &&
            !isCallerPopupDismissed &&
            state.isUnknownCaller &&
            (state.callerNumber.isNotBlank() || resolvedLocation.isNotBlank())
        binding.cardCallerIdPopup.visibility = if (showCallerPopup) View.VISIBLE else View.GONE
        if (showCallerPopup) {
            binding.tvPopupCallerBadge.text = getString(R.string.caller_id_unknown_badge)
            binding.tvPopupCallDirection.text = when (state.callState) {
                Call.STATE_RINGING -> getString(R.string.caller_id_direction_incoming)
                Call.STATE_DIALING, Call.STATE_CONNECTING -> getString(R.string.caller_id_direction_outgoing)
                else -> getString(R.string.caller_id_direction_active)
            }
            binding.tvPopupCallerName.text = displayName
            binding.tvPopupCallerLocation.text = getString(
                R.string.caller_id_location_format,
                resolvedLocation.ifBlank { getString(R.string.caller_id_location_unknown) }
            )
            val src = state.callerIdentitySource.ifBlank { getString(R.string.caller_id_popup_badge) }
            val socialPart = socialBadgeLine.takeIf { it.isNotBlank() }?.let { " • $it" }.orEmpty()
            binding.tvPopupCallerNumber.text =
                "${state.callerNumber.ifBlank { getString(R.string.incall_unknown_caller) }} • $src$socialPart"
        }

        val isRinging = state.callState == Call.STATE_RINGING
        val isActive = state.callState == Call.STATE_ACTIVE

        // Smart Top-Center Pill ("HD Voice · Encrypted" + "SIM 1 · 5G" + 4-Bar Live Signal)
        binding.tvIncallHdBadge.text = getString(R.string.incall_smart_hd_encrypted)
        binding.tvSmartSimNetworkBadge.text = if (state.activeSimLabel.isNotBlank()) {
            getString(R.string.incall_smart_sim_5g_format, state.activeSimLabel.take(12))
        } else {
            getString(R.string.incall_smart_sim_5g)
        }

        // Dynamic Full-Screen Ambient States (Recording crimson edge glow & Hold frosted-amber tint)
        binding.auroraBackdrop.setActiveCallMood(!isRinging)
        binding.auroraBackdrop.setRecordingAmbient(state.isRecording)
        binding.auroraBackdrop.setHoldAmbient(!isRinging && state.isHolding)

        // Voice-Reactive 116dp Liquid Avatar Aura + State-Aware Muted/Hold Behavior
        binding.viewLiquidPlatinumAvatar.setCallVisualState(
            ringing = isRinging,
            active = !isRinging,
            muted = state.isMuted,
            onHold = !isRinging && state.isHolding
        )
        binding.tvAvatarHoldBadge.visibility =
            if (!isRinging && state.isHolding) View.VISIBLE else View.GONE
        binding.viewLiveCallWaveform.setActiveEmerald(!isRinging)
        binding.viewLiveCallWaveform.setPaused(state.isMuted || state.isHolding)

        binding.layoutIncomingControls.visibility = if (isRinging) View.VISIBLE else View.GONE
        binding.layoutActiveControls.visibility = if (isRinging) View.GONE else View.VISIBLE
        binding.layoutIncomingControls.alpha = 1f
        binding.layoutActiveControls.alpha = 1f

        if (isRinging) {
            if (lastRenderedCallState != Call.STATE_RINGING) {
                binding.sidewaysDragDock.resetState()
            }
            binding.tvIncallStatus.text = getString(R.string.incall_incoming_shimmer)
            binding.tvIncallStatus.setTextColor(Color.parseColor("#E2E8F0"))
            startIncomingShimmer()
            renderSilencePillState()
        } else {
            stopIncomingShimmer()
            closeQuickReplyTray(animated = false)
            if (lastRenderedCallState == Call.STATE_RINGING) {
                animateCallAnsweredMorph()
            }
            binding.tvIncallStatus.setTextColor(
                if (state.isHolding) Color.parseColor("#FDE68A") else Color.parseColor("#6EE7B7")
            )
            if (state.isSwitchingSim) {
                val targetLabel = state.alternateSimLabel
                    .ifBlank { getString(R.string.incall_sim_badge_fallback) }
                binding.tvIncallStatus.text = getString(R.string.incall_switching_sim_status, targetLabel)
            } else if (isActive) {
                binding.tvIncallStatus.text = formatDurationTimer(state)
            } else {
                binding.tvIncallStatus.text = formatCallStateLabel(state.callState)
            }
        }
        lastRenderedCallState = state.callState

        // Sync minimized top bar if currently minimized
        syncMinimizedTopBarState(state)

        // Active SIM Badge (secondary showcase badge kept hidden when Smart Top Pill shows SIM)
        binding.tvIncallSimBadge.visibility = View.GONE

        // Optional contextual Multi-SIM / Merge Conference row
        val showSwitchSim = state.canSwitchSim && !isRinging
        val showMerge = state.canMerge && !isRinging
        binding.containerSwitchSim.visibility = if (showSwitchSim) View.VISIBLE else View.GONE
        binding.containerMergeCalls.visibility = if (showMerge) View.VISIBLE else View.GONE
        binding.layoutConferenceControls.visibility = if (showSwitchSim || showMerge) View.VISIBLE else View.GONE
        binding.btnMergeCalls.isEnabled = state.canMerge

        if (showSwitchSim) {
            val switchLabel = if (state.alternateSimLabel.isNotBlank()) {
                getString(R.string.incall_switch_sim_to_format, state.alternateSimLabel)
            } else {
                getString(R.string.incall_switch_sim)
            }
            binding.tvIncallSwitchSimLabel.text = switchLabel
            binding.btnIncallSwitchSim.contentDescription = switchLabel
            binding.btnIncallSwitchSim.isEnabled = !state.isSwitchingSim
        }

        // Secondary Call Banner
        val showSecondary = state.hasSecondaryCall && !isRinging
        binding.cardSecondaryCall.visibility = if (showSecondary) View.VISIBLE else View.GONE
        if (showSecondary) {
            binding.tvSecondaryCallerName.text = state.secondaryCallerName
                .ifBlank { state.secondaryCallerNumber }
                .ifBlank { getString(R.string.incall_unknown_caller) }
            binding.tvSecondaryCallStatus.text = formatCallStateLabel(state.secondaryCallState)
        }

        binding.btnManageConference.visibility = if (state.isConference && !isRinging) View.VISIBLE else View.GONE

        // Sculpted 3D Smoked-Sapphire Control Dock Active Glow States
        // 1. Mute: Glowing amber/red active state
        binding.btnIncallMute.setIconResource(if (state.isMuted) R.drawable.ic_mic_off else R.drawable.ic_mic)
        binding.tvIncallMuteLabel.text = getString(if (state.isMuted) R.string.incall_unmute else R.string.incall_mute)
        styleGridGlassButton(
            button = binding.btnIncallMute,
            isActive = state.isMuted,
            activeFillColor = Color.argb(118, 225, 29, 72),
            activeStrokeColor = Color.argb(228, 251, 191, 36)
        )

        // 3. Speaker: Glowing sapphire-blue active state + subtle sound-wave ripple
        val isBtActive = (state.audioRoute and CallAudioState.ROUTE_BLUETOOTH) != 0
        val isHeadsetActive = (state.audioRoute and CallAudioState.ROUTE_WIRED_HEADSET) != 0
        val isAudioHighlighted = state.isSpeakerOn || isBtActive || isHeadsetActive
        binding.btnIncallSpeaker.setIconResource(if (isBtActive) R.drawable.ic_bluetooth else R.drawable.ic_volume_up)
        binding.tvIncallSpeakerLabel.text = getString(
            when {
                isBtActive -> R.string.incall_audio_bluetooth
                isHeadsetActive -> R.string.incall_audio_headset
                else -> R.string.incall_speaker
            }
        )
        styleGridGlassButton(
            button = binding.btnIncallSpeaker,
            isActive = isAudioHighlighted,
            activeFillColor = Color.argb(122, 37, 99, 235),
            activeStrokeColor = Color.argb(232, 96, 165, 250)
        )
        if (!isRinging && isAudioHighlighted) {
            startSpeakerSoundWaveRipple()
        } else {
            stopSpeakerSoundWaveRipple()
        }
        if (isAudioOutputSheetOpen) {
            renderAudioOutputSheetState(state)
        }

        // 5. Hold / Resume / Swap: Glowing warm amber active state
        when {
            state.canSwap -> {
                binding.btnHoldSwap.setIconResource(R.drawable.ic_swap_calls)
                binding.tvHoldSwapLabel.text = getString(R.string.incall_swap)
                binding.btnHoldSwap.contentDescription = getString(R.string.incall_swap)
            }
            state.isHolding -> {
                binding.btnHoldSwap.setIconResource(R.drawable.ic_play_arrow)
                binding.tvHoldSwapLabel.text = getString(R.string.incall_resume)
                binding.btnHoldSwap.contentDescription = getString(R.string.incall_resume)
            }
            else -> {
                binding.btnHoldSwap.setIconResource(R.drawable.ic_pause)
                binding.tvHoldSwapLabel.text = getString(R.string.incall_hold)
                binding.btnHoldSwap.contentDescription = getString(R.string.incall_hold)
            }
        }
        styleGridGlassButton(
            button = binding.btnHoldSwap,
            isActive = state.isHolding,
            activeFillColor = Color.argb(112, 245, 158, 11),
            activeStrokeColor = Color.argb(230, 253, 230, 138)
        )
        binding.btnHoldSwap.isEnabled = state.canHold || state.canSwap || state.isHolding

        // 6. Record: Glowing crimson active state + Live ● REC 00:00 top pill + pulsing crimson screen glow
        binding.chipRecordingBadge.visibility = View.GONE
        binding.tvRecordGridRecBadge.visibility = if (state.isRecording) View.VISIBLE else View.GONE
        binding.tvIncallRecordLabel.text = getString(
            if (state.isRecording) R.string.incall_recording_active else R.string.incall_record
        )
        styleGridGlassButton(
            button = binding.btnIncallRecord,
            isActive = state.isRecording,
            activeFillColor = Color.argb(136, 225, 29, 72),
            activeStrokeColor = Color.argb(236, 253, 164, 175)
        )
        updateLiveRecordingPill(state.isRecording)
        if (state.isRecording) startRecBadgePulse() else stopRecBadgePulse()

        renderTopDrawerDetails(state)
        updateRecordButtonAvailability()
        if (::proximityScreenLockController.isInitialized) {
            proximityScreenLockController.updateCallState(
                hasActiveCall = state.hasActiveCall,
                callState = state.callState,
                audioRoute = state.audioRoute,
                isSpeakerOn = state.isSpeakerOn
            )
        }
    }

    private fun formatCallStateLabel(callState: Int): String {
        return when (callState) {
            Call.STATE_RINGING -> getString(R.string.incall_incoming_shimmer)
            Call.STATE_DIALING -> getString(R.string.incall_status_dialing)
            Call.STATE_CONNECTING -> getString(R.string.incall_status_connecting)
            Call.STATE_HOLDING -> getString(R.string.incall_status_holding)
            Call.STATE_DISCONNECTING -> getString(R.string.incall_status_disconnecting)
            Call.STATE_DISCONNECTED -> getString(R.string.incall_call_ended)
            else -> getString(R.string.incall_status_connecting)
        }
    }

    companion object {
        const val CALL_ENDED_HOLD_MS = 500L
    }
}
