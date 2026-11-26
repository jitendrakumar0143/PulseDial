package com.example.ui.incall

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.TextUtils
import android.util.AttributeSet
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.SoundEffectConstants
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.example.R
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Ultra-Premium 3D Floating Incoming & Active Call Popup Pill.
 *
 * Implements the exact 3D floating call popup choreography, visual system, and drag physics:
 * 1. Smoked-sapphire frosted glass (`165deg`, `rgba(22,28,46,0.88) -> rgba(10,14,24,0.94)`) with
 *    inset top specular highlight, inset bottom depth bevel, rotating 5s liquid-platinum conic rim,
 *    and subtle left emerald (`12%`) & right crimson (`88%`) wing glows.
 * 2. 3-stage 3D entrance:
 *    - Stage 1 (`460ms`, `cubic-bezier(0.22, 1.24, 0.36, 1)`): Drops from `-140dp` / `rotateX(38°)`
 *      / `scale(0.8)` (`perspective: 1100dp`) as a `68dp × 68dp` circular avatar initials orb.
 *    - Stage 2 (`920ms` width / `700ms` height, `cubic-bezier(0.22, 1, 0.36, 1)`): Unfolds center-to-sides
 *      to `min(94vw, 390dp) × 76dp` with a crisp `750ms` vertical center-seam platinum glint while
 *      `52dp` sculpted 3D Emerald & Crimson jewel buttons glide outward (`780ms`).
 *    - Stage 3: Drag-ready idle with caller name, subtitle + inline 4-bar `#38BDF8` live mini waveform,
 *      and pulsing directional chevrons (`»` emerald / `«` crimson).
 * 3. Smooth sideways drag physics (`55%` threshold, luminous glass trails, acoustic tick + 14ms haptic,
 *    dynamic emerald/crimson shell glow, elastic snap-back, and `±18dp` tap-to-nudge `"DRAG SIDEWAYS"`).
 * 4. Active "On Call" compact bar (`44dp` dark-slate avatar with `2dp` emerald halo, pulsing `7dp` live dot,
 *    `00:00` timer + 4-bar `#38BDF8` mini waveform, frosted glass Mute/Speaker with sky-blue ON state,
 *    and glossy 3D red End Call button).
 * 5. Collapse-to-Orb exit: symmetrically collapses back into the `68dp × 68dp` circular initials orb (`420ms`),
 *    then retracts upward off the top of the screen (`380ms`, `rotateX(34°)`, `scale(0.8)`).
 */
class Floating3DCallPillView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    enum class PillMode {
        HIDDEN,
        STAGE_1_DROP,
        STAGE_2_UNFOLD,
        STAGE_3_DRAG_READY,
        ACTIVE_CALL,
        DISMISSING
    }

    enum class DragSide {
        NONE,
        ANSWER_GREEN,
        DECLINE_RED
    }

    interface CallPillListener {
        fun onAnswerCall()
        fun onDeclineCall()
        fun onToggleMute()
        fun onToggleSpeaker()
        fun onEndCall()
        fun onPillTappedWhileActive() {}
        fun onDismissAnimationComplete() {}
    }

    var listener: CallPillListener? = null

    var currentMode: PillMode = PillMode.HIDDEN
        private set

    var activeDragSide: DragSide = DragSide.NONE
        private set

    var currentDragProgress: Float = 0f
        private set

    var lastStatusMessage: String = ""
        private set

    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    // Exact dimensions (in px)
    val orbSizePx: Float = ORB_SIZE_DP * density
    val pillHeightPx: Float = PILL_HEIGHT_DP * density
    val collapsedWidthPx: Float = COLLAPSED_WIDTH_DP * density
    val pillCornerRadiusPx: Float = PILL_CORNER_RADIUS_DP * density
    val jewelButtonSizePx: Int = (JEWEL_BUTTON_SIZE_DP * density).toInt()
    private val dropStartOffsetYPx: Float = -DROP_OFFSET_Y_DP * density
    private val sideInsetPx: Float = 12f * density
    private val rimStrokePx: Float = 1.5f * density

    // Animation state
    private var unfoldWidthProgress: Float = 0f   // 0f = 68dp orb, 1f = expanded pill width
    private var unfoldHeightProgress: Float = 0f  // 0f = 68dp orb, 1f = 76dp pill height
    private var buttonGlideProgress: Float = 0f   // 0f = center scale 0.5, 1f = edge scale 1.0
    private var wingGlowAlpha: Float = 0f
    private var orbAvatarAlpha: Float = 1f
    private var orbAvatarScale: Float = 1f
    private var seamGlintPhase: Float = -1f       // -1f = inactive, 0..1 = active 750ms flash
    private var rimRotationDegrees: Float = 0f
    private var ambientElapsedSec: Float = 0f
    private var dragCrossedThreshold: Boolean = false
    private var currentDragDeltaPx: Float = 0f

    // Cached caller & active-call data
    private var callerNameText: String = context.getString(R.string.incall_unknown_caller)
    private var callerInitialsText: String = computeCallerInitials(callerNameText)
    private var callerSubtitleText: String = context.getString(R.string.incall_status_ringing)
    private var activeTimerText: String = "00:00"
    private var isMutedState: Boolean = false
    private var isSpeakerState: Boolean = false
    private var isBluetoothAudioState: Boolean = false
    private var showingNudgeHint: Boolean = false

    // Zero-allocation drawing primitives for locked 60fps
    private val pillRect = RectF()
    private val innerRect = RectF()
    private val trailRect = RectF()
    private val seamRect = RectF()
    private val pillClipPath = Path()
    private val shaderMatrix = Matrix()

    private val shellShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val rimBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = rimStrokePx
    }
    private val outerSubtleRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = Color.argb(16, 255, 255, 255)
    }
    private val glassInteriorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val topInsetHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
        color = Color.argb(56, 255, 255, 255) // rgba(255,255,255,0.22)
    }
    private val topSheenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val bottomInsetDepthPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val wingTintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val seamGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val seamCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val orbInitialsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F8FAFC")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        letterSpacing = 0.03f
        textSize = 20f * density
    }
    private val chevronTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        textSize = 15f * density
    }

    // Subviews inside the pill
    private val incomingStageContainer: FrameLayout
    private val centerInfoColumn: LinearLayout
    private val tvCallerName: TextView
    private val callerSubRow: LinearLayout
    private val tvCallerSubtitle: TextView
    private val incomingMiniWave: MiniCallWaveformView
    private val tvDragStatus: TextView
    private val btnGreenAnswer: Jewel3DButtonView
    private val btnRedDecline: Jewel3DButtonView

    private val activeCallContainer: LinearLayout
    private val activeAvatarView: ActiveCallAvatarView
    private val activeCallerColumn: LinearLayout
    private val tvActiveName: TextView
    private val activeTimerRow: LinearLayout
    private val livePulseDotView: LivePulseDotView
    private val tvActiveTimer: TextView
    private val activeMiniWave: MiniCallWaveformView
    private val btnActiveMute: CompactGlassActionButton
    private val btnActiveSpeaker: CompactGlassActionButton
    private val btnActiveEnd: CompactGlassActionButton

    // Animators
    private var entranceAnimator: AnimatorSet? = null
    private var dismissAnimator: AnimatorSet? = null
    private var dragSnapAnimator: ValueAnimator? = null
    private var nudgeAnimator: AnimatorSet? = null
    private var ambientLoopAnimator: ValueAnimator? = null
    private var lastAmbientTickMs: Long = 0L

    private val clearNudgeRunnable = Runnable {
        showingNudgeHint = false
        if (activeDragSide == DragSide.NONE && currentMode == PillMode.STAGE_3_DRAG_READY) {
            btnGreenAnswer.animate().translationX(0f).setDuration(200L).start()
            btnRedDecline.animate().translationX(0f).setDuration(200L).start()
            tvDragStatus.animate().alpha(0f).setDuration(150L).start()
            centerInfoColumn.animate().alpha(1f).setDuration(180L).start()
            invalidate()
        }
    }

    // Gesture tracking
    private var downRawX: Float = 0f
    private var maxAbsDeltaX: Float = 0f
    private var dragMovedPastTap: Boolean = false
    private var dragCompletedThisGesture: Boolean = false

    init {
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
        outlineProvider = null
        elevation = OVERLAY_ELEVATION_DP * density
        translationZ = OVERLAY_TRANSLATION_Z_DP * density
        visibility = View.GONE

        // 3D perspective: matches CSS `perspective: 1100px`
        cameraDistance = PERSPECTIVE_DP * density * density

        // --- Incoming Call Layer ---
        incomingStageContainer = FrameLayout(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, pillHeightPx.toInt(), Gravity.CENTER)
            clipChildren = false
            clipToPadding = false
            alpha = 0f
        }

        centerInfoColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
                marginStart = (JEWEL_BUTTON_SIZE_DP * density + 30f * density).toInt()
                marginEnd = (JEWEL_BUTTON_SIZE_DP * density + 30f * density).toInt()
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { listener?.onPillTappedWhileActive() }
        }

        tvCallerName = TextView(context).apply {
            text = callerNameText
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            includeFontPadding = false
        }

        callerSubRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (2f * density).toInt()
            }
        }

        tvCallerSubtitle = TextView(context).apply {
            text = callerSubtitleText
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 11f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
                1f
            )
        }

        incomingMiniWave = MiniCallWaveformView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = (6f * density).toInt()
            }
        }

        callerSubRow.addView(tvCallerSubtitle)
        callerSubRow.addView(incomingMiniWave)

        centerInfoColumn.addView(tvCallerName)
        centerInfoColumn.addView(callerSubRow)

        tvDragStatus = TextView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
                marginStart = (JEWEL_BUTTON_SIZE_DP * density + 18f * density).toInt()
                marginEnd = (JEWEL_BUTTON_SIZE_DP * density + 18f * density).toInt()
            }
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            textSize = 11f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            letterSpacing = 0.11f
            isAllCaps = true
            setTextColor(Color.parseColor("#E2E8F0"))
            alpha = 0f
        }

        btnGreenAnswer = Jewel3DButtonView(
            context = context,
            isEmerald = true,
            iconRes = R.drawable.ic_call
        ).apply {
            id = R.id.btn_pill_drag_answer
            layoutParams = LayoutParams(jewelButtonSizePx, jewelButtonSizePx, Gravity.CENTER_VERTICAL or Gravity.START).apply {
                marginStart = sideInsetPx.toInt()
            }
            contentDescription = context.getString(R.string.incall_answer)
        }

        btnRedDecline = Jewel3DButtonView(
            context = context,
            isEmerald = false,
            iconRes = R.drawable.ic_call_end
        ).apply {
            id = R.id.btn_pill_drag_decline
            layoutParams = LayoutParams(jewelButtonSizePx, jewelButtonSizePx, Gravity.CENTER_VERTICAL or Gravity.END).apply {
                marginEnd = sideInsetPx.toInt()
            }
            contentDescription = context.getString(R.string.incall_decline)
        }

        incomingStageContainer.addView(centerInfoColumn)
        incomingStageContainer.addView(tvDragStatus)
        incomingStageContainer.addView(btnGreenAnswer)
        incomingStageContainer.addView(btnRedDecline)

        // --- Active "On Call" Compact Bar Layer ---
        activeCallContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, pillHeightPx.toInt(), Gravity.CENTER)
            setPadding((12f * density).toInt(), 0, (10f * density).toInt(), 0)
            clipChildren = false
            clipToPadding = false
            visibility = View.GONE
            alpha = 0f
        }

        val avatarSizePx = (48f * density).toInt() // 44dp visual circle + halo room
        activeAvatarView = ActiveCallAvatarView(context).apply {
            layoutParams = LinearLayout.LayoutParams(avatarSizePx, avatarSizePx).apply {
                marginEnd = (8f * density).toInt()
            }
            initials = callerInitialsText
            contentDescription = context.getString(R.string.incall_pill_live_call_cd)
        }

        activeCallerColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply {
                marginEnd = (6f * density).toInt()
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { listener?.onPillTappedWhileActive() }
        }

        tvActiveName = TextView(context).apply {
            text = callerNameText
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
        }

        activeTimerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (2f * density).toInt()
            }
        }

        livePulseDotView = LivePulseDotView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = (5f * density).toInt()
            }
        }

        tvActiveTimer = TextView(context).apply {
            text = activeTimerText
            setTextColor(Color.parseColor("#34D399"))
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            maxLines = 1
            includeFontPadding = false
        }

        activeMiniWave = MiniCallWaveformView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = (6f * density).toInt()
            }
        }

        activeTimerRow.addView(livePulseDotView)
        activeTimerRow.addView(tvActiveTimer)
        activeTimerRow.addView(activeMiniWave)

        activeCallerColumn.addView(tvActiveName)
        activeCallerColumn.addView(activeTimerRow)

        val miniCtrlTouchW = (42f * density).toInt()
        val miniCtrlTouchH = (48f * density).toInt()
        val endBtnTouchSize = (48f * density).toInt()

        btnActiveMute = CompactGlassActionButton(context, R.drawable.ic_mic, isDestructive = false).apply {
            id = R.id.btn_pill_active_mute
            layoutParams = LinearLayout.LayoutParams(miniCtrlTouchW, miniCtrlTouchH).apply {
                marginEnd = (4f * density).toInt()
            }
            contentDescription = context.getString(R.string.incall_mute)
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                listener?.onToggleMute()
            }
        }

        btnActiveSpeaker = CompactGlassActionButton(context, R.drawable.ic_volume_up, isDestructive = false).apply {
            id = R.id.btn_pill_active_speaker
            layoutParams = LinearLayout.LayoutParams(miniCtrlTouchW, miniCtrlTouchH).apply {
                marginEnd = (4f * density).toInt()
            }
            contentDescription = context.getString(R.string.incall_speaker)
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                listener?.onToggleSpeaker()
            }
        }

        btnActiveEnd = CompactGlassActionButton(context, R.drawable.ic_call_end, isDestructive = true).apply {
            id = R.id.btn_pill_active_end
            layoutParams = LinearLayout.LayoutParams(endBtnTouchSize, endBtnTouchSize)
            contentDescription = context.getString(R.string.incall_end_call)
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                listener?.onEndCall()
            }
        }

        activeCallContainer.addView(activeAvatarView)
        activeCallContainer.addView(activeCallerColumn)
        activeCallContainer.addView(btnActiveMute)
        activeCallContainer.addView(btnActiveSpeaker)
        activeCallContainer.addView(btnActiveEnd)

        addView(incomingStageContainer)
        addView(activeCallContainer)

        setupDragGesture(btnGreenAnswer, DragSide.ANSWER_GREEN)
        setupDragGesture(btnRedDecline, DragSide.DECLINE_RED)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val parentWidth = MeasureSpec.getSize(widthMeasureSpec)
        val expandedWidth = computeExpandedWidthPx(parentWidth)
        val totalHeight = (pillHeightPx + SHADOW_PADDING_VERTICAL_DP * 2f * density).toInt()
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(expandedWidth.toInt(), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(totalHeight, MeasureSpec.EXACTLY)
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        updateJewelRestingPositions()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ensureHighZOverlayLayer()
        if (currentMode != PillMode.HIDDEN) {
            startAmbientLoop()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelAllAnimations()
        removeCallbacks(clearNudgeRunnable)
    }

    private fun ensureHighZOverlayLayer() {
        clipChildren = false
        clipToPadding = false
        outlineProvider = null
        elevation = OVERLAY_ELEVATION_DP * density
        translationZ = OVERLAY_TRANSLATION_Z_DP * density
        (parent as? ViewGroup)?.let { host ->
            if (host.clipChildren) host.clipChildren = false
            if (host.clipToPadding) host.clipToPadding = false
        }
    }

    /**
     * Computes expanded pill width matching CSS `min(94vw, 390px)`, safely clamped on ultra-narrow devices.
     */
    fun computeExpandedWidthPx(availableWidthPx: Int): Float {
        val maxExpanded = MAX_EXPANDED_WIDTH_DP * density
        if (availableWidthPx <= 0) {
            return (resources.displayMetrics.widthPixels * MAX_WIDTH_FRACTION).coerceAtMost(maxExpanded)
        }
        val minCompact = (264f * density).coerceAtMost(availableWidthPx.toFloat())
        return (availableWidthPx * MAX_WIDTH_FRACTION).coerceIn(minCompact, maxExpanded)
    }

    /**
     * Updates caller metadata dynamically without restarting any running animation.
     */
    fun updateCallerInfo(callerName: String, callerLabelOrNumber: String) {
        callerNameText = callerName.ifBlank { context.getString(R.string.incall_unknown_caller) }
        callerInitialsText = computeCallerInitials(callerNameText)
        callerSubtitleText = callerLabelOrNumber.ifBlank { context.getString(R.string.incall_status_ringing) }
        tvCallerName.text = callerNameText
        tvCallerSubtitle.text = callerSubtitleText
        tvActiveName.text = callerNameText
        activeAvatarView.initials = callerInitialsText
        invalidate()
    }

    /**
     * Updates active call live timer & Mute / Speaker sky-blue ON highlights.
     */
    fun updateActiveCallState(
        callerName: String,
        formattedTimer: String,
        isMuted: Boolean,
        isSpeakerOn: Boolean,
        isBluetooth: Boolean = false
    ) {
        callerNameText = callerName.ifBlank { context.getString(R.string.incall_unknown_caller) }
        callerInitialsText = computeCallerInitials(callerNameText)
        activeTimerText = formattedTimer.ifBlank { "00:00" }
        isMutedState = isMuted
        isSpeakerState = isSpeakerOn
        isBluetoothAudioState = isBluetooth

        tvActiveName.text = callerNameText
        activeAvatarView.initials = callerInitialsText
        tvActiveTimer.text = activeTimerText
        invalidate()

        btnActiveMute.setIconRes(if (isMuted) R.drawable.ic_mic_off else R.drawable.ic_mic)
        btnActiveMute.setActiveHighlight(isMuted)
        btnActiveMute.contentDescription = context.getString(
            if (isMuted) R.string.incall_unmute else R.string.incall_mute
        )

        btnActiveSpeaker.setIconRes(if (isBluetooth) R.drawable.ic_bluetooth else R.drawable.ic_volume_up)
        btnActiveSpeaker.setActiveHighlight(isSpeakerOn || isBluetooth)
        btnActiveSpeaker.contentDescription = context.getString(
            when {
                isBluetooth -> R.string.incall_audio_bluetooth
                isSpeakerOn -> R.string.incall_speaker_on
                else -> R.string.incall_speaker
            }
        )
    }

    /**
     * Launches the exact 3-stage Incoming Call choreography:
     * - Stage 1 (`460ms`): Drop as `68dp × 68dp` circular initials orb (`translateY(-140dp) rotateX(38°) scale(0.8)`).
     * - Stage 2 (`920ms` width / `700ms` height / `780ms` buttons / `750ms` seam glint): Center-to-sides unfold to `76dp` pill.
     * - Stage 3: Drag-ready idle with pulsing directional chevrons & live 4-bar waveform.
     */
    fun showIncomingCall(callerName: String, callerLabelOrNumber: String) {
        updateCallerInfo(callerName, callerLabelOrNumber)
        if (currentMode == PillMode.STAGE_1_DROP ||
            currentMode == PillMode.STAGE_2_UNFOLD ||
            currentMode == PillMode.STAGE_3_DRAG_READY
        ) {
            return
        }

        cancelAllAnimations()
        removeCallbacks(clearNudgeRunnable)
        resetDragVisualsImmediate()
        ensureHighZOverlayLayer()

        visibility = View.VISIBLE
        incomingStageContainer.visibility = View.VISIBLE
        incomingStageContainer.alpha = 0f
        activeCallContainer.visibility = View.GONE
        activeCallContainer.alpha = 0f

        centerInfoColumn.alpha = 1f
        tvDragStatus.alpha = 0f
        unfoldWidthProgress = 0f
        unfoldHeightProgress = 0f
        buttonGlideProgress = 0f
        wingGlowAlpha = 0f
        orbAvatarAlpha = 1f
        orbAvatarScale = 1f
        seamGlintPhase = -1f

        // Stage 1 starting 3D orb pose
        currentMode = PillMode.STAGE_1_DROP
        translationY = dropStartOffsetYPx
        rotationX = DROP_START_ROTATION_X
        scaleX = DROP_START_SCALE
        scaleY = DROP_START_SCALE
        alpha = 0f

        setLayerType(LAYER_TYPE_HARDWARE, null)
        startAmbientLoop()
        updateJewelRestingPositions()
        invalidate()

        // Stage 1: Drop 68dp × 68dp circular orb (460ms spring bounce + 260ms opacity)
        val springBounce = PathInterpolator(0.22f, 1.24f, 0.36f, 1f)
        val dropTransform = AnimatorSet().apply {
            duration = STAGE_1_DROP_MS
            interpolator = springBounce
            playTogether(
                ObjectAnimator.ofFloat(this@Floating3DCallPillView, View.TRANSLATION_Y, dropStartOffsetYPx, 0f),
                ObjectAnimator.ofFloat(this@Floating3DCallPillView, View.ROTATION_X, DROP_START_ROTATION_X, 0f),
                ObjectAnimator.ofFloat(this@Floating3DCallPillView, View.SCALE_X, DROP_START_SCALE, 1f),
                ObjectAnimator.ofFloat(this@Floating3DCallPillView, View.SCALE_Y, DROP_START_SCALE, 1f)
            )
        }
        val dropOpacity = ObjectAnimator.ofFloat(this@Floating3DCallPillView, View.ALPHA, 0f, 1f).apply {
            duration = 260L
            interpolator = DecelerateInterpolator()
        }
        val stage1Drop = AnimatorSet().apply {
            playTogether(dropTransform, dropOpacity)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (currentMode == PillMode.STAGE_1_DROP) {
                        currentMode = PillMode.STAGE_2_UNFOLD
                    }
                }
            })
        }

        // Stage 2: Center-to-sides 76dp pill unfold with seam glint & jewel glide
        val unfoldEase = PathInterpolator(0.22f, 1f, 0.36f, 1f)

        val widthAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = STAGE_2_UNFOLD_MS
            interpolator = unfoldEase
            addUpdateListener { anim ->
                unfoldWidthProgress = anim.animatedValue as Float
                updateJewelRestingPositions()
                invalidate()
            }
        }
        val heightAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = STAGE_2_HEIGHT_MS
            interpolator = unfoldEase
            addUpdateListener { anim ->
                unfoldHeightProgress = anim.animatedValue as Float
                invalidate()
            }
        }
        val buttonGlideAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = STAGE_2_BUTTON_GLIDE_MS
            interpolator = unfoldEase
            addUpdateListener { anim ->
                buttonGlideProgress = anim.animatedValue as Float
                updateJewelRestingPositions()
            }
        }
        val seamGlintAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SEAM_GLINT_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                seamGlintPhase = anim.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    seamGlintPhase = -1f
                }
            })
        }
        val orbFadeOutAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 320L
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val t = anim.animatedValue as Float
                orbAvatarAlpha = (1f - (t / 0.82f)).coerceIn(0f, 1f)
                orbAvatarScale = lerp(1f, 0.65f, t)
                invalidate()
            }
        }
        val wingFadeInAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 600L
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                wingGlowAlpha = anim.animatedValue as Float
                invalidate()
            }
        }
        val incomingFadeIn = ObjectAnimator.ofFloat(incomingStageContainer, View.ALPHA, 0f, 1f).apply {
            duration = 320L
            interpolator = DecelerateInterpolator()
        }

        val stage2Unfold = AnimatorSet().apply {
            playTogether(
                widthAnim,
                heightAnim,
                buttonGlideAnim,
                seamGlintAnim,
                orbFadeOutAnim,
                wingFadeInAnim,
                incomingFadeIn
            )
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (currentMode == PillMode.STAGE_2_UNFOLD || currentMode == PillMode.STAGE_1_DROP) {
                        unfoldWidthProgress = 1f
                        unfoldHeightProgress = 1f
                        buttonGlideProgress = 1f
                        wingGlowAlpha = 1f
                        orbAvatarAlpha = 0f
                        orbAvatarScale = 0.65f
                        seamGlintPhase = -1f
                        incomingStageContainer.alpha = 1f
                        centerInfoColumn.alpha = 1f
                        currentMode = PillMode.STAGE_3_DRAG_READY
                        setLayerType(LAYER_TYPE_NONE, null)
                        updateJewelRestingPositions()
                        invalidate()
                    }
                }
            })
        }

        entranceAnimator = AnimatorSet().apply {
            playSequentially(stage1Drop, stage2Unfold)
            start()
        }
    }

    /**
     * Smoothly morphs the pill into the compact `76dp` Active "On Call" Bar.
     * If hidden (e.g. outgoing call), drops cleanly into the Active Bar.
     */
    fun morphToActiveCallBar(
        callerName: String,
        formattedTimer: String,
        isMuted: Boolean,
        isSpeakerOn: Boolean,
        isBluetooth: Boolean = false
    ) {
        updateActiveCallState(callerName, formattedTimer, isMuted, isSpeakerOn, isBluetooth)
        if (currentMode == PillMode.ACTIVE_CALL) {
            invalidate()
            return
        }

        val wasHidden = currentMode == PillMode.HIDDEN || visibility != View.VISIBLE
        cancelAllAnimations()
        removeCallbacks(clearNudgeRunnable)
        resetDragVisualsImmediate()

        currentMode = PillMode.ACTIVE_CALL
        ensureHighZOverlayLayer()
        visibility = View.VISIBLE
        unfoldWidthProgress = 1f
        unfoldHeightProgress = 1f
        buttonGlideProgress = 1f
        wingGlowAlpha = 0f
        orbAvatarAlpha = 0f
        orbAvatarScale = 0.65f
        seamGlintPhase = -1f
        startAmbientLoop()

        activeCallContainer.visibility = View.VISIBLE
        if (wasHidden) {
            incomingStageContainer.visibility = View.GONE
            activeCallContainer.alpha = 1f
            translationY = dropStartOffsetYPx
            rotationX = DROP_START_ROTATION_X
            scaleX = 0.88f
            scaleY = 0.88f
            alpha = 0f
            animate()
                .translationY(0f)
                .rotationX(0f)
                .scaleX(1f)
                .scaleY(1f)
                .alpha(1f)
                .setDuration(400L)
                .setInterpolator(PathInterpolator(0.22f, 1.18f, 0.36f, 1f))
                .start()
        } else {
            translationY = 0f
            rotationX = 0f
            scaleX = 1f
            scaleY = 1f
            alpha = 1f
            incomingStageContainer.animate()
                .alpha(0f)
                .setDuration(220L)
                .withEndAction {
                    if (currentMode == PillMode.ACTIVE_CALL) {
                        incomingStageContainer.visibility = View.GONE
                    }
                }
                .start()
            activeCallContainer.alpha = 0f
            activeCallContainer.animate()
                .alpha(1f)
                .setDuration(300L)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
        invalidate()
    }

    /**
     * Exact Collapse-to-Orb Exit animation on Decline / End Call:
     * 1. Collapses both sides symmetrically back into the `68dp × 68dp` circular initials orb (`420ms`).
     * 2. Retracts upward off the top of the screen (`380ms` transform `rotateX(34°)` / `scale(0.8)`, `280ms` opacity).
     */
    fun dismissWithReverseAnimation(onFinished: (() -> Unit)? = null) {
        if (currentMode == PillMode.HIDDEN) {
            onFinished?.invoke()
            return
        }
        if (currentMode == PillMode.DISMISSING) return

        cancelAllAnimations()
        removeCallbacks(clearNudgeRunnable)
        resetDragVisualsImmediate()
        currentMode = PillMode.DISMISSING
        setLayerType(LAYER_TYPE_HARDWARE, null)

        val startW = unfoldWidthProgress.coerceIn(0f, 1f)
        val startH = unfoldHeightProgress.coerceIn(0f, 1f)
        val startWing = wingGlowAlpha
        val startIncomingAlpha = incomingStageContainer.alpha
        val startActiveAlpha = activeCallContainer.alpha

        val collapseAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = DISMISS_COLLAPSE_MS
            interpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f)
            addUpdateListener { anim ->
                val t = anim.animatedValue as Float
                unfoldWidthProgress = lerp(startW, 0f, t)
                unfoldHeightProgress = lerp(startH, 0f, t)
                buttonGlideProgress = (1f - t).coerceIn(0f, 1f)
                wingGlowAlpha = lerp(startWing, 0f, t)

                val layerFade = (1f - t * 2.1f).coerceIn(0f, 1f)
                incomingStageContainer.alpha = startIncomingAlpha * layerFade
                activeCallContainer.alpha = startActiveAlpha * layerFade

                // Bring circular orb initials back into view cleanly as orb closes
                val orbIn = ((t - 0.30f) / 0.65f).coerceIn(0f, 1f)
                orbAvatarAlpha = orbIn
                orbAvatarScale = lerp(0.65f, 1f, orbIn)

                updateJewelRestingPositions()
                invalidate()
            }
        }

        val retractEase = PathInterpolator(0.5f, 0f, 0.75f, 0f)
        val retractTransform = AnimatorSet().apply {
            duration = DISMISS_RETRACT_MS
            interpolator = retractEase
            playTogether(
                ObjectAnimator.ofFloat(this@Floating3DCallPillView, View.TRANSLATION_Y, translationY, dropStartOffsetYPx),
                ObjectAnimator.ofFloat(this@Floating3DCallPillView, View.ROTATION_X, rotationX, RETRACT_END_ROTATION_X),
                ObjectAnimator.ofFloat(this@Floating3DCallPillView, View.SCALE_X, scaleX, DROP_START_SCALE),
                ObjectAnimator.ofFloat(this@Floating3DCallPillView, View.SCALE_Y, scaleY, DROP_START_SCALE)
            )
        }
        val retractOpacity = ObjectAnimator.ofFloat(this@Floating3DCallPillView, View.ALPHA, alpha, 0f).apply {
            duration = DISMISS_RETRACT_OPACITY_MS
            interpolator = DecelerateInterpolator()
        }
        val retractUpAnim = AnimatorSet().apply {
            playTogether(retractTransform, retractOpacity)
        }

        dismissAnimator = AnimatorSet().apply {
            playSequentially(collapseAnim, retractUpAnim)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    currentMode = PillMode.HIDDEN
                    visibility = View.GONE
                    setLayerType(LAYER_TYPE_NONE, null)
                    stopAmbientLoop()
                    onFinished?.invoke()
                    listener?.onDismissAnimationComplete()
                }
            })
            start()
        }
    }

    /**
     * Immediately hides the pill without animation (e.g. teardown or activity destroy).
     */
    fun hideImmediately() {
        cancelAllAnimations()
        removeCallbacks(clearNudgeRunnable)
        currentMode = PillMode.HIDDEN
        visibility = View.GONE
        unfoldWidthProgress = 0f
        unfoldHeightProgress = 0f
        buttonGlideProgress = 0f
        wingGlowAlpha = 0f
        orbAvatarAlpha = 1f
        orbAvatarScale = 1f
        seamGlintPhase = -1f
        resetDragVisualsImmediate()
        stopAmbientLoop()
    }

    // --- Sideways Drag-to-Answer & Drag-to-Decline Physics ---

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDragGesture(button: Jewel3DButtonView, side: DragSide) {
        button.setOnTouchListener { v, event ->
            if (currentMode != PillMode.STAGE_3_DRAG_READY && currentMode != PillMode.STAGE_2_UNFOLD) {
                return@setOnTouchListener false
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragSnapAnimator?.cancel()
                    nudgeAnimator?.cancel()
                    removeCallbacks(clearNudgeRunnable)
                    showingNudgeHint = false
                    downRawX = event.rawX
                    maxAbsDeltaX = 0f
                    dragMovedPastTap = false
                    dragCompletedThisGesture = false
                    dragCrossedThreshold = false
                    activeDragSide = side
                    currentDragProgress = 0f
                    currentDragDeltaPx = 0f
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (dragCompletedThisGesture) return@setOnTouchListener true
                    val rawDelta = event.rawX - downRawX
                    val directedDelta = if (side == DragSide.ANSWER_GREEN) {
                        rawDelta.coerceAtLeast(0f)
                    } else {
                        (-rawDelta).coerceAtLeast(0f)
                    }
                    maxAbsDeltaX = max(maxAbsDeltaX, abs(rawDelta))
                    if (abs(directedDelta) > 4f * density || maxAbsDeltaX > touchSlop * 0.45f) {
                        dragMovedPastTap = true
                        applyDragDisplacement(side, directedDelta)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    val isCancel = event.actionMasked == MotionEvent.ACTION_CANCEL
                    if (!isCancel && !dragMovedPastTap && maxAbsDeltaX < touchSlop) {
                        resetDragVisualsImmediate()
                        triggerTapToNudgeHint(side)
                    } else if (!isCancel && currentDragProgress >= DRAG_TRIGGER_THRESHOLD) {
                        completeDragAction(side)
                    } else {
                        snapBackFromDrag(side)
                    }
                    true
                }
                else -> false
            }
        }
    }

    internal fun applyDragProgressForTestOrGesture(side: DragSide, progress: Float) {
        activeDragSide = side
        val clamped = progress.coerceIn(0f, 1f)
        val maxTravel = computeMaxDragTravelPx()
        applyDragDisplacement(side, clamped * maxTravel)
    }

    private fun computeMaxDragTravelPx(): Float {
        val currentWidth = currentPillWidthPx()
        return max(120f * density, currentWidth - PILL_HEIGHT_DP * density)
    }

    private fun applyDragDisplacement(side: DragSide, directedDeltaPx: Float) {
        val maxTravel = computeMaxDragTravelPx()
        val clampedPx = directedDeltaPx.coerceIn(0f, maxTravel)
        val progress = (clampedPx / maxTravel).coerceIn(0f, 1f)
        currentDragDeltaPx = clampedPx
        currentDragProgress = progress

        val baseGreenX = sideInsetPx
        val baseRedX = (width - sideInsetPx - jewelButtonSizePx).coerceAtLeast(sideInsetPx)
        val activeScale = 1f + progress * 0.08f
        val oppositeAlpha = (1f - progress * 2.2f).coerceIn(0f, 1f)

        if (side == DragSide.ANSWER_GREEN) {
            btnGreenAnswer.x = baseGreenX + clampedPx
            btnGreenAnswer.scaleX = activeScale
            btnGreenAnswer.scaleY = activeScale
            btnRedDecline.alpha = oppositeAlpha
        } else {
            btnRedDecline.x = baseRedX - clampedPx
            btnRedDecline.scaleX = activeScale
            btnRedDecline.scaleY = activeScale
            btnGreenAnswer.alpha = oppositeAlpha
        }

        // Caller center fades cleanly (`max(0, 1 - progress * 3)`)
        centerInfoColumn.alpha = (1f - progress * 3f).coerceIn(0f, 1f)

        // 55% threshold tick & dynamic status hint + glow switch
        val crossedNow = progress >= DRAG_TRIGGER_THRESHOLD
        if (crossedNow && !dragCrossedThreshold) {
            dragCrossedThreshold = true
            triggerThresholdTickFeedback(isPick = side == DragSide.ANSWER_GREEN)
        } else if (!crossedNow && dragCrossedThreshold) {
            dragCrossedThreshold = false
        }

        val statusRes = when {
            side == DragSide.ANSWER_GREEN && crossedNow -> R.string.incall_pill_release_to_answer
            side == DragSide.ANSWER_GREEN -> R.string.incall_pill_slide_to_answer
            side == DragSide.DECLINE_RED && crossedNow -> R.string.incall_pill_release_to_decline
            else -> R.string.incall_pill_slide_to_decline
        }
        val statusText = context.getString(statusRes)
        lastStatusMessage = statusText
        tvDragStatus.text = statusText
        tvDragStatus.setTextColor(
            when {
                side == DragSide.ANSWER_GREEN && crossedNow -> Color.parseColor("#6EE7B7")
                side == DragSide.DECLINE_RED && crossedNow -> Color.parseColor("#FDA4AF")
                else -> Color.parseColor("#E2E8F0")
            }
        )
        tvDragStatus.alpha = 1f
        invalidate()
    }

    private fun triggerThresholdTickFeedback(isPick: Boolean) {
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        playSoundEffect(SoundEffectConstants.CLICK)
        runCatching {
            val toneType = if (isPick) ToneGenerator.TONE_PROP_BEEP else ToneGenerator.TONE_PROP_NACK
            val toneGen = ToneGenerator(AudioManager.STREAM_SYSTEM, 38)
            toneGen.startTone(toneType, 55)
            postDelayed({ runCatching { toneGen.release() } }, 120L)
        }
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (vibrator?.hasVibrator() == true) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(14L, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(14L)
                }
            }
        }
    }

    internal fun triggerTapToNudgeHint(side: DragSide) {
        nudgeAnimator?.cancel()
        removeCallbacks(clearNudgeRunnable)
        showingNudgeHint = true

        val hintText = context.getString(R.string.incall_pill_drag_sideways)
        lastStatusMessage = hintText
        tvDragStatus.text = hintText
        tvDragStatus.setTextColor(Color.parseColor("#E2E8F0"))
        centerInfoColumn.animate().alpha(0f).setDuration(140L).start()
        tvDragStatus.animate().alpha(1f).setDuration(140L).start()

        val targetView = if (side == DragSide.ANSWER_GREEN) btnGreenAnswer else btnRedDecline
        val nudgeDelta = (if (side == DragSide.ANSWER_GREEN) 18f else -18f) * density

        val nudgeOut = ObjectAnimator.ofFloat(targetView, View.TRANSLATION_X, 0f, nudgeDelta).apply {
            duration = 160L
            interpolator = DecelerateInterpolator()
        }
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        nudgeAnimator = AnimatorSet().apply {
            play(nudgeOut)
            start()
        }
        invalidate()
        postDelayed(clearNudgeRunnable, 650L)
    }

    private fun snapBackFromDrag(side: DragSide) {
        val startProgress = currentDragProgress
        dragSnapAnimator?.cancel()
        dragSnapAnimator = ValueAnimator.ofFloat(startProgress, 0f).apply {
            duration = 280L
            interpolator = OvershootInterpolator(1.25f)
            addUpdateListener { anim ->
                val p = (anim.animatedValue as Float).coerceAtLeast(0f)
                val maxTravel = computeMaxDragTravelPx()
                applyDragDisplacement(side, p * maxTravel)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    resetDragVisualsImmediate()
                }
            })
            start()
        }
    }

    internal fun completeDragAction(side: DragSide) {
        dragCompletedThisGesture = true
        performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        resetDragVisualsImmediate()
        if (side == DragSide.ANSWER_GREEN) {
            listener?.onAnswerCall()
        } else if (side == DragSide.DECLINE_RED) {
            listener?.onDeclineCall()
        }
    }

    private fun resetDragVisualsImmediate() {
        activeDragSide = DragSide.NONE
        currentDragProgress = 0f
        currentDragDeltaPx = 0f
        dragCrossedThreshold = false
        btnGreenAnswer.translationX = 0f
        btnRedDecline.translationX = 0f
        btnGreenAnswer.scaleX = 1f
        btnGreenAnswer.scaleY = 1f
        btnRedDecline.scaleX = 1f
        btnRedDecline.scaleY = 1f
        if (currentMode == PillMode.STAGE_3_DRAG_READY) {
            btnGreenAnswer.alpha = 1f
            btnRedDecline.alpha = 1f
            centerInfoColumn.alpha = 1f
        }
        tvDragStatus.alpha = 0f
        updateJewelRestingPositions()
        invalidate()
    }

    private fun updateJewelRestingPositions() {
        if (width <= 0) return
        val centerX = width * 0.5f
        val halfJewel = jewelButtonSizePx * 0.5f
        val currentW = currentPillWidthPx()
        val pillLeft = centerX - currentW * 0.5f
        val pillRight = centerX + currentW * 0.5f

        val targetGreenX = (pillLeft + sideInsetPx).coerceAtLeast(0f)
        val targetRedX = (pillRight - sideInsetPx - jewelButtonSizePx).coerceAtLeast(0f)
        val collapsedX = centerX - halfJewel

        if (activeDragSide == DragSide.NONE) {
            btnGreenAnswer.x = lerp(collapsedX, targetGreenX, buttonGlideProgress)
            btnRedDecline.x = lerp(collapsedX, targetRedX, buttonGlideProgress)
            val glideScale = lerp(0.5f, 1f, buttonGlideProgress)
            btnGreenAnswer.scaleX = glideScale
            btnGreenAnswer.scaleY = glideScale
            btnRedDecline.scaleX = glideScale
            btnRedDecline.scaleY = glideScale
        }
    }

    private fun currentPillWidthPx(): Float {
        val targetExpanded = if (width > 0) width.toFloat() else computeExpandedWidthPx(0)
        return lerp(orbSizePx, targetExpanded, unfoldWidthProgress)
    }

    private fun currentPillHeightPx(): Float {
        return lerp(orbSizePx, pillHeightPx, unfoldHeightProgress)
    }

    // --- 60fps Smoked-Sapphire Glass, Rotating Conic Rim, Wing Glows, Seam Glint, Trails & Chevrons ---

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (visibility != View.VISIBLE || width <= 0 || height <= 0) return

        val centerX = width * 0.5f
        val centerY = height * 0.5f
        val currentW = currentPillWidthPx()
        val currentH = currentPillHeightPx()
        val halfW = currentW * 0.5f
        val halfH = currentH * 0.5f
        val radius = min(halfW, halfH)

        pillRect.set(centerX - halfW, centerY - halfH, centerX + halfW, centerY + halfH)
        innerRect.set(
            pillRect.left + rimStrokePx,
            pillRect.top + rimStrokePx,
            pillRect.right - rimStrokePx,
            pillRect.bottom - rimStrokePx
        )
        val innerRadius = (radius - rimStrokePx).coerceAtLeast(0f)

        // 1. Ambient shell drop shadow + dynamic threshold emerald/crimson bloom
        drawShellShadows(canvas, pillRect, radius)

        // 2. Clipped smoked-sapphire frosted glass interior (`call-pill-inner`)
        pillClipPath.reset()
        pillClipPath.addRoundRect(innerRect, innerRadius, innerRadius, Path.Direction.CW)

        canvas.save()
        canvas.clipPath(pillClipPath)

        // 165deg smoked-sapphire gradient: rgba(22,28,46,0.88) -> rgba(10,14,24,0.94)
        glassInteriorPaint.shader = LinearGradient(
            innerRect.left + innerRect.width() * 0.25f,
            innerRect.top,
            innerRect.left + innerRect.width() * 0.75f,
            innerRect.bottom,
            intArrayOf(
                Color.argb(226, 22, 28, 46),
                Color.argb(242, 10, 14, 24)
            ),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(innerRect, glassInteriorPaint)

        // Left emerald (`12% 50%`) & right crimson (`88% 50%`) subtle wing glows when expanded
        if (wingGlowAlpha > 0.01f && currentMode != PillMode.ACTIVE_CALL) {
            val wingRadius = max(innerRect.height() * 1.45f, innerRect.width() * 0.40f)
            val wingA = (56 * wingGlowAlpha).toInt().coerceIn(0, 72)

            wingTintPaint.shader = RadialGradient(
                innerRect.left + innerRect.width() * 0.12f,
                innerRect.centerY(),
                wingRadius,
                intArrayOf(Color.argb(wingA, 16, 185, 129), Color.TRANSPARENT),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(innerRect, wingTintPaint)

            wingTintPaint.shader = RadialGradient(
                innerRect.left + innerRect.width() * 0.88f,
                innerRect.centerY(),
                wingRadius,
                intArrayOf(Color.argb(wingA, 239, 68, 68), Color.TRANSPARENT),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(innerRect, wingTintPaint)
        }

        // Dynamic luminous glass drag trails (`width = |dx| + 64dp`, `opacity = min(1, progress * 1.4)`)
        if (activeDragSide != DragSide.NONE && currentDragProgress > 0.005f) {
            val trailAlphaFactor = min(1f, currentDragProgress * 1.4f)
            val trailW = (currentDragDeltaPx + 64f * density).coerceAtMost(innerRect.width())
            if (activeDragSide == DragSide.ANSWER_GREEN) {
                trailRect.set(innerRect.left, innerRect.top, innerRect.left + trailW, innerRect.bottom)
                trailPaint.shader = LinearGradient(
                    trailRect.left, trailRect.centerY(),
                    trailRect.right, trailRect.centerY(),
                    intArrayOf(
                        Color.argb((115 * trailAlphaFactor).toInt(), 16, 185, 129),
                        Color.argb((38 * trailAlphaFactor).toInt(), 52, 211, 153)
                    ),
                    floatArrayOf(0f, 1f),
                    Shader.TileMode.CLAMP
                )
            } else {
                trailRect.set(innerRect.right - trailW, innerRect.top, innerRect.right, innerRect.bottom)
                trailPaint.shader = LinearGradient(
                    trailRect.right, trailRect.centerY(),
                    trailRect.left, trailRect.centerY(),
                    intArrayOf(
                        Color.argb((115 * trailAlphaFactor).toInt(), 239, 68, 68),
                        Color.argb((38 * trailAlphaFactor).toInt(), 251, 113, 133)
                    ),
                    floatArrayOf(0f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRoundRect(trailRect, innerRadius, innerRadius, trailPaint)
        }

        // Subtle upper glass sheen + inset bottom depth shadow (`inset 0 -6px 14px rgba(0,0,0,0.55)`)
        topSheenPaint.shader = LinearGradient(
            0f, innerRect.top,
            0f, innerRect.top + innerRect.height() * 0.44f,
            intArrayOf(Color.argb(38, 255, 255, 255), Color.TRANSPARENT),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(innerRect.left, innerRect.top, innerRect.right, innerRect.top + innerRect.height() * 0.44f, topSheenPaint)

        bottomInsetDepthPaint.shader = LinearGradient(
            0f, innerRect.bottom - 14f * density,
            0f, innerRect.bottom,
            intArrayOf(Color.TRANSPARENT, Color.argb(140, 0, 0, 0)),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(innerRect.left, innerRect.bottom - 14f * density, innerRect.right, innerRect.bottom, bottomInsetDepthPaint)

        // Crisp 1dp top inner specular highlight bevel (`inset 0 1px 1px rgba(255,255,255,0.22)`)
        canvas.drawRoundRect(
            innerRect.left + 0.6f * density,
            innerRect.top + 0.6f * density,
            innerRect.right - 0.6f * density,
            innerRect.bottom - 0.6f * density,
            innerRadius,
            innerRadius,
            topInsetHighlightPaint
        )

        // Center-seam platinum glint during Stage 2 unfold (`750ms` flash)
        if (seamGlintPhase in 0f..1f) {
            drawSeamGlint(canvas, innerRect, seamGlintPhase)
        }

        // Stage 3 pulsing directional chevrons (`»` emerald left at 70dp, `«` crimson right at 70dp)
        if (currentMode == PillMode.STAGE_3_DRAG_READY && activeDragSide == DragSide.NONE && !showingNudgeHint) {
            drawDirectionalChevrons(canvas, innerRect)
        }

        // Stage 1 & Collapse Orb Avatar initials (`20sp` bold `#F8FAFC` centered in `68dp × 68dp` orb)
        if (orbAvatarAlpha > 0.01f && currentMode != PillMode.ACTIVE_CALL) {
            drawOrbAvatarInitials(canvas, innerRect.centerX(), innerRect.centerY())
        }

        canvas.restore()

        // 3. Rotating liquid-platinum conic-gradient outer rim (`1.5dp`, 5s linear spin)
        drawLiquidPlatinumConicRim(canvas, pillRect, radius)
    }

    private fun drawShellShadows(canvas: Canvas, rect: RectF, radius: Float) {
        val thresholdGlow = dragCrossedThreshold && activeDragSide != DragSide.NONE
        if (thresholdGlow) {
            val isEmerald = activeDragSide == DragSide.ANSWER_GREEN
            val glowColor = if (isEmerald) Color.argb(140, 52, 211, 153) else Color.argb(140, 251, 113, 133)
            val deepGlow = if (isEmerald) Color.argb(110, 16, 185, 129) else Color.argb(110, 239, 68, 68)
            shellShadowPaint.color = deepGlow
            shellShadowPaint.setShadowLayer(18f * density, 0f, 4f * density, glowColor)
            canvas.drawRoundRect(rect, radius, radius, shellShadowPaint)
            shellShadowPaint.clearShadowLayer()
        } else {
            shellShadowPaint.color = Color.argb(130, 0, 0, 0)
            shellShadowPaint.setShadowLayer(16f * density, 0f, 8f * density, Color.argb(165, 0, 0, 0))
            canvas.drawRoundRect(rect, radius, radius, shellShadowPaint)
            shellShadowPaint.clearShadowLayer()
        }
        canvas.drawRoundRect(rect, radius, radius, outerSubtleRingPaint)
    }

    private fun drawSeamGlint(canvas: Canvas, rect: RectF, phase: Float) {
        val glintAlpha = if (phase < 0.25f) {
            phase / 0.25f
        } else {
            1f - ((phase - 0.25f) / 0.75f)
        }.coerceIn(0f, 1f)
        if (glintAlpha <= 0.01f) return

        val scaleY = if (phase < 0.25f) {
            lerp(0.2f, 1.0f, phase / 0.25f)
        } else {
            lerp(1.0f, 1.2f, (phase - 0.25f) / 0.75f)
        }
        val baseHalfH = rect.height() * 0.42f * scaleY
        val cx = rect.centerX()
        val cy = rect.centerY()

        // Soft 16dp white glow halo
        val haloHalfW = 10f * density
        seamRect.set(cx - haloHalfW, cy - baseHalfH, cx + haloHalfW, cy + baseHalfH)
        seamGlowPaint.shader = LinearGradient(
            seamRect.left, cy, seamRect.right, cy,
            intArrayOf(
                Color.TRANSPARENT,
                Color.argb((180 * glintAlpha).toInt(), 255, 255, 255),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(seamRect, haloHalfW, haloHalfW, seamGlowPaint)

        // Crisp 2dp bright vertical white core (`linear-gradient(to bottom, transparent, #fff, transparent)`)
        val coreHalfW = 1f * density
        seamRect.set(cx - coreHalfW, cy - baseHalfH, cx + coreHalfW, cy + baseHalfH)
        seamCorePaint.shader = LinearGradient(
            cx, seamRect.top, cx, seamRect.bottom,
            intArrayOf(
                Color.TRANSPARENT,
                Color.argb((255 * glintAlpha).toInt(), 255, 255, 255),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(seamRect, seamCorePaint)
    }

    private fun drawOrbAvatarInitials(canvas: Canvas, cx: Float, cy: Float) {
        val a = (orbAvatarAlpha * 255f).toInt().coerceIn(0, 255)
        orbInitialsPaint.alpha = a
        orbInitialsPaint.setShadowLayer(6f * density, 0f, 2f * density, Color.argb((a * 0.5f).toInt(), 0, 0, 0))
        canvas.save()
        canvas.scale(orbAvatarScale, orbAvatarScale, cx, cy)
        val baseline = cy - (orbInitialsPaint.descent() + orbInitialsPaint.ascent()) * 0.5f
        canvas.drawText(callerInitialsText, cx, baseline, orbInitialsPaint)
        canvas.restore()
    }

    private fun drawDirectionalChevrons(canvas: Canvas, rect: RectF) {
        if (rect.width() < 240f * density) return
        // 1.4s ease-in-out pulse matching CSS `@keyframes pulseRight` / `pulseLeft`
        val wave = ((1.0 - kotlin.math.cos(ambientElapsedSec * 2.0 * PI / 1.4)) * 0.5).toFloat()
        val shiftPx = 5f * density * wave
        val chevAlpha = ((0.40f + 0.60f * wave) * 0.85f * 255f).toInt().coerceIn(0, 255)
        val baseline = rect.centerY() - (chevronTextPaint.descent() + chevronTextPaint.ascent()) * 0.5f

        val leftX = rect.left + 72f * density + shiftPx
        chevronTextPaint.color = Color.argb(chevAlpha, 52, 211, 153) // #34D399
        canvas.drawText("»", leftX, baseline, chevronTextPaint)

        val rightX = rect.right - 72f * density - shiftPx
        chevronTextPaint.color = Color.argb(chevAlpha, 251, 113, 133) // #FB7185
        canvas.drawText("«", rightX, baseline, chevronTextPaint)
    }

    private fun drawLiquidPlatinumConicRim(canvas: Canvas, rect: RectF, radius: Float) {
        val rimColors = intArrayOf(
            Color.argb(31, 255, 255, 255),   // rgba(255,255,255,0.12)
            Color.argb(166, 226, 232, 240),  // rgba(226,232,240,0.65)
            Color.argb(46, 148, 163, 184),   // rgba(148,163,184,0.18)
            Color.argb(191, 255, 255, 255),  // rgba(255,255,255,0.75)
            Color.argb(38, 148, 163, 184),   // rgba(148,163,184,0.15)
            Color.argb(31, 255, 255, 255)    // wrap cleanly
        )

        val effectiveColors = when {
            dragCrossedThreshold && activeDragSide == DragSide.ANSWER_GREEN -> intArrayOf(
                Color.argb(80, 16, 185, 129),
                Color.argb(230, 110, 231, 183),
                Color.argb(95, 52, 211, 153),
                Color.argb(245, 209, 250, 229),
                Color.argb(90, 16, 185, 129),
                Color.argb(80, 16, 185, 129)
            )
            dragCrossedThreshold && activeDragSide == DragSide.DECLINE_RED -> intArrayOf(
                Color.argb(80, 239, 68, 68),
                Color.argb(230, 253, 164, 175),
                Color.argb(95, 251, 113, 133),
                Color.argb(245, 255, 228, 230),
                Color.argb(90, 239, 68, 68),
                Color.argb(80, 239, 68, 68)
            )
            else -> rimColors
        }

        val sweep = SweepGradient(rect.centerX(), rect.centerY(), effectiveColors, null)
        shaderMatrix.reset()
        shaderMatrix.setRotate(rimRotationDegrees, rect.centerX(), rect.centerY())
        sweep.setLocalMatrix(shaderMatrix)

        rimBorderPaint.shader = sweep
        val strokeInset = rimStrokePx * 0.5f
        canvas.drawRoundRect(
            rect.left + strokeInset,
            rect.top + strokeInset,
            rect.right - strokeInset,
            rect.bottom - strokeInset,
            (radius - strokeInset).coerceAtLeast(0f),
            (radius - strokeInset).coerceAtLeast(0f),
            rimBorderPaint
        )
    }

    private fun startAmbientLoop() {
        if (ambientLoopAnimator?.isRunning == true) return
        lastAmbientTickMs = android.os.SystemClock.uptimeMillis()
        ambientLoopAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = RIM_SPIN_DURATION_MS
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                val now = android.os.SystemClock.uptimeMillis()
                val dt = ((now - lastAmbientTickMs).coerceIn(0L, 64L)) / 1000f
                lastAmbientTickMs = now
                ambientElapsedSec += dt
                rimRotationDegrees = (anim.animatedValue as Float) * 360f

                if (currentMode == PillMode.ACTIVE_CALL) {
                    livePulseDotView.updatePhaseSeconds(ambientElapsedSec)
                    activeMiniWave.updatePhaseSeconds(ambientElapsedSec)
                } else if (currentMode == PillMode.STAGE_2_UNFOLD || currentMode == PillMode.STAGE_3_DRAG_READY) {
                    incomingMiniWave.updatePhaseSeconds(ambientElapsedSec)
                }
                invalidate()
            }
            start()
        }
    }

    private fun stopAmbientLoop() {
        ambientLoopAnimator?.cancel()
        ambientLoopAnimator = null
    }

    private fun cancelAllAnimations() {
        entranceAnimator?.cancel()
        entranceAnimator = null
        dismissAnimator?.cancel()
        dismissAnimator = null
        dragSnapAnimator?.cancel()
        dragSnapAnimator = null
        nudgeAnimator?.cancel()
        nudgeAnimator = null
    }

    private fun computeCallerInitials(name: String): String {
        val parts = name.trim()
            .split(Regex("\\s+"))
            .filter { token -> token.any { it.isLetter() } }
        return when {
            parts.size >= 2 -> "${parts[0].first { it.isLetter() }}${parts[1].first { it.isLetter() }}".uppercase()
            parts.size == 1 -> parts[0].filter { it.isLetter() }.take(2).uppercase().ifEmpty { "#" }
            else -> "#"
        }
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    companion object {
        const val PILL_HEIGHT_DP = 76f
        const val ORB_SIZE_DP = 68f
        const val COLLAPSED_WIDTH_DP = ORB_SIZE_DP
        const val PILL_CORNER_RADIUS_DP = 38f
        const val JEWEL_BUTTON_SIZE_DP = 52f
        const val MAX_WIDTH_FRACTION = 0.94f
        const val MAX_EXPANDED_WIDTH_DP = 390f
        const val MAX_TABLET_WIDTH_DP = MAX_EXPANDED_WIDTH_DP
        const val PERSPECTIVE_DP = 1100f
        const val DROP_OFFSET_Y_DP = 140f
        const val DROP_START_ROTATION_X = 38f
        const val RETRACT_END_ROTATION_X = 34f
        const val DROP_START_SCALE = 0.80f
        const val STAGE_1_DROP_MS = 460L
        const val STAGE_2_UNFOLD_MS = 920L
        const val STAGE_2_HEIGHT_MS = 700L
        const val STAGE_2_BUTTON_GLIDE_MS = 780L
        const val SEAM_GLINT_MS = 750L
        const val DISMISS_COLLAPSE_MS = 420L
        const val DISMISS_RETRACT_MS = 380L
        const val DISMISS_RETRACT_OPACITY_MS = 280L
        const val RIM_SPIN_DURATION_MS = 5000L
        const val DRAG_TRIGGER_THRESHOLD = 0.55f
        const val OVERLAY_ELEVATION_DP = 28f
        const val OVERLAY_TRANSLATION_Z_DP = 6f
        private const val SHADOW_PADDING_VERTICAL_DP = 14f
    }
}
