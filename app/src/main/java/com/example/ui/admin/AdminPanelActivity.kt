package com.example.ui.admin

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.button.MaterialButton
import com.example.R
import com.example.billing.UpiPaymentHelper
import com.example.data.DialerPreferences
import com.example.data.firebase.AdminPaymentFilter
import com.example.data.firebase.AdminRepository
import com.example.data.firebase.AdminSubscriberFilter
import com.example.data.firebase.AdminUserDateField
import com.example.data.firebase.AdminUserDateFilter
import com.example.data.firebase.AdminUserFilter
import com.example.data.firebase.AppConfig
import com.example.data.firebase.CustomPopupAd
import com.example.data.firebase.DeviceIdentityProvider
import com.example.data.firebase.DeviceTrialRepository
import com.example.data.firebase.DynamicSettingItem
import com.example.data.firebase.GoogleAuthHelper
import com.example.data.firebase.LiveBroadcast
import com.example.data.firebase.NavigationTabItem
import com.example.data.firebase.PaymentRepository
import com.example.data.firebase.PaymentRequest
import com.example.data.firebase.RealtimeDatabaseSync
import com.example.data.firebase.UserEntitlement
import coil.load
import com.example.databinding.ActivityAdminPanelBinding
import com.example.ui.account.PaymentRequestsAdapter
import androidx.core.content.ContextCompat
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.tabs.TabLayout
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

class AdminPanelActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAdminPanelBinding
    private lateinit var prefs: DialerPreferences
    private lateinit var adminRepository: AdminRepository
    private lateinit var paymentRepository: PaymentRepository

    private lateinit var usersAdapter: AdminUsersAdapter
    private lateinit var expiringUsersAdapter: AdminExpiringUsersAdapter
    private lateinit var paymentsAdapter: PaymentRequestsAdapter
    private lateinit var navTabsAdapter: AdminNavTabsAdapter
    private lateinit var settingsItemsAdapter: AdminSettingsItemsAdapter

    private var allUsers: List<UserEntitlement> = emptyList()
    private var expiringSubscriptionUsers: List<UserEntitlement> = emptyList()
    private var allActiveSubscribers: List<UserEntitlement> = emptyList()
    private var oldExpiredUsers: List<UserEntitlement> = emptyList()
    private var newTrialUsers: List<UserEntitlement> = emptyList()
    private var allPayments: List<PaymentRequest> = emptyList()
    private val firestoreUsersById = mutableMapOf<String, UserEntitlement>()
    private val rtdbUsersById = mutableMapOf<String, UserEntitlement>()
    private val firestorePaymentsById = mutableMapOf<String, PaymentRequest>()
    private val rtdbPaymentsById = mutableMapOf<String, PaymentRequest>()
    private var hasLoadedRtdbConfig = false
    private var currentConfig: AppConfig = AppConfig(id = AppConfig.DOC_GLOBAL_SETTINGS)
    private var currentNavTabs: List<NavigationTabItem> = NavigationTabItem.defaultTabs()
    private var currentSettingItems: List<DynamicSettingItem> = DynamicSettingItem.defaultItems()
    private var currentBroadcast: LiveBroadcast = LiveBroadcast()
    private var currentCustomPopupAd: CustomPopupAd = CustomPopupAd()
    private var hasBoundInitialPopupAd = false

    private var suppressHideSubSwitchListener = false

    private var currentUserFilter: AdminUserFilter = AdminUserFilter.ALL
    private var currentUserQuery: String = ""
    private var currentUserDateFilter: AdminUserDateFilter = AdminUserDateFilter.ALL_TIME
    private var currentUserDateField: AdminUserDateField = AdminUserDateField.JOIN_DATE
    private var customDateStartMs: Long = 0L
    private var customDateEndMs: Long = Long.MAX_VALUE

    private var currentSubscriberFilter: AdminSubscriberFilter = AdminSubscriberFilter.EXPIRING_SOON
    private var currentSubscriberQuery: String = ""

    private var currentPaymentFilter: AdminPaymentFilter = AdminPaymentFilter.ALL
    private var currentPaymentQuery: String = ""
    private var rtdbAdminUsersCleanup: (() -> Unit)? = null
    private var rtdbAdminConfigCleanup: (() -> Unit)? = null
    private var rtdbAdminPaymentsCleanup: (() -> Unit)? = null
    private var rtdbAdminNavTabsCleanup: (() -> Unit)? = null
    private var rtdbAdminSettingsItemsCleanup: (() -> Unit)? = null
    private var rtdbAdminBroadcastCleanup: (() -> Unit)? = null
    private var rtdbAdminCustomPopupAdCleanup: (() -> Unit)? = null

    private var rtdbApprovedUtrsCleanup: (() -> Unit)? = null
    private var approvedUtrKeys: Set<String> = emptySet()

    override fun onCreate(savedInstanceState: Bundle?) {
        com.example.ui.theme.ThemeStudioManager.applyActivityTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityAdminPanelBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = DialerPreferences(this)
        val activeEmail = GoogleAuthHelper.resolveActiveEmail(this)
        if (!GoogleAuthHelper.isUserAuthenticated(this) || !UpiPaymentHelper.isAuthorizedAdmin(this, prefs)) {
            Toast.makeText(
                this,
                getString(R.string.admin_access_denied),
                Toast.LENGTH_LONG
            ).show()
            finish()
            return
        }

        adminRepository = AdminRepository(this)
        paymentRepository = PaymentRepository(this)

        applyInsets()
        setupToolbar(activeEmail)
        setupTabs()
        setupUsersTab()
        setupExpiringTab()
        setupPaymentsTab()
        setupBroadcastTab()
        setupTabsAndUiTab()
        setupSettingsTab()
        observeAdminData()
        attachRtdbAdminListeners()
    }

    private fun attachRtdbAdminListeners() {
        rtdbAdminUsersCleanup = com.example.data.firebase.RealtimeDatabaseSync.attachAllUsersListener(
            context = this,
            onUpdate = { rtdbUsers ->
                rtdbUsersById.clear()
                rtdbUsers.forEach { user ->
                    if (user.uid.isNotBlank()) {
                        rtdbUsersById[user.uid] = user
                    }
                }
                mergeAndPublishUsers()
            }
        )
        rtdbAdminPaymentsCleanup = com.example.data.firebase.RealtimeDatabaseSync.attachAllPaymentRequestsListener(
            context = this,
            onUpdate = { rtdbRequests ->
                rtdbPaymentsById.clear()
                rtdbRequests.forEach { req ->
                    if (req.id.isNotBlank()) {
                        rtdbPaymentsById[req.id] = req
                    }
                }
                mergeAndPublishPayments()
            }
        )
        rtdbAdminConfigCleanup = com.example.data.firebase.RealtimeDatabaseSync.attachAppConfigListener(
            context = this,
            currentFallback = { currentConfig },
            onUpdate = { rtdbConfig ->
                hasLoadedRtdbConfig = true
                currentConfig = rtdbConfig
                prefs.applyAppConfig(rtdbConfig)
                usersAdapter.trialDaysConfig = rtdbConfig.trialDays.toInt()
                expiringUsersAdapter.trialDaysConfig = rtdbConfig.trialDays.toInt()
                bindSettingsInputs(rtdbConfig)
                applyUserFilters()
                applySubscriberFilters()
            }
        )
        rtdbAdminNavTabsCleanup = com.example.data.firebase.RealtimeDatabaseSync.attachNavigationTabsListener(
            context = this,
            onUpdate = { tabs ->
                if (tabs.isNotEmpty()) {
                    currentNavTabs = tabs
                    navTabsAdapter.submitList(tabs)
                }
            }
        )
        rtdbAdminSettingsItemsCleanup = com.example.data.firebase.RealtimeDatabaseSync.attachDynamicSettingsListener(
            context = this,
            onUpdate = { items ->
                if (items.isNotEmpty()) {
                    currentSettingItems = items
                    settingsItemsAdapter.submitList(items)
                }
            }
        )
        rtdbAdminBroadcastCleanup = RealtimeDatabaseSync.attachLiveBroadcastListener(
            context = this,
            onUpdate = { broadcast ->
                bindBroadcastInputs(broadcast)
            }
        )
        rtdbAdminCustomPopupAdCleanup = RealtimeDatabaseSync.attachCustomPopupAdListener(
            context = this,
            onUpdate = { ad ->
                bindCustomPopupAdState(ad)
            }
        )
        rtdbApprovedUtrsCleanup = RealtimeDatabaseSync.attachApprovedUtrsListener(
            context = this,
            onUpdate = { rtdbKeys ->
                approvedUtrKeys = rtdbKeys
                refreshApprovedUtrLocks()
            }
        )
    }

    private fun currentEffectiveApprovedUtrKeys(): Set<String> {
        val combined = HashSet<String>(approvedUtrKeys)
        allPayments.forEach { req ->
            if (req.status == PaymentRequest.STATUS_APPROVED) {
                val key = PaymentRepository.normalizeUtrKey(PaymentRepository.extractCanonicalUtr(req))
                if (key.isNotEmpty()) {
                    combined.add(key)
                }
            }
        }
        return combined
    }

    private fun refreshApprovedUtrLocks() {
        val effective = currentEffectiveApprovedUtrKeys()
        if (::usersAdapter.isInitialized) {
            usersAdapter.approvedUtrKeys = effective
        }
        if (::paymentsAdapter.isInitialized) {
            paymentsAdapter.approvedUtrKeys = effective
        }
    }

    override fun onDestroy() {
        rtdbAdminUsersCleanup?.invoke()
        rtdbAdminUsersCleanup = null
        rtdbAdminConfigCleanup?.invoke()
        rtdbAdminConfigCleanup = null
        rtdbAdminPaymentsCleanup?.invoke()
        rtdbAdminPaymentsCleanup = null
        rtdbAdminNavTabsCleanup?.invoke()
        rtdbAdminNavTabsCleanup = null
        rtdbAdminSettingsItemsCleanup?.invoke()
        rtdbAdminSettingsItemsCleanup = null
        rtdbAdminBroadcastCleanup?.invoke()
        rtdbAdminBroadcastCleanup = null
        rtdbAdminCustomPopupAdCleanup?.invoke()
        rtdbAdminCustomPopupAdCleanup = null
        rtdbApprovedUtrsCleanup?.invoke()
        rtdbApprovedUtrsCleanup = null
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (!GoogleAuthHelper.isUserAuthenticated(this) || !UpiPaymentHelper.isAuthorizedAdmin(this, prefs)) {
            Toast.makeText(this, getString(R.string.admin_access_denied), Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.adminAppBarLayout) { view, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.updatePadding(top = top)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.adminPanelRoot) { view, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            view.updatePadding(bottom = bottom)
            insets
        }
    }

    private fun setupToolbar(adminEmail: String) {
        val rolePrefix = if (UpiPaymentHelper.isSuperAdminEmail(adminEmail)) {
            getString(R.string.admin_user_badge_super_admin)
        } else {
            getString(R.string.admin_user_badge_admin)
        }
        binding.adminToolbar.subtitle = "$rolePrefix • $adminEmail"
        binding.adminToolbar.setNavigationOnClickListener { finish() }
    }

    private fun setupTabs() {
        binding.adminTabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                showSection(tab.position)
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
        binding.chipKpiExpiring.setOnClickListener {
            binding.rgAdminSubscriberFilter.check(R.id.rb_subscriber_expiring_soon)
            binding.adminTabLayout.getTabAt(1)?.select()
        }
        binding.chipKpiPremium.setOnClickListener {
            binding.rgAdminSubscriberFilter.check(R.id.rb_subscriber_all_active)
            binding.adminTabLayout.getTabAt(1)?.select()
        }
        showSection(0)
    }

    private fun showSection(position: Int) {
        binding.containerAdminUsers.visibility = if (position == 0) View.VISIBLE else View.GONE
        binding.containerAdminExpiring.visibility = if (position == 1) View.VISIBLE else View.GONE
        binding.containerAdminPayments.visibility = if (position == 2) View.VISIBLE else View.GONE
        binding.containerAdminBroadcast.visibility = if (position == 3) View.VISIBLE else View.GONE
        binding.containerAdminTabsUi.visibility = if (position == 4) View.VISIBLE else View.GONE
        binding.containerAdminSettings.visibility = if (position == 5) View.VISIBLE else View.GONE
    }

    private fun setupExpiringTab() {
        expiringUsersAdapter = AdminExpiringUsersAdapter(
            onRenewMonthClick = { user -> renewUserMonth(user) },
            onManageClick = { user -> showManageUserDialog(user) },
            onRevokeProClick = { user -> revokeUserPro(user) },
            onAdjustDaysClick = { user, deltaDays -> adjustUserDays(user, deltaDays) }
        )
        binding.recyclerAdminExpiring.layoutManager = LinearLayoutManager(this)
        binding.recyclerAdminExpiring.adapter = expiringUsersAdapter

        binding.etAdminExpiringSearch.doAfterTextChanged { editable ->
            currentSubscriberQuery = editable?.toString().orEmpty()
            applySubscriberFilters()
        }

        binding.rgAdminSubscriberFilter.setOnCheckedChangeListener { _, checkedId ->
            currentSubscriberFilter = when (checkedId) {
                R.id.rb_subscriber_all_active -> AdminSubscriberFilter.ALL_ACTIVE_SUBSCRIBERS
                R.id.rb_subscriber_old_expired -> AdminSubscriberFilter.OLD_EXPIRED_USERS
                R.id.rb_subscriber_new_trial -> AdminSubscriberFilter.NEW_TRIAL_USERS
                else -> AdminSubscriberFilter.EXPIRING_SOON
            }
            applySubscriberFilters()
        }

        binding.btnRefreshExpiring.setOnClickListener { refreshExpiringUsersFromFirestore() }
        applySubscriberFilters()
    }

    private fun refreshExpiringUsersFromFirestore() {
        binding.progressAdminExpiring.visibility = View.VISIBLE
        binding.btnRefreshExpiring.isEnabled = false
        val requestedFilter = currentSubscriberFilter
        lifecycleScope.launch {
            if (Firebase.auth.currentUser == null) {
                mergeAndPublishUsers()
                binding.progressAdminExpiring.visibility = View.GONE
                binding.btnRefreshExpiring.isEnabled = true
                return@launch
            }
            val result = when (requestedFilter) {
                AdminSubscriberFilter.ALL_ACTIVE_SUBSCRIBERS ->
                    adminRepository.fetchAllActiveSubscribers()
                AdminSubscriberFilter.EXPIRING_SOON ->
                    adminRepository.fetchUsersExpiringWithinDays(
                        days = AdminRepository.SUB_EXPIRING_SOON_DAYS
                    )
                AdminSubscriberFilter.OLD_EXPIRED_USERS,
                AdminSubscriberFilter.NEW_TRIAL_USERS -> {
                    mergeAndPublishUsers()
                    binding.progressAdminExpiring.visibility = View.GONE
                    binding.btnRefreshExpiring.isEnabled = true
                    return@launch
                }
            }
            binding.progressAdminExpiring.visibility = View.GONE
            binding.btnRefreshExpiring.isEnabled = true
            result.onSuccess { list ->
                list.forEach { user ->
                    if (user.uid.isNotBlank()) {
                        val rtdbCopy = rtdbUsersById[user.uid]
                        val fsMs = user.updatedAt?.toDate()?.time ?: 0L
                        val rtdbMs = rtdbCopy?.updatedAt?.toDate()?.time ?: 0L
                        if (rtdbCopy == null || fsMs > rtdbMs) {
                            firestoreUsersById[user.uid] = user
                        }
                    }
                }
                mergeAndPublishUsers()
            }.onFailure { err ->
                Toast.makeText(
                    this@AdminPanelActivity,
                    getString(R.string.admin_action_failed, err.localizedMessage ?: "Error"),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun mergeAndPublishUsers() {
        val mergedById = LinkedHashMap<String, UserEntitlement>()
        val allIds = LinkedHashSet<String>().apply {
            addAll(firestoreUsersById.keys)
            addAll(rtdbUsersById.keys)
        }
        for (uid in allIds) {
            val fsUser = firestoreUsersById[uid]
            val rtdbUser = rtdbUsersById[uid]
            val winner = DeviceTrialRepository.mergeEntitlementSources(fsUser, rtdbUser)
            if (winner != null) {
                mergedById[uid] = winner
            }
        }
        allUsers = mergedById.values.toList()
        val nowMs = System.currentTimeMillis()
        val trialDays = currentConfig.trialDays.toInt()
        expiringSubscriptionUsers = allUsers.filter { user ->
            user.isPremiumUser && !user.isBlocked &&
                AdminRepository.getSubscriptionRemainingDays(user, nowMs) in 1..AdminRepository.SUB_EXPIRING_SOON_DAYS
        }
        allActiveSubscribers = allUsers.filter { user ->
            user.isPremiumUser && !user.isBlocked &&
                AdminRepository.getSubscriptionRemainingDays(user, nowMs) != 0
        }
        oldExpiredUsers = allUsers.filter { user ->
            !user.isBlocked &&
                AdminRepository.resolveUserAccountStatus(user, trialDays, nowMs) ==
                com.example.data.firebase.AdminUserAccountStatus.EXPIRED
        }
        newTrialUsers = allUsers.filter { user ->
            !user.isBlocked && !user.isPremiumUser &&
                AdminRepository.resolveUserAccountStatus(user, trialDays, nowMs) !=
                com.example.data.firebase.AdminUserAccountStatus.EXPIRED
        }
        updateKpis()
        applyUserFilters()
        applySubscriberFilters()
    }

    private fun mergeAndPublishPayments() {
        val mergedById = LinkedHashMap<String, PaymentRequest>()
        val allIds = LinkedHashSet<String>().apply {
            addAll(firestorePaymentsById.keys)
            addAll(rtdbPaymentsById.keys)
        }
        for (id in allIds) {
            val fsReq = firestorePaymentsById[id]
            val rtdbReq = rtdbPaymentsById[id]
            val winner = when {
                fsReq == null -> rtdbReq
                rtdbReq == null -> fsReq
                else -> {
                    val fsMs = fsReq.updatedAt?.toDate()?.time ?: fsReq.createdAt?.toDate()?.time ?: 0L
                    val rtdbMs = rtdbReq.updatedAt?.toDate()?.time ?: rtdbReq.createdAt?.toDate()?.time ?: 0L
                    if (rtdbMs >= fsMs) rtdbReq else fsReq
                }
            }
            if (winner != null) {
                mergedById[id] = winner
            }
        }
        allPayments = mergedById.values.sortedWith(
            compareBy<PaymentRequest> {
                when (it.status) {
                    PaymentRequest.STATUS_PENDING -> 0
                    PaymentRequest.STATUS_APPROVED -> 1
                    else -> 2
                }
            }.thenByDescending { it.createdAt?.toDate()?.time ?: 0L }
        )
        refreshApprovedUtrLocks()
        updateKpis()
        applyPaymentFilters()
    }

    private fun applySubscriberFilters() {
        val trialDays = currentConfig.trialDays.toInt()
        val sourceList = when (currentSubscriberFilter) {
            AdminSubscriberFilter.ALL_ACTIVE_SUBSCRIBERS -> allActiveSubscribers
            AdminSubscriberFilter.OLD_EXPIRED_USERS -> oldExpiredUsers
            AdminSubscriberFilter.NEW_TRIAL_USERS -> newTrialUsers
            AdminSubscriberFilter.EXPIRING_SOON -> expiringSubscriptionUsers
        }
        val filtered = AdminRepository.filterSubscribers(
            subscribers = sourceList,
            filter = currentSubscriberFilter,
            query = currentSubscriberQuery,
            trialDaysConfig = trialDays
        )

        val (bgRes, strokeRes, textRes) = when (currentSubscriberFilter) {
            AdminSubscriberFilter.ALL_ACTIVE_SUBSCRIBERS -> Triple(
                R.color.md_theme_primaryContainer,
                R.color.md_theme_primary,
                R.color.md_theme_onPrimaryContainer
            )
            AdminSubscriberFilter.NEW_TRIAL_USERS -> Triple(
                R.color.md_theme_tertiaryContainer,
                R.color.md_theme_tertiary,
                R.color.md_theme_onTertiaryContainer
            )
            AdminSubscriberFilter.OLD_EXPIRED_USERS -> Triple(
                R.color.md_theme_secondaryContainer,
                R.color.md_theme_outline,
                R.color.md_theme_onSecondaryContainer
            )
            AdminSubscriberFilter.EXPIRING_SOON -> Triple(
                R.color.md_theme_errorContainer,
                R.color.md_theme_error,
                R.color.md_theme_onErrorContainer
            )
        }
        binding.cardSubscriberSummary.setCardBackgroundColor(ContextCompat.getColor(this, bgRes))
        binding.cardSubscriberSummary.strokeColor = ContextCompat.getColor(this, strokeRes)
        val bannerTextColor = ContextCompat.getColor(this, textRes)
        binding.tvSubscriberBannerTitle.setTextColor(bannerTextColor)
        binding.tvExpiringQueryCount.setTextColor(bannerTextColor)
        binding.tvSubscriberBannerDesc.setTextColor(bannerTextColor)

        val (titleRes, descRes, countRes, emptyRes) = when (currentSubscriberFilter) {
            AdminSubscriberFilter.ALL_ACTIVE_SUBSCRIBERS -> listOf(
                R.string.admin_active_banner_title,
                R.string.admin_active_banner_desc,
                R.string.admin_active_query_count,
                R.string.admin_active_empty
            )
            AdminSubscriberFilter.OLD_EXPIRED_USERS -> listOf(
                R.string.admin_old_expired_banner_title,
                R.string.admin_old_expired_banner_desc,
                R.string.admin_old_expired_query_count,
                R.string.admin_old_expired_empty
            )
            AdminSubscriberFilter.NEW_TRIAL_USERS -> listOf(
                R.string.admin_new_trial_banner_title,
                R.string.admin_new_trial_banner_desc,
                R.string.admin_new_trial_query_count,
                R.string.admin_new_trial_empty
            )
            AdminSubscriberFilter.EXPIRING_SOON -> listOf(
                R.string.admin_expiring_banner_title,
                R.string.admin_expiring_banner_desc,
                R.string.admin_expiring_query_count,
                R.string.admin_expiring_empty
            )
        }
        binding.tvSubscriberBannerTitle.text = getString(titleRes)
        binding.tvSubscriberBannerDesc.text = getString(descRes)
        binding.tvExpiringQueryCount.text = getString(countRes, filtered.size)

        binding.tvAdminExpiringEmpty.text = if (
            currentSubscriberQuery.isNotBlank() && currentSubscriberFilter == AdminSubscriberFilter.EXPIRING_SOON
        ) {
            getString(R.string.admin_expiring_search_empty)
        } else {
            getString(emptyRes)
        }
        binding.tvAdminExpiringEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        expiringUsersAdapter.submitList(filtered)
    }

    private fun revokeUserPro(user: UserEntitlement) {
        lifecycleScope.launch {
            val result = adminRepository.updateUserAdminDetails(
                user = user,
                isPremium = false,
                isBlocked = user.isBlocked,
                bonusTrialDays = user.bonusTrialDays,
                adminNote = user.adminNote,
                hideSubscriptionUI = user.hideSubscriptionUI,
                subscriptionExpiresAt = null
            )
            result.onSuccess {
                Toast.makeText(
                    this@AdminPanelActivity,
                    getString(R.string.admin_user_updated, user.displayName.ifBlank { user.email }),
                    Toast.LENGTH_SHORT
                ).show()
            }.onFailure { err ->
                Toast.makeText(
                    this@AdminPanelActivity,
                    getString(R.string.admin_action_failed, err.localizedMessage ?: "Error"),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun setupUsersTab() {
        usersAdapter = AdminUsersAdapter(
            onToggleProClick = { user -> toggleUserPro(user) },
            onRenewMonthClick = { user -> renewUserMonth(user) },
            onExtendTrialClick = { user -> adjustUserDays(user, 7L) },
            onToggleBlockClick = { user -> toggleUserBlock(user) },
            onManageClick = { user -> showManageUserDialog(user) },
            onApproveUtrClick = { user -> approveUserUtrDirect(user) },
            onRejectUtrClick = { user -> rejectUserUtrDirect(user) },
            onReduceDaysClick = { user -> adjustUserDays(user, -7L) },
            onDeleteClick = { user -> confirmDeleteUser(user) }
        )
        binding.recyclerAdminUsers.layoutManager = LinearLayoutManager(this)
        binding.recyclerAdminUsers.adapter = usersAdapter

        binding.btnAdminAddUser.setOnClickListener { showAddUserDialog() }

        binding.etAdminUserSearch.doAfterTextChanged { editable ->
            currentUserQuery = editable?.toString().orEmpty()
            applyUserFilters()
        }

        binding.chipGroupUserFilter.setOnCheckedStateChangeListener { _, checkedIds ->
            val checkedId = checkedIds.firstOrNull() ?: R.id.chip_user_filter_all
            currentUserFilter = when (checkedId) {
                R.id.chip_user_filter_expiring -> AdminUserFilter.EXPIRING_SOON
                R.id.chip_user_filter_trial -> AdminUserFilter.TRIAL_ACTIVE
                R.id.chip_user_filter_expired -> AdminUserFilter.EXPIRED
                R.id.chip_user_filter_premium -> AdminUserFilter.PREMIUM
                R.id.chip_user_filter_blocked -> AdminUserFilter.BLOCKED
                else -> AdminUserFilter.ALL
            }
            applyUserFilters()
        }

        binding.chipDateFieldToggle.setOnClickListener {
            currentUserDateField = when (currentUserDateField) {
                AdminUserDateField.JOIN_DATE -> AdminUserDateField.EXPIRY_DATE
                AdminUserDateField.EXPIRY_DATE -> AdminUserDateField.UPDATED_DATE
                AdminUserDateField.UPDATED_DATE -> AdminUserDateField.JOIN_DATE
            }
            binding.chipDateFieldToggle.text = when (currentUserDateField) {
                AdminUserDateField.JOIN_DATE -> "📅 By: Join Date"
                AdminUserDateField.EXPIRY_DATE -> "⏳ By: Expiry Date"
                AdminUserDateField.UPDATED_DATE -> "🔄 By: Last Updated"
            }
            applyUserFilters()
        }

        binding.chipGroupUserDateFilter.setOnCheckedStateChangeListener { _, checkedIds ->
            val checkedId = checkedIds.firstOrNull() ?: R.id.chip_date_filter_all
            when (checkedId) {
                R.id.chip_date_filter_today -> {
                    currentUserDateFilter = AdminUserDateFilter.TODAY
                    applyUserFilters()
                }
                R.id.chip_date_filter_7d -> {
                    currentUserDateFilter = AdminUserDateFilter.LAST_7_DAYS
                    applyUserFilters()
                }
                R.id.chip_date_filter_30d -> {
                    currentUserDateFilter = AdminUserDateFilter.LAST_30_DAYS
                    applyUserFilters()
                }
                R.id.chip_date_filter_custom -> {
                    showCustomUserDateRangePicker()
                }
                else -> {
                    currentUserDateFilter = AdminUserDateFilter.ALL_TIME
                    binding.chipDateFilterCustom.text = "Custom Date Range"
                    applyUserFilters()
                }
            }
        }
        binding.chipDateFilterCustom.setOnClickListener {
            if (currentUserDateFilter == AdminUserDateFilter.CUSTOM_RANGE) {
                showCustomUserDateRangePicker()
            }
        }
    }

    private fun showCustomUserDateRangePicker() {
        val picker = MaterialDatePicker.Builder.dateRangePicker()
            .setTitleText("Select User Filter Date Range")
            .build()
        picker.addOnPositiveButtonClickListener { range ->
            val startUtc = range.first ?: 0L
            val endUtc = (range.second ?: startUtc) + (DialerPreferences.ONE_DAY_MILLIS - 1L)
            customDateStartMs = startUtc
            customDateEndMs = endUtc
            currentUserDateFilter = AdminUserDateFilter.CUSTOM_RANGE
            val fmt = java.text.SimpleDateFormat("dd MMM", java.util.Locale.getDefault())
            binding.chipDateFilterCustom.text = "${fmt.format(java.util.Date(startUtc))} - ${fmt.format(java.util.Date(endUtc))}"
            applyUserFilters()
        }
        picker.addOnNegativeButtonClickListener {
            if (currentUserDateFilter != AdminUserDateFilter.CUSTOM_RANGE) {
                binding.chipGroupUserDateFilter.check(R.id.chip_date_filter_all)
            }
        }
        picker.show(supportFragmentManager, "admin_user_date_range")
    }

    private fun setupPaymentsTab() {
        paymentsAdapter = PaymentRequestsAdapter(
            onApproveClick = { request -> handleApprovePayment(request) },
            onRejectClick = { request -> showDisapprovePaymentDialog(request) }
        )
        binding.recyclerAdminPayments.layoutManager = LinearLayoutManager(this)
        binding.recyclerAdminPayments.adapter = paymentsAdapter

        binding.etAdminPaymentSearch.doAfterTextChanged { editable ->
            currentPaymentQuery = editable?.toString().orEmpty()
            applyPaymentFilters()
        }

        binding.chipGroupPaymentFilter.setOnCheckedStateChangeListener { _, checkedIds ->
            val checkedId = checkedIds.firstOrNull() ?: R.id.chip_pay_filter_all
            currentPaymentFilter = when (checkedId) {
                R.id.chip_pay_filter_pending -> AdminPaymentFilter.PENDING
                R.id.chip_pay_filter_approved -> AdminPaymentFilter.APPROVED
                R.id.chip_pay_filter_disapproved -> AdminPaymentFilter.DISAPPROVED
                else -> AdminPaymentFilter.ALL
            }
            applyPaymentFilters()
        }

        binding.switchAdminHideSubUi.setOnCheckedChangeListener { _, isChecked ->
            if (suppressHideSubSwitchListener) return@setOnCheckedChangeListener
            toggleGlobalHideSubscriptionUi(isChecked)
        }
    }

    private fun toggleGlobalHideSubscriptionUi(hide: Boolean) {
        val updatedConfig = currentConfig.copy(hideSubscriptionUI = hide)
        lifecycleScope.launch {
            val res = adminRepository.saveGlobalAppConfig(updatedConfig)
            res.onSuccess {
                currentConfig = updatedConfig
                prefs.applyAppConfig(updatedConfig)
                Toast.makeText(this@AdminPanelActivity, R.string.admin_settings_saved, Toast.LENGTH_SHORT).show()
            }.onFailure { err ->
                Toast.makeText(
                    this@AdminPanelActivity,
                    getString(R.string.admin_action_failed, err.localizedMessage ?: "Error"),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun setupBroadcastTab() {
        binding.etBroadcastImageUrl.doAfterTextChanged { editable ->
            updateBroadcastImagePreview(editable?.toString()?.trim().orEmpty())
        }

        binding.btnBroadcastPreview.setOnClickListener {
            val previewBroadcast = buildBroadcastFromInputs() ?: return@setOnClickListener
            showBroadcastPreviewDialog(previewBroadcast)
        }

        binding.btnBroadcastSendNow.setOnClickListener {
            val next = buildBroadcastFromInputs() ?: return@setOnClickListener
            saveLiveBroadcast(next, R.string.admin_broadcast_sent_toast)
        }

        binding.btnBroadcastStop.setOnClickListener {
            val next = currentBroadcast.copy(showPopup = false)
            saveLiveBroadcast(next, R.string.admin_broadcast_stopped_toast)
        }

        binding.btnBannerAdSave.setOnClickListener {
            val next = currentBroadcast.copy(
                showBannerAd = binding.switchBannerAdEnable.isChecked,
                bannerText = binding.etBannerAdText.text?.toString()?.trim().orEmpty(),
                bannerImageUrl = binding.etBannerAdImageUrl.text?.toString()?.trim().orEmpty(),
                bannerActionUrl = binding.etBannerAdActionUrl.text?.toString()?.trim().orEmpty()
            )
            saveLiveBroadcast(next, R.string.admin_banner_saved_toast)
        }

        // Initialize Custom Popup Ad live counters & publish button
        updatePopupAdCounters(0L, 0L)
        binding.btnPublishCustomPopupAd.setOnClickListener {
            publishCustomPopupAdFromInputs()
        }
        binding.btnPopupAdSendLiveNow.setOnClickListener {
            sendCustomPopupAdAsLiveBroadcastNow()
        }
    }

    private fun buildBroadcastFromInputs(): LiveBroadcast? {
        val title = binding.etBroadcastTitle.text?.toString()?.trim().orEmpty()
        val message = binding.etBroadcastMessage.text?.toString()?.trim().orEmpty()
        val imageUrl = binding.etBroadcastImageUrl.text?.toString()?.trim().orEmpty()
        val actionUrl = binding.etBroadcastActionUrl.text?.toString()?.trim().orEmpty()
        if (title.isBlank() && message.isBlank() && imageUrl.isBlank()) {
            Toast.makeText(this, "Enter popup image URL, title, or message", Toast.LENGTH_SHORT).show()
            return null
        }
        return currentBroadcast.copy(
            showPopup = true,
            popupId = "pop_${System.currentTimeMillis()}",
            title = title,
            message = message,
            imageUrl = imageUrl,
            buttonText = binding.etBroadcastBtnText.text?.toString()?.trim()?.ifBlank { "Open Link" } ?: "Open Link",
            actionUrl = actionUrl,
            isCancelable = binding.switchBroadcastCancelable.isChecked
        )
    }

    private fun sendCustomPopupAdAsLiveBroadcastNow() {
        val imageUrl = binding.etPopupAdImageUrl.text?.toString()?.trim().orEmpty()
        val title = binding.etPopupAdTitle.text?.toString()?.trim().orEmpty()
        val message = binding.etPopupAdMessage.text?.toString()?.trim().orEmpty()
        val targetUrl = binding.etPopupAdTargetUrl.text?.toString()?.trim().orEmpty()
        val buttonText = binding.etPopupAdButtonText.text?.toString()?.trim()
            ?.ifBlank { "Open Offer" }
            ?: "Open Offer"

        if (imageUrl.isBlank() && title.isBlank() && message.isBlank()) {
            Toast.makeText(this, R.string.admin_popup_ad_validation_error, Toast.LENGTH_SHORT).show()
            return
        }

        // Also publish the CustomPopupAd config AND push an immediate LiveBroadcast popup with link
        publishCustomPopupAdFromInputs()
        val livePopup = currentBroadcast.copy(
            showPopup = true,
            popupId = "pop_${System.currentTimeMillis()}",
            title = title,
            message = message,
            imageUrl = imageUrl,
            buttonText = buttonText,
            actionUrl = targetUrl,
            isCancelable = true
        )
        bindBroadcastInputs(livePopup)
        saveLiveBroadcast(livePopup, R.string.admin_broadcast_sent_toast)
    }

    private fun updateBroadcastImagePreview(imageUrl: String) {
        if (imageUrl.isBlank()) {
            binding.cardBroadcastImagePreview.visibility = View.GONE
            return
        }
        binding.cardBroadcastImagePreview.visibility = View.VISIBLE
        binding.ivBroadcastImagePreview.load(imageUrl) {
            crossfade(true)
            listener(
                onError = { _, _ ->
                    binding.cardBroadcastImagePreview.visibility = View.GONE
                },
                onSuccess = { _, _ ->
                    binding.cardBroadcastImagePreview.visibility = View.VISIBLE
                }
            )
        }
    }

    private fun showBroadcastPreviewDialog(broadcast: LiveBroadcast) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_live_broadcast, null)
        val cardImageWrapper = dialogView.findViewById<View>(R.id.card_popup_image_wrapper)
        val ivImage = dialogView.findViewById<android.widget.ImageView>(R.id.iv_popup_image)
        val tvImageLinkBadge = dialogView.findViewById<TextView>(R.id.tv_popup_image_link_badge)
        val tvTitle = dialogView.findViewById<TextView>(R.id.tv_popup_title)
        val tvMessage = dialogView.findViewById<TextView>(R.id.tv_popup_message)
        val btnAction = dialogView.findViewById<MaterialButton>(R.id.btn_popup_action)
        val btnDismiss = dialogView.findViewById<MaterialButton>(R.id.btn_popup_close)

        val cleanTitle = broadcast.title.trim()
        val cleanMessage = broadcast.message.trim()
        tvTitle.text = cleanTitle
        tvTitle.visibility = if (cleanTitle.isNotEmpty()) View.VISIBLE else View.GONE
        tvMessage.text = cleanMessage
        tvMessage.visibility = if (cleanMessage.isNotEmpty()) View.VISIBLE else View.GONE
        btnAction.text = broadcast.buttonText.ifBlank { "Open Link" }

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        val hasActionUrl = broadcast.actionUrl.isNotBlank()
        val openActionUrl = {
            if (hasActionUrl) {
                runCatching {
                    val raw = broadcast.actionUrl.trim()
                    val normalized = if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) {
                        raw
                    } else {
                        "https://$raw"
                    }
                    startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(normalized)))
                }
            }
            dialog.dismiss()
        }

        if (broadcast.imageUrl.isNotBlank()) {
            cardImageWrapper?.visibility = View.VISIBLE
            tvImageLinkBadge?.visibility = if (hasActionUrl) View.VISIBLE else View.GONE
            ivImage.load(broadcast.imageUrl) {
                crossfade(true)
            }
            if (hasActionUrl) {
                cardImageWrapper?.setOnClickListener { openActionUrl() }
                ivImage.setOnClickListener { openActionUrl() }
            }
        } else {
            cardImageWrapper?.visibility = View.GONE
            tvImageLinkBadge?.visibility = View.GONE
        }

        btnAction.setOnClickListener { openActionUrl() }
        btnDismiss.visibility = View.VISIBLE
        btnDismiss.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun selectedPopupAdTriggerMode(): String {
        return when (binding.rgPopupAdTriggerMode.checkedRadioButtonId) {
            R.id.rb_popup_ad_trigger_app_open -> CustomPopupAd.TRIGGER_ON_APP_OPEN
            R.id.rb_popup_ad_trigger_after_call -> CustomPopupAd.TRIGGER_AFTER_CALL_END
            else -> CustomPopupAd.TRIGGER_BOTH
        }
    }

    private fun updatePopupAdCounters(impressions: Long, clicks: Long) {
        binding.tvPopupAdImpressionsBadge.text =
            getString(R.string.admin_popup_ad_views_count, impressions)
        binding.tvPopupAdClicksBadge.text =
            getString(R.string.admin_popup_ad_clicks_count, clicks)
    }

    private fun bindCustomPopupAdState(ad: CustomPopupAd) {
        val previousAdId = currentCustomPopupAd.adId
        currentCustomPopupAd = ad
        updatePopupAdCounters(ad.impressionsCount, ad.clicksCount)

        val anyAdFieldFocused = binding.etPopupAdImageUrl.hasFocus() ||
            binding.etPopupAdTitle.hasFocus() ||
            binding.etPopupAdMessage.hasFocus() ||
            binding.etPopupAdButtonText.hasFocus() ||
            binding.etPopupAdTargetUrl.hasFocus() ||
            binding.etPopupAdCountdown.hasFocus()

        if (!hasBoundInitialPopupAd || (ad.adId.isNotBlank() && ad.adId != previousAdId && !anyAdFieldFocused)) {
            hasBoundInitialPopupAd = true
            binding.switchPopupAdEnable.isChecked = ad.isEnabled
            binding.switchPopupAdShowPremium.isChecked = ad.showToPremiumUsers
            val radioId = when (CustomPopupAd.normalizedTriggerMode(ad.triggerMode)) {
                CustomPopupAd.TRIGGER_ON_APP_OPEN -> R.id.rb_popup_ad_trigger_app_open
                CustomPopupAd.TRIGGER_AFTER_CALL_END -> R.id.rb_popup_ad_trigger_after_call
                else -> R.id.rb_popup_ad_trigger_both
            }
            binding.rgPopupAdTriggerMode.check(radioId)
            binding.etPopupAdImageUrl.setText(ad.imageUrl)
            binding.etPopupAdTitle.setText(ad.title)
            binding.etPopupAdMessage.setText(ad.message)
            binding.etPopupAdButtonText.setText(ad.buttonText)
            binding.etPopupAdTargetUrl.setText(ad.targetUrl)
            binding.etPopupAdCountdown.setText(ad.countdownSeconds.toString())
        }
    }

    private fun publishCustomPopupAdFromInputs() {
        val isEnabled = binding.switchPopupAdEnable.isChecked
        val showPremium = binding.switchPopupAdShowPremium.isChecked
        val triggerMode = selectedPopupAdTriggerMode()
        val imageUrl = binding.etPopupAdImageUrl.text?.toString()?.trim().orEmpty()
        val title = binding.etPopupAdTitle.text?.toString()?.trim().orEmpty()
        val message = binding.etPopupAdMessage.text?.toString()?.trim().orEmpty()
        val buttonText = binding.etPopupAdButtonText.text?.toString()?.trim()
            ?.ifBlank { CustomPopupAd.DEFAULT_BUTTON_TEXT }
            ?: CustomPopupAd.DEFAULT_BUTTON_TEXT
        val targetUrl = binding.etPopupAdTargetUrl.text?.toString()?.trim().orEmpty()
        val countdown = binding.etPopupAdCountdown.text?.toString()?.trim()?.toIntOrNull()
            ?.coerceIn(0, CustomPopupAd.MAX_COUNTDOWN_SECONDS)
            ?: CustomPopupAd.DEFAULT_COUNTDOWN_SECONDS

        if (isEnabled && title.isBlank() && message.isBlank() && imageUrl.isBlank()) {
            Toast.makeText(this, R.string.admin_popup_ad_validation_error, Toast.LENGTH_SHORT).show()
            return
        }

        val updated = CustomPopupAd(
            adId = "ad_${System.currentTimeMillis()}",
            isEnabled = isEnabled,
            showToPremiumUsers = showPremium,
            triggerMode = triggerMode,
            imageUrl = imageUrl,
            title = title,
            message = message,
            buttonText = buttonText,
            targetUrl = targetUrl,
            countdownSeconds = countdown,
            impressionsCount = currentCustomPopupAd.impressionsCount,
            clicksCount = currentCustomPopupAd.clicksCount
        )
        val ok = RealtimeDatabaseSync.publishCustomPopupAd(updated, this)
        if (ok) {
            currentCustomPopupAd = updated
            Toast.makeText(this, R.string.admin_popup_ad_published_toast, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(
                this,
                getString(R.string.admin_action_failed, "RTDB unavailable"),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun saveLiveBroadcast(broadcast: LiveBroadcast, successToastRes: Int) {
        lifecycleScope.launch {
            val result = adminRepository.saveLiveBroadcast(broadcast)
            result.onSuccess {
                currentBroadcast = broadcast
                Toast.makeText(this@AdminPanelActivity, successToastRes, Toast.LENGTH_SHORT).show()
            }.onFailure { err ->
                Toast.makeText(
                    this@AdminPanelActivity,
                    getString(R.string.admin_action_failed, err.localizedMessage ?: "Error"),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun bindBroadcastInputs(broadcast: LiveBroadcast) {
        currentBroadcast = broadcast
        binding.etBroadcastTitle.setText(broadcast.title)
        binding.etBroadcastMessage.setText(broadcast.message)
        binding.etBroadcastImageUrl.setText(broadcast.imageUrl)
        binding.etBroadcastBtnText.setText(broadcast.buttonText)
        binding.etBroadcastActionUrl.setText(broadcast.actionUrl)
        binding.switchBroadcastCancelable.isChecked = broadcast.isCancelable
        updateBroadcastImagePreview(broadcast.imageUrl)

        binding.switchBannerAdEnable.isChecked = broadcast.showBannerAd
        binding.etBannerAdText.setText(broadcast.bannerText)
        binding.etBannerAdImageUrl.setText(broadcast.bannerImageUrl)
        binding.etBannerAdActionUrl.setText(broadcast.bannerActionUrl)
    }

    private fun setupTabsAndUiTab() {
        navTabsAdapter = AdminNavTabsAdapter(
            onToggleEnabled = { tab, enabled ->
                val updated = currentNavTabs.map {
                    if (it.id == tab.id) it.copy(enabled = enabled) else it
                }
                if (updated.none { it.enabled }) {
                    Toast.makeText(this, "Keep at least 1 tab active.", Toast.LENGTH_SHORT).show()
                    navTabsAdapter.submitList(currentNavTabs)
                } else {
                    saveNavTabsList(updated)
                }
            },
            onMoveUp = { position ->
                if (position > 0) {
                    val mutable = currentNavTabs.toMutableList()
                    val temp = mutable[position]
                    mutable[position] = mutable[position - 1]
                    mutable[position - 1] = temp
                    saveNavTabsList(mutable)
                }
            },
            onMoveDown = { position ->
                if (position < currentNavTabs.lastIndex) {
                    val mutable = currentNavTabs.toMutableList()
                    val temp = mutable[position]
                    mutable[position] = mutable[position + 1]
                    mutable[position + 1] = temp
                    saveNavTabsList(mutable)
                }
            },
            onEdit = { tab -> showEditNavTabDialog(tab) },
            onDelete = { tab ->
                if (tab.type == NavigationTabItem.TYPE_CUSTOM_BANNER) {
                    saveNavTabsList(currentNavTabs.filterNot { it.id == tab.id })
                }
            }
        )
        binding.recyclerAdminNavTabs.layoutManager = LinearLayoutManager(this)
        binding.recyclerAdminNavTabs.adapter = navTabsAdapter

        binding.btnAddCustomTab.setOnClickListener { showEditNavTabDialog(null) }
        binding.btnResetDefaultTabs.setOnClickListener {
            saveNavTabsList(NavigationTabItem.defaultTabs())
        }

        settingsItemsAdapter = AdminSettingsItemsAdapter(
            onToggleEnabled = { item, enabled ->
                val updated = currentSettingItems.map {
                    if (it.key == item.key) it.copy(enabled = enabled) else it
                }
                saveSettingsItemsList(updated)
            },
            onMoveUp = { position ->
                if (position > 0) {
                    val mutable = currentSettingItems.toMutableList()
                    val temp = mutable[position]
                    mutable[position] = mutable[position - 1]
                    mutable[position - 1] = temp
                    saveSettingsItemsList(mutable)
                }
            },
            onMoveDown = { position ->
                if (position < currentSettingItems.lastIndex) {
                    val mutable = currentSettingItems.toMutableList()
                    val temp = mutable[position]
                    mutable[position] = mutable[position + 1]
                    mutable[position + 1] = temp
                    saveSettingsItemsList(mutable)
                }
            },
            onEdit = { item -> showEditSettingItemDialog(item) },
            onDelete = { item ->
                saveSettingsItemsList(currentSettingItems.filterNot { it.key == item.key })
            }
        )
        binding.recyclerAdminSettingsItems.layoutManager = LinearLayoutManager(this)
        binding.recyclerAdminSettingsItems.adapter = settingsItemsAdapter

        binding.btnAddCustomSetting.setOnClickListener { showEditSettingItemDialog(null) }
        binding.btnResetDefaultSettings.setOnClickListener {
            saveSettingsItemsList(DynamicSettingItem.defaultItems())
        }
    }

    private fun saveNavTabsList(tabs: List<NavigationTabItem>) {
        val reindexed = tabs.mapIndexed { idx, tab -> tab.copy(order = idx) }
        lifecycleScope.launch {
            val result = adminRepository.saveNavigationTabs(reindexed)
            result.onSuccess {
                currentNavTabs = reindexed
                navTabsAdapter.submitList(reindexed)
                Toast.makeText(this@AdminPanelActivity, R.string.admin_settings_saved, Toast.LENGTH_SHORT).show()
            }.onFailure { err ->
                Toast.makeText(
                    this@AdminPanelActivity,
                    getString(R.string.admin_action_failed, err.localizedMessage ?: "Error"),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun saveSettingsItemsList(items: List<DynamicSettingItem>) {
        val reindexed = items.mapIndexed { idx, item -> item.copy(order = idx) }
        lifecycleScope.launch {
            val result = adminRepository.saveDynamicSettings(reindexed)
            result.onSuccess {
                currentSettingItems = reindexed
                settingsItemsAdapter.submitList(reindexed)
                Toast.makeText(this@AdminPanelActivity, R.string.admin_settings_saved, Toast.LENGTH_SHORT).show()
            }.onFailure { err ->
                Toast.makeText(
                    this@AdminPanelActivity,
                    getString(R.string.admin_action_failed, err.localizedMessage ?: "Error"),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun showEditNavTabDialog(existing: NavigationTabItem?) {
        val isNew = existing == null
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
        }
        val titleInput = EditText(this).apply {
            hint = "Tab Label (e.g. Offers)"
            setText(existing?.title.orEmpty())
        }
        container.addView(titleInput)

        val isCustom = isNew || existing?.type == NavigationTabItem.TYPE_CUSTOM_BANNER
        val customHeadlineInput = EditText(this).apply {
            hint = "Screen Headline"
            setText(existing?.customTitle.orEmpty())
        }
        val customBodyInput = EditText(this).apply {
            hint = "Screen Message Body"
            setText(existing?.customBody.orEmpty())
        }
        val customUrlInput = EditText(this).apply {
            hint = "Action Link URL (optional)"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setText(existing?.customActionUrl.orEmpty())
        }
        if (isCustom) {
            container.addView(customHeadlineInput)
            container.addView(customBodyInput)
            container.addView(customUrlInput)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(if (isNew) "Add Custom Navigation Tab" else "Edit Tab: ${existing?.title}")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val cleanTitle = titleInput.text.toString().trim()
                if (cleanTitle.isBlank()) {
                    Toast.makeText(this, "Tab label is required", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val updatedList = if (isNew) {
                    val newTab = NavigationTabItem(
                        id = "custom_${System.currentTimeMillis()}",
                        title = cleanTitle,
                        iconKey = NavigationTabItem.ICON_ANNOUNCEMENT,
                        enabled = true,
                        order = currentNavTabs.size,
                        type = NavigationTabItem.TYPE_CUSTOM_BANNER,
                        customTitle = customHeadlineInput.text.toString().trim(),
                        customBody = customBodyInput.text.toString().trim(),
                        customActionUrl = customUrlInput.text.toString().trim()
                    )
                    currentNavTabs + newTab
                } else {
                    currentNavTabs.map { tab ->
                        if (tab.id == existing?.id) {
                            tab.copy(
                                title = cleanTitle,
                                customTitle = if (isCustom) customHeadlineInput.text.toString().trim() else tab.customTitle,
                                customBody = if (isCustom) customBodyInput.text.toString().trim() else tab.customBody,
                                customActionUrl = if (isCustom) customUrlInput.text.toString().trim() else tab.customActionUrl
                            )
                        } else {
                            tab
                        }
                    }
                }
                saveNavTabsList(updatedList)
            }
            .setNegativeButton(R.string.dialog_close, null)
            .show()
    }

    private fun showEditSettingItemDialog(existing: DynamicSettingItem?) {
        val isNew = existing == null
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
        }
        val titleInput = EditText(this).apply {
            hint = "Setting Title"
            setText(existing?.title.orEmpty())
        }
        val descInput = EditText(this).apply {
            hint = "Description"
            setText(existing?.description.orEmpty())
        }
        val urlInput = EditText(this).apply {
            hint = "Optional Link URL (for Action rows)"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setText(existing?.actionUrl.orEmpty())
        }
        val toggleSwitch = MaterialSwitch(this).apply {
            text = "Render as ON/OFF Toggle (off = Action Row)"
            isChecked = existing?.type != DynamicSettingItem.TYPE_ACTION
        }
        val lockedCheck = CheckBox(this).apply {
            text = "Lock setting globally (prevent user changes)"
            isChecked = existing?.lockedByAdmin == true
        }
        val forcedValSwitch = MaterialSwitch(this).apply {
            text = "Default / Forced Switch State (ON/OFF)"
            isChecked = existing?.defaultValue == true
        }
        container.addView(titleInput)
        container.addView(descInput)
        container.addView(urlInput)
        if (isNew || existing?.key?.startsWith("custom_") == true) {
            container.addView(toggleSwitch)
        }
        container.addView(lockedCheck)
        container.addView(forcedValSwitch)

        MaterialAlertDialogBuilder(this)
            .setTitle(if (isNew) "Add Custom Setting" else "Edit: ${existing?.title}")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val cleanTitle = titleInput.text.toString().trim()
                if (cleanTitle.isBlank()) {
                    Toast.makeText(this, "Title is required", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val cleanDesc = descInput.text.toString().trim()
                val cleanUrl = urlInput.text.toString().trim()
                val updatedList = if (isNew) {
                    val type = if (toggleSwitch.isChecked) DynamicSettingItem.TYPE_TOGGLE else DynamicSettingItem.TYPE_ACTION
                    val newItem = DynamicSettingItem(
                        key = "custom_${System.currentTimeMillis()}",
                        title = cleanTitle,
                        description = cleanDesc,
                        iconKey = if (type == DynamicSettingItem.TYPE_TOGGLE) DynamicSettingItem.ICON_TOGGLE else DynamicSettingItem.ICON_INFO,
                        type = type,
                        enabled = true,
                        lockedByAdmin = lockedCheck.isChecked,
                        defaultValue = forcedValSwitch.isChecked,
                        order = currentSettingItems.size,
                        actionUrl = cleanUrl
                    )
                    currentSettingItems + newItem
                } else {
                    currentSettingItems.map { item ->
                        if (item.key == existing?.key) {
                            item.copy(
                                title = cleanTitle,
                                description = cleanDesc,
                                lockedByAdmin = lockedCheck.isChecked,
                                defaultValue = forcedValSwitch.isChecked,
                                actionUrl = cleanUrl
                            )
                        } else {
                            item
                        }
                    }
                }
                saveSettingsItemsList(updatedList)
            }
            .setNegativeButton(R.string.dialog_close, null)
            .show()
    }

    private fun setupSettingsTab() {
        val currentThemeMode = when (prefs.configuredForceThemeMode) {
            AppConfig.THEME_DARK -> AppConfig.THEME_DARK
            AppConfig.THEME_LIGHT -> AppConfig.THEME_LIGHT
            else -> if (prefs.displayThemeMode == AppCompatDelegate.MODE_NIGHT_YES) {
                AppConfig.THEME_DARK
            } else {
                AppConfig.THEME_USER
            }
        }
        bindSettingsInputs(
            AppConfig(
                id = AppConfig.DOC_GLOBAL_SETTINGS,
                trialDays = prefs.configuredTrialDays.toLong(),
                subscriptionPriceInr = prefs.configuredPriceInr,
                upiVpa = prefs.configuredUpiVpa,
                whatsappNumber = prefs.configuredWhatsappNumber,
                enforceSingleDeviceTrial = prefs.enforceSingleDeviceTrialConfig,
                allowCallRecording = prefs.allowCallRecordingConfig,
                allowManualUtr = prefs.allowManualUtrConfig,
                maintenanceMode = prefs.maintenanceMode,
                maintenanceMessage = prefs.maintenanceMessage,
                announcementText = prefs.announcementText,
                dialpadTonesDefault = prefs.dialpadTonesEnabled,
                defaultAutoRecording = prefs.autoCallRecording,
                forceThemeMode = currentThemeMode,
                hideSubscriptionUI = prefs.configHideSubscriptionUi,
                enableUpiGateway = prefs.enableUpiGateway,
                enableRazorpayGateway = prefs.enableRazorpayGateway,
                razorpayKeyId = prefs.configuredRazorpayKeyId,
                razorpayPaymentLink = prefs.configuredRazorpayPaymentLink,
                enablePayuGateway = prefs.enablePayuGateway,
                payuMerchantKey = prefs.configuredPayuMerchantKey,
                payuPaymentLink = prefs.configuredPayuPaymentLink,
                enableCustomGateway = prefs.enableCustomGateway,
                customGatewayName = prefs.configuredCustomGatewayName,
                customGatewayUrl = prefs.configuredCustomGatewayUrl
            )
        )

        binding.btnSaveSettings.setOnClickListener { saveAllAdminSettings() }
        binding.btnResetSettings.setOnClickListener { resetDefaultAdminSettings() }
    }

    private fun bindSettingsInputs(config: AppConfig) {
        binding.etConfigTrialDays.setText(config.trialDays.toString())
        binding.etConfigPriceInr.setText(config.subscriptionPriceInr.toString())
        binding.etConfigUpiVpa.setText(config.upiVpa)
        binding.etConfigWhatsapp.setText(config.whatsappNumber)
        binding.switchConfigSingleDevice.isChecked = config.enforceSingleDeviceTrial
        binding.switchConfigAllowRecording.isChecked = config.allowCallRecording
        binding.switchConfigAllowUtr.isChecked = config.allowManualUtr
        binding.switchConfigMaintenance.isChecked = config.maintenanceMode
        binding.switchConfigHideSubUi.isChecked = config.hideSubscriptionUI
        suppressHideSubSwitchListener = true
        binding.switchAdminHideSubUi.isChecked = config.hideSubscriptionUI
        suppressHideSubSwitchListener = false
        binding.etConfigMaintenanceMsg.setText(config.maintenanceMessage)
        binding.etConfigAnnouncement.setText(config.announcementText)

        binding.switchLocalAutoRecording.isChecked = config.defaultAutoRecording
        binding.switchLocalDialpadTones.isChecked = config.dialpadTonesDefault
        binding.switchLocalDarkTheme.isChecked = config.forceThemeMode == AppConfig.THEME_DARK ||
            (config.forceThemeMode == AppConfig.THEME_USER &&
                prefs.displayThemeMode == AppCompatDelegate.MODE_NIGHT_YES)

        binding.switchConfigEnableUpi.isChecked = config.enableUpiGateway
        binding.switchConfigEnableRazorpay.isChecked = config.enableRazorpayGateway
        binding.etConfigRazorpayKeyId.setText(config.razorpayKeyId)
        binding.etConfigRazorpayLink.setText(config.razorpayPaymentLink)
        binding.switchConfigEnablePayu.isChecked = config.enablePayuGateway
        binding.etConfigPayuKey.setText(config.payuMerchantKey)
        binding.etConfigPayuLink.setText(config.payuPaymentLink)
        binding.switchConfigEnableCustomGw.isChecked = config.enableCustomGateway
        binding.etConfigCustomGwName.setText(config.customGatewayName)
        binding.etConfigCustomGwUrl.setText(config.customGatewayUrl)
    }

    private fun saveAllAdminSettings() {
        val trialDays = binding.etConfigTrialDays.text?.toString()?.trim()?.toLongOrNull()
        val priceInr = binding.etConfigPriceInr.text?.toString()?.trim()?.toLongOrNull()
        val upiVpa = binding.etConfigUpiVpa.text?.toString()?.trim().orEmpty()
        val whatsappDigits = binding.etConfigWhatsapp.text?.toString().orEmpty().filter { it.isDigit() }

        if (trialDays == null || trialDays !in 1L..365L ||
            priceInr == null || priceInr !in 1L..100000L ||
            !upiVpa.contains("@") || upiVpa.length < 3 ||
            whatsappDigits.length < 10
        ) {
            Toast.makeText(this, R.string.admin_settings_invalid_input, Toast.LENGTH_LONG).show()
            return
        }

        val enableUpi = binding.switchConfigEnableUpi.isChecked
        val enableRazorpay = binding.switchConfigEnableRazorpay.isChecked
        val razorpayKey = binding.etConfigRazorpayKeyId.text?.toString()?.trim().orEmpty()
        val razorpayLink = binding.etConfigRazorpayLink.text?.toString()?.trim().orEmpty()
        val enablePayu = binding.switchConfigEnablePayu.isChecked
        val payuKey = binding.etConfigPayuKey.text?.toString()?.trim().orEmpty()
        val payuLink = binding.etConfigPayuLink.text?.toString()?.trim().orEmpty()
        val enableCustom = binding.switchConfigEnableCustomGw.isChecked
        val customName = binding.etConfigCustomGwName.text?.toString()?.trim().orEmpty()
        val customUrl = binding.etConfigCustomGwUrl.text?.toString()?.trim().orEmpty()

        val hasAnyGateway = enableUpi || enableRazorpay || enablePayu || enableCustom
        val razorpayValid = !enableRazorpay || razorpayKey.isNotBlank() || razorpayLink.isNotBlank()
        val payuValid = !enablePayu || payuKey.isNotBlank() || payuLink.isNotBlank()
        val customValid = !enableCustom || customUrl.isNotBlank()
        if (!hasAnyGateway || !razorpayValid || !payuValid || !customValid) {
            Toast.makeText(this, R.string.admin_gateway_validation_error, Toast.LENGTH_LONG).show()
            return
        }

        val desiredGlobalTheme = if (binding.switchLocalDarkTheme.isChecked) {
            AppConfig.THEME_DARK
        } else {
            AppConfig.THEME_LIGHT
        }

        val updatedConfig = AppConfig(
            id = AppConfig.DOC_GLOBAL_SETTINGS,
            trialDays = trialDays,
            subscriptionPriceInr = priceInr,
            upiVpa = upiVpa,
            whatsappNumber = whatsappDigits,
            enforceSingleDeviceTrial = binding.switchConfigSingleDevice.isChecked,
            allowCallRecording = binding.switchConfigAllowRecording.isChecked,
            allowManualUtr = binding.switchConfigAllowUtr.isChecked,
            maintenanceMode = binding.switchConfigMaintenance.isChecked,
            maintenanceMessage = binding.etConfigMaintenanceMsg.text?.toString()?.trim().orEmpty(),
            announcementText = binding.etConfigAnnouncement.text?.toString()?.trim().orEmpty(),
            dialpadTonesDefault = binding.switchLocalDialpadTones.isChecked,
            defaultAutoRecording = binding.switchLocalAutoRecording.isChecked,
            forceThemeMode = desiredGlobalTheme,
            hideSubscriptionUI = binding.switchConfigHideSubUi.isChecked,
            enableUpiGateway = enableUpi,
            enableRazorpayGateway = enableRazorpay,
            razorpayKeyId = razorpayKey,
            razorpayPaymentLink = razorpayLink,
            enablePayuGateway = enablePayu,
            payuMerchantKey = payuKey,
            payuPaymentLink = payuLink,
            enableCustomGateway = enableCustom,
            customGatewayName = customName,
            customGatewayUrl = customUrl
        )

        val desiredNightMode = if (binding.switchLocalDarkTheme.isChecked) {
            AppCompatDelegate.MODE_NIGHT_YES
        } else {
            AppCompatDelegate.MODE_NIGHT_NO
        }
        if (prefs.displayThemeMode != desiredNightMode) {
            prefs.displayThemeMode = desiredNightMode
            AppCompatDelegate.setDefaultNightMode(desiredNightMode)
        }

        binding.btnSaveSettings.isEnabled = false
        lifecycleScope.launch {
            val result = adminRepository.saveGlobalAppConfig(updatedConfig)
            binding.btnSaveSettings.isEnabled = true
            result.onSuccess {
                currentConfig = updatedConfig
                prefs.applyAppConfig(updatedConfig)
                usersAdapter.trialDaysConfig = updatedConfig.trialDays.toInt()
                Toast.makeText(this@AdminPanelActivity, R.string.admin_settings_saved, Toast.LENGTH_SHORT).show()
            }.onFailure { err ->
                Toast.makeText(
                    this@AdminPanelActivity,
                    getString(R.string.admin_action_failed, err.localizedMessage ?: "Error"),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun resetDefaultAdminSettings() {
        val defaults = AppConfig(id = AppConfig.DOC_GLOBAL_SETTINGS)
        bindSettingsInputs(defaults)
        lifecycleScope.launch {
            val result = adminRepository.saveGlobalAppConfig(defaults)
            result.onSuccess {
                currentConfig = defaults
                prefs.applyAppConfig(defaults)
                usersAdapter.trialDaysConfig = defaults.trialDays.toInt()
                Toast.makeText(this@AdminPanelActivity, R.string.admin_settings_saved, Toast.LENGTH_SHORT).show()
            }.onFailure { err ->
                Toast.makeText(
                    this@AdminPanelActivity,
                    getString(R.string.admin_action_failed, err.localizedMessage ?: "Error"),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun observeAdminData() {
        if (Firebase.auth.currentUser == null) return
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    adminRepository.observeAllUsers()
                        .catch { /* logged in repository */ }
                        .collect { users ->
                            firestoreUsersById.clear()
                            users.forEach { user ->
                                if (user.uid.isNotBlank()) {
                                    firestoreUsersById[user.uid] = user
                                }
                            }
                            mergeAndPublishUsers()
                        }
                }
                launch {
                    paymentRepository.observeAllPaymentRequestsForAdmin()
                        .catch { /* logged in repository */ }
                        .collect { requests ->
                            firestorePaymentsById.clear()
                            requests.forEach { req ->
                                if (req.id.isNotBlank()) {
                                    firestorePaymentsById[req.id] = req
                                }
                            }
                            mergeAndPublishPayments()
                        }
                }
                launch {
                    adminRepository.observeGlobalAppConfig()
                        .catch { /* logged in repository */ }
                        .collect { config ->
                            if (!hasLoadedRtdbConfig || (config.updatedAt?.toDate()?.time ?: 0L) > (currentConfig.updatedAt?.toDate()?.time ?: 0L)) {
                                currentConfig = config
                                prefs.applyAppConfig(config)
                                usersAdapter.trialDaysConfig = config.trialDays.toInt()
                                expiringUsersAdapter.trialDaysConfig = config.trialDays.toInt()
                                bindSettingsInputs(config)
                                applyUserFilters()
                                applySubscriberFilters()
                            }
                        }
                }
                launch {
                    adminRepository.observeNavigationTabs()
                        .catch { /* logged in repository */ }
                        .collect { tabsConfig ->
                            if (currentNavTabs.isEmpty() || tabsConfig.tabs.isNotEmpty()) {
                                currentNavTabs = tabsConfig.tabs
                                navTabsAdapter.submitList(tabsConfig.tabs)
                            }
                        }
                }
                launch {
                    adminRepository.observeDynamicSettings()
                        .catch { /* logged in repository */ }
                        .collect { settingsConfig ->
                            if (currentSettingItems.isEmpty() || settingsConfig.items.isNotEmpty()) {
                                currentSettingItems = settingsConfig.items
                                settingsItemsAdapter.submitList(settingsConfig.items)
                            }
                        }
                }
                launch {
                    adminRepository.observeLiveBroadcast()
                        .catch { /* logged in repository */ }
                        .collect { broadcast ->
                            if ((broadcast.updatedAt?.toDate()?.time ?: 0L) >= (currentBroadcast.updatedAt?.toDate()?.time ?: 0L)) {
                                bindBroadcastInputs(broadcast)
                            }
                        }
                }
            }
        }
    }

    private fun updateKpis() {
        val trialDays = currentConfig.trialDays.toInt()
        binding.chipKpiUsers.text = getString(R.string.admin_kpi_users, allUsers.size)
        val premiumCount = allUsers.count {
            !it.isBlocked && it.isPremiumUser &&
                AdminRepository.resolveUserAccountStatus(it, trialDays) !=
                com.example.data.firebase.AdminUserAccountStatus.EXPIRED
        }
        binding.chipKpiPremium.text = getString(R.string.admin_kpi_premium, premiumCount)
        val expiringCount = allUsers.count { AdminRepository.isUserExpiringSoon(it, trialDays) }
        binding.chipKpiExpiring.text = getString(R.string.admin_kpi_expiring, expiringCount)
        val pendingCount = allPayments.count { it.status == PaymentRequest.STATUS_PENDING }
        binding.chipKpiPending.text = getString(R.string.admin_kpi_pending, pendingCount)
    }

    private fun applyUserFilters() {
        val trialDays = currentConfig.trialDays.toInt()
        val filtered = AdminRepository.filterUsers(
            users = allUsers,
            filter = currentUserFilter,
            query = currentUserQuery,
            trialDaysConfig = trialDays,
            nowMs = System.currentTimeMillis(),
            dateFilter = currentUserDateFilter,
            dateField = currentUserDateField,
            customStartMs = customDateStartMs,
            customEndMs = customDateEndMs
        )
        val fieldLabel = when (currentUserDateField) {
            AdminUserDateField.JOIN_DATE -> "Join Date"
            AdminUserDateField.EXPIRY_DATE -> "Expiry Date"
            AdminUserDateField.UPDATED_DATE -> "Last Updated"
        }
        val rangeLabel = when (currentUserDateFilter) {
            AdminUserDateFilter.ALL_TIME -> "All Dates"
            AdminUserDateFilter.TODAY -> "Today"
            AdminUserDateFilter.LAST_7_DAYS -> "Last 7 Days"
            AdminUserDateFilter.LAST_30_DAYS -> "Last 30 Days"
            AdminUserDateFilter.CUSTOM_RANGE -> binding.chipDateFilterCustom.text.toString()
        }
        binding.tvAdminUserDateSummary.text =
            "Showing ${filtered.size} of ${allUsers.size} users • Filter: $rangeLabel ($fieldLabel)"
        binding.tvAdminUsersEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        usersAdapter.submitList(filtered)
    }

    private fun applyPaymentFilters() {
        val filtered = AdminRepository.filterPaymentRequests(
            requests = allPayments,
            filter = currentPaymentFilter,
            query = currentPaymentQuery
        )
        binding.tvAdminPaymentsEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        paymentsAdapter.submitList(filtered)
    }

    private fun toggleUserPro(user: UserEntitlement) {
        val status = AdminRepository.resolveUserAccountStatus(user, currentConfig.trialDays.toInt())
        val isUnexpiredPro = user.isPremiumUser && status != com.example.data.firebase.AdminUserAccountStatus.EXPIRED
        val targetPremium = !isUnexpiredPro
        lifecycleScope.launch {
            val result = if (targetPremium) {
                adminRepository.renewUserMonthlySubscription(
                    user = user,
                    extraDays = DialerPreferences.MONTHLY_SUBSCRIPTION_DAYS.toLong()
                )
            } else {
                adminRepository.updateUserAdminDetails(
                    user = user,
                    isPremium = false,
                    isBlocked = user.isBlocked,
                    bonusTrialDays = user.bonusTrialDays,
                    adminNote = user.adminNote,
                    hideSubscriptionUI = user.hideSubscriptionUI,
                    subscriptionExpiresAt = null
                )
            }
            result.onSuccess {
                Toast.makeText(
                    this@AdminPanelActivity,
                    getString(R.string.admin_user_updated, user.displayName.ifBlank { user.email }),
                    Toast.LENGTH_SHORT
                ).show()
            }.onFailure { err ->
                Toast.makeText(
                    this@AdminPanelActivity,
                    getString(R.string.admin_action_failed, err.localizedMessage ?: "Error"),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun renewUserMonth(user: UserEntitlement) {
        lifecycleScope.launch {
            val result = adminRepository.renewUserMonthlySubscription(
                user = user,
                extraDays = DialerPreferences.MONTHLY_SUBSCRIPTION_DAYS.toLong()
            )
            result.onSuccess {
                Toast.makeText(
                    this@AdminPanelActivity,
                    getString(R.string.admin_user_updated, user.displayName.ifBlank { user.email }),
                    Toast.LENGTH_SHORT
                ).show()
            }.onFailure { err ->
                Toast.makeText(
                    this@AdminPanelActivity,
                    getString(R.string.admin_action_failed, err.localizedMessage ?: "Error"),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun adjustUserDays(user: UserEntitlement, deltaDays: Long) {
        val trialDays = currentConfig.trialDays.toInt()
        lifecycleScope.launch {
            val result = adminRepository.adjustUserAccessDays(
                user = user,
                deltaDays = deltaDays,
                trialDaysConfig = trialDays
            )
            result.onSuccess {
                val deltaText = if (deltaDays >= 0L) "+${deltaDays}d" else "${deltaDays}d"
                val label = user.displayName.ifBlank { user.email.ifBlank { user.userId } }
                val roleLabel = if (user.isPremiumUser) "Subscription" else "Access"
                Toast.makeText(
                    this@AdminPanelActivity,
                    getString(R.string.admin_days_adjusted_toast, label, deltaText, roleLabel),
                    Toast.LENGTH_SHORT
                ).show()
            }.onFailure { err ->
                Toast.makeText(
                    this@AdminPanelActivity,
                    getString(R.string.admin_action_failed, err.localizedMessage ?: "Error"),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun toggleUserBlock(user: UserEntitlement) {
        val nextBlocked = !user.isBlocked
        lifecycleScope.launch {
            val result = adminRepository.updateUserAdminDetails(
                user = user,
                isPremium = if (nextBlocked) false else user.isPremiumUser,
                isBlocked = nextBlocked,
                bonusTrialDays = user.bonusTrialDays,
                adminNote = user.adminNote,
                hideSubscriptionUI = user.hideSubscriptionUI
            )
            result.onSuccess {
                Toast.makeText(
                    this@AdminPanelActivity,
                    getString(R.string.admin_user_updated, user.displayName.ifBlank { user.email }),
                    Toast.LENGTH_SHORT
                ).show()
            }.onFailure { err ->
                Toast.makeText(
                    this@AdminPanelActivity,
                    getString(R.string.admin_action_failed, err.localizedMessage ?: "Error"),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun approveUserUtrDirect(user: UserEntitlement) {
        val locks = currentEffectiveApprovedUtrKeys()
        lifecycleScope.launch {
            val pendingReq = allPayments.firstOrNull {
                it.userId == user.userId && it.status == PaymentRequest.STATUS_PENDING
            }
            val result = if (pendingReq != null) {
                paymentRepository.approvePaymentRequest(
                    request = pendingReq,
                    knownRequests = allPayments,
                    approvedUtrKeys = locks
                )
            } else {
                adminRepository.approveUserUtrDirect(
                    user = user,
                    approvedUtrKeys = locks,
                    knownRequests = allPayments
                )
            }
            result.onSuccess {
                Toast.makeText(
                    this@AdminPanelActivity,
                    getString(R.string.admin_approved_toast, user.displayName.ifBlank { user.email }),
                    Toast.LENGTH_SHORT
                ).show()
            }.onFailure { err ->
                Toast.makeText(
                    this@AdminPanelActivity,
                    getString(R.string.admin_action_failed, err.localizedMessage ?: "Error"),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun rejectUserUtrDirect(user: UserEntitlement) {
        lifecycleScope.launch {
            val pendingReq = allPayments.firstOrNull {
                it.userId == user.userId && it.status == PaymentRequest.STATUS_PENDING
            }
            val result = if (pendingReq != null) {
                paymentRepository.rejectPaymentRequest(pendingReq, "Rejected by Admin")
            } else {
                adminRepository.rejectUserUtrDirect(user)
            }
            result.onSuccess {
                Toast.makeText(
                    this@AdminPanelActivity,
                    getString(R.string.admin_rejected_toast, user.displayName.ifBlank { user.email }),
                    Toast.LENGTH_SHORT
                ).show()
            }.onFailure { err ->
                Toast.makeText(
                    this@AdminPanelActivity,
                    getString(R.string.admin_action_failed, err.localizedMessage ?: "Error"),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun showManageUserDialog(user: UserEntitlement) {
        val deviceTag = if (user.deviceHash.length == 64) {
            DeviceIdentityProvider.formatShortDeviceTag(user.deviceHash)
        } else {
            "—"
        }
        val actingEmail = GoogleAuthHelper.resolveActiveEmail(this)
        val isActingSuperAdmin = UpiPaymentHelper.isSuperAdminEmail(actingEmail)
        val isTargetSuperAdmin = UpiPaymentHelper.isSuperAdminEmail(user.email)
        val dateFmt = java.text.SimpleDateFormat("dd MMM yyyy, hh:mm a", java.util.Locale.getDefault())

        var pendingExpiryMs = AdminRepository.resolveEffectiveExpiryMs(user)

        val scrollView = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
        }
        scrollView.addView(container)

        val infoView = TextView(this).apply {
            text = buildString {
                append("Email: ").append(user.email.ifBlank { "—" })
                append("\nUID: ").append(user.userId)
                append("\nDevice: ").append(deviceTag)
                append("\nStatus: ").append(user.subscriptionStatus)
                if (user.paymentRefId.isNotBlank()) {
                    append("\nUTR / Ref: ").append(user.paymentRefId)
                }
                if (isTargetSuperAdmin) {
                    append("\nRole: ").append(getString(R.string.admin_manage_super_admin_locked))
                } else if (UpiPaymentHelper.isDelegatedAdminAuthorized(user.isAdmin, user.adminGrantedBy)) {
                    append("\nRole: Delegated Admin (by ").append(user.adminGrantedBy).append(")")
                }
            }
            setPadding(0, 0, 0, 16)
        }

        val nameInput = EditText(this).apply {
            hint = getString(R.string.admin_manage_display_name_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setText(user.displayName)
        }
        val phoneInput = EditText(this).apply {
            hint = getString(R.string.admin_manage_phone_hint)
            inputType = InputType.TYPE_CLASS_PHONE
            setText(user.phoneNumber)
        }

        val subDaysHeader = TextView(this).apply {
            text = getString(R.string.admin_manage_sub_days_section)
            setPadding(0, 20, 0, 4)
        }
        val expiryPreviewView = TextView(this).apply {
            setPadding(0, 4, 0, 8)
        }
        val proCheck = CheckBox(this).apply {
            text = "Premium Active"
            isChecked = user.isPremiumUser
        }

        val bonusInput = EditText(this).apply {
            hint = "Total bonus trial days"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(user.bonusTrialDays.toString())
        }
        val trialDaysConfig = currentConfig.trialDays.toInt()
        val initialStatus = AdminRepository.resolveUserAccountStatus(user, trialDaysConfig)
        var trialExplicitlyExpired = initialStatus == com.example.data.firebase.AdminUserAccountStatus.EXPIRED

        fun updateExpiryPreview() {
            val nowMs = System.currentTimeMillis()
            val pendingBonus = bonusInput.text?.toString()?.trim()?.toLongOrNull()?.coerceIn(0L, 3650L)
                ?: user.bonusTrialDays
            expiryPreviewView.text = if (pendingExpiryMs > nowMs && proCheck.isChecked) {
                val daysLeft = kotlin.math.ceil(
                    (pendingExpiryMs - nowMs).toDouble() / DialerPreferences.ONE_DAY_MILLIS.toDouble()
                ).toInt().coerceAtLeast(1)
                "Subscription Expiry: ${dateFmt.format(java.util.Date(pendingExpiryMs))} (${daysLeft}d left)"
            } else if (!proCheck.isChecked && !trialExplicitlyExpired) {
                val virtualTrialUser = user.copy(
                    isPremiumUser = false,
                    bonusTrialDays = pendingBonus
                )
                val trialLeft = AdminRepository.getRemainingDaysForUser(virtualTrialUser, trialDaysConfig, nowMs)
                if (trialLeft > 0) {
                    "New / Trial Remaining: ${trialLeft}d left (Bonus +${pendingBonus}d)"
                } else {
                    "Trial / Plan Expired (0d left)"
                }
            } else {
                "Subscription Expiry: Not Active / Expired"
            }
        }
        updateExpiryPreview()

        fun applyDeltaDays(deltaDays: Long) {
            if (deltaDays == 0L) return
            val nowMs = System.currentTimeMillis()
            val isCurrentlyTrialUser = !proCheck.isChecked && pendingExpiryMs <= nowMs && !trialExplicitlyExpired
            if (isCurrentlyTrialUser) {
                val currentBonus = bonusInput.text?.toString()?.trim()?.toLongOrNull()?.coerceIn(0L, 3650L)
                    ?: user.bonusTrialDays
                if (deltaDays > 0L) {
                    val nextBonus = (currentBonus + deltaDays).coerceIn(0L, 3650L)
                    bonusInput.setText(nextBonus.toString())
                    trialExplicitlyExpired = false
                } else {
                    val sub = (-deltaDays).coerceAtMost(3650L)
                    if (sub <= currentBonus) {
                        bonusInput.setText((currentBonus - sub).coerceAtLeast(0L).toString())
                    } else {
                        val trialUser = user.copy(isPremiumUser = false, bonusTrialDays = currentBonus)
                        val rem = AdminRepository.getRemainingDaysForUser(trialUser, trialDaysConfig, nowMs).toLong()
                        val target = (rem - sub).coerceAtLeast(0L)
                        bonusInput.setText("0")
                        if (target <= 0L) {
                            pendingExpiryMs = 0L
                            proCheck.isChecked = false
                            trialExplicitlyExpired = true
                        } else {
                            pendingExpiryMs = nowMs + target * DialerPreferences.ONE_DAY_MILLIS
                            proCheck.isChecked = true
                            trialExplicitlyExpired = false
                        }
                    }
                }
                updateExpiryPreview()
                return
            }

            val virtualUser = user.copy(
                isPremiumUser = proCheck.isChecked || pendingExpiryMs > nowMs,
                subscriptionExpiresAt = if (pendingExpiryMs > 0L) {
                    com.google.firebase.Timestamp(java.util.Date(pendingExpiryMs))
                } else {
                    null
                }
            )
            pendingExpiryMs = AdminRepository.computeAdjustedSubscriptionExpiryMs(
                user = virtualUser,
                deltaDays = deltaDays,
                nowMs = nowMs
            )
            proCheck.isChecked = pendingExpiryMs > nowMs
            trialExplicitlyExpired = pendingExpiryMs <= nowMs
            updateExpiryPreview()
        }

        val quickDaysRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val quickDeltas = listOf(-30L to "-30d", -7L to "-7d", -1L to "-1d", 1L to "+1d", 7L to "+7d", 30L to "+30d")
        quickDeltas.forEach { (delta, label) ->
            val btn = MaterialButton(
                this,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                text = label
                textSize = 11f
                setPadding(6, 0, 6, 0)
                minWidth = 0
                minimumWidth = 0
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                ).apply {
                    marginEnd = 4
                }
                setOnClickListener { applyDeltaDays(delta) }
            }
            quickDaysRow.addView(btn)
        }

        val customDeltaRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8, 0, 8)
        }
        val customDaysInput = EditText(this).apply {
            hint = getString(R.string.admin_manage_sub_days_delta_hint)
            inputType = InputType.TYPE_CLASS_NUMBER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnAddDays = MaterialButton(this).apply {
            text = getString(R.string.admin_manage_add_days_btn)
            setPadding(16, 0, 16, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = 8 }
            setOnClickListener {
                val days = customDaysInput.text?.toString()?.trim()?.toLongOrNull() ?: 0L
                if (days > 0L) {
                    applyDeltaDays(days)
                    customDaysInput.setText("")
                }
            }
        }
        val btnSubDays = MaterialButton(
            this,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = getString(R.string.admin_manage_sub_days_btn)
            setPadding(16, 0, 16, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = 8 }
            setOnClickListener {
                val days = customDaysInput.text?.toString()?.trim()?.toLongOrNull() ?: 0L
                if (days > 0L) {
                    applyDeltaDays(-days)
                    customDaysInput.setText("")
                }
            }
        }
        customDeltaRow.addView(customDaysInput)
        customDeltaRow.addView(btnAddDays)
        customDeltaRow.addView(btnSubDays)

        val noteInput = EditText(this).apply {
            hint = "Admin note"
            setText(user.adminNote)
        }
        val autoRecordCheck = CheckBox(this).apply {
            text = getString(R.string.admin_manage_auto_record)
            isChecked = user.autoCallRecording
        }
        val blockCheck = CheckBox(this).apply {
            text = "Block / Suspend User"
            isChecked = user.isBlocked && !isTargetSuperAdmin
            isEnabled = !isTargetSuperAdmin
        }
        val hideSubCheck = CheckBox(this).apply {
            text = "Hide Subscription & Payment UI for this user"
            isChecked = user.hideSubscriptionUI
        }
        val resetDeviceCheck = CheckBox(this).apply {
            text = getString(R.string.admin_manage_reset_device)
            isChecked = false
        }
        val delegateAdminCheck = CheckBox(this).apply {
            text = getString(R.string.admin_manage_delegate_admin)
            isChecked = UpiPaymentHelper.isDelegatedAdminAuthorized(user.isAdmin, user.adminGrantedBy)
            visibility = if (isActingSuperAdmin && !isTargetSuperAdmin) View.VISIBLE else View.GONE
        }

        container.addView(infoView)
        container.addView(nameInput)
        container.addView(phoneInput)
        container.addView(subDaysHeader)
        container.addView(expiryPreviewView)
        container.addView(quickDaysRow)
        container.addView(customDeltaRow)
        container.addView(bonusInput)
        container.addView(noteInput)
        container.addView(proCheck)
        container.addView(autoRecordCheck)
        container.addView(blockCheck)
        container.addView(hideSubCheck)
        container.addView(resetDeviceCheck)
        container.addView(delegateAdminCheck)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(user.displayName.ifBlank { user.email.ifBlank { "Manage User" } })
            .setView(scrollView)
            .setNeutralButton("Copy UID") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText("UID", user.userId))
                Toast.makeText(this, "UID copied", Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton("Save") { _, _ ->
                val bonus = bonusInput.text.toString().trim().toLongOrNull() ?: user.bonusTrialDays
                val note = noteInput.text.toString().trim()
                val nextName = nameInput.text.toString().trim()
                val nextPhone = phoneInput.text.toString().trim()
                val nowMs = System.currentTimeMillis()
                val nextPremium = proCheck.isChecked && !blockCheck.isChecked &&
                    (pendingExpiryMs <= 0L || pendingExpiryMs > nowMs)
                val expiryTs = if (nextPremium && pendingExpiryMs > nowMs) {
                    com.google.firebase.Timestamp(java.util.Date(pendingExpiryMs))
                } else {
                    null
                }
                val nextAdminGrant = if (isActingSuperAdmin && !isTargetSuperAdmin) {
                    delegateAdminCheck.isChecked
                } else {
                    user.isAdmin
                }
                val nextStatusOverride = if (!nextPremium && !blockCheck.isChecked && trialExplicitlyExpired) {
                    UserEntitlement.STATUS_EXPIRED
                } else {
                    null
                }
                lifecycleScope.launch {
                    val result = adminRepository.updateFullUserManagement(
                        user = user,
                        displayName = nextName,
                        phoneNumber = nextPhone,
                        isPremium = nextPremium,
                        isBlocked = blockCheck.isChecked && !isTargetSuperAdmin,
                        bonusTrialDays = bonus.coerceIn(0L, 3650L),
                        autoCallRecording = autoRecordCheck.isChecked,
                        hideSubscriptionUI = hideSubCheck.isChecked,
                        adminNote = note,
                        subscriptionExpiresAt = expiryTs,
                        resetDeviceLock = resetDeviceCheck.isChecked,
                        grantAdminAccess = nextAdminGrant,
                        actingAdminEmail = actingEmail,
                        explicitSubscriptionStatus = nextStatusOverride
                    )
                    result.onSuccess {
                        Toast.makeText(
                            this@AdminPanelActivity,
                            getString(R.string.admin_user_updated, nextName.ifBlank { user.email }),
                            Toast.LENGTH_SHORT
                        ).show()
                    }.onFailure { err ->
                        Toast.makeText(
                            this@AdminPanelActivity,
                            getString(R.string.admin_action_failed, err.localizedMessage ?: "Error"),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
            .setNegativeButton(R.string.dialog_close, null)
            .create()

        if (!isTargetSuperAdmin) {
            val deleteBtn = MaterialButton(
                this,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                text = getString(R.string.admin_delete_user_confirm)
                setTextColor(ContextCompat.getColor(this@AdminPanelActivity, R.color.md_theme_error))
                strokeColor = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(this@AdminPanelActivity, R.color.md_theme_error)
                )
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 24 }
                setOnClickListener {
                    dialog.dismiss()
                    confirmDeleteUser(user)
                }
            }
            container.addView(deleteBtn)
        }

        dialog.show()
    }

    private fun showAddUserDialog() {
        val actingEmail = GoogleAuthHelper.resolveActiveEmail(this)
        val isActingSuperAdmin = UpiPaymentHelper.isSuperAdminEmail(actingEmail)

        val scrollView = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
        }
        scrollView.addView(container)

        val nameInput = EditText(this).apply {
            hint = getString(R.string.admin_manage_display_name_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        val emailInput = EditText(this).apply {
            hint = getString(R.string.admin_add_user_email_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        val phoneInput = EditText(this).apply {
            hint = getString(R.string.admin_manage_phone_hint)
            inputType = InputType.TYPE_CLASS_PHONE
        }
        val customUidInput = EditText(this).apply {
            hint = getString(R.string.admin_add_user_uid_hint)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val activateProCheck = CheckBox(this).apply {
            text = getString(R.string.admin_add_user_activate_pro)
            isChecked = false
        }
        val subDaysInput = EditText(this).apply {
            hint = getString(R.string.admin_add_user_sub_days_hint)
            inputType = InputType.TYPE_CLASS_NUMBER
            setText("30")
            visibility = View.GONE
        }
        val bonusDaysInput = EditText(this).apply {
            hint = getString(R.string.admin_add_user_bonus_days_hint)
            inputType = InputType.TYPE_CLASS_NUMBER
            setText("0")
        }
        activateProCheck.setOnCheckedChangeListener { _, checked ->
            subDaysInput.visibility = if (checked) View.VISIBLE else View.GONE
        }
        val autoRecordCheck = CheckBox(this).apply {
            text = getString(R.string.admin_manage_auto_record)
            isChecked = currentConfig.defaultAutoRecording
        }
        val hideSubCheck = CheckBox(this).apply {
            text = "Hide Subscription & Payment UI for this user"
            isChecked = false
        }
        val grantAdminCheck = CheckBox(this).apply {
            text = getString(R.string.admin_manage_delegate_admin)
            isChecked = false
            visibility = if (isActingSuperAdmin) View.VISIBLE else View.GONE
        }
        val noteInput = EditText(this).apply {
            hint = "Admin note"
        }

        container.addView(nameInput)
        container.addView(emailInput)
        container.addView(phoneInput)
        container.addView(customUidInput)
        container.addView(activateProCheck)
        container.addView(subDaysInput)
        container.addView(bonusDaysInput)
        container.addView(autoRecordCheck)
        container.addView(hideSubCheck)
        container.addView(grantAdminCheck)
        container.addView(noteInput)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.admin_add_user_title)
            .setView(scrollView)
            .setPositiveButton(R.string.admin_add_user_create_btn) { _, _ ->
                val name = nameInput.text?.toString().orEmpty().trim()
                val email = emailInput.text?.toString().orEmpty().trim()
                val phone = phoneInput.text?.toString().orEmpty().trim()
                val customUid = customUidInput.text?.toString().orEmpty().trim()
                val isPro = activateProCheck.isChecked
                val subDays = subDaysInput.text?.toString()?.trim()?.toLongOrNull() ?: 30L
                val bonusDays = bonusDaysInput.text?.toString()?.trim()?.toLongOrNull() ?: 0L
                val note = noteInput.text?.toString().orEmpty().trim()
                val grantAdmin = isActingSuperAdmin && grantAdminCheck.isChecked

                lifecycleScope.launch {
                    val result = adminRepository.createUserByAdmin(
                        displayName = name,
                        email = email,
                        phoneNumber = phone,
                        isPremium = isPro,
                        subscriptionDays = subDays,
                        bonusTrialDays = bonusDays,
                        autoCallRecording = autoRecordCheck.isChecked,
                        hideSubscriptionUI = hideSubCheck.isChecked,
                        adminNote = note,
                        grantAdminAccess = grantAdmin,
                        actingAdminEmail = actingEmail,
                        customUid = customUid
                    )
                    result.onSuccess { createdUser ->
                        rtdbUsersById[createdUser.uid] = createdUser
                        firestoreUsersById[createdUser.uid] = createdUser
                        mergeAndPublishUsers()
                        Toast.makeText(
                            this@AdminPanelActivity,
                            getString(
                                R.string.admin_add_user_success,
                                createdUser.displayName.ifBlank { createdUser.email.ifBlank { createdUser.uid } }
                            ),
                            Toast.LENGTH_SHORT
                        ).show()
                    }.onFailure { err ->
                        Toast.makeText(
                            this@AdminPanelActivity,
                            getString(R.string.admin_action_failed, err.localizedMessage ?: "Error"),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
            .setNegativeButton(R.string.dialog_close, null)
            .show()
    }

    private fun confirmDeleteUser(user: UserEntitlement) {
        if (UpiPaymentHelper.isSuperAdminEmail(user.email)) {
            Toast.makeText(this, R.string.admin_delete_super_admin_blocked, Toast.LENGTH_SHORT).show()
            return
        }
        val actingEmail = GoogleAuthHelper.resolveActiveEmail(this)
        val label = user.displayName.ifBlank { user.email.ifBlank { user.userId } }
        val identifier = user.email.ifBlank { user.phoneNumber.ifBlank { user.userId } }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.admin_delete_user_title)
            .setMessage(getString(R.string.admin_delete_user_message, label, identifier))
            .setPositiveButton(R.string.admin_delete_user_confirm) { _, _ ->
                lifecycleScope.launch {
                    val result = adminRepository.deleteUserByAdmin(user, actingEmail)
                    result.onSuccess {
                        val uid = user.userId.ifBlank { user.id }
                        firestoreUsersById.remove(uid)
                        rtdbUsersById.remove(uid)
                        mergeAndPublishUsers()
                        Toast.makeText(
                            this@AdminPanelActivity,
                            getString(R.string.admin_delete_user_success, label),
                            Toast.LENGTH_SHORT
                        ).show()
                    }.onFailure { err ->
                        Toast.makeText(
                            this@AdminPanelActivity,
                            getString(R.string.admin_action_failed, err.localizedMessage ?: "Error"),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
            .setNegativeButton(R.string.dialog_close, null)
            .show()
    }

    private fun handleApprovePayment(request: PaymentRequest) {
        val locks = currentEffectiveApprovedUtrKeys()
        lifecycleScope.launch {
            val result = paymentRepository.approvePaymentRequest(
                request = request,
                knownRequests = allPayments,
                approvedUtrKeys = locks
            )
            result.onSuccess {
                Toast.makeText(
                    this@AdminPanelActivity,
                    getString(R.string.admin_approved_toast, request.userName),
                    Toast.LENGTH_SHORT
                ).show()
            }.onFailure { err ->
                Toast.makeText(
                    this@AdminPanelActivity,
                    getString(R.string.admin_action_failed, err.localizedMessage ?: "Error"),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun showDisapprovePaymentDialog(request: PaymentRequest) {
        val reasonInput = EditText(this).apply {
            hint = "Disapproval reason"
            setText("Payment not verified on ${request.upiId.ifBlank { UpiPaymentHelper.UPI_VPA }}")
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 8)
            addView(reasonInput)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.admin_disapprove)
            .setView(container)
            .setPositiveButton(R.string.admin_disapprove) { _, _ ->
                val note = reasonInput.text.toString().trim()
                lifecycleScope.launch {
                    val result = paymentRepository.rejectPaymentRequest(
                        request = request,
                        reviewNote = note.ifBlank { "Disapproved by Admin" }
                    )
                    result.onSuccess {
                        Toast.makeText(
                            this@AdminPanelActivity,
                            getString(R.string.admin_rejected_toast, request.userName),
                            Toast.LENGTH_SHORT
                        ).show()
                    }.onFailure { err ->
                        Toast.makeText(
                            this@AdminPanelActivity,
                            getString(R.string.admin_action_failed, err.localizedMessage ?: "Error"),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
            .setNegativeButton(R.string.dialog_close, null)
            .show()
    }

    companion object {
        fun createIntent(context: Context): Intent = Intent(context, AdminPanelActivity::class.java)
    }
}
