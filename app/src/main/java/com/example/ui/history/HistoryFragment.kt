package com.example.ui.history

import android.Manifest
import android.content.Intent
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
import com.example.data.CallHistoryCsvExporter
import com.example.data.CallHistoryExportResult
import com.example.data.CallLogEntry
import com.example.data.ContactsRepository
import com.example.data.firebase.SettingsManager
import com.example.databinding.FragmentHistoryBinding
import com.example.telecom.DialerHelper
import com.example.ui.MainActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

/**
 * Displays Recent Calls only.
 * Call recordings (Cellular, WhatsApp, WhatsApp Business, Meet, Telegram) are intentionally
 * hidden from the call app and saved directly into separate folders in the File Manager.
 */
class HistoryFragment : Fragment() {

    private var _binding: FragmentHistoryBinding? = null
    private val binding get() = _binding!!

    private lateinit var contactsRepo: ContactsRepository
    private lateinit var settingsManager: SettingsManager
    private lateinit var csvExporter: CallHistoryCsvExporter
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

    private val writeStorageForExportLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            performExportCallHistoryCsv()
        } else if (isAdded) {
            Toast.makeText(
                requireContext(),
                R.string.history_export_storage_permission_required,
                Toast.LENGTH_SHORT
            ).show()
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
        settingsManager = SettingsManager(ctx)
        csvExporter = CallHistoryCsvExporter(ctx)

        callLogAdapter = CallLogAdapter(
            onRowClick = { entry ->
                startActivity(CallDetailActivity.createIntent(ctx, entry.number, entry.name))
            },
            onCallClick = { entry -> DialerHelper.initiateCall(activity ?: ctx, entry.number) },
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

        // Recordings are strictly stored in separate File Manager folders — never displayed in app
        binding.toggleHistoryMode.visibility = View.GONE
        binding.cardRecordingFolderBanner.visibility = View.GONE
        binding.recyclerRecordings.visibility = View.GONE
        binding.cardMiniPlayer.visibility = View.GONE

        binding.btnSetDefaultDialer.setOnClickListener {
            (activity as? MainActivity)?.requestDefaultDialerRole()
        }
        binding.btnGrantPermissions.setOnClickListener {
            (activity as? MainActivity)?.requestDialerPermissions()
        }

        binding.etHistorySearch.doAfterTextChanged { text ->
            applyCallLogFilter(text?.toString().orEmpty())
        }

        binding.btnExportHistoryCsv.setOnClickListener {
            startCallHistoryCsvExport()
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
            binding.tilHistorySearch.visibility = View.VISIBLE
            binding.recyclerHistory.visibility = View.VISIBLE
            binding.cardRecordingFolderBanner.visibility = View.GONE
            binding.recyclerRecordings.visibility = View.GONE
            binding.cardMiniPlayer.visibility = View.GONE
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

    private fun startCallHistoryCsvExport() {
        val ctx = context ?: return
        if (!contactsRepo.hasCallLogPermission()) {
            Toast.makeText(ctx, R.string.history_export_permission_required, Toast.LENGTH_SHORT).show()
            (activity as? MainActivity)?.requestDialerPermissions()
            return
        }
        if (csvExporter.needsLegacyWritePermission()) {
            writeStorageForExportLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        performExportCallHistoryCsv()
    }

    private fun performExportCallHistoryCsv() {
        if (_binding == null) return
        binding.btnExportHistoryCsv.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val callsToExport = contactsRepo.getRecentCalls(limit = 10_000)
                if (!isAdded) return@launch
                if (callsToExport.isEmpty()) {
                    Toast.makeText(requireContext(), R.string.history_export_empty, Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val result = csvExporter.exportToDocuments(callsToExport)
                if (!isAdded) return@launch
                if (result.success) {
                    showExportSuccessDialog(result)
                } else {
                    val reason = result.errorMessage ?: "Unknown error"
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.history_export_failed, reason),
                        Toast.LENGTH_LONG
                    ).show()
                }
            } finally {
                if (_binding != null) {
                    binding.btnExportHistoryCsv.isEnabled = true
                }
            }
        }
    }

    private fun showExportSuccessDialog(result: CallHistoryExportResult) {
        val ctx = context ?: return
        val builder = MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.history_export_success_title)
            .setMessage(
                getString(
                    R.string.history_export_success_message,
                    result.exportedCount,
                    result.relativePath
                )
            )
            .setPositiveButton(R.string.dialog_close, null)

        val fileUri = result.fileUri
        if (fileUri != null) {
            builder.setNeutralButton(R.string.history_export_share_button) { _, _ ->
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = CallHistoryCsvExporter.MIME_TYPE_CSV
                    putExtra(Intent.EXTRA_STREAM, fileUri)
                    putExtra(Intent.EXTRA_SUBJECT, result.fileName)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                runCatching {
                    startActivity(Intent.createChooser(shareIntent, getString(R.string.history_export_share_chooser)))
                }
            }
        }
        builder.show()
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
