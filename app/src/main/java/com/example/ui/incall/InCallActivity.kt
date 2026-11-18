package com.example.ui.incall

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.telecom.Call
import android.telecom.CallAudioState
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.R
import com.example.billing.SubscriptionManager
import com.example.data.DialerPreferences
import com.example.data.firebase.RealtimeSyncManager
import com.example.databinding.ActivityInCallBinding
import com.example.telecom.CallManager
import com.example.telecom.CallUiState
import com.example.ui.dialpad.DialpadBottomSheetFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class InCallActivity : AppCompatActivity() {

    private lateinit var binding: ActivityInCallBinding
    private lateinit var prefs: DialerPreferences
    private lateinit var subscriptionManager: SubscriptionManager
    private var toneGenerator: ToneGenerator? = null
    private var isKeypadVisible = false
    private var dtmfDigitsEntered: String = ""
    private var isCallerPopupDismissed = false
    private var lastPopupCallerKey = ""

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

        ViewCompat.setOnApplyWindowInsetsListener(binding.incallRoot) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = bars.top + 20, bottom = bars.bottom + 20)
            insets
        }

        setupControls()
        observeCallState()
        observeRealtimePolicy()
        startDurationTicker()
    }

    private fun canUseInCallRecording(): Boolean {
        return subscriptionManager.isSubscriptionActive() &&
            com.example.billing.SubscriptionEnforcementService.canStartCallRecording(this) &&
            prefs.allowCallRecordingConfig
    }

    private fun updateRecordButtonAvailability() {
        val canRecord = canUseInCallRecording()
        binding.btnIncallRecord.isEnabled = canRecord
        binding.btnIncallRecord.isClickable = canRecord
        binding.btnIncallRecord.alpha = if (canRecord) 1f else 0.45f
        binding.tvIncallRecordLabel.alpha = if (canRecord) 1f else 0.45f
    }

    private fun observeRealtimePolicy() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    com.example.billing.SubscriptionEnforcementService.state.collect { enfState ->
                        if (enfState.lastEvaluatedAtMs == 0L) return@collect
                        if (!enfState.isRecordingAllowed ||
                            !prefs.allowCallRecordingConfig ||
                            !subscriptionManager.isSubscriptionActive()
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
                            !subscriptionManager.isSubscriptionActive()
                        ) {
                            CallManager.stopRecordingIfNeeded()
                        }
                        updateRecordButtonAvailability()
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        toneGenerator?.release()
        toneGenerator = null
    }

    private fun setupControls() {
        binding.btnIncallMute.setOnClickListener { CallManager.toggleMute() }
        binding.btnIncallSpeaker.setOnClickListener { handleAudioRouteClick() }
        binding.btnIncallKeypad.setOnClickListener {
            isKeypadVisible = !isKeypadVisible
            binding.layoutIncallKeypadPanel.visibility = if (isKeypadVisible) View.VISIBLE else View.GONE
            binding.btnIncallKeypad.setBackgroundColor(
                ContextCompat.getColor(
                    this,
                    if (isKeypadVisible) R.color.incall_surface_active else R.color.incall_surface
                )
            )
        }
        binding.btnIncallRecord.setOnClickListener {
            if (!canUseInCallRecording()) {
                updateRecordButtonAvailability()
                return@setOnClickListener
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

        binding.btnIncallEnd.setOnClickListener { CallManager.declineOrEndCall() }
        binding.btnIncomingAnswer.setOnClickListener { CallManager.answerCall() }
        binding.btnIncomingDecline.setOnClickListener { CallManager.declineOrEndCall() }
        binding.btnIncomingQuickDecline.setOnClickListener { showQuickDeclineChooser() }
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
            return
        }

        val options = mutableListOf<Pair<Int, String>>()
        if (hasBluetooth) {
            options.add(CallAudioState.ROUTE_BLUETOOTH to getString(R.string.incall_audio_bluetooth))
        }
        if (hasWiredHeadset) {
            options.add(CallAudioState.ROUTE_WIRED_HEADSET to getString(R.string.incall_audio_headset))
        } else {
            options.add(CallAudioState.ROUTE_EARPIECE to getString(R.string.incall_audio_earpiece))
        }
        options.add(CallAudioState.ROUTE_SPEAKER to getString(R.string.incall_speaker))

        val labels = options.map { it.second }.toTypedArray()
        val checked = options.indexOfFirst { (route, _) -> (state.audioRoute and route) != 0 }
            .coerceAtLeast(0)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.incall_audio_chooser_title)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                CallManager.setAudioRoute(options[which].first)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.dialog_close, null)
            .show()
    }

    private fun showQuickDeclineChooser() {
        val responses = prefs.getQuickDeclineResponses().toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.setting_quick_decline_title)
            .setItems(responses) { _, which ->
                CallManager.rejectWithQuickMessage(responses[which])
            }
            .setNegativeButton(R.string.dialog_close, null)
            .show()
    }

    private fun triggerSwitchSim() {
        val targetLabel = CallManager.uiState.value.alternateSimLabel
            .ifBlank { getString(R.string.incall_sim_badge_fallback) }
        val switched = CallManager.switchSimToAlternate(this)
        if (switched) {
            Toast.makeText(
                this,
                getString(R.string.incall_switch_sim_toast, targetLabel),
                Toast.LENGTH_SHORT
            ).show()
        } else {
            Toast.makeText(this, R.string.incall_switch_sim_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

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
                        if (!isFinishing) finish()
                        return@collect
                    }
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
                    if (state.callState == Call.STATE_ACTIVE && state.connectTimeMillis > 0L) {
                        val elapsedSeconds = ((System.currentTimeMillis() - state.connectTimeMillis) / 1000L)
                            .coerceAtLeast(0L)
                        val mins = elapsedSeconds / 60L
                        val secs = elapsedSeconds % 60L
                        binding.tvIncallStatus.text = String.format("%02d:%02d", mins, secs)
                    }
                    delay(1000L)
                }
            }
        }
    }

    private fun renderState(state: CallUiState) {
        val displayName = when {
            state.isConference && state.conferenceParticipantCount > 0 ->
                getString(R.string.incall_conference_title, state.conferenceParticipantCount)
            state.isConference ->
                getString(R.string.incall_conference_title_generic)
            else -> state.callerName.ifBlank { getString(R.string.incall_unknown_caller) }
        }
        binding.tvIncallName.text = displayName
        binding.tvIncallNumber.text = state.callerNumber
        binding.tvIncallAvatar.text = if (state.isConference) {
            state.conferenceParticipantCount.coerceAtLeast(2).toString()
        } else {
            displayName.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        }

        val resolvedLocation = state.callerLocation.trim()
        binding.tvIncallLocation.text = resolvedLocation
        binding.tvIncallLocation.visibility =
            if (!state.isConference && resolvedLocation.isNotBlank()) View.VISIBLE else View.GONE

        val popupKey = "${state.callerNumber}|${state.callState == Call.STATE_RINGING}"
        if (popupKey != lastPopupCallerKey && state.callerNumber.isNotBlank()) {
            lastPopupCallerKey = popupKey
            isCallerPopupDismissed = false
        }

        val showCallerPopup = !state.isConference &&
            !isCallerPopupDismissed &&
            (state.callerNumber.isNotBlank() || state.callerLocation.isNotBlank())
        binding.cardCallerIdPopup.visibility = if (showCallerPopup) View.VISIBLE else View.GONE
        if (showCallerPopup) {
            binding.tvPopupCallerBadge.text = if (state.isUnknownCaller) {
                getString(R.string.caller_id_unknown_badge)
            } else {
                getString(R.string.caller_id_popup_badge)
            }
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
            binding.tvPopupCallerNumber.text = "${state.callerNumber.ifBlank { getString(R.string.incall_unknown_caller) }} • $src"
        }

        val isRinging = state.callState == Call.STATE_RINGING
        binding.layoutIncomingControls.visibility = if (isRinging) View.VISIBLE else View.GONE
        binding.layoutActiveControls.visibility = if (isRinging) View.GONE else View.VISIBLE

        // Active SIM Badge & Mid-Call Switch SIM Button
        if (state.activeSimLabel.isNotBlank()) {
            binding.tvIncallSimBadge.text = state.activeSimLabel
            binding.tvIncallSimBadge.visibility = View.VISIBLE
        } else {
            binding.tvIncallSimBadge.visibility = View.GONE
        }

        val showSwitchSim = state.canSwitchSim && !isRinging
        binding.containerSwitchSim.visibility = if (showSwitchSim) View.VISIBLE else View.GONE
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

        // Secondary Call Banner (when 2+ simultaneous top-level calls exist)
        val showSecondary = state.hasSecondaryCall && !isRinging
        binding.cardSecondaryCall.visibility = if (showSecondary) View.VISIBLE else View.GONE
        if (showSecondary) {
            binding.tvSecondaryCallerName.text = state.secondaryCallerName
                .ifBlank { state.secondaryCallerNumber }
                .ifBlank { getString(R.string.incall_unknown_caller) }
            binding.tvSecondaryCallStatus.text = formatCallStateLabel(state.secondaryCallState)
        }

        // Manage Conference Button
        binding.btnManageConference.visibility =
            if (state.isConference && !isRinging) View.VISIBLE else View.GONE

        if (state.isSwitchingSim) {
            val targetLabel = state.alternateSimLabel
                .ifBlank { getString(R.string.incall_sim_badge_fallback) }
            binding.tvIncallStatus.text = getString(R.string.incall_switching_sim_status, targetLabel)
        } else if (state.callState != Call.STATE_ACTIVE) {
            binding.tvIncallStatus.text = formatCallStateLabel(state.callState)
        }

        binding.btnIncallMute.setIconResource(if (state.isMuted) R.drawable.ic_mic_off else R.drawable.ic_mic)
        binding.btnIncallMute.setBackgroundColor(
            ContextCompat.getColor(
                this,
                if (state.isMuted) R.color.incall_surface_active else R.color.incall_surface
            )
        )

        val isBtActive = (state.audioRoute and CallAudioState.ROUTE_BLUETOOTH) != 0
        val isHeadsetActive = (state.audioRoute and CallAudioState.ROUTE_WIRED_HEADSET) != 0
        val isAudioHighlighted = state.isSpeakerOn || isBtActive || isHeadsetActive
        binding.btnIncallSpeaker.setIconResource(
            if (isBtActive) R.drawable.ic_bluetooth else R.drawable.ic_volume_up
        )
        binding.tvIncallSpeakerLabel.text = getString(
            when {
                isBtActive -> R.string.incall_audio_bluetooth
                isHeadsetActive -> R.string.incall_audio_headset
                else -> R.string.incall_speaker
            }
        )
        binding.btnIncallSpeaker.setBackgroundColor(
            ContextCompat.getColor(
                this,
                if (isAudioHighlighted) R.color.incall_surface_active else R.color.incall_surface
            )
        )

        // Hold / Resume / Swap Button
        when {
            state.canSwap -> {
                binding.btnHoldSwap.setIconResource(R.drawable.ic_swap_calls)
                binding.tvHoldSwapLabel.text = getString(R.string.incall_swap)
                binding.btnHoldSwap.contentDescription = getString(R.string.incall_swap)
                binding.btnHoldSwap.setBackgroundColor(
                    ContextCompat.getColor(this, R.color.incall_surface)
                )
            }
            state.isHolding -> {
                binding.btnHoldSwap.setIconResource(R.drawable.ic_play_arrow)
                binding.tvHoldSwapLabel.text = getString(R.string.incall_resume)
                binding.btnHoldSwap.contentDescription = getString(R.string.incall_resume)
                binding.btnHoldSwap.setBackgroundColor(
                    ContextCompat.getColor(this, R.color.incall_surface_active)
                )
            }
            else -> {
                binding.btnHoldSwap.setIconResource(R.drawable.ic_pause)
                binding.tvHoldSwapLabel.text = getString(R.string.incall_hold)
                binding.btnHoldSwap.contentDescription = getString(R.string.incall_hold)
                binding.btnHoldSwap.setBackgroundColor(
                    ContextCompat.getColor(this, R.color.incall_surface)
                )
            }
        }
        binding.btnHoldSwap.isEnabled = state.canHold || state.canSwap || state.isHolding

        // Merge Calls Button (auto-shown/enabled when calls.size >= 2 or CAPABILITY_MERGE_CONFERENCE)
        binding.containerMergeCalls.visibility = if (state.canMerge) View.VISIBLE else View.GONE
        binding.btnMergeCalls.isEnabled = state.canMerge

        binding.chipRecordingBadge.visibility = if (state.isRecording) View.VISIBLE else View.GONE
        binding.tvIncallRecordLabel.text = getString(
            if (state.isRecording) R.string.incall_recording_active else R.string.incall_record
        )
        binding.btnIncallRecord.setBackgroundColor(
            ContextCompat.getColor(
                this,
                if (state.isRecording) R.color.call_decline_red else R.color.incall_surface
            )
        )
        updateRecordButtonAvailability()
    }

    private fun formatCallStateLabel(callState: Int): String {
        return when (callState) {
            Call.STATE_RINGING -> getString(R.string.incall_status_ringing)
            Call.STATE_DIALING -> getString(R.string.incall_status_dialing)
            Call.STATE_CONNECTING -> getString(R.string.incall_status_connecting)
            Call.STATE_HOLDING -> getString(R.string.incall_status_holding)
            Call.STATE_DISCONNECTING -> getString(R.string.incall_status_disconnecting)
            Call.STATE_DISCONNECTED -> getString(R.string.incall_status_ended)
            else -> getString(R.string.incall_status_connecting)
        }
    }
}
