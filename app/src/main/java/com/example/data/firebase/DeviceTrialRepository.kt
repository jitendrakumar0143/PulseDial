package com.example.data.firebase

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import com.example.R
import com.example.billing.BillingManager
import com.example.data.DialerPreferences
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlin.math.ceil

class DeviceTrialRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth,
    private val prefs: DialerPreferences? = null
) {

    constructor(context: Context) : this(
        db = FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        ),
        auth = Firebase.auth,
        prefs = DialerPreferences(context.applicationContext)
    )

    private fun requireUserId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before verifying device trial.")
    }

    fun observeUserEntitlement(): Flow<UserEntitlement?> = flow {
        val uid = requireUserId()
        val path = "$COLLECTION_USERS/$uid"
        emitAll(
            db.collection(COLLECTION_USERS).document(uid)
                .snapshots()
                .map { snapshot ->
                    if (!snapshot.exists()) {
                        null
                    } else {
                        val user = snapshot.toObject(
                            UserEntitlement::class.java,
                            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                        )
                        prefs?.let { SettingsManager.applyCloudUserSettings(snapshot, user, it) }
                        user
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

    fun observeDeviceTrial(deviceHash: String): Flow<DeviceTrial?> = flow {
        val uid = requireUserId()
        val path = "$COLLECTION_DEVICE_TRIALS/$deviceHash"
        emitAll(
            db.collection(COLLECTION_DEVICE_TRIALS).document(deviceHash)
                .snapshots()
                .map { snapshot ->
                    if (!snapshot.exists()) {
                        null
                    } else {
                        snapshot.toObject(
                            DeviceTrial::class.java,
                            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                        )
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

    suspend fun syncDeviceAndUserTrial(deviceHash: String): Result<TrialVerificationResult> {
        val currentUser = runCatching { auth.currentUser }.getOrNull()
        if (currentUser == null) {
            val fallbackUid = prefs?.loggedInUserId?.takeIf { prefs.isLoggedIn && it.isNotBlank() }
                ?: return Result.failure(
                    IllegalStateException("User must be signed in with Google before verifying device trial.")
                )
            val nowMs = System.currentTimeMillis()
            val cleanPrefs = prefs!!
            val totalTrialDays = (cleanPrefs.configuredTrialDays + cleanPrefs.adminBonusTrialDays)
                .coerceIn(1, 4000)
            val totalTrialMs = totalTrialDays.toLong() * DialerPreferences.ONE_DAY_MILLIS
            val startMs = cleanPrefs.firstLaunchTime
            val isPremium = !cleanPrefs.adminBlockedUser && cleanPrefs.isPremiumActive(nowMs)
            val trialExpired = (nowMs - startMs) >= totalTrialMs
            val status = when {
                isPremium -> TrialStatus.PREMIUM_ACTIVE
                cleanPrefs.trialBlockedByDevice && trialExpired -> TrialStatus.DEVICE_TRIAL_ALREADY_USED
                trialExpired -> TrialStatus.TRIAL_EXPIRED
                else -> TrialStatus.FREE_TRIAL_ACTIVE
            }
            val result = TrialVerificationResult(
                status = status,
                deviceHash = deviceHash,
                shortDeviceTag = cleanPrefs.shortDeviceTag.ifBlank {
                    DeviceIdentityProvider.formatShortDeviceTag(deviceHash)
                },
                daysRemaining = cleanPrefs.getTrialDaysRemaining(nowMs),
                isPremium = isPremium,
                hasRecordingAccess = !cleanPrefs.adminBlockedUser && (isPremium || !trialExpired),
                trialStartedAtMs = startMs,
                serverNowMs = nowMs,
                accountSwitchCount = cleanPrefs.accountSwitchCount,
                isBlocked = cleanPrefs.adminBlockedUser,
                bonusTrialDays = cleanPrefs.adminBonusTrialDays.toLong(),
                subscriptionExpiresAtMs = cleanPrefs.subscriptionExpiresAtMs
            )
            RealtimeDatabaseSync.pushUserFields(
                fallbackUid,
                mapOf(
                    "deviceHash" to deviceHash,
                    "updatedAtMs" to nowMs
                )
            )
            return Result.success(result)
        }
        val uid = currentUser.uid
        val deviceDocPath = "$COLLECTION_DEVICE_TRIALS/$deviceHash"
        val deviceDocRef = db.collection(COLLECTION_DEVICE_TRIALS).document(deviceHash)

        val deviceTrial: DeviceTrial = try {
            val existingSnap = deviceDocRef.snapshots().first()
            val existing = existingSnap.toObject(
                DeviceTrial::class.java,
                DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
            )
            if (!existingSnap.exists() || existing == null) {
                val createPayload = mapOf(
                    "deviceHash" to deviceHash,
                    "ownerUid" to uid,
                    "lastUserId" to uid,
                    "accountSwitchCount" to 0L,
                    "trialStartedAt" to FieldValue.serverTimestamp(),
                    "lastVerifiedAt" to FieldValue.serverTimestamp(),
                    "isPremiumDevice" to false,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                deviceDocRef.set(createPayload).awaitTask()
                val createdSnap = deviceDocRef.snapshots().first()
                createdSnap.toObject(
                    DeviceTrial::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                ) ?: DeviceTrial(deviceHash = deviceHash, ownerUid = uid, lastUserId = uid)
            } else {
                val switchedAccount = existing.ownerUid != uid && existing.lastUserId != uid
                val nextCount = if (switchedAccount) existing.accountSwitchCount + 1L else existing.accountSwitchCount
                val updatePayload = mapOf(
                    "lastUserId" to uid,
                    "accountSwitchCount" to nextCount,
                    "lastVerifiedAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                deviceDocRef.update(updatePayload).awaitTask()
                val refreshedSnap = deviceDocRef.snapshots().first()
                refreshedSnap.toObject(
                    DeviceTrial::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                ) ?: existing
            }
        } catch (e: Exception) {
            val firestoreError = generateSequence<Throwable>(e) { it.cause }
                .filterIsInstance<FirebaseFirestoreException>()
                .firstOrNull()
            if (firestoreError == null && e is CancellationException) throw e
            val actualError = firestoreError ?: e
            handleFirestoreError(actualError, OperationType.WRITE, deviceDocPath, auth)
            return Result.failure(actualError)
        }

        val userDocPath = "$COLLECTION_USERS/$uid"
        val userDocRef = db.collection(COLLECTION_USERS).document(uid)

        val userEntitlement: UserEntitlement = try {
            val userSnap = userDocRef.snapshots().first()
            val existingUser = userSnap.toObject(
                UserEntitlement::class.java,
                DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
            )
            val sanitizedLocalPhone = prefs?.userPhoneNumber
                ?.takeIf { it.isNotBlank() && it != DialerPreferences.DEFAULT_USER_NUMBER }
                ?.take(32)
            if (!userSnap.exists() || existingUser == null) {
                val initialTheme = if (prefs?.displayThemeMode == AppCompatDelegate.MODE_NIGHT_YES) {
                    UserEntitlement.THEME_DARK
                } else {
                    UserEntitlement.THEME_LIGHT
                }
                val createUserPayload = mapOf(
                    "userId" to uid,
                    "displayName" to (currentUser?.displayName.orEmpty().take(120)),
                    "email" to (currentUser?.email.orEmpty().take(254)),
                    "phoneNumber" to sanitizedLocalPhone.orEmpty(),
                    "deviceHash" to deviceHash,
                    "trialStartedAt" to FieldValue.serverTimestamp(),
                    "isPremiumUser" to false,
                    "subscriptionStatus" to UserEntitlement.STATUS_TRIAL,
                    "subscriptionProductId" to "",
                    "purchaseToken" to "",
                    "autoCallRecording" to (prefs?.autoCallRecording ?: false),
                    "themeMode" to initialTheme,
                    "dialpadTonesEnabled" to (prefs?.dialpadTonesEnabled ?: true),
                    "blockedNumbers" to (prefs?.getBlockedNumbers()?.map { it.take(40) }?.take(500) ?: emptyList()),
                    "quickDeclineResponses" to (
                        prefs?.getQuickDeclineResponses()?.map { it.take(200) }?.take(50)
                            ?: DialerPreferences.DEFAULT_QUICK_DECLINE_RESPONSES
                        ),
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                userDocRef.set(createUserPayload).awaitTask()
                val createdUserSnap = userDocRef.snapshots().first()
                val created = createdUserSnap.toObject(
                    UserEntitlement::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                ) ?: UserEntitlement(userId = uid, deviceHash = deviceHash, isPremiumUser = false)
                RealtimeDatabaseSync.pushUserEntitlement(created)
                created
            } else {
                val updateUserPayload = mapOf(
                    "displayName" to (currentUser?.displayName?.take(120) ?: existingUser.displayName),
                    "email" to (currentUser?.email?.take(254) ?: existingUser.email),
                    "phoneNumber" to (sanitizedLocalPhone ?: existingUser.phoneNumber),
                    "deviceHash" to deviceHash,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                userDocRef.update(updateUserPayload).awaitTask()
                val refreshedUserSnap = userDocRef.snapshots().first()
                val refreshedUser = refreshedUserSnap.toObject(
                    UserEntitlement::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                ) ?: existingUser
                prefs?.let { SettingsManager.applyCloudUserSettings(refreshedUserSnap, refreshedUser, it) }
                RealtimeDatabaseSync.pushUserEntitlement(refreshedUser)
                refreshedUser
            }
        } catch (e: Exception) {
            val firestoreError = generateSequence<Throwable>(e) { it.cause }
                .filterIsInstance<FirebaseFirestoreException>()
                .firstOrNull()
            if (firestoreError == null && e is CancellationException) throw e
            val actualError = firestoreError ?: e
            handleFirestoreError(actualError, OperationType.WRITE, userDocPath, auth)
            return Result.failure(actualError)
        }

        val baseTrialDays = (prefs?.configuredTrialDays ?: DialerPreferences.TRIAL_DAYS).coerceIn(1, 365)
        val bonusDays = userEntitlement.bonusTrialDays.coerceAtLeast(0L)
        val totalTrialDays = (baseTrialDays + bonusDays).coerceIn(1L, 4000L)
        val totalTrialDurationMs = totalTrialDays * DialerPreferences.ONE_DAY_MILLIS

        val serverNowMs = deviceTrial.lastVerifiedAt?.toDate()?.time
            ?: userEntitlement.updatedAt?.toDate()?.time
            ?: System.currentTimeMillis()
        val deviceStartMs = deviceTrial.trialStartedAt?.toDate()?.time ?: serverNowMs
        val userStartMs = userEntitlement.trialStartedAt?.toDate()?.time ?: deviceStartMs

        val localNow = System.currentTimeMillis()
        val localElapsedMs = prefs?.let {
            (maxOf(localNow, it.lastSeenTimeMs) - it.firstLaunchTime)
                .coerceIn(0L, totalTrialDurationMs)
        } ?: 0L
        val offlineAdjustedStartMs = serverNowMs - localElapsedMs

        val enforceSingleDevice = prefs?.enforceSingleDeviceTrialConfig ?: true
        val effectiveStartMs = if (enforceSingleDevice) {
            minOf(deviceStartMs, userStartMs, offlineAdjustedStartMs)
        } else {
            minOf(userStartMs, offlineAdjustedStartMs)
        }

        val isBlocked = userEntitlement.isBlocked ||
            userEntitlement.subscriptionStatus == UserEntitlement.STATUS_BLOCKED
        val userStatusForcesInactive = userEntitlement.subscriptionStatus == UserEntitlement.STATUS_EXPIRED ||
            userEntitlement.subscriptionStatus == UserEntitlement.STATUS_REJECTED ||
            userEntitlement.subscriptionStatus == UserEntitlement.STATUS_BLOCKED
        val rawPremium = !userStatusForcesInactive &&
            (userEntitlement.isPremiumUser || userEntitlement.subscriptionStatus == UserEntitlement.STATUS_ACTIVE)
        val userExpiryMs = if (!userStatusForcesInactive) {
            userEntitlement.subscriptionExpiresAt?.toDate()?.time
                ?: if (userEntitlement.isPremiumUser) {
                    userEntitlement.updatedAt?.toDate()?.time?.let { it + DialerPreferences.ONE_MONTH_MILLIS }
                } else {
                    null
                }
        } else {
            null
        }
        val effectiveExpiresAtMs = (userExpiryMs ?: 0L).coerceAtLeast(0L)
        val isPremium = !isBlocked && rawPremium && (effectiveExpiresAtMs <= 0L || serverNowMs < effectiveExpiresAtMs)

        val trialExpired = (serverNowMs - effectiveStartMs) >= totalTrialDurationMs
        val deviceClaimedByDifferentOwner =
            enforceSingleDevice && deviceTrial.ownerUid.isNotBlank() && deviceTrial.ownerUid != uid

        val remainingMs = (effectiveStartMs + totalTrialDurationMs) - serverNowMs
        val daysRemaining = if (remainingMs <= 0L) {
            0
        } else {
            ceil(remainingMs.toDouble() / DialerPreferences.ONE_DAY_MILLIS.toDouble())
                .toInt()
                .coerceIn(1, totalTrialDays.toInt())
        }

        val status = when {
            isPremium -> TrialStatus.PREMIUM_ACTIVE
            deviceClaimedByDifferentOwner && trialExpired -> TrialStatus.DEVICE_TRIAL_ALREADY_USED
            trialExpired -> TrialStatus.TRIAL_EXPIRED
            else -> TrialStatus.FREE_TRIAL_ACTIVE
        }

        val result = TrialVerificationResult(
            status = status,
            deviceHash = deviceHash,
            shortDeviceTag = DeviceIdentityProvider.formatShortDeviceTag(deviceHash),
            daysRemaining = daysRemaining,
            isPremium = isPremium,
            hasRecordingAccess = !isBlocked && (isPremium || !trialExpired),
            trialStartedAtMs = effectiveStartMs,
            serverNowMs = serverNowMs,
            accountSwitchCount = deviceTrial.accountSwitchCount,
            isBlocked = isBlocked,
            bonusTrialDays = bonusDays,
            subscriptionExpiresAtMs = effectiveExpiresAtMs
        )

        prefs?.applyCloudVerification(result, currentUser?.displayName, currentUser?.email)
        return Result.success(result)
    }

    /**
     * Legacy/Play Billing sync attempt. Firestore security rules block non-admin writes to
     * isPremiumUser/isPremiumDevice; verified admin approval is the sole unlock authority.
     */
    suspend fun recordPremiumPurchase(
        deviceHash: String,
        productId: String,
        purchaseToken: String
    ): Result<Unit> {
        val uid = try {
            requireUserId()
        } catch (e: Exception) {
            return Result.failure(e)
        }

        val userDocPath = "$COLLECTION_USERS/$uid"
        val deviceDocPath = "$COLLECTION_DEVICE_TRIALS/$deviceHash"
        val cleanToken = purchaseToken.take(512)
        val cleanProductId = productId.take(64)

        try {
            db.collection(COLLECTION_USERS).document(uid).update(
                mapOf(
                    "isPremiumUser" to true,
                    "subscriptionStatus" to UserEntitlement.STATUS_ACTIVE,
                    "subscriptionProductId" to cleanProductId,
                    "purchaseToken" to cleanToken,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).awaitTask()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, userDocPath, auth)
            return Result.failure(e)
        }

        try {
            db.collection(COLLECTION_DEVICE_TRIALS).document(deviceHash).update(
                mapOf(
                    "isPremiumDevice" to true,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).awaitTask()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, deviceDocPath, auth)
            return Result.failure(e)
        }

        return Result.success(Unit)
    }

    suspend fun updateUserProfilePhone(displayName: String, phoneNumber: String): Result<Unit> {
        val currentUser = runCatching { auth.currentUser }.getOrNull()
        val uid = currentUser?.uid
            ?: prefs?.loggedInUserId?.takeIf { prefs.isLoggedIn && it.isNotBlank() }
            ?: return Result.failure(
                IllegalStateException("User must be signed in with Google before updating profile.")
            )
        val cleanName = displayName.trim().take(120)
        val cleanPhone = phoneNumber.trim().take(32)
        RealtimeDatabaseSync.pushUserFields(
            uid,
            mapOf(
                "displayName" to cleanName,
                "phone" to cleanPhone,
                "phoneNumber" to cleanPhone,
                "updatedAtMs" to System.currentTimeMillis()
            )
        )
        if (currentUser == null) {
            return Result.success(Unit)
        }
        val userDocRef = db.collection(COLLECTION_USERS).document(uid)
        return try {
            userDocRef.update(
                mapOf(
                    "displayName" to cleanName,
                    "phoneNumber" to cleanPhone,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).awaitTask()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, "$COLLECTION_USERS/$uid", auth)
            Result.failure(e)
        }
    }

    suspend fun getUserEntitlement(): Result<UserEntitlement?> {
        val uid = try {
            requireUserId()
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val path = "$COLLECTION_USERS/$uid"
        return try {
            val snap = db.collection(COLLECTION_USERS).document(uid).snapshots().first()
            val item = snap.toObject(
                UserEntitlement::class.java,
                DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
            )
            Result.success(item)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, path, auth)
            Result.failure(e)
        }
    }

    companion object {
        const val COLLECTION_DEVICE_TRIALS = "device_trials"
        const val COLLECTION_USERS = "users"
    }
}
