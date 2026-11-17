package com.example.ui.common

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.R
import com.example.data.ContactEntry
import com.example.data.ContactsRepository
import com.example.data.DialerPreferences
import com.example.data.firebase.RealtimeSyncManager
import com.example.data.firebase.SettingsManager
import com.example.databinding.BottomSheetNumberDetailBinding
import com.example.telecom.DialerHelper
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.launch

class NumberDetailBottomSheetFragment : BottomSheetDialogFragment() {

    private var _binding: BottomSheetNumberDetailBinding? = null
    private val binding get() = _binding!!

    private lateinit var repository: ContactsRepository
    private lateinit var prefs: DialerPreferences
    private lateinit var settingsManager: SettingsManager
    private lateinit var historyAdapter: NumberHistoryAdapter

    private var phoneNumber: String = ""
    private var initialName: String? = null
    private var matchedContact: ContactEntry? = null
    var onDataChanged: (() -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetNumberDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()
        repository = ContactsRepository(ctx)
        prefs = DialerPreferences(ctx)
        settingsManager = SettingsManager(ctx)

        phoneNumber = arguments?.getString(ARG_NUMBER).orEmpty()
        initialName = arguments?.getString(ARG_NAME)

        historyAdapter = NumberHistoryAdapter()
        binding.recyclerNumberHistory.layoutManager = LinearLayoutManager(ctx)
        binding.recyclerNumberHistory.adapter = historyAdapter

        setupActions()
        loadDetailsAndHistory()
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                RealtimeSyncManager.syncState.collect {
                    if (_binding != null) {
                        renderBlockedState()
                    }
                }
            }
        }
    }

    private fun setupActions() {
        binding.actionDetailCall.setOnClickListener {
            DialerHelper.initiateCall(activity ?: requireContext(), phoneNumber)
            dismiss()
        }

        binding.actionDetailMessage.setOnClickListener {
            val smsIntent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$phoneNumber"))
            runCatching { startActivity(smsIntent) }
        }

        binding.actionDetailSave.setOnClickListener {
            SaveContactHelper.showSaveNumberDialog(
                context = requireContext(),
                lifecycleOwner = viewLifecycleOwner,
                initialNumber = phoneNumber,
                initialName = matchedContact?.name ?: initialName.orEmpty(),
                onSaved = {
                    loadDetailsAndHistory()
                    onDataChanged?.invoke()
                }
            )
        }

        binding.actionDetailCopy.setOnClickListener {
            val clipboard = requireContext().getSystemService(ClipboardManager::class.java)
            clipboard?.setPrimaryClip(ClipData.newPlainText("Phone Number", phoneNumber))
            Toast.makeText(requireContext(), R.string.detail_copied, Toast.LENGTH_SHORT).show()
        }

        binding.actionDetailBlock.setOnClickListener {
            if (prefs.isNumberBlocked(phoneNumber)) {
                settingsManager.removeBlockedNumber(phoneNumber)
                Toast.makeText(
                    requireContext(),
                    getString(R.string.detail_unblocked_confirmation, phoneNumber),
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                settingsManager.addBlockedNumber(phoneNumber)
                Toast.makeText(
                    requireContext(),
                    getString(R.string.blocked_confirmation, phoneNumber),
                    Toast.LENGTH_SHORT
                ).show()
            }
            renderBlockedState()
            onDataChanged?.invoke()
        }

        binding.btnDetailStar.setOnClickListener {
            val contact = matchedContact ?: return@setOnClickListener
            viewLifecycleOwner.lifecycleScope.launch {
                repository.setContactStarred(contact.contactId, !contact.isStarred)
                loadDetailsAndHistory()
                onDataChanged?.invoke()
            }
        }
    }

    private fun loadDetailsAndHistory() {
        viewLifecycleOwner.lifecycleScope.launch {
            matchedContact = repository.lookupContactByNumber(phoneNumber)
            val history = repository.getCallHistoryForNumber(phoneNumber)
            if (_binding == null) return@launch

            val displayName = matchedContact?.name
                ?: initialName?.takeIf { it.isNotBlank() }
                ?: getString(R.string.detail_unsaved_number)

            binding.tvDetailName.text = displayName
            binding.tvDetailNumber.text = phoneNumber
            binding.tvDetailAvatar.text = displayName.firstOrNull()?.uppercaseChar()?.toString() ?: "#"

            // Hide Save button if already saved in contacts
            val isSaved = matchedContact != null
            binding.actionDetailSave.visibility = if (isSaved) View.GONE else View.VISIBLE
            binding.btnDetailStar.visibility = if (isSaved) View.VISIBLE else View.GONE
            if (matchedContact != null) {
                binding.btnDetailStar.setImageResource(
                    if (matchedContact!!.isStarred) R.drawable.ic_star else R.drawable.ic_star_outline
                )
            }

            renderBlockedState()

            historyAdapter.submitList(history)
            binding.tvDetailHistoryEmpty.visibility = if (history.isEmpty()) View.VISIBLE else View.GONE
            binding.recyclerNumberHistory.visibility = if (history.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun renderBlockedState() {
        val blocked = prefs.isNumberBlocked(phoneNumber)
        binding.chipDetailBlocked.visibility = if (blocked) View.VISIBLE else View.GONE
        binding.tvDetailBlockLabel.text = getString(
            if (blocked) R.string.detail_action_unblock else R.string.detail_action_block
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "NumberDetailBottomSheet"
        private const val ARG_NUMBER = "arg_number"
        private const val ARG_NAME = "arg_name"

        fun newInstance(number: String, name: String? = null): NumberDetailBottomSheetFragment {
            return NumberDetailBottomSheetFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_NUMBER, number)
                    putString(ARG_NAME, name)
                }
            }
        }
    }
}
