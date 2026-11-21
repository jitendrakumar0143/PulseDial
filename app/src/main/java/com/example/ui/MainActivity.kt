package com.example.ui

import android.Manifest
import android.accounts.AccountManager
import android.app.Activity
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.TelecomManager
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.credentials.CredentialManager
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import coil.load
import com.example.R
import com.example.billing.SubscriptionManager
import com.example.billing.UpiPaymentHelper
import com.example.data.DialerPreferences
import com.example.data.SecurityIntegrityGuard
import com.example.data.SimNumberHelper
import com.example.data.firebase.AdminRepository
import com.example.data.firebase.CustomPopupAd
import com.example.data.firebase.DeviceIdentityProvider
import com.example.data.firebase.GoogleAuthHelper
import com.example.data.firebase.LiveBroadcast
import com.example.data.firebase.NavigationTabItem
import com.example.data.firebase.PaymentRepository
import com.example.data.firebase.RealtimeSyncEvent
import com.example.data.firebase.RealtimeSyncManager
import com.example.databinding.ActivityMainBinding
import com.example.databinding.DialogLiveBroadcastBinding
import com.example.ui.account.AccountFragment
import com.example.ui.admin.AdminPanelActivity
import com.example.ui.admin.HiddenAdminTapDetector
import com.example.ui.ads.CustomAdPopupDialog
import com.example.ui.contacts.ContactsFragment
import com.example.ui.custom.CustomAnnouncementFragment
import com.example.ui.dialpad.DialpadBottomSheetFragment
import com.example.ui.favorites.FavoritesFragment
import com.example.telecom.CallManager
import com.example.telecom.CallUiState
import com.example.ui.history.HistoryFragment
import com.example.ui.incall.Floating3DCallPillView
import com.example.ui.incall.InCallActivity
import com.example.ui.settings.SettingsFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: DialerPreferences
    private lateinit var subscriptionManager: SubscriptionManager
    private lateinit var credentialManager: CredentialManager

    private val hiddenAdminTapDetector = HiddenAdminTapDetector()

    private var activeTabId: Int = R.id.nav_history
    private var trialDialogShownThisSession = false
    private var attemptedSilentSignIn = false
    private var lastSyncedUid: String? = null
    private var lastRenderedAuthState: Boolean? = null

    private var visibleTabsSignature: String = ""
    private val menuIdToTabConfig = linkedMapOf<Int, NavigationTabItem>()
    private var activeBroadcastDialog: AlertDialog? = null
    private var activeBroadcastPopupId: String? = null
    private var activeTrialExpiredDialog: AlertDialog? = null
    private var bannerDismissedTextKey: String? = null
    private var lastTriggeredAppOpenAdKey: String? = null
    private var lastAppliedChromeKey: String? = null

    private val authStateListener = FirebaseAuth.AuthStateListener { _ ->
        if (!isFinishing && !isDestroyed) {
            val signedIn = GoogleAuthHelper.isUserAuthenticated(this)
            if (lastRenderedAuthState != signedIn) {
                renderAuthState(signedIn)
            }
            val pendingAdTs = prefs.pendingAfterCallAdTimestampMs
            if (signedIn && pendingAdTs > 0L) {
                prefs.pendingAfterCallAdTimestampMs = 0L
                val isFresh = (System.currentTimeMillis() - pendingAdTs) <= 10L * 60L * 1000L
                if (isFresh) {
                    maybeShowCustomPopupAd(
                        RealtimeSyncManager.syncState.value.customPopupAd,
                        com.example.data.firebase.CustomPopupAd.TRIGGER_AFTER_CALL_END
                    )
                }
            }
        }
    }



    private var lastObservedUiRestrictionKey: Triple<Boolean, Boolean, Boolean>? = null

    private val requiredPermissions: Array<String>
        get() = buildList {
            add(Manifest.permission.CALL_PHONE)
            add(Manifest.permission.READ_CALL_LOG)
            add(Manifest.permission.WRITE_CALL_LOG)
            add(Manifest.permission.READ_CONTACTS)
            add(Manifest.permission.WRITE_CONTACTS)
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.READ_PHONE_STATE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                add(Manifest.permission.READ_PHONE_NUMBERS)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()

    private val accountPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val accountName = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)?.trim().orEmpty()
            val accountType = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_TYPE)?.trim()
            val isGoogleType = accountType.isNullOrBlank() || accountType == GoogleAuthHelper.GOOGLE_ACCOUNT_TYPE
            if (accountName.isNotBlank() && isGoogleType && GoogleAuthHelper.isEmailSignedInOnDevice(this, accountName)) {
                val displayName = accountName.substringBefore("@")
                    .replace(".", " ")
                    .replace("_", " ")
                    .trim()
                    .split(" ")
                    .filter { it.isNotBlank() }
                    .joinToString(" ") { part ->
                        part.replaceFirstChar { ch -> ch.uppercase() }
                    }
                runCatching {
                    GoogleAuthHelper.completeFallbackLogin(
                        context = this,
                        emailOrPhone = accountName,
                        displayName = displayName
                    )
                }.onSuccess {
                    setAuthLoading(false, null)
                    lastRenderedAuthState = null
                    renderAuthState(true)
                }.onFailure {
                    setAuthLoading(false, getString(R.string.auth_only_device_email_allowed))
                }
            } else if (accountName.isNotBlank()) {
                setAuthLoading(false, getString(R.string.auth_only_device_email_allowed))
            } else {
                setAuthLoading(false, getString(R.string.auth_picker_cancelled))
            }
        } else {
            setAuthLoading(false, getString(R.string.auth_picker_cancelled))
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        triggerSimAutoFillInBackground()
        refreshAuthGateDeviceAccountButton()
        refreshCurrentFragment()
    }

    private val defaultDialerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        refreshCurrentFragment()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        com.example.ui.theme.ThemeStudioManager.applyActivityTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = DialerPreferences(this)
        subscriptionManager = SubscriptionManager(this)
        subscriptionManager.ensureFirstLaunchTracked()
        credentialManager = CredentialManager.create(this)

        applyEdgeToEdgeInsets()
        setupAuthGate()

        if (savedInstanceState != null) {
            activeTabId = savedInstanceState.getInt(KEY_ACTIVE_TAB, R.id.nav_history)
            trialDialogShownThisSession = savedInstanceState.getBoolean(KEY_TRIAL_PROMPT_SHOWN, false)
            attemptedSilentSignIn = savedInstanceState.getBoolean(KEY_SILENT_SIGN_IN_ATTEMPTED, false)
            lastTriggeredAppOpenAdKey = savedInstanceState.getString(KEY_LAST_APP_OPEN_AD_KEY)
        } else if (intent?.getBooleanExtra(EXTRA_OPEN_ACCOUNT_TAB, false) == true) {
            activeTabId = R.id.nav_account
        }

        supportFragmentManager.registerFragmentLifecycleCallbacks(
            object : androidx.fragment.app.FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentViewCreated(
                    fm: androidx.fragment.app.FragmentManager,
                    f: Fragment,
                    v: View,
                    savedInstanceState: Bundle?
                ) {
                    com.example.ui.theme.ThemeStudioManager.applyFullApp3DThemeToViewHierarchy(v, prefs)
                }

                override fun onFragmentResumed(
                    fm: androidx.fragment.app.FragmentManager,
                    f: Fragment
                ) {
                    com.example.ui.theme.ThemeStudioManager.applyFullApp3DThemeToViewHierarchy(f.view, prefs)
                }
            },
            true
        )

        setupBottomNavigation()
        setupDialpadFab()
        refreshPremiumMainChrome()
        observeRealtimeSync()
        setupFloatingCallPillOverlay()
        renderAuthState(GoogleAuthHelper.isUserAuthenticated(this))
        handleIncomingIntent(intent)
        binding.root.post {
            if (!isFinishing && !isDestroyed) {
                com.example.billing.SubscriptionEnforcementService.init(this)
                RealtimeSyncManager.init(this)
                runSecurityIntegrityCheckInBackground()
            }
        }
    }

    fun refreshPremiumMainChrome(force: Boolean = false) {
        val isPremium = com.example.ui.theme.ThemeStudioManager.isUserEntitledToPremiumThemes(prefs)
        val palette = com.example.ui.theme.ThemeStudioManager.resolveEffectivePalette(prefs)
        val nightMode = com.example.ui.theme.ThemeStudioManager.resolveEffectiveNightMode(prefs)
        val chromeKey = "$isPremium|${palette.id}|$nightMode"
        if (force || lastAppliedChromeKey != chromeKey) {
            lastAppliedChromeKey = chromeKey
            com.example.ui.theme.ThemeStudioManager.applyPremiumMainActivityChrome(
                context = this,
                prefs = prefs,
                vipAccentBar = binding.viewVip3dTopAccentBar,
                vipBadgeView = binding.tvVipProHeaderBadge,
                fabDialpad = binding.fabDialpad,
                bottomNav = binding.bottomNavigation,
                mainRoot = binding.mainRoot,
                appBarLayout = binding.appBarLayout,
                topAppBar = binding.topAppBar
            )
        }
        val currentFragView = supportFragmentManager.findFragmentById(R.id.nav_host_container)?.view
        if (force || lastAppliedChromeKey == chromeKey) {
            if (force) {
                com.example.ui.theme.ThemeStudioManager.invalidateHierarchyThemeTag(currentFragView)
            }
            com.example.ui.theme.ThemeStudioManager.applyFullApp3DThemeToViewHierarchy(currentFragView, prefs)
        }
    }

    override fun onStart() {
        super.onStart()
        runCatching { Firebase.auth.addAuthStateListener(authStateListener) }
    }

    override fun onResume() {
        super.onResume()
        if (!CallManager.uiState.value.hasActiveCall && CallManager.getTrackedCallsCount() == 0) {
            com.example.telecom.CustomInCallService.cancelCallNotification(this)
        }
        refreshPremiumMainChrome()
        val isAuthenticated = GoogleAuthHelper.isUserAuthenticated(this)
        if (isAuthenticated) {
            binding.root.post {
                if (!isFinishing && !isDestroyed) {
                    RealtimeSyncManager.refreshFromCloudNow()
                }
            }
            triggerSimAutoFillInBackground()
            runSecurityIntegrityCheckInBackground()
            enforcePaywallIfExpired()
        } else {
            refreshAuthGateDeviceAccountButton()
            if (lastRenderedAuthState == true) {
                renderAuthState(false)
            }
        }
    }

    private fun runSecurityIntegrityCheckInBackground() {
        lifecycleScope.launch(Dispatchers.IO) {
            val report = SecurityIntegrityGuard.verifyRuntimeIntegrity(this@MainActivity, prefs)
            if (!report.isClean) {
                withContext(Dispatchers.Main) {
                    if (!isFinishing && !isDestroyed) {
                        updateBlockedOverlayState()
                        enforcePaywallIfExpired()
                    }
                }
            }
        }
    }

    private fun triggerSimAutoFillInBackground() {
        lifecycleScope.launch(Dispatchers.IO) {
            val filled = SimNumberHelper.autoFillAndSyncSimNumber(this@MainActivity)
            if (!filled.isNullOrBlank()) {
                withContext(Dispatchers.Main) {
                    if (!isFinishing && !isDestroyed) {
                        val current = supportFragmentManager.findFragmentById(R.id.nav_host_container)
                        if (current is AccountFragment) {
                            current.refreshTrialUi()
                        }
                    }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        runCatching { Firebase.auth.removeAuthStateListener(authStateListener) }
    }

    override fun onDestroy() {
        if (::binding.isInitialized) {
            binding.floating3dCallPill.hideImmediately()
        }
        runCatching { activeBroadcastDialog?.dismiss() }
        activeBroadcastDialog = null
        runCatching { activeTrialExpiredDialog?.dismiss() }
        activeTrialExpiredDialog = null
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_ACTIVE_TAB, activeTabId)
        outState.putBoolean(KEY_TRIAL_PROMPT_SHOWN, trialDialogShownThisSession)
        outState.putBoolean(KEY_SILENT_SIGN_IN_ATTEMPTED, attemptedSilentSignIn)
        outState.putString(KEY_LAST_APP_OPEN_AD_KEY, lastTriggeredAppOpenAdKey)
    }

    private fun refreshAuthGateDeviceAccountButton() {
        if (!::binding.isInitialized) return
        lifecycleScope.launch {
            val deviceEmails = withContext(Dispatchers.IO) {
                GoogleAuthHelper.getDeviceSignedInGoogleEmails(this@MainActivity)
            }
            if (isFinishing || isDestroyed || !::binding.isInitialized) return@launch
            binding.btnGoogleSignIn.text = if (deviceEmails.size == 1) {
                getString(R.string.auth_gate_btn_google_with_email, deviceEmails.first())
            } else {
                getString(R.string.auth_gate_btn_google)
            }
        }
    }

    private fun setupAuthGate() {
        binding.btnGoogleSignIn.setOnClickListener {
            setAuthLoading(true, null)
            GoogleAuthHelper.onGoogleSignInClicked(
                activity = this,
                credentialManager = credentialManager,
                scope = lifecycleScope,
                onAuthSuccess = {
                    setAuthLoading(false, null)
                    lastRenderedAuthState = null
                    renderAuthState(true)
                },
                onAuthError = { msg ->
                    setAuthLoading(false, msg)
                },
                onAuthCancelled = {
                    setAuthLoading(false, null)
                },
                onFallbackRequired = { _ ->
                    setAuthLoading(false, null)
                    val deviceEmails = GoogleAuthHelper.getDeviceSignedInGoogleEmails(this)
                    if (deviceEmails.size == 1) {
                        val singleDeviceEmail = deviceEmails.first()
                        runCatching {
                            GoogleAuthHelper.completeFallbackLogin(
                                context = this,
                                emailOrPhone = singleDeviceEmail
                            )
                        }.onSuccess {
                            setAuthLoading(false, null)
                            lastRenderedAuthState = null
                            renderAuthState(true)
                        }.onFailure {
                            setAuthLoading(false, getString(R.string.auth_only_device_email_allowed))
                        }
                    } else {
                        runCatching {
                            accountPickerLauncher.launch(GoogleAuthHelper.createChooseGoogleAccountIntent(this))
                        }.onFailure {
                            setAuthLoading(false, getString(R.string.auth_no_google_account))
                        }
                    }
                }
            )
        }
        binding.btnBlockedWhatsapp.setOnClickListener {
            val deviceTag = prefs.shortDeviceTag.ifBlank {
                DeviceIdentityProvider.formatShortDeviceTag(DeviceIdentityProvider.getDeviceHash(this))
            }
            val intent = UpiPaymentHelper.buildWhatsAppSupportIntent(
                userName = prefs.userName,
                userEmail = GoogleAuthHelper.resolveActiveEmail(this),
                deviceTag = deviceTag,
                supportNumberRaw = prefs.configuredWhatsappNumber
            )
            runCatching { startActivity(intent) }.onFailure {
                Toast.makeText(this, R.string.whatsapp_unavailable, Toast.LENGTH_SHORT).show()
            }
        }
        binding.btnBlockedSignOut.setOnClickListener {
            signOutUser()
        }
    }

    private fun updateBlockedOverlayState() {
        val isSignedIn = GoogleAuthHelper.isUserAuthenticated(this)
        val isAdmin = UpiPaymentHelper.isAuthorizedAdmin(this, prefs)
        val isBlocked = isSignedIn && !isAdmin && (
            prefs.adminBlockedUser ||
                com.example.billing.SubscriptionEnforcementService.state.value.isBlocked ||
                RealtimeSyncManager.syncState.value.userEntitlement?.isBlocked == true
            )
        if (isBlocked) {
            binding.tvBlockedOverlayMessage.text = getString(
                R.string.blocked_overlay_message,
                prefs.configuredWhatsappNumber
            )
            binding.blockedOverlayContainer.visibility = View.VISIBLE
            (supportFragmentManager.findFragmentByTag(DialpadBottomSheetFragment.TAG)
                as? DialpadBottomSheetFragment)?.dismissAllowingStateLoss()
        } else {
            binding.blockedOverlayContainer.visibility = View.GONE
        }
    }

    private fun setAuthLoading(loading: Boolean, errorMsg: String?) {
        binding.btnGoogleSignIn.isEnabled = !loading
        binding.progressAuthGate.visibility = if (loading) View.VISIBLE else View.GONE
        binding.tvAuthError.visibility = if (errorMsg.isNullOrBlank()) View.GONE else View.VISIBLE
        binding.tvAuthError.text = errorMsg.orEmpty()
    }

    private fun renderAuthState(isSignedIn: Boolean) {
        if (lastRenderedAuthState == isSignedIn) return
        lastRenderedAuthState = isSignedIn

        if (!isSignedIn) {
            lastSyncedUid = null
            com.example.billing.SubscriptionEnforcementService.stopMonitoring()
            RealtimeSyncManager.stop()
            runCatching { activeBroadcastDialog?.dismiss() }
            activeBroadcastDialog = null
            runCatching { activeTrialExpiredDialog?.dismiss() }
            activeTrialExpiredDialog = null
            binding.blockedOverlayContainer.visibility = View.GONE
            binding.cardLiveBannerAd.visibility = View.GONE
            binding.authGateContainer.visibility = View.VISIBLE
            binding.mainContentContainer.visibility = View.GONE
            binding.bottomNavigation.visibility = View.GONE
            if (binding.fabDialpad.isLaidOut) {
                binding.fabDialpad.hide()
            } else {
                binding.fabDialpad.visibility = View.GONE
            }
            binding.topAppBar.title = getString(R.string.app_name)
            refreshAuthGateDeviceAccountButton()

            if (!attemptedSilentSignIn) {
                attemptedSilentSignIn = true
                binding.root.post {
                    if (isFinishing || isDestroyed || GoogleAuthHelper.isUserAuthenticated(this)) return@post
                    setAuthLoading(true, null)
                    GoogleAuthHelper.attemptAutoSignIn(
                        context = this,
                        credentialManager = credentialManager,
                        scope = lifecycleScope,
                        onAuthSuccess = {
                            setAuthLoading(false, null)
                            lastRenderedAuthState = null
                            renderAuthState(true)
                        },
                        onUnauthenticated = {
                            setAuthLoading(false, null)
                        }
                    )
                }
            }
            return
        }

        binding.authGateContainer.visibility = View.GONE
        binding.mainContentContainer.visibility = View.VISIBLE
        binding.bottomNavigation.visibility = View.VISIBLE
        updateBlockedOverlayState()

        applyDynamicBottomNavigation(RealtimeSyncManager.syncState.value.navigationTabs)
        if (isAppLockedByTrialExpiry()) {
            activeTabId = R.id.nav_account
            if (binding.bottomNavigation.menu.findItem(R.id.nav_account) != null &&
                binding.bottomNavigation.selectedItemId != R.id.nav_account
            ) {
                binding.bottomNavigation.selectedItemId = R.id.nav_account
            }
            if (supportFragmentManager.findFragmentById(R.id.nav_host_container) !is AccountFragment) {
                switchFragment(R.id.nav_account, getString(R.string.nav_account))
            }
        } else if (supportFragmentManager.findFragmentById(R.id.nav_host_container) == null) {
            switchFragment(activeTabId, titleForTab(activeTabId))
        }
        updateFabVisibility(activeTabId)
        GoogleAuthHelper.resolveActiveUid(this)?.let { uid ->
            lastSyncedUid = uid
            binding.root.post {
                if (!isFinishing && !isDestroyed && lastSyncedUid == uid) {
                    com.example.billing.SubscriptionEnforcementService.startMonitoringUser(uid, this)
                    RealtimeSyncManager.startForUser(uid)
                }
            }
        }
        triggerSimAutoFillInBackground()
    }

    private fun observeRealtimeSync() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    com.example.billing.SubscriptionEnforcementService.state
                        .map { it.copy(lastEvaluatedAtMs = 0L) }
                        .distinctUntilChanged()
                        .collect { enfState ->
                            if (!GoogleAuthHelper.isUserAuthenticated(this@MainActivity)) return@collect
                            if (enfState.uid == null && enfState.installDateMs == 0L) return@collect

                            val restrictionKey = Triple(
                                enfState.shouldRestrictUiToAccountTab,
                                enfState.isBlocked,
                                enfState.isPremium
                            )
                            val restrictionChanged = lastObservedUiRestrictionKey != restrictionKey
                            lastObservedUiRestrictionKey = restrictionKey

                            updateBlockedOverlayState()
                            refreshPremiumMainChrome()
                            if (isAppLockedByTrialExpiry()) {
                                (supportFragmentManager.findFragmentByTag(DialpadBottomSheetFragment.TAG)
                                    as? DialpadBottomSheetFragment)?.dismissAllowingStateLoss()
                                enforcePaywallIfExpired()
                            } else {
                                updateFabVisibility(activeTabId)
                                if (restrictionChanged) {
                                    refreshCurrentFragment()
                                }
                            }
                        }
                }
                launch {
                    com.example.billing.SubscriptionEnforcementService.restrictionEvents.collect { event ->
                        when (event) {
                            is com.example.billing.SubscriptionRestrictionEvent.TrialExpiredRedirectToPayment -> {
                                refreshPremiumMainChrome()
                                refreshCurrentFragment()
                            }
                            is com.example.billing.SubscriptionRestrictionEvent.RecordingRestricted -> {
                                refreshPremiumMainChrome()
                                refreshCurrentFragment()
                            }
                            com.example.billing.SubscriptionRestrictionEvent.AccountBlockedByAdmin -> {
                                updateBlockedOverlayState()
                                refreshPremiumMainChrome()
                            }
                            com.example.billing.SubscriptionRestrictionEvent.SubscriptionUnlocked -> {
                                com.example.ui.theme.ThemeStudioManager.enforceEffectiveNightMode(prefs)
                                updateBlockedOverlayState()
                                refreshPremiumMainChrome()
                                updateFabVisibility(activeTabId)
                                refreshCurrentFragment()
                            }
                            com.example.billing.SubscriptionRestrictionEvent.UserDeletedByAdmin -> {
                                com.example.ui.theme.ThemeStudioManager.enforceEffectiveNightMode(prefs)
                                updateBlockedOverlayState()
                                refreshPremiumMainChrome()
                                updateFabVisibility(activeTabId)
                                refreshCurrentFragment()
                            }
                        }
                    }
                }
                launch {
                    RealtimeSyncManager.syncState.collect { state ->
                        if (state.uid == null || !GoogleAuthHelper.isUserAuthenticated(this@MainActivity)) return@collect
                        if (state.version == 0L) return@collect

                        updateBlockedOverlayState()
                        refreshPremiumMainChrome()
                        applyDynamicBottomNavigation(state.navigationTabs)
                        applyLiveBroadcastState(state.liveBroadcast)
                        maybeShowCustomPopupAd(state.customPopupAd, CustomPopupAd.TRIGGER_ON_APP_OPEN)

                        if (isAppLockedByTrialExpiry()) {
                            (supportFragmentManager.findFragmentByTag(DialpadBottomSheetFragment.TAG)
                                as? DialpadBottomSheetFragment)?.dismissAllowingStateLoss()
                            enforcePaywallIfExpired()
                        } else {
                            updateFabVisibility(activeTabId)
                        }
                    }
                }
                launch {
                    RealtimeSyncManager.events.collect { event ->
                        when (event) {
                            RealtimeSyncEvent.PaymentApproved -> {
                                com.example.ui.theme.ThemeStudioManager.enforceEffectiveNightMode(prefs)
                                updateBlockedOverlayState()
                                refreshPremiumMainChrome()
                                refreshCurrentFragment()
                                Toast.makeText(
                                    this@MainActivity,
                                    R.string.realtime_payment_approved_toast,
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                            is RealtimeSyncEvent.PaymentDisapproved -> {
                                Toast.makeText(
                                    this@MainActivity,
                                    getString(R.string.realtime_payment_disapproved_toast, event.reason),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                            RealtimeSyncEvent.AccountBlocked -> {
                                updateBlockedOverlayState()
                                Toast.makeText(
                                    this@MainActivity,
                                    R.string.realtime_account_blocked_toast,
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                            RealtimeSyncEvent.AccountUnblocked -> {
                                updateBlockedOverlayState()
                                Toast.makeText(
                                    this@MainActivity,
                                    R.string.realtime_account_unblocked_toast,
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                            RealtimeSyncEvent.AccountDeletedByAdmin -> {
                                com.example.ui.theme.ThemeStudioManager.enforceEffectiveNightMode(prefs)
                                updateBlockedOverlayState()
                                refreshPremiumMainChrome()
                                refreshCurrentFragment()
                                Toast.makeText(
                                    this@MainActivity,
                                    R.string.realtime_account_deleted_toast,
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                            RealtimeSyncEvent.RecordingDisabledByAdmin -> {
                                Toast.makeText(
                                    this@MainActivity,
                                    R.string.setting_recording_disabled_admin,
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                            RealtimeSyncEvent.GlobalSettingsUpdated -> {
                                refreshCurrentFragment()
                            }
                            is RealtimeSyncEvent.LiveBroadcastTriggered -> {
                                applyLiveBroadcastState(event.broadcast)
                            }
                        }
                    }
                }
                launch {
                    while (isActive) {
                        if (GoogleAuthHelper.isUserAuthenticated(this@MainActivity) &&
                            activeTabId != R.id.nav_account &&
                            isAppLockedByTrialExpiry()
                        ) {
                            (supportFragmentManager.findFragmentByTag(DialpadBottomSheetFragment.TAG)
                                as? DialpadBottomSheetFragment)?.dismissAllowingStateLoss()
                            enforcePaywallIfExpired()
                        }
                        delay(5000L)
                    }
                }
            }
        }
    }

    private fun applyDynamicBottomNavigation(tabs: List<NavigationTabItem>) {
        val visibleTabs = tabs
            .filter { it.enabled }
            .sortedBy { it.order }
            .take(5)
            .ifEmpty { NavigationTabItem.defaultTabs().take(5) }

        val signature = visibleTabs.joinToString("|") {
            "${it.id}:${it.title}:${it.iconKey}:${it.type}:${it.customTitle}:${it.customBody}:${it.customActionUrl}"
        }
        if (signature == visibleTabsSignature) return
        visibleTabsSignature = signature

        menuIdToTabConfig.clear()
        val menu = binding.bottomNavigation.menu
        menu.clear()

        visibleTabs.forEachIndexed { index, tab ->
            val menuId = menuIdForTab(tab, index)
            menuIdToTabConfig[menuId] = tab
            menu.add(Menu.NONE, menuId, index, tab.title.ifBlank { "Tab ${index + 1}" })
                .setIcon(iconResForTab(tab.iconKey, tab.type))
        }

        if (menuIdToTabConfig.containsKey(activeTabId)) {
            if (binding.bottomNavigation.selectedItemId != activeTabId) {
                binding.bottomNavigation.selectedItemId = activeTabId
            }
            binding.topAppBar.title = titleForTab(activeTabId)
            val currentFrag = supportFragmentManager.findFragmentById(R.id.nav_host_container)
            val activeTab = menuIdToTabConfig[activeTabId]
            if (currentFrag is CustomAnnouncementFragment && activeTab != null) {
                currentFrag.updateContent(
                    activeTab.customTitle.ifBlank { activeTab.title },
                    activeTab.customBody,
                    activeTab.customActionUrl
                )
            }
        } else {
            val fallbackId = if (isAppLockedByTrialExpiry()) {
                R.id.nav_account
            } else {
                menuIdToTabConfig.keys.firstOrNull() ?: R.id.nav_history
            }
            activeTabId = fallbackId
            if (menu.findItem(fallbackId) != null && binding.bottomNavigation.selectedItemId != fallbackId) {
                binding.bottomNavigation.selectedItemId = fallbackId
            }
            switchFragment(fallbackId, titleForTab(fallbackId))
            updateFabVisibility(fallbackId)
        }
    }

    private fun menuIdForTab(tab: NavigationTabItem, index: Int): Int = when (tab.type) {
        NavigationTabItem.TYPE_BUILTIN_HISTORY -> R.id.nav_history
        NavigationTabItem.TYPE_BUILTIN_FAVORITES -> R.id.nav_favorites
        NavigationTabItem.TYPE_BUILTIN_CONTACTS -> R.id.nav_contacts
        NavigationTabItem.TYPE_BUILTIN_SETTINGS -> R.id.nav_settings
        NavigationTabItem.TYPE_BUILTIN_ACCOUNT -> R.id.nav_account
        else -> Menu.FIRST + 1000 + index
    }

    private fun iconResForTab(iconKey: String, type: String): Int = when (iconKey) {
        NavigationTabItem.ICON_HISTORY -> R.drawable.ic_history
        NavigationTabItem.ICON_FAVORITES -> R.drawable.ic_star
        NavigationTabItem.ICON_CONTACTS -> R.drawable.ic_contacts
        NavigationTabItem.ICON_SETTINGS -> R.drawable.ic_settings
        NavigationTabItem.ICON_ACCOUNT -> R.drawable.ic_account
        NavigationTabItem.ICON_ANNOUNCEMENT -> R.drawable.ic_verified_shield
        else -> when (type) {
            NavigationTabItem.TYPE_BUILTIN_HISTORY -> R.drawable.ic_history
            NavigationTabItem.TYPE_BUILTIN_FAVORITES -> R.drawable.ic_star
            NavigationTabItem.TYPE_BUILTIN_CONTACTS -> R.drawable.ic_contacts
            NavigationTabItem.TYPE_BUILTIN_SETTINGS -> R.drawable.ic_settings
            NavigationTabItem.TYPE_BUILTIN_ACCOUNT -> R.drawable.ic_account
            else -> R.drawable.ic_verified_shield
        }
    }

    private fun applyLiveBroadcastState(broadcast: LiveBroadcast) {
        val currentBannerKey = "${broadcast.bannerText}|${broadcast.bannerImageUrl}|${broadcast.bannerActionUrl}"
        if (broadcast.showBannerAd &&
            (broadcast.bannerText.isNotBlank() || broadcast.bannerImageUrl.isNotBlank()) &&
            bannerDismissedTextKey != currentBannerKey
        ) {
            binding.cardLiveBannerAd.visibility = View.VISIBLE
            binding.tvLiveBannerAdText.text = broadcast.bannerText.ifBlank { getString(R.string.custom_tab_default_title) }
            if (broadcast.bannerImageUrl.isNotBlank()) {
                binding.ivLiveBannerAd.visibility = View.VISIBLE
                binding.ivLiveBannerAd.load(broadcast.bannerImageUrl) { crossfade(true) }
            } else {
                binding.ivLiveBannerAd.visibility = View.GONE
            }
            binding.cardLiveBannerAd.setOnClickListener {
                if (broadcast.bannerActionUrl.isNotBlank()) {
                    openSafeExternalUrl(broadcast.bannerActionUrl)
                }
            }
            binding.btnLiveBannerClose.setOnClickListener {
                bannerDismissedTextKey = currentBannerKey
                binding.cardLiveBannerAd.visibility = View.GONE
            }
        } else {
            binding.cardLiveBannerAd.visibility = View.GONE
        }

        if (!broadcast.showPopup) {
            if (activeBroadcastDialog?.isShowing == true) {
                activeBroadcastDialog?.dismiss()
            }
            activeBroadcastDialog = null
            activeBroadcastPopupId = null
            return
        }

        val popupId = broadcast.popupId.ifBlank { "${broadcast.title}|${broadcast.message}" }
        if (broadcast.isCancelable && prefs.isPopupDismissed(popupId)) {
            return
        }
        if (activeBroadcastDialog?.isShowing == true && activeBroadcastPopupId == popupId) {
            return
        }

        showLiveBroadcastDialog(broadcast, popupId)
    }

    private fun showLiveBroadcastDialog(broadcast: LiveBroadcast, popupId: String) {
        if (isFinishing || isDestroyed) return
        activeBroadcastDialog?.dismiss()

        val dialogBinding = DialogLiveBroadcastBinding.inflate(layoutInflater)
        val hasImage = broadcast.imageUrl.isNotBlank()
        val hasAction = broadcast.actionUrl.isNotBlank()

        if (broadcast.title.isBlank() && hasImage) {
            dialogBinding.tvPopupTitle.visibility = View.GONE
        } else {
            dialogBinding.tvPopupTitle.visibility = View.VISIBLE
            dialogBinding.tvPopupTitle.text = broadcast.title.ifBlank { getString(R.string.custom_tab_default_title) }
        }

        if (broadcast.message.isBlank()) {
            dialogBinding.tvPopupMessage.visibility = View.GONE
        } else {
            dialogBinding.tvPopupMessage.visibility = View.VISIBLE
            dialogBinding.tvPopupMessage.text = broadcast.message
        }

        if (hasImage) {
            dialogBinding.cardPopupImageWrapper.visibility = View.VISIBLE
            dialogBinding.ivPopupImage.load(broadcast.imageUrl) { crossfade(true) }
            dialogBinding.tvPopupImageLinkBadge.visibility = if (hasAction) View.VISIBLE else View.GONE
        } else {
            dialogBinding.cardPopupImageWrapper.visibility = View.GONE
            dialogBinding.tvPopupImageLinkBadge.visibility = View.GONE
        }

        dialogBinding.btnPopupAction.visibility = if (hasAction || !broadcast.isCancelable) View.VISIBLE else View.GONE
        dialogBinding.btnPopupAction.text = broadcast.buttonText.ifBlank { getString(R.string.custom_tab_open_link) }
        dialogBinding.btnPopupClose.visibility = if (broadcast.isCancelable) View.VISIBLE else View.GONE

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .setCancelable(broadcast.isCancelable)
            .create()

        val handleOpenLink: () -> Unit = {
            if (hasAction) {
                openSafeExternalUrl(broadcast.actionUrl)
            }
            if (broadcast.isCancelable) {
                prefs.markPopupDismissed(popupId)
                dialog.dismiss()
            }
        }

        if (hasImage && hasAction) {
            dialogBinding.cardPopupImageWrapper.setOnClickListener { handleOpenLink() }
            dialogBinding.ivPopupImage.setOnClickListener { handleOpenLink() }
        }

        dialogBinding.btnPopupClose.setOnClickListener {
            prefs.markPopupDismissed(popupId)
            dialog.dismiss()
        }
        dialogBinding.btnPopupAction.setOnClickListener {
            handleOpenLink()
        }
        dialog.setOnDismissListener {
            if (activeBroadcastDialog == dialog) {
                activeBroadcastDialog = null
                activeBroadcastPopupId = null
            }
        }

        activeBroadcastPopupId = popupId
        activeBroadcastDialog = dialog
        dialog.show()
    }

    private fun openSafeExternalUrl(rawUrl: String) {
        val clean = rawUrl.trim()
        if (clean.isEmpty()) return
        val normalized = if (!clean.contains("://") && !clean.startsWith("tel:") && !clean.startsWith("mailto:")) {
            "https://$clean"
        } else {
            clean
        }
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(normalized)))
        }.onFailure {
            Toast.makeText(this, R.string.broadcast_invalid_link, Toast.LENGTH_SHORT).show()
        }
    }

    fun signOutUser() {
        lastSyncedUid = null
        com.example.billing.SubscriptionEnforcementService.stopMonitoring()
        RealtimeSyncManager.stop()
        activeBroadcastDialog?.dismiss()
        activeBroadcastDialog = null
        (supportFragmentManager.findFragmentByTag(CustomAdPopupDialog.TAG) as? CustomAdPopupDialog)
            ?.dismissAllowingStateLoss()
        lastTriggeredAppOpenAdKey = null
        attemptedSilentSignIn = true
        trialDialogShownThisSession = false
        hiddenAdminTapDetector.onOtherTabSelected()
        prefs.resetSessionEntitlementsOnSignOut()
        GoogleAuthHelper.signOut(
            context = this,
            credentialManager = credentialManager,
            scope = lifecycleScope,
            onSignOutComplete = {
                lastRenderedAuthState = null
                renderAuthState(false)
            }
        )
    }

    private fun applyEdgeToEdgeInsets() {
        val extraTopPx = (6f * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(binding.mainRoot) { _, insets ->
            val topInset = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val bottomInset = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom

            if (binding.appBarLayout.paddingTop != topInset) {
                binding.appBarLayout.updatePadding(top = topInset)
            }
            val targetPillTopMargin = topInset + extraTopPx
            val pillLp = binding.floating3dCallPill.layoutParams as? ViewGroup.MarginLayoutParams
            if (pillLp != null && pillLp.topMargin != targetPillTopMargin) {
                binding.floating3dCallPill.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    topMargin = targetPillTopMargin
                }
            }
            if (binding.bottomNavigation.paddingBottom != bottomInset) {
                binding.bottomNavigation.updatePadding(bottom = bottomInset)
            }
            insets
        }
    }

    private fun setupFloatingCallPillOverlay() {
        binding.floating3dCallPill.listener = object : Floating3DCallPillView.CallPillListener {
            override fun onAnswerCall() {
                CallManager.answerCall()
            }

            override fun onDeclineCall() {
                binding.floating3dCallPill.dismissWithReverseAnimation()
                CallManager.declineOrEndCall()
            }

            override fun onToggleMute() {
                CallManager.toggleMute()
            }

            override fun onToggleSpeaker() {
                CallManager.toggleSpeaker()
            }

            override fun onEndCall() {
                binding.floating3dCallPill.dismissWithReverseAnimation()
                CallManager.declineOrEndCall()
            }

            override fun onPillTappedWhileActive() {
                runCatching {
                    val intent = Intent(this@MainActivity, InCallActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    }
                    startActivity(intent)
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    CallManager.uiState.collect { state ->
                        if (!state.hasActiveCall) {
                            if (binding.floating3dCallPill.currentMode != Floating3DCallPillView.PillMode.HIDDEN) {
                                binding.floating3dCallPill.dismissWithReverseAnimation()
                            }
                            return@collect
                        }
                        val displayName = resolveCallPillDisplayName(state)
                        val isBtActive = (state.audioRoute and CallAudioState.ROUTE_BLUETOOTH) != 0
                        if (state.callState == Call.STATE_RINGING) {
                            val loc = state.callerLocation.trim()
                            val num = state.callerNumber.trim()
                            val subtitle = when {
                                loc.isNotBlank() && num.isNotBlank() && !num.equals(loc, ignoreCase = true) -> "$loc • $num"
                                num.isNotBlank() -> num
                                loc.isNotBlank() -> loc
                                else -> getString(R.string.incall_status_ringing)
                            }
                            binding.floating3dCallPill.showIncomingCall(displayName, subtitle)
                        } else {
                            binding.floating3dCallPill.morphToActiveCallBar(
                                callerName = displayName,
                                formattedTimer = formatCallPillDuration(state),
                                isMuted = state.isMuted,
                                isSpeakerOn = state.isSpeakerOn,
                                isBluetooth = isBtActive
                            )
                        }
                    }
                }
                launch {
                    while (isActive) {
                        val state = CallManager.uiState.value
                        if (state.hasActiveCall && state.callState != Call.STATE_RINGING) {
                            val isBtActive = (state.audioRoute and CallAudioState.ROUTE_BLUETOOTH) != 0
                            binding.floating3dCallPill.updateActiveCallState(
                                callerName = resolveCallPillDisplayName(state),
                                formattedTimer = formatCallPillDuration(state),
                                isMuted = state.isMuted,
                                isSpeakerOn = state.isSpeakerOn,
                                isBluetooth = isBtActive
                            )
                        }
                        delay(1000L)
                    }
                }
            }
        }
    }

    private fun resolveCallPillDisplayName(state: CallUiState): String {
        return when {
            state.isConference && state.conferenceParticipantCount > 0 ->
                getString(R.string.incall_conference_title, state.conferenceParticipantCount)
            state.isConference ->
                getString(R.string.incall_conference_title_generic)
            else -> state.callerName.ifBlank { getString(R.string.incall_unknown_caller) }
        }
    }

    private fun formatCallPillDuration(state: CallUiState): String {
        if (state.connectTimeMillis <= 0L) {
            return getString(R.string.incall_connecting_timer)
        }
        val elapsedSeconds = ((System.currentTimeMillis() - state.connectTimeMillis) / 1000L)
            .coerceAtLeast(0L)
        val hours = elapsedSeconds / 3600L
        val mins = (elapsedSeconds % 3600L) / 60L
        val secs = elapsedSeconds % 60L
        return if (hours > 0L) {
            String.format("%02d:%02d:%02d", hours, mins, secs)
        } else {
            String.format("%02d:%02d", mins, secs)
        }
    }

    private fun setupBottomNavigation() {
        val defaults = NavigationTabItem.defaultTabs().take(5)
        menuIdToTabConfig.clear()
        val menu = binding.bottomNavigation.menu
        var xmlMatchesDefaults = menu.size() == defaults.size
        defaults.forEachIndexed { index, tab ->
            val expectedMenuId = menuIdForTab(tab, index)
            menuIdToTabConfig[expectedMenuId] = tab
            if (xmlMatchesDefaults) {
                val item = menu.getItem(index)
                if (item.itemId != expectedMenuId || item.title?.toString() != tab.title) {
                    xmlMatchesDefaults = false
                }
            }
        }
        if (xmlMatchesDefaults) {
            visibleTabsSignature = defaults.joinToString("|") {
                "${it.id}:${it.title}:${it.iconKey}:${it.type}:${it.customTitle}:${it.customBody}:${it.customActionUrl}"
            }
        }

        binding.bottomNavigation.setOnItemSelectedListener { item ->
            if (item.itemId == R.id.nav_account) {
                handleAccountTabTap()
            } else {
                hiddenAdminTapDetector.onOtherTabSelected()
            }

            if (item.itemId != R.id.nav_account && isAppLockedByTrialExpiry()) {
                Toast.makeText(this, R.string.paywall_tab_locked_toast, Toast.LENGTH_SHORT).show()
                if (binding.bottomNavigation.menu.findItem(R.id.nav_account) != null &&
                    binding.bottomNavigation.selectedItemId != R.id.nav_account
                ) {
                    binding.bottomNavigation.selectedItemId = R.id.nav_account
                } else {
                    switchFragment(R.id.nav_account, getString(R.string.nav_account))
                }
                return@setOnItemSelectedListener false
            }
            activeTabId = item.itemId
            switchFragment(item.itemId, titleForTab(item.itemId))
            updateFabVisibility(item.itemId)
            true
        }

        binding.bottomNavigation.setOnItemReselectedListener { item ->
            if (item.itemId == R.id.nav_account) {
                handleAccountTabTap()
            } else {
                hiddenAdminTapDetector.onOtherTabSelected()
            }
        }
    }

    private fun handleAccountTabTap() {
        if (hiddenAdminTapDetector.onAccountTabTapped()) {
            if (UpiPaymentHelper.isAuthorizedAdmin(this, prefs)) {
                startActivity(AdminPanelActivity.createIntent(this))
            } else {
                Toast.makeText(
                    this,
                    getString(R.string.admin_access_denied),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun updateFabVisibility(tabId: Int) {
        val shouldShow = !isAppLockedByTrialExpiry() && when (tabId) {
            R.id.nav_history, R.id.nav_favorites, R.id.nav_contacts -> true
            else -> false
        }
        if (shouldShow) {
            if (binding.fabDialpad.isLaidOut) {
                binding.fabDialpad.show()
            } else {
                binding.fabDialpad.visibility = View.VISIBLE
            }
        } else {
            if (binding.fabDialpad.isLaidOut) {
                binding.fabDialpad.hide()
            } else {
                binding.fabDialpad.visibility = View.GONE
            }
        }
    }

    private fun titleForTab(tabId: Int): String {
        val dynamic = menuIdToTabConfig[tabId]?.title
        if (!dynamic.isNullOrBlank()) return dynamic
        return when (tabId) {
            R.id.nav_history -> getString(R.string.nav_history)
            R.id.nav_favorites -> getString(R.string.nav_favorites)
            R.id.nav_contacts -> getString(R.string.nav_contacts)
            R.id.nav_settings -> getString(R.string.nav_settings)
            R.id.nav_account -> getString(R.string.nav_account)
            else -> getString(R.string.nav_history)
        }
    }

    private fun setupDialpadFab() {
        binding.fabDialpad.setOnClickListener {
            openDialpadSheet(initialNumber = "")
        }
    }

    private fun switchFragment(tabId: Int, title: String) {
        binding.topAppBar.title = title
        val tabConfig = menuIdToTabConfig[tabId]
        val fragment: Fragment = when {
            tabId == R.id.nav_history || tabConfig?.type == NavigationTabItem.TYPE_BUILTIN_HISTORY ->
                HistoryFragment()
            tabId == R.id.nav_favorites || tabConfig?.type == NavigationTabItem.TYPE_BUILTIN_FAVORITES ->
                FavoritesFragment()
            tabId == R.id.nav_contacts || tabConfig?.type == NavigationTabItem.TYPE_BUILTIN_CONTACTS ->
                ContactsFragment()
            tabId == R.id.nav_settings || tabConfig?.type == NavigationTabItem.TYPE_BUILTIN_SETTINGS ->
                SettingsFragment()
            tabId == R.id.nav_account || tabConfig?.type == NavigationTabItem.TYPE_BUILTIN_ACCOUNT ->
                AccountFragment()
            tabConfig != null -> CustomAnnouncementFragment.newInstance(
                title = tabConfig.customTitle.ifBlank { tabConfig.title },
                body = tabConfig.customBody,
                actionUrl = tabConfig.customActionUrl
            )
            else -> HistoryFragment()
        }
        supportFragmentManager.beginTransaction()
            .replace(R.id.nav_host_container, fragment, tabId.toString())
            .commitAllowingStateLoss()
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (!GoogleAuthHelper.isUserAuthenticated(this)) return
        if (intent?.getBooleanExtra(EXTRA_SHOW_AFTER_CALL_POPUP_AD, false) == true) {
            intent.removeExtra(EXTRA_SHOW_AFTER_CALL_POPUP_AD)
            val adBundle = intent.getBundleExtra(EXTRA_POPUP_AD_BUNDLE)
            intent.removeExtra(EXTRA_POPUP_AD_BUNDLE)
            val ad = if (adBundle != null) {
                CustomPopupAd.fromBundle(adBundle)
            } else {
                RealtimeSyncManager.syncState.value.customPopupAd
            }
            maybeShowCustomPopupAd(ad, CustomPopupAd.TRIGGER_AFTER_CALL_END)
        }
        if (intent?.getBooleanExtra(EXTRA_OPEN_ACCOUNT_TAB, false) == true || isAppLockedByTrialExpiry()) {
            navigateToAccountTab()
            return
        }
        val action = intent?.action ?: return
        if (action == Intent.ACTION_DIAL || action == Intent.ACTION_VIEW) {
            val number = intent.data?.schemeSpecificPart.orEmpty()
            openDialpadSheet(initialNumber = number)
        }
    }

    private fun resolveIsPremiumForAds(): Boolean {
        return subscriptionManager.isPremiumActive()
    }

    private fun maybeShowCustomPopupAd(ad: CustomPopupAd, trigger: String) {
        if (isFinishing || isDestroyed || !GoogleAuthHelper.isUserAuthenticated(this)) return
        if (prefs.adminBlockedUser) return

        val existing = supportFragmentManager.findFragmentByTag(CustomAdPopupDialog.TAG)
            as? CustomAdPopupDialog
        if (!ad.isEnabled) {
            existing?.dismissAllowingStateLoss()
            return
        }

        if (!ad.shouldDisplayFor(trigger, resolveIsPremiumForAds())) return

        if (trigger == CustomPopupAd.TRIGGER_ON_APP_OPEN) {
            val campaignKey = ad.effectiveCampaignKey()
            if (campaignKey == lastTriggeredAppOpenAdKey) return
            if (existing != null) return
            lastTriggeredAppOpenAdKey = campaignKey
        } else if (existing != null) {
            return
        }

        CustomAdPopupDialog.showIfNotAlreadyVisible(supportFragmentManager, ad)
    }

    fun navigateToAccountTab() {
        activeTabId = R.id.nav_account
        if (binding.bottomNavigation.menu.findItem(R.id.nav_account) != null) {
            binding.bottomNavigation.selectedItemId = R.id.nav_account
        } else {
            switchFragment(R.id.nav_account, getString(R.string.nav_account))
        }
        updateFabVisibility(R.id.nav_account)
    }

    fun isAppLockedByTrialExpiry(): Boolean {
        val isAdmin = UpiPaymentHelper.isAuthorizedAdmin(this, prefs)
        if (subscriptionManager.isMaintenanceLocked(isAdmin)) return true
        if (!isAdmin && (prefs.adminBlockedUser || prefs.tamperDetected)) return true
        return false
    }

    private fun enforcePaywallIfExpired() {
        if (isAppLockedByTrialExpiry()) {
            val wasNotOnAccount = activeTabId != R.id.nav_account
            navigateToAccountTab()
            if (wasNotOnAccount && !trialDialogShownThisSession && !prefs.adminBlockedUser && !prefs.maintenanceMode) {
                trialDialogShownThisSession = true
                showTrialExpiredDialog(isDeviceReused = prefs.trialBlockedByDevice)
            }
            refreshCurrentFragment()
        }
    }

    fun showTrialExpiredDialog(isDeviceReused: Boolean = prefs.trialBlockedByDevice) {
        if (isFinishing || isDestroyed) return
        runCatching { activeTrialExpiredDialog?.dismiss() }
        activeTrialExpiredDialog = null

        val deviceTag = prefs.shortDeviceTag.ifBlank {
            DeviceIdentityProvider.formatShortDeviceTag(DeviceIdentityProvider.getDeviceHash(this))
        }
        val isMonthlyExpired = subscriptionManager.hasMonthlySubscriptionExpired() &&
            !subscriptionManager.isPremiumActive()
        val titleRes = when {
            isMonthlyExpired -> R.string.paywall_locked_monthly_title
            isDeviceReused -> R.string.trial_device_used_dialog_title
            else -> R.string.trial_expired_dialog_title
        }
        val message = when {
            isMonthlyExpired -> getString(
                R.string.paywall_locked_monthly_message,
                deviceTag,
                prefs.configuredPriceInr,
                prefs.configuredUpiVpa
            )
            isDeviceReused -> getString(R.string.trial_device_used_dialog_message, deviceTag)
            else -> getString(R.string.trial_expired_dialog_message)
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(titleRes)
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton(R.string.trial_expired_dialog_upgrade) { _, _ ->
                navigateToAccountTab()
            }
            .create()
        dialog.setOnDismissListener {
            if (activeTrialExpiredDialog === dialog) {
                activeTrialExpiredDialog = null
            }
        }
        activeTrialExpiredDialog = dialog
        dialog.show()
    }

    fun openDialpadSheet(initialNumber: String = "") {
        if (isAppLockedByTrialExpiry()) {
            enforcePaywallIfExpired()
            return
        }
        if (supportFragmentManager.findFragmentByTag(DialpadBottomSheetFragment.TAG) != null) return
        DialpadBottomSheetFragment.newInstance(initialNumber)
            .show(supportFragmentManager, DialpadBottomSheetFragment.TAG)
    }

    fun requestDialerPermissions() {
        permissionLauncher.launch(requiredPermissions)
    }

    fun hasCorePermissions(): Boolean {
        return requiredPermissions.all { perm ->
            ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun requestDefaultDialerRole() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java) ?: return
            if (roleManager.isRoleAvailable(RoleManager.ROLE_DIALER) &&
                !roleManager.isRoleHeld(RoleManager.ROLE_DIALER)
            ) {
                defaultDialerLauncher.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER))
            }
        } else {
            @Suppress("DEPRECATION")
            val intent = Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER).apply {
                putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, packageName)
            }
            defaultDialerLauncher.launch(intent)
        }
    }

    private fun refreshCurrentFragment() {
        val current = supportFragmentManager.findFragmentById(R.id.nav_host_container)
        when (current) {
            is HistoryFragment -> current.reloadData()
            is FavoritesFragment -> current.reloadData()
            is ContactsFragment -> current.reloadData()
            is AccountFragment -> current.refreshTrialUi()
            is SettingsFragment -> current.refreshTrialUi()
        }
    }

    companion object {
        const val EXTRA_OPEN_ACCOUNT_TAB = "extra_open_account_tab"
        const val EXTRA_SHOW_AFTER_CALL_POPUP_AD = "extra_show_after_call_popup_ad"
        const val EXTRA_POPUP_AD_BUNDLE = "extra_popup_ad_bundle"
        private const val KEY_ACTIVE_TAB = "active_tab_id"
        private const val KEY_TRIAL_PROMPT_SHOWN = "trial_prompt_shown"
        private const val KEY_SILENT_SIGN_IN_ATTEMPTED = "silent_sign_in_attempted"
        private const val KEY_LAST_APP_OPEN_AD_KEY = "last_app_open_ad_key"
    }
}
