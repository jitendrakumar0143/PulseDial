package com.example.ui.history

import android.Manifest
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.R
import com.example.data.CallLogEntry
import com.example.data.ContactsRepository
import com.example.data.DialerPreferences
import com.example.data.firebase.SettingsManager
import com.example.databinding.FragmentHistoryBinding
import com.example.telecom.DialerHelper
import com.example.ui.MainActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

class HistoryFragment : Fragment() {

    private var _binding: FragmentHistoryBinding? = null
    private val binding get() = _binding!!

    private lateinit var contactsRepo: ContactsRepository
    private lateinit var prefs: DialerPreferences
    private lateinit var settingsManager: SettingsManager
    private lateinit var callLogAdapter: CallLogAdapter

    private var skipNextResumeReload = false
    private var allRecentCalls: List<CallLogEntry> = emptyList()

    private val writeCallLogPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            performDeleteAllCalls()
        } else if (isAdded) {
            Toast.makeText(requireContext(), R.string.history_delete_failed, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()
        contactsRepo = ContactsRepository(ctx)
        prefs = DialerPreferences(ctx)
        settingsManager = SettingsManager(ctx)

        callLogAdapter = CallLogAdapter(
            onRowClick = { entry ->
                startActivity(CallDetailActivity.createIntent(ctx, entry.number, entry.name))
            },
            onCallClick = { entry -> DialerHelper.placeCall(ctx, entry.number) },
            onBlockClick = { entry ->
                settingsManager.addBlockedNumber(entry.number)
                Toast.makeText(
                    ctx,
                    getString(R.string.blocked_confirmation, entry.number),
                    Toast.LENGTH_SHORT
                ).show()
            }
        )

        binding.recyclerHistory.layoutManager = LinearLayoutManager(ctx)
        binding.recyclerHistory.adapter = callLogAdapter

        binding.btnSetDefaultDialer.setOnClickListener {
            (activity as? MainActivity)?.requestDefaultDialerRole()
        }
        binding.btnGrantPermissions.setOnClickListener {
            (activity as? MainActivity)?.requestDialerPermissions()
        }

        binding.etHistorySearch.doAfterTextChanged { text ->
            applyCallLogFilter(text?.toString().orEmpty())
        }

        binding.btnDeleteAllCalls.setOnClickListener {
            confirmDeleteAllCallHistory()
        }

        skipNextResumeReload = true
        reloadData()
    }

    override fun onResume() {
        super.onResume()
        if (skipNextResumeReload) {
            skipNextResumeReload = false
            return
        }
        reloadData()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    fun reloadData() {
        if (_binding == null) return
        val ctx = requireContext()
        val mainAct = activity as? MainActivity

        val isDefault = DialerHelper.isDefaultDialer(ctx)
        binding.cardDefaultDialer.visibility = if (isDefault) View.GONE else View.VISIBLE

        val hasAllPerms = mainAct?.hasCorePermissions() ?: contactsRepo.hasCallLogPermission()
        binding.cardPermissionsBanner.visibility = if (hasAllPerms) View.GONE else View.VISIBLE

        viewLifecycleOwner.lifecycleScope.launch {
            allRecentCalls = contactsRepo.getRecentCalls()
            if (_binding == null) return@launch
            applyCallLogFilter(binding.etHistorySearch.text?.toString().orEmpty())
        }
    }

    private fun applyCallLogFilter(query: String) {
        if (_binding == null) return
        val filtered = filterCallLogs(allRecentCalls, query)
        callLogAdapter.submitList(filtered)

        val hasAnyCalls = allRecentCalls.isNotEmpty()
        binding.layoutRecentCallsActions.visibility = if (hasAnyCalls) View.VISIBLE else View.GONE
        if (hasAnyCalls) {
            binding.tvRecentCallsCount.text = getString(R.string.history_calls_count_header, filtered.size)
        }

        val isEmpty = filtered.isEmpty()
        binding.layoutHistoryEmpty.visibility = if (isEmpty) View.VISIBLE else View.GONE
        if (isEmpty && query.isNotBlank()) {
            binding.tvHistoryEmptyTitle.setText(R.string.history_search_empty_title)
            binding.tvHistoryEmptySubtitle.setText(R.string.history_search_empty_subtitle)
        } else {
            binding.tvHistoryEmptyTitle.setText(R.string.history_empty_title)
            binding.tvHistoryEmptySubtitle.setText(R.string.history_empty_subtitle)
        }
    }

    private fun confirmDeleteAllCallHistory() {
        val ctx = context ?: return
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.history_delete_all_title)
            .setMessage(R.string.history_delete_all_message)
            .setPositiveButton(R.string.history_delete_all_confirm) { _, _ ->
                if (contactsRepo.hasWriteCallLogPermission()) {
                    performDeleteAllCalls()
                } else {
                    writeCallLogPermissionLauncher.launch(Manifest.permission.WRITE_CALL_LOG)
                }
            }
            .setNegativeButton(R.string.dialog_close, null)
            .show()
    }

    private fun performDeleteAllCalls() {
        viewLifecycleOwner.lifecycleScope.launch {
            val count = contactsRepo.deleteAllCallHistory()
            if (!isAdded) return@launch
            Toast.makeText(
                requireContext(),
                getString(R.string.history_delete_all_done, count),
                Toast.LENGTH_SHORT
            ).show()
            reloadData()
        }
    }

    companion object {
        fun filterCallLogs(calls: List<CallLogEntry>, query: String): List<CallLogEntry> {
            val trimmed = query.trim()
            if (trimmed.isEmpty()) return calls
            val digitQuery = trimmed.filter { it.isDigit() }
            return calls.filter { entry ->
                val matchesName = entry.name?.contains(trimmed, ignoreCase = true) == true
                val matchesNumber = entry.number.contains(trimmed, ignoreCase = true)
                val matchesDigits = digitQuery.isNotEmpty() &&
                    entry.number.filter { it.isDigit() }.contains(digitQuery)
                matchesName || matchesNumber || matchesDigits
            }
        }
    }
}
