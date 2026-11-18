package com.example.ui.theme

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.annotation.StyleRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView
import com.example.R
import com.example.data.DialerPreferences
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.color.DynamicColors
import com.google.android.material.floatingactionbutton.FloatingActionButton

/**
 * Curated Full-App 3D Mix-Color Premium Themes for PulseDial.
 * - Free users stay on the standard theme ([EMERALD_PULSE]).
 * - Only active Premium subscribers (`prefs.isPremiumActive()`) can unlock and use the
 *   3 Full-App 3D Mix-Color Premium Themes ([CYBER_NEON_3D], [AURORA_3D_MIX], [ROYAL_GOLD_VIP]),
 *   which transform the entire app background, cards, dialer keypad, navigation bar, and all text.
 */
enum class AppThemePalette(
    val id: String,
    val title: String,
    val subtitle: String,
    val badgeText: String,
    val is3DMix: Boolean,
    val isPremiumTheme: Boolean,
    @param:StyleRes val styleOverlayRes: Int,
    val startColorHex: String,
    val midColorHex: String,
    val endColorHex: String,
    val bgStartHex: String,
    val bgMidHex: String,
    val bgEndHex: String,
    val cardBgHex: String,
    val cardStrokeHex: String,
    val textPrimaryHex: String,
    val textSecondaryHex: String
) {
    EMERALD_PULSE(
        id = "emerald_pulse",
        title = "Standard Classic",
        subtitle = "Default Clean Material 3 Surface",
        badgeText = "STANDARD",
        is3DMix = false,
        isPremiumTheme = false,
        styleOverlayRes = R.style.ThemeOverlay_PulseDial_EmeraldPulse,
        startColorHex = "#0F766E",
        midColorHex = "#1E3A5F",
        endColorHex = "#D97706",
        bgStartHex = "#F8FAFC",
        bgMidHex = "#F1F5F9",
        bgEndHex = "#E2E8F0",
        cardBgHex = "#FFFFFF",
        cardStrokeHex = "#CBD5E1",
        textPrimaryHex = "#0F172A",
        textSecondaryHex = "#475569"
    ),
    CYBER_NEON_3D(
        id = "cyber_neon_3d",
        title = "Cyber Neon 3D Mix",
        subtitle = "Full-App 3D Electric Violet • Cyber Cyan • Neon Magenta",
        badgeText = "PRO 3D MIX #1",
        is3DMix = true,
        isPremiumTheme = true,
        styleOverlayRes = R.style.ThemeOverlay_PulseDial_CyberNeon3D,
        startColorHex = "#6D28D9",
        midColorHex = "#0891B2",
        endColorHex = "#DB2777",
        bgStartHex = "#12072B",
        bgMidHex = "#0D1838",
        bgEndHex = "#280834",
        cardBgHex = "#1E1140",
        cardStrokeHex = "#22D3EE",
        textPrimaryHex = "#F5F3FF",
        textSecondaryHex = "#A5F3FC"
    ),
    AURORA_3D_MIX(
        id = "aurora_3d_mix",
        title = "Aurora Prism 3D Mix",
        subtitle = "Full-App 3D Northern Emerald • Cosmic Indigo • Coral Glow",
        badgeText = "PRO 3D MIX #2",
        is3DMix = true,
        isPremiumTheme = true,
        styleOverlayRes = R.style.ThemeOverlay_PulseDial_Aurora3DMix,
        startColorHex = "#059669",
        midColorHex = "#4F46E5",
        endColorHex = "#E11D48",
        bgStartHex = "#04211D",
        bgMidHex = "#0E163A",
        bgEndHex = "#220A29",
        cardBgHex = "#0A2A32",
        cardStrokeHex = "#34D399",
        textPrimaryHex = "#ECFDF5",
        textSecondaryHex = "#6EE7B7"
    ),
    ROYAL_GOLD_VIP(
        id = "royal_gold_vip",
        title = "Royal Gold & Obsidian 3D",
        subtitle = "Full-App 3D 24K Gold • Velvet Obsidian • Imperial Amber",
        badgeText = "PRO 3D MIX #3",
        is3DMix = true,
        isPremiumTheme = true,
        styleOverlayRes = R.style.ThemeOverlay_PulseDial_RoyalGoldVip,
        startColorHex = "#D97706",
        midColorHex = "#1E293B",
        endColorHex = "#F59E0B",
        bgStartHex = "#171005",
        bgMidHex = "#0F1420",
        bgEndHex = "#291906",
        cardBgHex = "#24190B",
        cardStrokeHex = "#FBBF24",
        textPrimaryHex = "#FFFBEB",
        textSecondaryHex = "#FDE68A"
    );

    fun primaryColorInt(): Int = Color.parseColor(startColorHex)
    fun secondaryColorInt(): Int = Color.parseColor(midColorHex)
    fun tertiaryColorInt(): Int = Color.parseColor(endColorHex)
    fun bgStartColorInt(): Int = Color.parseColor(bgStartHex)
    fun bgMidColorInt(): Int = Color.parseColor(bgMidHex)
    fun bgEndColorInt(): Int = Color.parseColor(bgEndHex)
    fun cardBgColorInt(): Int = Color.parseColor(cardBgHex)
    fun cardStrokeColorInt(): Int = Color.parseColor(cardStrokeHex)
    fun textPrimaryColorInt(): Int = Color.parseColor(textPrimaryHex)
    fun textSecondaryColorInt(): Int = Color.parseColor(textSecondaryHex)

    /**
     * Builds a high-depth 3D multi-color bevel/glass drawable with a 3-stop diagonal gradient
     * and a top specular highlight layer.
     */
    fun create3DGradientDrawable(
        cornerRadiusPx: Float,
        strokeColorInt: Int = Color.argb(90, 255, 255, 255),
        strokeWidthPx: Int = 2
    ): Drawable {
        val baseGradient = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(primaryColorInt(), secondaryColorInt(), tertiaryColorInt())
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = cornerRadiusPx
            if (strokeWidthPx > 0) {
                setStroke(strokeWidthPx, strokeColorInt)
            }
        }

        val specularSheen = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                Color.argb(72, 255, 255, 255),
                Color.argb(18, 255, 255, 255),
                Color.argb(0, 255, 255, 255),
                Color.argb(48, 0, 0, 0)
            )
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = cornerRadiusPx
        }

        return LayerDrawable(arrayOf(baseGradient, specularSheen))
    }

    /**
     * Builds a deep, full-screen 3D Mix-Color background drawable for the entire app (`main_root`,
     * fragments, and bottom sheets) with an ambient 3D depth overlay.
     */
    fun createFullApp3DBackgroundDrawable(cornerRadiusPx: Float = 0f): Drawable {
        val deepGradient = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(bgStartColorInt(), bgMidColorInt(), bgEndColorInt())
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = cornerRadiusPx
        }

        val ambientGlow = GradientDrawable(
            GradientDrawable.Orientation.TR_BL,
            intArrayOf(
                ColorUtils.setAlphaComponent(primaryColorInt(), 58),
                ColorUtils.setAlphaComponent(secondaryColorInt(), 32),
                ColorUtils.setAlphaComponent(tertiaryColorInt(), 52)
            )
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = cornerRadiusPx
        }

        return LayerDrawable(arrayOf(deepGradient, ambientGlow))
    }

    companion object {
        /**
         * Returns the 3 exclusive 3D Mix-Color Full-App Premium Themes.
         */
        fun premium3DMixThemes(): List<AppThemePalette> {
            return listOf(CYBER_NEON_3D, AURORA_3D_MIX, ROYAL_GOLD_VIP)
        }

        fun fromId(id: String?): AppThemePalette {
            return when (id?.trim()?.lowercase().orEmpty()) {
                CYBER_NEON_3D.id -> CYBER_NEON_3D
                AURORA_3D_MIX.id, "oceanic_sapphire_3d" -> AURORA_3D_MIX
                ROYAL_GOLD_VIP.id, "sunset_horizon_3d", "crimson_velvet_pro" -> ROYAL_GOLD_VIP
                else -> EMERALD_PULSE
            }
        }
    }
}

object ThemeStudioManager {

    private const val RECYCLER_3D_LISTENER_TAG_KEY = R.id.top_app_bar

    /**
     * Only active paid / Admin-granted Premium subscribers (`prefs.isPremiumActive()`)
     * can unlock the 3 3D Mix-Color Full-App Premium Themes and full-app 3D background/text styling.
     */
    fun isUserEntitledToPremiumThemes(prefs: DialerPreferences): Boolean {
        return !prefs.adminBlockedUser && (prefs.isPremiumActive() || prefs.isPremiumUser)
    }

    /**
     * Resolves the effective palette for the user. If a user stored a Premium 3D Mix theme ID
     * but their subscription is not active, immediately falls back to [AppThemePalette.EMERALD_PULSE].
     */
    fun resolveEffectivePalette(prefs: DialerPreferences): AppThemePalette {
        val requested = AppThemePalette.fromId(prefs.selectedThemePaletteId)
        if (requested.isPremiumTheme && !isUserEntitledToPremiumThemes(prefs)) {
            return AppThemePalette.EMERALD_PULSE
        }
        return requested
    }

    /**
     * Returns true when the user is an active Premium subscriber and has one of the 3
     * 3D Mix-Color Full-App Themes selected.
     */
    fun isFullApp3DMixThemeActive(prefs: DialerPreferences): Boolean {
        val palette = resolveEffectivePalette(prefs)
        return isUserEntitledToPremiumThemes(prefs) && palette.is3DMix
    }

    /**
     * Applies the user's selected color scheme mode (System Default / Light / Dark),
     * Material You Dynamic Colors (when enabled), and 3D / Premium ThemeOverlay to an Activity.
     * Call inside `Activity.onCreate()` before inflating views.
     */
    fun applyActivityTheme(activity: Activity) {
        val prefs = DialerPreferences(activity)
        val palette = resolveEffectivePalette(prefs)

        if (prefs.useDynamicColors && !palette.is3DMix) {
            if (DynamicColors.isDynamicColorAvailable()) {
                DynamicColors.applyToActivityIfAvailable(activity)
                return
            }
        }
        activity.theme.applyStyle(palette.styleOverlayRes, true)
    }

    /**
     * Styles MainActivity's full app background (`mainRoot`), top app bar (`appBarLayout` & `topAppBar`),
     * VIP 3D accent strip, crown badge, floating dialpad FAB, and bottom navigation bar
     * with the active Full-App 3D Mix-Color Premium Theme when the user is a Premium subscriber.
     */
    fun applyPremiumMainActivityChrome(
        context: Context,
        prefs: DialerPreferences,
        vipAccentBar: View?,
        vipBadgeView: TextView?,
        fabDialpad: FloatingActionButton?,
        bottomNav: BottomNavigationView?,
        mainRoot: View? = null,
        appBarLayout: AppBarLayout? = null,
        topAppBar: MaterialToolbar? = null
    ) {
        val isPremium = isUserEntitledToPremiumThemes(prefs)
        val palette = resolveEffectivePalette(prefs)
        val density = context.resources.displayMetrics.density

        if (isPremium && palette.is3DMix) {
            mainRoot?.background = palette.createFullApp3DBackgroundDrawable()
            appBarLayout?.background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(palette.bgStartColorInt(), palette.bgMidColorInt(), palette.bgEndColorInt())
            )
            topAppBar?.setTitleTextColor(palette.textPrimaryColorInt())
            topAppBar?.setSubtitleTextColor(palette.textSecondaryColorInt())

            vipAccentBar?.visibility = View.VISIBLE
            vipAccentBar?.background = palette.create3DGradientDrawable(
                cornerRadiusPx = 0f,
                strokeWidthPx = 0
            )
            vipBadgeView?.visibility = View.VISIBLE
            vipBadgeView?.text = "👑 ${palette.title}"
            vipBadgeView?.setTextColor(Color.WHITE)
            vipBadgeView?.background = palette.create3DGradientDrawable(
                cornerRadiusPx = 14f * density,
                strokeColorInt = Color.argb(140, 255, 255, 255),
                strokeWidthPx = (1 * density).toInt().coerceAtLeast(1)
            )

            fabDialpad?.backgroundTintList = ColorStateList.valueOf(palette.primaryColorInt())
            fabDialpad?.imageTintList = ColorStateList.valueOf(Color.WHITE)

            bottomNav?.background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(palette.bgStartColorInt(), palette.cardBgColorInt(), palette.bgEndColorInt())
            ).apply {
                setStroke(
                    (1 * density).toInt().coerceAtLeast(1),
                    ColorUtils.setAlphaComponent(palette.cardStrokeColorInt(), 110)
                )
            }
            val navStates = arrayOf(
                intArrayOf(android.R.attr.state_checked),
                intArrayOf(-android.R.attr.state_checked)
            )
            val navColors = intArrayOf(
                palette.cardStrokeColorInt(),
                ColorUtils.setAlphaComponent(palette.textPrimaryColorInt(), 195)
            )
            val navColorList = ColorStateList(navStates, navColors)
            bottomNav?.itemIconTintList = navColorList
            bottomNav?.itemTextColor = navColorList
            bottomNav?.itemActiveIndicatorColor = ColorStateList.valueOf(
                ColorUtils.setAlphaComponent(palette.primaryColorInt(), 90)
            )
            bottomNav?.itemRippleColor = ColorStateList.valueOf(
                ColorUtils.setAlphaComponent(palette.cardStrokeColorInt(), 50)
            )
        } else if (isPremium) {
            vipAccentBar?.visibility = View.VISIBLE
            vipAccentBar?.background = palette.create3DGradientDrawable(
                cornerRadiusPx = 0f,
                strokeWidthPx = 0
            )
            vipBadgeView?.visibility = View.VISIBLE
            vipBadgeView?.text = "👑 VIP PRO"
            vipBadgeView?.background = palette.create3DGradientDrawable(
                cornerRadiusPx = 14f * density,
                strokeColorInt = Color.argb(120, 255, 255, 255),
                strokeWidthPx = (1 * density).toInt().coerceAtLeast(1)
            )
        } else {
            vipAccentBar?.visibility = View.GONE
            vipBadgeView?.visibility = View.GONE
        }
    }

    /**
     * Recursively styles any Fragment or Activity view hierarchy with the selected 3D Mix-Color
     * Full-App Premium Theme (3D background, 3D glass cards, glowing strokes, and custom text colors)
     * when the user is an active Premium subscriber.
     */
    fun applyFullApp3DThemeToViewHierarchy(
        rootView: View?,
        prefs: DialerPreferences,
        applyRootBackground: Boolean = true
    ) {
        if (rootView == null) return
        val isPremium = isUserEntitledToPremiumThemes(prefs)
        val palette = resolveEffectivePalette(prefs)
        if (!isPremium || !palette.is3DMix) return

        val density = rootView.resources.displayMetrics.density
        if (applyRootBackground) {
            rootView.background = palette.createFullApp3DBackgroundDrawable()
        }
        styleNodeRecursively(rootView, palette, density, isRoot = true)
    }

    private fun styleNodeRecursively(
        view: View,
        palette: AppThemePalette,
        density: Float,
        isRoot: Boolean
    ) {
        if (view.id == R.id.banner_theme_3d_preview ||
            view.id == R.id.tv_vip_pro_header_badge ||
            view.id == R.id.tv_dialpad_vip_badge ||
            view.id == R.id.view_vip_3d_top_accent_bar
        ) {
            return
        }

        when (view) {
            is MaterialCardView -> {
                if (view.id != R.id.card_live_banner_ad) {
                    view.setCardBackgroundColor(palette.cardBgColorInt())
                    view.strokeColor = ColorUtils.setAlphaComponent(palette.cardStrokeColorInt(), 165)
                    view.strokeWidth = (1.4f * density).toInt().coerceAtLeast(1)
                }
            }
            is Chip -> {
                val chipStates = arrayOf(
                    intArrayOf(android.R.attr.state_checked),
                    intArrayOf(-android.R.attr.state_checked)
                )
                view.chipBackgroundColor = ColorStateList(
                    chipStates,
                    intArrayOf(palette.primaryColorInt(), palette.cardBgColorInt())
                )
                view.setTextColor(palette.textPrimaryColorInt())
                view.chipStrokeColor = ColorStateList.valueOf(
                    ColorUtils.setAlphaComponent(palette.cardStrokeColorInt(), 180)
                )
                view.chipStrokeWidth = (1.2f * density)
            }
            is MaterialButton -> {
                if (view.backgroundTintList != null &&
                    view.strokeWidth == 0
                ) {
                    view.setTextColor(Color.WHITE)
                } else {
                    view.setTextColor(palette.textSecondaryColorInt())
                    view.strokeColor = ColorStateList.valueOf(
                        ColorUtils.setAlphaComponent(palette.cardStrokeColorInt(), 170)
                    )
                    view.iconTint = ColorStateList.valueOf(palette.textSecondaryColorInt())
                }
            }
            is EditText -> {
                view.setTextColor(palette.textPrimaryColorInt())
                view.setHintTextColor(
                    ColorUtils.setAlphaComponent(palette.textSecondaryColorInt(), 175)
                )
            }
            is TextView -> {
                val isBold = view.typeface?.style == Typeface.BOLD ||
                    view.typeface?.isBold == true ||
                    view.textSize >= (15.5f * density)
                if (isBold) {
                    view.setTextColor(palette.textPrimaryColorInt())
                } else {
                    view.setTextColor(palette.textSecondaryColorInt())
                }
            }
        }

        if (view is RecyclerView) {
            for (i in 0 until view.childCount) {
                styleNodeRecursively(view.getChildAt(i), palette, density, isRoot = false)
            }
            if (view.getTag(RECYCLER_3D_LISTENER_TAG_KEY) != palette.id) {
                view.setTag(RECYCLER_3D_LISTENER_TAG_KEY, palette.id)
                view.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
                    override fun onChildViewAttachedToWindow(child: View) {
                        val currentPrefs = DialerPreferences(child.context.applicationContext)
                        val currentPalette = resolveEffectivePalette(currentPrefs)
                        if (isUserEntitledToPremiumThemes(currentPrefs) && currentPalette.is3DMix) {
                            styleNodeRecursively(
                                child,
                                currentPalette,
                                child.resources.displayMetrics.density,
                                isRoot = false
                            )
                        }
                    }

                    override fun onChildViewDetachedFromWindow(child: View) = Unit
                })
            }
            return
        }

        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                styleNodeRecursively(view.getChildAt(i), palette, density, isRoot = false)
            }
        }
    }

    /**
     * Styles the Dialpad Bottom Sheet (`bottom_sheet_dialpad.xml`) with Full-App 3D Mix-Color
     * background, 3D glass keys, and custom text colors when the user is an active Premium subscriber.
     */
    fun applyPremiumDialpadChrome(
        context: Context,
        prefs: DialerPreferences,
        vipHeaderBadge: TextView?,
        displayCard: MaterialCardView?,
        keyCards: List<MaterialCardView>,
        callButton: MaterialButton?,
        rootContainer: View? = null
    ) {
        val isPremium = isUserEntitledToPremiumThemes(prefs)
        val palette = resolveEffectivePalette(prefs)
        val density = context.resources.displayMetrics.density

        if (isPremium && palette.is3DMix) {
            rootContainer?.background = palette.createFullApp3DBackgroundDrawable(
                cornerRadiusPx = 28f * density
            )
            if (rootContainer != null) {
                applyFullApp3DThemeToViewHierarchy(
                    rootView = rootContainer,
                    prefs = prefs,
                    applyRootBackground = false
                )
            }
            vipHeaderBadge?.visibility = View.VISIBLE
            vipHeaderBadge?.text = "👑 VIP 3D DIALER • ${palette.title}"
            vipHeaderBadge?.setTextColor(Color.WHITE)
            vipHeaderBadge?.background = palette.create3DGradientDrawable(
                cornerRadiusPx = 12f * density,
                strokeColorInt = Color.argb(130, 255, 255, 255),
                strokeWidthPx = (1 * density).toInt().coerceAtLeast(1)
            )
            displayCard?.setCardBackgroundColor(palette.cardBgColorInt())
            displayCard?.strokeColor = ColorUtils.setAlphaComponent(palette.cardStrokeColorInt(), 190)
            displayCard?.strokeWidth = (1.6f * density).toInt().coerceAtLeast(1)

            val keyStrokeColor = ColorUtils.setAlphaComponent(palette.cardStrokeColorInt(), 145)
            val keyRippleColor = ColorStateList.valueOf(
                ColorUtils.setAlphaComponent(palette.cardStrokeColorInt(), 58)
            )
            keyCards.forEach { card ->
                card.setCardBackgroundColor(palette.cardBgColorInt())
                card.strokeColor = keyStrokeColor
                card.strokeWidth = (1.4f * density).toInt().coerceAtLeast(1)
                card.rippleColor = keyRippleColor
                styleNodeRecursively(card, palette, density, isRoot = false)
            }
            callButton?.backgroundTintList = ColorStateList.valueOf(palette.primaryColorInt())
            callButton?.setTextColor(Color.WHITE)
        } else if (isPremium) {
            vipHeaderBadge?.visibility = View.VISIBLE
            vipHeaderBadge?.text = "👑 VIP PRO DIALER"
            vipHeaderBadge?.background = palette.create3DGradientDrawable(
                cornerRadiusPx = 12f * density,
                strokeColorInt = Color.argb(110, 255, 255, 255),
                strokeWidthPx = (1 * density).toInt().coerceAtLeast(1)
            )
        } else {
            vipHeaderBadge?.visibility = View.GONE
        }
    }

    fun resolveThemeModeLabel(context: Context, nightMode: Int): String {
        return when (nightMode) {
            AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM ->
                context.getString(R.string.setting_theme_system_default)
            AppCompatDelegate.MODE_NIGHT_YES ->
                context.getString(R.string.setting_theme_dark)
            else ->
                context.getString(R.string.setting_theme_light)
        }
    }

    fun buildThemeSummary(context: Context, prefs: DialerPreferences): String {
        val modeLabel = resolveThemeModeLabel(context, prefs.displayThemeMode)
        val palette = resolveEffectivePalette(prefs)
        val dynamicTag = if (prefs.useDynamicColors && !palette.is3DMix) " • Dynamic Colors" else ""
        return "$modeLabel • ${palette.title}$dynamicTag"
    }
}
