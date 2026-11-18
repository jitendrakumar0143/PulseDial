package com.example.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.Settings
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.R
import com.example.billing.SubscriptionEnforcementService
import com.example.billing.SubscriptionManager
import com.example.data.DialerPreferences
import com.example.data.firebase.DynamicSettingItem
import com.example.data.firebase.RealtimeSyncManager
import com.example.data.firebase.SettingsManager
import com.example.data.firebase.UserEntitlement
import com.example.databinding.DialogListEditorBinding
import com.example.databinding.FragmentSettingsBinding
import com.example.databinding.ItemSimpleRowBinding
import com.example.recording.CallRecordingSource
import com.example.recording.VoipCallNotificationListener
import com.example.telecom.DialerHelper
import com.example.telecom.DualSimManager
import com.example.ui.MainActivity
import com.example.ui.theme.AppThemePalette
import com.example.ui.theme.ThemeStudioManager
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Server-Driven Dynamic Settings Fragment + VIP Smart Call Announcer, Unknown Caller Shield,
 * and VIP Business Auto Follow-Up (WhatsApp & SMS) synced in <1s with Firebase Realtime Database
 * (`https://gen-lang-client-0353534085-default-rtdb.firebaseio.com`) and Firestore (`/users/{uid}`).
 */
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private lateinit var prefs: DialerPreferences
    private lateinit var settingsManager: SettingsManager
    private lateinit var subscriptionManager: SubscriptionManager
    private lateinit var settingsAdapter: DynamicSettingsAdapter
    private var isBindingVipControls: Boolean = false
    private var isBindingVoipControls: Boolean = false
    private var isBindingThemeControls: Boolean = false
    private val paletteChipIds = LinkedHashMap<AppThemePalette, Int>()
    private var pendingVoipAction: (() -> Unit)? = null
    private var pendingFolderSource: CallRecordingSource? = null
    @Volatile
    private var cachedAccessibilityEnabled: Boolean = false
    @Volatile
    private var cachedAvailableSims: List<com.example.telecom.SimCardInfo> = emptyList()
    private var lastBoundRowsSignature: String? = null
    private var activeSettingsDialog: androidx.appcompat.app.AlertDialog? = null

    private val voipAudioPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (_binding == null) return@registerForActivityResult
        val recordGranted = results[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        val action = pendingVoipAction
        pendingVoipAction = null
        if (recordGranted) {
            action?.invoke()
            bindVoipCardState()
            if (!VoipCallNotificationListener.isNotificationAccessGranted(requireContext())) {
                showVoipNotificationAccessPrompt()
            }
        } else {
            bindVoipCardState()
            Toast.makeText(
                requireContext(),
                R.string.voip_mic_permission_required,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        val targetSource = pendingFolderSource
        pendingFolderSource = null
        if (treeUri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching {
                requireContext().contentResolver.takePersistableUriPermission(treeUri, flags)
            }
            val folderName = resolveReadableFolderName(treeUri)
            if (targetSource != null) {
                prefs.setCustomFolder(targetSource, treeUri.toString(), folderName)
                bindCurrentValues()
                Toast.makeText(
                    requireContext(),
                    getString(R.string.voip_folder_updated_for_app, targetSource.displayLabel, folderName),
                    Toast.LENGTH_SHORT
                ).show()
            } else {
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
    }

    private fun resolveReadableFolderName(treeUri: Uri): String {
        val docName = DocumentFile.fromTreeUri(requireContext(), treeUri)?.name?.trim()
        if (!docName.isNullOrEmpty()) return docName
        val decodedSegment = treeUri.lastPathSegment
            ?.substringAfter(':', "")
            ?.trim('/')
            ?.substringAfterLast('/')
            ?.trim()
        if (!decodedSegment.isNullOrEmpty()) return decodedSegment
        return treeUri.lastPathSegment?.substringAfterLast('/')?.ifBlank { null } ?: "Recordings"
    }

    private fun launchFolderPickerForSource(source: CallRecordingSource?) {
        pendingFolderSource = source
        val initialUri = (
            source?.let { prefs.getCustomFolderUri(it) } ?: prefs.recordingFolderUri
        )?.let { raw -> runCatching { Uri.parse(raw) }.getOrNull() }
        folderPickerLauncher.launch(initialUri)
    }

    private fun resetFolderForSource(source: CallRecordingSource) {
        prefs.clearCustomFolder(source)
        bindCurrentValues()
        Toast.makeText(
            requireContext(),
            getString(R.string.voip_folder_reset_for_app, source.displayLabel),
            Toast.LENGTH_SHORT
        ).show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingFolderSource?.name?.let { outState.putString(STATE_PENDING_FOLDER_SOURCE, it) }
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
        savedInstanceState?.getString(STATE_PENDING_FOLDER_SOURCE)?.let { raw ->
            pendingFolderSource = runCatching { CallRecordingSource.valueOf(raw) }.getOrNull()
        }

        setupRecyclerView()
        setupDefaultSimCard()
        setupThemeStudioCard()
        setupVoipRecordingCardListeners()
        setupVipCardsListeners()
        bindCurrentValues(force = true)
        refreshAccessibilityStatusAsync()
        refreshAvailableSimsAsync()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    RealtimeSyncManager.syncState
                        .map { Triple(it.uid, it.settingsItems, it.hideSubscriptionUi) }
                        .distinctUntilChanged()
                        .collect { (uid, items, _) ->
                            if (uid != null && _binding != null && isAdded) {
                                bindCurrentValues(items)
                            }
                        }
                }
                launch {
                    SubscriptionEnforcementService.state
                        .map { Triple(it.shouldRestrictUiToAccountTab, it.isBlocked, it.isPremium) }
                        .distinctUntilChanged()
                        .collect {
                            if (_binding != null && isAdded) {
                                bindCurrentValues()
                            }
                        }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null && isAdded) {
            bindCurrentValues()
            refreshAccessibilityStatusAsync()
            refreshAvailableSimsAsync()
        }
    }

    fun refreshTrialUi() {
        if (_binding != null && isAdded) {
            bindCurrentValues(force = true)
        }
    }

    private fun refreshAccessibilityStatusAsync() {
        val ctx = context?.applicationContext ?: return
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val enabled = runCatching {
                DialerHelper.isDefaultDialer(ctx)
            }.getOrDefault(false)
            if (enabled != cachedAccessibilityEnabled) {
                cachedAccessibilityEnabled = enabled
                withContext(Dispatchers.Main) {
                    if (_binding != null && isAdded) {
                        bindCurrentValues(force = true)
                    }
                }
            }
        }
    }

    private fun refreshAvailableSimsAsync() {
        val ctx = context?.applicationContext ?: return
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val sims = runCatching {
                DualSimManager.getAvailableSims(ctx)
            }.getOrDefault(emptyList())
            if (sims != cachedAvailableSims) {
                cachedAvailableSims = sims
                withContext(Dispatchers.Main) {
                    if (_binding != null && isAdded) {
                        bindDefaultSimCard()
                    }
                }
            }
        }
    }

    private fun isVipEntitledSnapshot(): Boolean {
        return !prefs.adminBlockedUser &&
            !SubscriptionEnforcementService.state.value.shouldRestrictUiToAccountTab &&
            subscriptionManager.isSubscriptionActive()
    }

    private fun isVipEntitled(): Boolean {
        return !prefs.adminBlockedUser &&
            !SubscriptionEnforcementService.shouldRestrictUi(requireContext()) &&
            subscriptionManager.isSubscriptionActive()
    }

    private fun handleVoipToggleChange(isChecked: Boolean, persistChange: (Boolean) -> Unit) {
        if (!isVipEntitled()) {
            bindVoipCardState()
            return
        }
        if (!isChecked) {
            persistChange(false)
            return
        }
        if (!prefs.allowCallRecordingConfig) {
            bindVoipCardState()
            Toast.makeText(requireContext(), R.string.setting_recording_disabled_admin, Toast.LENGTH_SHORT).show()
            return
        }

        val ctx = requireContext()
        val missingPerms = buildList {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.RECORD_AUDIO)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (missingPerms.isNotEmpty()) {
            pendingVoipAction = { persistChange(true) }
            voipAudioPermissionsLauncher.launch(missingPerms.toTypedArray())
            return
        }

        persistChange(true)
        if (!VoipCallNotificationListener.isNotificationAccessGranted(ctx)) {
            showVoipNotificationAccessPrompt()
        }
    }

    private fun setupVoipRecordingCardListeners() {
        binding.switchRecordWhatsapp.setOnCheckedChangeListener { _, isChecked ->
            if (isBindingVoipControls) return@setOnCheckedChangeListener
            handleVoipToggleChange(isChecked) { settingsManager.setRecordWhatsappCalls(it) }
        }
        binding.switchRecordWhatsappBusiness.setOnCheckedChangeListener { _, isChecked ->
            if (isBindingVoipControls) return@setOnCheckedChangeListener
            handleVoipToggleChange(isChecked) { settingsManager.setRecordWhatsappBusinessCalls(it) }
        }
        binding.switchRecordMeet.setOnCheckedChangeListener { _, isChecked ->
            if (isBindingVoipControls) return@setOnCheckedChangeListener
            handleVoipToggleChange(isChecked) { settingsManager.setRecordMeetCalls(it) }
        }
        binding.switchRecordTelegram.setOnCheckedChangeListener { _, isChecked ->
            if (isBindingVoipControls) return@setOnCheckedChangeListener
            handleVoipToggleChange(isChecked) { settingsManager.setRecordTelegramCalls(it) }
        }
        binding.switchRecordInstagram.setOnCheckedChangeListener { _, isChecked ->
            if (isBindingVoipControls) return@setOnCheckedChangeListener
            handleVoipToggleChange(isChecked) { settingsManager.setRecordInstagramCalls(it) }
        }

        binding.btnManageVoipFolders.setOnClickListener {
            if (!isVipEntitled()) return@setOnClickListener
            showVoipFoldersDialog()
        }
    }

    private fun bindVoipCardState() {
        isBindingVoipControls = true
        try {
            val vipEntitled = isVipEntitledSnapshot()
            val allowed = vipEntitled && prefs.allowCallRecordingConfig
            val wa = allowed && prefs.recordWhatsappCalls
            val wab = allowed && prefs.recordWhatsappBusinessCalls
            val meet = allowed && prefs.recordMeetCalls
            val tg = allowed && prefs.recordTelegramCalls
            val ig = allowed && prefs.recordInstagramCalls

            binding.cardVoipCallRecording.alpha = if (vipEntitled) 1f else 0.7f

            fun applyFrozenState(view: View, enabled: Boolean) {
                view.isEnabled = enabled
                view.isClickable = enabled
                view.alpha = if (enabled) 1f else 0.5f
            }

            listOf(
                binding.switchRecordWhatsapp to wa,
                binding.switchRecordWhatsappBusiness to wab,
                binding.switchRecordMeet to meet,
                binding.switchRecordTelegram to tg,
                binding.switchRecordInstagram to ig
            ).forEach { (switchView, checked) ->
                if (switchView.isChecked != checked) {
                    switchView.isChecked = checked
                }
                applyFrozenState(switchView, allowed)
            }

            applyFrozenState(binding.btnManageVoipFolders, allowed)
        } finally {
            isBindingVoipControls = false
        }
    }

    fun showVoipFoldersDialog() {
        val ctx = context ?: return
        val sources = CallRecordingSource.entries
        val labels = sources.map { source ->
            val target = prefs.resolveFolderTarget(source)
            val status = if (target.isCustomPerSource) {
                getString(R.string.voip_folder_custom_path, target.displayName)
            } else if (!target.treeUri.isNullOrBlank()) {
                getString(R.string.voip_folder_global_path, target.displayName)
            } else {
                target.displayName
            }
            "${source.displayLabel} — $status"
        }.toTypedArray()

        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.voip_folders_dialog_title)
            .setItems(labels) { _, which ->
                val chosen = sources.getOrNull(which) ?: return@setItems
                val target = prefs.resolveFolderTarget(chosen)
                if (target.isCustomPerSource) {
                    MaterialAlertDialogBuilder(ctx)
                        .setTitle(chosen.displayLabel)
                        .setItems(
                            arrayOf(
                                getString(R.string.voip_folder_change_btn),
                                getString(R.string.voip_folders_reset_item, chosen.displayLabel)
                            )
                        ) { _, actionIndex ->
                            if (actionIndex == 0) {
                                launchFolderPickerForSource(chosen)
                            } else {
                                resetFolderForSource(chosen)
                            }
                        }
                        .setNegativeButton(R.string.dialog_close, null)
                        .show()
                } else {
                    launchFolderPickerForSource(chosen)
                }
            }
            .setNeutralButton(R.string.voip_open_file_manager_btn) { _, _ ->
                openFileManagerRecordings()
            }
            .setPositiveButton(R.string.dialog_close, null)
            .show()
    }

    private fun showVoipNotificationAccessPrompt() {
        val ctx = context ?: return
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.voip_notification_access_title)
            .setMessage(R.string.voip_notification_access_message)
            .setPositiveButton(R.string.voip_notification_access_enable) { _, _ ->
                runCatching {
                    startActivity(VoipCallNotificationListener.createNotificationAccessSettingsIntent())
                }
            }
            .setNegativeButton(R.string.trial_expired_dialog_later, null)
            .show()
    }

    private fun openFileManagerRecordings() {
        val ctx = requireContext()
        val configuredUris = buildList {
            prefs.recordingFolderUri?.takeIf { it.isNotBlank() }?.let { add(it) }
            CallRecordingSource.entries.forEach { src ->
                prefs.getCustomFolderUri(src)?.takeIf { it.isNotBlank() }?.let { add(it) }
            }
        }.distinct()
        val candidateIntents = buildList {
            configuredUris.forEach { folderUriStr ->
                val treeUri = Uri.parse(folderUriStr)
                add(
                    Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(treeUri, DocumentsContract.Document.MIME_TYPE_DIR)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                )
            }
            add(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(
                        DocumentsContract.buildRootUri(
                            "com.android.externalstorage.documents",
                            "primary"
                        ),
                        DocumentsContract.Document.MIME_TYPE_DIR
                    )
                }
            )
            add(
                Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_APP_FILES)
                }
            )
        }

        for (intent in candidateIntents) {
            val launched = runCatching {
                startActivity(intent)
                true
            }.getOrDefault(false)
            if (launched) return
        }

        Toast.makeText(
            ctx,
            R.string.voip_file_manager_unavailable,
            Toast.LENGTH_LONG
        ).show()
    }

    private fun setupVipCardsListeners() {
        binding.switchVipAnnouncer.setOnCheckedChangeListener { _, isChecked ->
            if (isBindingVipControls) return@setOnCheckedChangeListener
            if (!isVipEntitled()) {
                bindVipCardsState()
                return@setOnCheckedChangeListener
            }
            settingsManager.setAnnouncerEnabled(isChecked)
        }

        binding.switchVipAnnouncerHeadset.setOnCheckedChangeListener { _, isChecked ->
            if (isBindingVipControls) return@setOnCheckedChangeListener
            if (!isVipEntitled()) {
                bindVipCardsState()
                return@setOnCheckedChangeListener
            }
            settingsManager.setAnnouncerHeadsetOnly(isChecked)
        }

        binding.rgVipUnknownShield.setOnCheckedChangeListener { _, checkedId ->
            if (isBindingVipControls) return@setOnCheckedChangeListener
            if (!isVipEntitled()) {
                bindVipCardsState()
                return@setOnCheckedChangeListener
            }
            val mode = when (checkedId) {
                R.id.rb_shield_silent_record -> UserEntitlement.SHIELD_SILENT_RECORD
                R.id.rb_shield_auto_reject -> UserEntitlement.SHIELD_AUTO_REJECT
                else -> UserEntitlement.SHIELD_OFF
            }
            settingsManager.setUnknownShieldMode(mode)
        }

        binding.switchVipAutoSms.setOnCheckedChangeListener { _, isChecked ->
            if (isBindingVipControls) return@setOnCheckedChangeListener
            if (!isVipEntitled()) {
                bindVipCardsState()
                return@setOnCheckedChangeListener
            }
            settingsManager.setAutoSmsFollowUp(isChecked)
        }

        binding.switchVipAutoWhatsapp.setOnCheckedChangeListener { _, isChecked ->
            if (isBindingVipControls) return@setOnCheckedChangeListener
            if (!isVipEntitled()) {
                bindVipCardsState()
                return@setOnCheckedChangeListener
            }
            settingsManager.setAutoWhatsappFollowUp(isChecked)
        }

        binding.rgVipFollowupTrigger.setOnCheckedChangeListener { _, checkedId ->
            if (isBindingVipControls) return@setOnCheckedChangeListener
            if (!isVipEntitled()) {
                bindVipCardsState()
                return@setOnCheckedChangeListener
            }
            val trigger = when (checkedId) {
                R.id.rb_trigger_missed_only -> UserEntitlement.TRIGGER_MISSED_ONLY
                R.id.rb_trigger_completed_only -> UserEntitlement.TRIGGER_COMPLETED_ONLY
                else -> UserEntitlement.TRIGGER_ALL_CALLS
            }
            settingsManager.setFollowUpTrigger(trigger)
        }

        binding.btnVipSaveTemplates.setOnClickListener {
            if (!isVipEntitled()) {
                return@setOnClickListener
            }
            val completedText = binding.etVipCompletedTemplate.text?.toString().orEmpty()
            val missedText = binding.etVipMissedTemplate.text?.toString().orEmpty()
            settingsManager.saveFollowUpTemplates(completedText, missedText)
            bindVipCardsState()
            Toast.makeText(
                requireContext(),
                R.string.vip_templates_saved_toast,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun bindVipCardsState() {
        isBindingVipControls = true
        try {
            val entitled = isVipEntitledSnapshot()
            val announcerOn = entitled && prefs.announcerEnabled
            if (binding.switchVipAnnouncer.isChecked != announcerOn) {
                binding.switchVipAnnouncer.isChecked = announcerOn
            }
            val headsetOn = entitled && prefs.announcerHeadsetOnly
            if (binding.switchVipAnnouncerHeadset.isChecked != headsetOn) {
                binding.switchVipAnnouncerHeadset.isChecked = headsetOn
            }

            val shieldMode = if (entitled) prefs.unknownShieldMode else UserEntitlement.SHIELD_OFF
            val shieldRadioId = when (shieldMode) {
                UserEntitlement.SHIELD_SILENT_RECORD -> R.id.rb_shield_silent_record
                UserEntitlement.SHIELD_AUTO_REJECT -> R.id.rb_shield_auto_reject
                else -> R.id.rb_shield_off
            }
            if (binding.rgVipUnknownShield.checkedRadioButtonId != shieldRadioId) {
                binding.rgVipUnknownShield.check(shieldRadioId)
            }

            val smsOn = entitled && prefs.autoSmsFollowUp
            if (binding.switchVipAutoSms.isChecked != smsOn) {
                binding.switchVipAutoSms.isChecked = smsOn
            }
            val waFollowOn = entitled && prefs.autoWhatsappFollowUp
            if (binding.switchVipAutoWhatsapp.isChecked != waFollowOn) {
                binding.switchVipAutoWhatsapp.isChecked = waFollowOn
            }

            val triggerRadioId = when (prefs.followUpTrigger) {
                UserEntitlement.TRIGGER_MISSED_ONLY -> R.id.rb_trigger_missed_only
                UserEntitlement.TRIGGER_COMPLETED_ONLY -> R.id.rb_trigger_completed_only
                else -> R.id.rb_trigger_all_calls
            }
            if (binding.rgVipFollowupTrigger.checkedRadioButtonId != triggerRadioId) {
                binding.rgVipFollowupTrigger.check(triggerRadioId)
            }

            val completedTemplate = prefs.completedCallTemplate
            if (!binding.etVipCompletedTemplate.hasFocus() &&
                binding.etVipCompletedTemplate.text?.toString() != completedTemplate
            ) {
                binding.etVipCompletedTemplate.setText(completedTemplate)
            }
            val missedTemplate = prefs.missedCallTemplate
            if (!binding.etVipMissedTemplate.hasFocus() &&
                binding.etVipMissedTemplate.text?.toString() != missedTemplate
            ) {
                binding.etVipMissedTemplate.setText(missedTemplate)
            }

            binding.cardVipAnnouncerShield.alpha = if (entitled) 1f else 0.65f
            binding.cardVipBusinessFollowup.alpha = if (entitled) 1f else 0.65f
            listOf(
                binding.switchVipAnnouncer,
                binding.switchVipAnnouncerHeadset,
                binding.rgVipUnknownShield,
                binding.rbShieldOff,
                binding.rbShieldSilentRecord,
                binding.rbShieldAutoReject,
                binding.switchVipAutoSms,
                binding.switchVipAutoWhatsapp,
                binding.rgVipFollowupTrigger,
                binding.rbTriggerAllCalls,
                binding.rbTriggerMissedOnly,
                binding.rbTriggerCompletedOnly,
                binding.etVipCompletedTemplate,
                binding.etVipMissedTemplate,
                binding.btnVipSaveTemplates
            ).forEach { control ->
                control.isEnabled = entitled
                control.isClickable = entitled
                control.alpha = if (entitled) 1f else 0.5f
            }
        } finally {
            isBindingVipControls = false
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

    private fun setupDefaultSimCard() {
        binding.cardDefaultCallingSim.setOnClickListener {
            showDefaultSimPickerDialog()
        }
        binding.btnSelectDefaultSim.setOnClickListener {
            showDefaultSimPickerDialog()
        }
    }

    private fun bindDefaultSimCard() {
        if (_binding == null || !isAdded) return
        val sims = cachedAvailableSims
        val summary = when {
            sims.isEmpty() -> getString(R.string.setting_default_sim_no_sim)
            sims.size == 1 -> getString(R.string.setting_default_sim_single, sims.first().fullDescription())
            else -> {
                val preferred = DualSimManager.resolvePreferredSim(sims, prefs.preferredSimAccountId)
                if (preferred != null) {
                    getString(R.string.setting_default_sim_selected, preferred.fullDescription())
                } else {
                    getString(R.string.setting_default_sim_always_ask)
                }
            }
        }
        binding.tvDefaultSimSummary.text = summary
        // Normal calling preference MUST stay unlocked even when subscription expires
        binding.cardDefaultCallingSim.isEnabled = true
        binding.cardDefaultCallingSim.isClickable = true
        binding.cardDefaultCallingSim.alpha = 1f
        binding.btnSelectDefaultSim.isEnabled = true
        binding.btnSelectDefaultSim.isClickable = true
    }

    private fun showDefaultSimPickerDialog() {
        val ctx = requireContext()
        val sims = DualSimManager.getAvailableSims(ctx)
        if (sims.isEmpty()) {
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.setting_default_sim_dialog_title)
                .setMessage(R.string.setting_default_sim_no_sim)
                .setPositiveButton(R.string.dialog_close, null)
                .show()
            return
        }

        val alwaysAskLabel = getString(R.string.setting_default_sim_always_ask)
        val optionLabels = (listOf(alwaysAskLabel) + sims.map { it.fullDescription() }).toTypedArray()
        val currentIdx = sims.indexOfFirst { it.accountId == prefs.preferredSimAccountId }
        val checkedIdx = if (currentIdx >= 0) currentIdx + 1 else 0

        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.setting_default_sim_dialog_title)
            .setSingleChoiceItems(optionLabels, checkedIdx) { dialog, which ->
                if (which == 0) {
                    prefs.preferredSimAccountId = ""
                } else {
                    sims.getOrNull(which - 1)?.let { chosen ->
                        prefs.preferredSimAccountId = chosen.accountId
                    }
                }
                bindDefaultSimCard()
                val chosenLabel = optionLabels.getOrNull(which) ?: alwaysAskLabel
                Toast.makeText(
                    ctx,
                    getString(R.string.setting_default_sim_updated, chosenLabel),
                    Toast.LENGTH_SHORT
                ).show()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.dialog_close, null)
            .show()
    }

    private fun setupThemeStudioCard() {
        val ctx = requireContext()
        binding.chipGroupThemePalettes.removeAllViews()
        paletteChipIds.clear()

        val hasPremiumThemes = ThemeStudioManager.isUserEntitledToPremiumThemes(prefs)
        AppThemePalette.premium3DMixThemes().forEach { palette ->
            val isLocked = !hasPremiumThemes
            val chip = Chip(ctx).apply {
                id = View.generateViewId()
                text = if (isLocked) {
                    "🔒 ${palette.title} (PRO)"
                } else {
                    "👑 ${palette.title}"
                }
                isCheckable = true
                isClickable = true
                chipStrokeWidth = 2f
                chipStrokeColor = android.content.res.ColorStateList.valueOf(palette.cardStrokeColorInt())
            }
            paletteChipIds[palette] = chip.id
            binding.chipGroupThemePalettes.addView(chip)
        }

        binding.chipGroupThemeMode.setOnCheckedStateChangeListener { _, checkedIds ->
            if (isBindingThemeControls) return@setOnCheckedStateChangeListener
            val checkedId = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            val desiredMode = when (checkedId) {
                R.id.chip_theme_mode_system -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                R.id.chip_theme_mode_dark -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_NO
            }
            if (prefs.displayThemeMode != desiredMode) {
                settingsManager.setThemeMode(desiredMode)
                bindThemeStudioCard()
                bindCurrentValues(force = true)
            }
        }

        binding.switchDynamicColors.setOnCheckedChangeListener { _, isChecked ->
            if (isBindingThemeControls) return@setOnCheckedChangeListener
            if (prefs.useDynamicColors != isChecked) {
                settingsManager.setUseDynamicColors(isChecked)
                bindThemeStudioCard()
                bindCurrentValues(force = true)
                view?.post {
                    if (isAdded && activity?.isFinishing == false) {
                        activity?.recreate()
                    }
                }
            }
        }

        binding.chipGroupThemePalettes.setOnCheckedStateChangeListener { _, checkedIds ->
            if (isBindingThemeControls) return@setOnCheckedStateChangeListener
            val checkedId = checkedIds.firstOrNull()
            if (checkedId == null) {
                applySelectedThemePalette(AppThemePalette.EMERALD_PULSE)
                return@setOnCheckedStateChangeListener
            }
            val chosen = paletteChipIds.entries.firstOrNull { it.value == checkedId }?.key ?: return@setOnCheckedStateChangeListener
            applySelectedThemePalette(chosen)
        }

        binding.btnOpenThemeGallery.setOnClickListener {
            showThemePickerDialog()
        }
        binding.bannerTheme3dPreview.setOnClickListener {
            showThemePickerDialog()
        }
    }

    private fun showPremiumThemeLockedPrompt(lockedPalette: AppThemePalette) {
        val ctx = context ?: return
        MaterialAlertDialogBuilder(ctx)
            .setTitle("👑 Full-App 3D Mix-Color Theme (Pro Only)")
            .setMessage(
                "\"${lockedPalette.title}\" is one of the 3 exclusive Full-App 3D Mix-Color Themes (transforming the full app background, 3D glass cards, dialer keypad, and all text colors).\n\nOnly active Premium subscribers can use the 3 Full-App 3D Mix-Color Themes. Upgrade to PulseDial Pro in the Account tab to unlock!"
            )
            .setPositiveButton("Upgrade to Pro 👑") { _, _ ->
                (activity as? MainActivity)?.navigateToAccountTab()
            }
            .setNegativeButton(R.string.dialog_close, null)
            .show()
    }

    private fun applySelectedThemePalette(palette: AppThemePalette) {
        if (palette.isPremiumTheme && !ThemeStudioManager.isUserEntitledToPremiumThemes(prefs)) {
            bindThemeStudioCard()
            showPremiumThemeLockedPrompt(palette)
            return
        }
        val changed = prefs.selectedThemePaletteId != palette.id
        settingsManager.setThemePalette(palette.id)
        if (palette.is3DMix && prefs.useDynamicColors) {
            settingsManager.setUseDynamicColors(false)
        }
        bindThemeStudioCard()
        bindCurrentValues(force = true)
        (activity as? MainActivity)?.refreshPremiumMainChrome()
        if (_binding != null) {
            ThemeStudioManager.applyFullApp3DThemeToViewHierarchy(binding.root, prefs)
        }
        Toast.makeText(
            requireContext(),
            getString(R.string.setting_theme_applied_toast, palette.title),
            Toast.LENGTH_SHORT
        ).show()
        if (changed) {
            view?.post {
                if (isAdded && activity?.isFinishing == false) {
                    activity?.recreate()
                }
            }
        }
    }

    private fun bindThemeStudioCard() {
        if (_binding == null || !isAdded) return
        isBindingThemeControls = true
        try {
            val ctx = requireContext()
            val hasPremiumThemes = ThemeStudioManager.isUserEntitledToPremiumThemes(prefs)
            val palette = ThemeStudioManager.resolveEffectivePalette(prefs)
            val density = resources.displayMetrics.density
            binding.bannerTheme3dPreview.background = if (palette.is3DMix) {
                palette.createFullApp3DBackgroundDrawable(cornerRadiusPx = 18f * density)
            } else {
                palette.create3DGradientDrawable(
                    cornerRadiusPx = 18f * density,
                    strokeColorInt = android.graphics.Color.argb(110, 255, 255, 255),
                    strokeWidthPx = (1.5f * density).toInt().coerceAtLeast(1)
                )
            }
            binding.tvActiveThemePaletteName.text = if (hasPremiumThemes && palette.isPremiumTheme) {
                "👑 ${palette.title}"
            } else {
                "${palette.title} (3 Pro 3D Themes Available)"
            }
            binding.tvActiveThemePaletteName.setTextColor(
                if (palette.is3DMix) palette.textPrimaryColorInt() else android.graphics.Color.WHITE
            )
            binding.tvActiveThemePaletteSubtitle.text =
                "${ThemeStudioManager.resolveThemeModeLabel(ctx, prefs.displayThemeMode)} • ${palette.subtitle}"
            binding.tvActiveThemePaletteSubtitle.setTextColor(
                if (palette.is3DMix) palette.textSecondaryColorInt() else android.graphics.Color.parseColor("#F1F5F9")
            )
            binding.tvActiveThemeBadge.text = when {
                hasPremiumThemes && palette.is3DMix -> "👑 ${palette.badgeText}"
                prefs.useDynamicColors -> "DYNAMIC"
                else -> "🔒 3 PRO 3D THEMES"
            }
            binding.tvActiveThemeBadge.setTextColor(palette.primaryColorInt())
            binding.cardThemeStudio.strokeColor = if (palette.is3DMix) palette.cardStrokeColorInt() else palette.primaryColorInt()
            binding.cardThemeStudio.strokeWidth = if (hasPremiumThemes && palette.is3DMix) {
                (2f * density).toInt().coerceAtLeast(2)
            } else {
                (1f * density).toInt().coerceAtLeast(1)
            }

            paletteChipIds.forEach { (pal, chipId) ->
                val chipView = binding.chipGroupThemePalettes.findViewById<Chip>(chipId)
                if (chipView != null) {
                    val isLocked = !hasPremiumThemes
                    chipView.text = if (isLocked) {
                        "🔒 ${pal.title} (PRO)"
                    } else {
                        "👑 ${pal.title}"
                    }
                }
            }

            val modeChipId = when (prefs.displayThemeMode) {
                AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM -> R.id.chip_theme_mode_system
                AppCompatDelegate.MODE_NIGHT_YES -> R.id.chip_theme_mode_dark
                else -> R.id.chip_theme_mode_light
            }
            if (binding.chipGroupThemeMode.checkedChipId != modeChipId) {
                binding.chipGroupThemeMode.check(modeChipId)
            }

            if (binding.switchDynamicColors.isChecked != prefs.useDynamicColors) {
                binding.switchDynamicColors.isChecked = prefs.useDynamicColors
            }

            val targetChipId = paletteChipIds[palette]
            if (targetChipId != null) {
                if (binding.chipGroupThemePalettes.checkedChipId != targetChipId) {
                    binding.chipGroupThemePalettes.check(targetChipId)
                }
            } else {
                binding.chipGroupThemePalettes.clearCheck()
            }
        } finally {
            isBindingThemeControls = false
        }
    }

    private fun bindCurrentValues(
        items: List<DynamicSettingItem> = RealtimeSyncManager.syncState.value.settingsItems,
        force: Boolean = false
    ) {
        if (_binding == null || !isAdded) return
        bindDefaultSimCard()
        bindThemeStudioCard()
        bindVoipCardState()
        bindVipCardsState()

        val visibleItems = items
            .filter { it.enabled }
            .sortedBy { it.order }

        if (visibleItems.isEmpty()) {
            lastBoundRowsSignature = "empty"
            binding.tvSettingsEmpty.visibility = View.VISIBLE
            binding.cardDynamicSettings.visibility = View.GONE
            settingsAdapter.submitList(emptyList())
            return
        }

        binding.tvSettingsEmpty.visibility = View.GONE
        binding.cardDynamicSettings.visibility = View.VISIBLE

        val vipEntitled = isVipEntitledSnapshot()
        val uiRows = visibleItems.mapIndexed { index, item ->
            val (desc, checked, interactive) = resolveItemState(item, vipEntitled)
            val frozenBySubscription = !vipEntitled && (
                item.key == DynamicSettingItem.KEY_AUTO_RECORDING ||
                    item.key == DynamicSettingItem.KEY_RECORDING_FOLDER
                )
            DynamicSettingUiRow(
                item = item,
                resolvedDescription = desc,
                isChecked = checked,
                isInteractive = interactive,
                isLastItem = index == visibleItems.lastIndex,
                isFrozenBySubscription = frozenBySubscription
            )
        }
        val rowsSig = uiRows.joinToString("|") {
            "${it.item.key}:${it.item.title}:${it.resolvedDescription}:${it.isChecked}:${it.isInteractive}:${it.isLastItem}:${it.isFrozenBySubscription}"
        }
        if (force || rowsSig != lastBoundRowsSignature) {
            lastBoundRowsSignature = rowsSig
            settingsAdapter.submitList(uiRows)
        }
    }

    private fun resolveItemState(
        item: DynamicSettingItem,
        vipEntitled: Boolean
    ): Triple<String, Boolean, Boolean> {
        val lockedText = getString(R.string.setting_locked_by_admin)
        return when (item.key) {
            DynamicSettingItem.KEY_AUTO_RECORDING -> {
                val recordingAllowed = prefs.allowCallRecordingConfig
                val interactive = vipEntitled && recordingAllowed && !item.lockedByAdmin
                val checked = vipEntitled && recordingAllowed &&
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
                Triple(desc, false, vipEntitled && !item.lockedByAdmin)
            }
            DynamicSettingItem.KEY_ACCESSIBILITY_STATUS -> {
                val isEnabled = cachedAccessibilityEnabled
                val desc = getString(
                    if (isEnabled) R.string.setting_accessibility_enabled
                    else R.string.setting_accessibility_disabled
                )
                Triple(desc, isEnabled, !item.lockedByAdmin)
            }
            DynamicSettingItem.KEY_BLOCKED_NUMBERS -> {
                val blockedCount = prefs.getBlockedNumbers().size
                val desc = getString(R.string.setting_blocked_desc_count, blockedCount)
                Triple(desc, false, !item.lockedByAdmin)
            }
            DynamicSettingItem.KEY_DISPLAY_THEME -> {
                val desc = ThemeStudioManager.buildThemeSummary(requireContext(), prefs)
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
        if (row.isFrozenBySubscription) {
            return false
        }
        if (item.lockedByAdmin) {
            Toast.makeText(requireContext(), R.string.setting_locked_by_admin, Toast.LENGTH_SHORT).show()
            return false
        }
        return when (item.key) {
            DynamicSettingItem.KEY_AUTO_RECORDING -> {
                if (isChecked && !prefs.allowCallRecordingConfig) {
                    Toast.makeText(requireContext(), R.string.setting_recording_disabled_admin, Toast.LENGTH_SHORT).show()
                    false
                } else if (!isVipEntitled() ||
                    !SubscriptionEnforcementService.canStartCallRecording(requireContext())
                ) {
                    false
                } else {
                    settingsManager.setAutoCallRecording(isChecked)
                    if (isChecked &&
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                        !DialerHelper.isDefaultDialer(requireContext())
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
        if (row.isFrozenBySubscription) {
            return
        }
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
            DynamicSettingItem.KEY_RECORDING_FOLDER -> {
                if (!isVipEntitled()) return
                launchFolderPickerForSource(null)
            }
            DynamicSettingItem.KEY_ACCESSIBILITY_STATUS -> {
                openDefaultDialerOrAudioSettings()
            }
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
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()

        val scrollView = android.widget.ScrollView(ctx)
        val container = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, (8 * density).toInt(), pad, pad)
        }
        scrollView.addView(container)

        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.setting_theme_studio_title)
            .setView(scrollView)
            .setPositiveButton(R.string.dialog_close, null)
            .create()

        // 1. Color Scheme Mode Section (System Default / Light Mode / Dark Mode)
        val modeHeader = TextView(ctx).apply {
            text = getString(R.string.setting_theme_title)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            textSize = 14f
            setPadding(0, 0, 0, (6 * density).toInt())
        }
        container.addView(modeHeader)

        val modeGroup = android.widget.RadioGroup(ctx).apply {
            orientation = android.widget.RadioGroup.VERTICAL
        }
        val rbSystem = android.widget.RadioButton(ctx).apply {
            id = View.generateViewId()
            text = getString(R.string.setting_theme_system_default)
        }
        val rbLight = android.widget.RadioButton(ctx).apply {
            id = View.generateViewId()
            text = getString(R.string.setting_theme_light)
        }
        val rbDark = android.widget.RadioButton(ctx).apply {
            id = View.generateViewId()
            text = getString(R.string.setting_theme_dark)
        }
        modeGroup.addView(rbSystem)
        modeGroup.addView(rbLight)
        modeGroup.addView(rbDark)

        when (prefs.displayThemeMode) {
            AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM -> modeGroup.check(rbSystem.id)
            AppCompatDelegate.MODE_NIGHT_YES -> modeGroup.check(rbDark.id)
            else -> modeGroup.check(rbLight.id)
        }

        modeGroup.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                rbSystem.id -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                rbDark.id -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_NO
            }
            runCatching { dialog.dismiss() }
            settingsManager.setThemeMode(mode)
            bindThemeStudioCard()
            bindCurrentValues(force = true)
        }
        container.addView(modeGroup)

        // 2. Dynamic Colors Toggle
        val dynamicSwitch = com.google.android.material.materialswitch.MaterialSwitch(ctx).apply {
            text = getString(R.string.setting_theme_dynamic_title)
            isChecked = prefs.useDynamicColors
            minHeight = (48 * density).toInt()
            setOnCheckedChangeListener { _, isChecked ->
                runCatching { dialog.dismiss() }
                settingsManager.setUseDynamicColors(isChecked)
                bindThemeStudioCard()
                bindCurrentValues(force = true)
            }
        }
        container.addView(dynamicSwitch)

        // 3. 3 Exclusive Full-App 3D Mix-Color Premium Themes (Only for Premium Subscribers)
        val hasPremiumThemes = ThemeStudioManager.isUserEntitledToPremiumThemes(prefs)
        val paletteHeader = TextView(ctx).apply {
            text = if (hasPremiumThemes) {
                "👑 3 Full-App 3D Mix-Color Themes (Unlocked)"
            } else {
                "🔒 3 Full-App 3D Mix-Color Themes (Pro Exclusive)"
            }
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            textSize = 14f
            setPadding(0, (12 * density).toInt(), 0, (8 * density).toInt())
        }
        container.addView(paletteHeader)

        val currentPalette = ThemeStudioManager.resolveEffectivePalette(prefs)
        AppThemePalette.premium3DMixThemes().forEach { palette ->
            val isSelected = palette == currentPalette
            val isLocked = !hasPremiumThemes
            val card = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                val cardPad = (14 * density).toInt()
                setPadding(cardPad, cardPad, cardPad, cardPad)
                background = palette.createFullApp3DBackgroundDrawable(
                    cornerRadiusPx = 16f * density
                )
                alpha = if (isLocked) 0.85f else 1f
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (10 * density).toInt()
                }
                isClickable = true
                isFocusable = true
            }

            val textCol = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    0,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            }
            val titleView = TextView(ctx).apply {
                text = when {
                    isSelected -> "✓ 👑 ${palette.title}"
                    isLocked -> "🔒 ${palette.title}"
                    else -> "👑 ${palette.title}"
                }
                setTextColor(palette.textPrimaryColorInt())
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                textSize = 15f
            }
            val subView = TextView(ctx).apply {
                text = if (isLocked) {
                    "${palette.subtitle} • Only Premium Users"
                } else {
                    "${palette.subtitle} • Full-App 3D Background + Text"
                }
                setTextColor(palette.textSecondaryColorInt())
                textSize = 12f
            }
            textCol.addView(titleView)
            textCol.addView(subView)

            val badgeView = TextView(ctx).apply {
                text = if (isLocked) "🔒 PRO ONLY" else palette.badgeText
                setTextColor(palette.textPrimaryColorInt())
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                textSize = 11f
                setPadding((8 * density).toInt(), (4 * density).toInt(), (8 * density).toInt(), (4 * density).toInt())
            }

            card.addView(textCol)
            card.addView(badgeView)
            card.setOnClickListener {
                runCatching { dialog.dismiss() }
                applySelectedThemePalette(palette)
            }
            container.addView(card)
        }

        if (hasPremiumThemes && currentPalette.is3DMix) {
            val resetBtn = com.google.android.material.button.MaterialButton(ctx).apply {
                text = "Reset to Standard Surface"
                setOnClickListener {
                    runCatching { dialog.dismiss() }
                    applySelectedThemePalette(AppThemePalette.EMERALD_PULSE)
                }
            }
            container.addView(resetBtn)
        }

        runCatching { activeSettingsDialog?.dismiss() }
        dialog.setOnDismissListener {
            if (activeSettingsDialog === dialog) {
                activeSettingsDialog = null
            }
        }
        activeSettingsDialog = dialog
        dialog.show()
    }

    private fun openDefaultDialerOrAudioSettings() {
        val ctx = requireContext()
        if (!DialerHelper.isDefaultDialer(ctx)) {
            runCatching {
                startActivity(
                    Intent(android.telecom.TelecomManager.ACTION_CHANGE_DEFAULT_DIALER).apply {
                        putExtra(
                            android.telecom.TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME,
                            ctx.packageName
                        )
                    }
                )
            }.onFailure {
                showAccessibilityEnablePrompt()
            }
        } else {
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", ctx.packageName, null)
                    }
                )
            }
        }
    }

    private fun showAccessibilityEnablePrompt() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.accessibility_prompt_title)
            .setMessage(
                getString(
                    R.string.accessibility_prompt_message,
                    getString(R.string.app_name)
                )
            )
            .setPositiveButton(R.string.accessibility_prompt_open) { _, _ ->
                openDefaultDialerOrAudioSettings()
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
        runCatching { activeSettingsDialog?.dismiss() }
        activeSettingsDialog = null
        lastBoundRowsSignature = null
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

    companion object {
        private const val STATE_PENDING_FOLDER_SOURCE = "state_pending_folder_source"
    }
}
