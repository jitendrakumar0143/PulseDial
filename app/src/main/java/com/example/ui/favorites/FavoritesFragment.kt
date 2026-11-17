package com.example.ui.favorites

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.example.data.ContactsRepository
import com.example.databinding.FragmentFavoritesBinding
import com.example.telecom.DialerHelper
import kotlinx.coroutines.launch

class FavoritesFragment : Fragment() {

    private var _binding: FragmentFavoritesBinding? = null
    private val binding get() = _binding!!

    private lateinit var repository: ContactsRepository
    private lateinit var adapter: FavoritesAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFavoritesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        repository = ContactsRepository(requireContext())

        adapter = FavoritesAdapter(
            onCardClick = { contact ->
                com.example.ui.common.NumberDetailBottomSheetFragment
                    .newInstance(contact.number, contact.name)
                    .apply { onDataChanged = { reloadData() } }
                    .show(parentFragmentManager, com.example.ui.common.NumberDetailBottomSheetFragment.TAG)
            },
            onCallClick = { contact ->
                DialerHelper.initiateCall(activity ?: requireContext(), contact.number)
            },
            onToggleStarClick = { contact ->
                viewLifecycleOwner.lifecycleScope.launch {
                    repository.setContactStarred(contact.contactId, false)
                    reloadData()
                }
            }
        )

        val spanCount = if (resources.configuration.screenWidthDp >= 600) 3 else 2
        binding.recyclerFavorites.layoutManager = GridLayoutManager(requireContext(), spanCount)
        binding.recyclerFavorites.adapter = adapter

        reloadData()
    }

    override fun onResume() {
        super.onResume()
        reloadData()
    }

    fun reloadData() {
        if (_binding == null) return
        viewLifecycleOwner.lifecycleScope.launch {
            val favorites = repository.getContacts(onlyStarred = true)
            if (_binding == null) return@launch
            adapter.submitList(favorites)
            binding.layoutFavoritesEmpty.visibility = if (favorites.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
