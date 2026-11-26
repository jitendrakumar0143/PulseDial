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
import com.example.telecom.PhoneGeoDatabase
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean

data class CallerDirectoryProfile(
    val phoneKey: String,
    val displayName: String,
    val city: String = "",
    val state: String = "",
    val postalCode: String = "",
    val country: String = "",
    val countryCode: String = "",
    val socialPlatforms: List<String> = emptyList(),
    val sourceLabel: String = "PulseDial Cloud Directory"
)

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
    const val NODE_APPROVED_UTRS = "approved_utrs"
    const val NODE_CALLER_DIRECTORY = "caller_directory"
    const val NODE_WELCOME_SCREEN = "welcome_screen"

    private val persistenceConfigured = AtomicBoolean(false)

    @Volatile
    private var databaseInstance: FirebaseDatabase? = null

    @Volatile
    private var serverTimeOffsetMs: Long = 0L

    @Volatile
    private var hasServerTimeOffset: Boolean = false

    fun currentServerNowMs(fallbackOffsetMs: Long = 0L): Long {
        val offset = if (hasServerTimeOffset) serverTimeOffsetMs else fallbackOffsetMs
        return System.currentTimeMillis() + offset
    }

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
                        instance.getReference(NODE_WELCOME_SCREEN).keepSynced(true)
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
     * Returns a persistent, keep-synced [DatabaseReference] pointing directly to `/users`
     * so `AdminPanelActivity` can attach a robust `addValueEventListener` with full error handling.
     */
    fun getUsersReference(context: Context): DatabaseReference? {
        val ref = refForPath(NODE_USERS, context) ?: return null
        runCatching { ref.keepSynced(true) }
        return ref
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
        onUserDeleted: (() -> Unit)? = null,
        onUpdate: (UserEntitlement, Map<*, *>) -> Unit
    ): (() -> Unit)? {
        if (uid.isBlank()) return null
        var hasSeenExistingNode = false
        return onValue("$NODE_USERS/$uid", context) { snapshot ->
            if (!snapshot.exists()) {
                if (hasSeenExistingNode) {
                    hasSeenExistingNode = false
                    onUserDeleted?.invoke()
                }
                return@onValue
            }
            val rawMap = snapshot.value as? Map<*, *> ?: return@onValue
            hasSeenExistingNode = true
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
                serverTimeOffsetMs = offset
                hasServerTimeOffset = true
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
        onExpiresAtChanged: (Long?) -> Unit = {},
        onBonusTrialDaysChanged: (Int) -> Unit = {},
        onUserDeleted: (() -> Unit)? = null,
        onFullSnapshot: ((
            subscriptionStatus: String?,
            isPremium: Boolean?,
            isBlocked: Boolean?,
            installDateMs: Long?,
            expiresAtMs: Long?,
            bonusTrialDays: Int?
        ) -> Unit)? = null
    ): (() -> Unit)? {
        if (uid.isBlank()) return null
        val cleanups = mutableListOf<() -> Unit>()
        var hasSeenExistingUserNode = false

        // Atomic parent snapshot listener delivers all entitlement fields together so out-of-order
        // child updates never clobber isPremium=true with a stale status or expiry timestamp,
        // and immediately reacts when Admin deletes `/users/{uid}` within < 1 second.
        onValue("$NODE_USERS/$uid", context) { snapshot ->
            if (!snapshot.exists()) {
                if (hasSeenExistingUserNode) {
                    hasSeenExistingUserNode = false
                    onUserDeleted?.invoke()
                }
                return@onValue
            }
            val rawMap = snapshot.value as? Map<*, *> ?: return@onValue
            hasSeenExistingUserNode = true
            val hasExpKey = rawMap.containsKey("subscriptionExpiresAtMs") || rawMap.containsKey("subscriptionExpiresAt")
            val expMs = if (hasExpKey) {
                asLong(rawMap["subscriptionExpiresAtMs"]) ?: asLong(rawMap["subscriptionExpiresAt"]) ?: 0L
            } else {
                null
            }
            val bonusDays = asLong(rawMap["bonusTrialDays"])?.toInt()?.coerceIn(0, 3650)
            val installMs = (asLong(rawMap["installDate"])
                ?: asLong(rawMap["registrationDate"])
                ?: asLong(rawMap["createdAtMs"]))?.takeIf { it > 0L }
            val blocked = asBool(rawMap["isBlocked"])
            val status = (rawMap["subscriptionStatus"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            val prem = asBool(rawMap["isPremium"]) ?: asBool(rawMap["isPremiumUser"])

            if (onFullSnapshot != null) {
                onFullSnapshot(status, prem, blocked, installMs, expMs, bonusDays)
            } else {
                if (hasExpKey) {
                    onExpiresAtChanged(expMs)
                }
                bonusDays?.let(onBonusTrialDaysChanged)
                installMs?.let(onInstallDateChanged)
                blocked?.let(onIsBlockedChanged)
                status?.let(onSubscriptionStatusChanged)
                prem?.let(onIsPremiumChanged)
            }
        }?.let { cleanups.add(it) }

        if (onFullSnapshot == null) {
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

            onValue("$NODE_USERS/$uid/bonusTrialDays", context) { snapshot ->
                if (!snapshot.exists()) {
                    onBonusTrialDaysChanged(0)
                    return@onValue
                }
                val days = (asLong(snapshot.value) ?: 0L).toInt().coerceIn(0, 3650)
                onBonusTrialDaysChanged(days)
            }?.let { cleanups.add(it) }
        }

        if (cleanups.isEmpty()) return null
        return {
            cleanups.forEach { cleanup -> runCatching { cleanup() } }
        }
    }

    /**
     * Propagates Admin-updated entitlement fields across any duplicate `/users/{otherUid}` nodes
     * sharing the same normalized email address (e.g. when a user signed in via fallback UID or
     * when Admin pre-created the user by email before their first login).
     */
    fun propagateEntitlementByEmailInRtdb(
        email: String,
        primaryUid: String,
        fields: Map<String, Any?>,
        context: Context? = null,
        deviceHash: String = ""
    ) {
        val normalizedEmail = email.trim().lowercase()
        val normalizedDeviceHash = deviceHash.trim().lowercase().takeIf { it.length == 64 }.orEmpty()
        if ((normalizedEmail.isEmpty() && normalizedDeviceHash.isEmpty()) || fields.isEmpty()) return
        val usersRef = refForPath(NODE_USERS, context) ?: return
        val entitlementKeys = setOf(
            "isPremium",
            "isPremiumUser",
            "subscriptionStatus",
            "subscriptionExpiresAtMs",
            "isBlocked",
            "bonusTrialDays",
            "adminNote",
            "hideSubscriptionUI",
            "autoCallRecording",
            "autoRecordEnabled",
            "isAdmin",
            "adminGrantedBy",
            "updatedAtMs"
        )
        val filteredFields = fields.filterKeys { it in entitlementKeys }
        if (filteredFields.isEmpty()) return
        runCatching {
            usersRef.addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (!snapshot.exists()) return
                    for (child in snapshot.children) {
                        val otherUid = child.key.orEmpty()
                        if (otherUid.isBlank() || otherUid == primaryUid) continue
                        val childEmail = (child.child("email").value as? String)?.trim()?.lowercase().orEmpty()
                        val childDevice = (child.child("deviceHash").value as? String)?.trim()?.lowercase().orEmpty()
                        val emailMatch = normalizedEmail.isNotEmpty() && childEmail == normalizedEmail
                        val deviceMatch = normalizedDeviceHash.isNotEmpty() && childDevice == normalizedDeviceHash
                        if (emailMatch || deviceMatch) {
                            child.ref.updateChildren(filteredFields)
                        }
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.d(TAG, "propagateEntitlementByEmailInRtdb cancelled: ${error.message}")
                }
            })
        }
    }

    /**
     * Checks whether any sibling `/users/{otherUid}` node matching [email] or [deviceHash]
     * holds an active Admin-granted Premium entitlement and mirrors it onto `/users/{activeUid}`
     * so UID mismatches between Google Sign-In, fallback login, or Admin pre-creation never block access.
     */
    fun syncEntitlementFromMatchingEmailOrDeviceInRtdb(
        context: Context,
        activeUid: String,
        email: String,
        deviceHash: String = "",
        onResolved: (UserEntitlement) -> Unit = {}
    ) {
        val cleanUid = activeUid.trim()
        val normalizedEmail = email.trim().lowercase()
        val normalizedDevice = deviceHash.trim().lowercase().takeIf { it.length == 64 }.orEmpty()
        if (cleanUid.isEmpty() || (normalizedEmail.isEmpty() && normalizedDevice.isEmpty())) return
        val usersRef = refForPath(NODE_USERS, context) ?: return
        val nowMs = currentServerNowMs()

        runCatching {
            usersRef.addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (!snapshot.exists()) return
                    var bestMatch: UserEntitlement? = null
                    var bestUpdatedMs = -1L
                    val currentNodeMap = snapshot.child(cleanUid).value as? Map<*, *>
                    val currentParsed = currentNodeMap?.let { parseUserSnapshot(cleanUid, it, null) }
                    val currentExp = currentParsed?.subscriptionExpiresAt?.toDate()?.time ?: 0L
                    val currentAlreadyActive = currentParsed != null &&
                        !currentParsed.isBlocked &&
                        (currentParsed.isPremiumUser || currentParsed.subscriptionStatus == UserEntitlement.STATUS_ACTIVE) &&
                        (currentExp <= 0L || currentExp > nowMs)

                    for (child in snapshot.children) {
                        val otherUid = child.key.orEmpty()
                        if (otherUid.isBlank() || otherUid == cleanUid) continue
                        val rawMap = child.value as? Map<*, *> ?: continue
                        val childEmail = (rawMap["email"] as? String)?.trim()?.lowercase().orEmpty()
                        val childDevice = (rawMap["deviceHash"] as? String)?.trim()?.lowercase().orEmpty()
                        val matches = (normalizedEmail.isNotEmpty() && childEmail == normalizedEmail) ||
                            (normalizedDevice.isNotEmpty() && childDevice == normalizedDevice)
                        if (!matches) continue

                        val parsed = parseUserSnapshot(cleanUid, rawMap, currentParsed)
                        val expMs = parsed.subscriptionExpiresAt?.toDate()?.time ?: 0L
                        val isActive = !parsed.isBlocked &&
                            (parsed.isPremiumUser || parsed.subscriptionStatus == UserEntitlement.STATUS_ACTIVE) &&
                            (expMs <= 0L || expMs > nowMs)
                        val updMs = parsed.updatedAt?.toDate()?.time ?: 0L
                        if (isActive && (!currentAlreadyActive || expMs > currentExp) && updMs >= bestUpdatedMs) {
                            bestMatch = parsed
                            bestUpdatedMs = updMs
                        }
                    }

                    val winner = bestMatch ?: return
                    val expMs = winner.subscriptionExpiresAt?.toDate()?.time
                    val mirrorFields = mutableMapOf<String, Any?>(
                        "isPremium" to true,
                        "isPremiumUser" to true,
                        "subscriptionStatus" to UserEntitlement.STATUS_ACTIVE,
                        "isBlocked" to false,
                        "updatedAtMs" to maxOf(nowMs, bestUpdatedMs, System.currentTimeMillis())
                    )
                    if (expMs != null && expMs > 0L) {
                        mirrorFields["subscriptionExpiresAtMs"] = expMs
                    }
                    if (winner.bonusTrialDays > 0L) {
                        mirrorFields["bonusTrialDays"] = winner.bonusTrialDays
                    }
                    if (winner.isAdmin) {
                        mirrorFields["isAdmin"] = true
                        mirrorFields["adminGrantedBy"] = winner.adminGrantedBy
                    }
                    usersRef.child(cleanUid).updateChildren(mirrorFields)
                    onResolved(winner)
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.d(TAG, "syncEntitlementFromMatchingEmailOrDeviceInRtdb cancelled: ${error.message}")
                }
            })
        }
    }

    suspend fun fetchUserSnapshotOnce(
        context: Context,
        uid: String,
        fallback: UserEntitlement? = null
    ): Pair<UserEntitlement, Boolean>? {
        if (uid.isBlank()) return null
        val ref = refForPath("$NODE_USERS/$uid", context) ?: return null
        return kotlinx.coroutines.withTimeoutOrNull(2500L) {
            kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                val listener = object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        if (!cont.isActive) return
                        val rawMap = if (snapshot.exists()) snapshot.value as? Map<*, *> else null
                        if (rawMap == null) {
                            cont.resume(null, onCancellation = null)
                        } else {
                            cont.resume(
                                parseUserSnapshot(uid, rawMap, fallback) to rawMap.containsKey("blockedNumbers"),
                                onCancellation = null
                            )
                        }
                    }

                    override fun onCancelled(error: DatabaseError) {
                        if (cont.isActive) {
                            cont.resume(null, onCancellation = null)
                        }
                    }
                }
                ref.addListenerForSingleValueEvent(listener)
                cont.invokeOnCancellation {
                    runCatching { ref.removeEventListener(listener) }
                }
            }
        }
    }

    suspend fun fetchAppConfigOnce(
        context: Context,
        fallback: AppConfig = AppConfig(id = AppConfig.DOC_GLOBAL_SETTINGS)
    ): AppConfig? {
        val ref = refForPath(NODE_APP_CONFIG, context) ?: return null
        return kotlinx.coroutines.withTimeoutOrNull(2500L) {
            kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                val listener = object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        if (!cont.isActive) return
                        val rawMap = if (snapshot.exists()) snapshot.value as? Map<*, *> else null
                        if (rawMap == null) {
                            cont.resume(null, onCancellation = null)
                        } else {
                            cont.resume(parseAppConfigSnapshot(rawMap, fallback), onCancellation = null)
                        }
                    }

                    override fun onCancelled(error: DatabaseError) {
                        if (cont.isActive) {
                            cont.resume(null, onCancellation = null)
                        }
                    }
                }
                ref.addListenerForSingleValueEvent(listener)
                cont.invokeOnCancellation {
                    runCatching { ref.removeEventListener(listener) }
                }
            }
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
     * Attaches a public, zero-secret `onValue` listener to `/welcome_screen` so the
     * unauthenticated Welcome / Sign-In screen updates live whenever Admin edits price or text.
     */
    fun attachWelcomeScreenListener(
        context: Context,
        currentFallback: () -> WelcomeScreenConfig,
        onUpdate: (WelcomeScreenConfig) -> Unit
    ): (() -> Unit)? {
        return onValue(NODE_WELCOME_SCREEN, context) { snapshot ->
            if (!snapshot.exists()) return@onValue
            val rawMap = snapshot.value as? Map<*, *> ?: return@onValue
            val fallback = currentFallback()
            val parsed = WelcomeScreenConfig(
                trialDays = (asLong(rawMap["trialDays"]) ?: fallback.trialDays).coerceIn(1L, 365L),
                subscriptionPriceInr = (asLong(rawMap["subscriptionPriceInr"])
                    ?: asLong(rawMap["subscriptionPrice"])
                    ?: fallback.subscriptionPriceInr).coerceIn(1L, 100000L),
                welcomeTitle = (rawMap["welcomeTitle"] as? String) ?: fallback.welcomeTitle,
                welcomeTrialBadge = (rawMap["welcomeTrialBadge"] as? String) ?: fallback.welcomeTrialBadge,
                welcomeSubtitle = (rawMap["welcomeSubtitle"] as? String) ?: fallback.welcomeSubtitle,
                welcomeButtonText = (rawMap["welcomeButtonText"] as? String) ?: fallback.welcomeButtonText
            )
            onUpdate(parsed)
        }
    }

    /**
     * Defensively parses the `/users` [DataSnapshot] into a sorted list of [UserEntitlement]s,
     * isolating malformed child entries so a single corrupted node never breaks the Admin list.
     */
    fun parseUsersListSnapshot(snapshot: DataSnapshot): List<UserEntitlement> {
        if (!snapshot.exists()) return emptyList()
        val result = mutableListOf<UserEntitlement>()
        for (child in snapshot.children) {
            val uid = child.key.orEmpty().trim()
            if (uid.isEmpty()) continue
            val rawMap = child.value as? Map<*, *> ?: continue
            runCatching {
                parseUserSnapshot(uid, rawMap, null)
            }.onSuccess { parsed ->
                result.add(parsed)
            }.onFailure { err ->
                Log.w(TAG, "Skipping malformed /users/$uid entry: ${err.message}")
            }
        }
        return result.sortedByDescending {
            it.updatedAt?.toDate()?.time
                ?: it.createdAt?.toDate()?.time
                ?: it.trialStartedAt?.toDate()?.time
                ?: 0L
        }
    }

    /**
     * Attaches a persistent `onValue` listener to `/users` for the Real-Time Admin Panel.
     */
    fun attachAllUsersListener(
        context: Context,
        onError: ((DatabaseError) -> Unit)? = null,
        onUpdate: (List<UserEntitlement>) -> Unit
    ): (() -> Unit)? {
        val ref = getUsersReference(context) ?: return null
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                onUpdate(parseUsersListSnapshot(snapshot))
            }

            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "RTDB /users listener cancelled: ${error.message}")
                onError?.invoke(error)
            }
        }
        ref.addValueEventListener(listener)
        return {
            runCatching { ref.removeEventListener(listener) }
        }
    }

    /**
     * Attaches a persistent `onValue` listener to `/payment_requests` filtered for [uid].
     */
    fun attachUserPaymentRequestsListener(
        context: Context,
        uid: String,
        userEmail: String = "",
        onUpdate: (List<PaymentRequest>) -> Unit
    ): (() -> Unit)? {
        if (uid.isBlank()) return null
        val normalizedEmail = userEmail.trim().lowercase()
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
                val emailMatch = normalizedEmail.isNotEmpty() &&
                    parsed.userEmail.trim().lowercase() == normalizedEmail
                if (parsed.userId == uid || emailMatch) {
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

    fun normalizeUtrKey(rawUtr: String?): String {
        if (rawUtr.isNullOrBlank()) return ""
        return rawUtr.trim().uppercase().replace(Regex("[^A-Z0-9_-]"), "").take(64)
    }

    fun attachApprovedUtrsListener(
        context: Context,
        onUpdate: (Set<String>) -> Unit
    ): (() -> Unit)? {
        return onValue(NODE_APPROVED_UTRS, context) { snapshot ->
            if (!snapshot.exists()) {
                onUpdate(emptySet())
                return@onValue
            }
            val keys = mutableSetOf<String>()
            for (child in snapshot.children) {
                val key = normalizeUtrKey(child.key)
                if (key.isNotEmpty()) {
                    keys.add(key)
                }
            }
            onUpdate(keys)
        }
    }

    suspend fun isUtrApprovedInRtdb(utrRaw: String, context: Context? = null): Boolean {
        val key = normalizeUtrKey(utrRaw)
        if (key.isEmpty()) return false
        val ref = refForPath("$NODE_APPROVED_UTRS/$key", context) ?: return false
        return kotlinx.coroutines.withTimeoutOrNull(2500L) {
            kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                val listener = object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        if (cont.isActive) {
                            cont.resume(snapshot.exists(), onCancellation = null)
                        }
                    }

                    override fun onCancelled(error: DatabaseError) {
                        if (cont.isActive) {
                            cont.resume(false, onCancellation = null)
                        }
                    }
                }
                ref.addListenerForSingleValueEvent(listener)
                cont.invokeOnCancellation {
                    runCatching { ref.removeEventListener(listener) }
                }
            }
        } ?: false
    }

    fun markUtrApproved(
        utrRaw: String,
        requestId: String,
        userId: String,
        approvedByUid: String,
        context: Context? = null
    ): Boolean {
        val key = normalizeUtrKey(utrRaw)
        if (key.isEmpty()) return false
        val payload = mapOf(
            "utr" to key,
            "rawUtr" to utrRaw.trim().take(64),
            "requestId" to requestId.trim(),
            "userId" to userId.trim(),
            "approvedByUid" to approvedByUid.trim(),
            "approvedAtMs" to ServerValue.TIMESTAMP
        )
        return set("$NODE_APPROVED_UTRS/$key", payload, context)
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
            base["registrationDate"] = ServerValue.TIMESTAMP
            base["createdAtMs"] = ServerValue.TIMESTAMP
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
                        if (snapshot.exists()) {
                            if (!snapshot.hasChild("installDate")) {
                                updateMap["installDate"] = deviceTrialStartMs ?: ServerValue.TIMESTAMP
                            }
                            if (!snapshot.hasChild("subscriptionStatus")) {
                                updateMap["subscriptionStatus"] = UserEntitlement.STATUS_TRIAL
                            }
                            if (!snapshot.hasChild("isPremium") && !snapshot.hasChild("isPremiumUser")) {
                                updateMap["isPremium"] = false
                                updateMap["isPremiumUser"] = false
                            }
                            if (!snapshot.hasChild("isBlocked")) {
                                updateMap["isBlocked"] = false
                            }
                            userRef.updateChildren(updateMap)
                            return
                        }

                        // New UID node: check if Admin already created or granted a subscription for this email
                        val normalizedEmail = cleanEmail.lowercase()
                        if (normalizedEmail.isNotEmpty()) {
                            root.child(NODE_USERS).addListenerForSingleValueEvent(object : ValueEventListener {
                                override fun onDataChange(allUsersSnap: DataSnapshot) {
                                    var matchedPrem: Boolean? = null
                                    var matchedStatus: String? = null
                                    var matchedExpMs: Long? = null
                                    var matchedBonusDays: Long? = null
                                    var matchedBlocked: Boolean? = null
                                    var matchedAdmin: Boolean? = null
                                    var matchedGrantedBy: String? = null
                                    for (child in allUsersSnap.children) {
                                        val otherUid = child.key.orEmpty()
                                        if (otherUid == cleanUid) continue
                                        val childEmail = (child.child("email").value as? String)?.trim()?.lowercase().orEmpty()
                                        val childDeviceHash = (child.child("deviceHash").value as? String)?.trim().orEmpty()
                                        val matchesEmail = normalizedEmail.isNotEmpty() && childEmail == normalizedEmail
                                        val matchesDevice = cleanDeviceHash.isNotEmpty() && childDeviceHash == cleanDeviceHash
                                        if (matchesEmail || matchesDevice) {
                                            val cPrem = asBool(child.child("isPremium").value)
                                                ?: asBool(child.child("isPremiumUser").value)
                                                ?: false
                                            val cStatus = (child.child("subscriptionStatus").value as? String)?.trim().orEmpty()
                                            if (cPrem || cStatus == UserEntitlement.STATUS_ACTIVE || matchedStatus == null) {
                                                matchedPrem = cPrem
                                                matchedStatus = cStatus.ifBlank {
                                                    if (cPrem) UserEntitlement.STATUS_ACTIVE else UserEntitlement.STATUS_TRIAL
                                                }
                                                matchedExpMs = asLong(child.child("subscriptionExpiresAtMs").value)
                                                matchedBonusDays = asLong(child.child("bonusTrialDays").value)
                                                matchedBlocked = asBool(child.child("isBlocked").value)
                                                matchedAdmin = asBool(child.child("isAdmin").value)
                                                matchedGrantedBy = (child.child("adminGrantedBy").value as? String)
                                                if (cPrem || cStatus == UserEntitlement.STATUS_ACTIVE) break
                                            }
                                        }
                                    }
                                    updateMap["installDate"] = deviceTrialStartMs ?: ServerValue.TIMESTAMP
                                    updateMap["subscriptionStatus"] = matchedStatus ?: UserEntitlement.STATUS_TRIAL
                                    updateMap["isPremium"] = matchedPrem ?: false
                                    updateMap["isPremiumUser"] = matchedPrem ?: false
                                    updateMap["isBlocked"] = matchedBlocked ?: false
                                    if (matchedExpMs != null && matchedExpMs > 0L) {
                                        updateMap["subscriptionExpiresAtMs"] = matchedExpMs
                                    }
                                    if (matchedBonusDays != null && matchedBonusDays > 0L) {
                                        updateMap["bonusTrialDays"] = matchedBonusDays
                                    }
                                    if (matchedAdmin == true) {
                                        updateMap["isAdmin"] = true
                                        updateMap["adminGrantedBy"] = matchedGrantedBy.orEmpty()
                                    }
                                    userRef.updateChildren(updateMap)
                                }

                                override fun onCancelled(error: DatabaseError) {
                                    updateMap["installDate"] = deviceTrialStartMs ?: ServerValue.TIMESTAMP
                                    updateMap["subscriptionStatus"] = UserEntitlement.STATUS_TRIAL
                                    updateMap["isPremium"] = false
                                    updateMap["isPremiumUser"] = false
                                    updateMap["isBlocked"] = false
                                    userRef.updateChildren(updateMap)
                                }
                            })
                        } else {
                            updateMap["installDate"] = deviceTrialStartMs ?: ServerValue.TIMESTAMP
                            updateMap["subscriptionStatus"] = UserEntitlement.STATUS_TRIAL
                            updateMap["isPremium"] = false
                            updateMap["isPremiumUser"] = false
                            updateMap["isBlocked"] = false
                            userRef.updateChildren(updateMap)
                        }
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
     * Atomically updates a user's `isPremium` status, `subscriptionStatus`, and expiry timestamp
     * directly in `/users/{uid}` (and any sibling nodes sharing the same email/device) for
     * sub-1-second client synchronization.
     */
    fun updateUserIsPremiumDirect(
        uid: String,
        isPremium: Boolean,
        subscriptionExpiresAtMs: Long?,
        subscriptionStatus: String = if (isPremium) UserEntitlement.STATUS_ACTIVE else UserEntitlement.STATUS_EXPIRED,
        email: String = "",
        deviceHash: String = "",
        context: Context? = null
    ): Boolean {
        val cleanUid = uid.trim()
        if (cleanUid.isEmpty()) return false
        val monotonicNowMs = maxOf(currentServerNowMs(), System.currentTimeMillis())
        val fields = mapOf<String, Any?>(
            "isPremium" to isPremium,
            "isPremiumUser" to isPremium,
            "subscriptionStatus" to subscriptionStatus,
            "subscriptionExpiresAtMs" to if (isPremium) subscriptionExpiresAtMs else null,
            "isBlocked" to false,
            "updatedAtMs" to monotonicNowMs
        )
        val ok = update("$NODE_USERS/$cleanUid", fields, context)
        if (email.isNotBlank() || deviceHash.length == 64) {
            propagateEntitlementByEmailInRtdb(
                email = email,
                primaryUid = cleanUid,
                fields = fields,
                context = context,
                deviceHash = deviceHash
            )
        }
        return ok
    }

    /**
     * Deletes a user node `/users/{uid}` in Firebase Realtime Database in <1s.
     */
    fun deleteUserNode(uid: String, context: Context? = null): Boolean {
        val cleanUid = uid.trim()
        if (cleanUid.isEmpty()) return false
        return runCatching {
            val ref = refForPath("$NODE_USERS/$cleanUid", context) ?: return false
            ref.removeValue()
            true
        }.onFailure { e ->
            Log.d(TAG, "RTDB deleteUserNode($cleanUid) skipped: ${e.message}")
        }.getOrDefault(false)
    }

    /**
     * Permanently deletes `/users/{uid}`, any duplicate sibling `/users/{otherUid}` nodes matching
     * the same normalized email or deviceHash, all `/payment_requests` belonging to this user, and
     * `/device_trials/{deviceHash}` if owned by this user so deletion reflects across Admin and
     * client devices within < 1 second.
     */
    fun deleteUserAndSiblingsInRtdb(
        uid: String,
        email: String = "",
        deviceHash: String = "",
        context: Context? = null
    ): Boolean {
        val cleanUid = uid.trim()
        val deletedPrimary = deleteUserNode(cleanUid, context)
        val normalizedEmail = email.trim().lowercase()
        val normalizedDevice = deviceHash.trim().lowercase().takeIf { it.length == 64 }.orEmpty()

        // 1. Remove any duplicate sibling /users/{otherUid} matching email or deviceHash (never touch Super Admins)
        if (normalizedEmail.isNotEmpty() || normalizedDevice.isNotEmpty()) {
            val usersRef = refForPath(NODE_USERS, context)
            if (usersRef != null) {
                runCatching {
                    usersRef.addListenerForSingleValueEvent(object : ValueEventListener {
                        override fun onDataChange(snapshot: DataSnapshot) {
                            if (!snapshot.exists()) return
                            for (child in snapshot.children) {
                                val otherUid = child.key.orEmpty().trim()
                                if (otherUid.isEmpty() || otherUid == cleanUid) continue
                                val childEmail = (child.child("email").value as? String)?.trim()?.lowercase().orEmpty()
                                if (com.example.billing.UpiPaymentHelper.isSuperAdminEmail(childEmail)) continue
                                val childDevice = (child.child("deviceHash").value as? String)?.trim()?.lowercase().orEmpty()
                                val matches = (normalizedEmail.isNotEmpty() && childEmail == normalizedEmail) ||
                                    (normalizedDevice.isNotEmpty() && childDevice == normalizedDevice)
                                if (matches) {
                                    child.ref.removeValue()
                                }
                            }
                        }

                        override fun onCancelled(error: DatabaseError) {
                            Log.d(TAG, "deleteUserAndSiblingsInRtdb users scan cancelled: ${error.message}")
                        }
                    })
                }
            }
        }

        // 2. Remove all /payment_requests belonging to this user (by userId or userEmail)
        if (cleanUid.isNotEmpty() || normalizedEmail.isNotEmpty()) {
            val paymentsRef = refForPath(NODE_PAYMENT_REQUESTS, context)
            if (paymentsRef != null) {
                runCatching {
                    paymentsRef.addListenerForSingleValueEvent(object : ValueEventListener {
                        override fun onDataChange(snapshot: DataSnapshot) {
                            if (!snapshot.exists()) return
                            for (child in snapshot.children) {
                                val reqUid = (child.child("userId").value as? String)?.trim().orEmpty()
                                val reqEmail = (child.child("userEmail").value as? String)?.trim()?.lowercase().orEmpty()
                                val matches = (cleanUid.isNotEmpty() && reqUid == cleanUid) ||
                                    (normalizedEmail.isNotEmpty() && reqEmail == normalizedEmail)
                                if (matches) {
                                    val utrCandidate = (child.child("upiTxnId").value as? String).orEmpty()
                                        .ifBlank { (child.child("approvalRefNo").value as? String).orEmpty() }
                                        .ifBlank { (child.child("txnRef").value as? String).orEmpty() }
                                    val utrKey = normalizeUtrKey(utrCandidate)
                                    if (utrKey.isNotEmpty()) {
                                        refForPath("$NODE_APPROVED_UTRS/$utrKey", context)?.removeValue()
                                    }
                                    child.ref.removeValue()
                                }
                            }
                        }

                        override fun onCancelled(error: DatabaseError) {
                            Log.d(TAG, "deleteUserAndSiblingsInRtdb payments scan cancelled: ${error.message}")
                        }
                    })
                }
            }
        }

        // 3. Remove any /approved_utrs locks belonging to this user
        if (cleanUid.isNotEmpty()) {
            val utrsRef = refForPath(NODE_APPROVED_UTRS, context)
            if (utrsRef != null) {
                runCatching {
                    utrsRef.addListenerForSingleValueEvent(object : ValueEventListener {
                        override fun onDataChange(snapshot: DataSnapshot) {
                            if (!snapshot.exists()) return
                            for (child in snapshot.children) {
                                val lockUid = (child.child("userId").value as? String)?.trim().orEmpty()
                                if (lockUid == cleanUid) {
                                    child.ref.removeValue()
                                }
                            }
                        }

                        override fun onCancelled(error: DatabaseError) {
                            Log.d(TAG, "deleteUserAndSiblingsInRtdb approved_utrs scan cancelled: ${error.message}")
                        }
                    })
                }
            }
        }

        // 4. Remove /device_trials/{deviceHash} if owned by this deleted user
        if (normalizedDevice.isNotEmpty()) {
            val deviceTrialRef = refForPath("$NODE_DEVICE_TRIALS/$normalizedDevice", context)
            if (deviceTrialRef != null) {
                runCatching {
                    deviceTrialRef.addListenerForSingleValueEvent(object : ValueEventListener {
                        override fun onDataChange(snapshot: DataSnapshot) {
                            if (!snapshot.exists()) return
                            val ownerUid = (snapshot.child("ownerUid").value as? String)?.trim().orEmpty()
                            val lastUid = (snapshot.child("lastUserId").value as? String)?.trim().orEmpty()
                            if (ownerUid == cleanUid || lastUid == cleanUid || ownerUid.isEmpty()) {
                                snapshot.ref.removeValue()
                            }
                        }

                        override fun onCancelled(error: DatabaseError) {
                            Log.d(TAG, "deleteUserAndSiblingsInRtdb device trial scan cancelled: ${error.message}")
                        }
                    })
                }
            }
        }

        return deletedPrimary
    }

    /**
     * Permanently deletes a single payment request node from RTDB `/payment_requests/{id}`
     * and optionally removes its `/approved_utrs/{utrKey}` lock.
     */
    fun deletePaymentRequest(
        requestId: String,
        utrKey: String = "",
        context: Context? = null
    ): Boolean {
        val cleanId = requestId.trim()
        if (cleanId.isEmpty()) return false
        return runCatching {
            val root = rootRef(context) ?: return false
            val updates = mutableMapOf<String, Any?>(
                "$NODE_PAYMENT_REQUESTS/$cleanId" to null
            )
            val cleanUtr = normalizeUtrKey(utrKey)
            if (cleanUtr.isNotEmpty()) {
                updates["$NODE_APPROVED_UTRS/$cleanUtr"] = null
            }
            root.updateChildren(updates)
            true
        }.onFailure { e ->
            Log.d(TAG, "RTDB deletePaymentRequest($cleanId) skipped: ${e.message}")
        }.getOrDefault(false)
    }

    /**
     * Permanently deletes a batch of payment requests (and optionally their UTR locks)
     * from Firebase Realtime Database atomically.
     */
    fun deletePaymentRequests(
        requests: List<PaymentRequest>,
        clearUtrLocks: Boolean = true,
        context: Context? = null
    ): Int {
        if (requests.isEmpty()) return 0
        val updates = mutableMapOf<String, Any?>()
        var count = 0
        for (req in requests) {
            val id = req.id.trim()
            if (id.isEmpty()) continue
            updates["$NODE_PAYMENT_REQUESTS/$id"] = null
            if (clearUtrLocks) {
                val utrKey = normalizeUtrKey(PaymentRepository.extractCanonicalUtr(req))
                if (utrKey.isNotEmpty()) {
                    updates["$NODE_APPROVED_UTRS/$utrKey"] = null
                }
            }
            count++
        }
        if (updates.isEmpty()) return 0
        return runCatching {
            val root = rootRef(context) ?: return 0
            root.updateChildren(updates)
            count
        }.onFailure { e ->
            Log.d(TAG, "RTDB deletePaymentRequests($count) skipped: ${e.message}")
        }.getOrDefault(0)
    }

    /**
     * Updates global `AppConfig` under `/app_config` AND mirrors non-secret welcome screen copy
     * under `/welcome_screen` so unauthenticated login gates update in real time.
     */
    fun pushAppConfig(config: AppConfig, context: Context? = null) {
        update(NODE_APP_CONFIG, toRtdbConfigMap(config), context)
        update(
            NODE_WELCOME_SCREEN,
            mapOf(
                "trialDays" to config.trialDays.coerceIn(1L, 365L),
                "subscriptionPriceInr" to config.subscriptionPriceInr.coerceIn(1L, 100000L),
                "welcomeTitle" to config.welcomeTitle.trim().take(100),
                "welcomeTrialBadge" to config.welcomeTrialBadge.trim().take(100),
                "welcomeSubtitle" to config.welcomeSubtitle.trim().take(400),
                "welcomeButtonText" to config.welcomeButtonText.trim().take(80),
                "updatedAtMs" to System.currentTimeMillis()
            ),
            context
        )
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
        val normalizedType = broadcast.normalizedPopupType()
        val normalizedSize = broadcast.normalizedPopupSize()
        val effectiveForce = normalizedType == LiveBroadcast.TYPE_UPDATE && broadcast.forceUpdate
        val effectiveCancelable = if (effectiveForce) false else true
        val payload = mapOf(
            "showPopup" to broadcast.showPopup,
            "popupId" to broadcast.popupId,
            "popupType" to normalizedType,
            "popupSize" to normalizedSize,
            "version" to broadcast.version,
            "forceUpdate" to effectiveForce,
            "showOnce" to broadcast.showOnce,
            "title" to broadcast.title,
            "message" to broadcast.message,
            "imageUrl" to broadcast.imageUrl,
            "buttonText" to broadcast.buttonText,
            "actionUrl" to broadcast.actionUrl,
            "isCancelable" to effectiveCancelable,
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
            "registrationDate" to installMs,
            "createdAtMs" to (entitlement.createdAt?.toDate()?.time ?: installMs),
            "subscriptionStatus" to entitlement.subscriptionStatus,
            "isPremium" to entitlement.isPremiumUser,
            "isPremiumUser" to entitlement.isPremiumUser,
            "paymentRefId" to entitlement.paymentRefId,
            "isBlocked" to entitlement.isBlocked,
            "isAdmin" to entitlement.isAdmin,
            "adminGrantedBy" to entitlement.adminGrantedBy,
            "autoRecordEnabled" to entitlement.autoCallRecording,
            "autoCallRecording" to entitlement.autoCallRecording,
            "themeMode" to entitlement.themeMode,
            "themePaletteId" to entitlement.themePaletteId,
            "useDynamicColors" to entitlement.useDynamicColors,
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
            "recordInstagramCalls" to entitlement.recordInstagramCalls,
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
            ?: asLong(map["registrationDate"])
            ?: asLong(map["createdAtMs"])
            ?: asLong(map["createdAt"])
            ?: asLong(map["trialStartedAtMs"])
        val createdAtMs = asLong(map["registrationDate"])
            ?: asLong(map["createdAtMs"])
            ?: asLong(map["createdAt"])
            ?: installDateMs
        val trialStartedTs = if (installDateMs != null && installDateMs > 0L) {
            Timestamp(Date(installDateMs))
        } else {
            base.trialStartedAt
        }
        val createdAtTs = if (createdAtMs != null && createdAtMs > 0L) {
            Timestamp(Date(createdAtMs))
        } else {
            base.createdAt ?: trialStartedTs
        }
        val expiresMs = asLong(map["subscriptionExpiresAtMs"])
            ?: asLong(map["subscriptionExpiresAt"])
        val hasExplicitExpiryField = map.containsKey("subscriptionExpiresAtMs") ||
            map.containsKey("subscriptionExpiresAt")
        val subscriptionExpiresTs = when {
            expiresMs != null && expiresMs > 0L -> Timestamp(Date(expiresMs))
            hasExplicitExpiryField -> null
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
            isAdmin = asBool(map["isAdmin"]) ?: base.isAdmin,
            adminGrantedBy = (map["adminGrantedBy"] as? String) ?: base.adminGrantedBy,
            subscriptionStatus = (map["subscriptionStatus"] as? String)?.trim()?.lowercase()?.ifBlank { null }
                ?: base.subscriptionStatus,
            paymentRefId = (map["paymentRefId"] as? String) ?: base.paymentRefId,
            autoCallRecording = autoRecordVal,
            themeMode = (map["themeMode"] as? String)?.takeIf { it.isNotBlank() } ?: base.themeMode,
            themePaletteId = (map["themePaletteId"] as? String)?.takeIf { it.isNotBlank() } ?: base.themePaletteId,
            useDynamicColors = asBool(map["useDynamicColors"]) ?: base.useDynamicColors,
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
            recordInstagramCalls = asBool(map["recordInstagramCalls"]) ?: base.recordInstagramCalls,
            createdAt = createdAtTs,
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
            "enableUpiGateway" to config.enableUpiGateway,
            "enableRazorpayGateway" to config.enableRazorpayGateway,
            "razorpayKeyId" to config.razorpayKeyId,
            "razorpayPaymentLink" to config.razorpayPaymentLink,
            "enablePayuGateway" to config.enablePayuGateway,
            "payuMerchantKey" to config.payuMerchantKey,
            "payuPaymentLink" to config.payuPaymentLink,
            "enableCustomGateway" to config.enableCustomGateway,
            "customGatewayName" to config.customGatewayName,
            "customGatewayUrl" to config.customGatewayUrl,
            "welcomeTitle" to config.welcomeTitle,
            "welcomeTrialBadge" to config.welcomeTrialBadge,
            "welcomeSubtitle" to config.welcomeSubtitle,
            "welcomeButtonText" to config.welcomeButtonText,
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
                ?: fallback.hideSubscriptionUI,
            enableUpiGateway = asBool(map["enableUpiGateway"])
                ?: fallback.enableUpiGateway,
            enableRazorpayGateway = asBool(map["enableRazorpayGateway"])
                ?: fallback.enableRazorpayGateway,
            razorpayKeyId = (map["razorpayKeyId"] as? String)
                ?: fallback.razorpayKeyId,
            razorpayPaymentLink = (map["razorpayPaymentLink"] as? String)
                ?: fallback.razorpayPaymentLink,
            enablePayuGateway = asBool(map["enablePayuGateway"])
                ?: fallback.enablePayuGateway,
            payuMerchantKey = (map["payuMerchantKey"] as? String)
                ?: fallback.payuMerchantKey,
            payuPaymentLink = (map["payuPaymentLink"] as? String)
                ?: fallback.payuPaymentLink,
            enableCustomGateway = asBool(map["enableCustomGateway"])
                ?: fallback.enableCustomGateway,
            customGatewayName = (map["customGatewayName"] as? String)
                ?: fallback.customGatewayName,
            customGatewayUrl = (map["customGatewayUrl"] as? String)
                ?: fallback.customGatewayUrl,
            welcomeTitle = (map["welcomeTitle"] as? String)
                ?: fallback.welcomeTitle,
            welcomeTrialBadge = (map["welcomeTrialBadge"] as? String)
                ?: fallback.welcomeTrialBadge,
            welcomeSubtitle = (map["welcomeSubtitle"] as? String)
                ?: fallback.welcomeSubtitle,
            welcomeButtonText = (map["welcomeButtonText"] as? String)
                ?: fallback.welcomeButtonText
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
        val isCancelable = (map["isCancelable"] as? Boolean) ?: true
        val rawVersion = (map["version"] as? String).orEmpty()
        val rawForce = (map["forceUpdate"] as? Boolean) ?: !isCancelable
        val rawType = (map["popupType"] as? String)?.trim()?.lowercase().orEmpty()
        val resolvedType = when (rawType) {
            LiveBroadcast.TYPE_AD -> LiveBroadcast.TYPE_AD
            LiveBroadcast.TYPE_UPDATE -> LiveBroadcast.TYPE_UPDATE
            else -> if (rawVersion.isNotBlank() || rawForce) LiveBroadcast.TYPE_UPDATE else LiveBroadcast.TYPE_AD
        }
        return LiveBroadcast(
            id = LiveBroadcast.DOC_LIVE_BROADCAST,
            showPopup = (map["showPopup"] as? Boolean) ?: false,
            popupId = (map["popupId"] as? String).orEmpty(),
            popupType = resolvedType,
            version = rawVersion,
            forceUpdate = resolvedType == LiveBroadcast.TYPE_UPDATE && rawForce,
            showOnce = (map["showOnce"] as? Boolean) ?: true,
            title = (map["title"] as? String).orEmpty(),
            message = (map["message"] as? String).orEmpty(),
            imageUrl = (map["imageUrl"] as? String).orEmpty(),
            buttonText = (map["buttonText"] as? String)?.ifBlank { "Open" } ?: "Open",
            actionUrl = (map["actionUrl"] as? String).orEmpty(),
            isCancelable = if (resolvedType == LiveBroadcast.TYPE_UPDATE && rawForce) false else isCancelable,
            showBannerAd = (map["showBannerAd"] as? Boolean) ?: false,
            bannerText = (map["bannerText"] as? String).orEmpty(),
            bannerImageUrl = (map["bannerImageUrl"] as? String).orEmpty(),
            bannerActionUrl = (map["bannerActionUrl"] as? String).orEmpty(),
            updatedByUid = (map["updatedByUid"] as? String).orEmpty(),
            popupSize = LiveBroadcast.normalizedPopupSize(map["popupSize"] as? String)
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
            "popupSize" to CustomPopupAd.normalizedPopupSize(ad.popupSize),
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
            clicksCount = (asLong(map["clicksCount"]) ?: 0L).coerceAtLeast(0L),
            popupSize = CustomPopupAd.normalizedPopupSize(map["popupSize"] as? String)
        )
    }

    /**
     * Looks up a verified real caller profile from Firebase RTDB:
     * 1. Exact key match under `/caller_directory/{e164Digits}`
     * 2. Fallback match against registered PulseDial users under `/users` matching the same phone number.
     * Always timeout-bounded (1.8s) and safe when offline or in Robolectric unit tests.
     */
    suspend fun lookupCallerProfileByPhone(
        context: Context,
        rawNumber: String
    ): CallerDirectoryProfile? {
        val e164Key = PhoneGeoDatabase.toE164Digits(rawNumber)
        if (e164Key.length < 7) return null
        val targetLast10 = e164Key.takeLast(10)
        val root = rootRef(context) ?: return null

        // 1. Check verified community/admin caller directory entry
        val directRef = root.child(NODE_CALLER_DIRECTORY).child(e164Key)
        val directProfile = kotlinx.coroutines.withTimeoutOrNull(1400L) {
            kotlinx.coroutines.suspendCancellableCoroutine<CallerDirectoryProfile?> { cont ->
                val listener = object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        if (!cont.isActive) return
                        val rawMap = if (snapshot.exists()) snapshot.value as? Map<*, *> else null
                        if (rawMap == null) {
                            cont.resume(null, onCancellation = null)
                            return
                        }
                        val name = (rawMap["displayName"] as? String)?.trim().orEmpty()
                        if (name.isEmpty()) {
                            cont.resume(null, onCancellation = null)
                            return
                        }
                        val platformsRaw = rawMap["socialPlatforms"] as? List<*>
                        val platforms = platformsRaw
                            ?.mapNotNull { (it as? String)?.trim()?.takeIf { s -> s.isNotEmpty() } }
                            .orEmpty()
                        cont.resume(
                            CallerDirectoryProfile(
                                phoneKey = e164Key,
                                displayName = name,
                                city = (rawMap["city"] as? String)?.trim().orEmpty(),
                                state = (rawMap["state"] as? String)?.trim().orEmpty(),
                                postalCode = (rawMap["postalCode"] as? String)?.trim().orEmpty(),
                                country = (rawMap["country"] as? String)?.trim().orEmpty(),
                                countryCode = (rawMap["countryCode"] as? String)?.trim().orEmpty(),
                                socialPlatforms = platforms,
                                sourceLabel = (rawMap["sourceLabel"] as? String)?.trim()
                                    ?.takeIf { it.isNotEmpty() }
                                    ?: "Verified Cloud Directory"
                            ),
                            onCancellation = null
                        )
                    }

                    override fun onCancelled(error: DatabaseError) {
                        if (cont.isActive) cont.resume(null, onCancellation = null)
                    }
                }
                directRef.addListenerForSingleValueEvent(listener)
                cont.invokeOnCancellation { runCatching { directRef.removeEventListener(listener) } }
            }
        }
        if (directProfile != null) return directProfile

        // 2. Fallback: match against registered PulseDial users by normalized phone number
        val usersRef = root.child(NODE_USERS)
        return kotlinx.coroutines.withTimeoutOrNull(1400L) {
            kotlinx.coroutines.suspendCancellableCoroutine<CallerDirectoryProfile?> { cont ->
                val listener = object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        if (!cont.isActive) return
                        var matched: CallerDirectoryProfile? = null
                        if (snapshot.exists()) {
                            for (child in snapshot.children) {
                                val rawMap = child.value as? Map<*, *> ?: continue
                                val userPhone = ((rawMap["phone"] as? String)
                                    ?: (rawMap["phoneNumber"] as? String)).orEmpty()
                                val userE164 = PhoneGeoDatabase.toE164Digits(userPhone)
                                if (userE164.length < 7) continue
                                if (userE164 == e164Key ||
                                    (targetLast10.length == 10 && userE164.takeLast(10) == targetLast10)
                                ) {
                                    val name = (rawMap["displayName"] as? String)?.trim().orEmpty()
                                    if (name.isNotEmpty() && !name.equals("PulseDial User", ignoreCase = true)) {
                                        matched = CallerDirectoryProfile(
                                            phoneKey = e164Key,
                                            displayName = name,
                                            city = (rawMap["city"] as? String)?.trim().orEmpty(),
                                            state = (rawMap["state"] as? String)?.trim().orEmpty(),
                                            postalCode = (rawMap["postalCode"] as? String)?.trim().orEmpty(),
                                            country = (rawMap["country"] as? String)?.trim().orEmpty(),
                                            socialPlatforms = listOf("PulseDial"),
                                            sourceLabel = "PulseDial Cloud Directory"
                                        )
                                        break
                                    }
                                }
                            }
                        }
                        cont.resume(matched, onCancellation = null)
                    }

                    override fun onCancelled(error: DatabaseError) {
                        if (cont.isActive) cont.resume(null, onCancellation = null)
                    }
                }
                usersRef.addListenerForSingleValueEvent(listener)
                cont.invokeOnCancellation { runCatching { usersRef.removeEventListener(listener) } }
            }
        }
    }

    /**
     * Saves a user-verified caller name and optional Location / State / ZIP / Country into
     * `/caller_directory/{e164Digits}` so all subsequent lookups show the verified real identity.
     */
    fun saveVerifiedCallerProfile(
        context: Context,
        rawNumber: String,
        displayName: String,
        city: String = "",
        state: String = "",
        postalCode: String = "",
        country: String = "",
        countryCode: String = "",
        socialPlatforms: List<String> = emptyList(),
        submittedByUid: String = ""
    ): Boolean {
        val e164Key = PhoneGeoDatabase.toE164Digits(rawNumber)
        val cleanName = displayName.trim()
        if (e164Key.length < 5 || cleanName.isEmpty()) return false
        val payload = mapOf(
            "phoneKey" to e164Key,
            "rawNumber" to rawNumber.trim(),
            "displayName" to cleanName,
            "city" to city.trim(),
            "state" to state.trim(),
            "postalCode" to postalCode.trim(),
            "country" to country.trim(),
            "countryCode" to countryCode.trim(),
            "socialPlatforms" to socialPlatforms.distinct(),
            "sourceLabel" to "Verified Caller Directory",
            "submittedByUid" to submittedByUid.trim(),
            "updatedAtMs" to ServerValue.TIMESTAMP
        )
        return set("$NODE_CALLER_DIRECTORY/$e164Key", payload, context)
    }
}
