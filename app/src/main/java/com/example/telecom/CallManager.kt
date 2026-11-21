package com.example.telecom

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.VideoProfile
import com.example.recording.CallRecorder
import com.example.recording.CallRecordingSource
import com.example.recording.VoipCallRecorderManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

data class ResolvedCallIdentity(
    val displayName: String,
    val phoneNumber: String,
    val location: String = "",
    val isUnknownNumber: Boolean = false,
    val identitySource: String = "",
    val locationDetails: String = "",
    val socialSummary: String = ""
)

data class ConferenceParticipantUi(
    val callKey: Int,
    val displayName: String,
    val phoneNumber: String,
    val callState: Int,
    val canSplit: Boolean,
    val canDrop: Boolean
)

enum class HoldSwapAction {
    NONE,
    HOLD,
    UNHOLD,
    SWAP
}

data class CallUiState(
    val hasActiveCall: Boolean = false,
    val callerName: String = "",
    val callerNumber: String = "",
    val callerLocation: String = "",
    val callerGeoLine: String = "",
    val callerSocialSummary: String = "",
    val isUnknownCaller: Boolean = false,
    val callerIdentitySource: String = "",
    val callState: Int = Call.STATE_NEW,
    val isMuted: Boolean = false,
    val isSpeakerOn: Boolean = false,
    val audioRoute: Int = CallAudioState.ROUTE_EARPIECE,
    val supportedAudioRoutes: Int = CallAudioState.ROUTE_EARPIECE or CallAudioState.ROUTE_SPEAKER,
    val isRecording: Boolean = false,
    val connectTimeMillis: Long = 0L,
    val lastSavedRecordingName: String? = null,
    val topLevelCallCount: Int = 0,
    val totalTrackedCallCount: Int = 0,
    val isHolding: Boolean = false,
    val canHold: Boolean = false,
    val canSwap: Boolean = false,
    val hasSecondaryCall: Boolean = false,
    val secondaryCallerName: String = "",
    val secondaryCallerNumber: String = "",
    val secondaryCallState: Int = Call.STATE_NEW,
    val canMerge: Boolean = false,
    val isConference: Boolean = false,
    val conferenceParticipantCount: Int = 0,
    val participants: List<ConferenceParticipantUi> = emptyList(),
    val activeSimLabel: String = "",
    val activeSimAccountId: String = "",
    val alternateSimLabel: String = "",
    val alternateSimAccountId: String = "",
    val canSwitchSim: Boolean = false,
    val isSwitchingSim: Boolean = false
)

object CallManager {

    private const val SIM_SWITCH_REDIAL_DELAY_MS = 350L
    private const val SIM_SWITCH_SAFETY_TIMEOUT_MS = 3200L

    private val _uiState = MutableStateFlow(CallUiState())
    val uiState: StateFlow<CallUiState> = _uiState.asStateFlow()

    private val _callsFlow = MutableStateFlow<List<Call>>(emptyList())
    val callsFlow: StateFlow<List<Call>> = _callsFlow.asStateFlow()

    private val trackedCalls = CopyOnWriteArrayList<Call>()
    private val callIdentities = ConcurrentHashMap<Call, ResolvedCallIdentity>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val isSimSwitchPending = AtomicBoolean(false)

    @Volatile
    private var pendingSwitchTargetSimLabel: String = ""

    @Volatile
    private var lastSwitchableNumber: String = ""

    @Volatile
    private var lastSwitchableAccountId: String = ""

    private var simSwitchTimeoutRunnable: Runnable? = null

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
        trackedCalls.clear()
        callIdentities.clear()
        _callsFlow.value = emptyList()
        if (isSimSwitchPending.get()) {
            _uiState.update { prev ->
                CallUiState(
                    lastSavedRecordingName = prev.lastSavedRecordingName,
                    callerName = prev.callerName,
                    callerNumber = prev.callerNumber,
                    alternateSimLabel = pendingSwitchTargetSimLabel,
                    isSwitchingSim = true
                )
            }
        } else {
            _uiState.value = CallUiState()
        }
        serviceRef = null
    }

    fun isSimSwitchInProgress(): Boolean = isSimSwitchPending.get()

    /**
     * Updates the tracked multi-call list from CustomInCallService (or single-call compat callers).
     */
    fun syncTrackedCalls(
        calls: List<Call>,
        resolver: ((Call) -> ResolvedCallIdentity)? = null
    ) {
        trackedCalls.clear()
        trackedCalls.addAll(calls)
        val snapshot = trackedCalls.toList()
        _callsFlow.value = snapshot

        val currentCallSet = HashSet<Call>(snapshot.size + 4)
        snapshot.forEach { call ->
            currentCallSet.add(call)
            call.children.orEmpty().forEach { child ->
                currentCallSet.add(child)
                if (resolver != null && !callIdentities.containsKey(child)) {
                    callIdentities[child] = resolver(child)
                }
            }
            if (resolver != null) {
                callIdentities[call] = resolver(call)
            }
        }
        callIdentities.keys.retainAll(currentCallSet)

        if (snapshot.isEmpty()) {
            stopRecordingIfNeeded()
            _uiState.update { prev ->
                if (isSimSwitchPending.get()) {
                    CallUiState(
                        lastSavedRecordingName = prev.lastSavedRecordingName,
                        callerName = prev.callerName,
                        callerNumber = prev.callerNumber,
                        alternateSimLabel = pendingSwitchTargetSimLabel,
                        isSwitchingSim = true
                    )
                } else {
                    CallUiState(lastSavedRecordingName = prev.lastSavedRecordingName)
                }
            }
            return
        }

        val topLevelCalls = resolveTopLevelCalls(snapshot)
        val primaryCall = selectPrimaryCall(topLevelCalls, snapshot)
        val secondaryCall = selectSecondaryCall(topLevelCalls, primaryCall)

        val primaryState = primaryCall?.let { getCompatState(it) } ?: Call.STATE_NEW
        val primaryDetails = primaryCall?.details
        val children = primaryCall?.children.orEmpty()
        val isConference = primaryCall != null && (
            (primaryDetails?.hasProperty(Call.Details.PROPERTY_CONFERENCE) == true) ||
                children.isNotEmpty()
            )

        val participants = children.map { child ->
            val childIdentity = resolveIdentityForCall(child, resolver)
            val childCapabilities = child.details?.callCapabilities ?: 0
            ConferenceParticipantUi(
                callKey = System.identityHashCode(child),
                displayName = childIdentity.displayName,
                phoneNumber = childIdentity.phoneNumber,
                callState = getCompatState(child),
                canSplit = canSplitParticipant(childCapabilities),
                canDrop = getCompatState(child) != Call.STATE_DISCONNECTED
            )
        }

        val primaryIdentity = primaryCall?.let { resolveIdentityForCall(it, resolver) }
            ?: ResolvedCallIdentity("", "")
        val displayPrimaryName = if (isConference) {
            if (participants.isNotEmpty()) {
                "Conference Call (${participants.size} Participants)"
            } else {
                "Conference Call"
            }
        } else {
            primaryIdentity.displayName
        }
        val displayPrimaryNumber = if (isConference && participants.isNotEmpty()) {
            participants.joinToString(", ") { p -> p.displayName.ifBlank { p.phoneNumber } }
        } else {
            primaryIdentity.phoneNumber
        }

        val secondaryIdentity = secondaryCall?.let { resolveIdentityForCall(it, resolver) }
        val secondaryState = secondaryCall?.let { getCompatState(it) } ?: Call.STATE_NEW

        val topLevelStates = topLevelCalls.map { getCompatState(it) }
        val holdSwapAction = resolveHoldSwapAction(topLevelStates)
        val primaryCapabilities = primaryDetails?.callCapabilities ?: 0
        val canHoldPrimary =
            (primaryCapabilities and Call.Details.CAPABILITY_HOLD) != 0 ||
                (primaryCapabilities and Call.Details.CAPABILITY_SUPPORT_HOLD) != 0
        val hasMergeCap = (primaryCapabilities and Call.Details.CAPABILITY_MERGE_CONFERENCE) != 0
        val hasConferenceables = (primaryCall?.conferenceableCalls?.isNotEmpty() == true)
        val canMerge = shouldEnableMergeCalls(
            topLevelCallCount = topLevelCalls.count { getCompatState(it) != Call.STATE_DISCONNECTED },
            hasMergeCapability = hasMergeCap,
            hasConferenceableCalls = hasConferenceables
        )

        val connectTime = primaryDetails?.connectTimeMillis ?: 0L

        if (!isConference && primaryIdentity.phoneNumber.isNotBlank()) {
            lastSwitchableNumber = primaryIdentity.phoneNumber
            primaryDetails?.accountHandle?.id?.trim()?.takeIf { it.isNotBlank() }?.let { accId ->
                lastSwitchableAccountId = accId
            }
        }

        val sims = serviceRef?.get()?.applicationContext?.let { ctx ->
            DualSimManager.getAvailableSims(ctx)
        }.orEmpty()
        val rawActiveAccountId = primaryDetails?.accountHandle?.id?.trim().orEmpty()
        val activeSim = DualSimManager.findSimByAccountId(sims, rawActiveAccountId)
            ?: primaryDetails?.accountHandle?.let { handle ->
                sims.firstOrNull { it.accountHandle == handle }
            }
            ?: if (rawActiveAccountId.isBlank()) sims.firstOrNull() else null
        val effectiveActiveId = activeSim?.accountId ?: rawActiveAccountId
        val alternateSim = DualSimManager.resolveAlternateSim(sims, effectiveActiveId)
        val canSwitchSim = !isConference &&
            primaryState != Call.STATE_RINGING &&
            primaryState != Call.STATE_DISCONNECTED &&
            primaryState != Call.STATE_DISCONNECTING &&
            sims.size >= 2 &&
            alternateSim != null &&
            (primaryIdentity.phoneNumber.isNotBlank() || lastSwitchableNumber.isNotBlank())

        _uiState.update { prev ->
            prev.copy(
                hasActiveCall = true,
                callerName = displayPrimaryName,
                callerNumber = displayPrimaryNumber,
                callerLocation = if (isConference) {
                    "Multi-Party Conference (${participants.size.coerceAtLeast(2)})"
                } else {
                    primaryIdentity.location.ifBlank {
                        CallerIdResolver.resolveLocation(primaryIdentity.phoneNumber)
                    }
                },
                callerGeoLine = if (isConference) {
                    ""
                } else {
                    primaryIdentity.locationDetails.ifBlank {
                        CallerIdResolver.resolveGeo(primaryIdentity.phoneNumber).formattedStructuredLine()
                    }
                },
                callerSocialSummary = if (isConference) "" else primaryIdentity.socialSummary,
                isUnknownCaller = !isConference && primaryIdentity.isUnknownNumber,
                callerIdentitySource = if (isConference) {
                    "Conference Call"
                } else {
                    primaryIdentity.identitySource
                },
                callState = primaryState,
                connectTimeMillis = if (connectTime > 0L) connectTime else prev.connectTimeMillis,
                topLevelCallCount = topLevelCalls.size,
                totalTrackedCallCount = snapshot.size,
                isHolding = primaryState == Call.STATE_HOLDING,
                canHold = holdSwapAction != HoldSwapAction.NONE || canHoldPrimary,
                canSwap = holdSwapAction == HoldSwapAction.SWAP,
                hasSecondaryCall = secondaryCall != null,
                secondaryCallerName = secondaryIdentity?.displayName.orEmpty(),
                secondaryCallerNumber = secondaryIdentity?.phoneNumber.orEmpty(),
                secondaryCallState = secondaryState,
                canMerge = canMerge,
                isConference = isConference,
                conferenceParticipantCount = participants.size,
                participants = participants,
                activeSimLabel = activeSim?.displayLabel().orEmpty(),
                activeSimAccountId = effectiveActiveId,
                alternateSimLabel = alternateSim?.shortLabel().orEmpty(),
                alternateSimAccountId = alternateSim?.accountId.orEmpty(),
                canSwitchSim = canSwitchSim,
                isSwitchingSim = isSimSwitchPending.get()
            )
        }
    }

    /**
     * Backward-compatible convenience method for single-call updates.
     */
    fun setCurrentCall(call: Call?, callerName: String, callerNumber: String) {
        if (call == null) {
            if (trackedCalls.isEmpty()) {
                stopRecordingIfNeeded()
                _uiState.value = CallUiState()
            } else {
                syncTrackedCalls(trackedCalls.toList())
            }
            return
        }
        val info = CallerIdResolver.resolveInstant(
            rawNumber = callerNumber,
            telecomDisplayName = callerName
        )
        callIdentities[call] = ResolvedCallIdentity(
            displayName = callerName.ifBlank { info.displayName },
            phoneNumber = callerNumber,
            location = info.location,
            isUnknownNumber = callerName.isBlank() || info.isUnknownNumber,
            identitySource = info.sourceLabel,
            locationDetails = info.structuredLocationLine(),
            socialSummary = info.socialBadgesSummary()
        )
        if (!trackedCalls.contains(call)) {
            trackedCalls.add(call)
        }
        syncTrackedCalls(trackedCalls.toList())
    }

    fun onCallAdded(
        call: Call,
        callerName: String,
        callerNumber: String,
        allCalls: List<Call>,
        resolver: ((Call) -> ResolvedCallIdentity)? = null
    ) {
        if (isSimSwitchPending.compareAndSet(true, false)) {
            simSwitchTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
            simSwitchTimeoutRunnable = null
            pendingSwitchTargetSimLabel = ""
        }
        val resolved = resolver?.invoke(call) ?: run {
            val info = CallerIdResolver.resolveInstant(
                rawNumber = callerNumber,
                telecomDisplayName = callerName
            )
            ResolvedCallIdentity(
                displayName = callerName.ifBlank { info.displayName },
                phoneNumber = callerNumber,
                location = info.location,
                isUnknownNumber = info.isUnknownNumber,
                identitySource = info.sourceLabel,
                locationDetails = info.structuredLocationLine(),
                socialSummary = info.socialBadgesSummary()
            )
        }
        callIdentities[call] = resolved
        syncTrackedCalls(allCalls, resolver)
    }

    /**
     * Mid-call Dual SIM switcher: if the active SIM has zero balance or weak signal while
     * calling/active, disconnects the current leg and redials the same number via the alternate SIM
     * while holding InCallActivity open during the 350ms handoff.
     */
    fun switchSimToAlternate(context: Context): Boolean {
        val appCtx = context.applicationContext
        val sims = DualSimManager.getAvailableSims(appCtx)
        if (sims.size < 2) return false

        val primaryCall = getPrimaryCall()
        val currentAccountId = primaryCall?.details?.accountHandle?.id?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: _uiState.value.activeSimAccountId.ifBlank { lastSwitchableAccountId }
        val alternateSim = DualSimManager.resolveAlternateSim(sims, currentAccountId) ?: return false

        val targetNumber = (
            primaryCall?.details?.handle?.schemeSpecificPart?.trim().orEmpty()
                .ifBlank { _uiState.value.callerNumber.trim() }
                .ifBlank { lastSwitchableNumber.trim() }
            )
        if (targetNumber.isBlank() || _uiState.value.isConference) return false

        if (!isSimSwitchPending.compareAndSet(false, true)) return false

        val targetShortLabel = alternateSim.shortLabel()
        pendingSwitchTargetSimLabel = targetShortLabel

        simSwitchTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        val timeoutRunnable = Runnable {
            if (isSimSwitchPending.compareAndSet(true, false)) {
                pendingSwitchTargetSimLabel = ""
                _uiState.update { it.copy(isSwitchingSim = false) }
            }
        }
        simSwitchTimeoutRunnable = timeoutRunnable
        mainHandler.postDelayed(timeoutRunnable, SIM_SWITCH_SAFETY_TIMEOUT_MS)

        _uiState.update {
            it.copy(
                isSwitchingSim = true,
                alternateSimLabel = targetShortLabel,
                alternateSimAccountId = alternateSim.accountId
            )
        }

        val callsToDisconnect = trackedCalls.filter {
            val st = getCompatState(it)
            st != Call.STATE_DISCONNECTED && st != Call.STATE_DISCONNECTING
        }
        if (callsToDisconnect.isEmpty()) {
            primaryCall?.disconnect()
        } else {
            callsToDisconnect.forEach { call ->
                runCatching { call.disconnect() }
            }
        }

        mainHandler.postDelayed({
            val placed = DialerHelper.placeCall(appCtx, targetNumber, alternateSim.accountHandle)
            if (!placed && isSimSwitchPending.compareAndSet(true, false)) {
                simSwitchTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
                simSwitchTimeoutRunnable = null
                pendingSwitchTargetSimLabel = ""
                _uiState.update { it.copy(isSwitchingSim = false) }
            }
        }, SIM_SWITCH_REDIAL_DELAY_MS)

        return true
    }

    fun onCallRemoved(
        call: Call,
        remainingCalls: List<Call>,
        resolver: ((Call) -> ResolvedCallIdentity)? = null
    ) {
        callIdentities.remove(call)
        syncTrackedCalls(remainingCalls, resolver)
    }

    fun updateCallState(
        call: Call,
        state: Int,
        resolver: ((Call) -> ResolvedCallIdentity)? = null
    ) {
        if (!trackedCalls.contains(call)) {
            trackedCalls.addIfAbsent(call)
        }
        syncTrackedCalls(trackedCalls.toList(), resolver)
        if (state == Call.STATE_DISCONNECTED &&
            !shouldKeepRecordingActive(trackedCalls.map { getCompatState(it) }, trackedCalls.size)
        ) {
            stopRecordingIfNeeded()
        }
    }

    fun updateAudioState(
        isMuted: Boolean,
        isSpeakerOn: Boolean,
        audioRoute: Int = if (isSpeakerOn) CallAudioState.ROUTE_SPEAKER else CallAudioState.ROUTE_EARPIECE,
        supportedAudioRoutes: Int = CallAudioState.ROUTE_EARPIECE or CallAudioState.ROUTE_SPEAKER
    ) {
        _uiState.update {
            it.copy(
                isMuted = isMuted,
                isSpeakerOn = isSpeakerOn,
                audioRoute = audioRoute,
                supportedAudioRoutes = supportedAudioRoutes
            )
        }
    }

    fun answerCall() {
        val ringingCall = trackedCalls.firstOrNull { getCompatState(it) == Call.STATE_RINGING }
            ?: getPrimaryCall()
        ringingCall?.answer(VideoProfile.STATE_AUDIO_ONLY)
    }

    fun declineOrEndCall() {
        val ringingCall = trackedCalls.firstOrNull { getCompatState(it) == Call.STATE_RINGING }
        if (ringingCall != null && getPrimaryCall() == ringingCall) {
            ringingCall.reject(false, null)
            return
        }
        val call = getPrimaryCall() ?: return
        val state = getCompatState(call)
        if (state == Call.STATE_RINGING) {
            call.reject(false, null)
        } else {
            call.disconnect()
        }
    }

    fun rejectWithQuickMessage(message: String) {
        val ringingCall = trackedCalls.firstOrNull { getCompatState(it) == Call.STATE_RINGING }
            ?: getPrimaryCall()
        ringingCall?.reject(true, message)
    }

    /**
     * Puts the currently active top-level call on hold before opening the Dialpad / Contacts
     * picker to place an additional call. Never interrupts an ongoing call recording.
     */
    fun prepareForAddCall() {
        val topLevel = resolveTopLevelCalls(trackedCalls)
        val active = topLevel.firstOrNull { getCompatState(it) == Call.STATE_ACTIVE }
        if (active != null) {
            active.hold()
        }
    }

    /**
     * Hold/Unhold when 1 call is present, or Swap (unhold held + hold active) when 2+ calls exist.
     */
    fun toggleHoldOrSwap() {
        val topLevel = resolveTopLevelCalls(trackedCalls)
            .filter { getCompatState(it) != Call.STATE_DISCONNECTED }
        if (topLevel.isEmpty()) return

        if (topLevel.size >= 2) {
            val active = topLevel.firstOrNull { getCompatState(it) == Call.STATE_ACTIVE }
            val held = topLevel.firstOrNull { it != active && getCompatState(it) == Call.STATE_HOLDING }
                ?: topLevel.firstOrNull { it != active }
            held?.unhold()
            if (active != null && active != held) {
                active.hold()
            }
        } else {
            val single = topLevel.first()
            if (getCompatState(single) == Call.STATE_HOLDING) {
                single.unhold()
            } else {
                single.hold()
            }
        }
    }

    /**
     * Merges active & held calls into a Conference Call.
     */
    fun mergeCalls(): Boolean {
        val topLevel = resolveTopLevelCalls(trackedCalls)
            .filter { getCompatState(it) != Call.STATE_DISCONNECTED }
        if (topLevel.isEmpty()) return false

        val primary = topLevel.firstOrNull { getCompatState(it) == Call.STATE_ACTIVE }
            ?: topLevel.firstOrNull()
            ?: return false

        val capabilities = primary.details?.callCapabilities ?: 0
        if ((capabilities and Call.Details.CAPABILITY_MERGE_CONFERENCE) != 0) {
            primary.mergeConference()
            return true
        }

        val other = topLevel.firstOrNull { it != primary && getCompatState(it) == Call.STATE_HOLDING }
            ?: topLevel.firstOrNull { it != primary }
            ?: primary.conferenceableCalls.firstOrNull()

        if (other != null) {
            primary.conference(other)
            return true
        }
        return false
    }

    /**
     * Splits a single participant out of a conference into a private 1:1 call while placing the rest
     * of the conference on hold.
     */
    fun splitParticipant(callKey: Int): Boolean {
        val childCall = findParticipantCallByKey(callKey) ?: return false
        val capabilities = childCall.details?.callCapabilities ?: 0
        if (!canSplitParticipant(capabilities)) {
            return false
        }
        childCall.splitFromConference()
        return true
    }

    /**
     * Disconnects/drops only the selected participant from the conference without ending the
     * conference call itself.
     */
    fun dropParticipant(callKey: Int): Boolean {
        val childCall = findParticipantCallByKey(callKey) ?: return false
        childCall.disconnect()
        return true
    }

    fun toggleMute() {
        val service = serviceRef?.get() ?: return
        val nextMuted = !_uiState.value.isMuted
        service.setMuted(nextMuted)
        _uiState.update { it.copy(isMuted = nextMuted) }
    }

    fun toggleSpeaker() {
        setSpeakerEnabled(!_uiState.value.isSpeakerOn)
    }

    fun isServiceAttached(): Boolean = serviceRef?.get() != null

    @Suppress("DEPRECATION")
    fun setSpeakerEnabled(enabled: Boolean) {
        val route = if (enabled) CallAudioState.ROUTE_SPEAKER else CallAudioState.ROUTE_EARPIECE
        setAudioRoute(route)
    }

    @Suppress("DEPRECATION")
    fun setAudioRoute(route: Int) {
        val service = serviceRef?.get()
        service?.setAudioRoute(route)
        if (service != null || _uiState.value.hasActiveCall) {
            _uiState.update {
                it.copy(
                    audioRoute = route,
                    isSpeakerOn = (route and CallAudioState.ROUTE_SPEAKER) != 0
                )
            }
        }
    }

    fun playDtmfTone(digit: Char) {
        val call = getPrimaryCall() ?: return
        call.playDtmfTone(digit)
        call.stopDtmfTone()
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
            if (!com.example.billing.SubscriptionEnforcementService.canStartCallRecording(context)) {
                _uiState.update { it.copy(isRecording = false) }
                return false
            }
            VoipCallRecorderManager.onCellularCallStarted()
            val numberOrLabel = buildRecordingLabel()
            val started = mgr.startRecording(numberOrLabel, CallRecordingSource.CELLULAR)
            _uiState.update { it.copy(isRecording = started) }
            started
        }
    }

    fun startAutoRecordingIfEnabled(context: Context) {
        if (!com.example.billing.SubscriptionEnforcementService.canStartCallRecording(context)) {
            stopRecordingIfNeeded()
            return
        }
        val mgr = recorder ?: CallRecorder(context.applicationContext).also {
            recorder = it
        }
        if (!mgr.isRecording) {
            VoipCallRecorderManager.onCellularCallStarted()
            val numberOrLabel = buildRecordingLabel()
            val started = mgr.startRecording(numberOrLabel, CallRecordingSource.CELLULAR)
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

    fun getTrackedCallsCount(): Int = trackedCalls.size

    private fun buildRecordingLabel(): String {
        val state = _uiState.value
        return if (state.isConference) {
            "Conference_${state.conferenceParticipantCount.coerceAtLeast(2)}"
        } else {
            state.callerNumber.ifBlank { state.callerName }
        }
    }

    private fun findParticipantCallByKey(callKey: Int): Call? {
        for (call in trackedCalls) {
            call.children.orEmpty().forEach { child ->
                if (System.identityHashCode(child) == callKey) {
                    return child
                }
            }
            if (call.parent != null && System.identityHashCode(call) == callKey) {
                return call
            }
        }
        return null
    }

    private fun resolveIdentityForCall(
        call: Call,
        resolver: ((Call) -> ResolvedCallIdentity)?
    ): ResolvedCallIdentity {
        if (resolver != null) {
            return resolver(call).also { callIdentities[call] = it }
        }
        val cached = callIdentities[call]
        if (cached != null && (cached.displayName.isNotBlank() || cached.phoneNumber.isNotBlank())) {
            return cached
        }
        val number = call.details?.handle?.schemeSpecificPart.orEmpty()
        val info = CallerIdResolver.resolveInstant(
            rawNumber = number,
            telecomDisplayName = call.details?.callerDisplayName
        )
        return ResolvedCallIdentity(
            displayName = info.displayName,
            phoneNumber = number,
            location = info.location,
            isUnknownNumber = info.isUnknownNumber,
            identitySource = info.sourceLabel,
            locationDetails = info.structuredLocationLine(),
            socialSummary = info.socialBadgesSummary()
        ).also { callIdentities[call] = it }
    }

    private fun resolveTopLevelCalls(calls: List<Call>): List<Call> {
        val topLevel = calls.filter { it.parent == null }
        return if (topLevel.isNotEmpty()) topLevel else calls
    }

    private fun getPrimaryCall(): Call? {
        val snapshot = trackedCalls.toList()
        if (snapshot.isEmpty()) return null
        return selectPrimaryCall(resolveTopLevelCalls(snapshot), snapshot)
    }

    private fun selectPrimaryCall(topLevelCalls: List<Call>, allCalls: List<Call>): Call? {
        val pool = topLevelCalls.ifEmpty { allCalls }
        return pool.firstOrNull { getCompatState(it) == Call.STATE_RINGING }
            ?: pool.firstOrNull { getCompatState(it) == Call.STATE_DIALING || getCompatState(it) == Call.STATE_CONNECTING }
            ?: pool.firstOrNull { getCompatState(it) == Call.STATE_ACTIVE }
            ?: pool.firstOrNull { getCompatState(it) == Call.STATE_HOLDING }
            ?: pool.firstOrNull()
    }

    private fun selectSecondaryCall(topLevelCalls: List<Call>, primaryCall: Call?): Call? {
        if (primaryCall == null || topLevelCalls.size < 2) return null
        return topLevelCalls.firstOrNull {
            it != primaryCall && getCompatState(it) != Call.STATE_DISCONNECTED
        }
    }

    @Suppress("DEPRECATION")
    fun getCompatState(call: Call): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            call.details?.state ?: call.state
        } else {
            call.state
        }
    }

    /**
     * Pure helper: recording stays continuous across hold, add-call dialing, and conference merge
     * transitions as long as the tracked call list is non-empty and at least one call is not disconnected.
     */
    fun shouldKeepRecordingActive(callStates: List<Int>, totalTrackedCalls: Int): Boolean {
        if (totalTrackedCalls <= 0 || callStates.isEmpty()) return false
        return callStates.any { state ->
            state != Call.STATE_DISCONNECTED && state != Call.STATE_DISCONNECTING
        }
    }

    /**
     * Pure helper: determines whether the Merge Calls button should be shown & enabled.
     */
    fun shouldEnableMergeCalls(
        topLevelCallCount: Int,
        hasMergeCapability: Boolean,
        hasConferenceableCalls: Boolean
    ): Boolean {
        return topLevelCallCount >= 2 || hasMergeCapability || hasConferenceableCalls
    }

    /**
     * Pure helper: determines the behavior of the Hold / Swap button.
     */
    fun resolveHoldSwapAction(topLevelStates: List<Int>): HoldSwapAction {
        val liveStates = topLevelStates.filter { it != Call.STATE_DISCONNECTED }
        if (liveStates.isEmpty()) return HoldSwapAction.NONE
        if (liveStates.size >= 2) {
            return HoldSwapAction.SWAP
        }
        return if (liveStates.first() == Call.STATE_HOLDING) {
            HoldSwapAction.UNHOLD
        } else {
            HoldSwapAction.HOLD
        }
    }

    fun canSplitParticipant(capabilities: Int): Boolean {
        return (capabilities and Call.Details.CAPABILITY_SEPARATE_FROM_CONFERENCE) != 0
    }

    /**
     * Returns true when an active call is part of a multi-party session (either merged
     * conference or 2+ simultaneous call legs).
     */
    fun isActiveMultiPartyCall(state: CallUiState = _uiState.value): Boolean {
        return state.hasActiveCall &&
            state.callState == Call.STATE_ACTIVE &&
            (state.isConference ||
                state.topLevelCallCount >= 2 ||
                state.totalTrackedCallCount >= 2 ||
                state.conferenceParticipantCount >= 2)
    }

    @androidx.annotation.VisibleForTesting
    internal fun publishStateForTesting(state: CallUiState) {
        _uiState.value = state
    }
}
