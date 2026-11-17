package com.example.ui.incall

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.telecom.Call
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
import com.example.ui.MainActivity
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
        binding.btnIncallSpeaker.setOnClickListener { CallManager.toggleSpeaker() }
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
            if (!prefs.allowCallRecordingConfig) {
                Toast.makeText(this, R.string.setting_recording_disabled_admin, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!subscriptionManager.isSubscriptionActive() ||
                !com.example.billing.SubscriptionEnforcementService.canStartCallRecording(this)
            ) {
                showTrialExpiredPrompt()
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

        binding.btnIncallEnd.setOnClickListener { CallManager.declineOrEndCall() }
        binding.btnIncomingAnswer.setOnClickListener { CallManager.answerCall() }
        binding.btnIncomingDecline.setOnClickListener { CallManager.declineOrEndCall() }
        binding.btnIncomingQuickDecline.setOnClickListener { showQuickDeclineChooser() }

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

    private fun showTrialExpiredPrompt() {
        val isDeviceReused = prefs.trialBlockedByDevice
        val deviceTag = prefs.shortDeviceTag.ifBlank {
            com.example.data.firebase.DeviceIdentityProvider.formatShortDeviceTag(
                com.example.data.firebase.DeviceIdentityProvider.getDeviceHash(this)
            )
        }
        val isMonthlyExpired = subscriptionManager.hasMonthlySubscriptionExpired() &&
            !subscriptionManager.isPremiumActive()
        val titleRes = when {
            isMonthlyExpired -> R.string.paywall_locked_monthly_title
            isDeviceReused -> R.string.trial_device_used_dialog_title
            else -> R.string.trial_expired_dialog_title
        }
        val message = when {
            isMonthlyExpired -> getString(
                R.string.paywall_locked_monthly_message,
                deviceTag,
                prefs.configuredPriceInr,
                prefs.configuredUpiVpa
            )
            isDeviceReused -> getString(R.string.trial_device_used_dialog_message, deviceTag)
            else -> getString(R.string.trial_expired_dialog_message)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(titleRes)
            .setMessage(message)
            .setPositiveButton(R.string.trial_expired_dialog_upgrade) { _, _ ->
                val intent = Intent(this, MainActivity::class.java).apply {
                    putExtra(MainActivity.EXTRA_OPEN_ACCOUNT_TAB, true)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                startActivity(intent)
            }
            .setNegativeButton(R.string.trial_expired_dialog_later, null)
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

    private fun observeCallState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                CallManager.uiState.collect { state ->
                    if (!state.hasActiveCall) {
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

        val isRinging = state.callState == Call.STATE_RINGING
        binding.layoutIncomingControls.visibility = if (isRinging) View.VISIBLE else View.GONE
        binding.layoutActiveControls.visibility = if (isRinging) View.GONE else View.VISIBLE

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

        if (state.callState != Call.STATE_ACTIVE) {
            binding.tvIncallStatus.text = formatCallStateLabel(state.callState)
        }

        binding.btnIncallMute.setIconResource(if (state.isMuted) R.drawable.ic_mic_off else R.drawable.ic_mic)
        binding.btnIncallMute.setBackgroundColor(
            ContextCompat.getColor(
                this,
                if (state.isMuted) R.color.incall_surface_active else R.color.incall_surface
            )
        )

        binding.btnIncallSpeaker.setBackgroundColor(
            ContextCompat.getColor(
                this,
                if (state.isSpeakerOn) R.color.incall_surface_active else R.color.incall_surface
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
