package com.example.ui.incall

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Full-viewport animated dark obsidian / midnight silk aurora backdrop with slow-moving
 * deep sapphire & indigo ambient light orbs behind a frosted glass veil, plus dynamic
 * full-screen ambient state overlays:
 * - Recording Active: Subtle pulsing crimson ambient glow around all 4 screen edges.
 * - Hold Active: Soft frosted-amber atmospheric tint overlay across the screen.
 */
class AuroraSilkBackdropView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val orbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glassVeilPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val holdOverlayPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val recordEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var phase: Float = 0f
    private var isActiveCallMode: Boolean = false
    var isRecordingAmbientActive: Boolean = false
        private set
    var isHoldAmbientActive: Boolean = false
        private set

    private var animator: ValueAnimator? = null

    fun setActiveCallMood(active: Boolean) {
        if (isActiveCallMode == active) return
        isActiveCallMode = active
        invalidate()
    }

    fun setRecordingAmbient(recording: Boolean) {
        if (isRecordingAmbientActive == recording) return
        isRecordingAmbientActive = recording
        invalidate()
    }

    fun setHoldAmbient(onHold: Boolean) {
        if (isHoldAmbientActive == onHold) return
        isHoldAmbientActive = onHold
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startAnimation()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopAnimation()
    }

    fun startAnimation() {
        if (animator?.isRunning == true) return
        animator = ValueAnimator.ofFloat(0f, (2.0 * PI).toFloat()).apply {
            duration = 12000L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                phase = anim.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun stopAnimation() {
        animator?.cancel()
        animator = null
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return

        basePaint.shader = LinearGradient(
            0f, 0f,
            w.toFloat(), h.toFloat(),
            intArrayOf(
                Color.parseColor("#050812"),
                Color.parseColor("#090F20"),
                Color.parseColor("#0D1429"),
                Color.parseColor("#060A15")
            ),
            floatArrayOf(0f, 0.35f, 0.72f, 1f),
            Shader.TileMode.CLAMP
        )

        glassVeilPaint.shader = LinearGradient(
            0f, 0f,
            0f, h.toFloat(),
            intArrayOf(
                Color.argb(62, 15, 23, 42),
                Color.argb(44, 10, 15, 28),
                Color.argb(120, 4, 7, 15)
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        canvas.drawRect(0f, 0f, w, h, basePaint)

        val maxDim = maxOf(w, h)

        // Orb 1: Deep Sapphire Ambient Orb
        val orb1X = w * (0.24f + 0.16f * cos(phase))
        val orb1Y = h * (0.20f + 0.08f * sin(phase * 1.15f))
        val orb1Radius = maxDim * 0.52f
        orbPaint.shader = RadialGradient(
            orb1X, orb1Y, orb1Radius,
            intArrayOf(
                Color.argb(104, 29, 78, 216),
                Color.argb(48, 30, 58, 138),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.48f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(orb1X, orb1Y, orb1Radius, orbPaint)

        // Orb 2: Rich Indigo Ambient Orb
        val orb2X = w * (0.78f + 0.14f * sin(phase * 0.9f))
        val orb2Y = h * (0.38f + 0.12f * cos(phase * 0.85f))
        val orb2Radius = maxDim * 0.48f
        orbPaint.shader = RadialGradient(
            orb2X, orb2Y, orb2Radius,
            intArrayOf(
                Color.argb(92, 79, 70, 229),
                Color.argb(40, 49, 46, 129),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.50f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(orb2X, orb2Y, orb2Radius, orbPaint)

        // Orb 3: Lower Sapphire / Calm Emerald / Warm Amber (when on Hold) Orb
        val orb3X = w * (0.48f + 0.18f * cos(phase * 0.7f + 1.4f))
        val orb3Y = h * (0.78f + 0.09f * sin(phase * 0.8f + 0.6f))
        val orb3Radius = maxDim * 0.46f
        val orb3Core = when {
            isHoldAmbientActive -> Color.argb(96, 245, 158, 11)
            isActiveCallMode -> Color.argb(82, 16, 185, 129)
            else -> Color.argb(74, 14, 165, 233)
        }
        val orb3Mid = when {
            isHoldAmbientActive -> Color.argb(38, 180, 83, 9)
            isActiveCallMode -> Color.argb(32, 6, 95, 70)
            else -> Color.argb(30, 30, 64, 175)
        }
        orbPaint.shader = RadialGradient(
            orb3X, orb3Y, orb3Radius,
            intArrayOf(orb3Core, orb3Mid, Color.TRANSPARENT),
            floatArrayOf(0f, 0.52f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(orb3X, orb3Y, orb3Radius, orbPaint)

        // Frosted smoked-glass atmospheric veil
        canvas.drawRect(0f, 0f, w, h, glassVeilPaint)

        // Dynamic State Overlay A: Soft Frosted-Amber Tint when Hold is Active
        if (isHoldAmbientActive) {
            holdOverlayPaint.shader = LinearGradient(
                0f, 0f,
                0f, h,
                intArrayOf(
                    Color.argb(54, 245, 158, 11),
                    Color.argb(34, 217, 119, 6),
                    Color.argb(62, 180, 83, 9)
                ),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w, h, holdOverlayPaint)
        }

        // Dynamic State Overlay B: Subtle Pulsing Crimson Ambient Glow around Screen Edges when Recording
        if (isRecordingAmbientActive) {
            val pulse = 0.55f + 0.45f * sin(phase * 4.5f).toFloat()
            val edgeAlpha = (95 * pulse).toInt().coerceIn(35, 115)
            val edgeDepth = minOf(w, h) * 0.16f

            // Top edge glow
            recordEdgePaint.shader = LinearGradient(
                0f, 0f, 0f, edgeDepth,
                intArrayOf(Color.argb(edgeAlpha, 225, 29, 72), Color.TRANSPARENT),
                null, Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w, edgeDepth, recordEdgePaint)

            // Bottom edge glow
            recordEdgePaint.shader = LinearGradient(
                0f, h, 0f, h - edgeDepth,
                intArrayOf(Color.argb(edgeAlpha, 225, 29, 72), Color.TRANSPARENT),
                null, Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, h - edgeDepth, w, h, recordEdgePaint)

            // Left edge glow
            recordEdgePaint.shader = LinearGradient(
                0f, 0f, edgeDepth, 0f,
                intArrayOf(Color.argb(edgeAlpha, 225, 29, 72), Color.TRANSPARENT),
                null, Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, edgeDepth, h, recordEdgePaint)

            // Right edge glow
            recordEdgePaint.shader = LinearGradient(
                w, 0f, w - edgeDepth, 0f,
                intArrayOf(Color.argb(edgeAlpha, 225, 29, 72), Color.TRANSPARENT),
                null, Shader.TileMode.CLAMP
            )
            canvas.drawRect(w - edgeDepth, 0f, w, h, recordEdgePaint)
        }
    }
}
