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

    private var latestConfig: AppConfig = AppConfig(id = AppConfig.DOC_GLOBAL_SETTINGS)
    private var latestUser: UserEntitlement? = null
    private var latestDevice: DeviceTrial? = null
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
        val auth = runCatching { Firebase.auth }.getOrNull() ?: return
        isInitialized = true
        auth.addAuthStateListener { firebaseAuth ->
            val uid = firebaseAuth.currentUser?.uid
            if (uid != null) {
                startForUser(uid)
            } else {
                stop()
            }
        }
        auth.currentUser?.uid?.let { startForUser(it) }
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
        prefs: DialerPreferences
    ) {
        val previous = latestUser
        latestUser = user

        if (user != null) {
            if (snapshot != null) {
                SettingsManager.applyCloudUserSettings(snapshot, user, prefs)
            } else {
                SettingsManager.applyRtdbUserEntitlement(user, prefs)
            }
            com.example.billing.SubscriptionEnforcementService.onUserEntitlementSynced(user, appContext)
            if (hasReceivedInitialUser && previous != null) {
                if (!previous.isBlocked && user.isBlocked) {
                    _events.tryEmit(RealtimeSyncEvent.AccountBlocked)
                } else if (previous.isBlocked && !user.isBlocked) {
                    _events.tryEmit(RealtimeSyncEvent.AccountUnblocked)
                }
                if ((!previous.isPremiumUser && user.isPremiumUser && !user.isBlocked) ||
                    (previous.subscriptionStatus != UserEntitlement.STATUS_ACTIVE &&
                        user.subscriptionStatus == UserEntitlement.STATUS_ACTIVE &&
                        !user.isBlocked) ||
                    (user.isPremiumUser && !user.isBlocked &&
                        previous.subscriptionExpiresAt != user.subscriptionExpiresAt)
                ) {
                    _events.tryEmit(RealtimeSyncEvent.PaymentApproved)
                } else if (previous.subscriptionStatus == UserEntitlement.STATUS_PENDING_APPROVAL &&
                    (user.subscriptionStatus == UserEntitlement.STATUS_REJECTED ||
                        user.subscriptionStatus == UserEntitlement.STATUS_EXPIRED)
                ) {
                    _events.tryEmit(RealtimeSyncEvent.PaymentDisapproved("Disapproved by Admin"))
                }
            }
            hasReceivedInitialUser = true
        }

        recomputeEntitlementFromSnapshots(prefs)
        bumpState(userEntitlement = user)
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
        rtdbConfigCleanup = RealtimeDatabaseSync.attachAppConfigListener(
            context = ctx,
            currentFallback = { latestConfig },
            onUpdate = { rtdbConfig ->
                applyIncomingAppConfig(rtdbConfig, prefs)
            }
        )
        rtdbUserCleanup = RealtimeDatabaseSync.attachUserListener(
            context = ctx,
            uid = uid,
            currentFallback = { latestUser },
            onUpdate = { rtdbUser ->
                handleIncomingUserUpdate(rtdbUser, snapshot = null, prefs = prefs)
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
                val config = if (snapshot != null && snapshot.exists()) {
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
                val user = if (snapshot != null && snapshot.exists()) {
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
                val deviceTrial = if (snapshot != null && snapshot.exists()) {
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
                val requests = snapshot?.documents?.mapNotNull { doc ->
                    doc.toObject(
                        PaymentRequest::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }?.sortedByDescending { it.createdAt?.toDate()?.time ?: 0L }.orEmpty()

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

        // 5. Live /app_config/navigation_tabs dynamic bottom navigation push stream
        navTabsListener = db.collection(AdminRepository.COLLECTION_APP_CONFIG)
            .document(NavigationTabsConfig.DOC_NAVIGATION_TABS)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "Navigation tabs listener error: ${error.code}")
                    return@addSnapshotListener
                }
                val parsed = if (snapshot != null && snapshot.exists()) {
                    snapshot.toObject(
                        NavigationTabsConfig::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                } else {
                    null
                }
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
                val parsed = if (snapshot != null && snapshot.exists()) {
                    snapshot.toObject(
                        SettingsItemsConfig::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                } else {
                    null
                }
                val items = if (parsed != null && parsed.items.isNotEmpty()) {
                    parsed.items.sortedBy { it.order }
                } else {
                    DynamicSettingItem.defaultItems()
                }
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

        // 7. Live /app_config/live_broadcast instant popup & custom banner ad stream
        liveBroadcastListener = db.collection(AdminRepository.COLLECTION_APP_CONFIG)
            .document(LiveBroadcast.DOC_LIVE_BROADCAST)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "Live broadcast listener error: ${error.code}")
                    return@addSnapshotListener
                }
                val broadcast = if (snapshot != null && snapshot.exists()) {
                    snapshot.toObject(
                        LiveBroadcast::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ) ?: LiveBroadcast()
                } else {
                    LiveBroadcast()
                }
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
        val rawPremium = (user?.isPremiumUser == true) ||
            (user?.subscriptionStatus == UserEntitlement.STATUS_ACTIVE) ||
            (enforceSingleDevice && device?.isPremiumDevice == true)

        val userExpiryMs = user?.subscriptionExpiresAt?.toDate()?.time
            ?: if (user?.isPremiumUser == true) {
                user.updatedAt?.toDate()?.time?.let { it + DialerPreferences.ONE_MONTH_MILLIS }
            } else {
                null
            }
        val deviceExpiryMs = if (enforceSingleDevice) {
            device?.subscriptionExpiresAt?.toDate()?.time
                ?: if (device?.isPremiumDevice == true) {
                    device.updatedAt?.toDate()?.time?.let { it + DialerPreferences.ONE_MONTH_MILLIS }
                } else {
                    null
                }
        } else {
            null
        }
        val effectiveExpiresAtMs = listOfNotNull(userExpiryMs, deviceExpiryMs).maxOrNull() ?: 0L

        val nowMs = System.currentTimeMillis()
        val serverNow = device?.lastVerifiedAt?.toDate()?.time
            ?: user?.updatedAt?.toDate()?.time
            ?: nowMs

        val effectivePremium = !blocked && rawPremium &&
            (effectiveExpiresAtMs <= 0L || serverNow < effectiveExpiresAtMs)
        val hideSubUi = latestConfig.hideSubscriptionUI || (user?.hideSubscriptionUI == true)

        prefs.adminBlockedUser = blocked
        prefs.adminBonusTrialDays = bonusDays.toInt()
        prefs.subscriptionExpiresAtMs = effectiveExpiresAtMs
        prefs.isPremiumUser = effectivePremium
        prefs.configHideSubscriptionUi = latestConfig.hideSubscriptionUI
        prefs.hideSubscriptionUi = hideSubUi

        if (!user?.displayName.isNullOrBlank()) {
            prefs.userName = user!!.displayName
        }
        if (!user?.email.isNullOrBlank()) {
            prefs.userEmail = user!!.email
        }
        if (!user?.phoneNumber.isNullOrBlank()) {
            prefs.userPhoneNumber = user!!.phoneNumber
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
            prefs.serverClockOffsetMs = serverNow - nowMs
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
        liveBroadcast: LiveBroadcast? = null
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
            hideSubscriptionUi = resolvedHideSub,
            lastSyncedAtMs = System.currentTimeMillis(),
            version = current.version + 1L
        )
    }

    fun refreshFromCloudNow() {
        val ctx = appContext ?: return
        val uid = activeUid ?: return
        val deviceHash = DeviceIdentityProvider.getDeviceHash(ctx)
        scope.launch(Dispatchers.IO) {
            runCatching {
                val res = DeviceTrialRepository(ctx).syncDeviceAndUserTrial(deviceHash)
                if (res.isSuccess && activeUid == uid) {
                    bumpState()
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
        activeUid = null
        latestConfig = AppConfig(id = AppConfig.DOC_GLOBAL_SETTINGS)
        latestUser = null
        latestDevice = null
        hasReceivedInitialConfig = false
        hasReceivedInitialUser = false
        hasReceivedInitialPayments = false
        knownPaymentStatuses.clear()
        _syncState.value = RealtimeSyncState()
    }
}
