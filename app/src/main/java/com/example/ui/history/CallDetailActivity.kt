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
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.R
import com.example.billing.SubscriptionManager
import com.example.billing.UpiPaymentHelper
import com.example.data.CallLogEntry
import com.example.data.ContactEntry
import com.example.data.ContactsRepository
import com.example.data.DialerPreferences
import com.example.data.firebase.RealtimeSyncManager
import com.example.data.firebase.SettingsManager
import com.example.databinding.ActivityCallDetailBinding
import com.example.telecom.DialerHelper
import com.example.ui.MainActivity
import com.example.ui.common.NumberHistoryAdapter
import com.example.ui.common.SaveContactHelper
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
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
        val currentEmail = runCatching { Firebase.auth.currentUser?.email }.getOrNull()
        val isAdmin = UpiPaymentHelper.isAdminEmail(currentEmail)
        return manager.isSubscriptionActive() && !manager.isMaintenanceLocked(isAdmin)
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
            DialerHelper.placeCall(this, phoneNumber)
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
    }

    private fun loadDetailsAndHistory() {
        lifecycleScope.launch {
            matchedContact = repository.lookupContactByNumber(phoneNumber)
            callHistoryList = repository.getCallHistoryForNumber(phoneNumber)
            if (isFinishing || isDestroyed) return@launch

            val displayName = matchedContact?.name
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
