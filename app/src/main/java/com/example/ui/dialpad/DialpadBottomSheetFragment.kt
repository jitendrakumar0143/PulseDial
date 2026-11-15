package com.example.ui.dialpad

import android.content.ClipboardManager
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.provider.ContactsContract
import android.telephony.PhoneNumberUtils
import android.view.HapticFeedbackConstants
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
import com.example.databinding.BottomSheetDialpadBinding
import com.example.telecom.DialerHelper
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.launch
import java.util.Locale

class DialpadBottomSheetFragment : BottomSheetDialogFragment() {

    private var _binding: BottomSheetDialpadBinding? = null
    private val binding get() = _binding!!

    private lateinit var prefs: DialerPreferences
    private lateinit var repository: ContactsRepository
    private lateinit var suggestionAdapter: T9SuggestionAdapter

    private var toneGenerator: ToneGenerator? = null
    private var cachedContacts: List<ContactEntry> = emptyList()
    private var lastDialedNumber: String? = null
    private var rawInput: String = ""

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetDialpadBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()
        prefs = DialerPreferences(ctx)
        repository = ContactsRepository(ctx)

        syncToneGeneratorState()

        suggestionAdapter = T9SuggestionAdapter { entry ->
            rawInput = entry.number
            updateInputDisplay()
            DialerHelper.placeCall(ctx, entry.number)
            dismiss()
        }

        binding.recyclerT9Suggestions.layoutManager =
            LinearLayoutManager(ctx, LinearLayoutManager.HORIZONTAL, false)
        binding.recyclerT9Suggestions.adapter = suggestionAdapter

        rawInput = arguments?.getString(ARG_INITIAL_NUMBER).orEmpty()
        loadContactsAndLastCall()
        setupKeypadButtons()
        setupActionChips()
        updateInputDisplay()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                RealtimeSyncManager.syncState.collect {
                    syncToneGeneratorState()
                }
            }
        }
    }

    private fun syncToneGeneratorState() {
        if (prefs.dialpadTonesEnabled) {
            if (toneGenerator == null) {
                toneGenerator = runCatching {
                    ToneGenerator(AudioManager.STREAM_DTMF, 65)
                }.getOrNull()
            }
        } else {
            toneGenerator?.release()
            toneGenerator = null
        }
    }

    private fun loadContactsAndLastCall() {
        viewLifecycleOwner.lifecycleScope.launch {
            cachedContacts = repository.getContacts(onlyStarred = false)
            val recent = repository.getRecentCalls(limit = 1)
            lastDialedNumber = recent.firstOrNull()?.number
            if (_binding != null) {
                updateT9Suggestions()
                updateActionChips()
            }
        }
    }

    private fun setupKeypadButtons() {
        val keyMap = mapOf(
            binding.btnKey1 to ('1' to ToneGenerator.TONE_DTMF_1),
            binding.btnKey2 to ('2' to ToneGenerator.TONE_DTMF_2),
            binding.btnKey3 to ('3' to ToneGenerator.TONE_DTMF_3),
            binding.btnKey4 to ('4' to ToneGenerator.TONE_DTMF_4),
            binding.btnKey5 to ('5' to ToneGenerator.TONE_DTMF_5),
            binding.btnKey6 to ('6' to ToneGenerator.TONE_DTMF_6),
            binding.btnKey7 to ('7' to ToneGenerator.TONE_DTMF_7),
            binding.btnKey8 to ('8' to ToneGenerator.TONE_DTMF_8),
            binding.btnKey9 to ('9' to ToneGenerator.TONE_DTMF_9),
            binding.btnKeyStar to ('*' to ToneGenerator.TONE_DTMF_S),
            binding.btnKey0 to ('0' to ToneGenerator.TONE_DTMF_0),
            binding.btnKeyHash to ('#' to ToneGenerator.TONE_DTMF_P)
        )

        keyMap.forEach { (keyView, pair) ->
            keyView.setOnClickListener { view ->
                animateKeyPress(view)
                appendChar(pair.first, pair.second, view)
            }
        }

        // Long-press special telephony characters: + (0), pause , (*), wait ; (#)
        binding.btnKey0.setOnLongClickListener { view ->
            animateKeyPress(view)
            appendChar('+', ToneGenerator.TONE_DTMF_0, view)
            true
        }
        binding.btnKeyStar.setOnLongClickListener { view ->
            animateKeyPress(view)
            appendChar(',', ToneGenerator.TONE_DTMF_S, view)
            true
        }
        binding.btnKeyHash.setOnLongClickListener { view ->
            animateKeyPress(view)
            appendChar(';', ToneGenerator.TONE_DTMF_P, view)
            true
        }

        binding.btnDialpadBackspace.setOnClickListener {
            if (rawInput.isNotEmpty()) {
                rawInput = rawInput.dropLast(1)
                updateInputDisplay()
            }
        }

        binding.btnDialpadBackspace.setOnLongClickListener {
            rawInput = ""
            updateInputDisplay()
            true
        }

        binding.btnDialpadCall.setOnClickListener {
            val number = rawInput.trim()
            if (number.isNotEmpty()) {
                DialerHelper.placeCall(requireContext(), number)
                dismiss()
            } else {
                val redial = lastDialedNumber
                if (!redial.isNullOrBlank()) {
                    rawInput = redial
                    updateInputDisplay()
                } else {
                    Toast.makeText(requireContext(), R.string.dialpad_redial_empty, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun setupActionChips() {
        binding.chipPasteNumber.setOnClickListener {
            val pasted = readClipboardPhoneNumber()
            if (!pasted.isNullOrBlank()) {
                rawInput = pasted
                updateInputDisplay()
            }
        }

        binding.chipAddContact.setOnClickListener {
            val number = rawInput.trim()
            if (number.isNotEmpty()) {
                com.example.ui.common.SaveContactHelper.showSaveNumberDialog(
                    context = requireContext(),
                    lifecycleOwner = viewLifecycleOwner,
                    initialNumber = number,
                    onSaved = { loadContactsAndLastCall() }
                )
            }
        }
    }

    private fun appendChar(ch: Char, tone: Int, view: View) {
        rawInput += ch
        updateInputDisplay()
        if (prefs.dialpadTonesEnabled) {
            toneGenerator?.startTone(tone, 110)
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }

    private fun animateKeyPress(view: View) {
        view.animate().cancel()
        view.scaleX = 0.94f
        view.scaleY = 0.94f
        view.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(110L)
            .start()
    }

    private fun updateInputDisplay() {
        val formatted = if (rawInput.contains("*") || rawInput.contains("#") || rawInput.contains(",") || rawInput.contains(";")) {
            rawInput
        } else {
            PhoneNumberUtils.formatNumber(rawInput, Locale.getDefault().country) ?: rawInput
        }
        binding.etDialpadInput.setText(formatted)
        updateT9Suggestions()
        updateActionChips()
    }

    private fun updateT9Suggestions() {
        val matches = T9Matcher.matchContacts(rawInput, cachedContacts)
        suggestionAdapter.submitList(matches)
        binding.recyclerT9Suggestions.visibility = if (matches.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun updateActionChips() {
        val clipboardNumber = readClipboardPhoneNumber()
        val showPaste = rawInput.isEmpty() && !clipboardNumber.isNullOrBlank()
        val showSaveContact = rawInput.filter { it.isDigit() }.length >= 3

        binding.chipPasteNumber.visibility = if (showPaste) View.VISIBLE else View.GONE
        binding.chipAddContact.visibility = if (showSaveContact) View.VISIBLE else View.GONE
        binding.layoutDialpadChips.visibility =
            if (showPaste || showSaveContact) View.VISIBLE else View.GONE
    }

    private fun readClipboardPhoneNumber(): String? {
        val clipboard = requireContext().getSystemService(ClipboardManager::class.java) ?: return null
        val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(requireContext())?.toString()?.trim()
            ?: return null
        val cleaned = text.filter { it.isDigit() || it == '+' }
        return if (cleaned.count { it.isDigit() } >= 3) cleaned else null
    }

    override fun onDestroyView() {
        super.onDestroyView()
        toneGenerator?.release()
        toneGenerator = null
        _binding = null
    }

    companion object {
        const val TAG = "DialpadBottomSheetFragment"
        private const val ARG_INITIAL_NUMBER = "initial_number"

        fun newInstance(initialNumber: String = ""): DialpadBottomSheetFragment {
            return DialpadBottomSheetFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_INITIAL_NUMBER, initialNumber)
                }
            }
        }
    }
}
