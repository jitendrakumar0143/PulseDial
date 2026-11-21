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
 * Compact animated 4-bar live network signal indicator embedded in the Top-Center Smart Pill.
 */
class LiveSignalBarsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val rect = RectF()
    private var phase: Float = 0f
    private var animator: ValueAnimator? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startSignalAnimation()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopSignalAnimation()
    }

    fun startSignalAnimation() {
        if (animator?.isRunning == true) return
        animator = ValueAnimator.ofFloat(0f, (2.0 * PI).toFloat()).apply {
            duration = 2200L
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

    fun stopSignalAnimation() {
        animator?.cancel()
        animator = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        val barCount = 4
        val gap = 2.0f * density
        val barWidth = ((width - gap * (barCount - 1)) / barCount).coerceAtLeast(2f * density)
        val heightFractions = floatArrayOf(0.38f, 0.58f, 0.78f, 1.0f)

        for (i in 0 until barCount) {
            val shimmer = 0.5f + 0.5f * sin(phase - i * 0.65f).toFloat()
            val alpha = (155 + (100 * shimmer)).toInt().coerceIn(130, 255)
            barPaint.color = Color.argb(alpha, 110, 231, 183)
            val barH = (height * heightFractions[i]).coerceAtLeast(3f * density)
            val left = i * (barWidth + gap)
            rect.set(left, height - barH, left + barWidth, height.toFloat())
            canvas.drawRoundRect(rect, barWidth * 0.5f, barWidth * 0.5f, barPaint)
        }
    }
}
