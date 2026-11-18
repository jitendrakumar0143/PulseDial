package com.example.data.firebase

import android.content.Context
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import com.example.R
import com.example.data.DialerPreferences
import com.example.telecom.CallManager
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class RealtimeSyncState(
    val uid: String? = null,
    val appConfig: AppConfig = AppConfig(id = AppConfig.DOC_GLOBAL_SETTINGS),
    val userEntitlement: UserEntitlement? = null,
    val deviceTrial: DeviceTrial? = null,
    val myPaymentRequests: List<PaymentRequest> = emptyList(),
    val navigationTabs: List<NavigationTabItem> = NavigationTabItem.defaultTabs(),
    val settingsItems: List<DynamicSettingItem> = DynamicSettingItem.defaultItems(),
    val liveBroadcast: LiveBroadcast = LiveBroadcast(),
    val customPopupAd: CustomPopupAd = CustomPopupAd(),
    val hideSubscriptionUi: Boolean = false,
    val lastSyncedAtMs: Long = 0L,
    val version: Long = 0L
)

sealed interface RealtimeSyncEvent {
    data object PaymentApproved : RealtimeSyncEvent
    data class PaymentDisapproved(val reason: String) : RealtimeSyncEvent
    data object AccountBlocked : RealtimeSyncEvent
    data object AccountUnblocked : RealtimeSyncEvent
    data object RecordingDisabledByAdmin : RealtimeSyncEvent
    data object GlobalSettingsUpdated : RealtimeSyncEvent
    data class LiveBroadcastTriggered(val broadcast: LiveBroadcast) : RealtimeSyncEvent
}

/**
 * Application-scoped real-time Firestore push synchronization engine.
 * Maintains one set of live snapshot listeners per active signed-in session so any Admin
 * change (AppConfig settings, navigation tabs, settings items, live popup/ads, user
 * block/unblock, hideSubscriptionUI, Pro grant/revoke, trial extension, or payment approval)
 * propagates across all connected devices within ~1 second.
 */
object RealtimeSyncManager {

    private const val TAG = "RealtimeSyncManager"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _syncState = MutableStateFlow(RealtimeSyncState())
    val syncState: StateFlow<RealtimeSyncState> = _syncState.asStateFlow()

    private val _events = MutableSharedFlow<RealtimeSyncEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<RealtimeSyncEvent> = _events.asSharedFlow()

    private var appContext: Context? = null
    private var isInitialized = false
    private var activeUid: String? = null

    private var configListener: ListenerRegistration? = null
    private var userListener: ListenerRegistration? = null
    private var deviceListener: ListenerRegistration? = null
    private var paymentsListener: ListenerRegistration? = null
    private var navTabsListener: ListenerRegistration? = null
    private var settingsItemsListener: ListenerRegistration? = null
    private var liveBroadcastListener: ListenerRegistration? = null
    private var rtdbConfigCleanup: (() -> Unit)? = null
    private var rtdbUserCleanup: (() -> Unit)? = null
    private var rtdbPaymentsCleanup: (() -> Unit)? = null
    private var rtdbNavTabsCleanup: (() -> Unit)? = null
    private var rtdbSettingsItemsCleanup: (() -> Unit)? = null
    private var rtdbLiveBroadcastCleanup: (() -> Unit)? = null
    private var rtdbCustomPopupAdCleanup: (() -> Unit)? = null
    private var rtdbServerTimeOffsetCleanup: (() -> Unit)? = null

    private var latestConfig: AppConfig = AppConfig(id = AppConfig.DOC_GLOBAL_SETTINGS)
    private var latestUser: UserEntitlement? = null
    private var latestRtdbUser: UserEntitlement? = null
    private var latestFirestoreUser: UserEntitlement? = null
    private var latestDevice: DeviceTrial? = null
    private var rtdbServerTimeOffsetMs: Long? = null
    private var hasReceivedInitialConfig = false
    private var hasReceivedInitialUser = false
    private var hasReceivedInitialPayments = false
    private val knownPaymentStatuses = mutableMapOf<String, String>()

    fun init(context: Context) {
        appContext = context.applicationContext
        if (isInitialized) return
        val firebaseAvailable = runCatching { FirebaseApp.getApps(context.applicationContext).isNotEmpty() }
            .getOrDefault(false)
        if (!firebaseAvailable) return

        RealtimeDatabaseSync.init(context.applicationContext)
        val auth = runCatching { Firebase.auth }.getOrNull()
        isInitialized = true
        auth?.addAuthStateListener { firebaseAuth ->
            val ctx = appContext ?: context.applicationContext
            val uid = firebaseAuth.currentUser?.uid
                ?: GoogleAuthHelper.resolveActiveUid(ctx)
            if (uid != null) {
                if (activeUid == uid && firebaseAuth.currentUser != null && userListener == null) {
                    attachFirestoreListenersIfAuthenticated(
                        uid = uid,
                        ctx = ctx,
                        prefs = DialerPreferences(ctx),
                        deviceHash = DeviceIdentityProvider.getDeviceHash(ctx)
                    )
                } else {
                    startForUser(uid)
                }
            } else {
                stop()
            }
        }
        GoogleAuthHelper.resolveActiveUid(context.applicationContext)?.let { startForUser(it) }
    }

    private fun applyIncomingAppConfig(config: AppConfig, prefs: DialerPreferences) {
        latestConfig = config
        val prevAllowRecording = prefs.allowCallRecordingConfig
        prefs.applyAppConfig(config)

        val desiredNightMode = when (config.forceThemeMode) {
            AppConfig.THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
            AppConfig.THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            else -> null
        }
        if (desiredNightMode != null &&
            android.os.Looper.myLooper() == android.os.Looper.getMainLooper() &&
            AppCompatDelegate.getDefaultNightMode() != desiredNightMode
        ) {
            AppCompatDelegate.setDefaultNightMode(desiredNightMode)
        }

        if (!config.allowCallRecording) {
            CallManager.stopRecordingIfNeeded()
            if (prevAllowRecording) {
                _events.tryEmit(RealtimeSyncEvent.RecordingDisabledByAdmin)
            }
        }

        if (hasReceivedInitialConfig) {
            _events.tryEmit(RealtimeSyncEvent.GlobalSettingsUpdated)
        }
        hasReceivedInitialConfig = true

        recomputeEntitlementFromSnapshots(prefs)
        bumpState(appConfig = config)
    }

    private fun applyIncomingPaymentRequests(requests: List<PaymentRequest>) {
        if (hasReceivedInitialPayments) {
            requests.forEach { req ->
                val prevStatus = knownPaymentStatuses[req.id]
                if (prevStatus != null && prevStatus != req.status) {
                    when (req.status) {
                        PaymentRequest.STATUS_APPROVED -> _events.tryEmit(RealtimeSyncEvent.PaymentApproved)
                        PaymentRequest.STATUS_REJECTED -> _events.tryEmit(
                            RealtimeSyncEvent.PaymentDisapproved(
                                req.reviewNote.ifBlank { "Disapproved by Admin" }
                            )
                        )
                    }
                }
            }
        }
        knownPaymentStatuses.clear()
        requests.forEach { knownPaymentStatuses[it.id] = it.status }
        hasReceivedInitialPayments = true

        bumpState(myPaymentRequests = requests)
    }

    private fun applyIncomingSettingsItems(items: List<DynamicSettingItem>, prefs: DialerPreferences) {
        items.filter { it.lockedByAdmin }.forEach { lockedItem ->
            when (lockedItem.key) {
                DynamicSettingItem.KEY_AUTO_RECORDING -> {
                    prefs.autoCallRecording = prefs.allowCallRecordingConfig && lockedItem.defaultValue
                }
                DynamicSettingItem.KEY_SOUNDS_VIBRATION -> {
                    prefs.dialpadTonesEnabled = lockedItem.defaultValue
                }
                else -> if (lockedItem.type == DynamicSettingItem.TYPE_TOGGLE) {
                    prefs.setDynamicToggle(lockedItem.key, lockedItem.defaultValue)
                }
            }
        }
        bumpState(settingsItems = items)
    }

    private fun handleIncomingUserUpdate(
        user: UserEntitlement?,
        snapshot: DocumentSnapshot?,
        prefs: DialerPreferences,
        hasRtdbBlockedNumbersField: Boolean = false,
        hasRtdbThemeModeField: Boolean = false
    ) {
        if (snapshot != null && snapshot.metadata.isFromCache && latestUser != null) {
            return
        }
        val previous = latestUser
        if (snapshot != null) {
            latestFirestoreUser = user
            if (user != null) {
                SettingsManager.applyCloudUserSettings(snapshot, user, prefs)
            }
        } else {
            latestRtdbUser = user
            if (user != null) {
                SettingsManager.applyRtdbUserEntitlement(
                    user = user,
                    prefs = prefs,
                    hasBlockedNumbersField = hasRtdbBlockedNumbersField,
                    hasThemeModeField = hasRtdbThemeModeField
                )
            }
        }

        val mergedUser = DeviceTrialRepository.mergeEntitlementSources(
            firestoreUser = latestFirestoreUser,
            rtdbUser = latestRtdbUser,
            nowMs = System.currentTimeMillis()
        ) ?: user
        latestUser = mergedUser

        if (mergedUser != null) {
            SettingsManager.applyRtdbUserEntitlement(
                user = mergedUser,
                prefs = prefs,
                hasBlockedNumbersField = hasRtdbBlockedNumbersField || snapshot != null,
                hasThemeModeField = hasRtdbThemeModeField || (snapshot?.contains("themeMode") == true)
            )
            com.example.billing.SubscriptionEnforcementService.onUserEntitlementSynced(mergedUser, appContext)
            if (hasReceivedInitialUser && previous != null) {
                if (!previous.isBlocked && mergedUser.isBlocked) {
                    _events.tryEmit(RealtimeSyncEvent.AccountBlocked)
                } else if (previous.isBlocked && !mergedUser.isBlocked) {
                    _events.tryEmit(RealtimeSyncEvent.AccountUnblocked)
                }
                if ((!previous.isPremiumUser && mergedUser.isPremiumUser && !mergedUser.isBlocked) ||
                    (previous.subscriptionStatus != UserEntitlement.STATUS_ACTIVE &&
                        mergedUser.subscriptionStatus == UserEntitlement.STATUS_ACTIVE &&
                        !mergedUser.isBlocked) ||
                    (mergedUser.isPremiumUser && !mergedUser.isBlocked &&
                        previous.subscriptionExpiresAt != mergedUser.subscriptionExpiresAt)
                ) {
                    _events.tryEmit(RealtimeSyncEvent.PaymentApproved)
                } else if (previous.subscriptionStatus == UserEntitlement.STATUS_PENDING_APPROVAL &&
                    (mergedUser.subscriptionStatus == UserEntitlement.STATUS_REJECTED ||
                        mergedUser.subscriptionStatus == UserEntitlement.STATUS_EXPIRED)
                ) {
                    _events.tryEmit(RealtimeSyncEvent.PaymentDisapproved("Disapproved by Admin"))
                }
            }
            hasReceivedInitialUser = true
        }

        recomputeEntitlementFromSnapshots(prefs)
        bumpState(userEntitlement = mergedUser)
    }

    fun startForUser(uid: String) {
        val ctx = appContext ?: return
        if (activeUid == uid && (configListener != null || rtdbConfigCleanup != null)) return
        stop()
        activeUid = uid
        com.example.billing.SubscriptionEnforcementService.startMonitoringUser(uid, ctx)

        val prefs = DialerPreferences(ctx)
        val deviceHash = DeviceIdentityProvider.getDeviceHash(ctx)

        _syncState.value = RealtimeSyncState(uid = uid)

        // Ensure device_trials and users documents exist / stay updated with server timestamps
        scope.launch(Dispatchers.IO) {
            runCatching {
                DeviceTrialRepository(ctx).syncDeviceAndUserTrial(deviceHash)
            }
        }

        // 0. Live Firebase Realtime Database ValueEventListeners (`onValue`)
        rtdbServerTimeOffsetCleanup = RealtimeDatabaseSync.attachServerTimeOffsetListener(ctx) { offsetMs ->
            rtdbServerTimeOffsetMs = offsetMs
            prefs.serverClockOffsetMs = offsetMs
        }
        rtdbConfigCleanup = RealtimeDatabaseSync.attachAppConfigListener(
            context = ctx,
            currentFallback = { latestConfig },
            onUpdate = { rtdbConfig ->
                applyIncomingAppConfig(rtdbConfig, prefs)
            }
        )
        rtdbUserCleanup = RealtimeDatabaseSync.attachUserListenerWithRawMap(
            context = ctx,
            uid = uid,
            currentFallback = { latestUser },
            onUpdate = { rtdbUser, rawMap ->
                handleIncomingUserUpdate(
                    user = rtdbUser,
                    snapshot = null,
                    prefs = prefs,
                    hasRtdbBlockedNumbersField = rawMap.containsKey("blockedNumbers"),
                    hasRtdbThemeModeField = rawMap.containsKey("themeMode")
                )
            }
        )
        rtdbPaymentsCleanup = RealtimeDatabaseSync.attachUserPaymentRequestsListener(
            context = ctx,
            uid = uid,
            onUpdate = { rtdbRequests ->
                if (rtdbRequests.isNotEmpty() || _syncState.value.myPaymentRequests.isEmpty()) {
                    applyIncomingPaymentRequests(rtdbRequests)
                }
            }
        )
        rtdbNavTabsCleanup = RealtimeDatabaseSync.attachNavigationTabsListener(
            context = ctx,
            onUpdate = { tabs ->
                bumpState(navigationTabs = tabs)
            }
        )
        rtdbSettingsItemsCleanup = RealtimeDatabaseSync.attachDynamicSettingsListener(
            context = ctx,
            onUpdate = { items ->
                applyIncomingSettingsItems(items, prefs)
            }
        )
        rtdbLiveBroadcastCleanup = RealtimeDatabaseSync.attachLiveBroadcastListener(
            context = ctx,
            onUpdate = { broadcast ->
                if (broadcast.showPopup) {
                    _events.tryEmit(RealtimeSyncEvent.LiveBroadcastTriggered(broadcast))
                }
                bumpState(liveBroadcast = broadcast)
            }
        )
        rtdbCustomPopupAdCleanup = RealtimeDatabaseSync.attachCustomPopupAdListener(
            context = ctx,
            onUpdate = { ad ->
                bumpState(customPopupAd = ad)
            }
        )

        attachFirestoreListenersIfAuthenticated(uid, ctx, prefs, deviceHash)
    }

    private fun attachFirestoreListenersIfAuthenticated(
        uid: String,
        ctx: Context,
        prefs: DialerPreferences,
        deviceHash: String
    ) {
        if (configListener != null) return
        val firebaseUid = runCatching { Firebase.auth.currentUser?.uid }.getOrNull()
        if (firebaseUid.isNullOrBlank()) return

        val dbId = ctx.getString(R.string.firestore_database_id)
        val db = runCatching { FirebaseFirestore.getInstance(dbId) }.getOrNull() ?: return

        // 1. Live global /app_config/global_settings push stream (shared single-doc fan-out)
        configListener = db.collection(AdminRepository.COLLECTION_APP_CONFIG)
            .document(AppConfig.DOC_GLOBAL_SETTINGS)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "Global config listener error: ${error.code}")
                    return@addSnapshotListener
                }
                if (snapshot == null) return@addSnapshotListener
                if (snapshot.metadata.isFromCache && hasReceivedInitialConfig) {
                    return@addSnapshotListener
                }
                if (!snapshot.exists() && hasReceivedInitialConfig) {
                    return@addSnapshotListener
                }
                val config = if (snapshot.exists()) {
                    snapshot.toObject(
                        AppConfig::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ) ?: AppConfig(id = AppConfig.DOC_GLOBAL_SETTINGS)
                } else {
                    AppConfig(id = AppConfig.DOC_GLOBAL_SETTINGS)
                }
                applyIncomingAppConfig(config, prefs)
            }

        // 2. Live /users/{uid} entitlement, real-time preferences & moderation push stream
        userListener = db.collection(DeviceTrialRepository.COLLECTION_USERS)
            .document(uid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "User entitlement listener error: ${error.code}")
                    return@addSnapshotListener
                }
                if (snapshot == null) return@addSnapshotListener
                if (!snapshot.exists() && latestUser != null) {
                    return@addSnapshotListener
                }
                val user = if (snapshot.exists()) {
                    snapshot.toObject(
                        UserEntitlement::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                } else {
                    null
                }
                handleIncomingUserUpdate(user, snapshot, prefs)
            }

        // 3. Live /device_trials/{deviceHash} device-lock push stream
        deviceListener = db.collection(DeviceTrialRepository.COLLECTION_DEVICE_TRIALS)
            .document(deviceHash)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "Device trial listener error: ${error.code}")
                    return@addSnapshotListener
                }
                if (snapshot == null) return@addSnapshotListener
                if (snapshot.metadata.isFromCache && latestDevice != null) {
                    return@addSnapshotListener
                }
                val deviceTrial = if (snapshot.exists()) {
                    snapshot.toObject(
                        DeviceTrial::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                } else {
                    null
                }
                latestDevice = deviceTrial
                recomputeEntitlementFromSnapshots(prefs)
                bumpState(deviceTrial = deviceTrial)
            }

        // 4. Live /payment_requests stream for the signed-in user
        paymentsListener = db.collection(PaymentRepository.COLLECTION_PAYMENT_REQUESTS)
            .whereEqualTo("userId", uid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "Payment requests listener error: ${error.code}")
                    return@addSnapshotListener
                }
                if (snapshot == null) return@addSnapshotListener
                if (snapshot.metadata.isFromCache &&
                    (hasReceivedInitialPayments || _syncState.value.myPaymentRequests.isNotEmpty())
                ) {
                    return@addSnapshotListener
                }
                val requests = snapshot.documents.mapNotNull { doc ->
                    doc.toObject(
                        PaymentRequest::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }.sortedByDescending { it.createdAt?.toDate()?.time ?: 0L }
                if (requests.isEmpty() && _syncState.value.myPaymentRequests.isNotEmpty()) {
                    return@addSnapshotListener
                }
                applyIncomingPaymentRequests(requests)
            }

        // 5. Live /app_config/navigation_tabs dynamic bottom navigation push stream
        navTabsListener = db.collection(AdminRepository.COLLECTION_APP_CONFIG)
            .document(NavigationTabsConfig.DOC_NAVIGATION_TABS)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "Navigation tabs listener error: ${error.code}")
                    return@addSnapshotListener
                }
                if (snapshot == null || snapshot.metadata.isFromCache || !snapshot.exists()) {
                    return@addSnapshotListener
                }
                val parsed = snapshot.toObject(
                    NavigationTabsConfig::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
                val tabs = if (parsed != null && parsed.tabs.isNotEmpty()) {
                    parsed.tabs.sortedBy { it.order }
                } else {
                    NavigationTabItem.defaultTabs()
                }
                bumpState(navigationTabs = tabs)
            }

        // 6. Live /app_config/settings_items dynamic settings push stream
        settingsItemsListener = db.collection(AdminRepository.COLLECTION_APP_CONFIG)
            .document(SettingsItemsConfig.DOC_SETTINGS_ITEMS)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "Settings items listener error: ${error.code}")
                    return@addSnapshotListener
                }
                if (snapshot == null || snapshot.metadata.isFromCache || !snapshot.exists()) {
                    return@addSnapshotListener
                }
                val parsed = snapshot.toObject(
                    SettingsItemsConfig::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
                val items = if (parsed != null && parsed.items.isNotEmpty()) {
                    parsed.items.sortedBy { it.order }
                } else {
                    DynamicSettingItem.defaultItems()
                }
                applyIncomingSettingsItems(items, prefs)
            }

        // 7. Live /app_config/live_broadcast instant popup & custom banner ad stream
        liveBroadcastListener = db.collection(AdminRepository.COLLECTION_APP_CONFIG)
            .document(LiveBroadcast.DOC_LIVE_BROADCAST)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "Live broadcast listener error: ${error.code}")
                    return@addSnapshotListener
                }
                if (snapshot == null || snapshot.metadata.isFromCache || !snapshot.exists()) {
                    return@addSnapshotListener
                }
                val broadcast = snapshot.toObject(
                    LiveBroadcast::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                ) ?: LiveBroadcast()
                if (broadcast.showPopup) {
                    _events.tryEmit(RealtimeSyncEvent.LiveBroadcastTriggered(broadcast))
                }
                bumpState(liveBroadcast = broadcast)
            }
    }

    private fun recomputeEntitlementFromSnapshots(prefs: DialerPreferences) {
        val user = latestUser
        val device = latestDevice
        val enforceSingleDevice = prefs.enforceSingleDeviceTrialConfig
        val blocked = user?.isBlocked == true || user?.subscriptionStatus == UserEntitlement.STATUS_BLOCKED
        val bonusDays = user?.bonusTrialDays?.coerceAtLeast(0L) ?: 0L
        val userStatusForcesInactive = user != null && (
            user.subscriptionStatus == UserEntitlement.STATUS_EXPIRED ||
                user.subscriptionStatus == UserEntitlement.STATUS_REJECTED ||
                user.subscriptionStatus == UserEntitlement.STATUS_BLOCKED
            )
        val rawPremium = if (user != null) {
            !userStatusForcesInactive &&
                (user.isPremiumUser || user.subscriptionStatus == UserEntitlement.STATUS_ACTIVE)
        } else {
            enforceSingleDevice && device?.isPremiumDevice == true
        }

        val userExpiryMs = if (user != null && !userStatusForcesInactive) {
            user.subscriptionExpiresAt?.toDate()?.time
                ?: if (user.isPremiumUser) {
                    user.updatedAt?.toDate()?.time?.let { it + DialerPreferences.ONE_MONTH_MILLIS }
                } else {
                    null
                }
        } else {
            null
        }
        val deviceExpiryMs = if (user == null && enforceSingleDevice) {
            device?.subscriptionExpiresAt?.toDate()?.time
                ?: if (device?.isPremiumDevice == true) {
                    device.updatedAt?.toDate()?.time?.let { it + DialerPreferences.ONE_MONTH_MILLIS }
                } else {
                    null
                }
        } else {
            null
        }
        val effectiveExpiresAtMs = (userExpiryMs ?: deviceExpiryMs ?: 0L).coerceAtLeast(0L)

        val nowMs = System.currentTimeMillis()
        val liveServerOffsetMs = rtdbServerTimeOffsetMs
        val serverNow = liveServerOffsetMs?.let { nowMs + it } ?: nowMs

        val effectivePremium = !blocked && rawPremium &&
            (effectiveExpiresAtMs <= 0L || serverNow < effectiveExpiresAtMs)
        val hideSubUi = latestConfig.hideSubscriptionUI || (user?.hideSubscriptionUI == true)

        prefs.configHideSubscriptionUi = latestConfig.hideSubscriptionUI
        prefs.applyEnforcementBatch(
            isPremium = effectivePremium,
            isBlocked = blocked,
            subscriptionExpiresAtMs = effectiveExpiresAtMs,
            bonusTrialDays = bonusDays.toInt(),
            hideSubscriptionUi = hideSubUi,
            delegatedAdmin = user?.isAdmin,
            adminGrantedBy = user?.adminGrantedBy
        )

        if (!user?.displayName.isNullOrBlank()) {
            prefs.userName = user!!.displayName
        }
        if (!user?.email.isNullOrBlank()) {
            prefs.userEmail = user!!.email
        }
        if (!user?.phoneNumber.isNullOrBlank() &&
            user!!.phoneNumber != DialerPreferences.DEFAULT_USER_NUMBER
        ) {
            prefs.userPhoneNumber = user.phoneNumber
        }

        if (user != null || device != null) {
            val deviceStart = device?.trialStartedAt?.toDate()?.time
            val userStart = user?.trialStartedAt?.toDate()?.time
            val earliest = listOfNotNull(
                if (enforceSingleDevice) deviceStart else null,
                userStart
            ).minOrNull()
            if (earliest != null) {
                prefs.firstLaunchTime = earliest
            }
            if (device?.lastVerifiedAt != null || liveServerOffsetMs != null) {
                prefs.serverClockOffsetMs = serverNow - nowMs
            }
            if (serverNow > prefs.lastSeenTimeMs) {
                prefs.lastSeenTimeMs = serverNow
            }
            val claimedOther = enforceSingleDevice &&
                device != null &&
                device.ownerUid.isNotBlank() &&
                user != null &&
                device.ownerUid != user.userId
            prefs.trialBlockedByDevice = claimedOther && prefs.isTrialExpired(nowMs)
            device?.let { prefs.accountSwitchCount = it.accountSwitchCount }
        }

        if (blocked || !prefs.hasRecordingAccess() || !prefs.allowCallRecordingConfig) {
            CallManager.stopRecordingIfNeeded()
        }
    }

    private fun bumpState(
        appConfig: AppConfig? = null,
        userEntitlement: UserEntitlement? = _syncState.value.userEntitlement,
        deviceTrial: DeviceTrial? = _syncState.value.deviceTrial,
        myPaymentRequests: List<PaymentRequest>? = null,
        navigationTabs: List<NavigationTabItem>? = null,
        settingsItems: List<DynamicSettingItem>? = null,
        liveBroadcast: LiveBroadcast? = null,
        customPopupAd: CustomPopupAd? = null
    ) {
        val current = _syncState.value
        val resolvedConfig = appConfig ?: current.appConfig
        val resolvedHideSub = resolvedConfig.hideSubscriptionUI || (userEntitlement?.hideSubscriptionUI == true)
        _syncState.value = current.copy(
            uid = activeUid,
            appConfig = resolvedConfig,
            userEntitlement = userEntitlement,
            deviceTrial = deviceTrial,
            myPaymentRequests = myPaymentRequests ?: current.myPaymentRequests,
            navigationTabs = navigationTabs ?: current.navigationTabs,
            settingsItems = settingsItems ?: current.settingsItems,
            liveBroadcast = liveBroadcast ?: current.liveBroadcast,
            customPopupAd = customPopupAd ?: current.customPopupAd,
            hideSubscriptionUi = resolvedHideSub,
            lastSyncedAtMs = System.currentTimeMillis(),
            version = current.version + 1L
        )
    }

    fun refreshFromCloudNow() {
        val ctx = appContext ?: return
        val uid = activeUid ?: return
        val prefs = DialerPreferences(ctx)
        val deviceHash = DeviceIdentityProvider.getDeviceHash(ctx)
        scope.launch(Dispatchers.IO) {
            runCatching {
                // Fetch RTDB first so active Admin grants in RTDB are loaded before Firestore sync
                val rtdbConfig = RealtimeDatabaseSync.fetchAppConfigOnce(ctx, latestConfig)
                val rtdbUserResult = RealtimeDatabaseSync.fetchUserSnapshotOnce(ctx, uid, latestRtdbUser ?: latestUser)
                scope.launch(Dispatchers.Main.immediate) {
                    if (activeUid != uid) return@launch
                    rtdbConfig?.let { applyIncomingAppConfig(it, prefs) }
                    rtdbUserResult?.let { (rtdbUser, hasBlockedField) ->
                        handleIncomingUserUpdate(
                            user = rtdbUser,
                            snapshot = null,
                            prefs = prefs,
                            hasRtdbBlockedNumbersField = hasBlockedField
                        )
                    }
                }
                val res = DeviceTrialRepository(ctx).syncDeviceAndUserTrial(deviceHash)
                scope.launch(Dispatchers.Main.immediate) {
                    if (activeUid != uid) return@launch
                    if (res.isSuccess && rtdbUserResult == null) {
                        bumpState()
                    }
                }
            }
        }
    }

    fun stop() {
        configListener?.remove()
        configListener = null
        userListener?.remove()
        userListener = null
        deviceListener?.remove()
        deviceListener = null
        paymentsListener?.remove()
        paymentsListener = null
        navTabsListener?.remove()
        navTabsListener = null
        settingsItemsListener?.remove()
        settingsItemsListener = null
        liveBroadcastListener?.remove()
        liveBroadcastListener = null
        rtdbConfigCleanup?.invoke()
        rtdbConfigCleanup = null
        rtdbUserCleanup?.invoke()
        rtdbUserCleanup = null
        rtdbPaymentsCleanup?.invoke()
        rtdbPaymentsCleanup = null
        rtdbNavTabsCleanup?.invoke()
        rtdbNavTabsCleanup = null
        rtdbSettingsItemsCleanup?.invoke()
        rtdbSettingsItemsCleanup = null
        rtdbLiveBroadcastCleanup?.invoke()
        rtdbLiveBroadcastCleanup = null
        rtdbCustomPopupAdCleanup?.invoke()
        rtdbCustomPopupAdCleanup = null
        rtdbServerTimeOffsetCleanup?.invoke()
        rtdbServerTimeOffsetCleanup = null
        activeUid = null
        latestConfig = AppConfig(id = AppConfig.DOC_GLOBAL_SETTINGS)
        latestUser = null
        latestRtdbUser = null
        latestFirestoreUser = null
        latestDevice = null
        rtdbServerTimeOffsetMs = null
        hasReceivedInitialConfig = false
        hasReceivedInitialUser = false
        hasReceivedInitialPayments = false
        knownPaymentStatuses.clear()
        _syncState.value = RealtimeSyncState()
    }
}
