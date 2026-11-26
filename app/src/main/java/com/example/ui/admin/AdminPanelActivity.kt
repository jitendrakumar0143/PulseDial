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
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import com.google.firebase.Timestamp
import com.google.firebase.auth.auth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import java.util.Date

class AdminPanelActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAdminPanelBinding
    private lateinit var prefs: DialerPreferences
    private val adminRepository: AdminRepository by lazy { AdminRepository(this) }
    private val paymentRepository: PaymentRepository by lazy { PaymentRepository(this) }

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

    private var uploadedPopupAdImageDataUri: String = ""
    private var uploadedBroadcastImageDataUri: String = ""
    private var uploadedBannerImageDataUri: String = ""
    private var suppressImageUrlTextWatcher = false

    private val popupAdImagePickerLauncher =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) handlePickedPopupImage(uri, ImageSlot.CUSTOM_POPUP_AD)
        }

    private val broadcastImagePickerLauncher =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) handlePickedPopupImage(uri, ImageSlot.BROADCAST_POPUP)
        }

    private val bannerImagePickerLauncher =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) handlePickedPopupImage(uri, ImageSlot.BANNER_AD)
        }

    private enum class ImageSlot {
        CUSTOM_POPUP_AD,
        BROADCAST_POPUP,
        BANNER_AD
    }

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
    private var usersDatabaseRef: DatabaseReference? = null
    private var usersValueEventListener: ValueEventListener? = null
    private var usersRtdbSyncErrorMessage: String? = null
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

        // Repositories are initialized lazily so AdminPanelActivity stays crash-free if FirebaseApp is not ready yet.

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
        attachUsersValueEventListener()
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

    private fun attachUsersValueEventListener() {
        detachUsersValueEventListener()
        val ref = RealtimeDatabaseSync.getUsersReference(this) ?: return
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (isFinishing || isDestroyed) return
                usersRtdbSyncErrorMessage = null
                binding.tvAdminUserDateSummary.setOnClickListener(null)
                val parsedUsers = RealtimeDatabaseSync.parseUsersListSnapshot(snapshot)
                handleUsersRtdbSnapshot(parsedUsers)
            }

            override fun onCancelled(error: DatabaseError) {
                if (isFinishing || isDestroyed) return
                android.util.Log.w("AdminPanelActivity", "RTDB /users ValueEventListener cancelled: ${error.message}")
                usersRtdbSyncErrorMessage = error.message
                binding.tvAdminUserDateSummary.text =
                    "RTDB /users sync error: ${error.message} — Tap to retry"
                binding.tvAdminUserDateSummary.setOnClickListener {
                    attachUsersValueEventListener()
                }
            }
        }
        usersDatabaseRef = ref
        usersValueEventListener = listener
        ref.addValueEventListener(listener)
    }

    private fun detachUsersValueEventListener() {
        val ref = usersDatabaseRef
        val listener = usersValueEventListener
        if (ref != null && listener != null) {
            runCatching { ref.removeEventListener(listener) }
        }
        usersDatabaseRef = null
        usersValueEventListener = null
    }

    /**
     * Reconciles live `/users` RTDB snapshots against cached Firestore users (removing any UID
     * that was deleted from `/users`) and immediately publishes updated KPIs & filtered lists.
     */
    internal fun handleUsersRtdbSnapshot(rtdbUsers: List<UserEntitlement>) {
        val previousRtdbIds = rtdbUsersById.keys.toSet()
        val incomingIds = HashSet<String>(rtdbUsers.size)
        rtdbUsersById.clear()
        rtdbUsers.forEach { user ->
            val uid = user.uid.trim()
            if (uid.isNotEmpty()) {
                incomingIds.add(uid)
                rtdbUsersById[uid] = user
            }
        }
        // Reconcile deletions: if a UID previously existed in RTDB and disappeared, purge stale copy
        previousRtdbIds.forEach { oldUid ->
            if (oldUid !in incomingIds) {
                firestoreUsersById.remove(oldUid)
            }
        }
        mergeAndPublishUsers()
    }

    override fun onDestroy() {
        detachUsersValueEventListener()
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
        binding.chipKpiUsers.setOnClickListener {
            binding.chipGroupUserFilter.check(R.id.chip_user_filter_all)
            binding.adminTabLayout.getTabAt(0)?.select()
        }
        binding.chipKpiExpiring.setOnClickListener {
            binding.rgAdminSubscriberFilter.check(R.id.rb_subscriber_expiring_soon)
            binding.adminTabLayout.getTabAt(1)?.select()
        }
        binding.chipKpiPremium.setOnClickListener {
            binding.rgAdminSubscriberFilter.check(R.id.rb_subscriber_all_active)
            binding.adminTabLayout.getTabAt(1)?.select()
        }
        binding.chipKpiPending.setOnClickListener {
            binding.chipGroupPaymentFilter.check(R.id.chip_pay_filter_pending)
            binding.adminTabLayout.getTabAt(2)?.select()
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
            if (runCatching { Firebase.auth.currentUser }.getOrNull() == null) {
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
        setUserPremiumStatus(user = user, targetPremium = false)
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

        binding.chipDateFieldToggle.text = "📅 By: Registration Date"
        binding.chipDateFieldToggle.setOnClickListener {
            currentUserDateField = when (currentUserDateField) {
                AdminUserDateField.JOIN_DATE -> AdminUserDateField.EXPIRY_DATE
                AdminUserDateField.EXPIRY_DATE -> AdminUserDateField.UPDATED_DATE
                AdminUserDateField.UPDATED_DATE -> AdminUserDateField.JOIN_DATE
            }
            binding.chipDateFieldToggle.text = when (currentUserDateField) {
                AdminUserDateField.JOIN_DATE -> "📅 By: Registration Date"
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
        binding.btnToggleUserDateFilters.setOnClickListener {
            val show = binding.layoutUserDateFilters.visibility != View.VISIBLE
            binding.layoutUserDateFilters.visibility = if (show) View.VISIBLE else View.GONE
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
            onRejectClick = { request -> showDisapprovePaymentDialog(request) },
            onDeleteClick = { request -> confirmDeletePaymentRequest(request) }
        )
        binding.recyclerAdminPayments.layoutManager = LinearLayoutManager(this)
        binding.recyclerAdminPayments.adapter = paymentsAdapter

        binding.btnAdminDeletePaymentHistory.setOnClickListener {
            confirmClearPaymentHistory()
        }

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

    private var suppressBroadcastActiveSwitchListener = false

    private fun handlePickedPopupImage(uri: android.net.Uri, slot: ImageSlot) {
        Toast.makeText(this, R.string.admin_popup_image_uploading, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val encoded = com.example.ui.common.PopupImageHelper.encodeImageUriToDataUri(
                context = this@AdminPanelActivity,
                uri = uri
            )
            if (encoded.isNullOrBlank()) {
                Toast.makeText(
                    this@AdminPanelActivity,
                    R.string.admin_popup_image_upload_failed,
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            val kb = com.example.ui.common.PopupImageHelper.approxKilobytes(encoded)
            when (slot) {
                ImageSlot.CUSTOM_POPUP_AD -> {
                    uploadedPopupAdImageDataUri = encoded
                    suppressImageUrlTextWatcher = true
                    binding.etPopupAdImageUrl.setText("")
                    suppressImageUrlTextWatcher = false
                    updatePopupAdImagePreview(encoded)
                }
                ImageSlot.BROADCAST_POPUP -> {
                    uploadedBroadcastImageDataUri = encoded
                    suppressImageUrlTextWatcher = true
                    binding.etBroadcastImageUrl.setText("")
                    suppressImageUrlTextWatcher = false
                    updateBroadcastImagePreview(encoded)
                }
                ImageSlot.BANNER_AD -> {
                    uploadedBannerImageDataUri = encoded
                    suppressImageUrlTextWatcher = true
                    binding.etBannerAdImageUrl.setText("")
                    suppressImageUrlTextWatcher = false
                    updateBannerImagePreview(encoded)
                }
            }
            Toast.makeText(
                this@AdminPanelActivity,
                getString(R.string.admin_popup_image_uploaded_toast, kb),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun resolveEffectivePopupAdImage(): String {
        return uploadedPopupAdImageDataUri.ifBlank {
            binding.etPopupAdImageUrl.text?.toString()?.trim().orEmpty()
        }
    }

    private fun resolveEffectiveBroadcastImage(): String {
        return uploadedBroadcastImageDataUri.ifBlank {
            binding.etBroadcastImageUrl.text?.toString()?.trim().orEmpty()
        }
    }

    private fun resolveEffectiveBannerImage(): String {
        return uploadedBannerImageDataUri.ifBlank {
            binding.etBannerAdImageUrl.text?.toString()?.trim().orEmpty()
        }
    }

    private fun selectedPopupAdSize(): String {
        return when (binding.chipGroupPopupAdSize.checkedChipId) {
            R.id.chip_popup_ad_size_small -> CustomPopupAd.SIZE_SMALL
            R.id.chip_popup_ad_size_large -> CustomPopupAd.SIZE_LARGE
            R.id.chip_popup_ad_size_fullscreen -> CustomPopupAd.SIZE_FULLSCREEN
            else -> CustomPopupAd.SIZE_MEDIUM
        }
    }

    private fun checkPopupAdSizeChip(size: String) {
        val chipId = when (CustomPopupAd.normalizedPopupSize(size)) {
            CustomPopupAd.SIZE_SMALL -> R.id.chip_popup_ad_size_small
            CustomPopupAd.SIZE_LARGE -> R.id.chip_popup_ad_size_large
            CustomPopupAd.SIZE_FULLSCREEN -> R.id.chip_popup_ad_size_fullscreen
            else -> R.id.chip_popup_ad_size_medium
        }
        binding.chipGroupPopupAdSize.check(chipId)
    }

    private fun selectedBroadcastPopupSize(): String {
        return when (binding.chipGroupBroadcastSize.checkedChipId) {
            R.id.chip_broadcast_size_small -> LiveBroadcast.SIZE_SMALL
            R.id.chip_broadcast_size_large -> LiveBroadcast.SIZE_LARGE
            R.id.chip_broadcast_size_fullscreen -> LiveBroadcast.SIZE_FULLSCREEN
            else -> LiveBroadcast.SIZE_MEDIUM
        }
    }

    private fun checkBroadcastPopupSizeChip(size: String) {
        val chipId = when (LiveBroadcast.normalizedPopupSize(size)) {
            LiveBroadcast.SIZE_SMALL -> R.id.chip_broadcast_size_small
            LiveBroadcast.SIZE_LARGE -> R.id.chip_broadcast_size_large
            LiveBroadcast.SIZE_FULLSCREEN -> R.id.chip_broadcast_size_fullscreen
            else -> R.id.chip_broadcast_size_medium
        }
        binding.chipGroupBroadcastSize.check(chipId)
    }

    private fun selectedBroadcastPopupType(): String {
        return if (binding.chipGroupBroadcastType.checkedChipId == R.id.chip_broadcast_type_ad) {
            LiveBroadcast.TYPE_AD
        } else {
            LiveBroadcast.TYPE_UPDATE
        }
    }

    private fun applyBroadcastTypeUi(popupType: String) {
        val isUpdate = popupType != LiveBroadcast.TYPE_AD
        binding.tilBroadcastVersion.visibility = if (isUpdate) View.VISIBLE else View.GONE
        binding.switchBroadcastForceUpdate.visibility = if (isUpdate) View.VISIBLE else View.GONE
        binding.tilBroadcastImageUrl.visibility = View.VISIBLE
        binding.layoutBroadcastImageActions.visibility = View.VISIBLE
        updateBroadcastImagePreview(resolveEffectiveBroadcastImage())
        binding.tilBroadcastTitle.hint = getString(
            if (isUpdate) R.string.admin_broadcast_title_hint else R.string.admin_broadcast_ad_title_hint
        )
        binding.tilBroadcastMessage.hint = getString(
            if (isUpdate) R.string.admin_broadcast_message_hint else R.string.admin_broadcast_ad_desc_hint
        )
        binding.tilBroadcastBtnText.hint = getString(
            if (isUpdate) R.string.admin_broadcast_btn_text_hint else R.string.admin_broadcast_ad_btn_text_hint
        )
    }

    private fun updateBroadcastStatusHeader(broadcast: LiveBroadcast) {
        suppressBroadcastActiveSwitchListener = true
        binding.switchBroadcastActiveToggle.isChecked = broadcast.showPopup
        suppressBroadcastActiveSwitchListener = false

        val idLabel = broadcast.popupId.ifBlank { "default" }
        binding.tvBroadcastLiveStatus.text = if (broadcast.showPopup) {
            getString(R.string.admin_broadcast_status_live, idLabel)
        } else {
            getString(R.string.admin_broadcast_status_inactive)
        }
    }

    private fun setupBroadcastTab() {
        applyBroadcastTypeUi(LiveBroadcast.TYPE_UPDATE)

        binding.chipGroupBroadcastType.setOnCheckedStateChangeListener { _, checkedIds ->
            val checkedId = checkedIds.firstOrNull() ?: R.id.chip_broadcast_type_update
            val type = if (checkedId == R.id.chip_broadcast_type_ad) {
                LiveBroadcast.TYPE_AD
            } else {
                LiveBroadcast.TYPE_UPDATE
            }
            applyBroadcastTypeUi(type)
        }

        binding.switchBroadcastForceUpdate.setOnCheckedChangeListener { _, isChecked ->
            binding.switchBroadcastCancelable.isChecked = !isChecked
        }

        binding.switchBroadcastActiveToggle.setOnCheckedChangeListener { _, isChecked ->
            if (suppressBroadcastActiveSwitchListener) return@setOnCheckedChangeListener
            if (isChecked) {
                val next = buildBroadcastFromInputs(generateFreshId = currentBroadcast.popupId.isBlank())
                if (next == null) {
                    suppressBroadcastActiveSwitchListener = true
                    binding.switchBroadcastActiveToggle.isChecked = false
                    suppressBroadcastActiveSwitchListener = false
                    return@setOnCheckedChangeListener
                }
                saveLiveBroadcast(next, R.string.admin_broadcast_sent_toast)
            } else {
                val next = currentBroadcast.copy(showPopup = false)
                saveLiveBroadcast(next, R.string.admin_broadcast_stopped_toast)
            }
        }

        binding.btnBroadcastUploadImage.setOnClickListener {
            broadcastImagePickerLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }
        binding.btnBroadcastClearImage.setOnClickListener {
            uploadedBroadcastImageDataUri = ""
            suppressImageUrlTextWatcher = true
            binding.etBroadcastImageUrl.setText("")
            suppressImageUrlTextWatcher = false
            updateBroadcastImagePreview("")
        }
        binding.etBroadcastImageUrl.doAfterTextChanged { editable ->
            if (suppressImageUrlTextWatcher) return@doAfterTextChanged
            val url = editable?.toString()?.trim().orEmpty()
            if (url.isNotEmpty()) {
                uploadedBroadcastImageDataUri = ""
            }
            updateBroadcastImagePreview(resolveEffectiveBroadcastImage())
        }

        binding.btnBroadcastPreview.setOnClickListener {
            val previewBroadcast = buildBroadcastFromInputs(generateFreshId = false) ?: return@setOnClickListener
            showBroadcastPreviewDialog(previewBroadcast)
        }

        binding.btnBroadcastSendNow.setOnClickListener {
            val next = buildBroadcastFromInputs(generateFreshId = true) ?: return@setOnClickListener
            saveLiveBroadcast(next, R.string.admin_broadcast_sent_toast)
        }

        binding.btnBroadcastStop.setOnClickListener {
            val next = currentBroadcast.copy(showPopup = false)
            saveLiveBroadcast(next, R.string.admin_broadcast_stopped_toast)
        }

        binding.btnBroadcastDelete.setOnClickListener {
            deleteLiveBroadcastPopup()
        }

        binding.btnBannerAdUploadImage.setOnClickListener {
            bannerImagePickerLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }
        binding.btnBannerAdClearImage.setOnClickListener {
            uploadedBannerImageDataUri = ""
            suppressImageUrlTextWatcher = true
            binding.etBannerAdImageUrl.setText("")
            suppressImageUrlTextWatcher = false
            updateBannerImagePreview("")
        }
        binding.etBannerAdImageUrl.doAfterTextChanged { editable ->
            if (suppressImageUrlTextWatcher) return@doAfterTextChanged
            val url = editable?.toString()?.trim().orEmpty()
            if (url.isNotEmpty()) {
                uploadedBannerImageDataUri = ""
            }
            updateBannerImagePreview(resolveEffectiveBannerImage())
        }

        binding.btnBannerAdSave.setOnClickListener {
            val next = currentBroadcast.copy(
                showBannerAd = binding.switchBannerAdEnable.isChecked,
                bannerText = binding.etBannerAdText.text?.toString()?.trim().orEmpty(),
                bannerImageUrl = resolveEffectiveBannerImage(),
                bannerActionUrl = binding.etBannerAdActionUrl.text?.toString()?.trim().orEmpty()
            )
            saveLiveBroadcast(next, R.string.admin_banner_saved_toast)
        }

        // Initialize Custom Popup Ad upload, preview, live counters & publish buttons
        updatePopupAdCounters(0L, 0L)
        binding.btnPopupAdUploadImage.setOnClickListener {
            popupAdImagePickerLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }
        binding.btnPopupAdClearImage.setOnClickListener {
            uploadedPopupAdImageDataUri = ""
            suppressImageUrlTextWatcher = true
            binding.etPopupAdImageUrl.setText("")
            suppressImageUrlTextWatcher = false
            updatePopupAdImagePreview("")
        }
        binding.etPopupAdImageUrl.doAfterTextChanged { editable ->
            if (suppressImageUrlTextWatcher) return@doAfterTextChanged
            val url = editable?.toString()?.trim().orEmpty()
            if (url.isNotEmpty()) {
                uploadedPopupAdImageDataUri = ""
            }
            updatePopupAdImagePreview(resolveEffectivePopupAdImage())
        }
        binding.btnPopupAdPreview.setOnClickListener {
            val previewAd = buildCustomPopupAdFromInputs(isForPreview = true) ?: return@setOnClickListener
            com.example.ui.ads.CustomAdPopupDialog.showIfNotAlreadyVisible(
                fragmentManager = supportFragmentManager,
                ad = previewAd,
                isPreview = true
            )
        }
        binding.btnPublishCustomPopupAd.setOnClickListener {
            publishCustomPopupAdFromInputs()
        }
        binding.btnPopupAdSendLiveNow.setOnClickListener {
            sendCustomPopupAdAsLiveBroadcastNow()
        }
    }

    private fun buildBroadcastFromInputs(generateFreshId: Boolean = true): LiveBroadcast? {
        val popupType = selectedBroadcastPopupType()
        val popupSize = selectedBroadcastPopupSize()
        val isUpdate = popupType == LiveBroadcast.TYPE_UPDATE
        val title = binding.etBroadcastTitle.text?.toString()?.trim().orEmpty()
        val version = if (isUpdate) binding.etBroadcastVersion.text?.toString()?.trim().orEmpty() else ""
        val message = binding.etBroadcastMessage.text?.toString()?.trim().orEmpty()
        val imageUrl = resolveEffectiveBroadcastImage()
        val actionUrl = binding.etBroadcastActionUrl.text?.toString()?.trim().orEmpty()
        if (title.isBlank() && message.isBlank() && imageUrl.isBlank()) {
            Toast.makeText(this, "Upload a photo or enter image URL, title, or message", Toast.LENGTH_SHORT).show()
            return null
        }
        val forceUpdate = isUpdate && binding.switchBroadcastForceUpdate.isChecked
        val defaultBtn = getString(
            if (isUpdate) R.string.popup_default_update_cta else R.string.popup_default_ad_cta
        )
        val resolvedPopupId = if (generateFreshId || currentBroadcast.popupId.isBlank()) {
            LiveBroadcast.generatePopupId()
        } else {
            currentBroadcast.popupId
        }
        return currentBroadcast.copy(
            showPopup = true,
            popupId = resolvedPopupId,
            popupType = popupType,
            popupSize = popupSize,
            version = version,
            forceUpdate = forceUpdate,
            showOnce = true,
            title = title,
            message = message,
            imageUrl = imageUrl,
            buttonText = binding.etBroadcastBtnText.text?.toString()?.trim()?.ifBlank { defaultBtn } ?: defaultBtn,
            actionUrl = actionUrl,
            isCancelable = !forceUpdate
        )
    }

    private fun sendCustomPopupAdAsLiveBroadcastNow() {
        val imageUrl = resolveEffectivePopupAdImage()
        val popupSize = selectedPopupAdSize()
        val title = binding.etPopupAdTitle.text?.toString()?.trim().orEmpty()
        val message = binding.etPopupAdMessage.text?.toString()?.trim().orEmpty()
        val targetUrl = binding.etPopupAdTargetUrl.text?.toString()?.trim().orEmpty()
        val buttonText = binding.etPopupAdButtonText.text?.toString()?.trim()
            ?.ifBlank { getString(R.string.popup_default_ad_cta) }
            ?: getString(R.string.popup_default_ad_cta)

        if (imageUrl.isBlank() && title.isBlank() && message.isBlank()) {
            Toast.makeText(this, R.string.admin_popup_ad_validation_error, Toast.LENGTH_SHORT).show()
            return
        }

        // Also publish the CustomPopupAd config AND push an immediate one-time Custom Ad popup
        publishCustomPopupAdFromInputs()
        val livePopup = currentBroadcast.copy(
            showPopup = true,
            popupId = LiveBroadcast.generatePopupId(),
            popupType = LiveBroadcast.TYPE_AD,
            popupSize = popupSize,
            version = "",
            forceUpdate = false,
            showOnce = true,
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

    private fun updateBroadcastImagePreview(imageSource: String) {
        if (imageSource.isBlank()) {
            binding.cardBroadcastImagePreview.visibility = View.GONE
            binding.btnBroadcastClearImage.visibility = View.GONE
            return
        }
        binding.btnBroadcastClearImage.visibility = View.VISIBLE
        binding.cardBroadcastImagePreview.visibility = View.VISIBLE
        binding.tvBroadcastImageStatus.text = if (com.example.ui.common.PopupImageHelper.isDataImageUri(imageSource)) {
            getString(
                R.string.admin_popup_image_uploaded_status,
                com.example.ui.common.PopupImageHelper.approxKilobytes(imageSource)
            )
        } else {
            getString(R.string.admin_popup_image_url_status)
        }
        com.example.ui.common.PopupImageHelper.loadPopupImage(
            imageView = binding.ivBroadcastImagePreview,
            rawSource = imageSource,
            onSuccess = {
                binding.cardBroadcastImagePreview.visibility = View.VISIBLE
            },
            onError = {
                if (!com.example.ui.common.PopupImageHelper.isDataImageUri(imageSource)) {
                    binding.cardBroadcastImagePreview.visibility = View.GONE
                }
            }
        )
    }

    private fun updatePopupAdImagePreview(imageSource: String) {
        if (imageSource.isBlank()) {
            binding.cardPopupAdImagePreview.visibility = View.GONE
            binding.btnPopupAdClearImage.visibility = View.GONE
            return
        }
        binding.btnPopupAdClearImage.visibility = View.VISIBLE
        binding.cardPopupAdImagePreview.visibility = View.VISIBLE
        binding.tvPopupAdImageStatus.text = if (com.example.ui.common.PopupImageHelper.isDataImageUri(imageSource)) {
            getString(
                R.string.admin_popup_image_uploaded_status,
                com.example.ui.common.PopupImageHelper.approxKilobytes(imageSource)
            )
        } else {
            getString(R.string.admin_popup_image_url_status)
        }
        com.example.ui.common.PopupImageHelper.loadPopupImage(
            imageView = binding.ivPopupAdImagePreview,
            rawSource = imageSource,
            onSuccess = {
                binding.cardPopupAdImagePreview.visibility = View.VISIBLE
            },
            onError = {
                if (!com.example.ui.common.PopupImageHelper.isDataImageUri(imageSource)) {
                    binding.cardPopupAdImagePreview.visibility = View.GONE
                }
            }
        )
    }

    private fun updateBannerImagePreview(imageSource: String) {
        if (imageSource.isBlank()) {
            binding.cardBannerAdImagePreview.visibility = View.GONE
            binding.btnBannerAdClearImage.visibility = View.GONE
            return
        }
        binding.btnBannerAdClearImage.visibility = View.VISIBLE
        binding.cardBannerAdImagePreview.visibility = View.VISIBLE
        binding.tvBannerAdImageStatus.text = if (com.example.ui.common.PopupImageHelper.isDataImageUri(imageSource)) {
            getString(
                R.string.admin_popup_image_uploaded_status,
                com.example.ui.common.PopupImageHelper.approxKilobytes(imageSource)
            )
        } else {
            getString(R.string.admin_popup_image_url_status)
        }
        com.example.ui.common.PopupImageHelper.loadPopupImage(
            imageView = binding.ivBannerAdImagePreview,
            rawSource = imageSource,
            onSuccess = {
                binding.cardBannerAdImagePreview.visibility = View.VISIBLE
            },
            onError = {
                if (!com.example.ui.common.PopupImageHelper.isDataImageUri(imageSource)) {
                    binding.cardBannerAdImagePreview.visibility = View.GONE
                }
            }
        )
    }

    private fun showBroadcastPreviewDialog(broadcast: LiveBroadcast) {
        com.example.ui.popup.SmokedSapphirePopupDialogFragment.showIfNotAlreadyVisible(
            fragmentManager = supportFragmentManager,
            broadcast = broadcast,
            isPreview = true
        )
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
            checkPopupAdSizeChip(ad.normalizedPopupSize())

            suppressImageUrlTextWatcher = true
            if (com.example.ui.common.PopupImageHelper.isDataImageUri(ad.imageUrl)) {
                uploadedPopupAdImageDataUri = ad.imageUrl
                binding.etPopupAdImageUrl.setText("")
            } else {
                uploadedPopupAdImageDataUri = ""
                binding.etPopupAdImageUrl.setText(ad.imageUrl)
            }
            suppressImageUrlTextWatcher = false
            updatePopupAdImagePreview(ad.imageUrl)

            binding.etPopupAdTitle.setText(ad.title)
            binding.etPopupAdMessage.setText(ad.message)
            binding.etPopupAdButtonText.setText(ad.buttonText)
            binding.etPopupAdTargetUrl.setText(ad.targetUrl)
            binding.etPopupAdCountdown.setText(ad.countdownSeconds.toString())
        }
    }

    private fun buildCustomPopupAdFromInputs(isForPreview: Boolean = false): CustomPopupAd? {
        val isEnabled = binding.switchPopupAdEnable.isChecked
        val showPremium = binding.switchPopupAdShowPremium.isChecked
        val triggerMode = selectedPopupAdTriggerMode()
        val popupSize = selectedPopupAdSize()
        val imageUrl = resolveEffectivePopupAdImage()
        val title = binding.etPopupAdTitle.text?.toString()?.trim().orEmpty()
        val message = binding.etPopupAdMessage.text?.toString()?.trim().orEmpty()
        val buttonText = binding.etPopupAdButtonText.text?.toString()?.trim()
            ?.ifBlank { CustomPopupAd.DEFAULT_BUTTON_TEXT }
            ?: CustomPopupAd.DEFAULT_BUTTON_TEXT
        val targetUrl = binding.etPopupAdTargetUrl.text?.toString()?.trim().orEmpty()
        val countdown = binding.etPopupAdCountdown.text?.toString()?.trim()?.toIntOrNull()
            ?.coerceIn(0, CustomPopupAd.MAX_COUNTDOWN_SECONDS)
            ?: CustomPopupAd.DEFAULT_COUNTDOWN_SECONDS

        if ((isEnabled || isForPreview) && title.isBlank() && message.isBlank() && imageUrl.isBlank()) {
            Toast.makeText(this, R.string.admin_popup_ad_validation_error, Toast.LENGTH_SHORT).show()
            return null
        }

        return CustomPopupAd(
            adId = if (isForPreview) "preview_${System.currentTimeMillis()}" else "ad_${System.currentTimeMillis()}",
            isEnabled = if (isForPreview) true else isEnabled,
            showToPremiumUsers = showPremium,
            triggerMode = triggerMode,
            imageUrl = imageUrl,
            title = title,
            message = message,
            buttonText = buttonText,
            targetUrl = targetUrl,
            countdownSeconds = countdown,
            impressionsCount = currentCustomPopupAd.impressionsCount,
            clicksCount = currentCustomPopupAd.clicksCount,
            popupSize = popupSize
        )
    }

    private fun publishCustomPopupAdFromInputs() {
        val updated = buildCustomPopupAdFromInputs(isForPreview = false) ?: return
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
                updateBroadcastStatusHeader(broadcast)
                Toast.makeText(this@AdminPanelActivity, successToastRes, Toast.LENGTH_SHORT).show()
            }.onFailure { err ->
                updateBroadcastStatusHeader(currentBroadcast)
                Toast.makeText(
                    this@AdminPanelActivity,
                    getString(R.string.admin_action_failed, err.localizedMessage ?: "Error"),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun deleteLiveBroadcastPopup() {
        lifecycleScope.launch {
            val result = adminRepository.deleteLiveBroadcastPopup(currentBroadcast)
            result.onSuccess { cleared ->
                bindBroadcastInputs(cleared)
                Toast.makeText(
                    this@AdminPanelActivity,
                    R.string.admin_broadcast_deleted_toast,
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

    private fun bindBroadcastInputs(broadcast: LiveBroadcast) {
        currentBroadcast = broadcast
        val resolvedType = broadcast.normalizedPopupType()
        binding.chipGroupBroadcastType.check(
            if (resolvedType == LiveBroadcast.TYPE_AD) {
                R.id.chip_broadcast_type_ad
            } else {
                R.id.chip_broadcast_type_update
            }
        )
        checkBroadcastPopupSizeChip(broadcast.normalizedPopupSize())
        updateBroadcastStatusHeader(broadcast)

        binding.etBroadcastTitle.setText(broadcast.title)
        binding.etBroadcastVersion.setText(broadcast.version)
        binding.etBroadcastMessage.setText(broadcast.message)

        suppressImageUrlTextWatcher = true
        if (com.example.ui.common.PopupImageHelper.isDataImageUri(broadcast.imageUrl)) {
            uploadedBroadcastImageDataUri = broadcast.imageUrl
            binding.etBroadcastImageUrl.setText("")
        } else {
            uploadedBroadcastImageDataUri = ""
            binding.etBroadcastImageUrl.setText(broadcast.imageUrl)
        }
        if (com.example.ui.common.PopupImageHelper.isDataImageUri(broadcast.bannerImageUrl)) {
            uploadedBannerImageDataUri = broadcast.bannerImageUrl
            binding.etBannerAdImageUrl.setText("")
        } else {
            uploadedBannerImageDataUri = ""
            binding.etBannerAdImageUrl.setText(broadcast.bannerImageUrl)
        }
        suppressImageUrlTextWatcher = false

        applyBroadcastTypeUi(resolvedType)
        binding.etBroadcastBtnText.setText(broadcast.buttonText)
        binding.etBroadcastActionUrl.setText(broadcast.actionUrl)
        binding.switchBroadcastForceUpdate.isChecked = broadcast.isForceUpdateActive()
        binding.switchBroadcastCancelable.isChecked = !broadcast.isForceUpdateActive()
        updateBroadcastImagePreview(broadcast.imageUrl)

        binding.switchBannerAdEnable.isChecked = broadcast.showBannerAd
        binding.etBannerAdText.setText(broadcast.bannerText)
        binding.etBannerAdActionUrl.setText(broadcast.bannerActionUrl)
        updateBannerImagePreview(broadcast.bannerImageUrl)
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
                customGatewayUrl = prefs.configuredCustomGatewayUrl,
                welcomeTitle = prefs.configuredWelcomeTitle,
                welcomeTrialBadge = prefs.configuredWelcomeTrialBadge,
                welcomeSubtitle = prefs.configuredWelcomeSubtitle,
                welcomeButtonText = prefs.configuredWelcomeButtonText
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
        binding.etConfigWelcomeTitle.setText(config.welcomeTitle)
        binding.etConfigWelcomeBadge.setText(config.welcomeTrialBadge)
        binding.etConfigWelcomeSubtitle.setText(config.welcomeSubtitle)
        binding.etConfigWelcomeButton.setText(config.welcomeButtonText)
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
            AppConfig.THEME_USER
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
            customGatewayUrl = customUrl,
            welcomeTitle = binding.etConfigWelcomeTitle.text?.toString()?.trim().orEmpty().take(100),
            welcomeTrialBadge = binding.etConfigWelcomeBadge.text?.toString()?.trim().orEmpty().take(100),
            welcomeSubtitle = binding.etConfigWelcomeSubtitle.text?.toString()?.trim().orEmpty().take(400),
            welcomeButtonText = binding.etConfigWelcomeButton.text?.toString()?.trim().orEmpty().take(80)
        )

        val desiredNightMode = if (binding.switchLocalDarkTheme.isChecked) {
            AppCompatDelegate.MODE_NIGHT_YES
        } else {
            AppCompatDelegate.MODE_NIGHT_NO
        }
        if (prefs.displayThemeMode != desiredNightMode) {
            com.example.data.firebase.SettingsManager(this).setThemeMode(desiredNightMode)
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
        if (runCatching { Firebase.auth.currentUser }.getOrNull() == null) return
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
            AdminUserDateField.JOIN_DATE -> "Registration Date"
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
        val syncError = usersRtdbSyncErrorMessage
        binding.tvAdminUserDateSummary.text = if (syncError != null) {
            "RTDB /users sync error: $syncError — Tap to retry"
        } else {
            "Showing ${filtered.size} of ${allUsers.size} users • Filter: $rangeLabel ($fieldLabel)"
        }
        binding.tvAdminUsersEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        lastFilteredUsers = filtered
        usersAdapter.submitList(filtered)
    }

    private var lastFilteredUsers: List<UserEntitlement> = emptyList()

    internal fun getDisplayedUsersForTesting(): List<UserEntitlement> = lastFilteredUsers

    internal fun applyRegistrationDateFilterForTesting(
        dateFilter: AdminUserDateFilter,
        customStartMs: Long = 0L,
        customEndMs: Long = Long.MAX_VALUE
    ) {
        currentUserDateField = AdminUserDateField.JOIN_DATE
        currentUserDateFilter = dateFilter
        if (dateFilter == AdminUserDateFilter.CUSTOM_RANGE) {
            customDateStartMs = customStartMs
            customDateEndMs = customEndMs
        }
        applyUserFilters()
    }

    private fun applyPaymentFilters() {
        val filtered = AdminRepository.filterPaymentRequests(
            requests = allPayments,
            filter = currentPaymentFilter,
            query = currentPaymentQuery
        )
        binding.tvAdminPaymentsCount.text = getString(R.string.admin_payments_count_format, filtered.size)
        binding.btnAdminDeletePaymentHistory.isEnabled = filtered.isNotEmpty()
        binding.btnAdminDeletePaymentHistory.alpha = if (filtered.isNotEmpty()) 1f else 0.45f
        binding.tvAdminPaymentsEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        paymentsAdapter.submitList(filtered)
    }

    private fun toggleUserPro(user: UserEntitlement) {
        val status = AdminRepository.resolveUserAccountStatus(user, currentConfig.trialDays.toInt())
        val isUnexpiredPro = user.isPremiumUser && status != com.example.data.firebase.AdminUserAccountStatus.EXPIRED
        setUserPremiumStatus(user = user, targetPremium = !isUnexpiredPro)
    }

    /**
     * Immediately updates a user's `isPremium` status in Firebase RTDB `/users/{uid}` (and any
     * matching email/device sibling nodes) for sub-1-second client propagation, optimistically
     * refreshes the Admin list, and synchronizes with Firestore.
     */
    internal fun setUserPremiumStatus(
        user: UserEntitlement,
        targetPremium: Boolean,
        subscriptionDays: Long = DialerPreferences.MONTHLY_SUBSCRIPTION_DAYS.toLong()
    ) {
        val uid = user.uid.trim()
        if (uid.isEmpty()) return
        val nowMs = maxOf(RealtimeDatabaseSync.currentServerNowMs(), System.currentTimeMillis())
        val expiryMs: Long? = if (targetPremium) {
            val baseMs = maxOf(nowMs, AdminRepository.resolveEffectiveExpiryMs(user))
            baseMs + subscriptionDays.coerceIn(1L, 3650L) * DialerPreferences.ONE_DAY_MILLIS
        } else {
            null
        }
        val nextStatus = if (targetPremium) {
            UserEntitlement.STATUS_ACTIVE
        } else {
            UserEntitlement.STATUS_EXPIRED
        }

        // 1. Direct atomic write to RTDB for <1s client-side reflection
        RealtimeDatabaseSync.updateUserIsPremiumDirect(
            uid = uid,
            isPremium = targetPremium,
            subscriptionExpiresAtMs = expiryMs,
            subscriptionStatus = nextStatus,
            email = user.email,
            deviceHash = user.deviceHash,
            context = this
        )

        // 2. Optimistic Admin UI update so admin immediately sees the updated status
        val updatedUser = user.copy(
            isPremiumUser = targetPremium,
            isBlocked = if (targetPremium) false else user.isBlocked,
            subscriptionStatus = nextStatus,
            subscriptionExpiresAt = expiryMs?.let { Timestamp(Date(it)) },
            updatedAt = Timestamp(Date(nowMs + 1_000L))
        )
        rtdbUsersById[uid] = updatedUser
        if (firestoreUsersById.containsKey(uid)) {
            firestoreUsersById[uid] = updatedUser
        }
        mergeAndPublishUsers()

        // 3. Synchronize with Firestore in background
        lifecycleScope.launch {
            val result = if (targetPremium) {
                adminRepository.renewUserMonthlySubscription(
                    user = user,
                    extraDays = subscriptionDays
                )
            } else {
                adminRepository.updateUserAdminDetails(
                    user = user,
                    isPremium = false,
                    isBlocked = user.isBlocked,
                    bonusTrialDays = user.bonusTrialDays,
                    adminNote = user.adminNote,
                    hideSubscriptionUI = user.hideSubscriptionUI,
                    subscriptionExpiresAt = null,
                    explicitSubscriptionStatus = UserEntitlement.STATUS_EXPIRED
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
                val nextBlocked = blockCheck.isChecked && !isTargetSuperAdmin
                val nextPremium = proCheck.isChecked && !nextBlocked
                val resolvedExpiryMs = if (nextPremium) {
                    if (pendingExpiryMs > nowMs) pendingExpiryMs else nowMs + DialerPreferences.ONE_MONTH_MILLIS
                } else {
                    0L
                }
                val expiryTs = if (nextPremium && resolvedExpiryMs > nowMs) {
                    Timestamp(Date(resolvedExpiryMs))
                } else {
                    null
                }
                val nextAdminGrant = if (isActingSuperAdmin && !isTargetSuperAdmin) {
                    delegateAdminCheck.isChecked
                } else {
                    user.isAdmin
                }
                val nextStatusOverride = when {
                    nextBlocked -> UserEntitlement.STATUS_BLOCKED
                    nextPremium -> UserEntitlement.STATUS_ACTIVE
                    trialExplicitlyExpired || user.isPremiumUser -> UserEntitlement.STATUS_EXPIRED
                    else -> null
                }
                val optimisticUser = user.copy(
                    displayName = nextName.ifBlank { user.displayName },
                    phoneNumber = nextPhone,
                    isPremiumUser = nextPremium,
                    isBlocked = nextBlocked,
                    bonusTrialDays = bonus.coerceIn(0L, 3650L),
                    autoCallRecording = autoRecordCheck.isChecked,
                    hideSubscriptionUI = hideSubCheck.isChecked,
                    adminNote = note,
                    subscriptionExpiresAt = expiryTs,
                    subscriptionStatus = nextStatusOverride ?: user.subscriptionStatus,
                    updatedAt = Timestamp(Date(nowMs + 1_000L))
                )
                rtdbUsersById[user.uid] = optimisticUser
                if (firestoreUsersById.containsKey(user.uid)) {
                    firestoreUsersById[user.uid] = optimisticUser
                }
                mergeAndPublishUsers()
                lifecycleScope.launch {
                    val result = adminRepository.updateFullUserManagement(
                        user = user,
                        displayName = nextName,
                        phoneNumber = nextPhone,
                        isPremium = nextPremium,
                        isBlocked = nextBlocked,
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
                deleteUserAccount(user, actingEmail)
            }
            .setNegativeButton(R.string.dialog_close, null)
            .show()
    }

    /**
     * Deletes a user record from RTDB `/users/{uid}` (and any matching email/device sibling nodes)
     * immediately for sub-1-second client-side sync, removes them from local Admin state, and
     * deletes the corresponding Firestore document.
     */
    internal fun deleteUserAccount(
        user: UserEntitlement,
        actingEmail: String = GoogleAuthHelper.resolveActiveEmail(this)
    ): Boolean {
        if (UpiPaymentHelper.isSuperAdminEmail(user.email)) {
            Toast.makeText(this, R.string.admin_delete_super_admin_blocked, Toast.LENGTH_SHORT).show()
            return false
        }
        val targetIsDelegatedAdmin = UpiPaymentHelper.isDelegatedAdminAuthorized(
            user.isAdmin,
            user.adminGrantedBy
        )
        if (targetIsDelegatedAdmin && !UpiPaymentHelper.isSuperAdminEmail(actingEmail)) {
            Toast.makeText(
                this,
                "Only a Super Admin can delete another Admin user.",
                Toast.LENGTH_LONG
            ).show()
            return false
        }
        val uid = user.uid.trim()
        if (uid.isEmpty()) return false
        val label = user.displayName.ifBlank { user.email.ifBlank { uid } }

        // 1. Immediate RTDB removal (primary + email/device siblings) for <1s client revocation
        RealtimeDatabaseSync.deleteUserAndSiblingsInRtdb(
            uid = uid,
            email = user.email,
            deviceHash = user.deviceHash,
            context = this
        )

        // 2. Immediate Admin UI removal (primary + email/device siblings + user's transactions)
        val normEmail = user.email.trim().lowercase()
        val normDevice = user.deviceHash.trim().lowercase().takeIf { it.length == 64 }.orEmpty()
        val removedUserIds = mutableSetOf(uid)
        fun shouldRemoveSiblingUser(candidate: UserEntitlement): Boolean {
            if (candidate.uid == uid) return true
            if (UpiPaymentHelper.isSuperAdminEmail(candidate.email)) return false
            val emailMatch = normEmail.isNotEmpty() && candidate.email.trim().lowercase() == normEmail
            val deviceMatch = normDevice.isNotEmpty() && candidate.deviceHash.trim().lowercase() == normDevice
            return emailMatch || deviceMatch
        }
        firestoreUsersById.entries.removeAll { (k, v) ->
            val remove = shouldRemoveSiblingUser(v)
            if (remove) removedUserIds.add(k)
            remove
        }
        rtdbUsersById.entries.removeAll { (k, v) ->
            val remove = shouldRemoveSiblingUser(v)
            if (remove) removedUserIds.add(k)
            remove
        }
        fun shouldRemoveUserPayment(req: PaymentRequest): Boolean {
            val uidMatch = req.userId in removedUserIds
            val emailMatch = normEmail.isNotEmpty() && req.userEmail.trim().lowercase() == normEmail
            return uidMatch || emailMatch
        }
        firestorePaymentsById.entries.removeAll { (_, req) -> shouldRemoveUserPayment(req) }
        rtdbPaymentsById.entries.removeAll { (_, req) -> shouldRemoveUserPayment(req) }
        mergeAndPublishUsers()
        mergeAndPublishPayments()

        // 3. Synchronize Firestore deletion
        lifecycleScope.launch {
            val result = adminRepository.deleteUserByAdmin(user, actingEmail)
            result.onSuccess {
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
        return true
    }

    private fun confirmDeletePaymentRequest(request: PaymentRequest) {
        val label = request.userName.ifBlank { request.userEmail.ifBlank { request.userId } }
        val utr = PaymentRepository.extractCanonicalUtr(request).ifBlank { "—" }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.admin_delete_payment_title)
            .setMessage(getString(R.string.admin_delete_payment_message, label, request.amountInr, utr))
            .setPositiveButton(R.string.admin_delete_payment_confirm) { _, _ ->
                deleteSinglePaymentRequest(request)
            }
            .setNegativeButton(R.string.dialog_close, null)
            .show()
    }

    private fun deleteSinglePaymentRequest(request: PaymentRequest) {
        val id = request.id.trim()
        if (id.isEmpty()) return
        firestorePaymentsById.remove(id)
        rtdbPaymentsById.remove(id)
        mergeAndPublishPayments()

        lifecycleScope.launch {
            val result = paymentRepository.deletePaymentRequest(request, clearUtrLock = true)
            result.onSuccess {
                Toast.makeText(
                    this@AdminPanelActivity,
                    R.string.admin_delete_payment_success,
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

    private fun confirmClearPaymentHistory() {
        val targets = AdminRepository.filterPaymentRequests(
            requests = allPayments,
            filter = currentPaymentFilter,
            query = currentPaymentQuery
        )
        if (targets.isEmpty()) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.admin_clear_payments_title)
            .setMessage(getString(R.string.admin_clear_payments_message, targets.size))
            .setPositiveButton(getString(R.string.admin_clear_payments_confirm, targets.size)) { _, _ ->
                deletePaymentHistoryBatch(targets)
            }
            .setNegativeButton(R.string.dialog_close, null)
            .show()
    }

    private fun deletePaymentHistoryBatch(targets: List<PaymentRequest>) {
        if (targets.isEmpty()) return
        targets.forEach { req ->
            val id = req.id.trim()
            if (id.isNotEmpty()) {
                firestorePaymentsById.remove(id)
                rtdbPaymentsById.remove(id)
            }
        }
        mergeAndPublishPayments()

        lifecycleScope.launch {
            val result = paymentRepository.deletePaymentRequests(targets, clearUtrLocks = true)
            result.onSuccess { count ->
                Toast.makeText(
                    this@AdminPanelActivity,
                    getString(R.string.admin_clear_payments_success, count),
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
