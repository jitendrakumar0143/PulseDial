package com.example.ui.incall

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.sin

/**
 * Compact 60fps animated 7-bar live audio waveform displayed under caller details.
 * Uses liquid-platinum/sapphire glow during incoming ringing and calm emerald glow when active.
 */
class LiveCallWaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val barRect = RectF()
    private var phase: Float = 0f
    private var isActiveEmerald: Boolean = false
    private var isPaused: Boolean = false
    private var animator: ValueAnimator? = null

    fun setActiveEmerald(active: Boolean) {
        isActiveEmerald = active
        invalidate()
    }

    fun setPaused(paused: Boolean) {
        isPaused = paused
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startWave()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopWave()
    }

    fun startWave() {
        if (animator?.isRunning == true) return
        animator = ValueAnimator.ofFloat(0f, (2.0 * PI).toFloat()).apply {
            duration = 1500L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                phase = anim.animatedValue as Float
                if (!isPaused) invalidate()
            }
            start()
        }
    }

    fun stopWave() {
        animator?.cancel()
        animator = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        val barCount = 7
        val barWidth = 3.2f * density
        val barGap = 4.2f * density
        val totalW = barCount * barWidth + (barCount - 1) * barGap
        val startX = (width - totalW) * 0.5f
        val cy = height * 0.5f
        val maxBarH = (height - 4f * density).coerceAtLeast(8f * density)
        val minBarH = 4f * density

        val weights = floatArrayOf(0.45f, 0.72f, 0.94f, 1.0f, 0.92f, 0.70f, 0.45f)
        for (i in 0 until barCount) {
            val energy = if (isPaused) {
                0.15f
            } else {
                val wave = 0.5f + 0.5f * sin(phase * 1.4f + i * 0.85f).toFloat()
                (0.24f + 0.76f * wave) * weights[i]
            }
            val barH = minBarH + (maxBarH - minBarH) * energy
            val left = startX + i * (barWidth + barGap)
            barRect.set(left, cy - barH * 0.5f, left + barWidth, cy + barH * 0.5f)
            barPaint.color = if (isActiveEmerald) {
                if (i % 2 == 0) Color.argb(235, 52, 211, 153) else Color.argb(220, 167, 243, 208)
            } else {
                if (i % 2 == 0) Color.argb(235, 125, 211, 252) else Color.argb(220, 226, 232, 240)
            }
            canvas.drawRoundRect(barRect, barWidth * 0.5f, barWidth * 0.5f, barPaint)
        }
    }
}
