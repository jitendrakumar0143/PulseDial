package com.example.billing

import android.content.Context
import android.util.Log
import com.example.data.DialerPreferences
import com.example.data.firebase.GoogleAuthHelper
import com.example.data.firebase.RealtimeDatabaseSync
import com.example.data.firebase.UserEntitlement
import com.example.telecom.CallManager
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.auth
import kotlin.math.ceil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Immutable snapshot of the real-time subscription and 7-day trial enforcement state
 * derived from `/users/{uid}/subscriptionStatus`, `/users/{uid}/isPremium`, and
 * `/users/{uid}/installDate`.
 */
data class SubscriptionEnforcementState(
    val uid: String? = null,
    val subscriptionStatus: String = UserEntitlement.STATUS_TRIAL,
    val isPremium: Boolean = false,
    val isBlocked: Boolean = false,
    val installDateMs: Long = 0L,
    val subscriptionExpiresAtMs: Long = 0L,
    val bonusTrialDays: Int = 0,
    val trialDaysConfig: Int = DialerPreferences.TRIAL_DAYS,
    val trialDaysElapsed: Int = 0,
    val trialDaysRemaining: Int = DialerPreferences.TRIAL_DAYS,
    val isTrialExpired: Boolean = false,
    val isSubscriptionActive: Boolean = true,
    val isRecordingAllowed: Boolean = false,
    val shouldRestrictUiToAccountTab: Boolean = false,
    val statusBannerText: String = "Free Plan — Calling & Contacts Active",
    val lastEvaluatedAtMs: Long = 0L
)

/**
 * Real-time events emitted when `/users/{uid}/subscriptionStatus` or `/users/{uid}/isPremium`
 * transitions or when the 7-day trial expires.
 */
sealed interface SubscriptionRestrictionEvent {
    data class RecordingRestricted(val reason: String) : SubscriptionRestrictionEvent
    data class TrialExpiredRedirectToPayment(val message: String) : SubscriptionRestrictionEvent
    data object SubscriptionUnlocked : SubscriptionRestrictionEvent
    data object AccountBlockedByAdmin : SubscriptionRestrictionEvent
    data object UserDeletedByAdmin : SubscriptionRestrictionEvent
}

/**
 * Dedicated Service Layer that monitors the `/users/{uid}/subscriptionStatus` and
 * `/users/{uid}/isPremium` (along with `/users/{uid}/installDate`, `/users/{uid}/isBlocked`,
 * and `/users/{uid}/subscriptionExpiresAtMs`) nodes in Firebase Realtime Database (`onValue` /
 * `ValueEventListener`) and enforces:
 * 1. The 7-Day Free Trial (`(System.currentTimeMillis() - installDate) / (1000 * 60 * 60 * 24) >= 7`)
 * 2. Real-time UI restrictions (locking tabs and redirecting expired users to the Account payment tab)
 * 3. Real-time Call Recording restrictions (stopping active recording mid-call and blocking
 *    auto/manual call recording when `isPremium == false` and the 7-day trial or subscription is expired).
 */
object SubscriptionEnforcementService {

    private const val TAG = "SubEnforcementService"
    private const val MILLIS_PER_DAY = DialerPreferences.ONE_DAY_MILLIS

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(SubscriptionEnforcementState())
    val state: StateFlow<SubscriptionEnforcementState> = _state.asStateFlow()

    private val _restrictionEvents = MutableSharedFlow<SubscriptionRestrictionEvent>(extraBufferCapacity = 16)
    val restrictionEvents: SharedFlow<SubscriptionRestrictionEvent> = _restrictionEvents.asSharedFlow()

    private var appContext: Context? = null
    private var isInitialized = false
    private var activeUid: String? = null
    private var rtdbNodeListenersCleanup: (() -> Unit)? = null
    private var tickerJob: Job? = null

    @Volatile
    private var liveSubscriptionStatus: String = UserEntitlement.STATUS_TRIAL

    @Volatile
    private var liveIsPremium: Boolean = false

    @Volatile
    private var liveIsBlocked: Boolean = false

    @Volatile
    private var liveInstallDateMs: Long = 0L

    @Volatile
    private var liveExpiresAtMs: Long = 0L

    @Volatile
    private var liveBonusTrialDays: Int = 0

    @Volatile
    private var hasLiveNodeState: Boolean = false

    /**
     * Initializes the service layer and binds to Firebase Auth state changes.
     */
    fun init(context: Context) {
        val cleanContext = context.applicationContext
        appContext = cleanContext
        val prefs = DialerPreferences(cleanContext)
        liveInstallDateMs = prefs.firstLaunchTime
        liveIsPremium = prefs.isPremiumUser
        liveIsBlocked = prefs.adminBlockedUser
        liveExpiresAtMs = prefs.subscriptionExpiresAtMs
        liveBonusTrialDays = prefs.adminBonusTrialDays
        recalculateAndEnforce(prefs)

        if (isInitialized) return
        val firebaseAvailable = runCatching { FirebaseApp.getApps(cleanContext).isNotEmpty() }
            .getOrDefault(false)
        if (!firebaseAvailable) return

        val auth = runCatching { Firebase.auth }.getOrNull()
        isInitialized = true
        auth?.addAuthStateListener { firebaseAuth ->
            val uid = firebaseAuth.currentUser?.uid
                ?: GoogleAuthHelper.resolveActiveUid(cleanContext)
            if (uid != null) {
                startMonitoringUser(uid, cleanContext)
            } else {
                stopMonitoring()
            }
        }
        val initialUid = runCatching { auth?.currentUser?.uid }.getOrNull()
            ?: GoogleAuthHelper.resolveActiveUid(cleanContext)
        if (initialUid != null) {
            startMonitoringUser(initialUid, cleanContext)
        }
    }

    /**
     * Starts real-time `onValue` monitoring on `/users/{uid}/subscriptionStatus` and
     * `/users/{uid}/isPremium` (plus `/users/{uid}/installDate`, `/users/{uid}/isBlocked`,
     * and `/users/{uid}/subscriptionExpiresAtMs`), and runs a continuous 1-second ticker
     * to enforce the 7-day trial boundary in real time.
     */
    fun startMonitoringUser(uid: String, context: Context? = appContext) {
        val ctx = context?.applicationContext ?: appContext ?: return
        appContext = ctx
        if (activeUid == uid && rtdbNodeListenersCleanup != null) return
        stopMonitoring()
        activeUid = uid

        val prefs = DialerPreferences(ctx)
        liveInstallDateMs = prefs.firstLaunchTime
        liveIsPremium = prefs.isPremiumUser
        liveIsBlocked = prefs.adminBlockedUser
        liveExpiresAtMs = prefs.subscriptionExpiresAtMs
        liveBonusTrialDays = prefs.adminBonusTrialDays
        recalculateAndEnforce(prefs)

        rtdbNodeListenersCleanup = RealtimeDatabaseSync.attachSubscriptionNodesListeners(
            context = ctx,
            uid = uid,
            onSubscriptionStatusChanged = { newStatus ->
                Log.d(TAG, "RTDB /users/$uid/subscriptionStatus -> $newStatus")
                applyNodeUpdate(
                    context = ctx,
                    subscriptionStatus = newStatus
                )
            },
            onIsPremiumChanged = { newPremium ->
                Log.d(TAG, "RTDB /users/$uid/isPremium -> $newPremium")
                applyNodeUpdate(
                    context = ctx,
                    isPremium = newPremium
                )
            },
            onInstallDateChanged = { newInstallDateMs ->
                applyNodeUpdate(
                    context = ctx,
                    installDateMs = newInstallDateMs
                )
            },
            onIsBlockedChanged = { newBlocked ->
                applyNodeUpdate(
                    context = ctx,
                    isBlocked = newBlocked
                )
            },
            onExpiresAtChanged = { newExpiresAtMs ->
                applyNodeUpdate(
                    context = ctx,
                    subscriptionExpiresAtMs = newExpiresAtMs ?: 0L
                )
            },
            onBonusTrialDaysChanged = { newBonusDays ->
                applyNodeUpdate(
                    context = ctx,
                    bonusTrialDays = newBonusDays
                )
            },
            onUserDeleted = {
                Log.w(TAG, "RTDB /users/$uid deleted by Admin — revoking premium immediately")
                handleUserDeletedByAdmin(ctx)
            },
            onFullSnapshot = { status, prem, blocked, installMs, expMs, bonusDays ->
                Log.d(TAG, "RTDB atomic /users/$uid -> status=$status, isPremium=$prem, blocked=$blocked, exp=$expMs")
                applyNodeUpdate(
                    context = ctx,
                    subscriptionStatus = status,
                    isPremium = prem,
                    isBlocked = blocked,
                    installDateMs = installMs,
                    subscriptionExpiresAtMs = expMs,
                    bonusTrialDays = bonusDays
                )
            }
        )

        tickerJob = scope.launch {
            while (isActive && activeUid == uid) {
                recalculateAndEnforce(prefs)
                delay(5000L)
            }
        }
    }

    /**
     * Synchronizes a full `UserEntitlement` snapshot (from RTDB `/users/{uid}` or Firestore)
     * into the enforcement service layer.
     */
    fun onUserEntitlementSynced(user: UserEntitlement, context: Context? = appContext) {
        val ctx = context?.applicationContext ?: appContext ?: return
        appContext = ctx
        activeUid = user.uid.ifBlank { activeUid }
        val installMs = user.installDate?.toDate()?.time
        val expMs = user.subscriptionExpiresAt?.toDate()?.time ?: 0L
        applyNodeUpdate(
            context = ctx,
            subscriptionStatus = user.subscriptionStatus,
            isPremium = user.isPremiumUser,
            isBlocked = user.isBlocked,
            installDateMs = installMs,
            subscriptionExpiresAtMs = expMs,
            bonusTrialDays = user.bonusTrialDays.toInt().coerceAtLeast(0)
        )
    }

    /**
     * Immediately revokes premium entitlements, stops active call recording, enforces
     * free-tier light theme, and emits [SubscriptionRestrictionEvent.UserDeletedByAdmin]
     * when the user's `/users/{uid}` node is deleted by Admin in RTDB.
     */
    fun handleUserDeletedByAdmin(
        context: Context? = appContext,
        nowMs: Long = -1L
    ): SubscriptionEnforcementState {
        val ctx = context?.applicationContext ?: appContext
        val prefs = ctx?.let { DialerPreferences(it) }
        val resolvedNowMs = if (nowMs > 0L) {
            nowMs
        } else {
            RealtimeDatabaseSync.currentServerNowMs(prefs?.serverClockOffsetMs ?: 0L)
        }
        liveIsPremium = false
        liveSubscriptionStatus = UserEntitlement.STATUS_EXPIRED
        liveExpiresAtMs = 0L
        liveIsBlocked = false
        liveBonusTrialDays = 0
        hasLiveNodeState = true

        CallManager.stopRecordingIfNeeded()

        val evaluated = if (prefs != null) {
            prefs.applyEnforcementBatch(
                isPremium = false,
                isBlocked = false,
                subscriptionExpiresAtMs = 0L,
                bonusTrialDays = 0,
                hideSubscriptionUi = false,
                delegatedAdmin = false,
                adminGrantedBy = ""
            )
            com.example.ui.theme.ThemeStudioManager.enforceEffectiveNightMode(prefs)
            recalculateAndEnforce(prefs, resolvedNowMs)
        } else {
            evaluateNodeState(
                uid = activeUid,
                subscriptionStatus = UserEntitlement.STATUS_EXPIRED,
                isPremium = false,
                isBlocked = false,
                installDateMs = liveInstallDateMs,
                subscriptionExpiresAtMs = 0L,
                bonusTrialDays = 0,
                trialDaysConfig = DialerPreferences.TRIAL_DAYS,
                allowCallRecordingConfig = false,
                nowMs = resolvedNowMs
            ).also { _state.value = it }
        }
        _restrictionEvents.tryEmit(SubscriptionRestrictionEvent.UserDeletedByAdmin)
        return evaluated
    }

    /**
     * Applies live updates from `/users/{uid}/subscriptionStatus` and `/users/{uid}/isPremium`,
     * updates local `DialerPreferences` with HMAC integrity, and immediately enforces
     * UI and Call Recording restrictions.
     */
    fun applyNodeUpdate(
        context: Context? = appContext,
        subscriptionStatus: String? = null,
        isPremium: Boolean? = null,
        isBlocked: Boolean? = null,
        installDateMs: Long? = null,
        subscriptionExpiresAtMs: Long? = null,
        bonusTrialDays: Int? = null,
        nowMs: Long = -1L
    ): SubscriptionEnforcementState {
        val ctx = context?.applicationContext ?: appContext
        val prefs = ctx?.let { DialerPreferences(it) }
        val resolvedNowMs = if (nowMs > 0L) {
            nowMs
        } else {
            RealtimeDatabaseSync.currentServerNowMs(prefs?.serverClockOffsetMs ?: 0L)
        }

        val cleanIncomingStatus = subscriptionStatus?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        if (cleanIncomingStatus != null) {
            liveSubscriptionStatus = cleanIncomingStatus
            if (isPremium == null) {
                when (cleanIncomingStatus) {
                    UserEntitlement.STATUS_ACTIVE -> liveIsPremium = true
                    UserEntitlement.STATUS_EXPIRED,
                    UserEntitlement.STATUS_REJECTED,
                    UserEntitlement.STATUS_BLOCKED,
                    "revoked" -> liveIsPremium = false
                }
            }
        }
        if (isPremium != null) {
            liveIsPremium = isPremium
            if (cleanIncomingStatus == null) {
                if (isPremium && liveSubscriptionStatus != UserEntitlement.STATUS_BLOCKED) {
                    liveSubscriptionStatus = UserEntitlement.STATUS_ACTIVE
                } else if (!isPremium && liveSubscriptionStatus == UserEntitlement.STATUS_ACTIVE) {
                    liveSubscriptionStatus = UserEntitlement.STATUS_TRIAL
                }
            }
        }
        if (isBlocked != null) {
            liveIsBlocked = isBlocked
        }
        if (installDateMs != null && installDateMs > 0L) {
            liveInstallDateMs = installDateMs
        } else if (liveInstallDateMs <= 0L && prefs != null) {
            liveInstallDateMs = prefs.firstLaunchTime
        }
        if (subscriptionExpiresAtMs != null) {
            liveExpiresAtMs = subscriptionExpiresAtMs
        } else if ((isPremium == true || cleanIncomingStatus == UserEntitlement.STATUS_ACTIVE) &&
            liveExpiresAtMs in 1L..resolvedNowMs
        ) {
            // Clear stale expired timestamp from a previous cycle while awaiting the new expiry node
            liveExpiresAtMs = 0L
        }
        if (bonusTrialDays != null) {
            liveBonusTrialDays = bonusTrialDays.coerceAtLeast(0)
        }
        hasLiveNodeState = true

        if (prefs != null) {
            val effectiveBlocked = liveIsBlocked || liveSubscriptionStatus == UserEntitlement.STATUS_BLOCKED
            val effectivePremium = !effectiveBlocked && (
                liveIsPremium || liveSubscriptionStatus == UserEntitlement.STATUS_ACTIVE
                ) && (liveExpiresAtMs <= 0L || resolvedNowMs < liveExpiresAtMs)

            prefs.applyEnforcementBatch(
                isPremium = effectivePremium,
                isBlocked = effectiveBlocked,
                subscriptionExpiresAtMs = if (liveExpiresAtMs > 0L) liveExpiresAtMs else 0L,
                installDateMs = liveInstallDateMs.takeIf { it > 0L },
                bonusTrialDays = liveBonusTrialDays
            )
            return recalculateAndEnforce(prefs, resolvedNowMs)
        }

        val evaluated = evaluateNodeState(
            uid = activeUid,
            subscriptionStatus = liveSubscriptionStatus,
            isPremium = liveIsPremium,
            isBlocked = liveIsBlocked,
            installDateMs = liveInstallDateMs,
            subscriptionExpiresAtMs = liveExpiresAtMs,
            bonusTrialDays = liveBonusTrialDays,
            trialDaysConfig = DialerPreferences.TRIAL_DAYS,
            allowCallRecordingConfig = true,
            nowMs = resolvedNowMs
        )
        val previous = _state.value
        val materiallyChanged = previous.lastEvaluatedAtMs == 0L ||
            previous.copy(lastEvaluatedAtMs = evaluated.lastEvaluatedAtMs) != evaluated
        if (materiallyChanged) {
            _state.value = evaluated
        }
        return evaluated
    }

    /**
     * Pure, deterministic evaluator for `/users/{uid}/subscriptionStatus` and `/users/{uid}/isPremium`
     * alongside the 7-day trial calculation:
     * `daysUsed = (nowMs - installDateMs) / (1000 * 60 * 60 * 24)`.
     */
    fun evaluateNodeState(
        uid: String?,
        subscriptionStatus: String,
        isPremium: Boolean,
        isBlocked: Boolean = false,
        installDateMs: Long,
        subscriptionExpiresAtMs: Long = 0L,
        bonusTrialDays: Int = 0,
        trialDaysConfig: Int = DialerPreferences.TRIAL_DAYS,
        allowCallRecordingConfig: Boolean = true,
        nowMs: Long = System.currentTimeMillis()
    ): SubscriptionEnforcementState {
        val cleanStatus = subscriptionStatus.trim().lowercase().ifBlank { UserEntitlement.STATUS_TRIAL }
        val effectiveBlocked = isBlocked || cleanStatus == UserEntitlement.STATUS_BLOCKED
        val totalTrialDays = (trialDaysConfig.coerceIn(1, 365) + bonusTrialDays.coerceAtLeast(0))
            .coerceIn(1, 4000)
        val totalTrialMillis = totalTrialDays.toLong() * MILLIS_PER_DAY

        val safeInstallMs = if (installDateMs > 0L) installDateMs else nowMs
        val elapsedMillis = (nowMs - safeInstallMs).coerceAtLeast(0L)
        val daysElapsed = (elapsedMillis / MILLIS_PER_DAY).toInt()

        val remainingMillis = (safeInstallMs + totalTrialMillis) - nowMs
        val daysRemaining = if (remainingMillis <= 0L) {
            0
        } else {
            ceil(remainingMillis.toDouble() / MILLIS_PER_DAY.toDouble())
                .toInt()
                .coerceIn(1, totalTrialDays)
        }

        val isMonthlyExpired = subscriptionExpiresAtMs > 0L && nowMs >= subscriptionExpiresAtMs
        val effectivePremium = !effectiveBlocked &&
            (isPremium || cleanStatus == UserEntitlement.STATUS_ACTIVE) &&
            !isMonthlyExpired

        val statusForcesExpired = cleanStatus == UserEntitlement.STATUS_EXPIRED ||
            cleanStatus == UserEntitlement.STATUS_REJECTED
        val isTrialExpired = !effectivePremium && (
            daysElapsed >= totalTrialDays || remainingMillis <= 0L || statusForcesExpired || isMonthlyExpired
            )

        val isSubscriptionActive = !effectiveBlocked && (effectivePremium || !isTrialExpired)
        val isRecordingAllowed = effectivePremium && allowCallRecordingConfig
        val shouldRestrictUi = effectiveBlocked

        val bannerText = when {
            effectiveBlocked -> "Account Blocked by Admin"
            effectivePremium -> "Pro Plan Active"
            cleanStatus == UserEntitlement.STATUS_PENDING_APPROVAL ->
                "Verification Pending... Admin is checking your UTR"
            isMonthlyExpired -> "Subscription Expired — Free Plan (Calling & Contacts)"
            else -> "Free Plan — Upgrade to Pro for Recording, Dark Mode & 3D Themes"
        }

        return SubscriptionEnforcementState(
            uid = uid,
            subscriptionStatus = cleanStatus,
            isPremium = effectivePremium,
            isBlocked = effectiveBlocked,
            installDateMs = safeInstallMs,
            subscriptionExpiresAtMs = subscriptionExpiresAtMs,
            bonusTrialDays = bonusTrialDays.coerceAtLeast(0),
            trialDaysConfig = totalTrialDays,
            trialDaysElapsed = daysElapsed,
            trialDaysRemaining = if (effectivePremium) totalTrialDays else daysRemaining,
            isTrialExpired = isTrialExpired,
            isSubscriptionActive = isSubscriptionActive,
            isRecordingAllowed = isRecordingAllowed,
            shouldRestrictUiToAccountTab = shouldRestrictUi,
            statusBannerText = bannerText,
            lastEvaluatedAtMs = nowMs
        )
    }

    private fun recalculateAndEnforce(
        prefs: DialerPreferences,
        nowMs: Long = -1L
    ): SubscriptionEnforcementState {
        val resolvedNowMs = if (nowMs > 0L) {
            nowMs
        } else {
            RealtimeDatabaseSync.currentServerNowMs(prefs.serverClockOffsetMs)
        }
        val previous = _state.value
        val installMs = if (liveInstallDateMs > 0L) liveInstallDateMs else prefs.firstLaunchTime
        val effectivePremiumSignal = if (hasLiveNodeState) {
            liveIsPremium
        } else {
            liveIsPremium || prefs.isPremiumActive(resolvedNowMs)
        }
        val effectiveBlockedSignal = if (hasLiveNodeState) {
            liveIsBlocked
        } else {
            liveIsBlocked || prefs.adminBlockedUser
        }
        val evaluated = evaluateNodeState(
            uid = activeUid,
            subscriptionStatus = liveSubscriptionStatus,
            isPremium = effectivePremiumSignal,
            isBlocked = effectiveBlockedSignal,
            installDateMs = installMs,
            subscriptionExpiresAtMs = if (hasLiveNodeState) liveExpiresAtMs else prefs.subscriptionExpiresAtMs,
            bonusTrialDays = if (hasLiveNodeState) liveBonusTrialDays else prefs.adminBonusTrialDays,
            trialDaysConfig = prefs.configuredTrialDays,
            allowCallRecordingConfig = prefs.allowCallRecordingConfig,
            nowMs = resolvedNowMs
        )

        val materiallyChanged = previous.lastEvaluatedAtMs == 0L ||
            previous.copy(lastEvaluatedAtMs = evaluated.lastEvaluatedAtMs) != evaluated
        if (materiallyChanged) {
            _state.value = evaluated
        }

        // Immediately stop any active call recording if recording is no longer allowed
        if (!evaluated.isRecordingAllowed) {
            val stoppedFile = CallManager.stopRecordingIfNeeded()
            if (stoppedFile != null) {
                _restrictionEvents.tryEmit(
                    SubscriptionRestrictionEvent.RecordingRestricted(evaluated.statusBannerText)
                )
            }
        }

        // Emit transition events for UI reaction
        if (materiallyChanged && previous.lastEvaluatedAtMs > 0L) {
            if (!previous.isBlocked && evaluated.isBlocked) {
                _restrictionEvents.tryEmit(SubscriptionRestrictionEvent.AccountBlockedByAdmin)
            } else if (!previous.isPremium && evaluated.isPremium) {
                _restrictionEvents.tryEmit(SubscriptionRestrictionEvent.SubscriptionUnlocked)
            } else if (previous.isSubscriptionActive && !evaluated.isSubscriptionActive) {
                _restrictionEvents.tryEmit(
                    SubscriptionRestrictionEvent.TrialExpiredRedirectToPayment(evaluated.statusBannerText)
                )
            }
        }

        return evaluated
    }

    /**
     * Gatekeeper called by `CustomInCallService`, `CallManager`, `InCallActivity`, and
     * `SettingsFragment` before starting or enabling Call Recording.
     * If the 7-day trial is expired and `isPremium` is false (or the user is blocked),
     * immediately stops any ongoing recording, emits `RecordingRestricted`, and returns `false`.
     */
    fun canStartCallRecording(
        context: Context? = appContext,
        nowMs: Long = -1L
    ): Boolean {
        val ctx = context?.applicationContext ?: appContext
        val prefs = ctx?.let { DialerPreferences(it) }
        val resolvedNowMs = if (nowMs > 0L) {
            nowMs
        } else {
            RealtimeDatabaseSync.currentServerNowMs(prefs?.serverClockOffsetMs ?: 0L)
        }
        val current = if (prefs != null) {
            recalculateAndEnforce(prefs, resolvedNowMs)
        } else {
            _state.value
        }
        if (!current.isRecordingAllowed) {
            CallManager.stopRecordingIfNeeded()
            _restrictionEvents.tryEmit(
                SubscriptionRestrictionEvent.RecordingRestricted(current.statusBannerText)
            )
            return false
        }
        return true
    }

    /**
     * Returns true only when the account is blocked by Admin.
     * Non-premium users retain full access to normal calling, contacts, and simple Light theme.
     */
    fun shouldRestrictUi(
        context: Context? = appContext,
        nowMs: Long = -1L
    ): Boolean {
        val ctx = context?.applicationContext ?: appContext
        val prefs = ctx?.let { DialerPreferences(it) }
        val resolvedNowMs = if (nowMs > 0L) {
            nowMs
        } else {
            RealtimeDatabaseSync.currentServerNowMs(prefs?.serverClockOffsetMs ?: 0L)
        }
        val current = if (prefs != null) {
            recalculateAndEnforce(prefs, resolvedNowMs)
        } else {
            _state.value
        }
        return current.shouldRestrictUiToAccountTab
    }

    fun stopMonitoring() {
        rtdbNodeListenersCleanup?.invoke()
        rtdbNodeListenersCleanup = null
        tickerJob?.cancel()
        tickerJob = null
        activeUid = null
        liveSubscriptionStatus = UserEntitlement.STATUS_TRIAL
        liveIsPremium = false
        liveIsBlocked = false
        liveExpiresAtMs = 0L
        liveBonusTrialDays = 0
        hasLiveNodeState = false
        _state.value = SubscriptionEnforcementState()
    }
}
