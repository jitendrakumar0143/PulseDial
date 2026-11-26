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
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import com.example.R
import com.example.data.firebase.CustomPopupAd
import com.example.data.firebase.RealtimeDatabaseSync
import com.example.databinding.DialogCustomPopupAdBinding
import com.example.ui.common.PopupImageHelper

/**
 * Rounded Material 3 Popup Ad Dialog with transparent background overlay, Sponsored tag,
 * live skip countdown badge turning into clickable "✕ Close", multi-size layouts
 * (`small`, `medium`, `large`, `fullscreen`), uploaded photo & remote URL loading,
 * and atomic RTDB `/custom_popup_ad` impressionsCount & clicksCount tracking.
 */
class CustomAdPopupDialog : DialogFragment() {

    private var _binding: DialogCustomPopupAdBinding? = null
    private val binding get() = _binding!!

    private val mainHandler = Handler(Looper.getMainLooper())
    private var remainingSeconds: Int = CustomPopupAd.DEFAULT_COUNTDOWN_SECONDS
    private var impressionRecorded: Boolean = false
    private var countdownRunning: Boolean = false

    private val isPreviewMode: Boolean by lazy {
        arguments?.getBoolean(ARG_IS_PREVIEW, false) ?: false
    }

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
        remainingSeconds = if (isPreviewMode) {
            0
        } else {
            savedInstanceState?.getInt(
                STATE_REMAINING_SECONDS,
                adCampaign.countdownSeconds
            ) ?: adCampaign.countdownSeconds.coerceIn(0, CustomPopupAd.MAX_COUNTDOWN_SECONDS)
        }
        impressionRecorded = savedInstanceState?.getBoolean(STATE_IMPRESSION_RECORDED, false) ?: false
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCanceledOnTouchOutside(isPreviewMode)
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

        val hasText = ad.title.isNotBlank() || ad.message.isNotBlank()
        applyPopupSizeLayout(ad.normalizedPopupSize(), hasText)

        val cleanImageUrl = ad.imageUrl.trim()
        if (cleanImageUrl.isNotBlank()) {
            binding.layoutAdPlaceholder.visibility = View.VISIBLE
            PopupImageHelper.loadPopupImage(
                imageView = binding.ivAdBanner,
                rawSource = cleanImageUrl,
                onSuccess = {
                    _binding?.layoutAdPlaceholder?.visibility = View.GONE
                },
                onError = {
                    _binding?.layoutAdPlaceholder?.visibility = View.VISIBLE
                }
            )
        } else {
            binding.layoutAdPlaceholder.visibility = View.VISIBLE
        }

        val clickHandler = View.OnClickListener { handleAdActionClick() }
        binding.btnAdCta.setOnClickListener(clickHandler)
        binding.ivAdBanner.setOnClickListener(clickHandler)

        binding.btnAdCloseBadge.setOnClickListener {
            if (isPreviewMode || remainingSeconds <= 0) {
                markCampaignSeen()
                dismissAllowingStateLoss()
            }
        }

        renderCountdownBadge(binding, remainingSeconds)
        startCountdownIfNeeded()

        if (!isPreviewMode && !impressionRecorded) {
            impressionRecorded = true
            RealtimeDatabaseSync.incrementCustomPopupAdImpression(requireContext())
        }

        // Smooth scale-in + fade-in entrance animation matching the dark smoked-sapphire theme
        binding.cardAdContainer.apply {
            alpha = 0f
            scaleX = 0.88f
            scaleY = 0.88f
            animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(280L)
                .setInterpolator(android.view.animation.OvershootInterpolator(1.08f))
                .start()
        }
    }

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

    private fun applyPopupSizeLayout(sizeMode: String, hasText: Boolean) {
        val screenH = resources.displayMetrics.heightPixels

        val rootPad = when (sizeMode) {
            CustomPopupAd.SIZE_SMALL -> dp(10f)
            CustomPopupAd.SIZE_LARGE -> dp(10f)
            CustomPopupAd.SIZE_FULLSCREEN -> dp(8f)
            else -> dp(14f)
        }
        binding.adRootFrame.setPadding(rootPad, rootPad, rootPad, rootPad)

        val textPad = when (sizeMode) {
            CustomPopupAd.SIZE_SMALL -> dp(14f)
            CustomPopupAd.SIZE_LARGE -> dp(18f)
            CustomPopupAd.SIZE_FULLSCREEN -> dp(16f)
            else -> dp(18f)
        }
        binding.layoutAdTextContainer.setPadding(textPad, textPad, textPad, textPad)

        val ctaParams = binding.btnAdCta.layoutParams as LinearLayout.LayoutParams
        ctaParams.topMargin = if (hasText) dp(14f) else dp(4f)
        binding.btnAdCta.layoutParams = ctaParams

        binding.cardAdContainer.radius = when (sizeMode) {
            CustomPopupAd.SIZE_SMALL -> dp(22f).toFloat()
            CustomPopupAd.SIZE_FULLSCREEN -> dp(22f).toFloat()
            else -> dp(28f).toFloat()
        }

        when (sizeMode) {
            CustomPopupAd.SIZE_SMALL -> {
                binding.tvAdTitle.textSize = 17f
                binding.tvAdMessage.textSize = 12.5f
            }
            CustomPopupAd.SIZE_LARGE -> {
                binding.tvAdTitle.textSize = 21f
                binding.tvAdMessage.textSize = 14.5f
            }
            CustomPopupAd.SIZE_FULLSCREEN -> {
                binding.tvAdTitle.textSize = 22f
                binding.tvAdMessage.textSize = 15f
            }
            else -> {
                binding.tvAdTitle.textSize = 20f
                binding.tvAdMessage.textSize = 14f
            }
        }

        val cardParams = binding.cardAdContainer.layoutParams
        val contentParams = binding.layoutAdCardContent.layoutParams
        val bannerParams = binding.frameAdBanner.layoutParams as LinearLayout.LayoutParams

        if (sizeMode == CustomPopupAd.SIZE_FULLSCREEN) {
            cardParams.height = ViewGroup.LayoutParams.MATCH_PARENT
            contentParams.height = ViewGroup.LayoutParams.MATCH_PARENT
            bannerParams.height = 0
            bannerParams.weight = 1f
            binding.ivAdBanner.scaleType = ImageView.ScaleType.FIT_CENTER
        } else {
            cardParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
            contentParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
            bannerParams.weight = 0f
            bannerParams.height = when (sizeMode) {
                CustomPopupAd.SIZE_SMALL -> if (hasText) dp(132f) else dp(176f)
                CustomPopupAd.SIZE_LARGE -> {
                    val cap = (screenH * if (hasText) 0.44f else 0.56f).toInt()
                    dp(if (hasText) 304f else 390f).coerceAtMost(cap.coerceAtLeast(dp(240f)))
                }
                else -> if (hasText) dp(204f) else dp(264f)
            }
            binding.ivAdBanner.scaleType = ImageView.ScaleType.CENTER_CROP
        }
        binding.cardAdContainer.layoutParams = cardParams
        binding.layoutAdCardContent.layoutParams = contentParams
        binding.frameAdBanner.layoutParams = bannerParams
    }

    override fun onStart() {
        super.onStart()
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        val sizeMode = adCampaign.normalizedPopupSize()

        val (targetWidth, targetHeight) = when (sizeMode) {
            CustomPopupAd.SIZE_SMALL -> Pair(
                (screenW * 0.80f).toInt().coerceAtMost(dp(320f)),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            CustomPopupAd.SIZE_LARGE -> Pair(
                (screenW * 0.96f).toInt().coerceAtMost(dp(480f)),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            CustomPopupAd.SIZE_FULLSCREEN -> Pair(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (screenH * 0.90f).toInt().coerceAtLeast(dp(480f))
            )
            else -> Pair(
                (screenW * 0.91f).toInt().coerceAtMost(dp(410f)),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        dialog?.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(targetWidth, targetHeight)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            val params = attributes
            params.dimAmount = if (sizeMode == CustomPopupAd.SIZE_FULLSCREEN) 0.82f else 0.68f
            attributes = params
        }
        dialog?.setCanceledOnTouchOutside(isPreviewMode)
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
        if (isPreviewMode || remainingSeconds <= 0 || countdownRunning) return
        countdownRunning = true
        mainHandler.removeCallbacks(countdownTickRunnable)
        mainHandler.postDelayed(countdownTickRunnable, COUNTDOWN_STEP_MS)
    }

    private fun renderCountdownBadge(viewBinding: DialogCustomPopupAdBinding?, secondsLeft: Int) {
        val canClose = isPreviewMode || secondsLeft <= 0
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

    private fun markCampaignSeen() {
        if (isPreviewMode) return
        val ctx = context ?: return
        val key = adCampaign.effectiveCampaignKey()
        if (key.isNotBlank()) {
            com.example.data.DialerPreferences(ctx).markPopupIdSeen(key)
        }
    }

    private fun handleAdActionClick() {
        val ctx = context ?: return
        if (!isPreviewMode) {
            RealtimeDatabaseSync.incrementCustomPopupAdClick(ctx)
            markCampaignSeen()
        }

        val normalizedUrl = CustomPopupAd.normalizeTargetUrl(adCampaign.targetUrl)
        if (normalizedUrl.isNotEmpty()) {
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(normalizedUrl)))
            }.onFailure {
                Toast.makeText(ctx, R.string.broadcast_invalid_link, Toast.LENGTH_SHORT).show()
            }
        }
        if (isPreviewMode || remainingSeconds <= 0) {
            dismissAllowingStateLoss()
        }
    }

    companion object {
        const val TAG = "CustomAdPopupDialog"
        private const val COUNTDOWN_STEP_MS = 1_000L
        private const val STATE_REMAINING_SECONDS = "state_remaining_seconds"
        private const val STATE_IMPRESSION_RECORDED = "state_impression_recorded"
        private const val ARG_IS_PREVIEW = "arg_is_preview"

        fun newInstance(ad: CustomPopupAd, isPreview: Boolean = false): CustomAdPopupDialog {
            return CustomAdPopupDialog().apply {
                arguments = ad.toBundle().apply {
                    putBoolean(ARG_IS_PREVIEW, isPreview)
                }
            }
        }

        fun showIfNotAlreadyVisible(
            fragmentManager: FragmentManager,
            ad: CustomPopupAd,
            isPreview: Boolean = false
        ): CustomAdPopupDialog? {
            if (fragmentManager.isStateSaved) return null
            val existing = fragmentManager.findFragmentByTag(TAG) as? CustomAdPopupDialog
            if (existing != null) {
                if (!isPreview) return null
                existing.dismissAllowingStateLoss()
            }
            return newInstance(ad, isPreview).also { dialog ->
                dialog.show(fragmentManager, TAG)
            }
        }
    }
}
