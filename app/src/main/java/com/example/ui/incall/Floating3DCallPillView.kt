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
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
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
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.example.R
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/**
 * Compact, ultra-premium 3D Floating Dynamic Island-style Incoming & Active Call Popup.
 *
 * Implements:
 * 1. Compact floating horizontal pill (`76dp` tall, `94%` max screen width, `38dp` corner radius)
 *    with smoked sapphire glass, inner specular highlight, ambient shadow, and rotating
 *    liquid-platinum conic-gradient iridescent rim border.
 * 2. Exact 3-stage entrance choreography:
 *    - Stage 1 (450ms): 3D Top Drop (`64dp × 76dp` center capsule, `translateY(-120dp)`, `rotateX(35deg)`, `scale(0.85)`)
 *      with spring bounce landing.
 *    - Stage 2 (1050ms): Slow Center-to-Sides 3D Unfold (`cubic-bezier(0.22, 1, 0.36, 1)`) with
 *      platinum center-seam glint, emerald/crimson wing tints, glossy 3D `52dp` Green & Red jewel buttons,
 *      caller details, and live animated 5-bar waveform.
 *    - Stage 3: Drag-Ready Idle state with animated directional chevrons (`››` and `‹‹`).
 * 3. Sideways drag-to-answer (Green right) and drag-to-decline (Red left) with luminous glass trail,
 *    dynamic rim glow shift, 55% release threshold with haptic tick, elastic snap-back, and tap-to-nudge hint.
 * 4. Smooth Active "On Call" bar morph (pulsing green dot, caller name, live timer, Mute/Speaker/End)
 *    and clean reverse collapse-to-capsule + top-retract dismiss animation.
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

    // Exact requested dimensions (in px)
    val pillHeightPx: Float = PILL_HEIGHT_DP * density
    val collapsedWidthPx: Float = COLLAPSED_WIDTH_DP * density
    val pillCornerRadiusPx: Float = PILL_CORNER_RADIUS_DP * density
    val jewelButtonSizePx: Int = (JEWEL_BUTTON_SIZE_DP * density).toInt()
    private val dropStartOffsetYPx: Float = -DROP_OFFSET_Y_DP * density
    private val sideInsetPx: Float = 12f * density

    // Animation state (0f = 64dp capsule, 1f = full 94% width)
    private var unfoldProgress: Float = 0f
    private var seamGlintProgress: Float = 0f
    private var rimRotationDegrees: Float = 0f
    private var waveformPhase: Float = 0f
    private var chevronPhase: Float = 0f
    private var liveDotPulse: Float = 0f
    private var dragCrossedThreshold: Boolean = false

    // Cached caller & active-call data
    private var callerNameText: String = context.getString(R.string.incall_unknown_caller)
    private var callerInitialsText: String = computeCallerInitials(callerNameText)
    private var callerSubtitleText: String = context.getString(R.string.incall_status_ringing)
    private var activeTimerText: String = "00:00"
    private var isMutedState: Boolean = false
    private var isSpeakerState: Boolean = false
    private var isBluetoothAudioState: Boolean = false
    private var showingNudgeHint: Boolean = false

    // Drawing geometry & Paints (zero allocations in onDraw for locked 60fps)
    private val pillRect = RectF()
    private val innerClipRect = RectF()
    private val trailRect = RectF()
    private val shaderMatrix = Matrix()

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val glassBasePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = COLOR_SMOKED_SAPPHIRE
    }
    private val glassDepthPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val wingTintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val specularHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val rimBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.85f * density
    }
    private val rimOuterGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4.2f * density
    }
    private val seamGlintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val waveformPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        strokeCap = Paint.Cap.ROUND
    }
    private val chevronPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 2.0f * density
    }
    private val liveDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val liveDotHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val capsuleAvatarFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val capsuleAvatarStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    private val capsuleInitialsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }
    private val capsuleAvatarFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val capsuleAvatarStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    private val capsuleInitialsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }

    // Subviews inside the pill
    private val incomingStageContainer: FrameLayout
    private val centerInfoColumn: LinearLayout
    private val tvCallerName: TextView
    private val tvCallerSubtitle: TextView
    private val tvDragStatus: TextView
    private val btnGreenAnswer: Jewel3DButtonView
    private val btnRedDecline: Jewel3DButtonView

    private val activeCallContainer: LinearLayout
    private val activeCallerColumn: LinearLayout
    private val tvActiveName: TextView
    private val tvActiveTimer: TextView
    private val btnActiveMute: CompactGlassActionButton
    private val btnActiveSpeaker: CompactGlassActionButton
    private val btnActiveEnd: CompactGlassActionButton

    // Animators
    private var entranceAnimator: AnimatorSet? = null
    private var dismissAnimator: AnimatorSet? = null
    private var dragSnapAnimator: ValueAnimator? = null
    private var nudgeAnimator: AnimatorSet? = null
    private var ambientLoopAnimator: ValueAnimator? = null
    private val clearNudgeRunnable = Runnable {
        showingNudgeHint = false
        if (activeDragSide == DragSide.NONE && currentMode == PillMode.STAGE_3_DRAG_READY) {
            tvDragStatus.animate().alpha(0f).setDuration(180L).start()
            centerInfoColumn.animate().alpha(1f).setDuration(200L).start()
        }
    }

    // Gesture tracking
    private var downRawX: Float = 0f
    private var maxAbsDeltaX: Float = 0f
    private var dragCompletedThisGesture: Boolean = false

    init {
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
        visibility = View.GONE

        // 3D perspective: equivalent to CSS perspective: 1000px
        cameraDistance = PERSPECTIVE_DP * density * density

        // --- Incoming Call Interactive Stage ---
        incomingStageContainer = FrameLayout(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, pillHeightPx.toInt(), Gravity.CENTER)
            clipChildren = false
            clipToPadding = false
        }

        centerInfoColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
                marginStart = (JEWEL_BUTTON_SIZE_DP * density + 38f * density).toInt()
                marginEnd = (JEWEL_BUTTON_SIZE_DP * density + 38f * density).toInt()
            }
            // Reserve bottom padding for the compact 5-bar waveform drawn directly on canvas
            setPadding(0, 0, 0, (12f * density).toInt())
            isClickable = true
            isFocusable = true
            setOnClickListener { listener?.onPillTappedWhileActive() }
        }

        tvCallerName = TextView(context).apply {
            text = callerNameText
            setTextColor(Color.WHITE)
            textSize = 14.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            includeFontPadding = false
        }

        tvCallerSubtitle = TextView(context).apply {
            text = callerSubtitleText
            setTextColor(Color.parseColor("#B8D4F6"))
            textSize = 11.2f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(0, (1.5f * density).toInt(), 0, 0)
        }

        centerInfoColumn.addView(tvCallerName)
        centerInfoColumn.addView(tvCallerSubtitle)

        tvDragStatus = TextView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
                marginStart = (JEWEL_BUTTON_SIZE_DP * density + 20f * density).toInt()
                marginEnd = (JEWEL_BUTTON_SIZE_DP * density + 20f * density).toInt()
            }
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            textSize = 13.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            letterSpacing = 0.03f
            setTextColor(Color.WHITE)
            alpha = 0f
        }

        btnGreenAnswer = Jewel3DButtonView(
            context = context,
            isEmerald = true,
            iconRes = R.drawable.ic_call
        ).apply {
            id = R.id.btn_pill_drag_answer
            layoutParams = LayoutParams(jewelButtonSizePx, jewelButtonSizePx, Gravity.CENTER_VERTICAL or Gravity.START)
            contentDescription = context.getString(R.string.incall_answer)
        }

        btnRedDecline = Jewel3DButtonView(
            context = context,
            isEmerald = false,
            iconRes = R.drawable.ic_call_end
        ).apply {
            id = R.id.btn_pill_drag_decline
            layoutParams = LayoutParams(jewelButtonSizePx, jewelButtonSizePx, Gravity.CENTER_VERTICAL or Gravity.END)
            contentDescription = context.getString(R.string.incall_decline)
        }

        incomingStageContainer.addView(centerInfoColumn)
        incomingStageContainer.addView(tvDragStatus)
        incomingStageContainer.addView(btnGreenAnswer)
        incomingStageContainer.addView(btnRedDecline)

        // --- Active "On Call" Compact Bar ---
        activeCallContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, pillHeightPx.toInt(), Gravity.CENTER)
            setPadding((14f * density).toInt(), 0, (12f * density).toInt(), 0)
            visibility = View.GONE
            alpha = 0f
        }

        // Left circular caller-initials avatar badge drawn directly on canvas
        val dotSpacer = View(context).apply {
            layoutParams = LinearLayout.LayoutParams((42f * density).toInt(), (42f * density).toInt()).apply {
                marginEnd = (10f * density).toInt()
            }
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
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
        }

        tvActiveTimer = TextView(context).apply {
            text = activeTimerText
            setTextColor(Color.parseColor("#6EE7B7"))
            textSize = 12f
            typeface = Typeface.MONOSPACE
            maxLines = 1
            includeFontPadding = false
            setPadding(0, (2f * density).toInt(), 0, 0)
        }

        activeCallerColumn.addView(tvActiveName)
        activeCallerColumn.addView(tvActiveTimer)

        val compactBtnSize = (48f * density).toInt()
        val compactEndBtnSize = (48f * density).toInt()

        btnActiveMute = CompactGlassActionButton(context, R.drawable.ic_mic, isDestructive = false).apply {
            id = R.id.btn_pill_active_mute
            layoutParams = LinearLayout.LayoutParams(compactBtnSize, compactBtnSize).apply {
                marginEnd = (2f * density).toInt()
            }
            contentDescription = context.getString(R.string.incall_mute)
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                listener?.onToggleMute()
            }
        }

        btnActiveSpeaker = CompactGlassActionButton(context, R.drawable.ic_volume_up, isDestructive = false).apply {
            id = R.id.btn_pill_active_speaker
            layoutParams = LinearLayout.LayoutParams(compactBtnSize, compactBtnSize).apply {
                marginEnd = (2f * density).toInt()
            }
            contentDescription = context.getString(R.string.incall_speaker)
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                listener?.onToggleSpeaker()
            }
        }

        btnActiveEnd = CompactGlassActionButton(context, R.drawable.ic_call_end, isDestructive = true).apply {
            id = R.id.btn_pill_active_end
            layoutParams = LinearLayout.LayoutParams(compactEndBtnSize, compactEndBtnSize)
            contentDescription = context.getString(R.string.incall_end_call)
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                listener?.onEndCall()
            }
        }

        activeCallContainer.addView(dotSpacer)
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
        if (currentMode != PillMode.HIDDEN) {
            startAmbientLoop()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelAllAnimations()
        removeCallbacks(clearNudgeRunnable)
    }

    /**
     * Computes expanded pill width: `94%` of available width, clamped safely for ultra-narrow & tablet screens.
     */
    fun computeExpandedWidthPx(availableWidthPx: Int): Float {
        if (availableWidthPx <= 0) {
            return (resources.displayMetrics.widthPixels * MAX_WIDTH_FRACTION)
                .coerceAtMost(MAX_TABLET_WIDTH_DP * density)
        }
        val minCompact = (264f * density).coerceAtMost(availableWidthPx.toFloat())
        val maxTablet = MAX_TABLET_WIDTH_DP * density
        return (availableWidthPx * MAX_WIDTH_FRACTION).coerceIn(minCompact, maxTablet)
    }

    /**
     * Updates displayed caller metadata without restarting the entrance animation.
     */
    fun updateCallerInfo(callerName: String, callerLabelOrNumber: String) {
        callerNameText = callerName.ifBlank { context.getString(R.string.incall_unknown_caller) }
        callerInitialsText = computeCallerInitials(callerNameText)
        callerSubtitleText = callerLabelOrNumber.ifBlank { context.getString(R.string.incall_status_ringing) }
        tvCallerName.text = callerNameText
        tvCallerSubtitle.text = callerSubtitleText
        tvActiveName.text = callerNameText
        invalidate()
    }

    /**
     * Updates active call live timer & Mute / Speaker button highlight states.
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
        tvActiveTimer.text = "● $activeTimerText"
        invalidate()

        btnActiveMute.setIconRes(if (isMuted) R.drawable.ic_mic_off else R.drawable.ic_mic)
        btnActiveMute.setActiveHighlight(isMuted, COLOR_MUTE_ACTIVE)
        btnActiveMute.contentDescription = context.getString(
            if (isMuted) R.string.incall_unmute else R.string.incall_mute
        )

        btnActiveSpeaker.setIconRes(if (isBluetooth) R.drawable.ic_bluetooth else R.drawable.ic_volume_up)
        btnActiveSpeaker.setActiveHighlight(isSpeakerOn || isBluetooth, COLOR_EMERALD_GLOW)
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
     * Stage 1 (450ms) -> Stage 2 (1050ms) -> Stage 3 (Drag-Ready Idle).
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

        visibility = View.VISIBLE
        incomingStageContainer.visibility = View.VISIBLE
        incomingStageContainer.alpha = 1f
        activeCallContainer.visibility = View.GONE
        activeCallContainer.alpha = 0f

        centerInfoColumn.alpha = 0f
        tvDragStatus.alpha = 0f
        btnGreenAnswer.alpha = 0f
        btnRedDecline.alpha = 0f
        unfoldProgress = 0f
        seamGlintProgress = 0f

        // Stage 1 starting 3D capsule pose
        currentMode = PillMode.STAGE_1_DROP
        translationY = dropStartOffsetYPx
        rotationX = DROP_START_ROTATION_X
        scaleX = DROP_START_SCALE
        scaleY = DROP_START_SCALE
        alpha = 0.05f

        setLayerType(LAYER_TYPE_HARDWARE, null)
        startAmbientLoop()
        updateJewelRestingPositions()
        invalidate()

        // Stage 1: 3D Top Drop (~450ms) with spring bounce landing
        val stage1Drop = AnimatorSet().apply {
            duration = STAGE_1_DROP_MS
            interpolator = OvershootInterpolator(1.18f)
            playTogether(
                ObjectAnimator.ofFloat(this@Floating3DCallPillView, View.TRANSLATION_Y, dropStartOffsetYPx, 0f),
                ObjectAnimator.ofFloat(this@Floating3DCallPillView, View.ROTATION_X, DROP_START_ROTATION_X, 0f),
                ObjectAnimator.ofFloat(this@Floating3DCallPillView, View.SCALE_X, DROP_START_SCALE, 1f),
                ObjectAnimator.ofFloat(this@Floating3DCallPillView, View.SCALE_Y, DROP_START_SCALE, 1f),
                ObjectAnimator.ofFloat(this@Floating3DCallPillView, View.ALPHA, 0.05f, 1f)
            )
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (currentMode == PillMode.STAGE_1_DROP) {
                        currentMode = PillMode.STAGE_2_UNFOLD
                    }
                }
            })
        }

        // Stage 2: Slow Center-to-Sides 3D Unfold (~1050ms) cubic-bezier(0.22, 1, 0.36, 1)
        val stage2Unfold = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = STAGE_2_UNFOLD_MS
            interpolator = PathInterpolator(0.22f, 1f, 0.36f, 1f)
            addUpdateListener { anim ->
                val p = anim.animatedValue as Float
                unfoldProgress = p
                // Center-seam platinum glint rises swiftly and fades smoothly across unfold
                seamGlintProgress = when {
                    p < 0.25f -> p / 0.25f
                    p < 0.80f -> 1f - ((p - 0.25f) / 0.55f)
                    else -> 0f
                }.coerceIn(0f, 1f)

                val jewelAlpha = ((p - 0.08f) / 0.35f).coerceIn(0f, 1f)
                btnGreenAnswer.alpha = jewelAlpha
                btnRedDecline.alpha = jewelAlpha
                val jewelScale = 0.72f + 0.28f * jewelAlpha
                btnGreenAnswer.scaleX = jewelScale
                btnGreenAnswer.scaleY = jewelScale
                btnRedDecline.scaleX = jewelScale
                btnRedDecline.scaleY = jewelScale

                centerInfoColumn.alpha = ((p - 0.38f) / 0.48f).coerceIn(0f, 1f)
                updateJewelRestingPositions()
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (currentMode == PillMode.STAGE_2_UNFOLD || currentMode == PillMode.STAGE_1_DROP) {
                        unfoldProgress = 1f
                        seamGlintProgress = 0f
                        centerInfoColumn.alpha = 1f
                        btnGreenAnswer.alpha = 1f
                        btnRedDecline.alpha = 1f
                        btnGreenAnswer.scaleX = 1f
                        btnGreenAnswer.scaleY = 1f
                        btnRedDecline.scaleX = 1f
                        btnRedDecline.scaleY = 1f
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
     * If the pill was hidden (e.g. outgoing call), drops it smoothly directly into Active Bar state.
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
        visibility = View.VISIBLE
        unfoldProgress = 1f
        seamGlintProgress = 0f
        startAmbientLoop()

        activeCallContainer.visibility = View.VISIBLE
        if (wasHidden) {
            incomingStageContainer.visibility = View.GONE
            activeCallContainer.alpha = 1f
            translationY = dropStartOffsetYPx
            rotationX = DROP_START_ROTATION_X
            scaleX = 0.90f
            scaleY = 0.90f
            alpha = 0f
            animate()
                .translationY(0f)
                .rotationX(0f)
                .scaleX(1f)
                .scaleY(1f)
                .alpha(1f)
                .setDuration(380L)
                .setInterpolator(OvershootInterpolator(1.08f))
                .start()
        } else {
            translationY = 0f
            rotationX = 0f
            scaleX = 1f
            scaleY = 1f
            alpha = 1f
            incomingStageContainer.animate()
                .alpha(0f)
                .setDuration(180L)
                .withEndAction {
                    if (currentMode == PillMode.ACTIVE_CALL) {
                        incomingStageContainer.visibility = View.GONE
                    }
                }
                .start()
            activeCallContainer.alpha = 0f
            activeCallContainer.scaleX = 0.95f
            activeCallContainer.scaleY = 0.95f
            activeCallContainer.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(280L)
                .setInterpolator(PathInterpolator(0.22f, 1f, 0.36f, 1f))
                .start()
        }
        invalidate()
    }

    /**
     * Reverses the animation cleanly on Decline / End Call:
     * 1. Collapses both sides symmetrically back into the `64dp` center capsule.
     * 2. Retracts upward out of the top of the screen (`translateY(-120dp)`, `rotateX(35deg)`, `scale(0.85)`).
     */
    fun dismissWithReverseAnimation(onFinished: (() -> Unit)? = null) {
        if (currentMode == PillMode.HIDDEN) {
            onFinished?.invoke()
            return
        }
        if (currentMode == PillMode.DISMISSING) return

        cancelAllAnimations()
        removeCallbacks(clearNudgeRunnable)
        currentMode = PillMode.DISMISSING
        setLayerType(LAYER_TYPE_HARDWARE, null)

        val startUnfold = unfoldProgress.coerceIn(0f, 1f)
        val collapseAnim = ValueAnimator.ofFloat(startUnfold, 0f).apply {
            duration = DISMISS_COLLAPSE_MS
            interpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f)
            addUpdateListener { anim ->
                val p = anim.animatedValue as Float
                unfoldProgress = p
                val contentAlpha = ((p - 0.25f) / 0.55f).coerceIn(0f, 1f)
                incomingStageContainer.alpha = contentAlpha
                activeCallContainer.alpha = contentAlpha
                activeDragSide = DragSide.NONE
                currentDragProgress = 0f
                updateJewelRestingPositions()
                invalidate()
            }
        }

        val retractUpAnim = AnimatorSet().apply {
            duration = DISMISS_RETRACT_MS
            interpolator = PathInterpolator(0.4f, 0f, 1f, 1f)
            playTogether(
                ObjectAnimator.ofFloat(this@Floating3DCallPillView, View.TRANSLATION_Y, translationY, dropStartOffsetYPx),
                ObjectAnimator.ofFloat(this@Floating3DCallPillView, View.ROTATION_X, rotationX, DROP_START_ROTATION_X),
                ObjectAnimator.ofFloat(this@Floating3DCallPillView, View.SCALE_X, scaleX, DROP_START_SCALE),
                ObjectAnimator.ofFloat(this@Floating3DCallPillView, View.SCALE_Y, scaleY, DROP_START_SCALE),
                ObjectAnimator.ofFloat(this@Floating3DCallPillView, View.ALPHA, alpha, 0f)
            )
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
        unfoldProgress = 0f
        resetDragVisualsImmediate()
        stopAmbientLoop()
    }

    // --- Sideways Drag-to-Pick & Drag-to-Decline Physics ---

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
                    dragCompletedThisGesture = false
                    dragCrossedThreshold = false
                    activeDragSide = side
                    currentDragProgress = 0f
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    v.animate().scaleX(1.06f).scaleY(1.06f).setDuration(100L).start()
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
                    if (maxAbsDeltaX > touchSlop * 0.45f) {
                        applyDragDisplacement(side, directedDelta)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    v.animate().scaleX(1f).scaleY(1f).setDuration(150L).start()
                    val isCancel = event.actionMasked == MotionEvent.ACTION_CANCEL
                    if (!isCancel && maxAbsDeltaX < touchSlop) {
                        // Tap without dragging -> Tap-to-Nudge hint!
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

    /**
     * Programmatic / testable drag evaluator that updates trail, rim glow, fading, and 55% status threshold.
     */
    internal fun applyDragProgressForTestOrGesture(side: DragSide, progress: Float) {
        activeDragSide = side
        val clamped = progress.coerceIn(0f, 1f)
        val maxTravel = computeMaxDragTravelPx()
        applyDragDisplacement(side, clamped * maxTravel)
    }

    private fun computeMaxDragTravelPx(): Float {
        val currentWidth = currentPillWidthPx()
        return (currentWidth - jewelButtonSizePx - sideInsetPx * 2f).coerceAtLeast(1f)
    }

    private fun applyDragDisplacement(side: DragSide, directedDeltaPx: Float) {
        val maxTravel = computeMaxDragTravelPx()
        val clampedPx = directedDeltaPx.coerceIn(0f, maxTravel)
        val progress = (clampedPx / maxTravel).coerceIn(0f, 1f)
        currentDragProgress = progress

        val baseGreenX = sideInsetPx
        val baseRedX = (width - sideInsetPx - jewelButtonSizePx).coerceAtLeast(sideInsetPx)

        if (side == DragSide.ANSWER_GREEN) {
            btnGreenAnswer.x = baseGreenX + clampedPx
            btnRedDecline.alpha = (1f - progress * 1.9f).coerceIn(0f, 1f)
            btnRedDecline.scaleX = (1f - progress * 0.25f).coerceIn(0.75f, 1f)
            btnRedDecline.scaleY = (1f - progress * 0.25f).coerceIn(0.75f, 1f)
        } else {
            btnRedDecline.x = baseRedX - clampedPx
            btnGreenAnswer.alpha = (1f - progress * 1.9f).coerceIn(0f, 1f)
            btnGreenAnswer.scaleX = (1f - progress * 0.25f).coerceIn(0.75f, 1f)
            btnGreenAnswer.scaleY = (1f - progress * 0.25f).coerceIn(0.75f, 1f)
        }

        // Smoothly fade out center caller info & waveform as drag increases
        centerInfoColumn.alpha = (1f - progress * 2.2f).coerceIn(0f, 1f)

        // Show dynamic status text switching at 55% threshold
        val crossedNow = progress >= DRAG_TRIGGER_THRESHOLD
        if (crossedNow && !dragCrossedThreshold) {
            dragCrossedThreshold = true
            triggerThresholdTickFeedback()
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
                side == DragSide.ANSWER_GREEN && crossedNow -> Color.parseColor("#A7F3D0")
                side == DragSide.DECLINE_RED && crossedNow -> Color.parseColor("#FECDD3")
                else -> Color.WHITE
            }
        )
        tvDragStatus.alpha = ((progress - 0.04f) / 0.22f).coerceIn(0f, 1f)
        invalidate()
    }

    private fun triggerThresholdTickFeedback() {
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        playSoundEffect(SoundEffectConstants.CLICK)
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (vibrator?.hasVibrator() == true) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(18L, VibrationEffect.DEFAULT_AMPLITUDE))
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
        tvDragStatus.setTextColor(
            if (side == DragSide.ANSWER_GREEN) Color.parseColor("#6EE7B7") else Color.parseColor("#FDA4AF")
        )
        centerInfoColumn.animate().alpha(0.15f).setDuration(140L).start()
        tvDragStatus.animate().alpha(1f).setDuration(140L).start()

        val targetView = if (side == DragSide.ANSWER_GREEN) btnGreenAnswer else btnRedDecline
        val nudgeDelta = (if (side == DragSide.ANSWER_GREEN) 18f else -18f) * density

        val nudgeOut = ObjectAnimator.ofFloat(targetView, View.TRANSLATION_X, 0f, nudgeDelta).apply {
            duration = 160L
            interpolator = PathInterpolator(0.22f, 1f, 0.36f, 1f)
        }
        val nudgeBack = ObjectAnimator.ofFloat(targetView, View.TRANSLATION_X, nudgeDelta, 0f).apply {
            duration = 260L
            interpolator = OvershootInterpolator(1.6f)
        }

        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        nudgeAnimator = AnimatorSet().apply {
            playSequentially(nudgeOut, nudgeBack)
            start()
        }
        postDelayed(clearNudgeRunnable, 1250L)
    }

    private fun snapBackFromDrag(side: DragSide) {
        val startProgress = currentDragProgress
        dragSnapAnimator?.cancel()
        dragSnapAnimator = ValueAnimator.ofFloat(startProgress, 0f).apply {
            duration = 280L
            interpolator = OvershootInterpolator(1.35f)
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
        if (side == DragSide.ANSWER_GREEN) {
            listener?.onAnswerCall()
        } else if (side == DragSide.DECLINE_RED) {
            listener?.onDeclineCall()
        }
    }

    private fun resetDragVisualsImmediate() {
        activeDragSide = DragSide.NONE
        currentDragProgress = 0f
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
        val currentWidth = currentPillWidthPx()
        val pillLeft = centerX - currentWidth * 0.5f
        val pillRight = centerX + currentWidth * 0.5f

        val targetGreenX = (pillLeft + sideInsetPx).coerceAtLeast(0f)
        val targetRedX = (pillRight - sideInsetPx - jewelButtonSizePx).coerceAtLeast(0f)
        val collapsedX = centerX - halfJewel

        if (activeDragSide == DragSide.NONE) {
            btnGreenAnswer.x = lerp(collapsedX, targetGreenX, unfoldProgress)
            btnRedDecline.x = lerp(collapsedX, targetRedX, unfoldProgress)
        }
    }

    private fun currentPillWidthPx(): Float {
        val targetExpanded = if (width > 0) width.toFloat() else computeExpandedWidthPx(0)
        return lerp(collapsedWidthPx, targetExpanded, unfoldProgress)
    }

    // --- 60fps GPU-Accelerated Glassmorphism, Iridescent Rim, Wing Tints, Trails, Waveform & Chevrons ---

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (visibility != View.VISIBLE || width <= 0 || height <= 0) return

        val centerX = width * 0.5f
        val topPad = SHADOW_PADDING_VERTICAL_DP * density
        val currentW = currentPillWidthPx()
        val left = centerX - currentW * 0.5f
        val right = centerX + currentW * 0.5f
        val bottom = topPad + pillHeightPx
        val radius = pillCornerRadiusPx.coerceAtMost(currentW * 0.5f)

        pillRect.set(left, topPad, right, bottom)

        // 1. Soft deep ambient drop shadow halo behind the floating pill
        val shadowOffsetY = 6f * density
        shadowPaint.color = Color.argb((110 * alpha).toInt().coerceIn(0, 140), 2, 5, 14)
        shadowPaint.setShadowLayer(16f * density, 0f, shadowOffsetY, Color.argb(155, 2, 6, 18))
        canvas.drawRoundRect(pillRect, radius, radius, shadowPaint)
        shadowPaint.clearShadowLayer()

        // 2. Multi-layered Smoked Sapphire Glass fill (`rgba(15, 18, 28, 0.78)` + depth gradient)
        canvas.drawRoundRect(pillRect, radius, radius, glassBasePaint)

        glassDepthPaint.shader = LinearGradient(
            pillRect.left, pillRect.top,
            pillRect.right, pillRect.bottom,
            intArrayOf(
                Color.argb(155, 24, 34, 58),
                Color.argb(205, 13, 17, 29),
                Color.argb(180, 20, 26, 46)
            ),
            floatArrayOf(0f, 0.52f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(pillRect, radius, radius, glassDepthPaint)

        // 3. Subtle 3D Hinged Wing Tints (Emerald left wing + Crimson right wing) when unfolded
        if (currentMode != PillMode.ACTIVE_CALL && unfoldProgress > 0.2f) {
            val wingStrength = ((unfoldProgress - 0.2f) / 0.8f).coerceIn(0f, 1f)
            val emeraldAlpha = (36 * wingStrength * (1f + if (activeDragSide == DragSide.ANSWER_GREEN) currentDragProgress else 0f))
                .toInt().coerceIn(0, 95)
            wingTintPaint.shader = RadialGradient(
                pillRect.left + jewelButtonSizePx * 0.85f,
                pillRect.centerY(),
                pillHeightPx * 1.45f,
                intArrayOf(Color.argb(emeraldAlpha, 16, 185, 129), Color.TRANSPARENT),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(pillRect, radius, radius, wingTintPaint)

            val crimsonAlpha = (36 * wingStrength * (1f + if (activeDragSide == DragSide.DECLINE_RED) currentDragProgress else 0f))
                .toInt().coerceIn(0, 95)
            wingTintPaint.shader = RadialGradient(
                pillRect.right - jewelButtonSizePx * 0.85f,
                pillRect.centerY(),
                pillHeightPx * 1.45f,
                intArrayOf(Color.argb(crimsonAlpha, 239, 68, 68), Color.TRANSPARENT),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(pillRect, radius, radius, wingTintPaint)
        }

        // 4. Luminous Emerald / Crimson Glass Drag Trail proportional to drag distance
        if (activeDragSide != DragSide.NONE && currentDragProgress > 0.01f) {
            val trailPad = 6f * density
            val trailRadius = (pillHeightPx - trailPad * 2f) * 0.5f
            if (activeDragSide == DragSide.ANSWER_GREEN) {
                val trailRight = (btnGreenAnswer.x + jewelButtonSizePx + 6f * density)
                    .coerceAtMost(pillRect.right - trailPad)
                trailRect.set(pillRect.left + trailPad, pillRect.top + trailPad, trailRight, pillRect.bottom - trailPad)
                trailPaint.shader = LinearGradient(
                    trailRect.left, trailRect.centerY(),
                    trailRect.right, trailRect.centerY(),
                    intArrayOf(
                        Color.argb((45 + 95 * currentDragProgress).toInt(), 5, 150, 105),
                        Color.argb((110 + 115 * currentDragProgress).toInt(), 16, 185, 129),
                        Color.argb((140 + 90 * currentDragProgress).toInt(), 52, 211, 153)
                    ),
                    floatArrayOf(0f, 0.6f, 1f),
                    Shader.TileMode.CLAMP
                )
            } else {
                val trailLeft = (btnRedDecline.x - 6f * density)
                    .coerceAtLeast(pillRect.left + trailPad)
                trailRect.set(trailLeft, pillRect.top + trailPad, pillRect.right - trailPad, pillRect.bottom - trailPad)
                trailPaint.shader = LinearGradient(
                    trailRect.right, trailRect.centerY(),
                    trailRect.left, trailRect.centerY(),
                    intArrayOf(
                        Color.argb((45 + 95 * currentDragProgress).toInt(), 185, 28, 28),
                        Color.argb((110 + 115 * currentDragProgress).toInt(), 239, 68, 68),
                        Color.argb((140 + 90 * currentDragProgress).toInt(), 251, 113, 133)
                    ),
                    floatArrayOf(0f, 0.6f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRoundRect(trailRect, trailRadius, trailRadius, trailPaint)
        }

        // 5. Inner Specular Top Glass Highlight (3D glossy bevel)
        innerClipRect.set(pillRect.left + 2f * density, pillRect.top + 1.5f * density, pillRect.right - 2f * density, pillRect.top + pillHeightPx * 0.48f)
        specularHighlightPaint.shader = LinearGradient(
            0f, innerClipRect.top,
            0f, innerClipRect.bottom,
            intArrayOf(
                Color.argb(66, 255, 255, 255),
                Color.argb(14, 224, 242, 254),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(innerClipRect, radius * 0.88f, radius * 0.65f, specularHighlightPaint)

        // 6. Metallic Platinum Center-Seam Glint during Stage 2 3D Unfold
        if (seamGlintProgress > 0.01f) {
            val glintHalfW = (18f * density) * (0.4f + 0.6f * seamGlintProgress)
            val seamRect = RectF(centerX - glintHalfW, pillRect.top + 4f * density, centerX + glintHalfW, pillRect.bottom - 4f * density)
            val peakAlpha = (210 * seamGlintProgress).toInt().coerceIn(0, 225)
            seamGlintPaint.shader = LinearGradient(
                seamRect.left, seamRect.centerY(),
                seamRect.right, seamRect.centerY(),
                intArrayOf(
                    Color.TRANSPARENT,
                    Color.argb(peakAlpha, 226, 232, 240),
                    Color.argb(peakAlpha, 255, 255, 255),
                    Color.argb(peakAlpha, 186, 230, 253),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.35f, 0.5f, 0.65f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(seamRect, glintHalfW, glintHalfW, seamGlintPaint)
        }

        // 7. Compact Animated 5-Bar Live Audio Waveform in center space (Incoming Call Stage 2/3)
        if ((currentMode == PillMode.STAGE_2_UNFOLD || currentMode == PillMode.STAGE_3_DRAG_READY) &&
            centerInfoColumn.alpha > 0.05f
        ) {
            drawCompactLiveWaveform(canvas, centerX, pillRect.bottom - 10.5f * density, centerInfoColumn.alpha)
        }

        // 8. Stage 3 Animated Directional Chevrons (`››` emerald right, `‹‹` crimson left)
        if (currentMode == PillMode.STAGE_3_DRAG_READY && activeDragSide == DragSide.NONE && !showingNudgeHint) {
            drawDirectionalChevrons(canvas, pillRect.centerY())
        }

        // 8b. Circular Caller Initials Badge inside Collapsed Capsule during Stage 1 Drop, Early Unfold & Retract
        if (currentMode != PillMode.ACTIVE_CALL && unfoldProgress < 0.36f) {
            val badgeAlpha = (1f - (unfoldProgress / 0.36f)).coerceIn(0f, 1f)
            drawCircularInitialsBadge(
                canvas = canvas,
                cx = centerX,
                cy = pillRect.centerY(),
                radiusPx = 22f * density,
                alphaFraction = badgeAlpha
            )
        }

        // 9. Circular Caller Initials Badge + Pulsing Emerald Live Halo in Active "On Call" Bar
        if (currentMode == PillMode.ACTIVE_CALL) {
            val badgeRadius = 20f * density
            val badgeCx = pillRect.left + 14f * density + badgeRadius
            val badgeCy = pillRect.centerY()
            drawCircularInitialsBadge(
                canvas = canvas,
                cx = badgeCx,
                cy = badgeCy,
                radiusPx = badgeRadius,
                alphaFraction = activeCallContainer.alpha.coerceIn(0f, 1f)
            )
            // Subtle pulsing emerald active-call ring dot at bottom-right of initials avatar
            val dotCx = badgeCx + badgeRadius * 0.68f
            val dotCy = badgeCy + badgeRadius * 0.68f
            val haloRadius = (3.8f + 4.0f * liveDotPulse) * density
            liveDotHaloPaint.color = Color.argb(((1f - liveDotPulse) * 145).toInt().coerceIn(0, 150), 52, 211, 153)
            canvas.drawCircle(dotCx, dotCy, haloRadius, liveDotHaloPaint)
            liveDotPaint.color = Color.parseColor("#34D399")
            canvas.drawCircle(dotCx, dotCy, 3.6f * density, liveDotPaint)
        }

        // 10. Animated Liquid-Platinum Iridescent Conic-Gradient Rim Border (shifts to Emerald / Crimson on drag)
        drawIridescentConicRim(canvas, pillRect, radius)
    }

    private fun drawCircularInitialsBadge(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radiusPx: Float,
        alphaFraction: Float
    ) {
        if (alphaFraction <= 0.01f) return
        val a = (alphaFraction * 255f).toInt().coerceIn(0, 255)
        capsuleAvatarFillPaint.shader = RadialGradient(
            cx - radiusPx * 0.25f,
            cy - radiusPx * 0.25f,
            radiusPx * 1.45f,
            intArrayOf(
                Color.argb((a * 0.92f).toInt(), 37, 99, 235),
                Color.argb((a * 0.95f).toInt(), 23, 37, 84)
            ),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, radiusPx, capsuleAvatarFillPaint)

        capsuleAvatarStrokePaint.color = Color.argb((a * 0.72f).toInt(), 147, 197, 253)
        canvas.drawCircle(cx, cy, radiusPx - 0.75f * density, capsuleAvatarStrokePaint)

        capsuleInitialsPaint.textSize = radiusPx * 0.78f
        capsuleInitialsPaint.alpha = a
        val textBaseline = cy - (capsuleInitialsPaint.descent() + capsuleInitialsPaint.ascent()) * 0.5f
        canvas.drawText(callerInitialsText, cx, textBaseline, capsuleInitialsPaint)
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

    private fun drawCompactLiveWaveform(canvas: Canvas, cx: Float, baselineY: Float, alphaFactor: Float) {
        val barCount = 5
        val barWidth = 2.6f * density
        val barGap = 4.2f * density
        val totalW = barCount * barWidth + (barCount - 1) * barGap
        var startX = cx - totalW * 0.5f

        for (i in 0 until barCount) {
            val wave = sin(waveformPhase + i * 0.95).toFloat()
            val norm = (wave + 1f) * 0.5f
            val centerWeight = if (i == 2) 1.0f else if (i == 1 || i == 3) 0.82f else 0.62f
            val h = (3.5f + 6.5f * norm * centerWeight) * density
            val barColor = ColorUtils.blendARGB(
                Color.parseColor("#34D399"),
                Color.parseColor("#7DD3FC"),
                i / (barCount - 1f)
            )
            waveformPaint.color = ColorUtils.setAlphaComponent(barColor, (220 * alphaFactor).toInt().coerceIn(0, 225))
            val r = barWidth * 0.5f
            canvas.drawRoundRect(startX, baselineY - h * 0.5f, startX + barWidth, baselineY + h * 0.5f, r, r, waveformPaint)
            startX += barWidth + barGap
        }
    }

    private fun drawDirectionalChevrons(canvas: Canvas, cy: Float) {
        val chevSize = 4.6f * density
        val chevGap = 7.5f * density
        val greenStart = btnGreenAnswer.x + jewelButtonSizePx + 7f * density
        val redStart = btnRedDecline.x - 7f * density

        // Only draw if there is ample horizontal breathing room
        if (redStart - greenStart < 72f * density) return

        // Emerald ›› pointing right beside Green button
        for (i in 0 until 2) {
            val wave = ((sin(chevronPhase - i * 1.15) + 1.0) * 0.5).toFloat()
            val x = greenStart + i * chevGap + wave * 2.5f * density
            chevronPaint.color = Color.argb((75 + 165 * wave).toInt().coerceIn(0, 240), 52, 211, 153)
            canvas.drawLine(x - chevSize * 0.5f, cy - chevSize, x + chevSize * 0.5f, cy, chevronPaint)
            canvas.drawLine(x + chevSize * 0.5f, cy, x - chevSize * 0.5f, cy + chevSize, chevronPaint)
        }

        // Crimson ‹‹ pointing left beside Red button
        for (i in 0 until 2) {
            val wave = ((sin(chevronPhase - i * 1.15) + 1.0) * 0.5).toFloat()
            val x = redStart - i * chevGap - wave * 2.5f * density
            chevronPaint.color = Color.argb((75 + 165 * wave).toInt().coerceIn(0, 240), 251, 113, 133)
            canvas.drawLine(x + chevSize * 0.5f, cy - chevSize, x - chevSize * 0.5f, cy, chevronPaint)
            canvas.drawLine(x - chevSize * 0.5f, cy, x + chevSize * 0.5f, cy + chevSize, chevronPaint)
        }
    }

    private fun drawIridescentConicRim(canvas: Canvas, rect: RectF, radius: Float) {
        val baseColors = intArrayOf(
            Color.parseColor("#E2E8F0"), // Platinum
            Color.parseColor("#7DD3FC"), // Ice Sapphire
            Color.parseColor("#FFFFFF"), // Specular Glint
            Color.parseColor("#475569"), // Smoked Slate
            Color.parseColor("#CBD5E1"), // Silver Mist
            Color.parseColor("#A5F3FC"), // Iridescent Cyan
            Color.parseColor("#E2E8F0")  // Wrap smoothly
        )

        val effectiveColors = when (activeDragSide) {
            DragSide.ANSWER_GREEN -> {
                val blend = (0.35f + 0.65f * currentDragProgress).coerceIn(0f, 1f)
                intArrayOf(
                    ColorUtils.blendARGB(baseColors[0], Color.parseColor("#34D399"), blend),
                    ColorUtils.blendARGB(baseColors[1], Color.parseColor("#10B981"), blend),
                    ColorUtils.blendARGB(baseColors[2], Color.parseColor("#D1FAE5"), blend),
                    ColorUtils.blendARGB(baseColors[3], Color.parseColor("#059669"), blend),
                    ColorUtils.blendARGB(baseColors[4], Color.parseColor("#6EE7B7"), blend),
                    ColorUtils.blendARGB(baseColors[5], Color.parseColor("#10B981"), blend),
                    ColorUtils.blendARGB(baseColors[6], Color.parseColor("#34D399"), blend)
                )
            }
            DragSide.DECLINE_RED -> {
                val blend = (0.35f + 0.65f * currentDragProgress).coerceIn(0f, 1f)
                intArrayOf(
                    ColorUtils.blendARGB(baseColors[0], Color.parseColor("#FB7185"), blend),
                    ColorUtils.blendARGB(baseColors[1], Color.parseColor("#EF4444"), blend),
                    ColorUtils.blendARGB(baseColors[2], Color.parseColor("#FFE4E6"), blend),
                    ColorUtils.blendARGB(baseColors[3], Color.parseColor("#B91C1C"), blend),
                    ColorUtils.blendARGB(baseColors[4], Color.parseColor("#FDA4AF"), blend),
                    ColorUtils.blendARGB(baseColors[5], Color.parseColor("#EF4444"), blend),
                    ColorUtils.blendARGB(baseColors[6], Color.parseColor("#FB7185"), blend)
                )
            }
            DragSide.NONE -> {
                if (currentMode == PillMode.ACTIVE_CALL) {
                    intArrayOf(
                        Color.parseColor("#6EE7B7"),
                        Color.parseColor("#E2E8F0"),
                        Color.parseColor("#34D399"),
                        Color.parseColor("#475569"),
                        Color.parseColor("#CBD5E1"),
                        Color.parseColor("#6EE7B7"),
                        Color.parseColor("#6EE7B7")
                    )
                } else {
                    baseColors
                }
            }
        }

        val sweep = SweepGradient(rect.centerX(), rect.centerY(), effectiveColors, null)
        shaderMatrix.reset()
        shaderMatrix.setRotate(rimRotationDegrees, rect.centerX(), rect.centerY())
        sweep.setLocalMatrix(shaderMatrix)

        // Soft outer luminous glow halo during drag or active call
        if (activeDragSide != DragSide.NONE || currentMode == PillMode.STAGE_1_DROP) {
            rimOuterGlowPaint.shader = sweep
            rimOuterGlowPaint.alpha = if (activeDragSide != DragSide.NONE) {
                (70 + 100 * currentDragProgress).toInt().coerceIn(0, 175)
            } else {
                95
            }
            canvas.drawRoundRect(rect, radius, radius, rimOuterGlowPaint)
        }

        rimBorderPaint.shader = sweep
        rimBorderPaint.alpha = 235
        canvas.drawRoundRect(rect, radius, radius, rimBorderPaint)
    }

    private fun startAmbientLoop() {
        if (ambientLoopAnimator?.isRunning == true) return
        ambientLoopAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 3600L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                val t = anim.animatedValue as Float
                rimRotationDegrees = t * 360f
                waveformPhase = (t * 6.0 * PI).toFloat()
                chevronPhase = (t * 4.0 * PI).toFloat()
                liveDotPulse = (t * 2f) % 1f
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

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    /**
     * Sculpted 3D Circular Jewel Button (`52dp × 52dp`) — Glossy Emerald Green or Ruby Red
     * with specular dome highlight, radial inner light, and metallic bevel stroke.
     */
    private class Jewel3DButtonView(
        context: Context,
        private val isEmerald: Boolean,
        iconRes: Int
    ) : FrameLayout(context) {

        private val d = resources.displayMetrics.density
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val glossPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.6f * d
        }
        private val glossRect = RectF()

        init {
            setWillNotDraw(false)
            isClickable = true
            isFocusable = true
            elevation = 8f * d

            val icon = ImageView(context).apply {
                layoutParams = LayoutParams((24f * d).toInt(), (24f * d).toInt(), Gravity.CENTER)
                setImageResource(iconRes)
                setColorFilter(Color.WHITE)
            }
            addView(icon)
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width * 0.5f
            val cy = height * 0.5f
            val r = (minOf(width, height) * 0.5f) - 1.5f * d

            // 3D Sculpted Radial Jewel Fill
            fillPaint.shader = if (isEmerald) {
                RadialGradient(
                    cx - r * 0.25f, cy - r * 0.30f, r * 1.35f,
                    intArrayOf(
                        Color.parseColor("#6EE7B7"),
                        Color.parseColor("#10B981"),
                        Color.parseColor("#047857"),
                        Color.parseColor("#064E3B")
                    ),
                    floatArrayOf(0f, 0.35f, 0.78f, 1f),
                    Shader.TileMode.CLAMP
                )
            } else {
                RadialGradient(
                    cx - r * 0.25f, cy - r * 0.30f, r * 1.35f,
                    intArrayOf(
                        Color.parseColor("#FDA4AF"),
                        Color.parseColor("#EF4444"),
                        Color.parseColor("#B91C1C"),
                        Color.parseColor("#7F1D1D")
                    ),
                    floatArrayOf(0f, 0.35f, 0.78f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawCircle(cx, cy, r, fillPaint)

            // Top Glossy Specular Dome Reflection
            glossRect.set(cx - r * 0.68f, cy - r * 0.85f, cx + r * 0.68f, cy - r * 0.05f)
            glossPaint.shader = LinearGradient(
                0f, glossRect.top, 0f, glossRect.bottom,
                intArrayOf(Color.argb(145, 255, 255, 255), Color.argb(12, 255, 255, 255)),
                null,
                Shader.TileMode.CLAMP
            )
            canvas.drawOval(glossRect, glossPaint)

            // Crisp Metallic Rim Highlight
            rimPaint.color = if (isEmerald) {
                Color.argb(215, 167, 243, 208)
            } else {
                Color.argb(215, 254, 205, 211)
            }
            canvas.drawCircle(cx, cy, r, rimPaint)
            super.onDraw(canvas)
        }
    }

    /**
     * Compact 3D Glass Circular Action Button used inside the Active "On Call" Bar (Mute, Speaker, End Call).
     */
    private class CompactGlassActionButton(
        context: Context,
        initialIconRes: Int,
        private val isDestructive: Boolean
    ) : FrameLayout(context) {

        private val d = resources.displayMetrics.density
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.25f * d
        }
        private val iconView: ImageView
        private var isActive: Boolean = false
        private var highlightColor: Int = Color.parseColor("#34D399")

        init {
            setWillNotDraw(false)
            isClickable = true
            isFocusable = true
            iconView = ImageView(context).apply {
                layoutParams = LayoutParams((20f * d).toInt(), (20f * d).toInt(), Gravity.CENTER)
                setImageResource(initialIconRes)
                setColorFilter(Color.WHITE)
            }
            addView(iconView)
        }

        fun setIconRes(resId: Int) {
            iconView.setImageResource(resId)
        }

        fun setActiveHighlight(active: Boolean, colorInt: Int) {
            if (isActive == active && highlightColor == colorInt) return
            isActive = active
            highlightColor = colorInt
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width * 0.5f
            val cy = height * 0.5f
            val visualInset = (if (isDestructive) 2.5f else 4.5f) * d
            val r = (minOf(width, height) * 0.5f) - visualInset

            if (isDestructive) {
                bgPaint.shader = RadialGradient(
                    cx - r * 0.25f, cy - r * 0.25f, r * 1.3f,
                    intArrayOf(
                        Color.parseColor("#FB7185"),
                        Color.parseColor("#DC2626"),
                        Color.parseColor("#991B1B")
                    ),
                    floatArrayOf(0f, 0.5f, 1f),
                    Shader.TileMode.CLAMP
                )
                strokePaint.color = Color.argb(190, 254, 202, 202)
            } else if (isActive) {
                bgPaint.shader = null
                bgPaint.color = ColorUtils.setAlphaComponent(highlightColor, 85)
                strokePaint.color = ColorUtils.setAlphaComponent(highlightColor, 220)
            } else {
                bgPaint.shader = null
                bgPaint.color = Color.argb(72, 148, 163, 184)
                strokePaint.color = Color.argb(95, 226, 232, 240)
            }
            canvas.drawCircle(cx, cy, r, bgPaint)
            canvas.drawCircle(cx, cy, r, strokePaint)
            super.onDraw(canvas)
        }
    }

    companion object {
        const val PILL_HEIGHT_DP = 76f
        const val COLLAPSED_WIDTH_DP = 64f
        const val PILL_CORNER_RADIUS_DP = 38f
        const val JEWEL_BUTTON_SIZE_DP = 52f
        const val MAX_WIDTH_FRACTION = 0.94f
        const val MAX_TABLET_WIDTH_DP = 520f
        const val PERSPECTIVE_DP = 1000f
        const val DROP_OFFSET_Y_DP = 120f
        const val DROP_START_ROTATION_X = 35f
        const val DROP_START_SCALE = 0.85f
        const val STAGE_1_DROP_MS = 450L
        const val STAGE_2_UNFOLD_MS = 1050L
        const val DISMISS_COLLAPSE_MS = 340L
        const val DISMISS_RETRACT_MS = 280L
        const val DRAG_TRIGGER_THRESHOLD = 0.55f
        private const val SHADOW_PADDING_VERTICAL_DP = 10f

        private val COLOR_SMOKED_SAPPHIRE = Color.argb(200, 15, 18, 28) // rgba(15, 18, 28, 0.78)
        private val COLOR_EMERALD_GLOW = Color.parseColor("#10B981")
        private val COLOR_MUTE_ACTIVE = Color.parseColor("#38BDF8")
    }
}
