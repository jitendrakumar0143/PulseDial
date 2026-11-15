package com.example.ui.custom

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import com.example.R
import com.example.databinding.FragmentCustomAnnouncementBinding

class CustomAnnouncementFragment : Fragment() {

    private var _binding: FragmentCustomAnnouncementBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCustomAnnouncementBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val title = arguments?.getString(ARG_TITLE).orEmpty()
        val body = arguments?.getString(ARG_BODY).orEmpty()
        val actionUrl = arguments?.getString(ARG_ACTION_URL).orEmpty()
        bindContent(title, body, actionUrl)
    }

    fun updateContent(title: String, body: String, actionUrl: String) {
        arguments = bundleOf(
            ARG_TITLE to title,
            ARG_BODY to body,
            ARG_ACTION_URL to actionUrl
        )
        if (_binding != null) {
            bindContent(title, body, actionUrl)
        }
    }

    private fun bindContent(title: String, body: String, actionUrl: String) {
        binding.tvCustomTabTitle.text = title.ifBlank { getString(R.string.custom_tab_default_title) }
        binding.tvCustomTabBody.text = body.ifBlank { getString(R.string.custom_tab_default_body) }
        val cleanUrl = actionUrl.trim()
        if (cleanUrl.isNotBlank()) {
            binding.btnCustomTabAction.visibility = View.VISIBLE
            binding.btnCustomTabAction.setOnClickListener {
                openSafeExternalLink(cleanUrl)
            }
        } else {
            binding.btnCustomTabAction.visibility = View.GONE
            binding.btnCustomTabAction.setOnClickListener(null)
        }
    }

    private fun openSafeExternalLink(url: String) {
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
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_TITLE = "arg_title"
        private const val ARG_BODY = "arg_body"
        private const val ARG_ACTION_URL = "arg_action_url"

        fun newInstance(title: String, body: String, actionUrl: String): CustomAnnouncementFragment {
            return CustomAnnouncementFragment().apply {
                arguments = bundleOf(
                    ARG_TITLE to title,
                    ARG_BODY to body,
                    ARG_ACTION_URL to actionUrl
                )
            }
        }
    }
}
