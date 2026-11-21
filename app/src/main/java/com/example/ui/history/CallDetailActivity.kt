package com.example.ui.history

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.CallLog
import android.text.format.DateUtils
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.R
import com.example.billing.SubscriptionManager
import com.example.billing.UpiPaymentHelper
import com.example.data.CallLogEntry
import com.example.data.ContactEntry
import com.example.data.ContactsRepository
import com.example.data.DialerPreferences
import com.example.data.firebase.CallerDirectoryProfile
import com.example.data.firebase.RealtimeDatabaseSync
import com.example.data.firebase.RealtimeSyncManager
import com.example.data.firebase.SettingsManager
import com.example.databinding.ActivityCallDetailBinding
import com.example.telecom.CallerIdResolver
import com.example.telecom.CallerIdentityInfo
import com.example.telecom.DialerHelper
import com.example.telecom.PhoneGeoDatabase
import com.example.ui.MainActivity
import com.example.ui.common.NumberHistoryAdapter
import com.example.ui.common.SaveContactHelper
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch

class CallDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCallDetailBinding
    private lateinit var repository: ContactsRepository
    private lateinit var prefs: DialerPreferences
    private lateinit var settingsManager: SettingsManager
    private lateinit var historyAdapter: NumberHistoryAdapter

    private var phoneNumber: String = ""
    private var initialName: String? = null
    private var matchedContact: ContactEntry? = null
    private var resolvedIdentity: CallerIdentityInfo? = null
    private var callHistoryList: List<CallLogEntry> = emptyList()
    private var pendingDeleteAction: (() -> Unit)? = null

    private val writeCallLogPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val action = pendingDeleteAction
        pendingDeleteAction = null
        if (granted) {
            action?.invoke()
        } else {
            Toast.makeText(this, R.string.history_delete_failed, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        com.example.ui.theme.ThemeStudioManager.applyActivityTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityCallDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        phoneNumber = intent?.getStringExtra(EXTRA_PHONE_NUMBER).orEmpty()
        initialName = intent?.getStringExtra(EXTRA_CALLER_NAME)
        if (phoneNumber.isBlank()) {
            finish()
            return
        }

        repository = ContactsRepository(this)
        prefs = DialerPreferences(this)
        settingsManager = SettingsManager(this)

        if (!hasAuthorizedAccess()) {
            redirectToPaymentPage()
            return
        }

        applyEdgeToEdgeInsets()
        setupToolbar()
        setupRecycler()
        setupActions()
        com.example.ui.theme.ThemeStudioManager.applyFullApp3DThemeToViewHierarchy(binding.root, prefs)
        loadDetailsAndHistory()
        observeRealtimeLock()
    }

    override fun onResume() {
        super.onResume()
        if (!hasAuthorizedAccess()) {
            redirectToPaymentPage()
            return
        }
        if (phoneNumber.isNotBlank()) {
            loadDetailsAndHistory()
        }
    }

    private fun hasAuthorizedAccess(): Boolean {
        val manager = SubscriptionManager(this)
        val isAdmin = UpiPaymentHelper.isAuthorizedAdmin(this, prefs)
        return !manager.isMaintenanceLocked(isAdmin) &&
            (isAdmin || (!prefs.adminBlockedUser && !prefs.tamperDetected))
    }

    private fun observeRealtimeLock() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                RealtimeSyncManager.syncState.collect { state ->
                    if (state.uid != null && !hasAuthorizedAccess() && !isFinishing) {
                        redirectToPaymentPage()
                    } else if (!isFinishing) {
                        renderBlockedState()
                    }
                }
            }
        }
    }

    private fun redirectToPaymentPage() {
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_OPEN_ACCOUNT_TAB, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(intent)
        finish()
    }

    private fun applyEdgeToEdgeInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.appBarLayout) { view, insets ->
            val topInset = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.updatePadding(top = topInset)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.scrollCallDetail) { view, insets ->
            val bottomInset = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            view.updatePadding(bottom = bottomInset)
            insets
        }
    }

    private fun setupToolbar() {
        binding.topAppBar.setNavigationOnClickListener {
            finish()
        }
    }

    private fun setupRecycler() {
        historyAdapter = NumberHistoryAdapter(
            onDeleteClick = { entry -> confirmDeleteSingleCall(entry) }
        )
        binding.recyclerCallLogs.layoutManager = LinearLayoutManager(this)
        binding.recyclerCallLogs.adapter = historyAdapter
    }

    private fun setupActions() {
        binding.btnSaveContact.setOnClickListener {
            SaveContactHelper.showSaveNumberDialog(
                context = this,
                lifecycleOwner = this,
                initialNumber = phoneNumber,
                initialName = matchedContact?.name ?: initialName.orEmpty(),
                onSaved = { loadDetailsAndHistory() }
            )
        }

        binding.actionDetailCall.setOnClickListener {
            DialerHelper.initiateCall(this, phoneNumber)
        }

        binding.actionDetailMessage.setOnClickListener {
            val smsIntent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$phoneNumber"))
            runCatching { startActivity(smsIntent) }
        }

        binding.actionDetailCopy.setOnClickListener {
            val clipboard = getSystemService(ClipboardManager::class.java)
            clipboard?.setPrimaryClip(ClipData.newPlainText("Phone Number", phoneNumber))
            Toast.makeText(this, R.string.detail_copied, Toast.LENGTH_SHORT).show()
        }

        binding.actionDetailBlock.setOnClickListener {
            if (prefs.isNumberBlocked(phoneNumber)) {
                settingsManager.removeBlockedNumber(phoneNumber)
                Toast.makeText(
                    this,
                    getString(R.string.detail_unblocked_confirmation, phoneNumber),
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                settingsManager.addBlockedNumber(phoneNumber)
                Toast.makeText(
                    this,
                    getString(R.string.blocked_confirmation, phoneNumber),
                    Toast.LENGTH_SHORT
                ).show()
            }
            renderBlockedState()
        }

        binding.btnDetailStar.setOnClickListener {
            val contact = matchedContact ?: return@setOnClickListener
            lifecycleScope.launch {
                repository.setContactStarred(contact.contactId, !contact.isStarred)
                loadDetailsAndHistory()
            }
        }

        binding.btnDeleteNumberHistory.setOnClickListener {
            confirmDeleteNumberHistory()
        }

        binding.btnDeleteAllHistory.setOnClickListener {
            confirmDeleteAllHistory()
        }

        // One-Tap Play-Compliant Social & Web Lookup Actions
        binding.chipLookupWhatsapp.setOnClickListener {
            openSocialLookup(CallerIdResolver.PLATFORM_WHATSAPP, getString(R.string.caller_id_social_whatsapp))
        }
        binding.chipLookupTelegram.setOnClickListener {
            openSocialLookup(CallerIdResolver.PLATFORM_TELEGRAM, getString(R.string.caller_id_social_telegram))
        }
        binding.chipLookupFacebook.setOnClickListener {
            openSocialLookup(CallerIdResolver.PLATFORM_FACEBOOK, getString(R.string.caller_id_social_facebook))
        }
        binding.chipLookupInstagram.setOnClickListener {
            openSocialLookup(CallerIdResolver.PLATFORM_INSTAGRAM, getString(R.string.caller_id_social_instagram))
        }
        binding.chipLookupWeb.setOnClickListener {
            openSocialLookup(CallerIdResolver.PLATFORM_GOOGLE, getString(R.string.caller_id_social_web))
        }
        binding.btnVerifyCallerIdentity.setOnClickListener {
            showVerifyCallerIdentityDialog()
        }
    }

    private fun openSocialLookup(platform: String, label: String) {
        val url = CallerIdResolver.buildSocialLookupUrl(platform, phoneNumber)
        val viewIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        val launched = runCatching {
            startActivity(viewIntent)
            true
        }.getOrDefault(false)
        if (!launched) {
            Toast.makeText(
                this,
                getString(R.string.caller_id_lookup_open_failed, label),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun renderCallerIdentificationCard(identity: CallerIdentityInfo) {
        resolvedIdentity = identity
        val na = getString(R.string.caller_id_not_available)

        binding.tvCallerIdSourceBadge.text = identity.sourceLabel.ifBlank { na }
        val carrierLine = listOf(identity.carrier.trim(), identity.lineType.trim())
            .filter { it.isNotEmpty() }
            .joinToString(" • ")
        binding.tvCallerIdCarrier.text = if (carrierLine.isNotEmpty()) {
            "${getString(R.string.caller_id_label_carrier)}: $carrierLine"
        } else {
            "${getString(R.string.caller_id_label_carrier)}: $na"
        }

        binding.tvCallerIdCity.text = identity.city.ifBlank { na }
        binding.tvCallerIdState.text = identity.state.ifBlank { na }
        binding.tvCallerIdZip.text = identity.postalCode.ifBlank { na }
        val countryDisplay = buildString {
            append(identity.country.ifBlank { na })
            if (identity.countryCode.isNotBlank() && identity.country.isNotBlank()) {
                append(" (${identity.countryCode})")
            }
        }
        binding.tvCallerIdCountry.text = countryDisplay

        binding.chipGroupDetectedSocials.removeAllViews()
        if (identity.socialProfiles.isEmpty()) {
            binding.chipGroupDetectedSocials.visibility = View.GONE
            binding.tvNoDetectedSocials.visibility = View.VISIBLE
        } else {
            binding.chipGroupDetectedSocials.visibility = View.VISIBLE
            binding.tvNoDetectedSocials.visibility = View.GONE
            identity.socialProfiles.forEach { profile ->
                val chip = Chip(this).apply {
                    val nameSuffix = profile.profileName.trim()
                        .takeIf { it.isNotEmpty() && !it.equals(identity.displayName, ignoreCase = true) }
                        ?.let { " ($it)" }
                        .orEmpty()
                    text = "${profile.platform} ✓$nameSuffix"
                    isClickable = false
                    isCheckable = false
                }
                binding.chipGroupDetectedSocials.addView(chip)
            }
        }
    }

    private fun showVerifyCallerIdentityDialog() {
        val current = resolvedIdentity ?: CallerIdResolver.resolveInstant(phoneNumber, matchedContact?.name, initialName)
        val density = resources.displayMetrics.density
        val padH = (20f * density).toInt()
        val padTop = (8f * density).toInt()
        val spacing = (8f * density).toInt()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padH, padTop, padH, 0)
        }

        val subtitleView = TextView(this).apply {
            text = getString(R.string.caller_id_verify_dialog_subtitle)
            setPadding(0, 0, 0, spacing)
        }
        container.addView(subtitleView)

        fun createField(hintRes: Int, initialValue: String): TextInputEditText {
            val til = TextInputLayout(this).apply {
                hint = getString(hintRes)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = spacing }
            }
            val edit = TextInputEditText(til.context).apply {
                setText(initialValue)
                setSingleLine(true)
            }
            til.addView(edit)
            container.addView(til)
            return edit
        }

        val prefillName = if (current.isUnknownNumber && current.displayName.startsWith("Unknown")) {
            ""
        } else {
            current.displayName
        }
        val etName = createField(R.string.caller_id_verify_hint_name, prefillName)
        val etCity = createField(R.string.caller_id_verify_hint_city, current.city)
        val etState = createField(R.string.caller_id_verify_hint_state, current.state)
        val etZip = createField(
            R.string.caller_id_verify_hint_zip,
            current.postalCode.takeUnless { it.equals("N/A", ignoreCase = true) }.orEmpty()
        )
        val etCountry = createField(R.string.caller_id_verify_hint_country, current.country)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.caller_id_verify_dialog_title)
            .setView(container)
            .setPositiveButton(R.string.caller_id_verify_save) { _, _ ->
                val verifiedName = etName.text?.toString()?.trim().orEmpty()
                if (verifiedName.isEmpty()) {
                    Toast.makeText(this, R.string.caller_id_verify_name_required, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val verifiedCity = etCity.text?.toString()?.trim().orEmpty()
                val verifiedState = etState.text?.toString()?.trim().orEmpty()
                val verifiedZip = etZip.text?.toString()?.trim().orEmpty()
                val verifiedCountry = etCountry.text?.toString()?.trim().orEmpty()
                val e164Key = PhoneGeoDatabase.toE164Digits(phoneNumber)

                val profile = CallerDirectoryProfile(
                    phoneKey = e164Key,
                    displayName = verifiedName,
                    city = verifiedCity,
                    state = verifiedState,
                    postalCode = verifiedZip,
                    country = verifiedCountry,
                    countryCode = current.countryCode,
                    socialPlatforms = listOf("Verified Directory"),
                    sourceLabel = "Verified Caller Directory"
                )
                CallerIdResolver.cacheVerifiedCallerProfile(profile)
                RealtimeDatabaseSync.saveVerifiedCallerProfile(
                    context = applicationContext,
                    rawNumber = phoneNumber,
                    displayName = verifiedName,
                    city = verifiedCity,
                    state = verifiedState,
                    postalCode = verifiedZip,
                    country = verifiedCountry,
                    countryCode = current.countryCode,
                    socialPlatforms = listOf("Verified Directory"),
                    submittedByUid = RealtimeSyncManager.syncState.value.uid.orEmpty()
                )
                Toast.makeText(
                    this,
                    getString(R.string.caller_id_verify_saved_toast, verifiedName),
                    Toast.LENGTH_SHORT
                ).show()
                loadDetailsAndHistory()
            }
            .setNegativeButton(R.string.dialog_close, null)
            .show()
    }

    private fun loadDetailsAndHistory() {
        // Immediate deterministic offline render first (zero wait, zero randomness)
        renderCallerIdentificationCard(
            CallerIdResolver.resolveInstant(
                rawNumber = phoneNumber,
                savedContactName = matchedContact?.name,
                telecomDisplayName = initialName
            )
        )

        lifecycleScope.launch {
            matchedContact = repository.lookupContactByNumber(phoneNumber)
            callHistoryList = repository.getCallHistoryForNumber(phoneNumber)
            val identity = CallerIdResolver.resolveWithDeviceAsync(
                context = applicationContext,
                rawNumber = phoneNumber,
                telecomDisplayName = initialName
            )
            if (isFinishing || isDestroyed) return@launch

            renderCallerIdentificationCard(identity)

            val displayName = matchedContact?.name
                ?: identity.displayName.takeIf {
                    it.isNotBlank() && !it.equals("Unknown Caller", ignoreCase = true)
                }
                ?: initialName?.takeIf { it.isNotBlank() }
                ?: getString(R.string.detail_unsaved_number)

            binding.tvDetailName.text = displayName
            binding.tvDetailNumber.text = phoneNumber
            binding.tvDetailAvatar.text = displayName.firstOrNull()?.uppercaseChar()?.toString() ?: "#"

            val isSaved = matchedContact != null
            binding.btnSaveContact.visibility = if (isSaved) View.GONE else View.VISIBLE
            binding.btnDetailStar.visibility = if (isSaved) View.VISIBLE else View.GONE
            if (matchedContact != null) {
                binding.btnDetailStar.setImageResource(
                    if (matchedContact!!.isStarred) R.drawable.ic_star else R.drawable.ic_star_outline
                )
            }

            val totalCount = callHistoryList.size
            binding.chipDetailCallsCount.text = when {
                isSaved && totalCount == 0 -> getString(R.string.call_detail_saved_contact)
                totalCount == 1 -> getString(R.string.call_detail_one_call_count)
                else -> getString(R.string.call_detail_calls_count, totalCount)
            }

            renderBlockedState()

            historyAdapter.submitList(callHistoryList)
            val isEmpty = callHistoryList.isEmpty()
            binding.recyclerCallLogs.visibility = if (isEmpty) View.GONE else View.VISIBLE
            binding.tvDetailHistoryEmpty.visibility = if (isEmpty) View.VISIBLE else View.GONE
            binding.btnDeleteNumberHistory.visibility = if (isEmpty) View.GONE else View.VISIBLE
        }
    }

    private fun renderBlockedState() {
        val blocked = prefs.isNumberBlocked(phoneNumber)
        binding.chipDetailBlocked.visibility = if (blocked) View.VISIBLE else View.GONE
        binding.tvDetailBlockLabel.text = getString(
            if (blocked) R.string.detail_action_unblock else R.string.detail_action_block
        )
    }

    private fun runWithWriteCallLogPermission(action: () -> Unit) {
        if (repository.hasWriteCallLogPermission()) {
            action()
        } else {
            pendingDeleteAction = action
            writeCallLogPermissionLauncher.launch(Manifest.permission.WRITE_CALL_LOG)
        }
    }

    private fun confirmDeleteSingleCall(entry: CallLogEntry) {
        val typeLabel = when (entry.type) {
            CallLog.Calls.INCOMING_TYPE -> getString(R.string.call_type_incoming)
            CallLog.Calls.OUTGOING_TYPE -> getString(R.string.call_type_outgoing)
            CallLog.Calls.MISSED_TYPE -> getString(R.string.call_type_missed)
            CallLog.Calls.REJECTED_TYPE -> getString(R.string.call_type_rejected)
            else -> getString(R.string.call_type_outgoing)
        }
        val formattedTime = DateUtils.formatDateTime(
            this,
            entry.timestampMillis,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH
        )

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.call_detail_delete_single_title)
            .setMessage(getString(R.string.call_detail_delete_single_message, typeLabel, formattedTime))
            .setPositiveButton(R.string.history_delete_confirm) { _, _ ->
                runWithWriteCallLogPermission {
                    lifecycleScope.launch {
                        val deleted = repository.deleteSingleCallLog(entry.id)
                        if (isFinishing || isDestroyed) return@launch
                        if (deleted) {
                            Toast.makeText(
                                this@CallDetailActivity,
                                R.string.call_detail_single_deleted,
                                Toast.LENGTH_SHORT
                            ).show()
                            loadDetailsAndHistory()
                        } else {
                            Toast.makeText(
                                this@CallDetailActivity,
                                R.string.history_delete_failed,
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }
            .setNegativeButton(R.string.dialog_close, null)
            .show()
    }

    private fun confirmDeleteNumberHistory() {
        val label = matchedContact?.name ?: phoneNumber
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.call_detail_delete_number_title)
            .setMessage(getString(R.string.call_detail_delete_number_message, label))
            .setPositiveButton(R.string.history_delete_confirm) { _, _ ->
                runWithWriteCallLogPermission {
                    lifecycleScope.launch {
                        val count = repository.deleteCallHistoryForNumber(phoneNumber)
                        if (isFinishing || isDestroyed) return@launch
                        Toast.makeText(
                            this@CallDetailActivity,
                            getString(R.string.call_detail_number_deleted, count, label),
                            Toast.LENGTH_SHORT
                        ).show()
                        loadDetailsAndHistory()
                    }
                }
            }
            .setNegativeButton(R.string.dialog_close, null)
            .show()
    }

    private fun confirmDeleteAllHistory() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.history_delete_all_title)
            .setMessage(R.string.history_delete_all_message)
            .setPositiveButton(R.string.history_delete_all_confirm) { _, _ ->
                runWithWriteCallLogPermission {
                    lifecycleScope.launch {
                        val count = repository.deleteAllCallHistory()
                        if (isFinishing || isDestroyed) return@launch
                        Toast.makeText(
                            this@CallDetailActivity,
                            getString(R.string.history_delete_all_done, count),
                            Toast.LENGTH_SHORT
                        ).show()
                        finish()
                    }
                }
            }
            .setNegativeButton(R.string.dialog_close, null)
            .show()
    }

    companion object {
        const val EXTRA_PHONE_NUMBER = "extra_phone_number"
        const val EXTRA_CALLER_NAME = "extra_caller_name"

        fun createIntent(context: Context, phoneNumber: String, callerName: String? = null): Intent {
            return Intent(context, CallDetailActivity::class.java).apply {
                putExtra(EXTRA_PHONE_NUMBER, phoneNumber)
                putExtra(EXTRA_CALLER_NAME, callerName)
            }
        }
    }
}
