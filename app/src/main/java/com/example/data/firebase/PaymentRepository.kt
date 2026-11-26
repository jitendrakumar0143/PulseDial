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
import com.google.firebase.firestore.snapshots
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

class PaymentRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth,
    private val fallbackUidProvider: () -> String? = { null },
    private val appContext: Context? = null
) {

    constructor(context: Context) : this(
        db = FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        ),
        auth = Firebase.auth,
        fallbackUidProvider = { GoogleAuthHelper.resolveActiveUid(context.applicationContext) },
        appContext = context.applicationContext
    )

    private fun requireUserId(): String {
        val firebaseUid = runCatching { auth.currentUser?.uid }.getOrNull()?.takeIf { it.isNotBlank() }
        if (firebaseUid != null) return firebaseUid
        val fallbackUid = fallbackUidProvider()?.takeIf { it.isNotBlank() }
        if (fallbackUid != null) return fallbackUid
        throw IllegalStateException("User must be signed in before managing payments.")
    }

    fun observeUserEntitlement(): Flow<UserEntitlement?> = flow {
        val uid = requireUserId()
        val path = "${DeviceTrialRepository.COLLECTION_USERS}/$uid"
        emitAll(
            db.collection(DeviceTrialRepository.COLLECTION_USERS).document(uid)
                .snapshots()
                .map { snapshot ->
                    if (!snapshot.exists()) null
                    else snapshot.toObject(
                        UserEntitlement::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
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

    fun observeMyPaymentRequests(): Flow<List<PaymentRequest>> = flow {
        val uid = requireUserId()
        emitAll(
            db.collection(COLLECTION_PAYMENT_REQUESTS)
                .whereEqualTo("userId", uid)
                .snapshots()
                .map { snapshot ->
                    snapshot.documents.mapNotNull { doc ->
                        doc.toObject(
                            PaymentRequest::class.java,
                            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                        )
                    }.sortedByDescending { it.createdAt?.toDate()?.time ?: 0L }
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, COLLECTION_PAYMENT_REQUESTS, auth)
                    }
                    throw error
                }
        )
    }

    fun observeAllPaymentRequestsForAdmin(): Flow<List<PaymentRequest>> = flow {
        val uid = requireUserId()
        emitAll(
            db.collection(COLLECTION_PAYMENT_REQUESTS)
                .snapshots()
                .map { snapshot ->
                    snapshot.documents.mapNotNull { doc ->
                        doc.toObject(
                            PaymentRequest::class.java,
                            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                        )
                    }.sortedWith(
                        compareBy<PaymentRequest> {
                            when (it.status) {
                                PaymentRequest.STATUS_PENDING -> 0
                                PaymentRequest.STATUS_APPROVED -> 1
                                else -> 2
                            }
                        }.thenByDescending { it.createdAt?.toDate()?.time ?: 0L }
                    )
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, COLLECTION_PAYMENT_REQUESTS, auth)
                    }
                    throw error
                }
        )
    }

    suspend fun submitUpiPaymentRequest(
        deviceHash: String,
        shortDeviceTag: String,
        userName: String,
        userEmail: String,
        txnRef: String,
        upiTxnId: String,
        approvalRefNo: String,
        upiResponseStatus: String,
        upiId: String = UpiPaymentHelper.UPI_VPA,
        amountInr: Long = UpiPaymentHelper.UPI_AMOUNT_LONG
    ): Result<String> {
        val uid = try {
            requireUserId()
        } catch (e: Exception) {
            return Result.failure(e)
        }

        val docRef = db.collection(COLLECTION_PAYMENT_REQUESTS).document()
        val userRef = db.collection(DeviceTrialRepository.COLLECTION_USERS).document(uid)
        val cleanStatus = when (upiResponseStatus.uppercase()) {
            "SUCCESS", "SUBMITTED", "MANUAL_UTR" -> upiResponseStatus.uppercase()
            else -> "SUBMITTED"
        }
        val cleanTxnRef = txnRef.trim().ifBlank { UpiPaymentHelper.generateTxnRef() }.take(64)
        val payload = mapOf(
            "userId" to uid,
            "userName" to userName.trim().ifBlank { "PulseDial User" }.take(120),
            "userEmail" to userEmail.trim().take(254),
            "deviceHash" to deviceHash,
            "shortDeviceTag" to shortDeviceTag.ifBlank { DeviceIdentityProvider.formatShortDeviceTag(deviceHash) }.take(32),
            "upiId" to upiId.trim().ifBlank { UpiPaymentHelper.UPI_VPA }.take(100),
            "amountInr" to amountInr.coerceIn(1L, 100000L),
            "txnRef" to cleanTxnRef,
            "upiTxnId" to upiTxnId.trim().take(64),
            "approvalRefNo" to approvalRefNo.trim().take(64),
            "upiResponseStatus" to cleanStatus,
            "status" to PaymentRequest.STATUS_PENDING,
            "reviewedByUid" to "",
            "reviewNote" to "",
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )

        return try {
            val effectiveRefId = upiTxnId.trim()
                .ifBlank { approvalRefNo.trim() }
                .ifBlank { cleanTxnRef }
                .take(64)
            val utrKey = normalizeUtrKey(effectiveRefId)
            if (utrKey.isNotBlank() && RealtimeDatabaseSync.isUtrApprovedInRtdb(utrKey)) {
                return Result.failure(
                    IllegalStateException("This UTR has already been approved once and cannot be reused.")
                )
            }
            if (utrKey.isNotBlank() && runCatching { auth.currentUser }.getOrNull() != null) {
                val existingLock = runCatching {
                    db.collection(COLLECTION_APPROVED_UTRS).document(utrKey).snapshots().first()
                }.getOrNull()
                if (existingLock?.exists() == true) {
                    return Result.failure(
                        IllegalStateException("This UTR has already been approved once and cannot be reused.")
                    )
                }
            }
            val nowTs = Timestamp(Date(System.currentTimeMillis()))
            val rtdbRequest = PaymentRequest(
                id = docRef.id,
                userId = uid,
                userName = userName.trim().ifBlank { "PulseDial User" }.take(120),
                userEmail = userEmail.trim().take(254),
                deviceHash = deviceHash,
                shortDeviceTag = shortDeviceTag.ifBlank {
                    DeviceIdentityProvider.formatShortDeviceTag(deviceHash)
                }.take(32),
                upiId = upiId.trim().ifBlank { UpiPaymentHelper.UPI_VPA }.take(100),
                amountInr = amountInr.coerceIn(1L, 100000L),
                txnRef = cleanTxnRef,
                upiTxnId = upiTxnId.trim().take(64),
                approvalRefNo = approvalRefNo.trim().take(64),
                upiResponseStatus = cleanStatus,
                status = PaymentRequest.STATUS_PENDING,
                reviewedByUid = "",
                reviewNote = "",
                createdAt = nowTs,
                updatedAt = nowTs
            )
            // Push to Firebase Realtime Database (/payment_requests & /users/{uid})
            RealtimeDatabaseSync.pushPaymentRequest(rtdbRequest)
            RealtimeDatabaseSync.pushUserFields(
                uid,
                mapOf(
                    "subscriptionStatus" to UserEntitlement.STATUS_PENDING_APPROVAL,
                    "paymentRefId" to effectiveRefId,
                    "updatedAtMs" to System.currentTimeMillis()
                )
            )
            if (runCatching { auth.currentUser }.getOrNull() == null) {
                return Result.success(docRef.id)
            }
            docRef.set(payload).awaitTask()
            runCatching {
                val userSnap = userRef.snapshots().first()
                if (userSnap.exists()) {
                    userRef.update(
                        mapOf(
                            "subscriptionStatus" to UserEntitlement.STATUS_PENDING_APPROVAL,
                            "paymentRefId" to effectiveRefId,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    ).awaitTask()
                }
            }
            Result.success(docRef.id)
        } catch (e: Exception) {
            val firestoreError = generateSequence<Throwable>(e) { it.cause }
                .filterIsInstance<FirebaseFirestoreException>()
                .firstOrNull()
            if (firestoreError == null && e is CancellationException) throw e
            val actualError = firestoreError ?: e
            handleFirestoreError(actualError, OperationType.CREATE, docRef.path, auth)
            Result.failure(actualError)
        }
    }

    suspend fun approvePaymentRequest(
        request: PaymentRequest,
        reviewNote: String = "Verified on ${request.upiId.ifBlank { UpiPaymentHelper.UPI_VPA }}",
        knownRequests: List<PaymentRequest> = emptyList(),
        approvedUtrKeys: Set<String> = emptySet()
    ): Result<Unit> {
        val adminUid = try {
            requireUserId()
        } catch (e: Exception) {
            return Result.failure(e)
        }
        require(request.id.isNotBlank()) { "Payment request ID must not be blank" }

        if (request.status == PaymentRequest.STATUS_APPROVED) {
            return Result.failure(
                IllegalStateException("UTR can only be approved once. This request was already approved.")
            )
        }

        val canonicalUtr = extractCanonicalUtr(request)
        val utrKey = normalizeUtrKey(canonicalUtr)
        if (utrKey.isNotBlank()) {
            val duplicateInKnown = knownRequests.any { existing ->
                existing.status == PaymentRequest.STATUS_APPROVED &&
                    normalizeUtrKey(extractCanonicalUtr(existing)) == utrKey
            }
            if (approvedUtrKeys.contains(utrKey) ||
                duplicateInKnown ||
                RealtimeDatabaseSync.isUtrApprovedInRtdb(utrKey)
            ) {
                return Result.failure(
                    IllegalStateException("UTR ($canonicalUtr) has already been approved once and cannot be reused.")
                )
            }
        }

        val reqRef = db.collection(COLLECTION_PAYMENT_REQUESTS).document(request.id)
        val userRef = db.collection(DeviceTrialRepository.COLLECTION_USERS).document(request.userId)
        val deviceRef = db.collection(DeviceTrialRepository.COLLECTION_DEVICE_TRIALS).document(request.deviceHash)
        val cleanNote = reviewNote.trim().take(240)

        return try {
            val nowMs = RealtimeDatabaseSync.currentServerNowMs()
            if (runCatching { auth.currentUser }.getOrNull() == null) {
                val nextExpiresAtMs = nowMs + DialerPreferences.ONE_MONTH_MILLIS
                if (utrKey.isNotBlank()) {
                    RealtimeDatabaseSync.markUtrApproved(
                        utrRaw = canonicalUtr,
                        requestId = request.id,
                        userId = request.userId,
                        approvedByUid = adminUid
                    )
                }
                RealtimeDatabaseSync.updatePaymentRequestStatus(
                    requestId = request.id,
                    status = PaymentRequest.STATUS_APPROVED,
                    reviewedByUid = adminUid,
                    reviewNote = cleanNote
                )
                val rtdbApproveMap = mapOf(
                    "isPremium" to true,
                    "isPremiumUser" to true,
                    "isBlocked" to false,
                    "subscriptionStatus" to UserEntitlement.STATUS_ACTIVE,
                    "subscriptionExpiresAtMs" to nextExpiresAtMs,
                    "updatedAtMs" to maxOf(nowMs, System.currentTimeMillis())
                )
                RealtimeDatabaseSync.pushUserFields(request.userId, rtdbApproveMap)
                RealtimeDatabaseSync.propagateEntitlementByEmailInRtdb(
                    email = request.userEmail,
                    primaryUid = request.userId,
                    fields = rtdbApproveMap,
                    deviceHash = request.deviceHash
                )
                return Result.success(Unit)
            }
            if (utrKey.isNotBlank()) {
                val firestoreUtrLock = runCatching {
                    db.collection(COLLECTION_APPROVED_UTRS).document(utrKey).snapshots().first()
                }.getOrNull()
                if (firestoreUtrLock?.exists() == true) {
                    return Result.failure(
                        IllegalStateException("UTR ($canonicalUtr) has already been approved once and cannot be reused.")
                    )
                }
            }
            val currentReqSnap = runCatching { reqRef.snapshots().first() }.getOrNull()
            if (currentReqSnap?.getString("status") == PaymentRequest.STATUS_APPROVED) {
                return Result.failure(
                    IllegalStateException("UTR can only be approved once. This request was already approved.")
                )
            }
            val userSnap = runCatching { userRef.snapshots().first() }.getOrNull()
            val deviceSnap = if (request.deviceHash.length == 64) {
                runCatching { deviceRef.snapshots().first() }.getOrNull()
            } else {
                null
            }

            val existingUserExpMs = userSnap?.getTimestamp("subscriptionExpiresAt")?.toDate()?.time ?: 0L
            val existingDeviceExpMs = deviceSnap?.getTimestamp("subscriptionExpiresAt")?.toDate()?.time ?: 0L
            val renewalBaseMs = maxOf(nowMs, existingUserExpMs, existingDeviceExpMs)
            val nextExpiresAt = Timestamp(Date(renewalBaseMs + DialerPreferences.ONE_MONTH_MILLIS))

            if (utrKey.isNotBlank()) {
                RealtimeDatabaseSync.markUtrApproved(
                    utrRaw = canonicalUtr,
                    requestId = request.id,
                    userId = request.userId,
                    approvedByUid = adminUid
                )
            }
            RealtimeDatabaseSync.updatePaymentRequestStatus(
                requestId = request.id,
                status = PaymentRequest.STATUS_APPROVED,
                reviewedByUid = adminUid,
                reviewNote = cleanNote
            )
            val rtdbApproveMap = mapOf(
                "isPremium" to true,
                "isPremiumUser" to true,
                "isBlocked" to false,
                "subscriptionStatus" to UserEntitlement.STATUS_ACTIVE,
                "subscriptionExpiresAtMs" to nextExpiresAt.toDate().time,
                "updatedAtMs" to maxOf(nowMs, System.currentTimeMillis())
            )
            RealtimeDatabaseSync.pushUserFields(request.userId, rtdbApproveMap)
            RealtimeDatabaseSync.propagateEntitlementByEmailInRtdb(
                email = request.userEmail,
                primaryUid = request.userId,
                fields = rtdbApproveMap,
                deviceHash = request.deviceHash
            )

            val batch = db.batch()
            var hasBatchWrites = false
            if (utrKey.isNotBlank()) {
                val utrLockRef = db.collection(COLLECTION_APPROVED_UTRS).document(utrKey)
                batch.set(
                    utrLockRef,
                    mapOf(
                        "utr" to utrKey,
                        "rawUtr" to canonicalUtr,
                        "requestId" to request.id,
                        "userId" to request.userId,
                        "approvedByUid" to adminUid,
                        "approvedAt" to FieldValue.serverTimestamp()
                    )
                )
                hasBatchWrites = true
            }
            if (currentReqSnap?.exists() == true) {
                batch.update(
                    reqRef,
                    mapOf(
                        "status" to PaymentRequest.STATUS_APPROVED,
                        "reviewedByUid" to adminUid,
                        "reviewNote" to cleanNote,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                hasBatchWrites = true
            }
            val userApprovePayload = mapOf(
                "isPremiumUser" to true,
                "isBlocked" to false,
                "subscriptionStatus" to UserEntitlement.STATUS_ACTIVE,
                "subscriptionProductId" to UpiPaymentHelper.UPI_PLAN_ID,
                "purchaseToken" to request.upiTxnId.ifBlank { request.txnRef }.take(512),
                "subscriptionExpiresAt" to nextExpiresAt,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (userSnap?.exists() == true) {
                batch.update(userRef, userApprovePayload)
                hasBatchWrites = true
            }
            val cleanEmail = request.userEmail.trim()
            if (cleanEmail.isNotEmpty()) {
                val emailMatches = runCatching {
                    db.collection(DeviceTrialRepository.COLLECTION_USERS)
                        .whereEqualTo("email", cleanEmail)
                        .snapshots()
                        .first()
                }.getOrNull()
                emailMatches?.documents?.forEach { doc ->
                    if (doc.id != request.userId) {
                        batch.update(doc.reference, userApprovePayload)
                        hasBatchWrites = true
                    }
                }
            }
            if (deviceSnap?.exists() == true) {
                batch.update(
                    deviceRef,
                    mapOf(
                        "isPremiumDevice" to true,
                        "subscriptionExpiresAt" to nextExpiresAt,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                hasBatchWrites = true
            }
            if (hasBatchWrites) {
                batch.commit().awaitTask()
            }
            Result.success(Unit)
        } catch (e: Exception) {
            val firestoreError = generateSequence<Throwable>(e) { it.cause }
                .filterIsInstance<FirebaseFirestoreException>()
                .firstOrNull()
            if (firestoreError == null && e is CancellationException) throw e
            if (firestoreError?.code == FirebaseFirestoreException.Code.NOT_FOUND) {
                return Result.success(Unit)
            }
            val actualError = firestoreError ?: e
            handleFirestoreError(actualError, OperationType.UPDATE, reqRef.path, auth)
            Result.failure(actualError)
        }
    }

    /**
     * Disapproves (rejects/revokes) a payment request and revokes user/device premium entitlement if active.
     */
    suspend fun rejectPaymentRequest(
        request: PaymentRequest,
        reviewNote: String = "Disapproved by Admin"
    ): Result<Unit> {
        val adminUid = try {
            requireUserId()
        } catch (e: Exception) {
            return Result.failure(e)
        }
        require(request.id.isNotBlank()) { "Payment request ID must not be blank" }

        val reqRef = db.collection(COLLECTION_PAYMENT_REQUESTS).document(request.id)
        val userRef = db.collection(DeviceTrialRepository.COLLECTION_USERS).document(request.userId)
        val deviceRef = db.collection(DeviceTrialRepository.COLLECTION_DEVICE_TRIALS).document(request.deviceHash)
        val cleanNote = reviewNote.trim().take(240)

        return try {
            val nowMs = RealtimeDatabaseSync.currentServerNowMs()
            RealtimeDatabaseSync.updatePaymentRequestStatus(
                requestId = request.id,
                status = PaymentRequest.STATUS_REJECTED,
                reviewedByUid = adminUid,
                reviewNote = cleanNote
            )
            val rtdbRejectMap = mapOf(
                "isPremium" to false,
                "isPremiumUser" to false,
                "subscriptionStatus" to UserEntitlement.STATUS_EXPIRED,
                "subscriptionExpiresAtMs" to null,
                "updatedAtMs" to maxOf(nowMs, System.currentTimeMillis())
            )
            RealtimeDatabaseSync.pushUserFields(request.userId, rtdbRejectMap)
            RealtimeDatabaseSync.propagateEntitlementByEmailInRtdb(
                email = request.userEmail,
                primaryUid = request.userId,
                fields = rtdbRejectMap,
                deviceHash = request.deviceHash
            )
            if (runCatching { auth.currentUser }.getOrNull() == null) {
                return Result.success(Unit)
            }
            val currentReqSnap = runCatching { reqRef.snapshots().first() }.getOrNull()
            val userSnap = runCatching { userRef.snapshots().first() }.getOrNull()
            val deviceSnap = if (request.deviceHash.length == 64) {
                runCatching { deviceRef.snapshots().first() }.getOrNull()
            } else {
                null
            }

            val batch = db.batch()
            var hasBatchWrites = false
            if (currentReqSnap?.exists() == true) {
                batch.update(
                    reqRef,
                    mapOf(
                        "status" to PaymentRequest.STATUS_REJECTED,
                        "reviewedByUid" to adminUid,
                        "reviewNote" to cleanNote,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                hasBatchWrites = true
            }
            val userRejectPayload = mapOf(
                "isPremiumUser" to false,
                "subscriptionStatus" to UserEntitlement.STATUS_REJECTED,
                "subscriptionProductId" to "",
                "purchaseToken" to "",
                "subscriptionExpiresAt" to null,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (userSnap?.exists() == true) {
                batch.update(userRef, userRejectPayload)
                hasBatchWrites = true
            }
            val cleanEmail = request.userEmail.trim()
            if (cleanEmail.isNotEmpty()) {
                val emailMatches = runCatching {
                    db.collection(DeviceTrialRepository.COLLECTION_USERS)
                        .whereEqualTo("email", cleanEmail)
                        .snapshots()
                        .first()
                }.getOrNull()
                emailMatches?.documents?.forEach { doc ->
                    if (doc.id != request.userId) {
                        batch.update(doc.reference, userRejectPayload)
                        hasBatchWrites = true
                    }
                }
            }
            if (deviceSnap?.exists() == true) {
                batch.update(
                    deviceRef,
                    mapOf(
                        "isPremiumDevice" to false,
                        "subscriptionExpiresAt" to null,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                hasBatchWrites = true
            }
            if (hasBatchWrites) {
                batch.commit().awaitTask()
            }
            Result.success(Unit)
        } catch (e: Exception) {
            val firestoreError = generateSequence<Throwable>(e) { it.cause }
                .filterIsInstance<FirebaseFirestoreException>()
                .firstOrNull()
            if (firestoreError == null && e is CancellationException) throw e
            if (firestoreError?.code == FirebaseFirestoreException.Code.NOT_FOUND) {
                return Result.success(Unit)
            }
            val actualError = firestoreError ?: e
            handleFirestoreError(actualError, OperationType.UPDATE, reqRef.path, auth)
            Result.failure(actualError)
        }
    }

    /**
     * Permanently deletes a single payment transaction from both Firebase RTDB and Firestore
     * (plus its approved_utrs lock if requested). Admin-only operation enforced by Firestore rules.
     */
    suspend fun deletePaymentRequest(
        request: PaymentRequest,
        clearUtrLock: Boolean = true
    ): Result<Unit> {
        try {
            requireUserId()
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val cleanId = request.id.trim()
        require(cleanId.isNotBlank()) { "Payment request ID must not be blank" }

        val utrKey = if (clearUtrLock) normalizeUtrKey(extractCanonicalUtr(request)) else ""
        val reqRef = db.collection(COLLECTION_PAYMENT_REQUESTS).document(cleanId)

        return try {
            RealtimeDatabaseSync.deletePaymentRequest(
                requestId = cleanId,
                utrKey = utrKey,
                context = appContext
            )
            if (runCatching { auth.currentUser }.getOrNull() == null) {
                return Result.success(Unit)
            }
            reqRef.delete().awaitTask()
            if (utrKey.isNotEmpty()) {
                runCatching {
                    db.collection(COLLECTION_APPROVED_UTRS).document(utrKey).delete().awaitTask()
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            val firestoreError = generateSequence<Throwable>(e) { it.cause }
                .filterIsInstance<FirebaseFirestoreException>()
                .firstOrNull()
            if (firestoreError == null && e is CancellationException) throw e
            if (firestoreError?.code == FirebaseFirestoreException.Code.NOT_FOUND) {
                return Result.success(Unit)
            }
            val actualError = firestoreError ?: e
            handleFirestoreError(actualError, OperationType.DELETE, reqRef.path, auth)
            Result.failure(actualError)
        }
    }

    /**
     * Permanently deletes a list of payment transactions from both Firebase RTDB and Firestore
     * in chunked batches (safe for Firestore's 500-write batch limit).
     */
    suspend fun deletePaymentRequests(
        requests: List<PaymentRequest>,
        clearUtrLocks: Boolean = true
    ): Result<Int> {
        try {
            requireUserId()
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val validRequests = requests.filter { it.id.isNotBlank() }.distinctBy { it.id.trim() }
        if (validRequests.isEmpty()) return Result.success(0)

        return try {
            RealtimeDatabaseSync.deletePaymentRequests(
                requests = validRequests,
                clearUtrLocks = clearUtrLocks,
                context = appContext
            )
            if (runCatching { auth.currentUser }.getOrNull() == null) {
                return Result.success(validRequests.size)
            }
            val refsToDelete = mutableListOf<com.google.firebase.firestore.DocumentReference>()
            val seenUtrs = mutableSetOf<String>()
            for (req in validRequests) {
                refsToDelete.add(db.collection(COLLECTION_PAYMENT_REQUESTS).document(req.id.trim()))
                if (clearUtrLocks) {
                    val utrKey = normalizeUtrKey(extractCanonicalUtr(req))
                    if (utrKey.isNotEmpty() && seenUtrs.add(utrKey)) {
                        refsToDelete.add(db.collection(COLLECTION_APPROVED_UTRS).document(utrKey))
                    }
                }
            }
            for (chunk in refsToDelete.chunked(400)) {
                val batch = db.batch()
                chunk.forEach { ref -> batch.delete(ref) }
                batch.commit().awaitTask()
            }
            Result.success(validRequests.size)
        } catch (e: Exception) {
            val firestoreError = generateSequence<Throwable>(e) { it.cause }
                .filterIsInstance<FirebaseFirestoreException>()
                .firstOrNull()
            if (firestoreError == null && e is CancellationException) throw e
            val actualError = firestoreError ?: e
            handleFirestoreError(actualError, OperationType.DELETE, COLLECTION_PAYMENT_REQUESTS, auth)
            Result.failure(actualError)
        }
    }

    companion object {
        const val COLLECTION_PAYMENT_REQUESTS = "payment_requests"
        const val COLLECTION_APPROVED_UTRS = "approved_utrs"

        fun extractCanonicalUtr(request: PaymentRequest): String {
            return request.upiTxnId.trim()
                .ifBlank { request.approvalRefNo.trim() }
                .ifBlank { request.txnRef.trim() }
        }

        fun normalizeUtrKey(raw: String?): String = RealtimeDatabaseSync.normalizeUtrKey(raw)
    }
}
