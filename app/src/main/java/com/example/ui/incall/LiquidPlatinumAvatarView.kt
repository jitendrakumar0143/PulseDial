package com.example.ui.incall

import android.animation.ValueAnimator
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
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.sin

/**
 * VisionOS-inspired Voice-Reactive 3D Liquid Avatar Aura (`116dp × 116dp` core):
 * - Ringing: Rotating liquid-platinum conic rim + expanding concentric 3D radar rings.
 * - Active Call: Multi-layered liquid-platinum & emerald conic aura with organic voice-reactive pulsing rings.
 * - Muted: Smoothly pauses & dims the voice-reactive rings.
 * - On Hold: Pauses the voice rings and shifts the aura + conic rim to warm frosted amber.
 */
class LiquidPlatinumAvatarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    val avatarCoreSizePx: Float = AVATAR_CORE_SIZE_DP * density

    private var rimRotationDeg: Float = 0f
    private var radarPhase: Float = 0f
    private var voicePhase: Float = 0f

    var isRingingState: Boolean = false
        private set
    var isActiveState: Boolean = false
        private set
    var isMutedState: Boolean = false
        private set
    var isOnHoldState: Boolean = false
        private set

    private var animator: ValueAnimator? = null
    private val shaderMatrix = Matrix()
    private val glossRect = RectF()

    private val ringStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val auraFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val outerGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 8f * density
    }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.4f * density
    }
    private val secondaryRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * density
    }
    private val coreFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val specularDomePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    fun setCallVisualState(
        ringing: Boolean,
        active: Boolean,
        muted: Boolean = false,
        onHold: Boolean = false
    ) {
        if (isRingingState == ringing &&
            isActiveState == active &&
            isMutedState == muted &&
            isOnHoldState == onHold
        ) return
        isRingingState = ringing
        isActiveState = active
        isMutedState = muted
        isOnHoldState = onHold
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startAmbientMotion()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopAmbientMotion()
    }

    fun startAmbientMotion() {
        if (animator?.isRunning == true) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 3200L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                val f = anim.animatedValue as Float
                rimRotationDeg = (f * 360f) % 360f
                radarPhase = f
                if (!isMutedState && !isOnHoldState) {
                    voicePhase = (f * 2.0 * PI).toFloat()
                }
                invalidate()
            }
            start()
        }
    }

    fun stopAmbientMotion() {
        animator?.cancel()
        animator = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        val cx = width * 0.5f
        val cy = height * 0.5f
        val maxRadius = minOf(width, height) * 0.5f - 4f * density
        val coreRadius = (avatarCoreSizePx * 0.5f).coerceAtMost(maxRadius * 0.72f)

        // 1. State-Aware Outer Aura & Voice-Reactive / Radar Rings
        when {
            isRingingState -> {
                // Concentric 3D Radar Pulse Rings expanding outward while ringing
                val ringCount = 3
                for (i in 0 until ringCount) {
                    val progress = (radarPhase + i.toFloat() / ringCount) % 1f
                    val radius = coreRadius + (maxRadius - coreRadius) * progress
                    val fadeAlpha = ((1f - progress) * (1f - progress) * 170f).toInt().coerceIn(0, 185)
                    val fillAlpha = ((1f - progress) * 28f).toInt().coerceIn(0, 34)

                    auraFillPaint.shader = null
                    auraFillPaint.color = Color.argb(fillAlpha, 125, 211, 252)
                    canvas.drawCircle(cx, cy, radius, auraFillPaint)

                    ringStrokePaint.strokeWidth = (2.1f - 1.0f * progress).coerceAtLeast(0.9f) * density
                    ringStrokePaint.color = Color.argb(fadeAlpha, 203, 213, 225)
                    canvas.drawCircle(cx, cy, radius, ringStrokePaint)
                }
            }
            isOnHoldState -> {
                // On Hold: Paused warm amber aura (no voice pulse)
                val haloRadius = coreRadius + 18f * density
                auraFillPaint.shader = RadialGradient(
                    cx, cy, haloRadius,
                    intArrayOf(
                        Color.argb(84, 245, 158, 11),
                        Color.argb(30, 251, 191, 36),
                        Color.TRANSPARENT
                    ),
                    floatArrayOf(0.55f, 0.82f, 1f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawCircle(cx, cy, haloRadius, auraFillPaint)
                auraFillPaint.shader = null

                ringStrokePaint.strokeWidth = 1.4f * density
                ringStrokePaint.color = Color.argb(95, 253, 230, 138)
                canvas.drawCircle(cx, cy, coreRadius + 11f * density, ringStrokePaint)
            }
            isActiveState -> {
                // Active Call: Organic voice-reactive emerald-platinum rings (paused & dimmed when Muted)
                val ringAlphaScale = if (isMutedState) 0.25f else 1.0f
                val energy1 = if (isMutedState) 0.12f else (0.5f + 0.5f * sin(voicePhase * 2.0f))
                val energy2 = if (isMutedState) 0.08f else (0.5f + 0.5f * sin(voicePhase * 3.0f + 1.1f))
                val energy3 = if (isMutedState) 0.05f else (0.5f + 0.5f * sin(voicePhase * 1.5f + 2.4f))

                val haloRadius = coreRadius + (14f + 14f * energy1) * density
                auraFillPaint.shader = RadialGradient(
                    cx, cy, haloRadius,
                    intArrayOf(
                        Color.argb((76 * ringAlphaScale).toInt(), 16, 185, 129),
                        Color.argb((30 * ringAlphaScale).toInt(), 52, 211, 153),
                        Color.TRANSPARENT
                    ),
                    floatArrayOf(0.52f, 0.80f, 1f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawCircle(cx, cy, haloRadius, auraFillPaint)
                auraFillPaint.shader = null

                val ring1R = coreRadius + (7f + 8f * energy1) * density
                val ring2R = coreRadius + (14f + 12f * energy2) * density
                val ring3R = coreRadius + (21f + 14f * energy3) * density

                ringStrokePaint.strokeWidth = 1.8f * density
                ringStrokePaint.color = Color.argb((145 * ringAlphaScale).toInt(), 110, 231, 183)
                canvas.drawCircle(cx, cy, ring1R.coerceAtMost(maxRadius), ringStrokePaint)

                ringStrokePaint.strokeWidth = 1.3f * density
                ringStrokePaint.color = Color.argb((98 * ringAlphaScale).toInt(), 226, 232, 240)
                canvas.drawCircle(cx, cy, ring2R.coerceAtMost(maxRadius), ringStrokePaint)

                ringStrokePaint.strokeWidth = 1.0f * density
                ringStrokePaint.color = Color.argb((60 * ringAlphaScale).toInt(), 52, 211, 153)
                canvas.drawCircle(cx, cy, ring3R.coerceAtMost(maxRadius), ringStrokePaint)
            }
        }

        // 2. Soft Outer Glow Halo
        outerGlowPaint.color = when {
            isOnHoldState -> Color.argb(82, 245, 158, 11)
            isActiveState && isMutedState -> Color.argb(40, 148, 163, 184)
            isActiveState -> Color.argb(72, 52, 211, 153)
            else -> Color.argb(62, 148, 163, 184)
        }
        canvas.drawCircle(cx, cy, coreRadius + 2.5f * density, outerGlowPaint)

        // 3. Smoked-Sapphire 3D Glass Core Fill
        coreFillPaint.shader = RadialGradient(
            cx - coreRadius * 0.25f,
            cy - coreRadius * 0.28f,
            coreRadius * 1.45f,
            when {
                isOnHoldState -> intArrayOf(
                    Color.parseColor("#3B2B17"),
                    Color.parseColor("#1E1814"),
                    Color.parseColor("#0D0B0E")
                )
                isActiveState -> intArrayOf(
                    Color.parseColor("#1D3D4A"),
                    Color.parseColor("#111C30"),
                    Color.parseColor("#090E1B")
                )
                else -> intArrayOf(
                    Color.parseColor("#233252"),
                    Color.parseColor("#12192D"),
                    Color.parseColor("#0A0F1D")
                )
            },
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, coreRadius - 1.5f * density, coreFillPaint)

        // 4. Glossy Top Specular Dome Highlight
        glossRect.set(
            cx - coreRadius * 0.66f,
            cy - coreRadius * 0.84f,
            cx + coreRadius * 0.66f,
            cy - coreRadius * 0.08f
        )
        specularDomePaint.shader = LinearGradient(
            0f, glossRect.top,
            0f, glossRect.bottom,
            intArrayOf(Color.argb(76, 255, 255, 255), Color.argb(0, 255, 255, 255)),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawOval(glossRect, specularDomePaint)

        // 5. Multi-Layered Animated Conic-Gradient Rim (Liquid-Platinum / Emerald / Warm Amber)
        val rimColors = when {
            isOnHoldState -> intArrayOf(
                Color.argb(245, 253, 230, 138),
                Color.argb(230, 245, 158, 11),
                Color.argb(215, 251, 191, 36),
                Color.argb(245, 255, 251, 235),
                Color.argb(245, 253, 230, 138)
            )
            isActiveState -> intArrayOf(
                Color.argb(240, 110, 231, 183),
                Color.argb(220, 226, 232, 240),
                Color.argb(200, 16, 185, 129),
                Color.argb(248, 248, 250, 252),
                Color.argb(180, 52, 211, 153),
                Color.argb(240, 110, 231, 183)
            )
            else -> intArrayOf(
                Color.argb(245, 248, 250, 252),
                Color.argb(190, 148, 163, 184),
                Color.argb(225, 125, 211, 252),
                Color.argb(245, 255, 255, 255),
                Color.argb(185, 165, 180, 252),
                Color.argb(245, 248, 250, 252)
            )
        }
        val sweep = SweepGradient(cx, cy, rimColors, null)
        shaderMatrix.reset()
        shaderMatrix.setRotate(rimRotationDeg, cx, cy)
        sweep.setLocalMatrix(shaderMatrix)
        rimPaint.shader = sweep
        canvas.drawCircle(cx, cy, coreRadius, rimPaint)

        // Inner counter-rotating platinum specular rim layer
        val counterSweep = SweepGradient(cx, cy, rimColors, null)
        shaderMatrix.reset()
        shaderMatrix.setRotate(-rimRotationDeg * 0.7f, cx, cy)
        counterSweep.setLocalMatrix(shaderMatrix)
        secondaryRimPaint.shader = counterSweep
        secondaryRimPaint.alpha = 145
        canvas.drawCircle(cx, cy, coreRadius - 4.2f * density, secondaryRimPaint)
    }

    companion object {
        const val AVATAR_CORE_SIZE_DP = 116f
    }
}
