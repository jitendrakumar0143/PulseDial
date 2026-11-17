package com.example.telecom

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Optional floating system overlay popup shown when [Settings.canDrawOverlays] is granted.
 * Displays the identified Caller Name & Geographic Location on incoming and outgoing calls even if
 * the user minimizes or navigates away from [com.example.ui.incall.InCallActivity].
 * Always wrapped safely so restricted environments / Robolectric never crash.
 */
object CallerIdPopupOverlay {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var attachedView: LinearLayout? = null
    private var windowManager: WindowManager? = null
    private var tvDirection: TextView? = null
    private var tvName: TextView? = null
    private var tvLocation: TextView? = null
    private var tvNumber: TextView? = null

    fun showOrUpdate(
        context: Context,
        info: CallerIdentityInfo,
        isIncoming: Boolean
    ) {
        val appContext = context.applicationContext
        val canDraw = runCatching { Settings.canDrawOverlays(appContext) }.getOrDefault(false)
        if (!canDraw) return

        mainHandler.post {
            runCatching {
                val wm = windowManager ?: appContext.getSystemService(WindowManager::class.java)
                    ?: return@runCatching
                windowManager = wm

                if (attachedView == null) {
                    val card = buildPopupView(appContext)
                    val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    } else {
                        @Suppress("DEPRECATION")
                        WindowManager.LayoutParams.TYPE_PHONE
                    }
                    val params = WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        type,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
                        PixelFormat.TRANSLUCENT
                    ).apply {
                        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                        y = 96
                    }
                    wm.addView(card, params)
                    attachedView = card
                }

                val directionLabel = if (isIncoming) {
                    "INCOMING CALLER ID"
                } else {
                    "OUTGOING CALLER ID"
                }
                val unknownTag = if (info.isUnknownNumber) " • UNKNOWN NUMBER" else ""
                tvDirection?.text = "$directionLabel$unknownTag"
                tvName?.text = info.displayName
                tvLocation?.text = "Location: ${info.location.ifBlank { CallerIdResolver.UNKNOWN_LOCATION }}"
                tvNumber?.text = "${info.formattedNumber} (${info.sourceLabel})"
            }
        }
    }

    fun dismiss() {
        mainHandler.post {
            runCatching {
                attachedView?.let { view ->
                    windowManager?.removeViewImmediate(view)
                }
            }
            attachedView = null
            tvDirection = null
            tvName = null
            tvLocation = null
            tvNumber = null
        }
    }

    private fun buildPopupView(context: Context): LinearLayout {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 28, 36, 28)
            val margin = 24
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(margin, 0, margin, 0)
            }
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EE0F172A"))
                cornerRadius = 32f
                setStroke(2, Color.parseColor("#2DD4BF"))
            }
            elevation = 16f
        }

        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val dirText = TextView(context).apply {
            textSize = 11f
            setTextColor(Color.parseColor("#2DD4BF"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        tvDirection = dirText

        val closeBtn = ImageButton(context).apply {
            setImageResource(com.example.R.drawable.ic_close)
            setColorFilter(Color.parseColor("#CBD5E1"))
            setBackgroundColor(Color.TRANSPARENT)
            contentDescription = "Dismiss Caller ID Popup"
            setOnClickListener { dismiss() }
        }

        headerRow.addView(dirText)
        headerRow.addView(closeBtn)

        val nameText = TextView(context).apply {
            textSize = 18f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        tvName = nameText

        val locText = TextView(context).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#38BDF8"))
            setPadding(0, 6, 0, 4)
        }
        tvLocation = locText

        val numText = TextView(context).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#94A3B8"))
        }
        tvNumber = numText

        container.addView(headerRow)
        container.addView(nameText)
        container.addView(locText)
        container.addView(numText)
        return container
    }
}
