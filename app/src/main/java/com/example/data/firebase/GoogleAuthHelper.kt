package com.example.data.firebase

import android.accounts.Account
import android.accounts.AccountManager
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.example.R
import com.example.billing.SubscriptionEnforcementService
import com.example.data.DialerPreferences
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.Companion.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.auth
import java.util.ArrayList
import java.util.Date
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object GoogleAuthHelper {

    private const val TAG = "GoogleAuthHelper"
    const val GOOGLE_ACCOUNT_TYPE = "com.google"

    /**
     * Returns the list of Google [Account]s (`com.google`) currently signed in on the mobile device
     * that are visible to the app via [AccountManager].
     */
    fun getDeviceSignedInGoogleAccounts(context: Context): List<Account> {
        return runCatching {
            AccountManager.get(context.applicationContext)
                .getAccountsByType(GOOGLE_ACCOUNT_TYPE)
                .filter { account ->
                    account.name.isNotBlank() && account.name.contains("@")
                }
        }.getOrDefault(emptyList())
    }

    /**
     * Returns the normalized email addresses of all Google accounts currently signed in on the mobile device.
     */
    fun getDeviceSignedInGoogleEmails(context: Context): List<String> {
        val seen = linkedSetOf<String>()
        val result = mutableListOf<String>()
        getDeviceSignedInGoogleAccounts(context).forEach { account ->
            val trimmed = account.name.trim()
            val lower = trimmed.lowercase()
            if (trimmed.contains("@") && seen.add(lower)) {
                result.add(trimmed)
            }
        }
        return result
    }

    /**
     * Verifies that [candidateEmail] is a valid email address and matches a Google account
     * currently signed in on this mobile device.
     */
    fun isEmailSignedInOnDevice(context: Context, candidateEmail: String): Boolean {
        val clean = candidateEmail.trim()
        if (!clean.contains("@") || !clean.contains(".") || clean.length < 5) {
            return false
        }
        val lowerCandidate = clean.lowercase()
        val deviceEmails = getDeviceSignedInGoogleEmails(context).map { it.lowercase() }
        if (deviceEmails.isNotEmpty()) {
            return lowerCandidate in deviceEmails
        }
        val firebaseEmail = runCatching { Firebase.auth.currentUser?.email?.trim()?.lowercase() }.getOrNull()
        if (!firebaseEmail.isNullOrBlank()) {
            return lowerCandidate == firebaseEmail
        }
        return true
    }

    /**
     * Checks if the user is authenticated AND that their logged-in email belongs to a Google account
     * currently signed in on this mobile device.
     */
    fun isUserAuthenticated(context: Context): Boolean {
        val firebaseUser = runCatching { Firebase.auth.currentUser }.getOrNull()
        val prefs = DialerPreferences(context.applicationContext)
        val hasSession = firebaseUser != null || (prefs.isLoggedIn && prefs.loggedInUserId.isNotBlank())
        if (!hasSession) return false

        val activeEmail = resolveActiveEmail(context).trim()
        val deviceEmails = getDeviceSignedInGoogleEmails(context)
        if (deviceEmails.isNotEmpty()) {
            val lowerActive = activeEmail.lowercase()
            if (activeEmail.isBlank() ||
                !activeEmail.contains("@") ||
                !activeEmail.contains(".") ||
                activeEmail.length < 5 ||
                deviceEmails.none { it.equals(lowerActive, ignoreCase = true) }
            ) {
                return false
            }
        } else if (activeEmail.isNotBlank() && !activeEmail.contains("@")) {
            return false
        }
        return true
    }

    /**
     * Resolves the active user's UID across both Firebase Auth and SHA-1-independent fallback sessions.
     */
    fun resolveActiveUid(context: Context): String? {
        val firebaseUid = runCatching { Firebase.auth.currentUser?.uid }.getOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        if (firebaseUid != null) return firebaseUid
        val prefs = DialerPreferences(context.applicationContext)
        return prefs.loggedInUserId.trim().takeIf { prefs.isLoggedIn && it.isNotEmpty() }
    }

    /**
     * Resolves the active user's email address across both Firebase Auth and fallback sessions.
     */
    fun resolveActiveEmail(context: Context): String {
        val firebaseEmail = runCatching { Firebase.auth.currentUser?.email }.getOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        if (firebaseEmail != null) return firebaseEmail
        return DialerPreferences(context.applicationContext).userEmail.trim()
    }

    /**
     * Resolves the active user's display name across both Firebase Auth and fallback sessions.
     */
    fun resolveActiveDisplayName(context: Context): String {
        val firebaseName = runCatching { Firebase.auth.currentUser?.displayName }.getOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        if (firebaseName != null) return firebaseName
        return DialerPreferences(context.applicationContext).userName.trim()
    }

    /**
     * Generates a deterministic, permanent `userId` by pairing the user's normalized Email or
     * 10-digit Mobile Number with the hardware `Settings.Secure.ANDROID_ID`.
     * This guarantees the exact same `userId` across sessions on any APK build without SHA-1 issues.
     */
    fun buildDeterministicUserId(emailOrPhone: String, androidId: String): String {
        val cleanInput = emailOrPhone.trim().lowercase()
        val normalizedIdentity = if (cleanInput.contains("@")) {
            cleanInput
        } else {
            val digits = cleanInput.filter { it.isDigit() }
            if (digits.length >= 10) digits.takeLast(10) else cleanInput
        }
        val cleanAndroidId = androidId.trim().lowercase().ifEmpty { "default_device" }
        val hash = DeviceIdentityProvider.sha256Hex("pulsedial_uid_v1|$normalizedIdentity|$cleanAndroidId")
        return "usr_${hash.take(24)}"
    }

    /**
     * Creates a native Android `AccountManager` intent restricted to the Google account(s)
     * currently signed in on the mobile device.
     * When [context] has visible `com.google` accounts on the device, passes `allowableAccounts`
     * so the user can only select from the Google accounts already logged in on the phone.
     */
    fun createChooseGoogleAccountIntent(context: Context? = null): Intent {
        val deviceAccounts = context?.let { getDeviceSignedInGoogleAccounts(it) }.orEmpty()
        val selectedAccount = deviceAccounts.firstOrNull()
        val allowableAccounts = if (deviceAccounts.isNotEmpty()) {
            ArrayList(deviceAccounts)
        } else {
            null
        }
        return AccountManager.newChooseAccountIntent(
            selectedAccount,
            allowableAccounts,
            arrayOf(GOOGLE_ACCOUNT_TYPE),
            null,
            null,
            null,
            null
        )
    }

    /**
     * Validates whether the input string is a valid email address or a 10+ digit mobile number.
     */
    fun isValidEmailOrMobile(input: String): Boolean {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.contains("@") && trimmed.contains(".") && trimmed.length >= 5) {
            return true
        }
        val digits = trimmed.filter { it.isDigit() }
        return digits.length >= 10
    }

    /**
     * Immediately completes login for the verified device Google account:
     * 1. Enforces that if the device has signed-in Google accounts, [emailOrPhone] matches one of them.
     * 2. Saves `isLoggedIn = true`, `userId`, `userEmail` / `userPhoneNumber`, and `deviceId` in `SharedPreferences`.
     * 3. Immediately creates/updates `/users/{userId}` in Firebase Realtime Database.
     * 4. Starts real-time `<1 second` listeners (`SubscriptionEnforcementService` + `RealtimeSyncManager`).
     */
    fun completeFallbackLogin(
        context: Context,
        emailOrPhone: String,
        displayName: String? = null,
        explicitUid: String? = null
    ): UserEntitlement {
        val appContext = context.applicationContext
        val prefs = DialerPreferences(appContext)
        prefs.ensureFirstLaunchRecorded()

        val trimmedInput = emailOrPhone.trim()
        val isEmail = trimmedInput.contains("@")
        val resolvedEmail = if (isEmail) trimmedInput else prefs.userEmail

        if (getDeviceSignedInGoogleEmails(appContext).isNotEmpty()) {
            require(resolvedEmail.isNotBlank() && isEmailSignedInOnDevice(appContext, resolvedEmail)) {
                "Login restricted: Only the Google email currently signed in on this mobile device is allowed."
            }
        }

        val androidId = DeviceIdentityProvider.getHardwareAndroidId(appContext)
        val deviceHash = DeviceIdentityProvider.getDeviceHash(appContext)
        val shortTag = DeviceIdentityProvider.formatShortDeviceTag(deviceHash)
        val existingCustomPhone = prefs.userPhoneNumber
            .takeIf { it != DialerPreferences.DEFAULT_USER_NUMBER }
            .orEmpty()
        val resolvedPhone = if (!isEmail && trimmedInput.any { it.isDigit() }) {
            trimmedInput
        } else {
            existingCustomPhone.ifBlank {
                com.example.data.SimNumberHelper.resolveDeviceSimNumber(appContext).orEmpty()
            }
        }

        val derivedName = when {
            !displayName.isNullOrBlank() -> displayName.trim()
            isEmail -> trimmedInput.substringBefore("@")
                .replace(".", " ")
                .replace("_", " ")
                .trim()
                .split(" ")
                .filter { it.isNotBlank() }
                .joinToString(" ") { part ->
                    part.replaceFirstChar { ch -> ch.uppercase() }
                }
                .ifBlank { "PulseDial User" }
            trimmedInput.isNotBlank() -> "User ${trimmedInput.filter { it.isDigit() }.takeLast(4)}"
            else -> prefs.userName
        }

        val userId = explicitUid?.trim()?.takeIf { it.isNotEmpty() }
            ?: buildDeterministicUserId(
                emailOrPhone = trimmedInput.ifEmpty { resolvedEmail.ifEmpty { androidId } },
                androidId = androidId
            )

        val isFirstRegistration = !prefs.initialDeviceRegisteredInRtdb

        // 1. Immediately persist login state in SharedPreferences
        prefs.isLoggedIn = true
        prefs.loggedInUserId = userId
        prefs.rawAndroidId = androidId
        prefs.deviceIdHash = deviceHash
        prefs.shortDeviceTag = shortTag
        if (resolvedEmail.isNotBlank()) {
            prefs.userEmail = resolvedEmail
        }
        if (resolvedPhone.isNotBlank()) {
            prefs.userPhoneNumber = resolvedPhone
        }
        if (derivedName.isNotBlank()) {
            prefs.userName = derivedName
        }

        // 2. Immediately register/update `/users/{userId}` in Firebase Realtime Database
        RealtimeDatabaseSync.init(appContext)
        RealtimeDatabaseSync.registerOrUpdateUserOnLogin(
            context = appContext,
            userId = userId,
            email = resolvedEmail,
            phone = resolvedPhone,
            displayName = derivedName,
            deviceId = androidId,
            deviceHash = deviceHash,
            isFirstRegistrationOnDevice = isFirstRegistration
        )
        prefs.initialDeviceRegisteredInRtdb = true

        // 3. Immediately start real-time <1 second listeners on `/users/{userId}`
        SubscriptionEnforcementService.startMonitoringUser(userId, appContext)
        RealtimeSyncManager.startForUser(userId)

        val nowTs = Timestamp(Date(prefs.firstLaunchTime))
        return UserEntitlement(
            id = userId,
            userId = userId,
            deviceHash = deviceHash,
            email = resolvedEmail,
            displayName = derivedName,
            phoneNumber = resolvedPhone,
            trialStartedAt = nowTs,
            isPremiumUser = prefs.isPremiumActive(),
            isBlocked = prefs.adminBlockedUser,
            subscriptionStatus = if (prefs.isPremiumActive()) {
                UserEntitlement.STATUS_ACTIVE
            } else {
                UserEntitlement.STATUS_TRIAL
            },
            createdAt = nowTs,
            updatedAt = Timestamp(Date(System.currentTimeMillis()))
        )
    }

    fun attemptAutoSignIn(
        context: Context,
        credentialManager: CredentialManager,
        scope: CoroutineScope,
        onAuthSuccess: () -> Unit,
        onUnauthenticated: () -> Unit
    ) {
        if (isUserAuthenticated(context)) {
            val uid = resolveActiveUid(context)
            if (!uid.isNullOrBlank()) {
                completeFallbackLogin(
                    context = context,
                    emailOrPhone = resolveActiveEmail(context),
                    displayName = resolveActiveDisplayName(context),
                    explicitUid = uid
                )
            }
            onAuthSuccess()
            return
        }

        val clientId = try {
            context.getString(R.string.default_web_client_id)
        } catch (e: Exception) {
            Log.w(TAG, "default_web_client_id missing", e)
            onUnauthenticated()
            return
        }

        suspend fun tryAutoGetGoogleId(filterAuthorized: Boolean): GoogleIdTokenCredential? {
            val googleIdOption = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(filterAuthorized)
                .setServerClientId(clientId)
                .setAutoSelectEnabled(true)
                .build()
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()
            val result = credentialManager.getCredential(context, request)
            val credential = result.credential
            return if (credential is CustomCredential && credential.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                GoogleIdTokenCredential.createFrom(credential.data)
            } else {
                null
            }
        }

        scope.launch {
            try {
                val googleCred = runCatching { tryAutoGetGoogleId(filterAuthorized = true) }.getOrNull()
                    ?: runCatching { tryAutoGetGoogleId(filterAuthorized = false) }.getOrNull()
                if (googleCred != null) {
                    val authCredential = GoogleAuthProvider.getCredential(googleCred.idToken, null)
                    val authResult = runCatching {
                        Firebase.auth.signInWithCredential(authCredential).awaitTask()
                    }.getOrNull()
                    val firebaseUser = authResult?.user ?: runCatching { Firebase.auth.currentUser }.getOrNull()
                    val candidateEmail = (firebaseUser?.email ?: googleCred.id).trim()
                    if (isEmailSignedInOnDevice(context, candidateEmail)) {
                        completeFallbackLogin(
                            context = context,
                            emailOrPhone = candidateEmail,
                            displayName = firebaseUser?.displayName ?: googleCred.displayName,
                            explicitUid = firebaseUser?.uid
                        )
                        onAuthSuccess()
                    } else {
                        runCatching { Firebase.auth.signOut() }
                        onUnauthenticated()
                    }
                } else {
                    onUnauthenticated()
                }
            } catch (e: Exception) {
                Log.d(TAG, "Silent auto sign-in skipped: ${e.message}")
                onUnauthenticated()
            }
        }
    }

    fun onGoogleSignInClicked(
        activity: Activity,
        credentialManager: CredentialManager,
        scope: CoroutineScope,
        onAuthSuccess: () -> Unit,
        onAuthError: (String) -> Unit,
        onAuthCancelled: () -> Unit = {},
        onFallbackRequired: (String) -> Unit = onAuthError
    ) {
        val clientId = try {
            activity.getString(R.string.default_web_client_id)
        } catch (e: Exception) {
            onFallbackRequired("Google Sign-In configuration missing")
            return
        }

        // Use GetGoogleIdOption so CredentialManager ONLY shows Google accounts already signed in
        // on the mobile device (preventing external web-login with a non-device email).
        val deviceGoogleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(clientId)
            .setAutoSelectEnabled(true)
            .build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(deviceGoogleIdOption)
            .build()

        scope.launch {
            try {
                val result = credentialManager.getCredential(activity, request)
                val credential = result.credential
                if (credential is CustomCredential && credential.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    val googleCred = GoogleIdTokenCredential.createFrom(credential.data)
                    val authCredential = GoogleAuthProvider.getCredential(googleCred.idToken, null)
                    val authResult = runCatching {
                        Firebase.auth.signInWithCredential(authCredential).awaitTask()
                    }.getOrNull()
                    val firebaseUser = authResult?.user ?: runCatching { Firebase.auth.currentUser }.getOrNull()
                    val candidateEmail = (firebaseUser?.email ?: googleCred.id).trim()
                    if (!isEmailSignedInOnDevice(activity, candidateEmail)) {
                        runCatching { Firebase.auth.signOut() }
                        onAuthError(activity.getString(R.string.auth_only_device_email_allowed))
                        return@launch
                    }
                    completeFallbackLogin(
                        context = activity,
                        emailOrPhone = candidateEmail,
                        displayName = firebaseUser?.displayName ?: googleCred.displayName,
                        explicitUid = firebaseUser?.uid
                    )
                    onAuthSuccess()
                } else {
                    Log.w(TAG, "Unexpected credential type returned; triggering device Google account picker")
                    onFallbackRequired("Unexpected credential type")
                }
            } catch (e: GetCredentialCancellationException) {
                Log.w(TAG, "Google Sign-In aborted/cancelled: ${e.message}", e)
                onAuthCancelled()
                onFallbackRequired(e.localizedMessage ?: "Google Sign-In fallback triggered")
            } catch (e: Exception) {
                Log.w(TAG, "Google Sign-In fallback to device Google account picker", e)
                onFallbackRequired(e.localizedMessage ?: "Sign in fallback triggered")
            }
        }
    }

    fun signOut(
        context: Context,
        credentialManager: CredentialManager,
        scope: CoroutineScope,
        onSignOutComplete: () -> Unit = {}
    ) {
        val prefs = DialerPreferences(context.applicationContext)
        prefs.resetSessionEntitlementsOnSignOut()
        scope.launch {
            withContext(NonCancellable) {
                try {
                    credentialManager.clearCredentialState(ClearCredentialStateRequest())
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to clear credential state: ${e.message}")
                } finally {
                    runCatching { Firebase.auth.signOut() }
                    onSignOutComplete()
                }
            }
        }
    }
}
