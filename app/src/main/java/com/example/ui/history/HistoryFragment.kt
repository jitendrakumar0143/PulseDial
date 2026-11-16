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
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.R
import com.example.data.CallLogEntry
import com.example.data.ContactsRepository
import com.example.data.DialerPreferences
import com.example.data.RecordingEntry
import com.example.data.firebase.SettingsManager
import com.example.databinding.FragmentHistoryBinding
import com.example.recording.RecordingAudioPlayer
import com.example.recording.RecordingsRepository
import com.example.telecom.DialerHelper
import com.example.ui.MainActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class HistoryFragment : Fragment() {

    private var _binding: FragmentHistoryBinding? = null
    private val binding get() = _binding!!

    private lateinit var contactsRepo: ContactsRepository
    private lateinit var recordingsRepo: RecordingsRepository
    private lateinit var audioPlayer: RecordingAudioPlayer
    private lateinit var prefs: DialerPreferences
    private lateinit var settingsManager: SettingsManager
    private lateinit var callLogAdapter: CallLogAdapter
    private lateinit var recordingsAdapter: RecordingsAdapter

    private var skipNextResumeReload = false
    private var isRecordingsMode = false
    private var allRecentCalls: List<CallLogEntry> = emptyList()
    private var allRecordings: List<RecordingEntry> = emptyList()

    private val writeCallLogPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            performDeleteAllCalls()
        } else if (isAdded) {
            Toast.makeText(requireContext(), R.string.history_delete_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null && isAdded) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching {
                requireContext().contentResolver.takePersistableUriPermission(treeUri, flags)
            }
            val folderName = DocumentFile.fromTreeUri(requireContext(), treeUri)?.name
                ?: treeUri.lastPathSegment
                ?: "Recordings"
            prefs.recordingFolderUri = treeUri.toString()
            prefs.recordingFolderName = folderName
            Toast.makeText(
                requireContext(),
                getString(R.string.setting_folder_updated, folderName),
                Toast.LENGTH_SHORT
            ).show()
            reloadData()
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
        recordingsRepo = RecordingsRepository(ctx)
        audioPlayer = RecordingAudioPlayer(ctx)
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

        recordingsAdapter = RecordingsAdapter(
            onPlayPauseClick = { entry ->
                audioPlayer.togglePlayPause(entry)
            }
        )

        binding.recyclerHistory.layoutManager = LinearLayoutManager(ctx)
        binding.recyclerHistory.adapter = callLogAdapter

        binding.recyclerRecordings.layoutManager = LinearLayoutManager(ctx)
        binding.recyclerRecordings.adapter = recordingsAdapter

        binding.toggleHistoryMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                isRecordingsMode = (checkedId == R.id.btn_mode_recordings)
                renderActiveMode()
            }
        }

        binding.btnSelectRecordingFolder.setOnClickListener {
            folderPickerLauncher.launch(null)
        }
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

        binding.btnMiniPlayPause.setOnClickListener {
            audioPlayer.toggleActivePlayPause()
        }
        binding.btnMiniStop.setOnClickListener {
            audioPlayer.stop()
        }

        observeAudioPlayback()

        skipNextResumeReload = true
        reloadData()
    }

    private fun observeAudioPlayback() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    audioPlayer.playbackState.collect { state ->
                        if (_binding == null) return@collect
                        recordingsAdapter.updatePlaybackState(state.activeUri, state.isPlaying)
                        if (state.activeUri != null) {
                            binding.cardMiniPlayer.visibility = View.VISIBLE
                            binding.tvMiniPlayerTitle.text = state.title
                            binding.btnMiniPlayPause.setImageResource(
                                if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow
                            )
                            val posStr = formatDuration(state.positionMs.toLong())
                            val durStr = formatDuration(state.durationMs.toLong())
                            binding.tvMiniPlayerTime.text = "$posStr / $durStr"
                            binding.progressMiniPlayer.max = state.durationMs.coerceAtLeast(1)
                            binding.progressMiniPlayer.progress = state.positionMs.coerceIn(0, state.durationMs.coerceAtLeast(1))
                        } else {
                            binding.cardMiniPlayer.visibility = View.GONE
                        }
                    }
                }
                launch {
                    while (isActive) {
                        audioPlayer.refreshProgress()
                        delay(250L)
                    }
                }
            }
        }
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
        audioPlayer.stop()
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
            allRecordings = recordingsRepo.getRecordings(prefs)
            if (_binding == null) return@launch
            recordingsAdapter.submitList(allRecordings)
            renderActiveMode()
        }
    }

    private fun renderActiveMode() {
        if (_binding == null) return
        val folderName = prefs.recordingFolderName
        if (!folderName.isNullOrBlank()) {
            binding.tvRecordingFolderTitle.text = getString(R.string.setting_folder_selected, folderName)
            binding.btnSelectRecordingFolder.setText(R.string.action_change_folder)
        } else {
            binding.tvRecordingFolderTitle.setText(R.string.recordings_folder_banner_title)
            binding.btnSelectRecordingFolder.setText(R.string.action_choose_folder)
        }

        if (isRecordingsMode) {
            binding.tilHistorySearch.visibility = View.GONE
            binding.layoutRecentCallsActions.visibility = View.GONE
            binding.recyclerHistory.visibility = View.GONE
            binding.cardRecordingFolderBanner.visibility = View.VISIBLE
            binding.recyclerRecordings.visibility = View.VISIBLE

            val isEmpty = allRecordings.isEmpty()
            binding.layoutHistoryEmpty.visibility = if (isEmpty) View.VISIBLE else View.GONE
            if (isEmpty) {
                binding.tvHistoryEmptyTitle.setText(R.string.recordings_empty_title)
                binding.tvHistoryEmptySubtitle.setText(R.string.recordings_empty_subtitle)
            }
        } else {
            binding.tilHistorySearch.visibility = View.VISIBLE
            binding.cardRecordingFolderBanner.visibility = View.GONE
            binding.recyclerRecordings.visibility = View.GONE
            binding.recyclerHistory.visibility = View.VISIBLE
            applyCallLogFilter(binding.etHistorySearch.text?.toString().orEmpty())
        }
    }

    private fun applyCallLogFilter(query: String) {
        if (_binding == null) return
        val filtered = filterCallLogs(allRecentCalls, query)
        callLogAdapter.submitList(filtered)

        if (isRecordingsMode) return

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

    private fun formatDuration(durationMs: Long): String {
        if (durationMs <= 0L) return "00:00"
        val totalSeconds = durationMs / 1000L
        val mins = totalSeconds / 60L
        val secs = totalSeconds % 60L
        return String.format(Locale.US, "%02d:%02d", mins, secs)
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
