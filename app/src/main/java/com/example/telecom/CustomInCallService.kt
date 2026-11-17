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
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.PulseDialApp
import com.example.R
import com.example.billing.SubscriptionEnforcementService
import com.example.billing.SubscriptionManager
import com.example.data.ContactsRepository
import com.example.data.DialerPreferences
import com.example.data.firebase.CustomPopupAd
import com.example.data.firebase.RealtimeDatabaseSync
import com.example.data.firebase.RealtimeSyncManager
import com.example.data.firebase.UserEntitlement
import com.example.recording.CallRecordingAccessibilityService
import com.example.ui.MainActivity
import com.example.ui.incall.InCallActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Default Dialer InCallService paired with CallRecordingAccessibilityService and CallRecorder.
 *
 * Supports:
 * 1. Multi-Party Call & Conference State Tracking (`CopyOnWriteArrayList<Call>` + `StateFlow<List<Call>>`).
 * 2. Bell-to-bell continuous silent call recording across hold/swap/add-call/merge until ALL calls end.
 * 3. Smart Call Announcer (`TextToSpeech` with `AudioAttributes.USAGE_NOTIFICATION_RINGTONE` & Headset mode).
 * 4. Unknown Caller Shield (`OFF`, `SILENT_RECORD`, `AUTO_REJECT`) for unsaved numbers.
 * 5. VIP Auto WhatsApp & SMS Follow-up Engine (`COMPLETED_CALL` vs `MISSED_OR_BUSY_CALL`).
 */
class CustomInCallService : InCallService(), TextToSpeech.OnInitListener {

    enum class CallEndOutcome {
        NONE,
        COMPLETED_CALL,
        MISSED_OR_BUSY_CALL
    }

    private data class TrackedCallMeta(
        val number: String,
        val savedContactName: String?,
        val displayName: String,
        val location: String,
        val isUnknownNumber: Boolean,
        val identitySource: String,
        var wasIncomingRinging: Boolean,
        var reachedActive: Boolean,
        var followUpHandled: Boolean = false
    )

    private lateinit var prefs: DialerPreferences
    private lateinit var subscriptionManager: SubscriptionManager
    private lateinit var contactsRepo: ContactsRepository
    private var audioManager: AudioManager? = null

    /** Live multi-call list tracking all active, held, dialing, ringing, and conference calls simultaneously. */
    private val trackedCalls = CopyOnWriteArrayList<Call>()
    private val _callsState = MutableStateFlow<List<Call>>(emptyList())
    val callsState: StateFlow<List<Call>> = _callsState.asStateFlow()

    private val callMetadata = ConcurrentHashMap<Call, TrackedCallMeta>()
    private var ringerMutedByShield: Boolean = false

    private var tts: TextToSpeech? = null
    private var isTtsReady: Boolean = false
    private var pendingAnnouncementSpeech: String? = null
    private var announcerLoopJob: Job? = null
    private var proximitySpeakerController: ProximitySpeakerController? = null

    @Volatile
    private var latestCustomPopupAd: CustomPopupAd = CustomPopupAd()
    private var rtdbCustomPopupAdCleanup: (() -> Unit)? = null
    private var afterCallAdDispatchedForCurrentSession: Boolean = false

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val callCallback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            super.onStateChanged(call, state)
            val meta = callMetadata[call]
            if (state == Call.STATE_RINGING) {
                meta?.wasIncomingRinging = true
            } else if (state == Call.STATE_ACTIVE) {
                meta?.reachedActive = true
                if (trackedCalls.none { getCompatState(it) == Call.STATE_RINGING }) {
                    stopCallAnnouncer()
                    restoreRingerIfMutedByShield()
                }
            }

            broadcastTrackedCalls()

            if (isEarlyOrActiveRecordingState(state) || state == Call.STATE_HOLDING) {
                // Keep recording continuously during hold/swap/add-call/merge
                triggerAutoRecordingIfPermitted()
            } else if (state == Call.STATE_DISCONNECTED) {
                if (trackedCalls.none { getCompatState(it) == Call.STATE_RINGING }) {
                    stopCallAnnouncer()
                    restoreRingerIfMutedByShield()
                }

                // Follow-up triggers per disconnected caller if not already handled and not a synthetic conference node
                val isConferenceParent =
                    call.details?.hasProperty(Call.Details.PROPERTY_CONFERENCE) == true
                if (meta != null && !meta.followUpHandled && !isConferenceParent) {
                    meta.followUpHandled = true
                    val outcome = resolveCallEndOutcome(
                        wasIncomingRinging = meta.wasIncomingRinging,
                        reachedActive = meta.reachedActive
                    )
                    handleCallDisconnectedFollowUp(meta.number, outcome)
                }

                // Never stop CallRecorderForegroundService while another call or conference remains connected
                val allStates = trackedCalls.map { getCompatState(it) }
                if (!CallManager.shouldKeepRecordingActive(allStates, trackedCalls.size)) {
                    CallManager.stopRecordingIfNeeded()
                    if (!CallManager.isSimSwitchInProgress()) {
                        maybeDispatchAfterCallPopupAd()
                    }
                }
                refreshCallNotificationFromCurrentCalls()
            }
        }

        override fun onDetailsChanged(call: Call, details: Call.Details) {
            super.onDetailsChanged(call, details)
            refreshCallMetaFromDetails(call, details)
            broadcastTrackedCalls()
            refreshCallNotificationFromCurrentCalls()
        }

        override fun onChildrenChanged(call: Call, children: List<Call>) {
            super.onChildrenChanged(call, children)
            children.forEach { child ->
                if (!callMetadata.containsKey(child)) {
                    resolveAndCacheCallMeta(child, getCompatState(child))
                }
            }
            broadcastTrackedCalls()
            refreshCallNotificationFromCurrentCalls()
        }

        override fun onConferenceableCallsChanged(call: Call, conferenceableCalls: List<Call>) {
            super.onConferenceableCallsChanged(call, conferenceableCalls)
            broadcastTrackedCalls()
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
        rtdbCustomPopupAdCleanup = RealtimeDatabaseSync.attachCustomPopupAdListener(applicationContext) { ad ->
            latestCustomPopupAd = ad
        }

        val proxController = ProximitySpeakerController(applicationContext) { speakerOn ->
            CallManager.setSpeakerEnabled(speakerOn)
        }
        proximitySpeakerController = proxController
        serviceScope.launch {
            CallManager.uiState.collect { uiState ->
                proxController.onCallUiStateChanged(uiState)
            }
        }

        serviceScope.launch {
            SubscriptionEnforcementService.state.collect { enforcementState ->
                if (trackedCalls.isEmpty()) return@collect
                val vipAllowed = !enforcementState.shouldRestrictUiToAccountTab &&
                    subscriptionManager.isSubscriptionActive() &&
                    !prefs.adminBlockedUser
                if (!vipAllowed) {
                    stopCallAnnouncer()
                }
                if (!enforcementState.isRecordingAllowed ||
                    !vipAllowed ||
                    !prefs.allowCallRecordingConfig
                ) {
                    CallManager.stopRecordingIfNeeded()
                } else if (hasAnyEarlyOrActiveRecordingCall()) {
                    triggerAutoRecordingIfPermitted()
                }
            }
        }
        serviceScope.launch {
            RealtimeSyncManager.syncState.collect {
                if (trackedCalls.isEmpty()) return@collect
                val vipAllowed = hasVipFeatureAccess()
                if (!prefs.announcerEnabled || !vipAllowed) {
                    stopCallAnnouncer()
                }
                if (!SubscriptionEnforcementService.canStartCallRecording(applicationContext) ||
                    !vipAllowed ||
                    !prefs.allowCallRecordingConfig
                ) {
                    CallManager.stopRecordingIfNeeded()
                } else if (hasAnyEarlyOrActiveRecordingCall()) {
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
            val hasRingingCall = trackedCalls.any { getCompatState(it) == Call.STATE_RINGING }
            if (!pending.isNullOrBlank() && hasRingingCall) {
                pendingAnnouncementSpeech = null
                startRepeatedAnnouncement(pending)
            }
        } else {
            isTtsReady = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        rtdbCustomPopupAdCleanup?.invoke()
        rtdbCustomPopupAdCleanup = null
        proximitySpeakerController?.release()
        proximitySpeakerController = null
        CallerIdPopupOverlay.dismiss()
        stopCallAnnouncer()
        restoreRingerIfMutedByShield()
        runCatching {
            tts?.stop()
            tts?.shutdown()
        }
        tts = null
        isTtsReady = false
        trackedCalls.forEach { call ->
            runCatching { call.unregisterCallback(callCallback) }
        }
        trackedCalls.clear()
        callMetadata.clear()
        _callsState.value = emptyList()
        serviceScope.cancel()
        CallManager.stopRecordingIfNeeded()
        cancelCallNotification()
        CallManager.detachService()
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        val rawNumber = call.details?.handle?.schemeSpecificPart.orEmpty()
        val initialState = getCompatState(call)

        // 1. Automatically reject explicitly blocked numbers before any recording or UI starts
        if (initialState == Call.STATE_RINGING && prefs.isNumberBlocked(rawNumber)) {
            call.reject(false, null)
            return
        }

        val telecomName = call.details?.callerDisplayName
        val isVipEntitled = hasVipFeatureAccess()
        // Only run synchronous PhoneLookup if Unknown Caller Shield is actively needed on an incoming ring
        val savedContactName = if (initialState == Call.STATE_RINGING &&
            isVipEntitled &&
            prefs.unknownShieldMode != UserEntitlement.SHIELD_OFF
        ) {
            contactsRepo.lookupCallerName(rawNumber)?.takeIf { it.isNotBlank() }
        } else {
            null
        }

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

        val instantInfo = CallerIdResolver.resolveInstant(
            rawNumber = rawNumber,
            savedContactName = savedContactName,
            telecomDisplayName = telecomName
        )

        callMetadata[call] = TrackedCallMeta(
            number = rawNumber,
            savedContactName = savedContactName,
            displayName = instantInfo.displayName,
            location = instantInfo.location,
            isUnknownNumber = instantInfo.isUnknownNumber,
            identitySource = instantInfo.sourceLabel,
            wasIncomingRinging = (initialState == Call.STATE_RINGING),
            reachedActive = (initialState == Call.STATE_ACTIVE)
        )
        call.children.orEmpty().forEach { child ->
            resolveAndCacheCallMeta(child, getCompatState(child))
        }

        if (trackedCalls.isEmpty()) {
            afterCallAdDispatchedForCurrentSession = false
        }
        trackedCalls.addIfAbsent(call)
        call.registerCallback(callCallback)

        val snapshot = trackedCalls.toList()
        _callsState.value = snapshot
        CallManager.onCallAdded(
            call = call,
            callerName = instantInfo.displayName,
            callerNumber = rawNumber,
            allCalls = snapshot,
            resolver = ::resolveIdentityForCall
        )
        CallerIdPopupOverlay.showOrUpdate(
            context = applicationContext,
            info = instantInfo,
            isIncoming = (initialState == Call.STATE_RINGING)
        )
        refreshCallNotificationFromCurrentCalls()

        // Async Contacts & CallLog enrichment off Main Thread so ANR never occurs
        serviceScope.launch {
            val enriched = CallerIdResolver.resolveWithDeviceAsync(
                context = applicationContext,
                rawNumber = rawNumber,
                telecomDisplayName = call.details?.callerDisplayName
            )
            if (!trackedCalls.contains(call)) return@launch
            val currentMeta = callMetadata[call] ?: return@launch
            callMetadata[call] = currentMeta.copy(
                savedContactName = if (!enriched.isUnknownNumber) enriched.displayName else currentMeta.savedContactName,
                displayName = enriched.displayName,
                location = enriched.location,
                isUnknownNumber = enriched.isUnknownNumber,
                identitySource = enriched.sourceLabel
            )
            broadcastTrackedCalls()
            CallerIdPopupOverlay.showOrUpdate(
                context = applicationContext,
                info = enriched,
                isIncoming = (getCompatState(call) == Call.STATE_RINGING || currentMeta.wasIncomingRinging)
            )
            refreshCallNotificationFromCurrentCalls()

            if (getCompatState(call) == Call.STATE_RINGING &&
                isVipEntitled &&
                prefs.announcerEnabled &&
                !ringerMutedByShield
            ) {
                maybeAnnounceIncomingCaller(
                    if (!enriched.isUnknownNumber) enriched.displayName else null,
                    rawNumber
                )
            }
        }

        // 3. Start/continue silent recording from the very first bell (never restarted on 2nd call/merge)
        if (isEarlyOrActiveRecordingState(initialState) || initialState == Call.STATE_HOLDING) {
            triggerAutoRecordingIfPermitted()
        }

        val launchIntent = Intent(this, InCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(launchIntent)
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        runCatching { call.unregisterCallback(callCallback) }
        trackedCalls.remove(call)
        val meta = callMetadata.remove(call)

        val snapshot = trackedCalls.toList()
        _callsState.value = snapshot
        CallManager.onCallRemoved(call, snapshot, ::resolveIdentityForCall)

        if (meta != null && !meta.followUpHandled) {
            val isConferenceParent =
                call.details?.hasProperty(Call.Details.PROPERTY_CONFERENCE) == true
            if (!isConferenceParent) {
                meta.followUpHandled = true
                val outcome = resolveCallEndOutcome(
                    wasIncomingRinging = meta.wasIncomingRinging,
                    reachedActive = meta.reachedActive
                )
                handleCallDisconnectedFollowUp(meta.number, outcome)
            }
        }

        if (trackedCalls.none { getCompatState(it) == Call.STATE_RINGING }) {
            stopCallAnnouncer()
            restoreRingerIfMutedByShield()
        }

        // Only stop call recording & remove foreground service when ALL calls are disconnected
        if (snapshot.isEmpty() ||
            !CallManager.shouldKeepRecordingActive(snapshot.map { getCompatState(it) }, snapshot.size)
        ) {
            CallManager.stopRecordingIfNeeded()
        }

        if (snapshot.isEmpty()) {
            CallerIdPopupOverlay.dismiss()
            cancelCallNotification()
            if (!CallManager.isSimSwitchInProgress()) {
                maybeDispatchAfterCallPopupAd()
            }
        } else {
            refreshCallNotificationFromCurrentCalls()
        }
    }

    private fun maybeDispatchAfterCallPopupAd() {
        if (afterCallAdDispatchedForCurrentSession) return
        val syncedAd = RealtimeSyncManager.syncState.value.customPopupAd
        val ad = if (latestCustomPopupAd.adId.isNotBlank() || latestCustomPopupAd.isEnabled) {
            latestCustomPopupAd
        } else {
            syncedAd
        }
        val isPremium = prefs.isPremiumUser || subscriptionManager.isPremiumActive()
        if (!ad.shouldDisplayFor(CustomPopupAd.TRIGGER_AFTER_CALL_END, isPremium)) return

        afterCallAdDispatchedForCurrentSession = true
        val adIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_SHOW_AFTER_CALL_POPUP_AD, true)
            putExtra(MainActivity.EXTRA_POPUP_AD_BUNDLE, ad.toBundle())
        }
        runCatching { startActivity(adIntent) }
            .onFailure { e -> Log.w(TAG, "Failed to launch post-call popup ad: ${e.message}") }
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onCallAudioStateChanged(audioState: CallAudioState?) {
        super.onCallAudioStateChanged(audioState)
        audioState ?: return
        proximitySpeakerController?.onAudioRouteChanged(audioState.route)
        val isSpeaker = (audioState.route and CallAudioState.ROUTE_SPEAKER) != 0
        CallManager.updateAudioState(audioState.isMuted, isSpeaker)
    }

    private fun broadcastTrackedCalls() {
        val snapshot = trackedCalls.toList()
        _callsState.value = snapshot
        CallManager.syncTrackedCalls(snapshot, ::resolveIdentityForCall)
    }

    private fun refreshCallMetaFromDetails(call: Call, details: Call.Details) {
        val prev = callMetadata[call]
        val newNumber = details.handle?.schemeSpecificPart?.takeIf { it.isNotBlank() } ?: prev?.number.orEmpty()
        val info = CallerIdResolver.resolveInstant(
            rawNumber = newNumber,
            savedContactName = prev?.savedContactName,
            telecomDisplayName = details.callerDisplayName
        )
        callMetadata[call] = (prev ?: TrackedCallMeta(
            number = newNumber,
            savedContactName = null,
            displayName = info.displayName,
            location = info.location,
            isUnknownNumber = info.isUnknownNumber,
            identitySource = info.sourceLabel,
            wasIncomingRinging = (getCompatState(call) == Call.STATE_RINGING),
            reachedActive = (getCompatState(call) == Call.STATE_ACTIVE)
        )).copy(
            number = newNumber,
            displayName = info.displayName,
            location = info.location,
            isUnknownNumber = info.isUnknownNumber,
            identitySource = info.sourceLabel
        )
    }

    private fun resolveAndCacheCallMeta(call: Call, state: Int): TrackedCallMeta {
        callMetadata[call]?.let { return it }
        val rawNumber = call.details?.handle?.schemeSpecificPart.orEmpty()
        val info = CallerIdResolver.resolveInstant(
            rawNumber = rawNumber,
            savedContactName = null,
            telecomDisplayName = call.details?.callerDisplayName
        )
        return TrackedCallMeta(
            number = rawNumber,
            savedContactName = null,
            displayName = info.displayName,
            location = info.location,
            isUnknownNumber = info.isUnknownNumber,
            identitySource = info.sourceLabel,
            wasIncomingRinging = (state == Call.STATE_RINGING),
            reachedActive = (state == Call.STATE_ACTIVE)
        ).also { callMetadata[call] = it }
    }

    private fun resolveIdentityForCall(call: Call): ResolvedCallIdentity {
        val meta = resolveAndCacheCallMeta(call, getCompatState(call))
        return ResolvedCallIdentity(
            displayName = meta.displayName,
            phoneNumber = meta.number,
            location = meta.location,
            isUnknownNumber = meta.isUnknownNumber,
            identitySource = meta.identitySource
        )
    }

    private fun refreshCallNotificationFromCurrentCalls() {
        val ui = CallManager.uiState.value
        if (!ui.hasActiveCall || trackedCalls.isEmpty()) {
            cancelCallNotification()
            return
        }
        val title = ui.callerName.ifBlank { getString(R.string.incall_unknown_caller) }
        val subtitle = ui.callerNumber.ifBlank { getString(R.string.incall_unknown_caller) }
        val isRinging = (ui.callState == Call.STATE_RINGING)
        postCallNotification(title, subtitle, isRinging)
    }

    private fun hasAnyEarlyOrActiveRecordingCall(): Boolean {
        return trackedCalls.any { call ->
            val st = getCompatState(call)
            isEarlyOrActiveRecordingState(st) || st == Call.STATE_HOLDING
        }
    }

    private fun hasVipFeatureAccess(): Boolean {
        return !prefs.adminBlockedUser &&
            !SubscriptionEnforcementService.shouldRestrictUi(applicationContext) &&
            subscriptionManager.isSubscriptionActive()
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
                val ringing = trackedCalls.firstOrNull { getCompatState(it) == Call.STATE_RINGING }
                if (ringing == null || !prefs.announcerEnabled) {
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
            call.details?.state ?: call.state
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
