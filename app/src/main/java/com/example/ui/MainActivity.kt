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
import android.telecom.TelecomManager
import android.view.Menu
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
import com.example.ui.history.HistoryFragment
import com.example.ui.settings.SettingsFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: DialerPreferences
    private lateinit var subscriptionManager: SubscriptionManager
    private lateinit var credentialManager: CredentialManager
    private lateinit var paymentRepository: PaymentRepository
    private lateinit var adminRepository: AdminRepository

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
    private var bannerDismissedTextKey: String? = null
    private var lastTriggeredAppOpenAdKey: String? = null

    private val authStateListener = FirebaseAuth.AuthStateListener { _ ->
        if (!isFinishing && !isDestroyed) {
            val signedIn = GoogleAuthHelper.isUserAuthenticated(this)
            if (lastRenderedAuthState != signedIn) {
                renderAuthState(signedIn)
            }
        }
    }



    private val requiredPermissions: Array<String>
        get() = buildList {
            add(Manifest.permission.CALL_PHONE)
            add(Manifest.permission.READ_CALL_LOG)
            add(Manifest.permission.WRITE_CALL_LOG)
            add(Manifest.permission.READ_CONTACTS)
            add(Manifest.permission.WRITE_CONTACTS)
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
                add(Manifest.permission.READ_MEDIA_AUDIO)
            } else {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }.toTypedArray()

    private val accountPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val accountName = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
            if (!accountName.isNullOrBlank()) {
                val displayName = accountName.substringBefore("@")
                    .replace(".", " ")
                    .replace("_", " ")
                    .trim()
                    .split(" ")
                    .filter { it.isNotBlank() }
                    .joinToString(" ") { part ->
                        part.replaceFirstChar { ch -> ch.uppercase() }
                    }
                GoogleAuthHelper.completeFallbackLogin(
                    context = this,
                    emailOrPhone = accountName,
                    displayName = displayName
                )
                setAuthLoading(false, null)
                lastRenderedAuthState = null
                renderAuthState(true)
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
        refreshCurrentFragment()
    }

    private val defaultDialerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        refreshCurrentFragment()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = DialerPreferences(this)
        subscriptionManager = SubscriptionManager(this)
        subscriptionManager.ensureFirstLaunchTracked()
        credentialManager = CredentialManager.create(this)
        paymentRepository = PaymentRepository(this)
        adminRepository = AdminRepository(this)

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

        setupBottomNavigation()
        setupDialpadFab()
        com.example.billing.SubscriptionEnforcementService.init(this)
        RealtimeSyncManager.init(this)
        observeRealtimeSync()
        renderAuthState(GoogleAuthHelper.isUserAuthenticated(this))
        handleIncomingIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        Firebase.auth.addAuthStateListener(authStateListener)
    }

    override fun onResume() {
        super.onResume()
        if (GoogleAuthHelper.isUserAuthenticated(this)) {
            RealtimeSyncManager.refreshFromCloudNow()
            enforcePaywallIfExpired()
        }
    }

    override fun onStop() {
        super.onStop()
        Firebase.auth.removeAuthStateListener(authStateListener)
    }

    override fun onDestroy() {
        activeBroadcastDialog?.dismiss()
        activeBroadcastDialog = null
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
                    runCatching {
                        accountPickerLauncher.launch(GoogleAuthHelper.createChooseGoogleAccountIntent())
                    }.onFailure {
                        setAuthLoading(false, getString(R.string.auth_no_google_account))
                        runCatching {
                            startActivity(Intent(android.provider.Settings.ACTION_ADD_ACCOUNT).apply {
                                putExtra(android.provider.Settings.EXTRA_ACCOUNT_TYPES, arrayOf("com.google"))
                            })
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
        val isAdmin = UpiPaymentHelper.isAdminEmail(GoogleAuthHelper.resolveActiveEmail(this))
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
            activeBroadcastDialog?.dismiss()
            activeBroadcastDialog = null
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
            com.example.billing.SubscriptionEnforcementService.startMonitoringUser(uid, this)
            RealtimeSyncManager.startForUser(uid)
        }
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

                            updateBlockedOverlayState()
                            if (enfState.shouldRestrictUiToAccountTab || isAppLockedByTrialExpiry()) {
                                (supportFragmentManager.findFragmentByTag(DialpadBottomSheetFragment.TAG)
                                    as? DialpadBottomSheetFragment)?.dismissAllowingStateLoss()
                                enforcePaywallIfExpired()
                            } else {
                                updateFabVisibility(activeTabId)
                            }
                            refreshCurrentFragment()
                        }
                }
                launch {
                    com.example.billing.SubscriptionEnforcementService.restrictionEvents.collect { event ->
                        when (event) {
                            is com.example.billing.SubscriptionRestrictionEvent.TrialExpiredRedirectToPayment -> {
                                enforcePaywallIfExpired()
                            }
                            is com.example.billing.SubscriptionRestrictionEvent.RecordingRestricted -> {
                                enforcePaywallIfExpired()
                            }
                            com.example.billing.SubscriptionRestrictionEvent.AccountBlockedByAdmin -> {
                                updateBlockedOverlayState()
                            }
                            com.example.billing.SubscriptionRestrictionEvent.SubscriptionUnlocked -> {
                                updateBlockedOverlayState()
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
                        delay(1000L)
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
        dialogBinding.tvPopupTitle.text = broadcast.title.ifBlank { getString(R.string.custom_tab_default_title) }
        dialogBinding.tvPopupMessage.text = broadcast.message

        if (broadcast.imageUrl.isNotBlank()) {
            dialogBinding.cardPopupImageWrapper.visibility = View.VISIBLE
            dialogBinding.ivPopupImage.load(broadcast.imageUrl) { crossfade(true) }
        } else {
            dialogBinding.cardPopupImageWrapper.visibility = View.GONE
        }

        val hasAction = broadcast.actionUrl.isNotBlank()
        dialogBinding.btnPopupAction.visibility = if (hasAction || !broadcast.isCancelable) View.VISIBLE else View.GONE
        dialogBinding.btnPopupAction.text = broadcast.buttonText.ifBlank { getString(R.string.custom_tab_open_link) }
        dialogBinding.btnPopupClose.visibility = if (broadcast.isCancelable) View.VISIBLE else View.GONE

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .setCancelable(broadcast.isCancelable)
            .create()

        dialogBinding.btnPopupClose.setOnClickListener {
            prefs.markPopupDismissed(popupId)
            dialog.dismiss()
        }
        dialogBinding.btnPopupAction.setOnClickListener {
            if (hasAction) {
                openSafeExternalUrl(broadcast.actionUrl)
            }
            if (broadcast.isCancelable) {
                prefs.markPopupDismissed(popupId)
                dialog.dismiss()
            }
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
        ViewCompat.setOnApplyWindowInsetsListener(binding.appBarLayout) { view, insets ->
            val topInset = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.updatePadding(top = topInset)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.bottomNavigation) { view, insets ->
            val bottomInset = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            view.updatePadding(bottom = bottomInset)
            insets
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
            val email = GoogleAuthHelper.resolveActiveEmail(this)
            if (UpiPaymentHelper.isAdminEmail(email)) {
                startActivity(AdminPanelActivity.createIntent(this))
            } else {
                Toast.makeText(
                    this,
                    getString(R.string.admin_access_denied, UpiPaymentHelper.ADMIN_EMAIL),
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
        return prefs.isPremiumUser || subscriptionManager.isPremiumActive()
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
        val isAdmin = UpiPaymentHelper.isAdminEmail(GoogleAuthHelper.resolveActiveEmail(this))
        if (subscriptionManager.isMaintenanceLocked(isAdmin)) return true
        return !subscriptionManager.isSubscriptionActive()
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

        MaterialAlertDialogBuilder(this)
            .setTitle(titleRes)
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton(R.string.trial_expired_dialog_upgrade) { _, _ ->
                navigateToAccountTab()
            }
            .show()
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
