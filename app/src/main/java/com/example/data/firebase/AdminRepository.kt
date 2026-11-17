package com.example.data.firebase

import android.content.Context
import com.example.R
import com.example.billing.UpiPaymentHelper
import com.example.data.DialerPreferences
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.util.Date
import kotlin.math.ceil

enum class AdminUserFilter {
    ALL,
    EXPIRING_SOON,
    TRIAL_ACTIVE,
    EXPIRED,
    PREMIUM,
    BLOCKED
}

enum class AdminPaymentFilter {
    ALL,
    PENDING,
    APPROVED,
    DISAPPROVED
}

enum class AdminUserAccountStatus {
    PREMIUM,
    EXPIRING_SOON,
    TRIAL_ACTIVE,
    EXPIRED,
    BLOCKED
}

enum class AdminSubscriberFilter {
    EXPIRING_SOON,
    ALL_ACTIVE_SUBSCRIBERS
}

class AdminRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth,
    private val fallbackUidProvider: () -> String? = { null }
) {

    constructor(context: Context) : this(
        db = FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        ),
        auth = Firebase.auth,
        fallbackUidProvider = { GoogleAuthHelper.resolveActiveUid(context.applicationContext) }
    )

    private fun requireAdminUid(): String {
        val firebaseUid = runCatching { auth.currentUser?.uid }.getOrNull()?.takeIf { it.isNotBlank() }
        if (firebaseUid != null) return firebaseUid
        val fallbackUid = fallbackUidProvider()?.takeIf { it.isNotBlank() }
        if (fallbackUid != null) return fallbackUid
        throw IllegalStateException("Admin must be signed in.")
    }

    fun observeAllUsers(): Flow<List<UserEntitlement>> = flow {
        val uid = requireAdminUid()
        emitAll(
            db.collection(DeviceTrialRepository.COLLECTION_USERS)
                .snapshots()
                .map { snapshot ->
                    snapshot.documents.mapNotNull { doc ->
                        doc.toObject(
                            UserEntitlement::class.java,
                            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                        )
                    }.sortedByDescending { it.updatedAt?.toDate()?.time ?: it.createdAt?.toDate()?.time ?: 0L }
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(
                            exceptionToLog,
                            OperationType.LIST,
                            DeviceTrialRepository.COLLECTION_USERS,
                            auth
                        )
                    }
                    throw error
                }
        )
    }

    /**
     * Real-time Firestore listener for users whose subscriptionStatus == "pending_approval".
     */
    fun observeUsersWithPendingApprovals(): Flow<List<UserEntitlement>> = flow {
        val uid = requireAdminUid()
        emitAll(
            db.collection(DeviceTrialRepository.COLLECTION_USERS)
                .whereEqualTo("subscriptionStatus", UserEntitlement.STATUS_PENDING_APPROVAL)
                .snapshots()
                .map { snapshot ->
                    snapshot.documents.mapNotNull { doc ->
                        doc.toObject(
                            UserEntitlement::class.java,
                            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                        )
                    }.sortedByDescending { it.updatedAt?.toDate()?.time ?: it.createdAt?.toDate()?.time ?: 0L }
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(
                            exceptionToLog,
                            OperationType.LIST,
                            DeviceTrialRepository.COLLECTION_USERS,
                            auth
                        )
                    }
                    throw error
                }
        )
    }

    /**
     * Real-time Firestore range query for paid subscribers whose subscriptionExpiresAt falls in
     * (now, now + days], ordered soonest-first (uses automatic single-field index on subscriptionExpiresAt).
     */
    fun observeUsersExpiringWithinDays(
        days: Int = SUB_EXPIRING_SOON_DAYS,
        nowMs: Long = System.currentTimeMillis()
    ): Flow<List<UserEntitlement>> = flow {
        val uid = requireAdminUid()
        val windowDays = days.coerceIn(1, 365).toLong()
        val nowTs = Timestamp(Date(nowMs))
        val cutoffTs = Timestamp(Date(nowMs + windowDays * DialerPreferences.ONE_DAY_MILLIS))
        emitAll(
            db.collection(DeviceTrialRepository.COLLECTION_USERS)
                .whereGreaterThan("subscriptionExpiresAt", nowTs)
                .whereLessThanOrEqualTo("subscriptionExpiresAt", cutoffTs)
                .orderBy("subscriptionExpiresAt", Query.Direction.ASCENDING)
                .snapshots()
                .map { snapshot ->
                    snapshot.documents.mapNotNull { doc ->
                        doc.toObject(
                            UserEntitlement::class.java,
                            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                        )
                    }.filter { it.isPremiumUser && !it.isBlocked }
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(
                            exceptionToLog,
                            OperationType.LIST,
                            DeviceTrialRepository.COLLECTION_USERS,
                            auth
                        )
                    }
                    throw error
                }
        )
    }

    /**
     * On-demand Firestore range query for paid subscribers expiring within [days] days.
     */
    suspend fun fetchUsersExpiringWithinDays(
        days: Int = SUB_EXPIRING_SOON_DAYS,
        nowMs: Long = System.currentTimeMillis()
    ): Result<List<UserEntitlement>> {
        try {
            requireAdminUid()
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val windowDays = days.coerceIn(1, 365).toLong()
        val nowTs = Timestamp(Date(nowMs))
        val cutoffTs = Timestamp(Date(nowMs + windowDays * DialerPreferences.ONE_DAY_MILLIS))
        return try {
            val snap = db.collection(DeviceTrialRepository.COLLECTION_USERS)
                .whereGreaterThan("subscriptionExpiresAt", nowTs)
                .whereLessThanOrEqualTo("subscriptionExpiresAt", cutoffTs)
                .orderBy("subscriptionExpiresAt", Query.Direction.ASCENDING)
                .snapshots()
                .first()
            val list = snap.documents.mapNotNull { doc ->
                doc.toObject(
                    UserEntitlement::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
            }.filter { it.isPremiumUser && !it.isBlocked }
            Result.success(list)
        } catch (e: Exception) {
            val firestoreError = generateSequence<Throwable>(e) { it.cause }
                .filterIsInstance<FirebaseFirestoreException>()
                .firstOrNull()
            if (firestoreError == null && e is CancellationException) throw e
            val actualError = firestoreError ?: e
            handleFirestoreError(
                actualError,
                OperationType.LIST,
                DeviceTrialRepository.COLLECTION_USERS,
                auth
            )
            Result.failure(actualError)
        }
    }

    /**
     * Real-time Firestore range query for all currently active paid subscribers whose
     * subscriptionExpiresAt is in the future, ordered soonest-first (single-field index).
     */
    fun observeAllActiveSubscribers(
        nowMs: Long = System.currentTimeMillis()
    ): Flow<List<UserEntitlement>> = flow {
        val uid = requireAdminUid()
        val nowTs = Timestamp(Date(nowMs))
        emitAll(
            db.collection(DeviceTrialRepository.COLLECTION_USERS)
                .whereGreaterThan("subscriptionExpiresAt", nowTs)
                .orderBy("subscriptionExpiresAt", Query.Direction.ASCENDING)
                .snapshots()
                .map { snapshot ->
                    snapshot.documents.mapNotNull { doc ->
                        doc.toObject(
                            UserEntitlement::class.java,
                            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                        )
                    }.filter { it.isPremiumUser && !it.isBlocked }
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(
                            exceptionToLog,
                            OperationType.LIST,
                            DeviceTrialRepository.COLLECTION_USERS,
                            auth
                        )
                    }
                    throw error
                }
        )
    }

    /**
     * On-demand Firestore range query for all active paid subscribers (subscriptionExpiresAt > now).
     */
    suspend fun fetchAllActiveSubscribers(
        nowMs: Long = System.currentTimeMillis()
    ): Result<List<UserEntitlement>> {
        try {
            requireAdminUid()
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val nowTs = Timestamp(Date(nowMs))
        return try {
            val snap = db.collection(DeviceTrialRepository.COLLECTION_USERS)
                .whereGreaterThan("subscriptionExpiresAt", nowTs)
                .orderBy("subscriptionExpiresAt", Query.Direction.ASCENDING)
                .snapshots()
                .first()
            val list = snap.documents.mapNotNull { doc ->
                doc.toObject(
                    UserEntitlement::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
            }.filter { it.isPremiumUser && !it.isBlocked }
            Result.success(list)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(
                e,
                OperationType.LIST,
                DeviceTrialRepository.COLLECTION_USERS,
                auth
            )
            Result.failure(e)
        }
    }

    fun observeAllDeviceTrials(): Flow<List<DeviceTrial>> = flow {
        val uid = requireAdminUid()
        emitAll(
            db.collection(DeviceTrialRepository.COLLECTION_DEVICE_TRIALS)
                .snapshots()
                .map { snapshot ->
                    snapshot.documents.mapNotNull { doc ->
                        doc.toObject(
                            DeviceTrial::class.java,
                            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                        )
                    }.sortedByDescending { it.updatedAt?.toDate()?.time ?: 0L }
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(
                            exceptionToLog,
                            OperationType.LIST,
                            DeviceTrialRepository.COLLECTION_DEVICE_TRIALS,
                            auth
                        )
                    }
                    throw error
                }
        )
    }

    fun observeGlobalAppConfig(): Flow<AppConfig> = flow {
        val uid = auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in before observing app config.")
        val path = "$COLLECTION_APP_CONFIG/${AppConfig.DOC_GLOBAL_SETTINGS}"
        emitAll(
            db.collection(COLLECTION_APP_CONFIG).document(AppConfig.DOC_GLOBAL_SETTINGS)
                .snapshots()
                .map { snapshot ->
                    if (!snapshot.exists()) {
                        AppConfig(id = AppConfig.DOC_GLOBAL_SETTINGS)
                    } else {
                        snapshot.toObject(
                            AppConfig::class.java,
                            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                        ) ?: AppConfig(id = AppConfig.DOC_GLOBAL_SETTINGS)
                    }
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(exceptionToLog, OperationType.GET, path, auth)
                    }
                    throw error
                }
        )
    }

    suspend fun getGlobalAppConfig(): Result<AppConfig> {
        if (auth.currentUser == null) {
            return Result.failure(IllegalStateException("User must be signed in."))
        }
        val path = "$COLLECTION_APP_CONFIG/${AppConfig.DOC_GLOBAL_SETTINGS}"
        return try {
            val snap = db.collection(COLLECTION_APP_CONFIG)
                .document(AppConfig.DOC_GLOBAL_SETTINGS)
                .snapshots()
                .first()
            val config = if (!snap.exists()) {
                AppConfig(id = AppConfig.DOC_GLOBAL_SETTINGS)
            } else {
                snap.toObject(
                    AppConfig::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                ) ?: AppConfig(id = AppConfig.DOC_GLOBAL_SETTINGS)
            }
            Result.success(config)
        } catch (e: Exception) {
            val firestoreError = generateSequence<Throwable>(e) { it.cause }
                .filterIsInstance<FirebaseFirestoreException>()
                .firstOrNull()
            if (firestoreError == null && e is CancellationException) throw e
            val actualError = firestoreError ?: e
            handleFirestoreError(actualError, OperationType.GET, path, auth)
            Result.failure(actualError)
        }
    }

    suspend fun saveGlobalAppConfig(config: AppConfig): Result<Unit> {
        val adminUid = try {
            requireAdminUid()
        } catch (e: Exception) {
            return Result.failure(e)
        }

        val docRef = db.collection(COLLECTION_APP_CONFIG).document(AppConfig.DOC_GLOBAL_SETTINGS)
        val cleanWhatsapp = config.whatsappNumber.filter { it.isDigit() }.ifBlank {
            UpiPaymentHelper.WHATSAPP_SUPPORT_LOCAL
        }.take(16)
        val cleanUpi = config.upiVpa.trim().ifBlank { UpiPaymentHelper.UPI_VPA }.take(100)

        val cleanTheme = when (config.forceThemeMode) {
            AppConfig.THEME_LIGHT, AppConfig.THEME_DARK -> config.forceThemeMode
            else -> AppConfig.THEME_USER
        }

        val payload = mapOf(
            "trialDays" to config.trialDays.coerceIn(1L, 365L),
            "subscriptionPriceInr" to config.subscriptionPriceInr.coerceIn(1L, 100000L),
            "upiVpa" to cleanUpi,
            "whatsappNumber" to cleanWhatsapp,
            "enforceSingleDeviceTrial" to config.enforceSingleDeviceTrial,
            "allowCallRecording" to config.allowCallRecording,
            "allowManualUtr" to config.allowManualUtr,
            "maintenanceMode" to config.maintenanceMode,
            "maintenanceMessage" to config.maintenanceMessage.trim().take(240),
            "announcementText" to config.announcementText.trim().take(240),
            "dialpadTonesDefault" to config.dialpadTonesDefault,
            "defaultAutoRecording" to config.defaultAutoRecording,
            "forceThemeMode" to cleanTheme,
            "hideSubscriptionUI" to config.hideSubscriptionUI,
            "updatedByUid" to adminUid,
            "updatedAt" to FieldValue.serverTimestamp()
        )

        return try {
            RealtimeDatabaseSync.pushAppConfig(
                config.copy(
                    upiVpa = cleanUpi,
                    whatsappNumber = cleanWhatsapp,
                    forceThemeMode = cleanTheme
                )
            )
            if (runCatching { auth.currentUser }.getOrNull() == null) {
                return Result.success(Unit)
            }
            docRef.set(payload).awaitTask()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path, auth)
            Result.failure(e)
        }
    }

    suspend fun updateUserAdminDetails(
        user: UserEntitlement,
        isPremium: Boolean,
        isBlocked: Boolean,
        bonusTrialDays: Long,
        adminNote: String,
        hideSubscriptionUI: Boolean = user.hideSubscriptionUI,
        subscriptionExpiresAt: Timestamp? = null,
        explicitSubscriptionStatus: String? = null
    ): Result<Unit> {
        try {
            requireAdminUid()
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val targetUid = user.userId.ifBlank { user.id }
        require(targetUid.isNotBlank()) { "User ID must not be blank" }

        val userRef = db.collection(DeviceTrialRepository.COLLECTION_USERS).document(targetUid)
        val deviceRef = if (user.deviceHash.length == 64) {
            db.collection(DeviceTrialRepository.COLLECTION_DEVICE_TRIALS).document(user.deviceHash)
        } else {
            null
        }

        val nowMs = System.currentTimeMillis()
        val resolvedExpiry: Timestamp? = if (isPremium && !isBlocked) {
            subscriptionExpiresAt
                ?: user.subscriptionExpiresAt?.takeIf { it.toDate().time > nowMs }
                ?: Timestamp(Date(nowMs + DialerPreferences.ONE_MONTH_MILLIS))
        } else {
            null
        }

        val resolvedStatus = explicitSubscriptionStatus ?: when {
            isBlocked -> UserEntitlement.STATUS_BLOCKED
            isPremium -> UserEntitlement.STATUS_ACTIVE
            else -> UserEntitlement.STATUS_TRIAL
        }

        return try {
            RealtimeDatabaseSync.pushUserFields(
                targetUid,
                mapOf(
                    "isPremium" to isPremium,
                    "isPremiumUser" to isPremium,
                    "subscriptionStatus" to resolvedStatus,
                    "subscriptionExpiresAtMs" to resolvedExpiry?.toDate()?.time,
                    "isBlocked" to isBlocked,
                    "bonusTrialDays" to bonusTrialDays.coerceIn(0L, 3650L),
                    "adminNote" to adminNote.trim().take(240),
                    "hideSubscriptionUI" to hideSubscriptionUI,
                    "updatedAtMs" to System.currentTimeMillis()
                )
            )
            if (runCatching { auth.currentUser }.getOrNull() == null) {
                return Result.success(Unit)
            }
            val deviceSnap = deviceRef?.snapshots()?.first()
            val batch = db.batch()
            batch.update(
                userRef,
                mapOf(
                    "isPremiumUser" to isPremium,
                    "subscriptionStatus" to resolvedStatus,
                    "subscriptionProductId" to if (isPremium) {
                        user.subscriptionProductId.ifBlank { "admin_granted_pro" }
                    } else {
                        ""
                    },
                    "purchaseToken" to if (isPremium) {
                        user.purchaseToken.ifBlank { "admin_manual" }
                    } else {
                        ""
                    },
                    "subscriptionExpiresAt" to resolvedExpiry,
                    "isBlocked" to isBlocked,
                    "bonusTrialDays" to bonusTrialDays.coerceIn(0L, 3650L),
                    "adminNote" to adminNote.trim().take(240),
                    "hideSubscriptionUI" to hideSubscriptionUI,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            if (deviceRef != null && deviceSnap?.exists() == true) {
                batch.update(
                    deviceRef,
                    mapOf(
                        "isPremiumDevice" to (isPremium && !isBlocked),
                        "subscriptionExpiresAt" to resolvedExpiry,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            }
            batch.commit().awaitTask()
            Result.success(Unit)
        } catch (e: Exception) {
            val firestoreError = generateSequence<Throwable>(e) { it.cause }
                .filterIsInstance<FirebaseFirestoreException>()
                .firstOrNull()
            if (firestoreError == null && e is CancellationException) throw e
            val actualError = firestoreError ?: e
            handleFirestoreError(actualError, OperationType.UPDATE, userRef.path, auth)
            Result.failure(actualError)
        }
    }

    /**
     * Approves a user's pending UTR directly from the Admin User Card:
     * sets `isPremium = true`, `subscriptionStatus = "active"`, and extends expiry by 30 days.
     */
    suspend fun approveUserUtrDirect(user: UserEntitlement): Result<Unit> {
        val nowMs = System.currentTimeMillis()
        val existingExpMs = resolveEffectiveExpiryMs(user)
        val baseMs = maxOf(nowMs, existingExpMs)
        val nextExpiry = Timestamp(Date(baseMs + DialerPreferences.ONE_MONTH_MILLIS))
        return updateUserAdminDetails(
            user = user,
            isPremium = true,
            isBlocked = false,
            bonusTrialDays = user.bonusTrialDays,
            adminNote = user.adminNote,
            hideSubscriptionUI = user.hideSubscriptionUI,
            subscriptionExpiresAt = nextExpiry,
            explicitSubscriptionStatus = UserEntitlement.STATUS_ACTIVE
        )
    }

    /**
     * Rejects a user's pending UTR directly from the Admin User Card:
     * sets `isPremium = false` and `subscriptionStatus = "expired"`.
     */
    suspend fun rejectUserUtrDirect(user: UserEntitlement): Result<Unit> {
        return updateUserAdminDetails(
            user = user,
            isPremium = false,
            isBlocked = user.isBlocked,
            bonusTrialDays = user.bonusTrialDays,
            adminNote = user.adminNote,
            hideSubscriptionUI = user.hideSubscriptionUI,
            subscriptionExpiresAt = null,
            explicitSubscriptionStatus = UserEntitlement.STATUS_EXPIRED
        )
    }

    suspend fun renewUserMonthlySubscription(
        user: UserEntitlement,
        extraDays: Long = DialerPreferences.MONTHLY_SUBSCRIPTION_DAYS.toLong()
    ): Result<Unit> {
        val nowMs = System.currentTimeMillis()
        val existingExpMs = resolveEffectiveExpiryMs(user)
        val baseMs = maxOf(nowMs, existingExpMs)
        val nextExpiry = Timestamp(Date(baseMs + extraDays.coerceIn(1L, 3650L) * DialerPreferences.ONE_DAY_MILLIS))
        return updateUserAdminDetails(
            user = user,
            isPremium = true,
            isBlocked = false,
            bonusTrialDays = user.bonusTrialDays,
            adminNote = user.adminNote,
            hideSubscriptionUI = user.hideSubscriptionUI,
            subscriptionExpiresAt = nextExpiry
        )
    }

    fun observeNavigationTabs(): Flow<NavigationTabsConfig> = flow {
        val uid = auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in before observing navigation tabs.")
        val path = "$COLLECTION_APP_CONFIG/${NavigationTabsConfig.DOC_NAVIGATION_TABS}"
        emitAll(
            db.collection(COLLECTION_APP_CONFIG)
                .document(NavigationTabsConfig.DOC_NAVIGATION_TABS)
                .snapshots()
                .map { snapshot ->
                    val parsed = if (snapshot.exists()) {
                        snapshot.toObject(
                            NavigationTabsConfig::class.java,
                            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                        )
                    } else {
                        null
                    }
                    if (parsed != null && parsed.tabs.isNotEmpty()) {
                        parsed.copy(tabs = parsed.tabs.sortedBy { it.order })
                    } else {
                        NavigationTabsConfig()
                    }
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(exceptionToLog, OperationType.GET, path, auth)
                    }
                    throw error
                }
        )
    }

    suspend fun saveNavigationTabs(tabs: List<NavigationTabItem>): Result<Unit> {
        val adminUid = try {
            requireAdminUid()
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val normalized = tabs
            .sortedBy { it.order }
            .take(10)
            .mapIndexed { index, tab ->
                tab.copy(
                    title = tab.title.trim().ifBlank { "Tab ${index + 1}" }.take(24),
                    order = index,
                    customTitle = tab.customTitle.trim().take(120),
                    customBody = tab.customBody.trim().take(500),
                    customActionUrl = tab.customActionUrl.trim().take(500)
                )
            }
        val docRef = db.collection(COLLECTION_APP_CONFIG)
            .document(NavigationTabsConfig.DOC_NAVIGATION_TABS)
        val payload = mapOf(
            "tabs" to normalized.map { it.toFirestoreMap() },
            "updatedByUid" to adminUid,
            "updatedAt" to FieldValue.serverTimestamp()
        )
        return try {
            RealtimeDatabaseSync.setNavigationTabs(normalized, adminUid)
            if (runCatching { auth.currentUser }.getOrNull() == null) {
                return Result.success(Unit)
            }
            docRef.set(payload).awaitTask()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path, auth)
            Result.failure(e)
        }
    }

    fun observeDynamicSettings(): Flow<SettingsItemsConfig> = flow {
        val uid = auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in before observing settings items.")
        val path = "$COLLECTION_APP_CONFIG/${SettingsItemsConfig.DOC_SETTINGS_ITEMS}"
        emitAll(
            db.collection(COLLECTION_APP_CONFIG)
                .document(SettingsItemsConfig.DOC_SETTINGS_ITEMS)
                .snapshots()
                .map { snapshot ->
                    val parsed = if (snapshot.exists()) {
                        snapshot.toObject(
                            SettingsItemsConfig::class.java,
                            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                        )
                    } else {
                        null
                    }
                    if (parsed != null && parsed.items.isNotEmpty()) {
                        parsed.copy(items = parsed.items.sortedBy { it.order })
                    } else {
                        SettingsItemsConfig()
                    }
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(exceptionToLog, OperationType.GET, path, auth)
                    }
                    throw error
                }
        )
    }

    suspend fun saveDynamicSettings(items: List<DynamicSettingItem>): Result<Unit> {
        val adminUid = try {
            requireAdminUid()
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val normalized = items
            .sortedBy { it.order }
            .take(30)
            .mapIndexed { index, item ->
                item.copy(
                    key = item.key.trim().ifBlank { "custom_$index" }.take(60),
                    title = item.title.trim().ifBlank { "Setting ${index + 1}" }.take(80),
                    description = item.description.trim().take(200),
                    order = index,
                    actionUrl = item.actionUrl.trim().take(500)
                )
            }
        val docRef = db.collection(COLLECTION_APP_CONFIG)
            .document(SettingsItemsConfig.DOC_SETTINGS_ITEMS)
        val payload = mapOf(
            "items" to normalized.map { it.toFirestoreMap() },
            "updatedByUid" to adminUid,
            "updatedAt" to FieldValue.serverTimestamp()
        )
        return try {
            RealtimeDatabaseSync.setDynamicSettings(normalized, adminUid)
            if (runCatching { auth.currentUser }.getOrNull() == null) {
                return Result.success(Unit)
            }
            docRef.set(payload).awaitTask()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path, auth)
            Result.failure(e)
        }
    }

    fun observeLiveBroadcast(): Flow<LiveBroadcast> = flow {
        val uid = auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in before observing live broadcast.")
        val path = "$COLLECTION_APP_CONFIG/${LiveBroadcast.DOC_LIVE_BROADCAST}"
        emitAll(
            db.collection(COLLECTION_APP_CONFIG)
                .document(LiveBroadcast.DOC_LIVE_BROADCAST)
                .snapshots()
                .map { snapshot ->
                    if (!snapshot.exists()) {
                        LiveBroadcast()
                    } else {
                        snapshot.toObject(
                            LiveBroadcast::class.java,
                            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                        ) ?: LiveBroadcast()
                    }
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(exceptionToLog, OperationType.GET, path, auth)
                    }
                    throw error
                }
        )
    }

    suspend fun saveLiveBroadcast(broadcast: LiveBroadcast): Result<Unit> {
        val adminUid = try {
            requireAdminUid()
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val docRef = db.collection(COLLECTION_APP_CONFIG)
            .document(LiveBroadcast.DOC_LIVE_BROADCAST)
        val payload = mapOf(
            "showPopup" to broadcast.showPopup,
            "popupId" to broadcast.popupId.trim().take(80),
            "title" to broadcast.title.trim().take(140),
            "message" to broadcast.message.trim().take(500),
            "imageUrl" to broadcast.imageUrl.trim().take(500),
            "buttonText" to broadcast.buttonText.trim().ifBlank { "Open" }.take(60),
            "actionUrl" to broadcast.actionUrl.trim().take(500),
            "isCancelable" to broadcast.isCancelable,
            "showBannerAd" to broadcast.showBannerAd,
            "bannerText" to broadcast.bannerText.trim().take(180),
            "bannerImageUrl" to broadcast.bannerImageUrl.trim().take(500),
            "bannerActionUrl" to broadcast.bannerActionUrl.trim().take(500),
            "updatedByUid" to adminUid,
            "updatedAt" to FieldValue.serverTimestamp()
        )
        return try {
            RealtimeDatabaseSync.setLiveBroadcast(broadcast, adminUid)
            if (runCatching { auth.currentUser }.getOrNull() == null) {
                return Result.success(Unit)
            }
            docRef.set(payload).awaitTask()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path, auth)
            Result.failure(e)
        }
    }

    companion object {
        const val COLLECTION_APP_CONFIG = "app_config"
        const val SUB_EXPIRING_SOON_DAYS = DialerPreferences.EXPIRING_SOON_WINDOW_DAYS
        const val TRIAL_EXPIRING_SOON_DAYS = 2

        fun resolveEffectiveExpiryMs(user: UserEntitlement): Long {
            val explicit = user.subscriptionExpiresAt?.toDate()?.time
            if (explicit != null && explicit > 0L) return explicit
            if (user.isPremiumUser) {
                val updatedMs = user.updatedAt?.toDate()?.time ?: 0L
                if (updatedMs > 0L) {
                    return updatedMs + DialerPreferences.ONE_MONTH_MILLIS
                }
            }
            return 0L
        }

        fun getSubscriptionRemainingDays(
            user: UserEntitlement,
            nowMs: Long = System.currentTimeMillis()
        ): Int {
            if (!user.isPremiumUser || user.isBlocked) return 0
            val expMs = resolveEffectiveExpiryMs(user)
            if (expMs <= 0L) return -1
            val remainingMs = expMs - nowMs
            if (remainingMs <= 0L) return 0
            return ceil(remainingMs.toDouble() / DialerPreferences.ONE_DAY_MILLIS.toDouble())
                .toInt()
                .coerceAtLeast(1)
        }

        fun resolveUserAccountStatus(
            user: UserEntitlement,
            trialDaysConfig: Int = DialerPreferences.TRIAL_DAYS,
            nowMs: Long = System.currentTimeMillis()
        ): AdminUserAccountStatus {
            if (user.isBlocked) return AdminUserAccountStatus.BLOCKED
            if (user.isPremiumUser) {
                val subDaysLeft = getSubscriptionRemainingDays(user, nowMs)
                return when {
                    subDaysLeft == 0 -> AdminUserAccountStatus.EXPIRED
                    subDaysLeft in 1..SUB_EXPIRING_SOON_DAYS -> AdminUserAccountStatus.EXPIRING_SOON
                    else -> AdminUserAccountStatus.PREMIUM
                }
            }
            val startedMs = user.trialStartedAt?.toDate()?.time ?: nowMs
            val totalDays = (trialDaysConfig.coerceIn(1, 365) + user.bonusTrialDays.coerceAtLeast(0L))
                .coerceIn(1L, 4000L)
            val expiresMs = startedMs + totalDays * DialerPreferences.ONE_DAY_MILLIS
            if (nowMs >= expiresMs) return AdminUserAccountStatus.EXPIRED
            val trialDaysLeft = getRemainingDaysForUser(user, trialDaysConfig, nowMs)
            return if (trialDaysLeft <= TRIAL_EXPIRING_SOON_DAYS) {
                AdminUserAccountStatus.EXPIRING_SOON
            } else {
                AdminUserAccountStatus.TRIAL_ACTIVE
            }
        }

        fun getRemainingDaysForUser(
            user: UserEntitlement,
            trialDaysConfig: Int = DialerPreferences.TRIAL_DAYS,
            nowMs: Long = System.currentTimeMillis()
        ): Int {
            val startedMs = user.trialStartedAt?.toDate()?.time ?: nowMs
            val totalDays = (trialDaysConfig.coerceIn(1, 365) + user.bonusTrialDays.coerceAtLeast(0L))
                .coerceIn(1L, 4000L)
            val remainingMs = (startedMs + totalDays * DialerPreferences.ONE_DAY_MILLIS) - nowMs
            if (remainingMs <= 0L) return 0
            return ceil(remainingMs.toDouble() / DialerPreferences.ONE_DAY_MILLIS.toDouble())
                .toInt()
                .coerceIn(1, totalDays.toInt())
        }

        fun isUserExpiringSoon(
            user: UserEntitlement,
            trialDaysConfig: Int = DialerPreferences.TRIAL_DAYS,
            nowMs: Long = System.currentTimeMillis()
        ): Boolean = resolveUserAccountStatus(user, trialDaysConfig, nowMs) == AdminUserAccountStatus.EXPIRING_SOON

        fun filterUsers(
            users: List<UserEntitlement>,
            filter: AdminUserFilter,
            query: String,
            trialDaysConfig: Int = DialerPreferences.TRIAL_DAYS,
            nowMs: Long = System.currentTimeMillis()
        ): List<UserEntitlement> {
            val trimmed = query.trim().lowercase()
            return users.filter { user ->
                val status = resolveUserAccountStatus(user, trialDaysConfig, nowMs)
                val matchesFilter = when (filter) {
                    AdminUserFilter.ALL -> true
                    AdminUserFilter.EXPIRING_SOON -> status == AdminUserAccountStatus.EXPIRING_SOON
                    AdminUserFilter.TRIAL_ACTIVE ->
                        !user.isBlocked && !user.isPremiumUser && status != AdminUserAccountStatus.EXPIRED
                    AdminUserFilter.EXPIRED -> status == AdminUserAccountStatus.EXPIRED
                    AdminUserFilter.PREMIUM ->
                        !user.isBlocked && user.isPremiumUser && status != AdminUserAccountStatus.EXPIRED
                    AdminUserFilter.BLOCKED -> status == AdminUserAccountStatus.BLOCKED
                }
                if (!matchesFilter) return@filter false
                if (trimmed.isEmpty()) return@filter true

                val deviceTag = if (user.deviceHash.length == 64) {
                    DeviceIdentityProvider.formatShortDeviceTag(user.deviceHash).lowercase()
                } else {
                    ""
                }
                user.displayName.lowercase().contains(trimmed) ||
                    user.email.lowercase().contains(trimmed) ||
                    user.phoneNumber.lowercase().contains(trimmed) ||
                    user.paymentRefId.lowercase().contains(trimmed) ||
                    user.userId.lowercase().contains(trimmed) ||
                    user.id.lowercase().contains(trimmed) ||
                    user.deviceHash.lowercase().contains(trimmed) ||
                    deviceTag.contains(trimmed) ||
                    user.adminNote.lowercase().contains(trimmed)
            }.sortedWith(
                compareBy<UserEntitlement> {
                    if (it.subscriptionStatus == UserEntitlement.STATUS_PENDING_APPROVAL) 0 else 1
                }.thenByDescending {
                    it.updatedAt?.toDate()?.time ?: it.createdAt?.toDate()?.time ?: 0L
                }
            )
        }

        fun filterSubscribers(
            subscribers: List<UserEntitlement>,
            filter: AdminSubscriberFilter,
            query: String,
            nowMs: Long = System.currentTimeMillis()
        ): List<UserEntitlement> {
            val trimmed = query.trim().lowercase()
            return subscribers.filter { user ->
                if (!user.isPremiumUser || user.isBlocked) return@filter false
                val daysLeft = getSubscriptionRemainingDays(user, nowMs)
                val matchesMode = when (filter) {
                    AdminSubscriberFilter.EXPIRING_SOON -> daysLeft in 1..SUB_EXPIRING_SOON_DAYS
                    AdminSubscriberFilter.ALL_ACTIVE_SUBSCRIBERS -> daysLeft > 0 || daysLeft == -1
                }
                if (!matchesMode) return@filter false
                if (trimmed.isEmpty()) return@filter true

                val deviceTag = if (user.deviceHash.length == 64) {
                    DeviceIdentityProvider.formatShortDeviceTag(user.deviceHash).lowercase()
                } else {
                    ""
                }
                user.email.lowercase().contains(trimmed) ||
                    user.userId.lowercase().contains(trimmed) ||
                    user.id.lowercase().contains(trimmed) ||
                    user.displayName.lowercase().contains(trimmed) ||
                    user.phoneNumber.lowercase().contains(trimmed) ||
                    user.deviceHash.lowercase().contains(trimmed) ||
                    deviceTag.contains(trimmed) ||
                    user.adminNote.lowercase().contains(trimmed)
            }
        }

        fun filterPaymentRequests(
            requests: List<PaymentRequest>,
            filter: AdminPaymentFilter,
            query: String
        ): List<PaymentRequest> {
            val trimmed = query.trim().lowercase()
            return requests.filter { req ->
                val matchesFilter = when (filter) {
                    AdminPaymentFilter.ALL -> true
                    AdminPaymentFilter.PENDING -> req.status == PaymentRequest.STATUS_PENDING
                    AdminPaymentFilter.APPROVED -> req.status == PaymentRequest.STATUS_APPROVED
                    AdminPaymentFilter.DISAPPROVED -> req.status == PaymentRequest.STATUS_REJECTED
                }
                if (!matchesFilter) return@filter false
                if (trimmed.isEmpty()) return@filter true

                req.userName.lowercase().contains(trimmed) ||
                    req.userEmail.lowercase().contains(trimmed) ||
                    req.userId.lowercase().contains(trimmed) ||
                    req.shortDeviceTag.lowercase().contains(trimmed) ||
                    req.txnRef.lowercase().contains(trimmed) ||
                    req.upiTxnId.lowercase().contains(trimmed) ||
                    req.approvalRefNo.lowercase().contains(trimmed)
            }
        }
    }
}
