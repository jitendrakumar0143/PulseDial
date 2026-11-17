package com.example.ui.incall

import android.os.Bundle
import android.telecom.Call
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.R
import com.example.databinding.BottomSheetConferenceParticipantsBinding
import com.example.databinding.ItemConferenceParticipantBinding
import com.example.telecom.CallManager
import com.example.telecom.ConferenceParticipantUi
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.launch

/**
 * Material 3 Modal Bottom Sheet displaying active Conference Call participants (`call.children`),
 * allowing the user to Split a participant into a private 1:1 call (`splitFromConference`) or
 * Drop/Disconnect a specific participant (`disconnect`) without ending the main conference call.
 */
class ConferenceParticipantsSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetConferenceParticipantsBinding? = null
    private val binding get() = _binding!!

    private lateinit var participantAdapter: ParticipantAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetConferenceParticipantsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        participantAdapter = ParticipantAdapter(
            onSplitClicked = { participant ->
                if (participant.canSplit && CallManager.splitParticipant(participant.callKey)) {
                    dismissAllowingStateLoss()
                } else {
                    Toast.makeText(
                        requireContext(),
                        R.string.incall_split_unsupported,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            },
            onDropClicked = { participant ->
                CallManager.dropParticipant(participant.callKey)
            }
        )

        binding.recyclerConferenceParticipants.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerConferenceParticipants.adapter = participantAdapter

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                CallManager.uiState.collect { state ->
                    if (!state.hasActiveCall || (!state.isConference && state.participants.isEmpty())) {
                        dismissAllowingStateLoss()
                        return@collect
                    }

                    val count = state.participants.size
                    binding.tvConferenceSheetTitle.text = if (count > 0) {
                        getString(R.string.incall_conference_title, count)
                    } else {
                        getString(R.string.incall_conference_sheet_title)
                    }

                    participantAdapter.submitList(state.participants)
                    val isEmpty = state.participants.isEmpty()
                    binding.recyclerConferenceParticipants.visibility =
                        if (isEmpty) View.GONE else View.VISIBLE
                    binding.tvConferenceEmpty.visibility =
                        if (isEmpty) View.VISIBLE else View.GONE
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private class ParticipantAdapter(
        private val onSplitClicked: (ConferenceParticipantUi) -> Unit,
        private val onDropClicked: (ConferenceParticipantUi) -> Unit
    ) : ListAdapter<ConferenceParticipantUi, ParticipantAdapter.ParticipantViewHolder>(DiffCallback) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ParticipantViewHolder {
            val binding = ItemConferenceParticipantBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return ParticipantViewHolder(binding, onSplitClicked, onDropClicked)
        }

        override fun onBindViewHolder(holder: ParticipantViewHolder, position: Int) {
            holder.bind(getItem(position))
        }

        class ParticipantViewHolder(
            private val binding: ItemConferenceParticipantBinding,
            private val onSplitClicked: (ConferenceParticipantUi) -> Unit,
            private val onDropClicked: (ConferenceParticipantUi) -> Unit
        ) : RecyclerView.ViewHolder(binding.root) {

            fun bind(item: ConferenceParticipantUi) {
                val context = binding.root.context
                val name = item.displayName.ifBlank {
                    item.phoneNumber.ifBlank { context.getString(R.string.incall_unknown_caller) }
                }
                binding.tvParticipantName.text = name
                val stateLabel = when (item.callState) {
                    Call.STATE_HOLDING -> context.getString(R.string.incall_status_holding)
                    Call.STATE_DISCONNECTING -> context.getString(R.string.incall_status_disconnecting)
                    else -> null
                }
                binding.tvParticipantNumber.text = when {
                    item.phoneNumber.isNotBlank() && stateLabel != null ->
                        "${item.phoneNumber} • $stateLabel"
                    item.phoneNumber.isNotBlank() -> item.phoneNumber
                    stateLabel != null -> stateLabel
                    else -> context.getString(R.string.incall_unknown_caller)
                }
                binding.tvParticipantAvatar.text =
                    name.firstOrNull()?.uppercaseChar()?.toString() ?: "?"

                binding.btnSplit.isEnabled = item.canSplit
                binding.btnSplit.alpha = if (item.canSplit) 1f else 0.45f
                binding.btnSplit.setOnClickListener { onSplitClicked(item) }

                binding.btnDrop.isEnabled = item.canDrop
                binding.btnDrop.setOnClickListener { onDropClicked(item) }
            }
        }

        private object DiffCallback : DiffUtil.ItemCallback<ConferenceParticipantUi>() {
            override fun areItemsTheSame(
                oldItem: ConferenceParticipantUi,
                newItem: ConferenceParticipantUi
            ): Boolean = oldItem.callKey == newItem.callKey

            override fun areContentsTheSame(
                oldItem: ConferenceParticipantUi,
                newItem: ConferenceParticipantUi
            ): Boolean = oldItem == newItem
        }
    }

    companion object {
        const val TAG = "ConferenceParticipantsSheet"

        fun newInstance(): ConferenceParticipantsSheet = ConferenceParticipantsSheet()
    }
}
