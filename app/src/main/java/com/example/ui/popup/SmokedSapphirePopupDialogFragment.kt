package com.example.ui.popup

import android.app.Dialog
import android.content.DialogInterface
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import coil.load
import com.example.R
import com.example.data.DialerPreferences
import com.example.data.firebase.LiveBroadcast
import com.example.databinding.DialogLiveBroadcastBinding

/**
 * Reusable Dark Smoked-Sapphire Glassmorphism DialogFragment for Admin Broadcast Popups
 * (App Update Popups with optional Force Update lock & Custom Ad Popups).
 *
 * Visual & behavioral highlights:
 * - Smoked-sapphire 3D glass card (`bg_sapphire_glass_popup`) with glowing cyan-platinum rim
 *   and top specular highlight.
 * - Real native Android 12+ (`API 31+`) window backdrop blur (`FLAG_BLUR_BEHIND` +
 *   `blurBehindRadius` + `setBackgroundBlurRadius`) paired with a deep smoked glass scrim
 *   for consistent premium contrast across Android 10–16+.
 * - Dynamic payload injection for Update vs. Ad popups (badges, version pill, optional hero
 *   image via Coil, Force Update close-lock, CTA tint).
 * - Strict One-Time Delivery: persists `popupId` to [DialerPreferences.markPopupIdSeen] on
 *   dismiss, cancel, or CTA click when not in Admin preview mode and not a Force Update.
 */
class SmokedSapphirePopupDialogFragment : DialogFragment() {

    private var _binding: DialogLiveBroadcastBinding? = null
    private val binding get() = _binding!!

    private var hasRecordedSeen = false

    private val isPreviewMode: Boolean by lazy {
        arguments?.getBoolean(ARG_IS_PREVIEW, false) ?: false
    }

    private val payload: LiveBroadcast by lazy {
        broadcastFromArgs(arguments)
    }

    val currentPopupId: String
        get() = payload.effectivePopupId()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val canDismiss = isPreviewMode || !payload.isForceUpdateActive()
        isCancelable = canDismiss
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val canDismiss = isPreviewMode || !payload.isForceUpdateActive()
        dialog.setCancelable(canDismiss)
        dialog.setCanceledOnTouchOutside(canDismiss)
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogLiveBroadcastBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindBroadcastPayload(payload)
        playEntranceAnimation()
    }

    override fun onStart() {
        super.onStart()
        configureGlassmorphicWindow()
    }

    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)
        markSeenIfEligible()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun configureGlassmorphicWindow() {
        val window = dialog?.window ?: return
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val maxWidthPx = (resources.displayMetrics.widthPixels * 0.92f).toInt()
            .coerceAtMost((440f * resources.displayMetrics.density).toInt())
        window.setLayout(maxWidthPx, ViewGroup.LayoutParams.WRAP_CONTENT)

        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        val params = window.attributes
        params.dimAmount = 0.68f

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            params.blurBehindRadius = BACKDROP_BLUR_RADIUS_PX
            runCatching {
                window.setBackgroundBlurRadius(BACKDROP_BLUR_RADIUS_PX)
            }
        }
        window.attributes = params

        val canDismiss = isPreviewMode || !payload.isForceUpdateActive()
        dialog?.setCancelable(canDismiss)
        dialog?.setCanceledOnTouchOutside(canDismiss)
    }

    private fun bindBroadcastPayload(broadcast: LiveBroadcast) {
        val isUpdate = broadcast.isUpdatePopup()
        val isForceUpdate = broadcast.isForceUpdateActive()
        val canDismiss = isPreviewMode || !isForceUpdate
        val hasImage = broadcast.imageUrl.isNotBlank()
        val hasAction = broadcast.actionUrl.isNotBlank()

        // 1. Dynamic Type Badge + Optional Version Pill
        binding.tvPopupTypeBadge.text = getString(
            if (isUpdate) R.string.popup_badge_app_update else R.string.popup_badge_custom_ad
        )
        binding.tvPopupTypeBadge.setBackgroundResource(
            if (isUpdate) R.drawable.bg_sapphire_badge_update else R.drawable.bg_sapphire_badge_ad
        )
        binding.tvPopupTypeBadge.setTextColor(
            Color.parseColor(if (isUpdate) "#6EE7B7" else "#FDE68A")
        )

        if (isUpdate && broadcast.version.isNotBlank()) {
            binding.tvPopupVersionPill.visibility = View.VISIBLE
            binding.tvPopupVersionPill.text = broadcast.version.trim()
        } else {
            binding.tvPopupVersionPill.visibility = View.GONE
        }

        // 2. Dynamic Title, Message & Force-Update Notice
        val cleanTitle = broadcast.title.trim()
        if (cleanTitle.isEmpty() && hasImage) {
            binding.tvPopupTitle.visibility = View.GONE
        } else {
            binding.tvPopupTitle.visibility = View.VISIBLE
            binding.tvPopupTitle.text = cleanTitle.ifEmpty {
                getString(
                    if (isUpdate) R.string.popup_badge_app_update
                    else R.string.custom_tab_default_title
                )
            }
        }

        val cleanMessage = broadcast.message.trim()
        if (cleanMessage.isEmpty()) {
            binding.tvPopupMessage.visibility = View.GONE
        } else {
            binding.tvPopupMessage.visibility = View.VISIBLE
            binding.tvPopupMessage.text = cleanMessage
        }

        binding.tvPopupForceNotice.visibility = if (isForceUpdate) View.VISIBLE else View.GONE

        // 3. Optional Hero Image (Ad Banner or Update Visual)
        if (hasImage) {
            binding.cardPopupImageWrapper.visibility = View.VISIBLE
            binding.ivPopupImage.load(broadcast.imageUrl.trim()) {
                crossfade(true)
            }
            binding.tvPopupImageLinkBadge.visibility = if (hasAction) View.VISIBLE else View.GONE
        } else {
            binding.cardPopupImageWrapper.visibility = View.GONE
            binding.tvPopupImageLinkBadge.visibility = View.GONE
        }

        // 4. Primary CTA Button & Dismiss Controls
        binding.btnPopupAction.visibility = if (hasAction || isForceUpdate) View.VISIBLE else View.GONE
        binding.btnPopupAction.text = broadcast.buttonText.trim().ifEmpty {
            getString(
                if (isUpdate) R.string.popup_default_update_cta
                else R.string.popup_default_ad_cta
            )
        }
        binding.btnPopupAction.backgroundTintList = ColorStateList.valueOf(
            Color.parseColor(if (isUpdate) "#10B981" else "#2563EB")
        )

        // Hide close buttons strictly when Force Update is ON (even in preview, show accurate Force Update UI;
        // preview still allows tapping outside / back button to exit safely).
        val showCloseControls = !isForceUpdate
        binding.btnPopupCloseIcon.visibility = if (showCloseControls) View.VISIBLE else View.GONE
        binding.btnPopupClose.visibility = if (showCloseControls) View.VISIBLE else View.GONE

        val dismissAction = View.OnClickListener {
            if (canDismiss) {
                markSeenIfEligible()
                dismissAllowingStateLoss()
            }
        }
        val ctaAction = View.OnClickListener {
            if (hasAction) {
                openSafeExternalUrl(broadcast.actionUrl)
            }
            if (canDismiss) {
                markSeenIfEligible()
                dismissAllowingStateLoss()
            }
        }

        binding.btnPopupCloseIcon.setOnClickListener(dismissAction)
        binding.btnPopupClose.setOnClickListener(dismissAction)
        binding.btnPopupAction.setOnClickListener(ctaAction)

        if (hasImage && hasAction) {
            binding.cardPopupImageWrapper.setOnClickListener(ctaAction)
            binding.ivPopupImage.setOnClickListener(ctaAction)
        }
    }

    private fun playEntranceAnimation() {
        binding.cardPopupGlass.apply {
            alpha = 0f
            scaleX = 0.88f
            scaleY = 0.88f
            translationY = 18f * resources.displayMetrics.density
            animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .translationY(0f)
                .setDuration(290L)
                .setInterpolator(OvershootInterpolator(1.08f))
                .start()
        }
    }

    private fun markSeenIfEligible() {
        if (hasRecordedSeen || isPreviewMode || payload.isForceUpdateActive()) return
        val ctx = context ?: return
        val id = payload.effectivePopupId()
        if (id.isNotBlank()) {
            hasRecordedSeen = true
            DialerPreferences(ctx).markPopupIdSeen(id)
        }
    }

    private fun openSafeExternalUrl(rawUrl: String) {
        val ctx = context ?: return
        val clean = rawUrl.trim()
        if (clean.isEmpty()) return
        val normalized = if (
            !clean.contains("://") &&
            !clean.startsWith("tel:") &&
            !clean.startsWith("mailto:")
        ) {
            "https://$clean"
        } else {
            clean
        }
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(normalized)))
        }.onFailure {
            Toast.makeText(ctx, R.string.broadcast_invalid_link, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val TAG = "SmokedSapphirePopupDialogFragment"
        private const val BACKDROP_BLUR_RADIUS_PX = 36

        private const val ARG_IS_PREVIEW = "arg_is_preview"
        private const val ARG_POPUP_ID = "arg_popup_id"
        private const val ARG_POPUP_TYPE = "arg_popup_type"
        private const val ARG_VERSION = "arg_version"
        private const val ARG_FORCE_UPDATE = "arg_force_update"
        private const val ARG_SHOW_ONCE = "arg_show_once"
        private const val ARG_TITLE = "arg_title"
        private const val ARG_MESSAGE = "arg_message"
        private const val ARG_IMAGE_URL = "arg_image_url"
        private const val ARG_BUTTON_TEXT = "arg_button_text"
        private const val ARG_ACTION_URL = "arg_action_url"
        private const val ARG_IS_CANCELABLE = "arg_is_cancelable"

        fun newInstance(
            broadcast: LiveBroadcast,
            isPreview: Boolean = false
        ): SmokedSapphirePopupDialogFragment {
            return SmokedSapphirePopupDialogFragment().apply {
                arguments = Bundle().apply {
                    putBoolean(ARG_IS_PREVIEW, isPreview)
                    putString(ARG_POPUP_ID, broadcast.effectivePopupId())
                    putString(ARG_POPUP_TYPE, broadcast.normalizedPopupType())
                    putString(ARG_VERSION, broadcast.version)
                    putBoolean(ARG_FORCE_UPDATE, broadcast.forceUpdate)
                    putBoolean(ARG_SHOW_ONCE, broadcast.showOnce)
                    putString(ARG_TITLE, broadcast.title)
                    putString(ARG_MESSAGE, broadcast.message)
                    putString(ARG_IMAGE_URL, broadcast.imageUrl)
                    putString(ARG_BUTTON_TEXT, broadcast.buttonText)
                    putString(ARG_ACTION_URL, broadcast.actionUrl)
                    putBoolean(ARG_IS_CANCELABLE, broadcast.isCancelable)
                }
            }
        }

        fun showIfNotAlreadyVisible(
            fragmentManager: FragmentManager,
            broadcast: LiveBroadcast,
            isPreview: Boolean = false
        ): SmokedSapphirePopupDialogFragment? {
            if (fragmentManager.isStateSaved) return null
            val targetId = broadcast.effectivePopupId()
            val existing = fragmentManager.findFragmentByTag(TAG) as? SmokedSapphirePopupDialogFragment
            if (existing != null && existing.isAdded) {
                if (!isPreview && existing.currentPopupId == targetId) {
                    return existing
                }
                existing.dismissAllowingStateLoss()
            }
            return newInstance(broadcast, isPreview).also { fragment ->
                fragment.show(fragmentManager, TAG)
            }
        }

        fun dismissIfShowing(fragmentManager: FragmentManager) {
            val existing = fragmentManager.findFragmentByTag(TAG) as? SmokedSapphirePopupDialogFragment
            existing?.dismissAllowingStateLoss()
        }

        fun isShowingPopup(fragmentManager: FragmentManager, popupId: String): Boolean {
            val existing = fragmentManager.findFragmentByTag(TAG) as? SmokedSapphirePopupDialogFragment
            return existing != null && existing.isAdded && existing.currentPopupId == popupId
        }

        internal fun broadcastFromArgs(args: Bundle?): LiveBroadcast {
            if (args == null) return LiveBroadcast()
            return LiveBroadcast(
                showPopup = true,
                popupId = args.getString(ARG_POPUP_ID).orEmpty(),
                popupType = args.getString(ARG_POPUP_TYPE).orEmpty(),
                version = args.getString(ARG_VERSION).orEmpty(),
                forceUpdate = args.getBoolean(ARG_FORCE_UPDATE, false),
                showOnce = args.getBoolean(ARG_SHOW_ONCE, true),
                title = args.getString(ARG_TITLE).orEmpty(),
                message = args.getString(ARG_MESSAGE).orEmpty(),
                imageUrl = args.getString(ARG_IMAGE_URL).orEmpty(),
                buttonText = args.getString(ARG_BUTTON_TEXT).orEmpty(),
                actionUrl = args.getString(ARG_ACTION_URL).orEmpty(),
                isCancelable = args.getBoolean(ARG_IS_CANCELABLE, true)
            )
        }
    }
}
