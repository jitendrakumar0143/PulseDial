package com.example.telecom

import android.Manifest
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.util.Log
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
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
 * Default Dialer InCallService paired with CallRecorder.
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
        var followUpHandled: Boolean = false,
        val locationDetails: String = "",
        val socialSummary: String = ""
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
    internal var telephonyCallStateMonitor: TelephonyCallStateMonitor? = null

    @Volatile
    private var latestCustomPopupAd: CustomPopupAd = CustomPopupAd()
    private var rtdbCustomPopupAdCleanup: (() -> Unit)? = null
    private var afterCallAdDispatchedForCurrentSession: Boolean = false
    private var lastPostedWasIncomingHeadsUp: Boolean = false

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
                    cancelCallNotification()
                    if (!CallManager.isSimSwitchInProgress()) {
                        maybeDispatchAfterCallPopupAd()
                    }
                } else {
                    refreshCallNotificationFromCurrentCalls()
                }
            } else if (state == Call.STATE_DISCONNECTING) {
                if (!hasAnyLiveCall()) {
                    cancelCallNotification()
                } else {
                    refreshCallNotificationFromCurrentCalls()
                }
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

        telephonyCallStateMonitor = TelephonyCallStateMonitor(
            context = applicationContext,
            onIdleAfterOffhook = {
                handleTelephonyIdleCleanup(wasOffhook = true)
            },
            onIdleAfterRinging = {
                handleTelephonyIdleCleanup(wasOffhook = false)
            }
        ).also { it.start() }

        var lastNotifSignature = ""
        serviceScope.launch {
            CallManager.uiState.collect { uiState ->
                proxController.onCallUiStateChanged(uiState)
                val sig = if (!uiState.hasActiveCall) {
                    "NONE"
                } else {
                    "${uiState.callState}|${uiState.callerName}|${uiState.callerNumber}|${uiState.callerLocation}|${uiState.isMuted}|${uiState.isSpeakerOn}|${uiState.audioRoute}|${uiState.isRecording}|${uiState.activeSimLabel}|${uiState.connectTimeMillis}"
                }
                if (sig != lastNotifSignature) {
                    lastNotifSignature = sig
                    if (uiState.hasActiveCall && hasAnyLiveCall()) {
                        refreshCallNotificationFromCurrentCalls()
                    } else {
                        cancelCallNotification()
                    }
                }
            }
        }

        serviceScope.launch {
            CallManager.inCallUiVisible.collect {
                if (hasAnyLiveCall()) {
                    refreshCallNotificationFromCurrentCalls()
                } else {
                    cancelCallNotification()
                }
            }
        }

        serviceScope.launch {
            SubscriptionEnforcementService.state.collect { enforcementState ->
                if (trackedCalls.isEmpty()) return@collect
                val vipAllowed = !enforcementState.isBlocked &&
                    subscriptionManager.isPremiumActive() &&
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
        telephonyCallStateMonitor?.stop()
        telephonyCallStateMonitor = null
        proximitySpeakerController?.release()
        proximitySpeakerController = null
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

    override fun onUnbind(intent: Intent?): Boolean {
        telephonyCallStateMonitor?.stop()
        stopCallAnnouncer()
        restoreRingerIfMutedByShield()
        trackedCalls.forEach { call ->
            runCatching { call.unregisterCallback(callCallback) }
        }
        trackedCalls.clear()
        callMetadata.clear()
        _callsState.value = emptyList()
        CallManager.stopRecordingIfNeeded()
        cancelCallNotification()
        CallManager.detachService()
        return super.onUnbind(intent)
    }

    /**
     * Authoritative fallback cleanup triggered when [TelephonyCallStateMonitor] observes
     * `CALL_STATE_OFFHOOK -> CALL_STATE_IDLE` (or `CALL_STATE_RINGING -> CALL_STATE_IDLE`).
     * Ensures the active call UI, ongoing notification, announcer, and recorder terminate cleanly
     * even if Telecom fails to deliver `onCallRemoved`.
     */
    internal fun handleTelephonyIdleCleanup(wasOffhook: Boolean) {
        if (CallManager.isSimSwitchInProgress()) return
        stopCallAnnouncer()
        restoreRingerIfMutedByShield()
        trackedCalls.forEach { call ->
            runCatching { call.unregisterCallback(callCallback) }
        }
        trackedCalls.clear()
        callMetadata.clear()
        _callsState.value = emptyList()
        cancelCallNotification()
        CallManager.forceCleanupOnTelephonyIdle(applicationContext, wasOffhook = wasOffhook)
        if (!CallManager.isSimSwitchInProgress()) {
            maybeDispatchAfterCallPopupAd()
        }
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        val rawNumber = call.details?.handle?.schemeSpecificPart.orEmpty()
        val initialState = getCompatState(call)

        if (initialState == Call.STATE_DISCONNECTED || initialState == Call.STATE_DISCONNECTING) {
            if (!hasAnyLiveCall()) {
                cancelCallNotification()
            }
            return
        }

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
            reachedActive = (initialState == Call.STATE_ACTIVE),
            locationDetails = instantInfo.structuredLocationLine(),
            socialSummary = instantInfo.socialBadgesSummary()
        )
        call.children.orEmpty().forEach { child ->
            resolveAndCacheCallMeta(child, getCompatState(child))
        }

        if (trackedCalls.isEmpty()) {
            afterCallAdDispatchedForCurrentSession = false
        }
        trackedCalls.addIfAbsent(call)
        call.registerCallback(callCallback)
        telephonyCallStateMonitor?.let { monitor ->
            if (!monitor.isMonitoring()) {
                monitor.start()
            }
            val seededState = when (initialState) {
                Call.STATE_RINGING -> android.telephony.TelephonyManager.CALL_STATE_RINGING
                Call.STATE_DIALING,
                Call.STATE_CONNECTING,
                Call.STATE_ACTIVE,
                Call.STATE_HOLDING -> android.telephony.TelephonyManager.CALL_STATE_OFFHOOK
                else -> null
            }
            if (seededState != null) {
                monitor.seedInitialState(seededState)
            }
        }

        val snapshot = trackedCalls.toList()
        _callsState.value = snapshot
        CallManager.onCallAdded(
            call = call,
            callerName = instantInfo.displayName,
            callerNumber = rawNumber,
            allCalls = snapshot,
            resolver = ::resolveIdentityForCall
        )
        refreshCallNotificationFromCurrentCalls()

        // Async Contacts & CallLog enrichment off Main Thread so ANR never occurs
        serviceScope.launch {
            val enriched = CallerIdResolver.resolveWithDeviceAsync(
                context = applicationContext,
                rawNumber = rawNumber,
                telecomDisplayName = call.details?.callerDisplayName
            )
            val liveState = getCompatState(call)
            if (!trackedCalls.contains(call) ||
                liveState == Call.STATE_DISCONNECTED ||
                liveState == Call.STATE_DISCONNECTING
            ) {
                if (!hasAnyLiveCall()) cancelCallNotification()
                return@launch
            }
            val currentMeta = callMetadata[call] ?: return@launch
            callMetadata[call] = currentMeta.copy(
                savedContactName = if (!enriched.isUnknownNumber) enriched.displayName else currentMeta.savedContactName,
                displayName = enriched.displayName,
                location = enriched.location,
                isUnknownNumber = enriched.isUnknownNumber,
                identitySource = enriched.sourceLabel,
                locationDetails = enriched.structuredLocationLine(),
                socialSummary = enriched.socialBadgesSummary()
            )
            broadcastTrackedCalls()
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
        val isPremium = subscriptionManager.isPremiumActive()
        if (!ad.shouldDisplayFor(CustomPopupAd.TRIGGER_AFTER_CALL_END, isPremium)) return

        afterCallAdDispatchedForCurrentSession = true
        if (isInteractiveForegroundApp()) {
            val adIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(MainActivity.EXTRA_SHOW_AFTER_CALL_POPUP_AD, true)
                putExtra(MainActivity.EXTRA_POPUP_AD_BUNDLE, ad.toBundle())
            }
            val started = runCatching {
                startActivity(adIntent)
                true
            }.getOrDefault(false)
            if (!started) {
                prefs.pendingAfterCallAdTimestampMs = System.currentTimeMillis()
            }
        } else {
            // Queue cleanly for next resume instead of hitting Android 10-16 BAL block
            prefs.pendingAfterCallAdTimestampMs = System.currentTimeMillis()
        }
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onCallAudioStateChanged(audioState: CallAudioState?) {
        super.onCallAudioStateChanged(audioState)
        audioState ?: return
        proximitySpeakerController?.onAudioRouteChanged(audioState.route)
        val isSpeaker = (audioState.route and CallAudioState.ROUTE_SPEAKER) != 0
        CallManager.updateAudioState(
            isMuted = audioState.isMuted,
            isSpeakerOn = isSpeaker,
            audioRoute = audioState.route,
            supportedAudioRoutes = audioState.supportedRouteMask
        )
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
            reachedActive = (getCompatState(call) == Call.STATE_ACTIVE),
            locationDetails = info.structuredLocationLine(),
            socialSummary = info.socialBadgesSummary()
        )).copy(
            number = newNumber,
            displayName = info.displayName,
            location = info.location,
            isUnknownNumber = info.isUnknownNumber,
            identitySource = info.sourceLabel,
            locationDetails = prev?.locationDetails?.takeIf { it.isNotBlank() } ?: info.structuredLocationLine(),
            socialSummary = prev?.socialSummary.orEmpty().ifBlank { info.socialBadgesSummary() }
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
            reachedActive = (state == Call.STATE_ACTIVE),
            locationDetails = info.structuredLocationLine(),
            socialSummary = info.socialBadgesSummary()
        ).also { callMetadata[call] = it }
    }

    private fun resolveIdentityForCall(call: Call): ResolvedCallIdentity {
        val meta = resolveAndCacheCallMeta(call, getCompatState(call))
        return ResolvedCallIdentity(
            displayName = meta.displayName,
            phoneNumber = meta.number,
            location = meta.location,
            isUnknownNumber = meta.isUnknownNumber,
            identitySource = meta.identitySource,
            locationDetails = meta.locationDetails,
            socialSummary = meta.socialSummary
        )
    }

    private fun hasAnyLiveCall(): Boolean {
        return trackedCalls.any { call ->
            val st = getCompatState(call)
            st != Call.STATE_DISCONNECTED && st != Call.STATE_DISCONNECTING
        }
    }

    private fun refreshCallNotificationFromCurrentCalls() {
        val ui = CallManager.uiState.value
        if (!ui.hasActiveCall ||
            trackedCalls.isEmpty() ||
            !hasAnyLiveCall() ||
            ui.callState == Call.STATE_DISCONNECTED ||
            ui.callState == Call.STATE_DISCONNECTING
        ) {
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
            subscriptionManager.isPremiumActive()
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
        val waIntent = if (whatsappEnabled) buildWhatsappFollowUpIntent(rawNumber, messageTemplate) else null
        val smsIntent = if (smsEnabled) buildSmsFollowUpIntent(rawNumber, messageTemplate) else null

        if (isInteractiveForegroundApp()) {
            if (smsIntent != null && runCatching { startActivity(smsIntent); true }.getOrDefault(false)) {
                deliveredAny = true
            }
            if (waIntent != null && runCatching { startActivity(waIntent); true }.getOrDefault(false)) {
                deliveredAny = true
            }
        }

        // Always post a BAL-safe actionable notification when screen is locked or backgrounded
        if (!deliveredAny && (waIntent != null || smsIntent != null)) {
            postFollowUpActionNotification(rawNumber, waIntent, smsIntent)
            deliveredAny = true
        }

        if (deliveredAny) {
            prefs.recordFollowUpSent(rawNumber, nowMs)
        }
    }

    private fun isInteractiveForegroundApp(): Boolean {
        val powerOk = runCatching {
            getSystemService(PowerManager::class.java)?.isInteractive ?: true
        }.getOrDefault(true)
        if (!powerOk) return false
        val importance = runCatching {
            val info = ActivityManager.RunningAppProcessInfo()
            ActivityManager.getMyMemoryState(info)
            info.importance
        }.getOrDefault(ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND)
        return importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    private fun buildSmsFollowUpIntent(phoneNumber: String, message: String): Intent? {
        val cleanNumber = phoneNumber.trim()
        if (cleanNumber.isEmpty() || message.isBlank()) return null
        return Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(cleanNumber)}")).apply {
            putExtra("sms_body", message)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }

    private fun buildWhatsappFollowUpIntent(phoneNumber: String, message: String): Intent? {
        val cleanNumber = formatWhatsappFollowUpPhone(phoneNumber)
        if (cleanNumber.isEmpty() || message.isBlank()) return null
        val uri = Uri.parse(
            "https://api.whatsapp.com/send?phone=$cleanNumber&text=${Uri.encode(message)}"
        )
        return Intent(Intent.ACTION_VIEW, uri).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }

    private fun postFollowUpActionNotification(
        phoneNumber: String,
        waIntent: Intent?,
        smsIntent: Intent?
    ) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val primaryIntent = waIntent ?: smsIntent ?: return
        val primaryPending = PendingIntent.getActivity(
            this,
            3001,
            primaryIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, PulseDialApp.CHANNEL_FOLLOW_UP)
            .setSmallIcon(R.drawable.ic_message)
            .setContentTitle(getString(R.string.followup_notification_title, phoneNumber))
            .setContentText(getString(R.string.followup_notification_body))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(primaryPending)

        if (waIntent != null) {
            val waPending = PendingIntent.getActivity(
                this,
                3002,
                waIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                R.drawable.ic_message,
                getString(R.string.followup_action_whatsapp),
                waPending
            )
        }
        if (smsIntent != null) {
            val smsPending = PendingIntent.getActivity(
                this,
                3003,
                smsIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                R.drawable.ic_quick_reply,
                getString(R.string.followup_action_sms),
                smsPending
            )
        }

        runCatching {
            manager.notify(FOLLOW_UP_NOTIFICATION_ID, builder.build())
        }
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
            !prefs.adminBlockedUser &&
            subscriptionManager.isPremiumActive() &&
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

        val isInCallUiVisible = CallManager.isInCallUiVisible()
        val channelId = if (isRinging && !isInCallUiVisible) {
            PulseDialApp.CHANNEL_INCOMING_CALLS
        } else {
            PulseDialApp.CHANNEL_ONGOING_CALLS
        }

        val answerPendingIntent = PendingIntent.getBroadcast(
            this,
            1002,
            Intent(this, CallNotificationActionReceiver::class.java).apply {
                action = CallNotificationActionReceiver.ACTION_ANSWER_CALL
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val declinePendingIntent = PendingIntent.getBroadcast(
            this,
            1003,
            Intent(this, CallNotificationActionReceiver::class.java).apply {
                action = CallNotificationActionReceiver.ACTION_DECLINE_CALL
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val hangUpPendingIntent = PendingIntent.getBroadcast(
            this,
            1004,
            Intent(this, CallNotificationActionReceiver::class.java).apply {
                action = CallNotificationActionReceiver.ACTION_HANG_UP_CALL
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val toggleMutePendingIntent = PendingIntent.getBroadcast(
            this,
            1005,
            Intent(this, CallNotificationActionReceiver::class.java).apply {
                action = CallNotificationActionReceiver.ACTION_TOGGLE_MUTE
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val toggleSpeakerPendingIntent = PendingIntent.getBroadcast(
            this,
            1006,
            Intent(this, CallNotificationActionReceiver::class.java).apply {
                action = CallNotificationActionReceiver.ACTION_TOGGLE_SPEAKER
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val ui = CallManager.uiState.value
        val cleanSubtitle = if (number.isNotBlank()) number else getString(R.string.incall_unknown_caller)
        val elapsedMs = if (ui.connectTimeMillis > 0L) {
            (System.currentTimeMillis() - ui.connectTimeMillis).coerceAtLeast(0L)
        } else {
            0L
        }
        val chronometerBase = SystemClock.elapsedRealtime() - elapsedMs
        val isTimerRunning = ui.callState == Call.STATE_ACTIVE && ui.connectTimeMillis > 0L

        val simLabel = ui.activeSimLabel.ifBlank { getString(R.string.incall_sim_badge_fallback) }
        val isBtActive = (ui.audioRoute and CallAudioState.ROUTE_BLUETOOTH) != 0
        val isHeadsetActive = (ui.audioRoute and CallAudioState.ROUTE_WIRED_HEADSET) != 0
        val audioLabel = getString(
            when {
                isBtActive -> R.string.incall_audio_bluetooth
                isHeadsetActive -> R.string.incall_audio_headset
                ui.isSpeakerOn -> R.string.incall_speaker_on
                else -> R.string.incall_audio_earpiece
            }
        )
        val recLabel = getString(if (ui.isRecording) R.string.incall_rec_badge else R.string.incall_rec_off)
        val locSuffix = ui.callerLocation.trim().takeIf { it.isNotBlank() }?.let { " • $it" }.orEmpty()
        val detailsSummary = if (isRinging) {
            "${getString(R.string.incall_status_ringing)} • $simLabel$locSuffix"
        } else {
            "$simLabel • $audioLabel • $recLabel$locSuffix"
        }

        val compactViews = RemoteViews(packageName, R.layout.notification_call_3d_compact).apply {
            setTextViewText(R.id.tv_notif_compact_name, name)
            setTextViewText(
                R.id.tv_notif_compact_meta,
                if (isRinging) "$cleanSubtitle • ${getString(R.string.incall_status_ringing)}" else "$cleanSubtitle • $simLabel • $audioLabel"
            )
            if (isRinging) {
                setViewVisibility(R.id.chrono_notif_compact_timer, android.view.View.GONE)
            } else {
                setViewVisibility(R.id.chrono_notif_compact_timer, android.view.View.VISIBLE)
                setChronometer(R.id.chrono_notif_compact_timer, chronometerBase, null, isTimerRunning)
            }
        }
        val popupViews = RemoteViews(packageName, R.layout.notification_call_3d_popup).apply {
            setTextViewText(R.id.tv_notif_popup_caller, name)
            setTextViewText(R.id.tv_notif_popup_number, cleanSubtitle)
            setTextViewText(R.id.tv_notif_popup_details, detailsSummary)
            if (isRinging) {
                setViewVisibility(R.id.chrono_notif_call_timer, android.view.View.GONE)
                setTextViewText(R.id.btn_notif_mute, getString(R.string.incall_answer))
                setInt(R.id.btn_notif_mute, "setBackgroundResource", R.drawable.bg_notif_btn_answer_3d)
                setOnClickPendingIntent(R.id.btn_notif_mute, answerPendingIntent)
                setViewVisibility(R.id.btn_notif_speaker, android.view.View.GONE)
                setTextViewText(R.id.btn_notif_end, getString(R.string.incall_decline))
                setInt(R.id.btn_notif_end, "setBackgroundResource", R.drawable.bg_notif_btn_decline_3d)
                setOnClickPendingIntent(R.id.btn_notif_end, declinePendingIntent)
            } else {
                setViewVisibility(R.id.chrono_notif_call_timer, android.view.View.VISIBLE)
                setChronometer(R.id.chrono_notif_call_timer, chronometerBase, null, isTimerRunning)
                setTextViewText(
                    R.id.btn_notif_mute,
                    getString(if (ui.isMuted) R.string.incall_unmute else R.string.incall_mute)
                )
                setInt(R.id.btn_notif_mute, "setBackgroundResource", R.drawable.bg_notif_btn_glass_3d)
                setOnClickPendingIntent(R.id.btn_notif_mute, toggleMutePendingIntent)
                setViewVisibility(R.id.btn_notif_speaker, android.view.View.VISIBLE)
                setTextViewText(
                    R.id.btn_notif_speaker,
                    getString(if (ui.isSpeakerOn) R.string.incall_speaker_on else R.string.incall_speaker)
                )
                setInt(R.id.btn_notif_speaker, "setBackgroundResource", R.drawable.bg_notif_btn_glass_3d)
                setOnClickPendingIntent(R.id.btn_notif_speaker, toggleSpeakerPendingIntent)
                setTextViewText(R.id.btn_notif_end, getString(R.string.incall_end_call))
                setInt(R.id.btn_notif_end, "setBackgroundResource", R.drawable.bg_notif_btn_decline_3d)
                setOnClickPendingIntent(R.id.btn_notif_end, hangUpPendingIntent)
            }
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        builder.setSmallIcon(R.drawable.ic_call)
            .setContentTitle(name)
            .setContentText("$cleanSubtitle • $detailsSummary")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_CALL)
            .setContentIntent(pendingIntent)
            .setColor(android.graphics.Color.parseColor("#0B1220"))
            .setShowWhen(true)
            .setWhen(if (ui.connectTimeMillis > 0L) ui.connectTimeMillis else System.currentTimeMillis())

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setColorized(true)
        }

        if (isRinging) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Safety auto-eviction: a ringing phone call never exceeds 90 seconds,
                // guaranteeing the OS clears the incoming banner even if Telecom drops callbacks.
                builder.setTimeoutAfter(90_000L)
            }
            if (!isInCallUiVisible) {
                builder.setFullScreenIntent(pendingIntent, true)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                builder.setStyle(Notification.DecoratedCustomViewStyle())
                builder.setCustomContentView(compactViews)
                builder.setCustomBigContentView(popupViews)
                if (!isInCallUiVisible) {
                    builder.setCustomHeadsUpContentView(popupViews)
                }
            }
        } else {
            if (isTimerRunning) {
                builder.setUsesChronometer(true)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                builder.setStyle(Notification.DecoratedCustomViewStyle())
                builder.setCustomContentView(compactViews)
                builder.setCustomBigContentView(popupViews)
            }
        }

        val postingIncomingHeadsUp = isRinging && !isInCallUiVisible
        runCatching {
            if (lastPostedWasIncomingHeadsUp && !postingIncomingHeadsUp) {
                manager.cancel(CALL_NOTIFICATION_ID)
            }
            manager.notify(CALL_NOTIFICATION_ID, builder.build())
            lastPostedWasIncomingHeadsUp = postingIncomingHeadsUp
        }
    }

    private fun cancelCallNotification() {
        lastPostedWasIncomingHeadsUp = false
        cancelCallNotification(this)
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
        const val CALL_NOTIFICATION_ID = 4401
        private const val FOLLOW_UP_NOTIFICATION_ID = 4403
        private const val ANNOUNCER_REPEAT_PAUSE_MS = 2_000L
        const val USAGE_NOTIFICATION_RING = AudioAttributes.USAGE_NOTIFICATION_RINGTONE

        fun cancelCallNotification(context: android.content.Context) {
            runCatching {
                val manager = context.getSystemService(NotificationManager::class.java) ?: return
                manager.cancel(CALL_NOTIFICATION_ID)
            }
        }

        fun shouldShowCallNotification(
            hasActiveCall: Boolean,
            callState: Int,
            liveCallCount: Int
        ): Boolean {
            if (!hasActiveCall || liveCallCount <= 0) return false
            return callState != Call.STATE_DISCONNECTED && callState != Call.STATE_DISCONNECTING
        }

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
