package com.example.ui.incall

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import kotlin.math.PI
import kotlin.math.sin

/**
 * Sculpted 3D Circular Jewel Button (`52dp × 52dp`) — Glossy Emerald Green or Crimson Red
 * matching the smoked-sapphire 3D floating call popup spec:
 * - Emerald radial gradient: `#6EE7B7 0% -> #10B981 48% -> #047857 100%`
 * - Crimson radial gradient: `#FDA4AF 0% -> #EF4444 48% -> #991B1B 100%`
 * - Colored drop shadow, inset top specular dome highlight, and bottom depth rim.
 */
internal class Jewel3DButtonView(
    context: Context,
    private val isEmerald: Boolean,
    iconRes: Int
) : FrameLayout(context) {

    private val d = resources.displayMetrics.density
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val topSpecularPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bottomDepthPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.25f * d
    }
    private val glossRect = RectF()
    private val bottomRect = RectF()

    init {
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
        isClickable = true
        isFocusable = true
        elevation = 8f * d

        val icon = ImageView(context).apply {
            layoutParams = LayoutParams((22f * d).toInt(), (22f * d).toInt(), Gravity.CENTER)
            setImageResource(iconRes)
            setColorFilter(Color.WHITE)
        }
        addView(icon)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width * 0.5f
        val cy = height * 0.5f
        val r = (minOf(width, height) * 0.5f) - 1f * d

        // Colored 3D jewel glow shadow (`0 8px 20px rgba(16,185,129,0.5)` / crimson)
        shadowPaint.color = if (isEmerald) {
            Color.argb(128, 16, 185, 129)
        } else {
            Color.argb(128, 239, 68, 68)
        }
        shadowPaint.setShadowLayer(
            12f * d,
            0f,
            4f * d,
            if (isEmerald) Color.argb(135, 16, 185, 129) else Color.argb(135, 239, 68, 68)
        )
        canvas.drawCircle(cx, cy, r - 0.5f * d, shadowPaint)
        shadowPaint.clearShadowLayer()

        // Radial gradient originating at (30%, 25%) matching spec
        val focalX = cx - r * 0.40f
        val focalY = cy - r * 0.50f
        fillPaint.shader = if (isEmerald) {
            RadialGradient(
                focalX, focalY, r * 1.48f,
                intArrayOf(
                    Color.parseColor("#6EE7B7"),
                    Color.parseColor("#10B981"),
                    Color.parseColor("#047857")
                ),
                floatArrayOf(0f, 0.48f, 1f),
                Shader.TileMode.CLAMP
            )
        } else {
            RadialGradient(
                focalX, focalY, r * 1.48f,
                intArrayOf(
                    Color.parseColor("#FDA4AF"),
                    Color.parseColor("#EF4444"),
                    Color.parseColor("#991B1B")
                ),
                floatArrayOf(0f, 0.48f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(cx, cy, r, fillPaint)

        // Inset top specular bevel (`inset 0 2px 4px rgba(255,255,255,0.55)`)
        glossRect.set(cx - r * 0.70f, cy - r * 0.88f, cx + r * 0.70f, cy - r * 0.08f)
        topSpecularPaint.shader = LinearGradient(
            0f, glossRect.top, 0f, glossRect.bottom,
            intArrayOf(Color.argb(145, 255, 255, 255), Color.argb(10, 255, 255, 255)),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawOval(glossRect, topSpecularPaint)

        // Inset bottom depth shading (`inset 0 -4px 8px rgba(0,0,0,0.35)`)
        bottomRect.set(cx - r * 0.80f, cy + r * 0.25f, cx + r * 0.80f, cy + r * 0.96f)
        bottomDepthPaint.shader = LinearGradient(
            0f, bottomRect.top, 0f, bottomRect.bottom,
            intArrayOf(Color.TRANSPARENT, Color.argb(90, 0, 0, 0)),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawOval(bottomRect, bottomDepthPaint)

        // Crisp subtle rim bevel
        rimPaint.color = if (isEmerald) {
            Color.argb(165, 167, 243, 208)
        } else {
            Color.argb(165, 254, 205, 211)
        }
        canvas.drawCircle(cx, cy, r - 0.6f * d, rimPaint)
        super.onDraw(canvas)
    }
}

/**
 * Compact 3D Frosted Glass Circular Action Button inside the Active "On Call" Bar:
 * - Mute & Speaker: `38dp` visual circle inside accessible touch bounds; frosted glass idle
 *   (`rgba(255,255,255,0.08)` + `rgba(255,255,255,0.14)` border) and sky-blue ON state
 *   (`rgba(56,189,248,0.28)` + `rgba(56,189,248,0.65)` border).
 * - End Call (`isDestructive = true`): `44dp` visual glossy 3D ruby circle
 *   (`#FDA4AF -> #EF4444 -> #991B1B`) with crimson drop glow & top specular bevel.
 */
internal class CompactGlassActionButton(
    context: Context,
    initialIconRes: Int,
    private val isDestructive: Boolean
) : FrameLayout(context) {

    private val d = resources.displayMetrics.density
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glossPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.1f * d
    }
    private val glossRect = RectF()
    private val iconView: ImageView
    private var isActive: Boolean = false

    init {
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
        isClickable = true
        isFocusable = true

        val iconSizePx = ((if (isDestructive) 19f else 17f) * d).toInt()
        iconView = ImageView(context).apply {
            layoutParams = LayoutParams(iconSizePx, iconSizePx, Gravity.CENTER)
            setImageResource(initialIconRes)
            setColorFilter(if (isDestructive) Color.WHITE else Color.parseColor("#E2E8F0"))
        }
        addView(iconView)
    }

    fun setIconRes(resId: Int) {
        iconView.setImageResource(resId)
    }

    fun setActiveHighlight(active: Boolean) {
        if (isActive == active) return
        isActive = active
        if (!isDestructive) {
            iconView.setColorFilter(if (active) Color.WHITE else Color.parseColor("#E2E8F0"))
        }
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                animate().scaleX(0.92f).scaleY(0.92f).setDuration(120L).start()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                animate().scaleX(1f).scaleY(1f).setDuration(120L).start()
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width * 0.5f
        val cy = height * 0.5f
        val targetDiameter = (if (isDestructive) 44f else 38f) * d
        val r = minOf(minOf(width, height) * 0.5f - 1f * d, targetDiameter * 0.5f)

        if (isDestructive) {
            shadowPaint.color = Color.argb(115, 239, 68, 68)
            shadowPaint.setShadowLayer(10f * d, 0f, 3f * d, Color.argb(120, 239, 68, 68))
            canvas.drawCircle(cx, cy, r - 0.5f * d, shadowPaint)
            shadowPaint.clearShadowLayer()

            bgPaint.shader = RadialGradient(
                cx - r * 0.40f, cy - r * 0.50f, r * 1.45f,
                intArrayOf(
                    Color.parseColor("#FDA4AF"),
                    Color.parseColor("#EF4444"),
                    Color.parseColor("#991B1B")
                ),
                floatArrayOf(0f, 0.50f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx, cy, r, bgPaint)

            glossRect.set(cx - r * 0.65f, cy - r * 0.85f, cx + r * 0.65f, cy - r * 0.12f)
            glossPaint.shader = LinearGradient(
                0f, glossRect.top, 0f, glossRect.bottom,
                intArrayOf(Color.argb(118, 255, 255, 255), Color.TRANSPARENT),
                null,
                Shader.TileMode.CLAMP
            )
            canvas.drawOval(glossRect, glossPaint)
        } else {
            bgPaint.shader = null
            if (isActive) {
                // `.mini-ctrl.on`: rgba(56, 189, 248, 0.28) fill + rgba(56, 189, 248, 0.65) border
                bgPaint.color = Color.argb(72, 56, 189, 248)
                strokePaint.color = Color.argb(166, 56, 189, 248)
            } else {
                // `.mini-ctrl`: rgba(255, 255, 255, 0.08) fill + rgba(255, 255, 255, 0.14) border
                bgPaint.color = Color.argb(22, 255, 255, 255)
                strokePaint.color = Color.argb(38, 255, 255, 255)
            }
            canvas.drawCircle(cx, cy, r, bgPaint)
            canvas.drawCircle(cx, cy, r - 0.55f * d, strokePaint)
        }
        super.onDraw(canvas)
    }
}

/**
 * Inline 4-Bar `#38BDF8` Live Mini Audio Waveform matching `.mini-wave` in both
 * Incoming Call subtitle row and Active "On Call" timer row.
 */
internal class MiniCallWaveformView(context: Context) : View(context) {

    private val d = resources.displayMetrics.density
    private val barWidth = 2f * d
    private val barGap = 2f * d
    private val minBarH = 4f * d
    private val maxBarH = 12f * d
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private var phaseSeconds: Float = 0f

    fun updatePhaseSeconds(seconds: Float) {
        phaseSeconds = seconds
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val totalW = (BAR_COUNT * barWidth + (BAR_COUNT - 1) * barGap).toInt() + 1
        val totalH = maxBarH.toInt() + 1
        setMeasuredDimension(totalW, totalH)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cy = height * 0.5f
        val r = barWidth * 0.5f
        var x = 0f
        for (i in 0 until BAR_COUNT) {
            // 0.9s cycle with 0.15s per-bar phase stagger matching CSS `@keyframes waveBar`
            val cyclePos = ((phaseSeconds - i * 0.15f) / 0.90f)
            val norm = ((1.0 - kotlin.math.cos(cyclePos * 2.0 * PI)) * 0.5).toFloat().coerceIn(0f, 1f)
            val h = minBarH + (maxBarH - minBarH) * norm
            val barAlpha = ((0.55f + 0.45f * norm) * 255f).toInt().coerceIn(0, 255)
            barPaint.color = Color.argb(barAlpha, 56, 189, 248) // #38BDF8
            canvas.drawRoundRect(x, cy - h * 0.5f, x + barWidth, cy + h * 0.5f, r, r, barPaint)
            x += barWidth + barGap
        }
    }

    companion object {
        const val BAR_COUNT = 4
    }
}

/**
 * Active "On Call" `44dp` Circular Caller Avatar with Dark Slate gradient (`#1E293B -> #0F172A`),
 * crisp `2dp` Emerald halo ring (`rgba(16,185,129,0.75)`), soft outer emerald bloom, and `14sp` initials.
 */
internal class ActiveCallAvatarView(context: Context) : View(context) {

    private val d = resources.displayMetrics.density
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * d
        color = Color.argb(192, 16, 185, 129) // rgba(16,185,129,0.75)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        textSize = 14f * d
    }

    var initials: String = ""
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width * 0.5f
        val cy = height * 0.5f
        val r = minOf(width, height) * 0.5f - 2.5f * d

        // Soft emerald outer halo (`0 0 14px rgba(16, 185, 129, 0.35)`)
        glowPaint.color = Color.argb(90, 16, 185, 129)
        glowPaint.setShadowLayer(8f * d, 0f, 0f, Color.argb(110, 16, 185, 129))
        canvas.drawCircle(cx, cy, r, glowPaint)
        glowPaint.clearShadowLayer()

        // 135deg slate fill `#1E293B -> #0F172A`
        fillPaint.shader = LinearGradient(
            cx - r, cy - r, cx + r, cy + r,
            Color.parseColor("#1E293B"),
            Color.parseColor("#0F172A"),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r, fillPaint)

        // 2dp emerald halo ring
        canvas.drawCircle(cx, cy, r, ringPaint)

        // Bold 14sp initials
        val baseline = cy - (textPaint.descent() + textPaint.ascent()) * 0.5f
        canvas.drawText(initials, cx, baseline, textPaint)
    }
}

/**
 * Pulsing `7dp` Emerald Live Dot (`#10B981`, blinking `1.0 <-> 0.35` over `1200ms`).
 */
internal class LivePulseDotView(context: Context) : View(context) {

    private val d = resources.displayMetrics.density
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var pulseAlpha: Float = 1f

    fun updatePhaseSeconds(seconds: Float) {
        val norm = ((sin(seconds * 2.0 * PI / 1.2) + 1.0) * 0.5).toFloat()
        pulseAlpha = 0.35f + 0.65f * norm
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = (11f * d).toInt()
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width * 0.5f
        val cy = height * 0.5f
        val dotR = 3.5f * d
        val a = (pulseAlpha * 255f).toInt().coerceIn(0, 255)
        haloPaint.color = Color.argb((a * 0.42f).toInt(), 16, 185, 129)
        canvas.drawCircle(cx, cy, dotR + 2f * d, haloPaint)
        dotPaint.color = Color.argb(a, 16, 185, 129)
        canvas.drawCircle(cx, cy, dotR, dotPaint)
    }
}
