package com.example.telecom

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.speech.tts.TextToSpeech
import android.telephony.SmsManager
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.PulseDialApp
import com.example.R
import com.example.billing.SubscriptionEnforcementService
import com.example.billing.SubscriptionManager
import com.example.data.ContactsRepository
import com.example.data.DialerPreferences
import com.example.data.firebase.RealtimeSyncManager
import com.example.data.firebase.UserEntitlement
import com.example.recording.CallRecordingAccessibilityService
import com.example.ui.incall.InCallActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Default Dialer InCallService paired with CallRecordingAccessibilityService and CallRecorder.
 *
 * Includes:
 * 1. Bell-to-bell silent call recording from the first ring (`STATE_DIALING`, `STATE_RINGING`, `STATE_CONNECTING`).
 * 2. Smart Call Announcer (`TextToSpeech` with `AudioAttributes.USAGE_NOTIFICATION_RINGTONE` and Headset/Bluetooth-Only mode).
 * 3. Unknown Caller Shield (`OFF`, `SILENT_RECORD`, `AUTO_REJECT`) for unsaved numbers.
 * 4. VIP Auto WhatsApp & SMS Follow-up Engine (`COMPLETED_CALL` vs `MISSED_OR_BUSY_CALL` with 24-hour per-number anti-spam cooldown).
 */
class CustomInCallService : InCallService(), TextToSpeech.OnInitListener {

    enum class CallEndOutcome {
        NONE,
        COMPLETED_CALL,
        MISSED_OR_BUSY_CALL
    }

    private lateinit var prefs: DialerPreferences
    private lateinit var subscriptionManager: SubscriptionManager
    private lateinit var contactsRepo: ContactsRepository
    private var audioManager: AudioManager? = null

    private var currentCall: Call? = null
    private var currentCallNumber: String = ""
    private var currentCallWasIncomingRinging: Boolean = false
    private var currentCallReachedActive: Boolean = false
    private var ringerMutedByShield: Boolean = false

    private var tts: TextToSpeech? = null
    private var isTtsReady: Boolean = false
    private var pendingAnnouncementSpeech: String? = null
    private var announcerLoopJob: Job? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val callCallback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            super.onStateChanged(call, state)
            CallManager.updateCallState(call, state)

            if (state == Call.STATE_RINGING) {
                currentCallWasIncomingRinging = true
            } else if (state == Call.STATE_ACTIVE) {
                currentCallReachedActive = true
                stopCallAnnouncer()
                restoreRingerIfMutedByShield()
            }

            if (isEarlyOrActiveRecordingState(state)) {
                triggerAutoRecordingIfPermitted()
            } else if (state == Call.STATE_DISCONNECTED) {
                stopCallAnnouncer()
                restoreRingerIfMutedByShield()
                CallManager.stopRecordingIfNeeded()
                cancelCallNotification()

                val outcome = resolveCallEndOutcome(
                    wasIncomingRinging = currentCallWasIncomingRinging,
                    reachedActive = currentCallReachedActive
                )
                handleCallDisconnectedFollowUp(currentCallNumber, outcome)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = DialerPreferences(this)
        subscriptionManager = SubscriptionManager(this)
        contactsRepo = ContactsRepository(this)
        audioManager = getSystemService(AudioManager::class.java)
        initTextToSpeech()

        CallManager.attachService(this)
        SubscriptionEnforcementService.init(applicationContext)
        RealtimeSyncManager.init(applicationContext)

        serviceScope.launch {
            SubscriptionEnforcementService.state.collect { enforcementState ->
                val activeCall = currentCall ?: return@collect
                val callState = getCompatState(activeCall)
                if (!enforcementState.isRecordingAllowed ||
                    !subscriptionManager.isSubscriptionActive() ||
                    !prefs.allowCallRecordingConfig ||
                    prefs.adminBlockedUser
                ) {
                    CallManager.stopRecordingIfNeeded()
                    stopCallAnnouncer()
                } else if (isEarlyOrActiveRecordingState(callState)) {
                    triggerAutoRecordingIfPermitted()
                }
            }
        }
        serviceScope.launch {
            RealtimeSyncManager.syncState.collect {
                val activeCall = currentCall ?: return@collect
                val callState = getCompatState(activeCall)
                if (!prefs.announcerEnabled) {
                    stopCallAnnouncer()
                }
                if (!SubscriptionEnforcementService.canStartCallRecording(applicationContext) ||
                    !subscriptionManager.isSubscriptionActive() ||
                    !prefs.allowCallRecordingConfig ||
                    prefs.adminBlockedUser
                ) {
                    CallManager.stopRecordingIfNeeded()
                    stopCallAnnouncer()
                } else if (isEarlyOrActiveRecordingState(callState)) {
                    triggerAutoRecordingIfPermitted()
                }
            }
        }
    }

    private fun initTextToSpeech() {
        runCatching {
            tts = TextToSpeech(applicationContext, this)
        }.onFailure { e ->
            Log.w(TAG, "TextToSpeech init unavailable: ${e.message}")
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val engine = tts ?: return
            runCatching {
                val localeResult = engine.setLanguage(Locale.US)
                if (localeResult == TextToSpeech.LANG_MISSING_DATA ||
                    localeResult == TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    engine.setLanguage(Locale.getDefault())
                }
                val attrs = AudioAttributes.Builder()
                    .setUsage(USAGE_NOTIFICATION_RING)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                engine.setAudioAttributes(attrs)
            }
            isTtsReady = true
            val pending = pendingAnnouncementSpeech
            if (!pending.isNullOrBlank() &&
                currentCall != null &&
                getCompatState(currentCall!!) == Call.STATE_RINGING
            ) {
                pendingAnnouncementSpeech = null
                startRepeatedAnnouncement(pending)
            }
        } else {
            isTtsReady = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCallAnnouncer()
        restoreRingerIfMutedByShield()
        runCatching {
            tts?.stop()
            tts?.shutdown()
        }
        tts = null
        isTtsReady = false
        serviceScope.cancel()
        CallManager.stopRecordingIfNeeded()
        cancelCallNotification()
        CallManager.detachService()
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        val rawNumber = call.details.handle?.schemeSpecificPart.orEmpty()
        val initialState = getCompatState(call)

        // 1. Automatically reject explicitly blocked numbers before any recording or UI starts
        if (initialState == Call.STATE_RINGING && prefs.isNumberBlocked(rawNumber)) {
            call.reject(false, null)
            return
        }

        // Look up whether the caller is saved in ContactsContract.PhoneLookup
        val savedContactName = contactsRepo.lookupCallerName(rawNumber)?.takeIf { it.isNotBlank() }
        val isVipEntitled = hasVipFeatureAccess()

        // 2. Unknown Caller Shield (Silent Ring & Auto-Reject for Unsaved Numbers)
        if (initialState == Call.STATE_RINGING && savedContactName == null && isVipEntitled) {
            when (prefs.unknownShieldMode) {
                UserEntitlement.SHIELD_AUTO_REJECT -> {
                    prefs.recordShieldRejectedNumber(rawNumber)
                    call.reject(false, null)
                    return
                }
                UserEntitlement.SHIELD_SILENT_RECORD -> {
                    muteRingerForShield()
                }
            }
        }

        currentCall = call
        currentCallNumber = rawNumber
        currentCallWasIncomingRinging = (initialState == Call.STATE_RINGING)
        currentCallReachedActive = (initialState == Call.STATE_ACTIVE)
        call.registerCallback(callCallback)

        val resolvedName = savedContactName
            ?: call.details.callerDisplayName?.takeIf { it.isNotBlank() }
            ?: getString(R.string.incall_unknown_caller)

        CallManager.setCurrentCall(call, resolvedName, rawNumber)
        postCallNotification(resolvedName, rawNumber, initialState == Call.STATE_RINGING)

        // 3. Start silent recording from the very first bell (Dialing / Ringing / Connecting)
        if (isEarlyOrActiveRecordingState(initialState)) {
            triggerAutoRecordingIfPermitted()
        }

        // 4. Smart Call Announcer (when incoming call rings and not muted by SILENT_RECORD shield)
        if (initialState == Call.STATE_RINGING &&
            isVipEntitled &&
            prefs.announcerEnabled &&
            !ringerMutedByShield
        ) {
            maybeAnnounceIncomingCaller(savedContactName, rawNumber)
        }

        val launchIntent = Intent(this, InCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(launchIntent)
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        call.unregisterCallback(callCallback)
        if (call == currentCall) {
            stopCallAnnouncer()
            restoreRingerIfMutedByShield()
            CallManager.stopRecordingIfNeeded()
            currentCall = null
            currentCallNumber = ""
            currentCallWasIncomingRinging = false
            currentCallReachedActive = false
            CallManager.setCurrentCall(null, "", "")
            cancelCallNotification()
        }
    }

    @Suppress("DEPRECATION")
    override fun onCallAudioStateChanged(audioState: CallAudioState?) {
        super.onCallAudioStateChanged(audioState)
        audioState ?: return
        val isSpeaker = (audioState.route and CallAudioState.ROUTE_SPEAKER) != 0
        CallManager.updateAudioState(audioState.isMuted, isSpeaker)
    }

    private fun hasVipFeatureAccess(): Boolean {
        return !prefs.adminBlockedUser &&
            subscriptionManager.isSubscriptionActive() &&
            SubscriptionEnforcementService.canStartCallRecording(applicationContext)
    }

    private fun muteRingerForShield() {
        runCatching {
            audioManager?.adjustStreamVolume(
                AudioManager.STREAM_RING,
                AudioManager.ADJUST_MUTE,
                0
            )
            ringerMutedByShield = true
        }.onFailure { e ->
            Log.w(TAG, "Unable to mute stream_ring for Unknown Shield: ${e.message}")
        }
    }

    private fun restoreRingerIfMutedByShield() {
        if (!ringerMutedByShield) return
        ringerMutedByShield = false
        runCatching {
            audioManager?.adjustStreamVolume(
                AudioManager.STREAM_RING,
                AudioManager.ADJUST_UNMUTE,
                0
            )
        }
    }

    private fun maybeAnnounceIncomingCaller(savedContactName: String?, rawNumber: String) {
        if (prefs.announcerHeadsetOnly && !isHeadsetOrBluetoothConnected()) {
            return
        }
        val speechText = buildAnnouncerSpeechText(savedContactName, rawNumber)
        if (isTtsReady) {
            startRepeatedAnnouncement(speechText)
        } else {
            pendingAnnouncementSpeech = speechText
        }
    }

    private fun startRepeatedAnnouncement(speechText: String) {
        announcerLoopJob?.cancel()
        announcerLoopJob = serviceScope.launch {
            repeat(2) { index ->
                val active = currentCall
                if (active == null || getCompatState(active) != Call.STATE_RINGING || !prefs.announcerEnabled) {
                    stopCallAnnouncer()
                    return@launch
                }
                if (prefs.announcerHeadsetOnly && !isHeadsetOrBluetoothConnected()) {
                    stopCallAnnouncer()
                    return@launch
                }
                runCatching {
                    tts?.speak(
                        speechText,
                        TextToSpeech.QUEUE_FLUSH,
                        null,
                        "pulsedial_announcer_$index"
                    )
                }
                if (index == 0) {
                    delay(ANNOUNCER_REPEAT_PAUSE_MS)
                }
            }
        }
    }

    private fun stopCallAnnouncer() {
        pendingAnnouncementSpeech = null
        announcerLoopJob?.cancel()
        announcerLoopJob = null
        runCatching {
            tts?.stop()
        }
    }

    /**
     * Checks `AudioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)` to verify whether
     * wired earphones, USB headsets, neckbands, or helmet Bluetooth audio outputs are connected.
     */
    fun isHeadsetOrBluetoothConnected(): Boolean {
        val am = audioManager ?: getSystemService(AudioManager::class.java) ?: return false
        val outputs = runCatching { am.getDevices(AudioManager.GET_DEVICES_OUTPUTS) }.getOrNull()
            ?: return false
        return outputs.any { device ->
            when (device.type) {
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_USB_HEADSET -> true
                else -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        device.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                            device.type == AudioDeviceInfo.TYPE_BLE_SPEAKER
                    } else {
                        false
                    }
                }
            }
        }
    }

    private fun handleCallDisconnectedFollowUp(rawNumber: String, outcome: CallEndOutcome) {
        if (outcome == CallEndOutcome.NONE) return
        val cleanDigits = rawNumber.filter { it.isDigit() }
        if (cleanDigits.length < 7) return
        if (!hasVipFeatureAccess()) return

        val smsEnabled = prefs.autoSmsFollowUp
        val whatsappEnabled = prefs.autoWhatsappFollowUp
        if (!smsEnabled && !whatsappEnabled) return

        val shouldTrigger = shouldTriggerFollowUp(
            triggerMode = prefs.followUpTrigger,
            outcome = outcome
        )
        if (!shouldTrigger) return

        // Enforce 24-hour anti-spam cooldown per phone number
        val nowMs = System.currentTimeMillis()
        if (!prefs.canSendFollowUpToday(rawNumber, nowMs)) {
            return
        }

        val messageTemplate = when (outcome) {
            CallEndOutcome.COMPLETED_CALL -> prefs.completedCallTemplate
            CallEndOutcome.MISSED_OR_BUSY_CALL -> prefs.missedCallTemplate
            CallEndOutcome.NONE -> return
        }.trim()
        if (messageTemplate.isEmpty()) return

        var deliveredAny = false

        if (smsEnabled) {
            if (sendBackgroundFollowUpSms(rawNumber, messageTemplate)) {
                deliveredAny = true
            }
        }

        if (whatsappEnabled) {
            if (launchWhatsappFollowUpIntent(rawNumber, messageTemplate)) {
                deliveredAny = true
            }
        }

        if (deliveredAny) {
            prefs.recordFollowUpSent(rawNumber, nowMs)
        }
    }

    private fun sendBackgroundFollowUpSms(phoneNumber: String, message: String): Boolean {
        val hasSmsPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasSmsPermission) return false

        return runCatching {
            val smsManager: SmsManager = getSystemService(SmsManager::class.java)
                ?: @Suppress("DEPRECATION") SmsManager.getDefault()
            val parts = smsManager.divideMessage(message)
            if (parts != null && parts.size > 1) {
                smsManager.sendMultipartTextMessage(phoneNumber, null, parts, null, null)
            } else {
                smsManager.sendTextMessage(phoneNumber, null, message, null, null)
            }
            true
        }.onFailure { e ->
            Log.w(TAG, "Auto SMS follow-up failed: ${e.message}")
        }.getOrDefault(false)
    }

    private fun launchWhatsappFollowUpIntent(phoneNumber: String, message: String): Boolean {
        val cleanNumber = formatWhatsappFollowUpPhone(phoneNumber)
        if (cleanNumber.isEmpty()) return false
        val uri = Uri.parse(
            "https://api.whatsapp.com/send?phone=$cleanNumber&text=${Uri.encode(message)}"
        )
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return runCatching {
            CallRecordingAccessibilityService.notifyPendingWhatsappAutoSend()
            startActivity(intent)
            true
        }.onFailure { e ->
            CallRecordingAccessibilityService.clearPendingWhatsappAutoSend()
            Log.w(TAG, "Auto WhatsApp follow-up launch failed: ${e.message}")
        }.getOrDefault(false)
    }

    private fun isEarlyOrActiveRecordingState(state: Int): Boolean {
        return state == Call.STATE_DIALING ||
            state == Call.STATE_RINGING ||
            state == Call.STATE_CONNECTING ||
            state == Call.STATE_ACTIVE
    }

    private fun triggerAutoRecordingIfPermitted() {
        if (prefs.autoCallRecording &&
            prefs.allowCallRecordingConfig &&
            subscriptionManager.isSubscriptionActive() &&
            SubscriptionEnforcementService.canStartCallRecording(applicationContext)
        ) {
            CallManager.startAutoRecordingIfEnabled(applicationContext)
        }
    }

    private fun postCallNotification(name: String, number: String, isRinging: Boolean) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val fullScreenIntent = Intent(this, InCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            1001,
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val channelId = if (isRinging) {
            PulseDialApp.CHANNEL_INCOMING_CALLS
        } else {
            PulseDialApp.CHANNEL_ONGOING_CALLS
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        builder.setSmallIcon(R.drawable.ic_call)
            .setContentTitle(name)
            .setContentText(if (number.isNotBlank()) number else getString(R.string.incall_unknown_caller))
            .setOngoing(true)
            .setContentIntent(pendingIntent)

        if (isRinging) {
            builder.setFullScreenIntent(pendingIntent, true)
        }

        runCatching {
            manager.notify(CALL_NOTIFICATION_ID, builder.build())
        }
    }

    private fun cancelCallNotification() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.cancel(CALL_NOTIFICATION_ID)
    }

    @Suppress("DEPRECATION")
    private fun getCompatState(call: Call): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            call.details.state
        } else {
            call.state
        }
    }

    companion object {
        private const val TAG = "CustomInCallService"
        private const val CALL_NOTIFICATION_ID = 4401
        private const val ANNOUNCER_REPEAT_PAUSE_MS = 2_000L
        const val USAGE_NOTIFICATION_RING = AudioAttributes.USAGE_NOTIFICATION_RINGTONE

        /**
         * Formats the spoken caller announcement text:
         * - Saved contact: `"Incoming call from [Contact Name]"`
         * - Unsaved/unknown: `"Incoming call from unknown number [spaced digits]"`
         */
        fun buildAnnouncerSpeechText(savedContactName: String?, rawNumber: String): String {
            val cleanName = savedContactName?.trim().orEmpty()
            if (cleanName.isNotEmpty()) {
                return "Incoming call from $cleanName"
            }
            val spacedDigits = rawNumber.filter { it.isDigit() }
                .map { it.toString() }
                .joinToString(" ")
                .ifBlank { "private number" }
            return "Incoming call from unknown number $spacedDigits"
        }

        /**
         * Distinguishes `COMPLETED_CALL` (reached `STATE_ACTIVE`) vs
         * `MISSED_OR_BUSY_CALL` (incoming `STATE_RINGING` ended without reaching `STATE_ACTIVE`).
         */
        fun resolveCallEndOutcome(
            wasIncomingRinging: Boolean,
            reachedActive: Boolean
        ): CallEndOutcome {
            return when {
                reachedActive -> CallEndOutcome.COMPLETED_CALL
                wasIncomingRinging -> CallEndOutcome.MISSED_OR_BUSY_CALL
                else -> CallEndOutcome.NONE
            }
        }

        /**
         * Evaluates whether a call outcome matches the user's configured `followUpTrigger`.
         */
        fun shouldTriggerFollowUp(triggerMode: String, outcome: CallEndOutcome): Boolean {
            return when (outcome) {
                CallEndOutcome.COMPLETED_CALL ->
                    triggerMode == UserEntitlement.TRIGGER_ALL_CALLS ||
                        triggerMode == UserEntitlement.TRIGGER_COMPLETED_ONLY
                CallEndOutcome.MISSED_OR_BUSY_CALL ->
                    triggerMode == UserEntitlement.TRIGGER_ALL_CALLS ||
                        triggerMode == UserEntitlement.TRIGGER_MISSED_ONLY
                CallEndOutcome.NONE -> false
            }
        }

        /**
         * Formats a phone number with country code (default `91` for 10-digit Indian numbers)
         * for `https://api.whatsapp.com/send?phone=...`.
         */
        fun formatWhatsappFollowUpPhone(rawNumber: String): String {
            val digits = rawNumber.filter { it.isDigit() }
            return when {
                digits.length == 10 -> "91$digits"
                digits.length == 11 && digits.startsWith("0") -> "91${digits.drop(1)}"
                digits.length >= 11 -> digits
                else -> ""
            }
        }
    }
}
