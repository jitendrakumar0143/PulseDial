package com.example.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.R
import com.example.billing.SubscriptionManager
import com.example.data.DialerPreferences
import com.example.data.firebase.DynamicSettingItem
import com.example.data.firebase.RealtimeSyncManager
import com.example.data.firebase.SettingsManager
import com.example.databinding.DialogListEditorBinding
import com.example.databinding.FragmentSettingsBinding
import com.example.databinding.ItemSimpleRowBinding
import com.example.recording.CallRecordingAccessibilityService
import com.example.ui.MainActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

/**
 * Server-Driven Dynamic Settings Fragment synced in real time with Firestore
 * (/app_config/settings_items and /users/{uid}) and persisted locally in SharedPreferences.
 */
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private lateinit var prefs: DialerPreferences
    private lateinit var settingsManager: SettingsManager
    private lateinit var subscriptionManager: SubscriptionManager
    private lateinit var settingsAdapter: DynamicSettingsAdapter

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching {
                requireContext().contentResolver.takePersistableUriPermission(treeUri, flags)
            }
            val folderName = DocumentFile.fromTreeUri(requireContext(), treeUri)?.name
                ?: treeUri.lastPathSegment
                ?: "Recordings"
            prefs.recordingFolderUri = treeUri.toString()
            prefs.recordingFolderName = folderName
            bindCurrentValues()
            Toast.makeText(
                requireContext(),
                getString(R.string.setting_folder_updated, folderName),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = DialerPreferences(requireContext())
        settingsManager = SettingsManager(requireContext())
        subscriptionManager = SubscriptionManager(requireContext())

        setupRecyclerView()
        bindCurrentValues()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                RealtimeSyncManager.syncState.collect { state ->
                    if (state.uid != null && _binding != null) {
                        bindCurrentValues(state.settingsItems)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        bindCurrentValues()
    }

    fun refreshTrialUi() {
        if (_binding != null) {
            bindCurrentValues()
        }
    }

    private fun setupRecyclerView() {
        settingsAdapter = DynamicSettingsAdapter(
            onRowClicked = { row -> handleSettingAction(row) },
            onToggleChanged = { row, isChecked -> handleSettingToggle(row, isChecked) }
        )
        binding.recyclerDynamicSettings.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerDynamicSettings.adapter = settingsAdapter
    }

    private fun bindCurrentValues(
        items: List<DynamicSettingItem> = RealtimeSyncManager.syncState.value.settingsItems
    ) {
        val visibleItems = items
            .filter { it.enabled }
            .sortedBy { it.order }

        if (visibleItems.isEmpty()) {
            binding.tvSettingsEmpty.visibility = View.VISIBLE
            binding.cardDynamicSettings.visibility = View.GONE
            settingsAdapter.submitList(emptyList())
            return
        }

        binding.tvSettingsEmpty.visibility = View.GONE
        binding.cardDynamicSettings.visibility = View.VISIBLE

        val uiRows = visibleItems.mapIndexed { index, item ->
            val (desc, checked, interactive) = resolveItemState(item)
            DynamicSettingUiRow(
                item = item,
                resolvedDescription = desc,
                isChecked = checked,
                isInteractive = interactive,
                isLastItem = index == visibleItems.lastIndex
            )
        }
        settingsAdapter.submitList(uiRows)
    }

    private fun resolveItemState(item: DynamicSettingItem): Triple<String, Boolean, Boolean> {
        val lockedText = getString(R.string.setting_locked_by_admin)
        return when (item.key) {
            DynamicSettingItem.KEY_AUTO_RECORDING -> {
                val recordingAllowed = prefs.allowCallRecordingConfig
                val interactive = recordingAllowed && !item.lockedByAdmin
                val checked = recordingAllowed && subscriptionManager.isSubscriptionActive() &&
                    if (item.lockedByAdmin) item.defaultValue else prefs.autoCallRecording
                val baseDesc = item.description.ifBlank { getString(R.string.setting_auto_record_desc) }
                val finalDesc = when {
                    !recordingAllowed -> getString(R.string.setting_recording_disabled_admin)
                    item.lockedByAdmin -> "$baseDesc • $lockedText"
                    else -> baseDesc
                }
                Triple(finalDesc, checked, interactive)
            }
            DynamicSettingItem.KEY_RECORDING_FOLDER -> {
                val folderName = prefs.recordingFolderName
                val desc = if (!folderName.isNullOrBlank()) {
                    getString(R.string.setting_folder_selected, folderName)
                } else {
                    item.description.ifBlank { getString(R.string.setting_folder_default) }
                }
                Triple(desc, false, !item.lockedByAdmin)
            }
            DynamicSettingItem.KEY_BLOCKED_NUMBERS -> {
                val blockedCount = prefs.getBlockedNumbers().size
                val desc = getString(R.string.setting_blocked_desc_count, blockedCount)
                Triple(desc, false, !item.lockedByAdmin)
            }
            DynamicSettingItem.KEY_DISPLAY_THEME -> {
                val isDark = prefs.displayThemeMode == AppCompatDelegate.MODE_NIGHT_YES
                val desc = getString(
                    if (isDark) R.string.setting_theme_dark else R.string.setting_theme_light
                )
                Triple(desc, false, !item.lockedByAdmin)
            }
            DynamicSettingItem.KEY_SOUNDS_VIBRATION -> {
                val checked = if (item.lockedByAdmin) item.defaultValue else prefs.dialpadTonesEnabled
                val baseDesc = item.description.ifBlank { getString(R.string.setting_sounds_desc) }
                val desc = if (item.lockedByAdmin) "$baseDesc • $lockedText" else baseDesc
                Triple(desc, checked, !item.lockedByAdmin)
            }
            DynamicSettingItem.KEY_QUICK_DECLINE -> {
                val declineCount = prefs.getQuickDeclineResponses().size
                val desc = getString(R.string.setting_quick_decline_desc, declineCount)
                Triple(desc, false, !item.lockedByAdmin)
            }
            else -> {
                val checked = if (item.lockedByAdmin) {
                    item.defaultValue
                } else {
                    prefs.getDynamicToggle(item.key, item.defaultValue)
                }
                val baseDesc = item.description.ifBlank { item.title }
                val desc = if (item.lockedByAdmin) "$baseDesc • $lockedText" else baseDesc
                Triple(desc, checked, !item.lockedByAdmin)
            }
        }
    }

    private fun handleSettingToggle(row: DynamicSettingUiRow, isChecked: Boolean): Boolean {
        val item = row.item
        if (item.lockedByAdmin) {
            Toast.makeText(requireContext(), R.string.setting_locked_by_admin, Toast.LENGTH_SHORT).show()
            return false
        }
        return when (item.key) {
            DynamicSettingItem.KEY_AUTO_RECORDING -> {
                if (isChecked && !prefs.allowCallRecordingConfig) {
                    Toast.makeText(requireContext(), R.string.setting_recording_disabled_admin, Toast.LENGTH_SHORT).show()
                    false
                } else if (isChecked && !subscriptionManager.isSubscriptionActive()) {
                    (activity as? MainActivity)?.showTrialExpiredDialog()
                    false
                } else {
                    settingsManager.setAutoCallRecording(isChecked)
                    if (isChecked &&
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                        !CallRecordingAccessibilityService.isServiceEnabled(requireContext())
                    ) {
                        showAccessibilityEnablePrompt()
                    }
                    true
                }
            }
            DynamicSettingItem.KEY_SOUNDS_VIBRATION -> {
                settingsManager.setDialpadTonesEnabled(isChecked)
                true
            }
            else -> {
                prefs.setDynamicToggle(item.key, isChecked)
                true
            }
        }
    }

    private fun handleSettingAction(row: DynamicSettingUiRow) {
        val item = row.item
        if (!row.isInteractive) {
            Toast.makeText(
                requireContext(),
                if (item.key == DynamicSettingItem.KEY_AUTO_RECORDING && !prefs.allowCallRecordingConfig) {
                    R.string.setting_recording_disabled_admin
                } else {
                    R.string.setting_locked_by_admin
                },
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        when (item.key) {
            DynamicSettingItem.KEY_RECORDING_FOLDER -> folderPickerLauncher.launch(null)
            DynamicSettingItem.KEY_BLOCKED_NUMBERS -> {
                showListEditorDialog(
                    title = item.title.ifBlank { getString(R.string.setting_blocked_title) },
                    hint = getString(R.string.dialog_add_blocked_hint),
                    inputType = InputType.TYPE_CLASS_PHONE,
                    loadItems = { prefs.getBlockedNumbers() },
                    onAddItem = { settingsManager.addBlockedNumber(it) },
                    onRemoveItem = { settingsManager.removeBlockedNumber(it) }
                )
            }
            DynamicSettingItem.KEY_DISPLAY_THEME -> showThemePickerDialog()
            DynamicSettingItem.KEY_QUICK_DECLINE -> {
                showListEditorDialog(
                    title = item.title.ifBlank { getString(R.string.setting_quick_decline_title) },
                    hint = getString(R.string.dialog_add_decline_hint),
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
                    loadItems = { prefs.getQuickDeclineResponses() },
                    onAddItem = { settingsManager.addQuickDeclineResponse(it) },
                    onRemoveItem = { settingsManager.removeQuickDeclineResponse(it) }
                )
            }
            else -> {
                val url = item.actionUrl.trim()
                if (url.isNotEmpty()) {
                    val normalized = if (!url.contains("://") && !url.startsWith("tel:") && !url.startsWith("mailto:")) {
                        "https://$url"
                    } else {
                        url
                    }
                    runCatching {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(normalized)))
                    }.onFailure {
                        Toast.makeText(requireContext(), R.string.broadcast_invalid_link, Toast.LENGTH_SHORT).show()
                    }
                } else {
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(item.title)
                        .setMessage(item.description.ifBlank { item.title })
                        .setPositiveButton(R.string.dialog_close, null)
                        .show()
                }
            }
        }
    }

    private fun showThemePickerDialog() {
        val options = arrayOf(
            getString(R.string.setting_theme_light),
            getString(R.string.setting_theme_dark)
        )
        val checkedIndex = if (prefs.displayThemeMode == AppCompatDelegate.MODE_NIGHT_YES) 1 else 0

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.setting_theme_title)
            .setSingleChoiceItems(options, checkedIndex) { dialog, which ->
                val mode = if (which == 1) {
                    AppCompatDelegate.MODE_NIGHT_YES
                } else {
                    AppCompatDelegate.MODE_NIGHT_NO
                }
                settingsManager.setThemeMode(mode)
                bindCurrentValues()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.dialog_close, null)
            .show()
    }

    private fun showAccessibilityEnablePrompt() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.accessibility_prompt_title)
            .setMessage(
                getString(
                    R.string.accessibility_prompt_message,
                    getString(R.string.accessibility_service_label)
                )
            )
            .setPositiveButton(R.string.accessibility_prompt_open) { _, _ ->
                runCatching {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            }
            .setNegativeButton(R.string.trial_expired_dialog_later, null)
            .show()
    }

    private fun showListEditorDialog(
        title: String,
        hint: String,
        inputType: Int,
        loadItems: () -> List<String>,
        onAddItem: (String) -> Unit,
        onRemoveItem: (String) -> Unit
    ) {
        val dialogBinding = DialogListEditorBinding.inflate(layoutInflater)
        dialogBinding.tilDialogEntry.hint = hint
        dialogBinding.etDialogEntry.inputType = inputType

        val items = loadItems().toMutableList()
        lateinit var simpleAdapter: SimpleStringAdapter
        simpleAdapter = SimpleStringAdapter(items) { itemToRemove ->
            onRemoveItem(itemToRemove)
            items.clear()
            items.addAll(loadItems())
            simpleAdapter.notifyDataSetChanged()
            bindCurrentValues()
        }

        dialogBinding.recyclerDialogItems.layoutManager = LinearLayoutManager(requireContext())
        dialogBinding.recyclerDialogItems.adapter = simpleAdapter

        dialogBinding.btnDialogAdd.setOnClickListener {
            val text = dialogBinding.etDialogEntry.text?.toString().orEmpty().trim()
            if (text.isNotEmpty()) {
                onAddItem(text)
                dialogBinding.etDialogEntry.setText("")
                items.clear()
                items.addAll(loadItems())
                simpleAdapter.notifyDataSetChanged()
                bindCurrentValues()
            }
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.dialog_close, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private class SimpleStringAdapter(
        private val items: List<String>,
        private val onDelete: (String) -> Unit
    ) : RecyclerView.Adapter<SimpleStringAdapter.RowViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowViewHolder {
            val binding = ItemSimpleRowBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return RowViewHolder(binding)
        }

        override fun onBindViewHolder(holder: RowViewHolder, position: Int) {
            val item = items[position]
            holder.binding.tvRowValue.text = item
            holder.binding.btnRowDelete.setOnClickListener { onDelete(item) }
        }

        override fun getItemCount(): Int = items.size

        class RowViewHolder(val binding: ItemSimpleRowBinding) :
            RecyclerView.ViewHolder(binding.root)
    }
}
