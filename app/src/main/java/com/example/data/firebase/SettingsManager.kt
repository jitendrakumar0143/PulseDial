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

    fun observeUserSettings(): Flow<UserEntitlement?> = flow {
        val firestore = db
            ?: throw IllegalStateException("FirebaseFirestore is not initialized.")
        val uid = auth?.currentUser?.uid
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
        prefs.autoCallRecording = enabled
        pushFieldsAsync(mapOf("autoCallRecording" to enabled))
    }

    fun setThemeMode(nightMode: Int) {
        prefs.displayThemeMode = nightMode
        if (AppCompatDelegate.getDefaultNightMode() != nightMode) {
            AppCompatDelegate.setDefaultNightMode(nightMode)
        }
        val themeStr = if (nightMode == AppCompatDelegate.MODE_NIGHT_YES) {
            UserEntitlement.THEME_DARK
        } else {
            UserEntitlement.THEME_LIGHT
        }
        pushFieldsAsync(mapOf("themeMode" to themeStr))
    }

    fun setDialpadTonesEnabled(enabled: Boolean) {
        prefs.dialpadTonesEnabled = enabled
        pushFieldsAsync(mapOf("dialpadTonesEnabled" to enabled))
    }

    fun addBlockedNumber(number: String) {
        prefs.addBlockedNumber(number)
        pushFieldsAsync(mapOf("blockedNumbers" to prefs.getBlockedNumbers().take(500)))
    }

    fun removeBlockedNumber(number: String) {
        prefs.removeBlockedNumber(number)
        pushFieldsAsync(mapOf("blockedNumbers" to prefs.getBlockedNumbers().take(500)))
    }

    fun addQuickDeclineResponse(message: String) {
        prefs.addQuickDeclineResponse(message)
        pushFieldsAsync(
            mapOf(
                "quickDeclineResponses" to prefs.getQuickDeclineResponses()
                    .map { it.take(200) }
                    .take(50)
            )
        )
    }

    fun removeQuickDeclineResponse(message: String) {
        prefs.removeQuickDeclineResponse(message)
        pushFieldsAsync(
            mapOf(
                "quickDeclineResponses" to prefs.getQuickDeclineResponses()
                    .map { it.take(200) }
                    .take(50)
            )
        )
    }

    suspend fun syncAllLocalPreferencesToCloud(): Result<Unit> {
        val firestore = db
            ?: return Result.failure(IllegalStateException("FirebaseFirestore is not initialized."))
        val uid = auth?.currentUser?.uid
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
            "updatedAt" to FieldValue.serverTimestamp()
        )
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
        val uid = auth?.currentUser?.uid ?: return
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

            if (snapshot.contains("autoCallRecording")) {
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
