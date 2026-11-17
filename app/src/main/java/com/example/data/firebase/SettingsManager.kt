package com.example.data.firebase

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import com.example.R
import com.example.data.DialerPreferences
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Centralized Real-Time Settings & SharedPreferences Synchronization Manager.
 *
 * Ensures that whenever a user changes preferences ("Auto Call Recording", "Theme (Dark/Light)",
 * "Sounds & Vibration", "Blocked Numbers", or "Quick Decline Responses"):
 * 1. The change is immediately persisted to local [DialerPreferences] (SharedPreferences).
 * 2. The change is pushed in real time to `/users/{uid}` in Firebase Firestore.
 * 3. Incoming real-time `addSnapshotListener` updates on `/users/{uid}` (via [RealtimeSyncManager]
 *    or [observeUserSettings]) immediately update local [DialerPreferences] and active UI state.
 */
class SettingsManager(
    private val db: FirebaseFirestore?,
    private val auth: FirebaseAuth?,
    private val prefs: DialerPreferences
) {

    constructor(context: Context) : this(
        db = resolveFirestoreOrNull(context.applicationContext),
        auth = resolveAuthOrNull(context.applicationContext),
        prefs = DialerPreferences(context.applicationContext)
    )

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun activeUid(): String? {
        val firebaseUid = runCatching { auth?.currentUser?.uid }.getOrNull()?.takeIf { it.isNotBlank() }
        if (firebaseUid != null) return firebaseUid
        return prefs.loggedInUserId.takeIf { prefs.isLoggedIn && it.isNotBlank() }
    }

    fun observeUserSettings(): Flow<UserEntitlement?> = flow {
        val firestore = db
            ?: throw IllegalStateException("FirebaseFirestore is not initialized.")
        val uid = activeUid()
            ?: throw IllegalStateException("User must be signed in before observing settings.")
        val path = "${DeviceTrialRepository.COLLECTION_USERS}/$uid"
        emitAll(
            firestore.collection(DeviceTrialRepository.COLLECTION_USERS)
                .document(uid)
                .snapshots()
                .map { snapshot ->
                    if (!snapshot.exists()) {
                        null
                    } else {
                        val user = snapshot.toObject(
                            UserEntitlement::class.java,
                            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                        )
                        applyCloudUserSettings(snapshot, user, prefs)
                        user
                    }
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth?.currentUser?.uid == uid) {
                        handleFirestoreError(exceptionToLog, OperationType.GET, path, auth)
                    }
                    throw error
                }
        )
    }

    fun setAutoCallRecording(enabled: Boolean) {
        prefs.markAutoRecordingCustomizedByUser()
        prefs.autoCallRecording = enabled
        pushFieldsAsync(mapOf("autoCallRecording" to enabled))
        activeUid()?.let { uid ->
            RealtimeDatabaseSync.pushUserFields(
                uid,
                mapOf(
                    "autoRecordEnabled" to enabled,
                    "autoCallRecording" to enabled,
                    "updatedAtMs" to System.currentTimeMillis()
                )
            )
        }
    }

    fun setThemeMode(nightMode: Int) {
        prefs.displayThemeMode = nightMode
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper() &&
            AppCompatDelegate.getDefaultNightMode() != nightMode
        ) {
            AppCompatDelegate.setDefaultNightMode(nightMode)
        }
        val themeStr = if (nightMode == AppCompatDelegate.MODE_NIGHT_YES) {
            UserEntitlement.THEME_DARK
        } else {
            UserEntitlement.THEME_LIGHT
        }
        pushFieldsAsync(mapOf("themeMode" to themeStr))
        activeUid()?.let { uid ->
            RealtimeDatabaseSync.pushUserFields(
                uid,
                mapOf(
                    "themeMode" to themeStr,
                    "updatedAtMs" to System.currentTimeMillis()
                )
            )
        }
    }

    fun setDialpadTonesEnabled(enabled: Boolean) {
        prefs.markDialpadTonesCustomizedByUser()
        prefs.dialpadTonesEnabled = enabled
        pushFieldsAsync(mapOf("dialpadTonesEnabled" to enabled))
        activeUid()?.let { uid ->
            RealtimeDatabaseSync.pushUserFields(
                uid,
                mapOf(
                    "dialpadTonesEnabled" to enabled,
                    "updatedAtMs" to System.currentTimeMillis()
                )
            )
        }
    }

    fun addBlockedNumber(number: String) {
        prefs.addBlockedNumber(number)
        val blocked = prefs.getBlockedNumbers().take(500)
        pushFieldsAsync(mapOf("blockedNumbers" to blocked))
        activeUid()?.let { uid ->
            RealtimeDatabaseSync.pushUserFields(
                uid,
                mapOf(
                    "blockedNumbers" to blocked,
                    "updatedAtMs" to System.currentTimeMillis()
                )
            )
        }
    }

    fun removeBlockedNumber(number: String) {
        prefs.removeBlockedNumber(number)
        val blocked = prefs.getBlockedNumbers().take(500)
        pushFieldsAsync(mapOf("blockedNumbers" to blocked))
        activeUid()?.let { uid ->
            RealtimeDatabaseSync.pushUserFields(
                uid,
                mapOf(
                    "blockedNumbers" to blocked,
                    "updatedAtMs" to System.currentTimeMillis()
                )
            )
        }
    }

    fun addQuickDeclineResponse(message: String) {
        prefs.addQuickDeclineResponse(message)
        val declines = prefs.getQuickDeclineResponses().map { it.take(200) }.take(50)
        pushFieldsAsync(mapOf("quickDeclineResponses" to declines))
        activeUid()?.let { uid ->
            RealtimeDatabaseSync.pushUserFields(
                uid,
                mapOf(
                    "quickDeclineResponses" to declines,
                    "updatedAtMs" to System.currentTimeMillis()
                )
            )
        }
    }

    fun removeQuickDeclineResponse(message: String) {
        prefs.removeQuickDeclineResponse(message)
        val declines = prefs.getQuickDeclineResponses().map { it.take(200) }.take(50)
        pushFieldsAsync(mapOf("quickDeclineResponses" to declines))
        activeUid()?.let { uid ->
            RealtimeDatabaseSync.pushUserFields(
                uid,
                mapOf(
                    "quickDeclineResponses" to declines,
                    "updatedAtMs" to System.currentTimeMillis()
                )
            )
        }
    }

    fun setAnnouncerEnabled(enabled: Boolean) {
        prefs.announcerEnabled = enabled
        pushFieldsAsync(mapOf("announcerEnabled" to enabled))
        activeUid()?.let { uid ->
            RealtimeDatabaseSync.pushUserFields(
                uid,
                mapOf(
                    "announcerEnabled" to enabled,
                    "updatedAtMs" to System.currentTimeMillis()
                )
            )
        }
    }

    fun setAnnouncerHeadsetOnly(enabled: Boolean) {
        prefs.announcerHeadsetOnly = enabled
        pushFieldsAsync(mapOf("announcerHeadsetOnly" to enabled))
        activeUid()?.let { uid ->
            RealtimeDatabaseSync.pushUserFields(
                uid,
                mapOf(
                    "announcerHeadsetOnly" to enabled,
                    "updatedAtMs" to System.currentTimeMillis()
                )
            )
        }
    }

    fun setUnknownShieldMode(mode: String) {
        prefs.unknownShieldMode = mode
        val normalized = prefs.unknownShieldMode
        pushFieldsAsync(mapOf("unknownShieldMode" to normalized))
        activeUid()?.let { uid ->
            RealtimeDatabaseSync.pushUserFields(
                uid,
                mapOf(
                    "unknownShieldMode" to normalized,
                    "updatedAtMs" to System.currentTimeMillis()
                )
            )
        }
    }

    fun setAutoSmsFollowUp(enabled: Boolean) {
        prefs.autoSmsFollowUp = enabled
        pushFieldsAsync(mapOf("autoSmsFollowUp" to enabled))
        activeUid()?.let { uid ->
            RealtimeDatabaseSync.pushUserFields(
                uid,
                mapOf(
                    "autoSmsFollowUp" to enabled,
                    "updatedAtMs" to System.currentTimeMillis()
                )
            )
        }
    }

    fun setAutoWhatsappFollowUp(enabled: Boolean) {
        prefs.autoWhatsappFollowUp = enabled
        pushFieldsAsync(mapOf("autoWhatsappFollowUp" to enabled))
        activeUid()?.let { uid ->
            RealtimeDatabaseSync.pushUserFields(
                uid,
                mapOf(
                    "autoWhatsappFollowUp" to enabled,
                    "updatedAtMs" to System.currentTimeMillis()
                )
            )
        }
    }

    fun setFollowUpTrigger(trigger: String) {
        prefs.followUpTrigger = trigger
        val normalized = prefs.followUpTrigger
        pushFieldsAsync(mapOf("followUpTrigger" to normalized))
        activeUid()?.let { uid ->
            RealtimeDatabaseSync.pushUserFields(
                uid,
                mapOf(
                    "followUpTrigger" to normalized,
                    "updatedAtMs" to System.currentTimeMillis()
                )
            )
        }
    }

    fun saveFollowUpTemplates(completedTemplate: String, missedTemplate: String) {
        prefs.completedCallTemplate = completedTemplate
        prefs.missedCallTemplate = missedTemplate
        val cleanCompleted = prefs.completedCallTemplate
        val cleanMissed = prefs.missedCallTemplate
        pushFieldsAsync(
            mapOf(
                "completedCallTemplate" to cleanCompleted,
                "missedCallTemplate" to cleanMissed
            )
        )
        activeUid()?.let { uid ->
            RealtimeDatabaseSync.pushUserFields(
                uid,
                mapOf(
                    "completedCallTemplate" to cleanCompleted,
                    "missedCallTemplate" to cleanMissed,
                    "updatedAtMs" to System.currentTimeMillis()
                )
            )
        }
    }

    fun setRecordWhatsappCalls(enabled: Boolean) {
        prefs.recordWhatsappCalls = enabled
        pushFieldsAsync(mapOf("recordWhatsappCalls" to enabled))
        activeUid()?.let { uid ->
            RealtimeDatabaseSync.pushUserFields(
                uid,
                mapOf(
                    "recordWhatsappCalls" to enabled,
                    "updatedAtMs" to System.currentTimeMillis()
                )
            )
        }
    }

    fun setRecordWhatsappBusinessCalls(enabled: Boolean) {
        prefs.recordWhatsappBusinessCalls = enabled
        pushFieldsAsync(mapOf("recordWhatsappBusinessCalls" to enabled))
        activeUid()?.let { uid ->
            RealtimeDatabaseSync.pushUserFields(
                uid,
                mapOf(
                    "recordWhatsappBusinessCalls" to enabled,
                    "updatedAtMs" to System.currentTimeMillis()
                )
            )
        }
    }

    fun setRecordMeetCalls(enabled: Boolean) {
        prefs.recordMeetCalls = enabled
        pushFieldsAsync(mapOf("recordMeetCalls" to enabled))
        activeUid()?.let { uid ->
            RealtimeDatabaseSync.pushUserFields(
                uid,
                mapOf(
                    "recordMeetCalls" to enabled,
                    "updatedAtMs" to System.currentTimeMillis()
                )
            )
        }
    }

    fun setRecordTelegramCalls(enabled: Boolean) {
        prefs.recordTelegramCalls = enabled
        pushFieldsAsync(mapOf("recordTelegramCalls" to enabled))
        activeUid()?.let { uid ->
            RealtimeDatabaseSync.pushUserFields(
                uid,
                mapOf(
                    "recordTelegramCalls" to enabled,
                    "updatedAtMs" to System.currentTimeMillis()
                )
            )
        }
    }

    suspend fun syncAllLocalPreferencesToCloud(): Result<Unit> {
        val firestore = db
            ?: return Result.failure(IllegalStateException("FirebaseFirestore is not initialized."))
        val uid = activeUid()
            ?: return Result.failure(IllegalStateException("User must be signed in before syncing settings."))
        val themeStr = if (prefs.displayThemeMode == AppCompatDelegate.MODE_NIGHT_YES) {
            UserEntitlement.THEME_DARK
        } else {
            UserEntitlement.THEME_LIGHT
        }
        val payload = mapOf(
            "autoCallRecording" to prefs.autoCallRecording,
            "themeMode" to themeStr,
            "dialpadTonesEnabled" to prefs.dialpadTonesEnabled,
            "blockedNumbers" to prefs.getBlockedNumbers().map { it.take(40) }.take(500),
            "quickDeclineResponses" to prefs.getQuickDeclineResponses().map { it.take(200) }.take(50),
            "announcerEnabled" to prefs.announcerEnabled,
            "announcerHeadsetOnly" to prefs.announcerHeadsetOnly,
            "unknownShieldMode" to prefs.unknownShieldMode,
            "autoSmsFollowUp" to prefs.autoSmsFollowUp,
            "autoWhatsappFollowUp" to prefs.autoWhatsappFollowUp,
            "followUpTrigger" to prefs.followUpTrigger,
            "completedCallTemplate" to prefs.completedCallTemplate,
            "missedCallTemplate" to prefs.missedCallTemplate,
            "recordWhatsappCalls" to prefs.recordWhatsappCalls,
            "recordWhatsappBusinessCalls" to prefs.recordWhatsappBusinessCalls,
            "recordMeetCalls" to prefs.recordMeetCalls,
            "recordTelegramCalls" to prefs.recordTelegramCalls,
            "updatedAt" to FieldValue.serverTimestamp()
        )
        RealtimeDatabaseSync.pushUserFields(
            uid,
            mapOf(
                "autoRecordEnabled" to prefs.autoCallRecording,
                "autoCallRecording" to prefs.autoCallRecording,
                "themeMode" to themeStr,
                "dialpadTonesEnabled" to prefs.dialpadTonesEnabled,
                "blockedNumbers" to prefs.getBlockedNumbers().map { it.take(40) }.take(500),
                "quickDeclineResponses" to prefs.getQuickDeclineResponses().map { it.take(200) }.take(50),
                "announcerEnabled" to prefs.announcerEnabled,
                "announcerHeadsetOnly" to prefs.announcerHeadsetOnly,
                "unknownShieldMode" to prefs.unknownShieldMode,
                "autoSmsFollowUp" to prefs.autoSmsFollowUp,
                "autoWhatsappFollowUp" to prefs.autoWhatsappFollowUp,
                "followUpTrigger" to prefs.followUpTrigger,
                "completedCallTemplate" to prefs.completedCallTemplate,
                "missedCallTemplate" to prefs.missedCallTemplate,
                "recordWhatsappCalls" to prefs.recordWhatsappCalls,
                "recordWhatsappBusinessCalls" to prefs.recordWhatsappBusinessCalls,
                "recordMeetCalls" to prefs.recordMeetCalls,
                "recordTelegramCalls" to prefs.recordTelegramCalls,
                "updatedAtMs" to System.currentTimeMillis()
            )
        )
        if (runCatching { auth?.currentUser }.getOrNull() == null) {
            return Result.success(Unit)
        }
        val docRef = firestore.collection(DeviceTrialRepository.COLLECTION_USERS).document(uid)
        return try {
            docRef.update(payload).awaitTask()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path, auth ?: Firebase.auth)
            Result.failure(e)
        }
    }

    private fun pushFieldsAsync(fields: Map<String, Any>) {
        val firestore = db ?: return
        val firebaseUser = runCatching { auth?.currentUser }.getOrNull() ?: return
        val uid = firebaseUser.uid.takeIf { it.isNotBlank() } ?: return
        val payload = fields + ("updatedAt" to FieldValue.serverTimestamp())
        ioScope.launch {
            runCatching {
                firestore.collection(DeviceTrialRepository.COLLECTION_USERS)
                    .document(uid)
                    .update(payload)
                    .awaitTask()
            }
        }
    }

    companion object {
        private fun resolveFirestoreOrNull(context: Context): FirebaseFirestore? {
            val initialized = runCatching { FirebaseApp.getApps(context).isNotEmpty() }.getOrDefault(false)
            if (!initialized) return null
            val dbId = runCatching { context.getString(R.string.firestore_database_id) }.getOrNull() ?: return null
            return runCatching { FirebaseFirestore.getInstance(dbId) }.getOrNull()
        }

        private fun resolveAuthOrNull(context: Context): FirebaseAuth? {
            val initialized = runCatching { FirebaseApp.getApps(context).isNotEmpty() }.getOrDefault(false)
            if (!initialized) return null
            return runCatching { Firebase.auth }.getOrNull()
        }

        /**
         * Synchronizes incoming real-time Firestore `/users/{uid}` snapshot preferences
         * into local [DialerPreferences] while respecting admin locks (`forceThemeMode` and
         * `lockedByAdmin` items).
         */
        fun applyCloudUserSettings(
            snapshot: DocumentSnapshot?,
            user: UserEntitlement?,
            prefs: DialerPreferences
        ) {
            if (snapshot == null || !snapshot.exists() || user == null) return

            if (snapshot.contains("autoCallRecording") || snapshot.contains("autoRecordEnabled")) {
                val allowed = prefs.allowCallRecordingConfig
                prefs.autoCallRecording = allowed && user.autoCallRecording
            }

            if (snapshot.contains("dialpadTonesEnabled")) {
                prefs.dialpadTonesEnabled = user.dialpadTonesEnabled
            }

            if (snapshot.contains("themeMode") &&
                prefs.configuredForceThemeMode == AppConfig.THEME_USER
            ) {
                val desiredMode = if (user.themeMode == UserEntitlement.THEME_DARK) {
                    AppCompatDelegate.MODE_NIGHT_YES
                } else {
                    AppCompatDelegate.MODE_NIGHT_NO
                }
                if (prefs.displayThemeMode != desiredMode) {
                    prefs.displayThemeMode = desiredMode
                    if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper() &&
                        AppCompatDelegate.getDefaultNightMode() != desiredMode
                    ) {
                        AppCompatDelegate.setDefaultNightMode(desiredMode)
                    }
                }
            }

            if (snapshot.contains("blockedNumbers")) {
                prefs.setBlockedNumbers(user.blockedNumbers)
            }

            if (snapshot.contains("quickDeclineResponses") && user.quickDeclineResponses.isNotEmpty()) {
                prefs.setQuickDeclineResponses(user.quickDeclineResponses)
            }

            if (snapshot.contains("announcerEnabled")) {
                prefs.announcerEnabled = user.announcerEnabled
            }
            if (snapshot.contains("announcerHeadsetOnly")) {
                prefs.announcerHeadsetOnly = user.announcerHeadsetOnly
            }
            if (snapshot.contains("unknownShieldMode")) {
                prefs.unknownShieldMode = user.unknownShieldMode
            }
            if (snapshot.contains("autoSmsFollowUp")) {
                prefs.autoSmsFollowUp = user.autoSmsFollowUp
            }
            if (snapshot.contains("autoWhatsappFollowUp")) {
                prefs.autoWhatsappFollowUp = user.autoWhatsappFollowUp
            }
            if (snapshot.contains("followUpTrigger")) {
                prefs.followUpTrigger = user.followUpTrigger
            }
            if (snapshot.contains("completedCallTemplate")) {
                prefs.completedCallTemplate = user.completedCallTemplate
            }
            if (snapshot.contains("missedCallTemplate")) {
                prefs.missedCallTemplate = user.missedCallTemplate
            }
            if (snapshot.contains("recordWhatsappCalls")) {
                prefs.recordWhatsappCalls = user.recordWhatsappCalls
            }
            if (snapshot.contains("recordWhatsappBusinessCalls")) {
                prefs.recordWhatsappBusinessCalls = user.recordWhatsappBusinessCalls
            }
            if (snapshot.contains("recordMeetCalls")) {
                prefs.recordMeetCalls = user.recordMeetCalls
            }
            if (snapshot.contains("recordTelegramCalls")) {
                prefs.recordTelegramCalls = user.recordTelegramCalls
            }
        }

        /**
         * Synchronizes incoming real-time RTDB `/users/{uid}` preferences into local [DialerPreferences].
         */
        fun applyRtdbUserEntitlement(
            user: UserEntitlement,
            prefs: DialerPreferences,
            hasBlockedNumbersField: Boolean = false
        ) {
            val allowed = prefs.allowCallRecordingConfig
            prefs.autoCallRecording = allowed && user.autoCallRecording
            prefs.dialpadTonesEnabled = user.dialpadTonesEnabled
            if (hasBlockedNumbersField) {
                prefs.setBlockedNumbers(user.blockedNumbers)
            }
            if (user.quickDeclineResponses.isNotEmpty()) {
                prefs.setQuickDeclineResponses(user.quickDeclineResponses)
            }
            prefs.announcerEnabled = user.announcerEnabled
            prefs.announcerHeadsetOnly = user.announcerHeadsetOnly
            prefs.unknownShieldMode = user.unknownShieldMode
            prefs.autoSmsFollowUp = user.autoSmsFollowUp
            prefs.autoWhatsappFollowUp = user.autoWhatsappFollowUp
            prefs.followUpTrigger = user.followUpTrigger
            prefs.completedCallTemplate = user.completedCallTemplate
            prefs.missedCallTemplate = user.missedCallTemplate
            prefs.recordWhatsappCalls = user.recordWhatsappCalls
            prefs.recordWhatsappBusinessCalls = user.recordWhatsappBusinessCalls
            prefs.recordMeetCalls = user.recordMeetCalls
            prefs.recordTelegramCalls = user.recordTelegramCalls
            if (prefs.configuredForceThemeMode == AppConfig.THEME_USER) {
                val desiredMode = if (user.themeMode == UserEntitlement.THEME_DARK) {
                    AppCompatDelegate.MODE_NIGHT_YES
                } else {
                    AppCompatDelegate.MODE_NIGHT_NO
                }
                if (prefs.displayThemeMode != desiredMode) {
                    prefs.displayThemeMode = desiredMode
                    if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper() &&
                        AppCompatDelegate.getDefaultNightMode() != desiredMode
                    ) {
                        AppCompatDelegate.setDefaultNightMode(desiredMode)
                    }
                }
            }
        }

        fun themeModeToString(nightMode: Int): String =
            if (nightMode == AppCompatDelegate.MODE_NIGHT_YES) {
                UserEntitlement.THEME_DARK
            } else {
                UserEntitlement.THEME_LIGHT
            }

        fun stringToThemeMode(themeMode: String): Int =
            if (themeMode == UserEntitlement.THEME_DARK) {
                AppCompatDelegate.MODE_NIGHT_YES
            } else {
                AppCompatDelegate.MODE_NIGHT_NO
            }
    }
}
