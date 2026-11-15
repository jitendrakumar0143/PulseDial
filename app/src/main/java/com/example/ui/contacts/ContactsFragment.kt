package com.example.ui.contacts

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.data.ContactEntry
import com.example.data.ContactsRepository
import com.example.databinding.FragmentContactsBinding
import com.example.telecom.DialerHelper
import kotlinx.coroutines.launch

class ContactsFragment : Fragment() {

    private var _binding: FragmentContactsBinding? = null
    private val binding get() = _binding!!

    private lateinit var repository: ContactsRepository
    private lateinit var adapter: ContactsAdapter
    private var allContacts: List<ContactEntry> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentContactsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        repository = ContactsRepository(requireContext())

        adapter = ContactsAdapter(
            onRowClick = { contact ->
                com.example.ui.common.NumberDetailBottomSheetFragment
                    .newInstance(contact.number, contact.name)
                    .apply { onDataChanged = { reloadData() } }
                    .show(parentFragmentManager, com.example.ui.common.NumberDetailBottomSheetFragment.TAG)
            },
            onCallClick = { contact ->
                DialerHelper.placeCall(requireContext(), contact.number)
            },
            onStarClick = { contact ->
                viewLifecycleOwner.lifecycleScope.launch {
                    repository.setContactStarred(contact.contactId, !contact.isStarred)
                    reloadData()
                }
            }
        )

        binding.recyclerContacts.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerContacts.adapter = adapter

        binding.btnAddContact.setOnClickListener {
            com.example.ui.common.SaveContactHelper.showSaveNumberDialog(
                context = requireContext(),
                lifecycleOwner = viewLifecycleOwner,
                onSaved = { reloadData() }
            )
        }

        binding.etContactSearch.doAfterTextChanged { text ->
            filterContacts(text?.toString().orEmpty())
        }

        reloadData()
    }

    override fun onResume() {
        super.onResume()
        reloadData()
    }

    fun reloadData() {
        if (_binding == null) return
        viewLifecycleOwner.lifecycleScope.launch {
            allContacts = repository.getContacts(onlyStarred = false)
            if (_binding == null) return@launch
            filterContacts(binding.etContactSearch.text?.toString().orEmpty())
        }
    }

    private fun filterContacts(query: String) {
        val trimmed = query.trim().lowercase()
        val filtered = if (trimmed.isEmpty()) {
            allContacts
        } else {
            allContacts.filter {
                it.name.lowercase().contains(trimmed) || it.number.lowercase().contains(trimmed)
            }
        }
        adapter.submitList(filtered)
        binding.layoutContactsEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
