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
import com.example.R
import com.example.billing.UpiPaymentHelper
import com.example.data.DialerPreferences
import com.example.data.firebase.AdminPaymentFilter
import com.example.data.firebase.AdminRepository
import com.example.data.firebase.AdminSubscriberFilter
import com.example.data.firebase.AdminUserFilter
import com.example.data.firebase.AppConfig
import com.example.data.firebase.DeviceIdentityProvider
import com.example.data.firebase.DynamicSettingItem
import com.example.data.firebase.LiveBroadcast
import com.example.data.firebase.NavigationTabItem
import com.example.data.firebase.PaymentRepository
import com.example.data.firebase.PaymentRequest
import com.example.data.firebase.UserEntitlement
import com.example.databinding.ActivityAdminPanelBinding
import com.example.ui.account.PaymentRequestsAdapter
import androidx.core.content.ContextCompat
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
    private var allPayments: List<PaymentRequest> = emptyList()
    private var currentConfig: AppConfig = AppConfig(id = AppConfig.DOC_GLOBAL_SETTINGS)
    private var currentNavTabs: List<NavigationTabItem> = NavigationTabItem.defaultTabs()
    private var currentSettingItems: List<DynamicSettingItem> = DynamicSettingItem.defaultItems()
    private var currentBroadcast: LiveBroadcast = LiveBroadcast()

    private var suppressHideSubSwitchListener = false

    private var currentUserFilter: AdminUserFilter = AdminUserFilter.ALL
    private var currentUserQuery: String = ""

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityAdminPanelBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val currentUser = Firebase.auth.currentUser
        if (currentUser == null || !UpiPaymentHelper.isAdminEmail(currentUser.email)) {
            Toast.makeText(
                this,
                getString(R.string.admin_access_denied, UpiPaymentHelper.ADMIN_EMAIL),
                Toast.LENGTH_LONG
            ).show()
            finish()
            return
        }

        prefs = DialerPreferences(this)
        adminRepository = AdminRepository(this)
        paymentRepository = PaymentRepository(this)

        applyInsets()
        setupToolbar(currentUser.email.orEmpty())
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
                if (rtdbUsers.isNotEmpty() || allUsers.isEmpty()) {
                    val mergedById = allUsers.associateBy { it.uid }.toMutableMap()
                    rtdbUsers.forEach { rtdbUser ->
                        mergedById[rtdbUser.uid] = rtdbUser
                    }
                    allUsers = mergedById.values.toList()
                    val nowMs = System.currentTimeMillis()
                    expiringSubscriptionUsers = allUsers.filter { user ->
                        user.isPremiumUser && !user.isBlocked &&
                            AdminRepository.getSubscriptionRemainingDays(user, nowMs) in 1..AdminRepository.SUB_EXPIRING_SOON_DAYS
                    }
                    allActiveSubscribers = allUsers.filter { user ->
                        user.isPremiumUser && !user.isBlocked &&
                            AdminRepository.getSubscriptionRemainingDays(user, nowMs) != 0
                    }
                    updateKpis()
                    applyUserFilters()
                    applySubscriberFilters()
                }
            }
        )
        rtdbAdminPaymentsCleanup = com.example.data.firebase.RealtimeDatabaseSync.attachAllPaymentRequestsListener(
            context = this,
            onUpdate = { rtdbRequests ->
                if (rtdbRequests.isNotEmpty() || allPayments.isEmpty()) {
                    val mergedById = allPayments.associateBy { it.id }.toMutableMap()
                    rtdbRequests.forEach { req ->
                        if (req.id.isNotBlank()) {
                            mergedById[req.id] = req
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
                    updateKpis()
                    applyPaymentFilters()
                }
            }
        )
        rtdbAdminConfigCleanup = com.example.data.firebase.RealtimeDatabaseSync.attachAppConfigListener(
            context = this,
            currentFallback = { currentConfig },
            onUpdate = { rtdbConfig ->
                currentConfig = rtdbConfig
                prefs.applyAppConfig(rtdbConfig)
                usersAdapter.trialDaysConfig = rtdbConfig.trialDays.toInt()
                bindSettingsInputs(rtdbConfig)
                applyUserFilters()
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
        rtdbAdminBroadcastCleanup = com.example.data.firebase.RealtimeDatabaseSync.attachLiveBroadcastListener(
            context = this,
            onUpdate = { broadcast ->
                bindBroadcastInputs(broadcast)
            }
        )
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
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        val currentUser = Firebase.auth.currentUser
        if (currentUser == null || !UpiPaymentHelper.isAdminEmail(currentUser.email)) {
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
        binding.adminToolbar.subtitle = adminEmail
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
            onRevokeProClick = { user -> revokeUserPro(user) }
        )
        binding.recyclerAdminExpiring.layoutManager = LinearLayoutManager(this)
        binding.recyclerAdminExpiring.adapter = expiringUsersAdapter

        binding.etAdminExpiringSearch.doAfterTextChanged { editable ->
            currentSubscriberQuery = editable?.toString().orEmpty()
            applySubscriberFilters()
        }

        binding.rgAdminSubscriberFilter.setOnCheckedChangeListener { _, checkedId ->
            currentSubscriberFilter = if (checkedId == R.id.rb_subscriber_all_active) {
                AdminSubscriberFilter.ALL_ACTIVE_SUBSCRIBERS
            } else {
                AdminSubscriberFilter.EXPIRING_SOON
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
            val result = if (requestedFilter == AdminSubscriberFilter.ALL_ACTIVE_SUBSCRIBERS) {
                adminRepository.fetchAllActiveSubscribers()
            } else {
                adminRepository.fetchUsersExpiringWithinDays(
                    days = AdminRepository.SUB_EXPIRING_SOON_DAYS
                )
            }
            binding.progressAdminExpiring.visibility = View.GONE
            binding.btnRefreshExpiring.isEnabled = true
            result.onSuccess { list ->
                if (requestedFilter == AdminSubscriberFilter.ALL_ACTIVE_SUBSCRIBERS) {
                    allActiveSubscribers = list
                } else {
                    expiringSubscriptionUsers = list
                }
                applySubscriberFilters()
            }.onFailure { err ->
                Toast.makeText(
                    this@AdminPanelActivity,
                    getString(R.string.admin_action_failed, err.localizedMessage ?: "Error"),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun applySubscriberFilters() {
        val sourceList = if (currentSubscriberFilter == AdminSubscriberFilter.ALL_ACTIVE_SUBSCRIBERS) {
            allActiveSubscribers
        } else {
            expiringSubscriptionUsers
        }
        val filtered = AdminRepository.filterSubscribers(
            subscribers = sourceList,
            filter = currentSubscriberFilter,
            query = currentSubscriberQuery
        )

        val isAllActive = currentSubscriberFilter == AdminSubscriberFilter.ALL_ACTIVE_SUBSCRIBERS
        binding.cardSubscriberSummary.setCardBackgroundColor(
            ContextCompat.getColor(
                this,
                if (isAllActive) R.color.md_theme_primaryContainer else R.color.md_theme_errorContainer
            )
        )
        binding.cardSubscriberSummary.strokeColor = ContextCompat.getColor(
            this,
            if (isAllActive) R.color.md_theme_primary else R.color.md_theme_error
        )
        val bannerTextColor = ContextCompat.getColor(
            this,
            if (isAllActive) R.color.md_theme_onPrimaryContainer else R.color.md_theme_onErrorContainer
        )
        binding.tvSubscriberBannerTitle.setTextColor(bannerTextColor)
        binding.tvExpiringQueryCount.setTextColor(bannerTextColor)
        binding.tvSubscriberBannerDesc.setTextColor(bannerTextColor)

        binding.tvSubscriberBannerTitle.text = getString(
            if (isAllActive) R.string.admin_active_banner_title else R.string.admin_expiring_banner_title
        )
        binding.tvSubscriberBannerDesc.text = getString(
            if (isAllActive) R.string.admin_active_banner_desc else R.string.admin_expiring_banner_desc
        )
        binding.tvExpiringQueryCount.text = getString(
            if (isAllActive) R.string.admin_active_query_count else R.string.admin_expiring_query_count,
            filtered.size
        )

        binding.tvAdminExpiringEmpty.text = when {
            isAllActive -> getString(R.string.admin_active_empty)
            currentSubscriberQuery.isNotBlank() -> getString(R.string.admin_expiring_search_empty)
            else -> getString(R.string.admin_expiring_empty)
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
            onExtendTrialClick = { user -> extendUserTrial(user, 7L) },
            onToggleBlockClick = { user -> toggleUserBlock(user) },
            onManageClick = { user -> showManageUserDialog(user) },
            onApproveUtrClick = { user -> approveUserUtrDirect(user) },
            onRejectUtrClick = { user -> rejectUserUtrDirect(user) }
        )
        binding.recyclerAdminUsers.layoutManager = LinearLayoutManager(this)
        binding.recyclerAdminUsers.adapter = usersAdapter

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
        binding.btnBroadcastSendNow.setOnClickListener {
            val title = binding.etBroadcastTitle.text?.toString()?.trim().orEmpty()
            val message = binding.etBroadcastMessage.text?.toString()?.trim().orEmpty()
            if (title.isBlank() && message.isBlank()) {
                Toast.makeText(this, "Enter popup title or message", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val next = currentBroadcast.copy(
                showPopup = true,
                popupId = "pop_${System.currentTimeMillis()}",
                title = title,
                message = message,
                imageUrl = binding.etBroadcastImageUrl.text?.toString()?.trim().orEmpty(),
                buttonText = binding.etBroadcastBtnText.text?.toString()?.trim()?.ifBlank { "Open" } ?: "Open",
                actionUrl = binding.etBroadcastActionUrl.text?.toString()?.trim().orEmpty(),
                isCancelable = binding.switchBroadcastCancelable.isChecked
            )
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
                hideSubscriptionUI = prefs.configHideSubscriptionUi
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
            hideSubscriptionUI = binding.switchConfigHideSubUi.isChecked
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
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    adminRepository.observeAllUsers()
                        .catch { /* logged in repository */ }
                        .collect { users ->
                            allUsers = users
                            updateKpis()
                            applyUserFilters()
                        }
                }
                launch {
                    adminRepository.observeUsersExpiringWithinDays(
                        days = AdminRepository.SUB_EXPIRING_SOON_DAYS
                    )
                        .catch { /* logged in repository */ }
                        .collect { expiring ->
                            expiringSubscriptionUsers = expiring
                            applySubscriberFilters()
                        }
                }
                launch {
                    adminRepository.observeAllActiveSubscribers()
                        .catch { /* logged in repository */ }
                        .collect { activeList ->
                            allActiveSubscribers = activeList
                            applySubscriberFilters()
                        }
                }
                launch {
                    paymentRepository.observeAllPaymentRequestsForAdmin()
                        .catch { /* logged in repository */ }
                        .collect { requests ->
                            allPayments = requests
                            updateKpis()
                            applyPaymentFilters()
                        }
                }
                launch {
                    adminRepository.observeGlobalAppConfig()
                        .catch { /* logged in repository */ }
                        .collect { config ->
                            currentConfig = config
                            prefs.applyAppConfig(config)
                            usersAdapter.trialDaysConfig = config.trialDays.toInt()
                            bindSettingsInputs(config)
                            applyUserFilters()
                        }
                }
                launch {
                    adminRepository.observeNavigationTabs()
                        .catch { /* logged in repository */ }
                        .collect { tabsConfig ->
                            currentNavTabs = tabsConfig.tabs
                            navTabsAdapter.submitList(tabsConfig.tabs)
                        }
                }
                launch {
                    adminRepository.observeDynamicSettings()
                        .catch { /* logged in repository */ }
                        .collect { settingsConfig ->
                            currentSettingItems = settingsConfig.items
                            settingsItemsAdapter.submitList(settingsConfig.items)
                        }
                }
                launch {
                    adminRepository.observeLiveBroadcast()
                        .catch { /* logged in repository */ }
                        .collect { broadcast ->
                            bindBroadcastInputs(broadcast)
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
        val filtered = AdminRepository.filterUsers(
            users = allUsers,
            filter = currentUserFilter,
            query = currentUserQuery,
            trialDaysConfig = currentConfig.trialDays.toInt()
        )
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

    private fun extendUserTrial(user: UserEntitlement, extraDays: Long) {
        val nextBonus = (user.bonusTrialDays + extraDays).coerceIn(0L, 3650L)
        lifecycleScope.launch {
            val result = adminRepository.updateUserAdminDetails(
                user = user,
                isPremium = user.isPremiumUser,
                isBlocked = user.isBlocked,
                bonusTrialDays = nextBonus,
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
        lifecycleScope.launch {
            val pendingReq = allPayments.firstOrNull {
                it.userId == user.userId && it.status == PaymentRequest.STATUS_PENDING
            }
            val result = if (pendingReq != null) {
                paymentRepository.approvePaymentRequest(pendingReq)
            } else {
                adminRepository.approveUserUtrDirect(user)
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
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
        }
        val infoView = TextView(this).apply {
            text = "Email: ${user.email}\nPhone: ${user.phoneNumber.ifBlank { "—" }}\nUID: ${user.userId}\nDevice: $deviceTag"
        }
        val bonusInput = EditText(this).apply {
            hint = "Total bonus trial days"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(user.bonusTrialDays.toString())
        }
        val noteInput = EditText(this).apply {
            hint = "Admin note"
            setText(user.adminNote)
        }
        val proCheck = CheckBox(this).apply {
            text = "Premium Active"
            isChecked = user.isPremiumUser
        }
        val blockCheck = CheckBox(this).apply {
            text = "Block / Suspend User"
            isChecked = user.isBlocked
        }
        val hideSubCheck = CheckBox(this).apply {
            text = "Hide Subscription & Payment UI for this user"
            isChecked = user.hideSubscriptionUI
        }
        container.addView(infoView)
        container.addView(bonusInput)
        container.addView(noteInput)
        container.addView(proCheck)
        container.addView(blockCheck)
        container.addView(hideSubCheck)

        MaterialAlertDialogBuilder(this)
            .setTitle(user.displayName.ifBlank { user.email.ifBlank { "Manage User" } })
            .setView(container)
            .setNeutralButton("Copy UID") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText("UID", user.userId))
                Toast.makeText(this, "UID copied", Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton("Save") { _, _ ->
                val bonus = bonusInput.text.toString().trim().toLongOrNull() ?: user.bonusTrialDays
                val note = noteInput.text.toString().trim()
                lifecycleScope.launch {
                    val result = adminRepository.updateUserAdminDetails(
                        user = user,
                        isPremium = proCheck.isChecked,
                        isBlocked = blockCheck.isChecked,
                        bonusTrialDays = bonus.coerceIn(0L, 3650L),
                        adminNote = note,
                        hideSubscriptionUI = hideSubCheck.isChecked
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
            .setNegativeButton(R.string.dialog_close, null)
            .show()
    }

    private fun handleApprovePayment(request: PaymentRequest) {
        lifecycleScope.launch {
            val result = paymentRepository.approvePaymentRequest(request)
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
