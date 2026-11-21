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
import android.widget.TextView
import com.example.R
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/**
 * Bottom 3D Sideways Drag Dock for Full-Screen Incoming Calls:
 * - Smoked-sapphire glass pill track (`76dp` tall, `90%` max screen width, `38dp` corner radius)
 *   with animated rotating liquid-platinum conic rim.
 * - Left edge: Sculpted 3D glossy Emerald Green Pick Call button (`56dp × 56dp`) + emerald `››` chevrons.
 * - Right edge: Sculpted 3D glossy Ruby Red Decline button (`56dp × 56dp`) + crimson `‹‹` chevrons.
 * - Sideways drag physics with pointer capture, luminous glass trail, opposite button fade,
 *   dynamic rim glow shift, 55% threshold switch ("Slide to Answer"/"Slide to Decline" ->
 *   "Release to Answer"/"Release to Decline") + haptic tick, elastic snap-back, and tap-to-nudge hint.
 */
class SidewaysCallDragDockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    enum class DragSide {
        NONE,
        ANSWER_GREEN,
        DECLINE_RED
    }

    interface DragDockListener {
        fun onAnswerCall()
        fun onDeclineCall()
    }

    var listener: DragDockListener? = null

    var activeDragSide: DragSide = DragSide.NONE
        private set

    var currentDragProgress: Float = 0f
        private set

    var lastStatusMessage: String = ""
        private set

    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    val dockHeightPx: Float = DOCK_HEIGHT_DP * density
    val dockCornerRadiusPx: Float = DOCK_CORNER_RADIUS_DP * density
    val jewelSizePx: Int = (JEWEL_SIZE_DP * density).toInt()
    private val sideInsetPx: Float = 10f * density

    private var rimRotationDeg: Float = 0f
    private var chevronPhase: Float = 0f
    private var dragCrossedThreshold: Boolean = false
    private var downRawX: Float = 0f
    private var maxAbsDeltaX: Float = 0f
    private var dragCompletedThisGesture: Boolean = false

    // Drawing geometry & Paints
    private val dockRect = RectF()
    private val innerHighlightRect = RectF()
    private val trailRect = RectF()
    private val shaderMatrix = Matrix()

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glassBasePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_SMOKED_SAPPHIRE
    }
    private val glassDepthPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val specularPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.85f * density
    }
    private val rimOuterGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4.4f * density
    }
    private val chevronPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 2.1f * density
    }

    private val tvCenterHint: TextView
    private val btnGreenPick: DockJewel3DButton
    private val btnRedDecline: DockJewel3DButton

    private var ambientAnimator: ValueAnimator? = null
    private var snapBackAnimator: ValueAnimator? = null
    private var nudgeAnimator: AnimatorSet? = null
    private val clearNudgeRunnable = Runnable {
        if (activeDragSide == DragSide.NONE) {
            tvCenterHint.animate().alpha(0f).setDuration(180L).start()
        }
    }

    init {
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
        cameraDistance = 1000f * density * density

        tvCenterHint = TextView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
                marginStart = (JEWEL_SIZE_DP * density + 18f * density).toInt()
                marginEnd = (JEWEL_SIZE_DP * density + 18f * density).toInt()
            }
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            letterSpacing = 0.03f
            setTextColor(Color.WHITE)
            alpha = 0f
        }

        btnGreenPick = DockJewel3DButton(context, isEmerald = true, iconRes = R.drawable.ic_call).apply {
            id = R.id.btn_dock_drag_answer
            layoutParams = LayoutParams(jewelSizePx, jewelSizePx, Gravity.CENTER_VERTICAL or Gravity.START)
            contentDescription = context.getString(R.string.incall_answer)
        }

        btnRedDecline = DockJewel3DButton(context, isEmerald = false, iconRes = R.drawable.ic_call_end).apply {
            id = R.id.btn_dock_drag_decline
            layoutParams = LayoutParams(jewelSizePx, jewelSizePx, Gravity.CENTER_VERTICAL or Gravity.END)
            contentDescription = context.getString(R.string.incall_decline)
        }

        addView(tvCenterHint)
        addView(btnGreenPick)
        addView(btnRedDecline)

        setupDragGesture(btnGreenPick, DragSide.ANSWER_GREEN)
        setupDragGesture(btnRedDecline, DragSide.DECLINE_RED)
    }

    fun computeDockWidthPx(availableWidthPx: Int): Float {
        if (availableWidthPx <= 0) {
            return (resources.displayMetrics.widthPixels * DOCK_WIDTH_FRACTION)
                .coerceAtMost(MAX_TABLET_WIDTH_DP * density)
        }
        val minCompact = (268f * density).coerceAtMost(availableWidthPx.toFloat())
        val maxTablet = MAX_TABLET_WIDTH_DP * density
        return (availableWidthPx * DOCK_WIDTH_FRACTION).coerceIn(minCompact, maxTablet)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val parentWidth = MeasureSpec.getSize(widthMeasureSpec)
        val targetWidth = computeDockWidthPx(parentWidth).toInt()
        val totalHeight = (dockHeightPx + SHADOW_PAD_DP * 2f * density).toInt()
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(targetWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(totalHeight, MeasureSpec.EXACTLY)
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (activeDragSide == DragSide.NONE) {
            positionRestingButtons()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startAmbientLoop()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopAmbientLoop()
        snapBackAnimator?.cancel()
        nudgeAnimator?.cancel()
        removeCallbacks(clearNudgeRunnable)
    }

    fun resetState() {
        snapBackAnimator?.cancel()
        nudgeAnimator?.cancel()
        removeCallbacks(clearNudgeRunnable)
        resetDragVisualsImmediate()
    }

    private fun startAmbientLoop() {
        if (ambientAnimator?.isRunning == true) return
        ambientAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2800L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                val f = anim.animatedValue as Float
                rimRotationDeg = (f * 360f) % 360f
                chevronPhase = f
                if (visibility == View.VISIBLE) invalidate()
            }
            start()
        }
    }

    private fun stopAmbientLoop() {
        ambientAnimator?.cancel()
        ambientAnimator = null
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDragGesture(button: DockJewel3DButton, side: DragSide) {
        button.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    snapBackAnimator?.cancel()
                    nudgeAnimator?.cancel()
                    removeCallbacks(clearNudgeRunnable)
                    downRawX = event.rawX
                    maxAbsDeltaX = 0f
                    dragCompletedThisGesture = false
                    dragCrossedThreshold = false
                    activeDragSide = side
                    currentDragProgress = 0f
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    v.animate().scaleX(1.06f).scaleY(1.06f).setDuration(90L).start()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (dragCompletedThisGesture) return@setOnTouchListener true
                    val rawDelta = event.rawX - downRawX
                    val directed = if (side == DragSide.ANSWER_GREEN) {
                        rawDelta.coerceAtLeast(0f)
                    } else {
                        (-rawDelta).coerceAtLeast(0f)
                    }
                    maxAbsDeltaX = max(maxAbsDeltaX, abs(rawDelta))
                    if (maxAbsDeltaX > touchSlop * 0.4f) {
                        applyDragDisplacement(side, directed)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    v.animate().scaleX(1f).scaleY(1f).setDuration(140L).start()
                    val isCancel = event.actionMasked == MotionEvent.ACTION_CANCEL
                    if (!isCancel && maxAbsDeltaX < touchSlop) {
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
        applyDragDisplacement(side, clamped * computeMaxTravelPx())
    }

    private fun computeMaxTravelPx(): Float {
        val w = if (width > 0) width.toFloat() else computeDockWidthPx(0)
        return (w - jewelSizePx - sideInsetPx * 2f).coerceAtLeast(1f)
    }

    private fun applyDragDisplacement(side: DragSide, directedDeltaPx: Float) {
        val maxTravel = computeMaxTravelPx()
        val clampedPx = directedDeltaPx.coerceIn(0f, maxTravel)
        val progress = (clampedPx / maxTravel).coerceIn(0f, 1f)
        currentDragProgress = progress

        val baseGreenX = sideInsetPx
        val baseRedX = ((if (width > 0) width.toFloat() else computeDockWidthPx(0)) - sideInsetPx - jewelSizePx)
            .coerceAtLeast(sideInsetPx)

        if (side == DragSide.ANSWER_GREEN) {
            btnGreenPick.x = baseGreenX + clampedPx
            btnRedDecline.alpha = (1f - progress * 1.9f).coerceIn(0f, 1f)
            btnRedDecline.scaleX = (1f - progress * 0.25f).coerceIn(0.75f, 1f)
            btnRedDecline.scaleY = (1f - progress * 0.25f).coerceIn(0.75f, 1f)
        } else {
            btnRedDecline.x = baseRedX - clampedPx
            btnGreenPick.alpha = (1f - progress * 1.9f).coerceIn(0f, 1f)
            btnGreenPick.scaleX = (1f - progress * 0.25f).coerceIn(0.75f, 1f)
            btnGreenPick.scaleY = (1f - progress * 0.25f).coerceIn(0.75f, 1f)
        }

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
        val text = context.getString(statusRes)
        lastStatusMessage = text
        tvCenterHint.text = text
        tvCenterHint.setTextColor(
            when {
                side == DragSide.ANSWER_GREEN && crossedNow -> Color.parseColor("#A7F3D0")
                side == DragSide.DECLINE_RED && crossedNow -> Color.parseColor("#FECDD3")
                else -> Color.WHITE
            }
        )
        tvCenterHint.alpha = ((progress - 0.03f) / 0.20f).coerceIn(0f, 1f)
        invalidate()
    }

    internal fun triggerTapToNudgeHint(side: DragSide) {
        nudgeAnimator?.cancel()
        removeCallbacks(clearNudgeRunnable)

        val hintText = context.getString(R.string.incall_pill_drag_sideways)
        lastStatusMessage = hintText
        tvCenterHint.text = hintText
        tvCenterHint.setTextColor(
            if (side == DragSide.ANSWER_GREEN) Color.parseColor("#6EE7B7") else Color.parseColor("#FDA4AF")
        )
        tvCenterHint.animate().alpha(1f).setDuration(140L).start()

        val target = if (side == DragSide.ANSWER_GREEN) btnGreenPick else btnRedDecline
        val nudgeDelta = (if (side == DragSide.ANSWER_GREEN) 20f else -20f) * density

        val nudgeOut = ObjectAnimator.ofFloat(target, View.TRANSLATION_X, 0f, nudgeDelta).apply {
            duration = 160L
            interpolator = PathInterpolator(0.22f, 1f, 0.36f, 1f)
        }
        val nudgeBack = ObjectAnimator.ofFloat(target, View.TRANSLATION_X, nudgeDelta, 0f).apply {
            duration = 260L
            interpolator = OvershootInterpolator(1.55f)
        }
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        nudgeAnimator = AnimatorSet().apply {
            playSequentially(nudgeOut, nudgeBack)
            start()
        }
        postDelayed(clearNudgeRunnable, 1250L)
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

    private fun snapBackFromDrag(side: DragSide) {
        val startProgress = currentDragProgress
        snapBackAnimator?.cancel()
        snapBackAnimator = ValueAnimator.ofFloat(startProgress, 0f).apply {
            duration = 280L
            interpolator = OvershootInterpolator(1.35f)
            addUpdateListener { anim ->
                val p = (anim.animatedValue as Float).coerceAtLeast(0f)
                applyDragDisplacement(side, p * computeMaxTravelPx())
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    resetDragVisualsImmediate()
                }
            })
            start()
        }
    }

    private fun resetDragVisualsImmediate() {
        activeDragSide = DragSide.NONE
        currentDragProgress = 0f
        dragCrossedThreshold = false
        btnGreenPick.translationX = 0f
        btnRedDecline.translationX = 0f
        btnGreenPick.alpha = 1f
        btnRedDecline.alpha = 1f
        btnGreenPick.scaleX = 1f
        btnGreenPick.scaleY = 1f
        btnRedDecline.scaleX = 1f
        btnRedDecline.scaleY = 1f
        tvCenterHint.alpha = 0f
        positionRestingButtons()
        invalidate()
    }

    private fun positionRestingButtons() {
        if (width <= 0) return
        btnGreenPick.x = sideInsetPx
        btnRedDecline.x = (width - sideInsetPx - jewelSizePx).coerceAtLeast(sideInsetPx)
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

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        val topPad = SHADOW_PAD_DP * density
        dockRect.set(0f, topPad, width.toFloat(), topPad + dockHeightPx)
        val radius = dockCornerRadiusPx

        // 1. Soft Ambient Drop Shadow
        shadowPaint.color = Color.argb(120, 2, 5, 14)
        shadowPaint.setShadowLayer(16f * density, 0f, 6f * density, Color.argb(150, 2, 6, 18))
        canvas.drawRoundRect(dockRect, radius, radius, shadowPaint)
        shadowPaint.clearShadowLayer()

        // 2. Multi-Layered Smoked-Sapphire Glass Track
        canvas.drawRoundRect(dockRect, radius, radius, glassBasePaint)
        glassDepthPaint.shader = LinearGradient(
            dockRect.left, dockRect.top,
            dockRect.right, dockRect.bottom,
            intArrayOf(
                Color.argb(165, 22, 32, 56),
                Color.argb(215, 12, 16, 28),
                Color.argb(185, 20, 26, 46)
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(dockRect, radius, radius, glassDepthPaint)

        // 3. Luminous Emerald / Crimson Glass Drag Trail
        if (activeDragSide != DragSide.NONE && currentDragProgress > 0.01f) {
            val pad = 6f * density
            val trailRadius = (dockHeightPx - pad * 2f) * 0.5f
            if (activeDragSide == DragSide.ANSWER_GREEN) {
                val rightEdge = (btnGreenPick.x + jewelSizePx + 6f * density).coerceAtMost(dockRect.right - pad)
                trailRect.set(dockRect.left + pad, dockRect.top + pad, rightEdge, dockRect.bottom - pad)
                trailPaint.shader = LinearGradient(
                    trailRect.left, trailRect.centerY(),
                    trailRect.right, trailRect.centerY(),
                    intArrayOf(
                        Color.argb((55 + 95 * currentDragProgress).toInt(), 5, 150, 105),
                        Color.argb((120 + 105 * currentDragProgress).toInt(), 16, 185, 129),
                        Color.argb((150 + 85 * currentDragProgress).toInt(), 52, 211, 153)
                    ),
                    floatArrayOf(0f, 0.6f, 1f),
                    Shader.TileMode.CLAMP
                )
            } else {
                val leftEdge = (btnRedDecline.x - 6f * density).coerceAtLeast(dockRect.left + pad)
                trailRect.set(leftEdge, dockRect.top + pad, dockRect.right - pad, dockRect.bottom - pad)
                trailPaint.shader = LinearGradient(
                    trailRect.right, trailRect.centerY(),
                    trailRect.left, trailRect.centerY(),
                    intArrayOf(
                        Color.argb((55 + 95 * currentDragProgress).toInt(), 185, 28, 28),
                        Color.argb((120 + 105 * currentDragProgress).toInt(), 239, 68, 68),
                        Color.argb((150 + 85 * currentDragProgress).toInt(), 251, 113, 133)
                    ),
                    floatArrayOf(0f, 0.6f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRoundRect(trailRect, trailRadius, trailRadius, trailPaint)
        }

        // 4. Specular Top Glass Bevel Highlight
        innerHighlightRect.set(
            dockRect.left + 2f * density,
            dockRect.top + 1.5f * density,
            dockRect.right - 2f * density,
            dockRect.top + dockHeightPx * 0.46f
        )
        specularPaint.shader = LinearGradient(
            0f, innerHighlightRect.top,
            0f, innerHighlightRect.bottom,
            intArrayOf(Color.argb(66, 255, 255, 255), Color.TRANSPARENT),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(innerHighlightRect, radius * 0.88f, radius * 0.65f, specularPaint)

        // 5. Animated Directional Chevrons (emerald ›› right of Green, crimson ‹‹ left of Red)
        if (activeDragSide == DragSide.NONE && tvCenterHint.alpha < 0.35f) {
            drawDirectionalChevrons(canvas)
        }

        // 6. Rotating Iridescent Liquid-Platinum Conic Rim (shifts to Emerald or Crimson while dragging)
        drawIridescentRim(canvas, radius)
    }

    private fun drawDirectionalChevrons(canvas: Canvas) {
        val cy = dockRect.centerY()
        val chevH = 5.5f * density
        val chevW = 4.6f * density
        val step = 8.2f * density

        val greenStart = dockRect.left + sideInsetPx + jewelSizePx + 12f * density
        val redStart = dockRect.right - sideInsetPx - jewelSizePx - 12f * density

        for (i in 0 until 2) {
            val wave = 0.5f + 0.5f * sin((chevronPhase * 2f * PI) - i * 1.15).toFloat()
            val alphaVal = (85 + 150 * wave).toInt().coerceIn(50, 235)
            chevronPaint.color = Color.argb(alphaVal, 52, 211, 153)
            val gx = greenStart + i * step
            canvas.drawLine(gx, cy - chevH, gx + chevW, cy, chevronPaint)
            canvas.drawLine(gx + chevW, cy, gx, cy + chevH, chevronPaint)

            chevronPaint.color = Color.argb(alphaVal, 251, 113, 133)
            val rx = redStart - i * step
            canvas.drawLine(rx, cy - chevH, rx - chevW, cy, chevronPaint)
            canvas.drawLine(rx - chevW, cy, rx, cy + chevH, chevronPaint)
        }
    }

    private fun drawIridescentRim(canvas: Canvas, radius: Float) {
        val cx = dockRect.centerX()
        val cy = dockRect.centerY()
        val colors = when {
            activeDragSide == DragSide.ANSWER_GREEN && currentDragProgress > 0.04f -> intArrayOf(
                Color.argb(245, 167, 243, 208),
                Color.argb(235, 16, 185, 129),
                Color.argb(245, 52, 211, 153),
                Color.argb(225, 236, 253, 245),
                Color.argb(245, 167, 243, 208)
            )
            activeDragSide == DragSide.DECLINE_RED && currentDragProgress > 0.04f -> intArrayOf(
                Color.argb(245, 254, 205, 211),
                Color.argb(235, 239, 68, 68),
                Color.argb(245, 251, 113, 133),
                Color.argb(225, 255, 241, 242),
                Color.argb(245, 254, 205, 211)
            )
            else -> intArrayOf(
                Color.argb(245, 248, 250, 252),
                Color.argb(175, 148, 163, 184),
                Color.argb(215, 125, 211, 252),
                Color.argb(245, 255, 255, 255),
                Color.argb(175, 165, 180, 252),
                Color.argb(245, 248, 250, 252)
            )
        }
        val sweep = SweepGradient(cx, cy, colors, null)
        shaderMatrix.reset()
        shaderMatrix.setRotate(rimRotationDeg, cx, cy)
        sweep.setLocalMatrix(shaderMatrix)

        rimOuterGlowPaint.color = when {
            activeDragSide == DragSide.ANSWER_GREEN && currentDragProgress > 0.04f ->
                Color.argb((60 + 115 * currentDragProgress).toInt(), 16, 185, 129)
            activeDragSide == DragSide.DECLINE_RED && currentDragProgress > 0.04f ->
                Color.argb((60 + 115 * currentDragProgress).toInt(), 239, 68, 68)
            else -> Color.argb(46, 203, 213, 225)
        }
        canvas.drawRoundRect(dockRect, radius, radius, rimOuterGlowPaint)

        rimBorderPaint.shader = sweep
        canvas.drawRoundRect(dockRect, radius, radius, rimBorderPaint)
    }

    private class DockJewel3DButton(
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
            addView(
                ImageView(context).apply {
                    layoutParams = LayoutParams((25f * d).toInt(), (25f * d).toInt(), Gravity.CENTER)
                    setImageResource(iconRes)
                    setColorFilter(Color.WHITE)
                }
            )
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width * 0.5f
            val cy = height * 0.5f
            val r = (minOf(width, height) * 0.5f) - 1.5f * d

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

            glossRect.set(cx - r * 0.66f, cy - r * 0.85f, cx + r * 0.66f, cy - r * 0.05f)
            glossPaint.shader = LinearGradient(
                0f, glossRect.top,
                0f, glossRect.bottom,
                intArrayOf(Color.argb(145, 255, 255, 255), Color.argb(12, 255, 255, 255)),
                null,
                Shader.TileMode.CLAMP
            )
            canvas.drawOval(glossRect, glossPaint)

            rimPaint.color = if (isEmerald) {
                Color.argb(215, 167, 243, 208)
            } else {
                Color.argb(215, 254, 205, 211)
            }
            canvas.drawCircle(cx, cy, r, rimPaint)
            super.onDraw(canvas)
        }
    }

    companion object {
        const val DOCK_HEIGHT_DP = 76f
        const val DOCK_WIDTH_FRACTION = 0.90f
        const val DOCK_CORNER_RADIUS_DP = 38f
        const val JEWEL_SIZE_DP = 56f
        const val MAX_TABLET_WIDTH_DP = 460f
        const val DRAG_TRIGGER_THRESHOLD = 0.55f
        private const val SHADOW_PAD_DP = 8f
        private val COLOR_SMOKED_SAPPHIRE = Color.argb(208, 15, 18, 28)
    }
}
