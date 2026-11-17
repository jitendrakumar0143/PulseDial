package com.example.data.firebase

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.Timestamp
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Real-Time Database (`com.google.firebase:firebase-database`) Synchronization Engine.
 *
 * Connects to `https://gen-lang-client-0353534085-default-rtdb.firebaseio.com` with offline
 * persistence enabled (`setPersistenceEnabled(true)`) and `.keepSynced(true)` on `/users`,
 * `/app_config`, and `/payment_requests`.
 *
 * Provides idiomatic Android equivalents of Firebase Realtime Database operations:
 * - `onValue(path, ...)` -> `DatabaseReference.addValueEventListener`
 * - `set(path, value)` -> `DatabaseReference.setValue`
 * - `update(path, children)` -> `DatabaseReference.updateChildren`
 * - `push(path, value)` -> `DatabaseReference.push().setValue`
 *
 * Strictly uses persistent `ValueEventListener` (`onValue`) with ZERO `.get()` calls for
 * sub-1-second real-time synchronization between the Admin Panel and all user devices.
 */
object RealtimeDatabaseSync {

    private const val TAG = "RealtimeDatabaseSync"
    const val RTDB_URL = "https://gen-lang-client-0353534085-default-rtdb.firebaseio.com"
    const val PROJECT_ID = "gen-lang-client-0353534085"
    const val PROJECT_NUMBER = "113923036774"
    const val STORAGE_BUCKET = "gen-lang-client-0353534085.firebasestorage.app"

    const val NODE_USERS = "users"
    const val NODE_DEVICE_TRIALS = "device_trials"
    const val NODE_APP_CONFIG = "app_config"
    const val NODE_PAYMENT_REQUESTS = "payment_requests"
    const val NODE_NAVIGATION_TABS = "navigation_tabs"
    const val NODE_SETTINGS_ITEMS = "settings_items"
    const val NODE_LIVE_BROADCAST = "live_broadcast"
    const val NODE_CUSTOM_POPUP_AD = "custom_popup_ad"

    private val persistenceConfigured = AtomicBoolean(false)

    @Volatile
    private var databaseInstance: FirebaseDatabase? = null

    /**
     * Initializes Firebase Realtime Database with offline persistence and `.keepSynced(true)`
     * on `/users`, `/app_config`, and `/payment_requests`. Safe to call multiple times or in tests.
     */
    fun init(context: Context): FirebaseDatabase? {
        return runCatching {
            if (FirebaseApp.getApps(context).isEmpty()) {
                return null
            }
            val db = databaseInstance ?: synchronized(this) {
                databaseInstance ?: FirebaseDatabase.getInstance(RTDB_URL).also { instance ->
                    if (persistenceConfigured.compareAndSet(false, true)) {
                        runCatching { instance.setPersistenceEnabled(true) }
                    }
                    runCatching {
                        instance.getReference(NODE_USERS).keepSynced(true)
                        instance.getReference(NODE_APP_CONFIG).keepSynced(true)
                        instance.getReference(NODE_PAYMENT_REQUESTS).keepSynced(true)
                        instance.getReference(NODE_CUSTOM_POPUP_AD).keepSynced(true)
                    }
                    databaseInstance = instance
                }
            }
            db
        }.onFailure { e ->
            Log.w(TAG, "RealtimeDatabaseSync init skipped or deferred: ${e.message}")
        }.getOrNull()
    }

    private fun rootRef(context: Context? = null): DatabaseReference? {
        return runCatching {
            val db = if (context != null) {
                init(context)
            } else {
                databaseInstance
            }
            db?.reference
        }.getOrNull()
    }

    private fun refForPath(path: String, context: Context? = null): DatabaseReference? {
        val cleanPath = path.trim('/')
        val root = rootRef(context) ?: return null
        return if (cleanPath.isEmpty()) root else root.child(cleanPath)
    }

    /**
     * Equivalent to Firebase Realtime Database `onValue(ref, callback)`.
     * Attaches a persistent `ValueEventListener` to [path] and returns an unsubscribe lambda.
     */
    fun onValue(
        path: String,
        context: Context? = null,
        onSnapshot: (DataSnapshot) -> Unit
    ): (() -> Unit)? {
        val ref = refForPath(path, context) ?: return null
        runCatching { ref.keepSynced(true) }
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                onSnapshot(snapshot)
            }

            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "RTDB onValue($path) cancelled: ${error.message}")
            }
        }
        ref.addValueEventListener(listener)
        return {
            runCatching { ref.removeEventListener(listener) }
        }
    }

    /**
     * Equivalent to Firebase Realtime Database `set(ref, value)`.
     * Overwrites data at the specified [path].
     */
    fun set(path: String, value: Any?, context: Context? = null): Boolean {
        return runCatching {
            val ref = refForPath(path, context) ?: return false
            ref.setValue(value)
            true
        }.onFailure { e ->
            Log.d(TAG, "RTDB set($path) skipped: ${e.message}")
        }.getOrDefault(false)
    }

    /**
     * Equivalent to Firebase Realtime Database `update(ref, fields)`.
     * Simultaneously updates specific children at [path] without overwriting other child nodes.
     */
    fun update(path: String, fields: Map<String, Any?>, context: Context? = null): Boolean {
        if (fields.isEmpty()) return false
        return runCatching {
            val ref = refForPath(path, context) ?: return false
            ref.updateChildren(fields)
            true
        }.onFailure { e ->
            Log.d(TAG, "RTDB update($path) skipped: ${e.message}")
        }.getOrDefault(false)
    }

    /**
     * Equivalent to Firebase Realtime Database `push(ref, value)`.
     * Generates a unique push key under [path], writes [value] (injecting `"id"` if a Map),
     * and returns the generated key.
     */
    fun push(path: String, value: Map<String, Any?>, context: Context? = null): String? {
        return runCatching {
            val parentRef = refForPath(path, context) ?: return null
            val pushedRef = parentRef.push()
            val generatedKey = pushedRef.key ?: return null
            val payloadWithKey = value.toMutableMap().apply {
                if (!containsKey("id") || (this["id"] as? String).isNullOrBlank()) {
                    put("id", generatedKey)
                }
            }
            pushedRef.setValue(payloadWithKey)
            generatedKey
        }.onFailure { e ->
            Log.d(TAG, "RTDB push($path) skipped: ${e.message}")
        }.getOrNull()
    }

    /**
     * Attaches a persistent `onValue` listener to `/users/{uid}` for sub-1-second updates.
     */
    fun attachUserListener(
        context: Context,
        uid: String,
        currentFallback: () -> UserEntitlement?,
        onUpdate: (UserEntitlement) -> Unit
    ): (() -> Unit)? {
        return attachUserListenerWithRawMap(context, uid, currentFallback) { parsed, _ ->
            onUpdate(parsed)
        }
    }

    fun attachUserListenerWithRawMap(
        context: Context,
        uid: String,
        currentFallback: () -> UserEntitlement?,
        onUpdate: (UserEntitlement, Map<*, *>) -> Unit
    ): (() -> Unit)? {
        if (uid.isBlank()) return null
        return onValue("$NODE_USERS/$uid", context) { snapshot ->
            if (!snapshot.exists()) return@onValue
            val rawMap = snapshot.value as? Map<*, *> ?: return@onValue
            val parsed = parseUserSnapshot(uid, rawMap, currentFallback())
            onUpdate(parsed, rawMap)
        }
    }

    /**
     * Listens to Firebase RTDB's built-in `.info/serverTimeOffset` so offline/RTDB-only sessions
     * always track accurate server clock offset without relying on stale `user.updatedAt`.
     */
    fun attachServerTimeOffsetListener(
        context: Context,
        onOffsetChanged: (Long) -> Unit
    ): (() -> Unit)? {
        val db = init(context) ?: return null
        val ref = db.getReference(".info/serverTimeOffset")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val offset = asLong(snapshot.value) ?: return
                onOffsetChanged(offset)
            }

            override fun onCancelled(error: DatabaseError) {
                Log.d(TAG, "RTDB .info/serverTimeOffset cancelled: ${error.message}")
            }
        }
        ref.addValueEventListener(listener)
        return {
            runCatching { ref.removeEventListener(listener) }
        }
    }

    /**
     * Attaches dedicated `onValue` (`ValueEventListener`) listeners directly to:
     * - `/users/{uid}/subscriptionStatus`
     * - `/users/{uid}/isPremium`
     * - `/users/{uid}/installDate`
     * - `/users/{uid}/isBlocked`
     * - `/users/{uid}/subscriptionExpiresAtMs`
     * so that any Admin or server change to `subscriptionStatus` or `isPremium` triggers
     * immediate sub-1-second enforcement of the 7-day trial and recording/UI restrictions.
     */
    fun attachSubscriptionNodesListeners(
        context: Context,
        uid: String,
        onSubscriptionStatusChanged: (String) -> Unit,
        onIsPremiumChanged: (Boolean) -> Unit,
        onInstallDateChanged: (Long) -> Unit = {},
        onIsBlockedChanged: (Boolean) -> Unit = {},
        onExpiresAtChanged: (Long?) -> Unit = {}
    ): (() -> Unit)? {
        if (uid.isBlank()) return null
        val cleanups = mutableListOf<() -> Unit>()

        onValue("$NODE_USERS/$uid/subscriptionStatus", context) { snapshot ->
            if (!snapshot.exists()) return@onValue
            val status = (snapshot.value as? String)?.trim().orEmpty()
            if (status.isNotEmpty()) {
                onSubscriptionStatusChanged(status)
            }
        }?.let { cleanups.add(it) }

        onValue("$NODE_USERS/$uid/isPremium", context) { snapshot ->
            if (!snapshot.exists()) return@onValue
            asBool(snapshot.value)?.let(onIsPremiumChanged)
        }?.let { cleanups.add(it) }

        onValue("$NODE_USERS/$uid/installDate", context) { snapshot ->
            if (!snapshot.exists()) return@onValue
            val installMs = asLong(snapshot.value)
            if (installMs != null && installMs > 0L) {
                onInstallDateChanged(installMs)
            }
        }?.let { cleanups.add(it) }

        onValue("$NODE_USERS/$uid/isBlocked", context) { snapshot ->
            if (!snapshot.exists()) return@onValue
            asBool(snapshot.value)?.let(onIsBlockedChanged)
        }?.let { cleanups.add(it) }

        onValue("$NODE_USERS/$uid/subscriptionExpiresAtMs", context) { snapshot ->
            if (!snapshot.exists()) {
                onExpiresAtChanged(null)
                return@onValue
            }
            onExpiresAtChanged(asLong(snapshot.value))
        }?.let { cleanups.add(it) }

        if (cleanups.isEmpty()) return null
        return {
            cleanups.forEach { cleanup -> runCatching { cleanup() } }
        }
    }

    /**
     * Attaches a persistent `onValue` listener to `/app_config` for sub-1-second updates
     * to `subscriptionPrice` (default ₹299), `adminUpiId` (default `elx@ptaxis`), etc.
     */
    fun attachAppConfigListener(
        context: Context,
        currentFallback: () -> AppConfig,
        onUpdate: (AppConfig) -> Unit
    ): (() -> Unit)? {
        return onValue(NODE_APP_CONFIG, context) { snapshot ->
            if (!snapshot.exists()) return@onValue
            val rawMap = snapshot.value as? Map<*, *> ?: return@onValue
            val parsed = parseAppConfigSnapshot(rawMap, currentFallback())
            onUpdate(parsed)
        }
    }

    /**
     * Attaches a persistent `onValue` listener to `/users` for the Real-Time Admin Panel.
     */
    fun attachAllUsersListener(
        context: Context,
        onUpdate: (List<UserEntitlement>) -> Unit
    ): (() -> Unit)? {
        return onValue(NODE_USERS, context) { snapshot ->
            if (!snapshot.exists()) {
                onUpdate(emptyList())
                return@onValue
            }
            val result = mutableListOf<UserEntitlement>()
            for (child in snapshot.children) {
                val uid = child.key.orEmpty()
                val rawMap = child.value as? Map<*, *> ?: continue
                if (uid.isNotBlank()) {
                    result.add(parseUserSnapshot(uid, rawMap, null))
                }
            }
            onUpdate(
                result.sortedByDescending {
                    it.updatedAt?.toDate()?.time ?: it.createdAt?.toDate()?.time ?: 0L
                }
            )
        }
    }

    /**
     * Attaches a persistent `onValue` listener to `/payment_requests` filtered for [uid].
     */
    fun attachUserPaymentRequestsListener(
        context: Context,
        uid: String,
        onUpdate: (List<PaymentRequest>) -> Unit
    ): (() -> Unit)? {
        if (uid.isBlank()) return null
        return onValue(NODE_PAYMENT_REQUESTS, context) { snapshot ->
            if (!snapshot.exists()) {
                onUpdate(emptyList())
                return@onValue
            }
            val result = mutableListOf<PaymentRequest>()
            for (child in snapshot.children) {
                val id = child.key.orEmpty()
                val rawMap = child.value as? Map<*, *> ?: continue
                val parsed = parsePaymentRequestSnapshot(id, rawMap)
                if (parsed.userId == uid) {
                    result.add(parsed)
                }
            }
            onUpdate(result.sortedByDescending { it.createdAt?.toDate()?.time ?: 0L })
        }
    }

    /**
     * Attaches a persistent `onValue` listener to `/payment_requests` for the Real-Time Admin Panel.
     */
    fun attachAllPaymentRequestsListener(
        context: Context,
        onUpdate: (List<PaymentRequest>) -> Unit
    ): (() -> Unit)? {
        return onValue(NODE_PAYMENT_REQUESTS, context) { snapshot ->
            if (!snapshot.exists()) {
                onUpdate(emptyList())
                return@onValue
            }
            val result = mutableListOf<PaymentRequest>()
            for (child in snapshot.children) {
                val id = child.key.orEmpty()
                val rawMap = child.value as? Map<*, *> ?: continue
                if (id.isNotBlank()) {
                    result.add(parsePaymentRequestSnapshot(id, rawMap))
                }
            }
            onUpdate(
                result.sortedWith(
                    compareBy<PaymentRequest> {
                        when (it.status) {
                            PaymentRequest.STATUS_PENDING -> 0
                            PaymentRequest.STATUS_APPROVED -> 1
                            else -> 2
                        }
                    }.thenByDescending { it.createdAt?.toDate()?.time ?: 0L }
                )
            )
        }
    }

    /**
     * Attaches a persistent `onValue` listener to `/app_config/navigation_tabs`.
     */
    fun attachNavigationTabsListener(
        context: Context,
        onUpdate: (List<NavigationTabItem>) -> Unit
    ): (() -> Unit)? {
        return onValue("$NODE_APP_CONFIG/$NODE_NAVIGATION_TABS", context) { snapshot ->
            if (!snapshot.exists()) return@onValue
            val rawMap = snapshot.value as? Map<*, *> ?: return@onValue
            val tabs = parseNavigationTabsSnapshot(rawMap)
            if (tabs.isNotEmpty()) {
                onUpdate(tabs)
            }
        }
    }

    /**
     * Attaches a persistent `onValue` listener to `/app_config/settings_items`.
     */
    fun attachDynamicSettingsListener(
        context: Context,
        onUpdate: (List<DynamicSettingItem>) -> Unit
    ): (() -> Unit)? {
        return onValue("$NODE_APP_CONFIG/$NODE_SETTINGS_ITEMS", context) { snapshot ->
            if (!snapshot.exists()) return@onValue
            val rawMap = snapshot.value as? Map<*, *> ?: return@onValue
            val items = parseDynamicSettingsSnapshot(rawMap)
            if (items.isNotEmpty()) {
                onUpdate(items)
            }
        }
    }

    /**
     * Attaches a persistent `onValue` listener to `/app_config/live_broadcast`.
     */
    fun attachLiveBroadcastListener(
        context: Context,
        onUpdate: (LiveBroadcast) -> Unit
    ): (() -> Unit)? {
        return onValue("$NODE_APP_CONFIG/$NODE_LIVE_BROADCAST", context) { snapshot ->
            if (!snapshot.exists()) return@onValue
            val rawMap = snapshot.value as? Map<*, *> ?: return@onValue
            onUpdate(parseLiveBroadcastSnapshot(rawMap))
        }
    }

    /**
     * Attaches a persistent `addValueEventListener` (`onValue`) to `/custom_popup_ad`
     * for sub-1-second real-time Custom Popup Ad updates across Admin & User devices.
     */
    fun attachCustomPopupAdListener(
        context: Context,
        onUpdate: (CustomPopupAd) -> Unit
    ): (() -> Unit)? {
        return onValue(NODE_CUSTOM_POPUP_AD, context) { snapshot ->
            if (!snapshot.exists()) {
                onUpdate(CustomPopupAd())
                return@onValue
            }
            val rawMap = snapshot.value as? Map<*, *> ?: return@onValue
            onUpdate(parseCustomPopupAdSnapshot(rawMap))
        }
    }

    /**
     * Publishes or updates editable `/custom_popup_ad` fields via `updateChildren`
     * while preserving concurrent `ServerValue.increment(1)` analytics counters.
     */
    fun publishCustomPopupAd(
        ad: CustomPopupAd,
        context: Context? = null
    ): Boolean {
        return update(NODE_CUSTOM_POPUP_AD, toRtdbCustomPopupAdPublishMap(ad), context)
    }

    /**
     * Atomically increments `/custom_popup_ad/impressionsCount` using `ServerValue.increment(1)`.
     */
    fun incrementCustomPopupAdImpression(context: Context? = null): Boolean {
        return update(
            NODE_CUSTOM_POPUP_AD,
            mapOf("impressionsCount" to ServerValue.increment(1L)),
            context
        )
    }

    /**
     * Atomically increments `/custom_popup_ad/clicksCount` using `ServerValue.increment(1)`.
     */
    fun incrementCustomPopupAdClick(context: Context? = null): Boolean {
        return update(
            NODE_CUSTOM_POPUP_AD,
            mapOf("clicksCount" to ServerValue.increment(1L)),
            context
        )
    }

    /**
     * Builds the Realtime Database registration map for `/users/{userId}` on login.
     * Uses `ServerValue.TIMESTAMP` for `installDate` on first registration on this device
     * to enforce "7-Day Free Trial • One Per Device".
     */
    fun buildInitialUserRegistrationPayload(
        userId: String,
        email: String,
        phone: String,
        displayName: String,
        deviceId: String,
        deviceHash: String,
        isFirstRegistrationOnDevice: Boolean
    ): Map<String, Any?> {
        val base = mutableMapOf<String, Any?>(
            "uid" to userId,
            "userId" to userId,
            "email" to email,
            "phone" to phone,
            "phoneNumber" to phone,
            "displayName" to displayName,
            "deviceId" to deviceId,
            "deviceHash" to deviceHash,
            "updatedAtMs" to ServerValue.TIMESTAMP
        )
        if (isFirstRegistrationOnDevice) {
            base["installDate"] = ServerValue.TIMESTAMP
            base["subscriptionStatus"] = UserEntitlement.STATUS_TRIAL
            base["isPremium"] = false
            base["isPremiumUser"] = false
            base["isBlocked"] = false
        }
        return base
    }

    /**
     * Safely creates or updates `/users/{userId}` and `/device_trials/{deviceHash}` in Firebase
     * Realtime Database without ever resetting an existing user's `installDate`, `isPremium`,
     * `subscriptionStatus`, or `isBlocked` on reinstall or re-login.
     */
    fun registerOrUpdateUserOnLogin(
        context: Context,
        userId: String,
        email: String,
        phone: String,
        displayName: String,
        deviceId: String,
        deviceHash: String,
        isFirstRegistrationOnDevice: Boolean
    ): Map<String, Any?> {
        val cleanUid = userId.trim()
        val cleanEmail = email.trim()
        val cleanPhone = phone.trim()
        val cleanName = displayName.trim().ifBlank { "PulseDial User" }
        val cleanDeviceId = deviceId.trim()
        val cleanDeviceHash = deviceHash.trim()

        val payload = buildInitialUserRegistrationPayload(
            userId = cleanUid,
            email = cleanEmail,
            phone = cleanPhone,
            displayName = cleanName,
            deviceId = cleanDeviceId,
            deviceHash = cleanDeviceHash,
            isFirstRegistrationOnDevice = isFirstRegistrationOnDevice
        )
        if (cleanUid.isEmpty()) return payload

        val identityUpdate = mutableMapOf<String, Any?>(
            "uid" to cleanUid,
            "userId" to cleanUid,
            "displayName" to cleanName,
            "deviceId" to cleanDeviceId,
            "deviceHash" to cleanDeviceHash,
            "updatedAtMs" to ServerValue.TIMESTAMP
        ).apply {
            if (cleanEmail.isNotEmpty()) {
                put("email", cleanEmail)
            }
            if (cleanPhone.isNotEmpty()) {
                put("phone", cleanPhone)
                put("phoneNumber", cleanPhone)
            }
        }

        val root = rootRef(context) ?: return payload
        val userRef = root.child(NODE_USERS).child(cleanUid)
        val deviceRef = if (cleanDeviceHash.isNotEmpty()) {
            root.child(NODE_DEVICE_TRIALS).child(cleanDeviceHash)
        } else {
            null
        }

        val syncUserNode: (Long?) -> Unit = { deviceTrialStartMs ->
            runCatching {
                userRef.addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        val updateMap = identityUpdate.toMutableMap()
                        if (!snapshot.exists() || !snapshot.hasChild("installDate")) {
                            updateMap["installDate"] = deviceTrialStartMs ?: ServerValue.TIMESTAMP
                        }
                        if (!snapshot.exists() || !snapshot.hasChild("subscriptionStatus")) {
                            updateMap["subscriptionStatus"] = UserEntitlement.STATUS_TRIAL
                        }
                        if (!snapshot.exists() || !snapshot.hasChild("isPremium")) {
                            updateMap["isPremium"] = false
                            updateMap["isPremiumUser"] = false
                        }
                        if (!snapshot.exists() || !snapshot.hasChild("isBlocked")) {
                            updateMap["isBlocked"] = false
                        }
                        userRef.updateChildren(updateMap)
                    }

                    override fun onCancelled(error: DatabaseError) {
                        userRef.updateChildren(identityUpdate)
                    }
                })
            }.onFailure {
                update("$NODE_USERS/$cleanUid", identityUpdate, context)
            }
        }

        if (deviceRef != null) {
            runCatching {
                deviceRef.addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        val existingStartMs = asLong(snapshot.child("trialStartedAtMs").value)
                            ?.takeIf { it > 0L }
                        val ownerUid = (snapshot.child("ownerUid").value as? String).orEmpty()
                        val lastUid = (snapshot.child("lastUserId").value as? String).orEmpty()
                        val prevSwitches = asLong(snapshot.child("accountSwitchCount").value) ?: 0L

                        val deviceUpdate = mutableMapOf<String, Any?>(
                            "deviceHash" to cleanDeviceHash,
                            "lastUserId" to cleanUid,
                            "lastVerifiedAtMs" to ServerValue.TIMESTAMP,
                            "updatedAtMs" to ServerValue.TIMESTAMP
                        )
                        if (!snapshot.exists() || existingStartMs == null) {
                            deviceUpdate["trialStartedAtMs"] = ServerValue.TIMESTAMP
                        }
                        if (!snapshot.exists() || ownerUid.isBlank()) {
                            deviceUpdate["ownerUid"] = cleanUid
                            deviceUpdate["accountSwitchCount"] = 0L
                            deviceUpdate["isPremiumDevice"] = false
                        } else if (ownerUid != cleanUid && lastUid != cleanUid) {
                            deviceUpdate["accountSwitchCount"] = prevSwitches + 1L
                        }
                        deviceRef.updateChildren(deviceUpdate)
                        syncUserNode(existingStartMs)
                    }

                    override fun onCancelled(error: DatabaseError) {
                        syncUserNode(null)
                    }
                })
            }.onFailure {
                syncUserNode(null)
            }
        } else {
            syncUserNode(null)
        }

        return payload
    }

    /**
     * Writes a full `UserEntitlement` state to `/users/{uid}` using `set` or `update`.
     */
    fun pushUserEntitlement(
        entitlement: UserEntitlement,
        context: Context? = null,
        overwriteWithSet: Boolean = false
    ) {
        val uid = entitlement.userId.ifBlank { entitlement.id }
        if (uid.isBlank()) return
        val map = toRtdbUserMap(entitlement)
        if (overwriteWithSet) {
            set("$NODE_USERS/$uid", map, context)
        } else {
            update("$NODE_USERS/$uid", map, context)
        }
    }

    /**
     * Updates partial user fields under `/users/{uid}` using `update`.
     */
    fun pushUserFields(uid: String, fields: Map<String, Any?>, context: Context? = null) {
        if (uid.isBlank() || fields.isEmpty()) return
        update("$NODE_USERS/$uid", fields, context)
    }

    /**
     * Updates global `AppConfig` under `/app_config` using `update`.
     */
    fun pushAppConfig(config: AppConfig, context: Context? = null) {
        update(NODE_APP_CONFIG, toRtdbConfigMap(config), context)
    }

    /**
     * Creates a new payment request in `/payment_requests` using `push` (or `set` if ID already exists).
     */
    fun pushPaymentRequest(request: PaymentRequest, context: Context? = null): String? {
        val map = toRtdbPaymentRequestMap(request)
        return if (request.id.isNotBlank()) {
            set("$NODE_PAYMENT_REQUESTS/${request.id}", map, context)
            request.id
        } else {
            push(NODE_PAYMENT_REQUESTS, map, context)
        }
    }

    /**
     * Updates an existing payment request status in `/payment_requests/{requestId}` using `update`.
     */
    fun updatePaymentRequestStatus(
        requestId: String,
        status: String,
        reviewedByUid: String,
        reviewNote: String,
        context: Context? = null
    ) {
        if (requestId.isBlank()) return
        update(
            "$NODE_PAYMENT_REQUESTS/$requestId",
            mapOf(
                "status" to status,
                "reviewedByUid" to reviewedByUid,
                "reviewNote" to reviewNote,
                "updatedAtMs" to System.currentTimeMillis()
            ),
            context
        )
    }

    /**
     * Saves navigation tabs to `/app_config/navigation_tabs` using `set`.
     */
    fun setNavigationTabs(tabs: List<NavigationTabItem>, updatedByUid: String = "", context: Context? = null) {
        val payload = mapOf(
            "tabs" to tabs.map { it.toFirestoreMap() },
            "updatedByUid" to updatedByUid,
            "updatedAtMs" to System.currentTimeMillis()
        )
        set("$NODE_APP_CONFIG/$NODE_NAVIGATION_TABS", payload, context)
    }

    /**
     * Saves dynamic settings items to `/app_config/settings_items` using `set`.
     */
    fun setDynamicSettings(items: List<DynamicSettingItem>, updatedByUid: String = "", context: Context? = null) {
        val payload = mapOf(
            "items" to items.map { it.toFirestoreMap() },
            "updatedByUid" to updatedByUid,
            "updatedAtMs" to System.currentTimeMillis()
        )
        set("$NODE_APP_CONFIG/$NODE_SETTINGS_ITEMS", payload, context)
    }

    /**
     * Saves live broadcast config to `/app_config/live_broadcast` using `set`.
     */
    fun setLiveBroadcast(broadcast: LiveBroadcast, updatedByUid: String = "", context: Context? = null) {
        val payload = mapOf(
            "showPopup" to broadcast.showPopup,
            "popupId" to broadcast.popupId,
            "title" to broadcast.title,
            "message" to broadcast.message,
            "imageUrl" to broadcast.imageUrl,
            "buttonText" to broadcast.buttonText,
            "actionUrl" to broadcast.actionUrl,
            "isCancelable" to broadcast.isCancelable,
            "showBannerAd" to broadcast.showBannerAd,
            "bannerText" to broadcast.bannerText,
            "bannerImageUrl" to broadcast.bannerImageUrl,
            "bannerActionUrl" to broadcast.bannerActionUrl,
            "updatedByUid" to updatedByUid,
            "updatedAtMs" to System.currentTimeMillis()
        )
        set("$NODE_APP_CONFIG/$NODE_LIVE_BROADCAST", payload, context)
    }

    /**
     * Deterministic conversion from `UserEntitlement` to the `/users/{uid}` RTDB map.
     */
    fun toRtdbUserMap(entitlement: UserEntitlement): Map<String, Any?> {
        val uid = entitlement.userId.ifBlank { entitlement.id }
        val nowMs = System.currentTimeMillis()
        val installMs = entitlement.trialStartedAt?.toDate()?.time
            ?: entitlement.createdAt?.toDate()?.time
            ?: nowMs
        val expiresMs = entitlement.subscriptionExpiresAt?.toDate()?.time
        return mapOf(
            "uid" to uid,
            "userId" to uid,
            "deviceHash" to entitlement.deviceHash,
            "email" to entitlement.email,
            "displayName" to entitlement.displayName,
            "phone" to entitlement.phoneNumber,
            "phoneNumber" to entitlement.phoneNumber,
            "installDate" to installMs,
            "subscriptionStatus" to entitlement.subscriptionStatus,
            "isPremium" to entitlement.isPremiumUser,
            "isPremiumUser" to entitlement.isPremiumUser,
            "paymentRefId" to entitlement.paymentRefId,
            "isBlocked" to entitlement.isBlocked,
            "autoRecordEnabled" to entitlement.autoCallRecording,
            "autoCallRecording" to entitlement.autoCallRecording,
            "themeMode" to entitlement.themeMode,
            "dialpadTonesEnabled" to entitlement.dialpadTonesEnabled,
            "blockedNumbers" to entitlement.blockedNumbers.take(500),
            "quickDeclineResponses" to entitlement.quickDeclineResponses.take(50),
            "bonusTrialDays" to entitlement.bonusTrialDays,
            "subscriptionExpiresAtMs" to expiresMs,
            "adminNote" to entitlement.adminNote,
            "hideSubscriptionUI" to entitlement.hideSubscriptionUI,
            "announcerEnabled" to entitlement.announcerEnabled,
            "announcerHeadsetOnly" to entitlement.announcerHeadsetOnly,
            "unknownShieldMode" to entitlement.unknownShieldMode,
            "autoSmsFollowUp" to entitlement.autoSmsFollowUp,
            "autoWhatsappFollowUp" to entitlement.autoWhatsappFollowUp,
            "followUpTrigger" to entitlement.followUpTrigger,
            "completedCallTemplate" to entitlement.completedCallTemplate,
            "missedCallTemplate" to entitlement.missedCallTemplate,
            "recordWhatsappCalls" to entitlement.recordWhatsappCalls,
            "recordWhatsappBusinessCalls" to entitlement.recordWhatsappBusinessCalls,
            "recordMeetCalls" to entitlement.recordMeetCalls,
            "recordTelegramCalls" to entitlement.recordTelegramCalls,
            "updatedAtMs" to (entitlement.updatedAt?.toDate()?.time ?: nowMs)
        )
    }

    /**
     * Deterministic conversion from a `/users/{uid}` RTDB snapshot map to `UserEntitlement`.
     */
    fun parseUserSnapshot(
        uid: String,
        map: Map<*, *>,
        fallback: UserEntitlement? = null
    ): UserEntitlement {
        val base = fallback ?: UserEntitlement(id = uid, userId = uid)
        val phoneVal = (map["phone"] as? String)
            ?.ifBlank { map["phoneNumber"] as? String ?: base.phoneNumber }
            ?: (map["phoneNumber"] as? String)
            ?: base.phoneNumber
        val isPremiumVal = asBool(map["isPremium"])
            ?: asBool(map["isPremiumUser"])
            ?: base.isPremiumUser
        val autoRecordVal = asBool(map["autoRecordEnabled"])
            ?: asBool(map["autoCallRecording"])
            ?: base.autoCallRecording
        val installDateMs = asLong(map["installDate"])
            ?: asLong(map["trialStartedAtMs"])
        val trialStartedTs = if (installDateMs != null && installDateMs > 0L) {
            Timestamp(Date(installDateMs))
        } else {
            base.trialStartedAt
        }
        val expiresMs = asLong(map["subscriptionExpiresAtMs"])
            ?: asLong(map["subscriptionExpiresAt"])
        val subscriptionExpiresTs = when {
            map.containsKey("subscriptionExpiresAtMs") && map["subscriptionExpiresAtMs"] == null -> null
            expiresMs != null && expiresMs > 0L -> Timestamp(Date(expiresMs))
            else -> base.subscriptionExpiresAt
        }
        val updatedAtMs = asLong(map["updatedAtMs"])
            ?: asLong(map["updatedAt"])
        val updatedAtTs = if (updatedAtMs != null && updatedAtMs > 0L) {
            Timestamp(Date(updatedAtMs))
        } else {
            base.updatedAt ?: Timestamp(Date(System.currentTimeMillis()))
        }

        val resolvedUid = (map["uid"] as? String)?.ifBlank { uid }
            ?: (map["userId"] as? String)?.ifBlank { uid }
            ?: uid

        val shieldVal = when ((map["unknownShieldMode"] as? String)?.trim()?.uppercase()) {
            UserEntitlement.SHIELD_SILENT_RECORD -> UserEntitlement.SHIELD_SILENT_RECORD
            UserEntitlement.SHIELD_AUTO_REJECT -> UserEntitlement.SHIELD_AUTO_REJECT
            UserEntitlement.SHIELD_OFF -> UserEntitlement.SHIELD_OFF
            else -> base.unknownShieldMode
        }
        val triggerVal = when ((map["followUpTrigger"] as? String)?.trim()?.uppercase()) {
            UserEntitlement.TRIGGER_MISSED_ONLY -> UserEntitlement.TRIGGER_MISSED_ONLY
            UserEntitlement.TRIGGER_COMPLETED_ONLY -> UserEntitlement.TRIGGER_COMPLETED_ONLY
            UserEntitlement.TRIGGER_ALL_CALLS -> UserEntitlement.TRIGGER_ALL_CALLS
            else -> base.followUpTrigger
        }
        val parsedBlockedNumbers = if (map.containsKey("blockedNumbers")) {
            asStringList(map["blockedNumbers"]).orEmpty()
        } else {
            base.blockedNumbers
        }
        val parsedQuickDeclines = if (map.containsKey("quickDeclineResponses")) {
            asStringList(map["quickDeclineResponses"]).orEmpty()
        } else {
            base.quickDeclineResponses
        }

        return base.copy(
            id = resolvedUid,
            userId = resolvedUid,
            deviceHash = (map["deviceHash"] as? String)?.ifBlank { base.deviceHash } ?: base.deviceHash,
            email = (map["email"] as? String) ?: base.email,
            displayName = (map["displayName"] as? String) ?: base.displayName,
            phoneNumber = phoneVal,
            trialStartedAt = trialStartedTs,
            isPremiumUser = isPremiumVal,
            isBlocked = asBool(map["isBlocked"]) ?: base.isBlocked,
            subscriptionStatus = (map["subscriptionStatus"] as? String)?.trim()?.lowercase()?.ifBlank { null }
                ?: base.subscriptionStatus,
            paymentRefId = (map["paymentRefId"] as? String) ?: base.paymentRefId,
            autoCallRecording = autoRecordVal,
            themeMode = (map["themeMode"] as? String) ?: base.themeMode,
            dialpadTonesEnabled = asBool(map["dialpadTonesEnabled"]) ?: base.dialpadTonesEnabled,
            blockedNumbers = parsedBlockedNumbers,
            quickDeclineResponses = parsedQuickDeclines,
            bonusTrialDays = asLong(map["bonusTrialDays"]) ?: base.bonusTrialDays,
            subscriptionExpiresAt = subscriptionExpiresTs,
            adminNote = (map["adminNote"] as? String) ?: base.adminNote,
            hideSubscriptionUI = asBool(map["hideSubscriptionUI"]) ?: base.hideSubscriptionUI,
            announcerEnabled = asBool(map["announcerEnabled"]) ?: base.announcerEnabled,
            announcerHeadsetOnly = asBool(map["announcerHeadsetOnly"]) ?: base.announcerHeadsetOnly,
            unknownShieldMode = shieldVal,
            autoSmsFollowUp = asBool(map["autoSmsFollowUp"]) ?: base.autoSmsFollowUp,
            autoWhatsappFollowUp = asBool(map["autoWhatsappFollowUp"]) ?: base.autoWhatsappFollowUp,
            followUpTrigger = triggerVal,
            completedCallTemplate = (map["completedCallTemplate"] as? String)
                ?.takeIf { it.isNotBlank() } ?: base.completedCallTemplate,
            missedCallTemplate = (map["missedCallTemplate"] as? String)
                ?.takeIf { it.isNotBlank() } ?: base.missedCallTemplate,
            recordWhatsappCalls = asBool(map["recordWhatsappCalls"]) ?: base.recordWhatsappCalls,
            recordWhatsappBusinessCalls = asBool(map["recordWhatsappBusinessCalls"])
                ?: base.recordWhatsappBusinessCalls,
            recordMeetCalls = asBool(map["recordMeetCalls"]) ?: base.recordMeetCalls,
            recordTelegramCalls = asBool(map["recordTelegramCalls"]) ?: base.recordTelegramCalls,
            updatedAt = updatedAtTs
        )
    }

    private fun asBool(raw: Any?): Boolean? = when (raw) {
        is Boolean -> raw
        is String -> when (raw.trim().lowercase()) {
            "true", "1", "yes" -> true
            "false", "0", "no" -> false
            else -> null
        }
        is Number -> raw.toInt() != 0
        else -> null
    }

    private fun asLong(raw: Any?): Long? = when (raw) {
        is Number -> raw.toLong()
        is String -> raw.trim().toLongOrNull()
        else -> null
    }

    private fun asStringList(raw: Any?): List<String>? = when (raw) {
        is List<*> -> raw.mapNotNull { (it as? String)?.trim() }.filter { it.isNotEmpty() }
        is Map<*, *> -> raw.values.mapNotNull { (it as? String)?.trim() }.filter { it.isNotEmpty() }
        else -> null
    }

    /**
     * Deterministic conversion from `AppConfig` to `/app_config` RTDB map.
     */
    fun toRtdbConfigMap(config: AppConfig): Map<String, Any?> {
        return mapOf(
            "subscriptionPrice" to config.subscriptionPriceInr,
            "subscriptionPriceInr" to config.subscriptionPriceInr,
            "adminUpiId" to config.upiVpa,
            "upiVpa" to config.upiVpa,
            "trialDays" to config.trialDays,
            "whatsappNumber" to config.whatsappNumber,
            "enforceSingleDeviceTrial" to config.enforceSingleDeviceTrial,
            "allowCallRecording" to config.allowCallRecording,
            "allowManualUtr" to config.allowManualUtr,
            "maintenanceMode" to config.maintenanceMode,
            "maintenanceMessage" to config.maintenanceMessage,
            "announcementText" to config.announcementText,
            "dialpadTonesDefault" to config.dialpadTonesDefault,
            "defaultAutoRecording" to config.defaultAutoRecording,
            "forceThemeMode" to config.forceThemeMode,
            "hideSubscriptionUI" to config.hideSubscriptionUI,
            "updatedAtMs" to System.currentTimeMillis()
        )
    }

    /**
     * Deterministic conversion from `/app_config` RTDB snapshot map to `AppConfig`.
     */
    fun parseAppConfigSnapshot(
        map: Map<*, *>,
        fallback: AppConfig = AppConfig(id = AppConfig.DOC_GLOBAL_SETTINGS)
    ): AppConfig {
        val price = asLong(map["subscriptionPrice"])
            ?: asLong(map["subscriptionPriceInr"])
            ?: fallback.subscriptionPriceInr
        val upi = (map["adminUpiId"] as? String)?.ifBlank { null }
            ?: (map["upiVpa"] as? String)?.ifBlank { null }
            ?: fallback.upiVpa
        return fallback.copy(
            subscriptionPriceInr = price.coerceAtLeast(1L),
            upiVpa = upi,
            trialDays = asLong(map["trialDays"]) ?: fallback.trialDays,
            whatsappNumber = (map["whatsappNumber"] as? String)?.ifBlank { fallback.whatsappNumber }
                ?: fallback.whatsappNumber,
            enforceSingleDeviceTrial = asBool(map["enforceSingleDeviceTrial"])
                ?: fallback.enforceSingleDeviceTrial,
            allowCallRecording = asBool(map["allowCallRecording"])
                ?: fallback.allowCallRecording,
            allowManualUtr = asBool(map["allowManualUtr"])
                ?: fallback.allowManualUtr,
            maintenanceMode = asBool(map["maintenanceMode"])
                ?: fallback.maintenanceMode,
            maintenanceMessage = (map["maintenanceMessage"] as? String)
                ?: fallback.maintenanceMessage,
            announcementText = (map["announcementText"] as? String)
                ?: fallback.announcementText,
            dialpadTonesDefault = asBool(map["dialpadTonesDefault"])
                ?: fallback.dialpadTonesDefault,
            defaultAutoRecording = asBool(map["defaultAutoRecording"])
                ?: fallback.defaultAutoRecording,
            forceThemeMode = (map["forceThemeMode"] as? String)
                ?: fallback.forceThemeMode,
            hideSubscriptionUI = asBool(map["hideSubscriptionUI"])
                ?: fallback.hideSubscriptionUI
        )
    }

    /**
     * Deterministic conversion from `PaymentRequest` to `/payment_requests/{id}` RTDB map.
     */
    fun toRtdbPaymentRequestMap(request: PaymentRequest): Map<String, Any?> {
        val nowMs = System.currentTimeMillis()
        val createdMs = request.createdAt?.toDate()?.time ?: nowMs
        val updatedMs = request.updatedAt?.toDate()?.time ?: nowMs
        return mapOf(
            "id" to request.id,
            "userId" to request.userId,
            "userName" to request.userName,
            "userEmail" to request.userEmail,
            "deviceHash" to request.deviceHash,
            "shortDeviceTag" to request.shortDeviceTag,
            "upiId" to request.upiId,
            "amountInr" to request.amountInr,
            "txnRef" to request.txnRef,
            "upiTxnId" to request.upiTxnId,
            "approvalRefNo" to request.approvalRefNo,
            "upiResponseStatus" to request.upiResponseStatus,
            "status" to request.status,
            "reviewedByUid" to request.reviewedByUid,
            "reviewNote" to request.reviewNote,
            "createdAtMs" to createdMs,
            "updatedAtMs" to updatedMs
        )
    }

    /**
     * Deterministic conversion from `/payment_requests/{id}` RTDB snapshot map to `PaymentRequest`.
     */
    fun parsePaymentRequestSnapshot(
        id: String,
        map: Map<*, *>
    ): PaymentRequest {
        val resolvedId = (map["id"] as? String)?.ifBlank { id } ?: id
        val createdMs = (map["createdAtMs"] as? Number)?.toLong()
            ?: (map["createdAt"] as? Number)?.toLong()
            ?: System.currentTimeMillis()
        val updatedMs = (map["updatedAtMs"] as? Number)?.toLong()
            ?: (map["updatedAt"] as? Number)?.toLong()
            ?: createdMs
        return PaymentRequest(
            id = resolvedId,
            userId = (map["userId"] as? String).orEmpty(),
            userName = (map["userName"] as? String).orEmpty(),
            userEmail = (map["userEmail"] as? String).orEmpty(),
            deviceHash = (map["deviceHash"] as? String).orEmpty(),
            shortDeviceTag = (map["shortDeviceTag"] as? String).orEmpty(),
            upiId = (map["upiId"] as? String).orEmpty(),
            amountInr = (map["amountInr"] as? Number)?.toLong() ?: 299L,
            txnRef = (map["txnRef"] as? String).orEmpty(),
            upiTxnId = (map["upiTxnId"] as? String).orEmpty(),
            approvalRefNo = (map["approvalRefNo"] as? String).orEmpty(),
            upiResponseStatus = (map["upiResponseStatus"] as? String) ?: "SUBMITTED",
            status = (map["status"] as? String) ?: PaymentRequest.STATUS_PENDING,
            reviewedByUid = (map["reviewedByUid"] as? String).orEmpty(),
            reviewNote = (map["reviewNote"] as? String).orEmpty(),
            createdAt = Timestamp(Date(createdMs)),
            updatedAt = Timestamp(Date(updatedMs))
        )
    }

    /**
     * Parses `/app_config/navigation_tabs` snapshot map into sorted `List<NavigationTabItem>`.
     */
    fun parseNavigationTabsSnapshot(map: Map<*, *>): List<NavigationTabItem> {
        val rawList = map["tabs"] as? List<*> ?: return emptyList()
        return rawList.mapIndexedNotNull { idx, entry ->
            val itemMap = entry as? Map<*, *> ?: return@mapIndexedNotNull null
            NavigationTabItem(
                id = (itemMap["id"] as? String).orEmpty().ifBlank { "tab_$idx" },
                title = (itemMap["title"] as? String).orEmpty(),
                iconKey = (itemMap["iconKey"] as? String) ?: NavigationTabItem.ICON_HISTORY,
                enabled = (itemMap["enabled"] as? Boolean) ?: true,
                order = (itemMap["order"] as? Number)?.toInt() ?: idx,
                type = (itemMap["type"] as? String) ?: NavigationTabItem.TYPE_CUSTOM_BANNER,
                customTitle = (itemMap["customTitle"] as? String).orEmpty(),
                customBody = (itemMap["customBody"] as? String).orEmpty(),
                customActionUrl = (itemMap["customActionUrl"] as? String).orEmpty()
            )
        }.sortedBy { it.order }
    }

    /**
     * Parses `/app_config/settings_items` snapshot map into sorted `List<DynamicSettingItem>`.
     */
    fun parseDynamicSettingsSnapshot(map: Map<*, *>): List<DynamicSettingItem> {
        val rawList = map["items"] as? List<*> ?: return emptyList()
        return rawList.mapIndexedNotNull { idx, entry ->
            val itemMap = entry as? Map<*, *> ?: return@mapIndexedNotNull null
            DynamicSettingItem(
                key = (itemMap["key"] as? String).orEmpty().ifBlank { "custom_$idx" },
                title = (itemMap["title"] as? String).orEmpty(),
                description = (itemMap["description"] as? String).orEmpty(),
                iconKey = (itemMap["iconKey"] as? String) ?: DynamicSettingItem.ICON_TOGGLE,
                type = (itemMap["type"] as? String) ?: DynamicSettingItem.TYPE_TOGGLE,
                enabled = (itemMap["enabled"] as? Boolean) ?: true,
                lockedByAdmin = (itemMap["lockedByAdmin"] as? Boolean) ?: false,
                defaultValue = (itemMap["defaultValue"] as? Boolean) ?: false,
                order = (itemMap["order"] as? Number)?.toInt() ?: idx,
                actionUrl = (itemMap["actionUrl"] as? String).orEmpty()
            )
        }.sortedBy { it.order }
    }

    /**
     * Parses `/app_config/live_broadcast` snapshot map into `LiveBroadcast`.
     */
    fun parseLiveBroadcastSnapshot(map: Map<*, *>): LiveBroadcast {
        return LiveBroadcast(
            id = LiveBroadcast.DOC_LIVE_BROADCAST,
            showPopup = (map["showPopup"] as? Boolean) ?: false,
            popupId = (map["popupId"] as? String).orEmpty(),
            title = (map["title"] as? String).orEmpty(),
            message = (map["message"] as? String).orEmpty(),
            imageUrl = (map["imageUrl"] as? String).orEmpty(),
            buttonText = (map["buttonText"] as? String)?.ifBlank { "Open" } ?: "Open",
            actionUrl = (map["actionUrl"] as? String).orEmpty(),
            isCancelable = (map["isCancelable"] as? Boolean) ?: true,
            showBannerAd = (map["showBannerAd"] as? Boolean) ?: false,
            bannerText = (map["bannerText"] as? String).orEmpty(),
            bannerImageUrl = (map["bannerImageUrl"] as? String).orEmpty(),
            bannerActionUrl = (map["bannerActionUrl"] as? String).orEmpty(),
            updatedByUid = (map["updatedByUid"] as? String).orEmpty()
        )
    }

    /**
     * Deterministic conversion from `CustomPopupAd` to `/custom_popup_ad` RTDB update map.
     * Omits `impressionsCount`/`clicksCount` so atomic `ServerValue.increment(1)` counts remain intact.
     */
    fun toRtdbCustomPopupAdPublishMap(ad: CustomPopupAd): Map<String, Any?> {
        val resolvedId = ad.adId.trim().ifBlank { "ad_${System.currentTimeMillis()}" }
        return mapOf(
            "adId" to resolvedId,
            "isEnabled" to ad.isEnabled,
            "showToPremiumUsers" to ad.showToPremiumUsers,
            "triggerMode" to CustomPopupAd.normalizedTriggerMode(ad.triggerMode),
            "imageUrl" to ad.imageUrl.trim(),
            "title" to ad.title.trim(),
            "message" to ad.message.trim(),
            "buttonText" to ad.buttonText.trim().ifBlank { CustomPopupAd.DEFAULT_BUTTON_TEXT },
            "targetUrl" to ad.targetUrl.trim(),
            "countdownSeconds" to ad.countdownSeconds.coerceIn(0, CustomPopupAd.MAX_COUNTDOWN_SECONDS),
            "updatedAtMs" to ServerValue.TIMESTAMP
        )
    }

    /**
     * Deterministic conversion from `/custom_popup_ad` RTDB snapshot map to `CustomPopupAd`.
     */
    fun parseCustomPopupAdSnapshot(map: Map<*, *>): CustomPopupAd {
        val countdown = (asLong(map["countdownSeconds"])
            ?: CustomPopupAd.DEFAULT_COUNTDOWN_SECONDS.toLong())
            .toInt()
            .coerceIn(0, CustomPopupAd.MAX_COUNTDOWN_SECONDS)
        return CustomPopupAd(
            adId = (map["adId"] as? String).orEmpty(),
            isEnabled = asBool(map["isEnabled"]) ?: false,
            showToPremiumUsers = asBool(map["showToPremiumUsers"]) ?: true,
            triggerMode = CustomPopupAd.normalizedTriggerMode(map["triggerMode"] as? String),
            imageUrl = (map["imageUrl"] as? String).orEmpty(),
            title = (map["title"] as? String).orEmpty(),
            message = (map["message"] as? String).orEmpty(),
            buttonText = (map["buttonText"] as? String)
                ?.ifBlank { CustomPopupAd.DEFAULT_BUTTON_TEXT }
                ?: CustomPopupAd.DEFAULT_BUTTON_TEXT,
            targetUrl = (map["targetUrl"] as? String).orEmpty(),
            countdownSeconds = countdown,
            impressionsCount = (asLong(map["impressionsCount"]) ?: 0L).coerceAtLeast(0L),
            clicksCount = (asLong(map["clicksCount"]) ?: 0L).coerceAtLeast(0L)
        )
    }
}
