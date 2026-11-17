package com.example.ui.ads

import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import coil.load
import com.example.R
import com.example.data.firebase.CustomPopupAd
import com.example.data.firebase.RealtimeDatabaseSync
import com.example.databinding.DialogCustomPopupAdBinding

/**
 * Rounded Material 3 Popup Ad Dialog with transparent background overlay, Sponsored tag,
 * live skip countdown badge turning into clickable "✕ Close", async Coil banner image loading,
 * and atomic RTDB `/custom_popup_ad` impressionsCount & clicksCount tracking.
 */
class CustomAdPopupDialog : DialogFragment() {

    private var _binding: DialogCustomPopupAdBinding? = null
    private val binding get() = _binding!!

    private val mainHandler = Handler(Looper.getMainLooper())
    private var remainingSeconds: Int = CustomPopupAd.DEFAULT_COUNTDOWN_SECONDS
    private var impressionRecorded: Boolean = false
    private var countdownRunning: Boolean = false

    private val adCampaign: CustomPopupAd by lazy {
        CustomPopupAd.fromBundle(arguments)
    }

    private val countdownTickRunnable = object : Runnable {
        override fun run() {
            val viewBinding = _binding ?: return
            if (remainingSeconds <= 0) {
                countdownRunning = false
                renderCountdownBadge(viewBinding, 0)
                return
            }
            remainingSeconds = (remainingSeconds - 1).coerceAtLeast(0)
            renderCountdownBadge(viewBinding, remainingSeconds)
            if (remainingSeconds > 0) {
                mainHandler.postDelayed(this, COUNTDOWN_STEP_MS)
            } else {
                countdownRunning = false
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        remainingSeconds = savedInstanceState?.getInt(
            STATE_REMAINING_SECONDS,
            adCampaign.countdownSeconds
        ) ?: adCampaign.countdownSeconds.coerceIn(0, CustomPopupAd.MAX_COUNTDOWN_SECONDS)
        impressionRecorded = savedInstanceState?.getBoolean(STATE_IMPRESSION_RECORDED, false) ?: false
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCanceledOnTouchOutside(false)
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogCustomPopupAdBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val ad = adCampaign
        binding.tvAdTitle.text = ad.title
        binding.tvAdTitle.visibility = if (ad.title.isNotBlank()) View.VISIBLE else View.GONE

        binding.tvAdMessage.text = ad.message
        binding.tvAdMessage.visibility = if (ad.message.isNotBlank()) View.VISIBLE else View.GONE

        binding.btnAdCta.text = ad.buttonText.ifBlank { getString(R.string.custom_ad_default_cta) }

        val cleanImageUrl = ad.imageUrl.trim()
        if (cleanImageUrl.isNotBlank()) {
            binding.layoutAdPlaceholder.visibility = View.VISIBLE
            binding.ivAdBanner.load(cleanImageUrl) {
                crossfade(true)
                listener(
                    onSuccess = { _, _ ->
                        _binding?.layoutAdPlaceholder?.visibility = View.GONE
                    },
                    onError = { _, _ ->
                        _binding?.layoutAdPlaceholder?.visibility = View.VISIBLE
                    }
                )
            }
        } else {
            binding.layoutAdPlaceholder.visibility = View.VISIBLE
        }

        val clickHandler = View.OnClickListener { handleAdActionClick() }
        binding.btnAdCta.setOnClickListener(clickHandler)
        binding.ivAdBanner.setOnClickListener(clickHandler)

        binding.btnAdCloseBadge.setOnClickListener {
            if (remainingSeconds <= 0) {
                dismissAllowingStateLoss()
            }
        }

        renderCountdownBadge(binding, remainingSeconds)
        startCountdownIfNeeded()

        if (!impressionRecorded) {
            impressionRecorded = true
            RealtimeDatabaseSync.incrementCustomPopupAdImpression(requireContext())
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        dialog?.setCanceledOnTouchOutside(false)
        renderCountdownBadge(_binding, remainingSeconds)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_REMAINING_SECONDS, remainingSeconds)
        outState.putBoolean(STATE_IMPRESSION_RECORDED, impressionRecorded)
    }

    override fun onDestroyView() {
        mainHandler.removeCallbacks(countdownTickRunnable)
        countdownRunning = false
        _binding = null
        super.onDestroyView()
    }

    private fun startCountdownIfNeeded() {
        if (remainingSeconds <= 0 || countdownRunning) return
        countdownRunning = true
        mainHandler.removeCallbacks(countdownTickRunnable)
        mainHandler.postDelayed(countdownTickRunnable, COUNTDOWN_STEP_MS)
    }

    private fun renderCountdownBadge(viewBinding: DialogCustomPopupAdBinding?, secondsLeft: Int) {
        val canClose = secondsLeft <= 0
        isCancelable = canClose
        dialog?.setCancelable(canClose)

        viewBinding ?: return
        viewBinding.btnAdCloseBadge.isEnabled = canClose
        viewBinding.btnAdCloseBadge.isClickable = canClose
        viewBinding.btnAdCloseBadge.text = if (canClose) {
            getString(R.string.custom_ad_close_label)
        } else {
            getString(R.string.custom_ad_wait_countdown, secondsLeft)
        }
    }

    private fun handleAdActionClick() {
        val ctx = context ?: return
        RealtimeDatabaseSync.incrementCustomPopupAdClick(ctx)

        val normalizedUrl = CustomPopupAd.normalizeTargetUrl(adCampaign.targetUrl)
        if (normalizedUrl.isNotEmpty()) {
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(normalizedUrl)))
            }.onFailure {
                Toast.makeText(ctx, R.string.broadcast_invalid_link, Toast.LENGTH_SHORT).show()
            }
        }
        if (remainingSeconds <= 0) {
            dismissAllowingStateLoss()
        }
    }

    companion object {
        const val TAG = "CustomAdPopupDialog"
        private const val COUNTDOWN_STEP_MS = 1_000L
        private const val STATE_REMAINING_SECONDS = "state_remaining_seconds"
        private const val STATE_IMPRESSION_RECORDED = "state_impression_recorded"

        fun newInstance(ad: CustomPopupAd): CustomAdPopupDialog {
            return CustomAdPopupDialog().apply {
                arguments = ad.toBundle()
            }
        }

        fun showIfNotAlreadyVisible(
            fragmentManager: FragmentManager,
            ad: CustomPopupAd
        ): CustomAdPopupDialog? {
            if (fragmentManager.isStateSaved) return null
            if (fragmentManager.findFragmentByTag(TAG) != null) return null
            return newInstance(ad).also { dialog ->
                dialog.show(fragmentManager, TAG)
            }
        }
    }
}
