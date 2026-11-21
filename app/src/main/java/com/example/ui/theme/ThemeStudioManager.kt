package com.example.ui.theme

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.StyleRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.graphics.ColorUtils
import androidx.core.widget.CompoundButtonCompat
import androidx.core.widget.ImageViewCompat
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
import com.google.android.material.divider.MaterialDivider
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputLayout

/**
 * Curated Full-App 3D Mix-Color Premium Themes for PulseDial.
 * - Free users stay on the standard theme ([EMERALD_PULSE]).
 * - Only active Premium subscribers (`prefs.isPremiumActive()`) can unlock and use the
 *   3 Full-App 3D Mix-Color Premium Themes ([CYBER_NEON_3D], [AURORA_3D_MIX], [ROYAL_GOLD_VIP]),
 *   which transform the entire app background, cards, dialer keypad, navigation bar, in-call 3D UI, and text.
 *
 * All palettes are calibrated for razor-sharp, zero-bloom WCAG AAA/AA typography:
 * - Pure high-contrast primary text (`#FFFFFF` on dark 3D obsidian surfaces, `#0B1220` on light)
 * - Clean neutral silver/slate secondary text (no saturated neon body text that causes chromatic blur)
 * - Deep studio glass cards with restrained 1dp specular borders and readable dark scrims behind badges/banners.
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
        subtitle = "Crisp Material 3 Studio Surface",
        badgeText = "STANDARD",
        is3DMix = false,
        isPremiumTheme = false,
        styleOverlayRes = R.style.ThemeOverlay_PulseDial_EmeraldPulse,
        startColorHex = "#0D736A",
        midColorHex = "#1E3A5F",
        endColorHex = "#C26A06",
        bgStartHex = "#F4F7FB",
        bgMidHex = "#EDF2F7",
        bgEndHex = "#E2E8F0",
        cardBgHex = "#FFFFFF",
        cardStrokeHex = "#CBD5E1",
        textPrimaryHex = "#0B1220",
        textSecondaryHex = "#334155"
    ),
    CYBER_NEON_3D(
        id = "cyber_neon_3d",
        title = "Cyber Neon 3D Mix",
        subtitle = "Full-App 3D Electric Violet • Cyber Cyan • Neon Magenta",
        badgeText = "PRO 3D MIX #1",
        is3DMix = true,
        isPremiumTheme = true,
        styleOverlayRes = R.style.ThemeOverlay_PulseDial_CyberNeon3D,
        startColorHex = "#8B5CF6",
        midColorHex = "#06B6D4",
        endColorHex = "#EC4899",
        bgStartHex = "#090418",
        bgMidHex = "#0B132B",
        bgEndHex = "#1B0628",
        cardBgHex = "#130D2B",
        cardStrokeHex = "#38BDF8",
        textPrimaryHex = "#FFFFFF",
        textSecondaryHex = "#D4DFEE"
    ),
    AURORA_3D_MIX(
        id = "aurora_3d_mix",
        title = "Aurora Prism 3D Mix",
        subtitle = "Full-App 3D Northern Emerald • Cosmic Indigo • Coral Glow",
        badgeText = "PRO 3D MIX #2",
        is3DMix = true,
        isPremiumTheme = true,
        styleOverlayRes = R.style.ThemeOverlay_PulseDial_Aurora3DMix,
        startColorHex = "#10B981",
        midColorHex = "#6366F1",
        endColorHex = "#F43F5E",
        bgStartHex = "#041317",
        bgMidHex = "#09132B",
        bgEndHex = "#1A0821",
        cardBgHex = "#0D1E28",
        cardStrokeHex = "#34D399",
        textPrimaryHex = "#FFFFFF",
        textSecondaryHex = "#D2E2E8"
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
        endColorHex = "#EA580C",
        bgStartHex = "#0E0A05",
        bgMidHex = "#0F141F",
        bgEndHex = "#1E1206",
        cardBgHex = "#1A140C",
        cardStrokeHex = "#FBBF24",
        textPrimaryHex = "#FFFFFF",
        textSecondaryHex = "#E2DDD3"
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
     * Smooth 5-stop color-mixed gradient palette eliminating harsh color transitions.
     */
    private fun mixedAccentStops(): IntArray {
        val c1 = primaryColorInt()
        val c2 = secondaryColorInt()
        val c3 = tertiaryColorInt()
        val bridge12 = ColorUtils.blendARGB(c1, c2, 0.5f)
        val bridge23 = ColorUtils.blendARGB(c2, c3, 0.5f)
        return intArrayOf(c1, bridge12, c2, bridge23, c3)
    }

    /**
     * Builds a studio-grade 3D multi-color bevel/glass drawable with 5-stop diagonal color mixing,
     * a subtle dark depth scrim for crisp foreground legibility, and a delicate top specular rim
     * (never milky white glare behind white text).
     */
    fun create3DGradientDrawable(
        cornerRadiusPx: Float,
        strokeColorInt: Int = Color.argb(120, 255, 255, 255),
        strokeWidthPx: Int = 2
    ): Drawable {
        val baseGradient = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            mixedAccentStops()
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = cornerRadiusPx
            if (strokeWidthPx > 0) {
                setStroke(strokeWidthPx, strokeColorInt)
            }
        }

        val depthGlow = GradientDrawable(
            GradientDrawable.Orientation.BL_TR,
            intArrayOf(
                ColorUtils.setAlphaComponent(tertiaryColorInt(), 55),
                Color.TRANSPARENT,
                ColorUtils.setAlphaComponent(cardStrokeColorInt(), 45)
            )
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = cornerRadiusPx
        }

        val legibilityRim = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                Color.argb(30, 255, 255, 255),
                Color.argb(28, 4, 8, 18),
                Color.argb(88, 4, 8, 18)
            )
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = cornerRadiusPx
        }

        return LayerDrawable(arrayOf(baseGradient, depthGlow, legibilityRim))
    }

    /**
     * Builds an ultra-crisp smoked-glass pill badge drawable for header pills and theme tags.
     * Ensures >10:1 contrast for pure white bold text with zero specular glare or washout.
     */
    fun createCrispBadgePillDrawable(
        cornerRadiusPx: Float,
        strokeWidthPx: Int = 2
    ): Drawable {
        val deepAnchor = if (is3DMix) bgStartColorInt() else Color.parseColor("#0B1626")
        val pStart = ColorUtils.blendARGB(deepAnchor, primaryColorInt(), 0.42f)
        val pMid = ColorUtils.blendARGB(deepAnchor, secondaryColorInt(), 0.34f)
        val pEnd = ColorUtils.blendARGB(deepAnchor, tertiaryColorInt(), 0.34f)

        val base = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(pStart, pMid, pEnd)
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = cornerRadiusPx
            if (strokeWidthPx > 0) {
                setStroke(
                    strokeWidthPx,
                    ColorUtils.setAlphaComponent(cardStrokeColorInt(), 210)
                )
            }
        }

        val subtleDepth = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                Color.argb(22, 255, 255, 255),
                Color.argb(36, 4, 8, 18),
                Color.argb(80, 4, 8, 18)
            )
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = cornerRadiusPx
        }

        return LayerDrawable(arrayOf(base, subtleDepth))
    }

    /**
     * Builds a studio-grade 3D showcase banner for Theme Studio previews and gallery cards.
     * Applies a smooth directional dark legibility scrim behind the left text zone so titles
     * and subtitles render razor-sharp while the 3D multi-color prism gleams cleanly across the card.
     */
    fun create3DThemeShowcaseBannerDrawable(
        cornerRadiusPx: Float,
        strokeWidthPx: Int = 2
    ): Drawable {
        val anchor = if (is3DMix) bgStartColorInt() else Color.parseColor("#0B192C")
        val c1 = ColorUtils.blendARGB(anchor, primaryColorInt(), if (is3DMix) 0.45f else 0.72f)
        val c2 = ColorUtils.blendARGB(anchor, secondaryColorInt(), if (is3DMix) 0.42f else 0.65f)
        val c3 = ColorUtils.blendARGB(anchor, tertiaryColorInt(), if (is3DMix) 0.38f else 0.42f)

        val prismGradient = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(c1, ColorUtils.blendARGB(c1, c2, 0.5f), c2, c3)
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = cornerRadiusPx
            if (strokeWidthPx > 0) {
                setStroke(
                    strokeWidthPx,
                    ColorUtils.setAlphaComponent(cardStrokeColorInt(), 165)
                )
            }
        }

        val textReadabilityScrim = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(
                Color.argb(148, 5, 10, 20),
                Color.argb(110, 5, 10, 20),
                Color.argb(56, 5, 10, 20)
            )
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = cornerRadiusPx
        }

        val topRim = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                Color.argb(24, 255, 255, 255),
                Color.TRANSPARENT,
                Color.argb(56, 0, 0, 0)
            )
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = cornerRadiusPx
        }

        return LayerDrawable(arrayOf(prismGradient, textReadabilityScrim, topRim))
    }

    /**
     * Builds a deep, clean 5-stop full-screen 3D Mix-Color studio background
     * with restrained atmospheric depth so foreground cards and crisp text stand out sharply
     * with zero misty haze.
     */
    fun createFullApp3DBackgroundDrawable(cornerRadiusPx: Float = 0f): Drawable {
        val b1 = bgStartColorInt()
        val b2 = bgMidColorInt()
        val b3 = bgEndColorInt()
        val deepGradient = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                b1,
                ColorUtils.blendARGB(b1, b2, 0.5f),
                b2,
                ColorUtils.blendARGB(b2, b3, 0.5f),
                b3
            )
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = cornerRadiusPx
        }

        val ambientGlow = GradientDrawable(
            GradientDrawable.Orientation.TR_BL,
            intArrayOf(
                ColorUtils.setAlphaComponent(primaryColorInt(), 32),
                ColorUtils.setAlphaComponent(secondaryColorInt(), 18),
                ColorUtils.setAlphaComponent(tertiaryColorInt(), 28)
            )
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = cornerRadiusPx
        }

        val topRadialDepth = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = cornerRadiusPx
            gradientType = GradientDrawable.RADIAL_GRADIENT
            setGradientCenter(0.5f, 0.12f)
            gradientRadius = 720f
            colors = intArrayOf(
                ColorUtils.setAlphaComponent(cardStrokeColorInt(), 24),
                ColorUtils.setAlphaComponent(primaryColorInt(), 10),
                Color.TRANSPARENT
            )
        }

        return LayerDrawable(arrayOf(deepGradient, ambientGlow, topRadialDepth))
    }

    /**
     * Builds a sleek dark obsidian-tinted 3D glass card surface with crisp inner contrast
     * and a refined translucent specular stroke.
     */
    fun create3DGlassCardDrawable(
        cornerRadiusPx: Float,
        strokeWidthPx: Int = 2
    ): Drawable {
        val cBase = cardBgColorInt()
        val cTop = ColorUtils.blendARGB(cBase, primaryColorInt(), 0.11f)
        val cMid = ColorUtils.blendARGB(cBase, secondaryColorInt(), 0.08f)
        val cBottom = ColorUtils.blendARGB(cBase, tertiaryColorInt(), 0.09f)

        val surfaceGradient = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(cTop, cMid, cBottom)
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = cornerRadiusPx
            if (strokeWidthPx > 0) {
                setStroke(
                    strokeWidthPx,
                    ColorUtils.setAlphaComponent(cardStrokeColorInt(), 118)
                )
            }
        }

        val topBevel = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                Color.argb(18, 255, 255, 255),
                Color.TRANSPARENT,
                Color.argb(36, 0, 0, 0)
            )
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = cornerRadiusPx
        }

        return LayerDrawable(arrayOf(surfaceGradient, topBevel))
    }

    /**
     * Builds a dark tinted studio glass tile for icons and circular avatars on 3D themes,
     * preventing bright Light-Mode pastel icon boxes from glowing stark white on dark cards.
     */
    fun createDarkGlassIconTileDrawable(
        cornerRadiusPx: Float,
        isOval: Boolean = false,
        strokeWidthPx: Int = 2
    ): Drawable {
        val tileStart = ColorUtils.blendARGB(cardBgColorInt(), primaryColorInt(), 0.28f)
        val tileEnd = ColorUtils.blendARGB(cardBgColorInt(), secondaryColorInt(), 0.22f)
        return GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(tileStart, tileEnd)
        ).apply {
            shape = if (isOval) GradientDrawable.OVAL else GradientDrawable.RECTANGLE
            if (!isOval) {
                cornerRadius = cornerRadiusPx
            }
            if (strokeWidthPx > 0) {
                setStroke(
                    strokeWidthPx,
                    ColorUtils.setAlphaComponent(cardStrokeColorInt(), 115)
                )
            }
        }
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
    private const val HIERARCHY_3D_PALETTE_TAG_KEY = R.id.tv_vip_pro_header_badge

    /**
     * Enables high-precision subpixel anti-aliasing and removes any fuzzy shadow layers
     * so typography renders razor-sharp across every theme and screen density.
     */
    fun applyCrispTypography(textView: TextView) {
        textView.paint.flags = textView.paint.flags or
            Paint.ANTI_ALIAS_FLAG or
            Paint.SUBPIXEL_TEXT_FLAG or
            Paint.FILTER_BITMAP_FLAG
        textView.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
    }

    private fun applyCrispTypographyRecursively(view: View) {
        if (view is TextView) {
            applyCrispTypography(view)
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                applyCrispTypographyRecursively(view.getChildAt(i))
            }
        }
    }

    /**
     * Only active paid / Admin-granted Premium subscribers (`prefs.isPremiumActive()`)
     * can unlock the 3 3D Mix-Color Full-App Premium Themes and full-app 3D background/text styling.
     */
    fun isUserEntitledToPremiumThemes(prefs: DialerPreferences): Boolean {
        return !prefs.adminBlockedUser && prefs.isPremiumActive()
    }

    /**
     * Resolves the effective night mode for the user:
     * - Free (non-premium) users stay strictly on Simple Light Mode (`MODE_NIGHT_NO`).
     * - Active Premium subscribers can freely toggle Dark Mode ON/OFF or System Default.
     */
    fun resolveEffectiveNightMode(prefs: DialerPreferences): Int {
        if (!isUserEntitledToPremiumThemes(prefs)) {
            return AppCompatDelegate.MODE_NIGHT_NO
        }
        return when (prefs.displayThemeMode) {
            AppCompatDelegate.MODE_NIGHT_YES,
            AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
            AppCompatDelegate.MODE_NIGHT_NO -> prefs.displayThemeMode
            else -> AppCompatDelegate.MODE_NIGHT_NO
        }
    }

    /**
     * Applies the resolved night mode on the main thread when it differs from the current delegate state.
     */
    fun enforceEffectiveNightMode(prefs: DialerPreferences) {
        val effectiveMode = resolveEffectiveNightMode(prefs)
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper() &&
            AppCompatDelegate.getDefaultNightMode() != effectiveMode
        ) {
            AppCompatDelegate.setDefaultNightMode(effectiveMode)
        }
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
     * Resolves the best 3D Mix-Color palette for the flagship In-Call 3D experience:
     * uses the subscriber's selected 3D Mix palette when active, or [AppThemePalette.AURORA_3D_MIX]
     * as the signature PulseDial luxury call canvas.
     */
    fun resolveInCall3DPalette(prefs: DialerPreferences): AppThemePalette {
        val active = resolveEffectivePalette(prefs)
        return if (active.is3DMix) active else AppThemePalette.AURORA_3D_MIX
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

        if (isUserEntitledToPremiumThemes(prefs) && prefs.useDynamicColors && !palette.is3DMix) {
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
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(
                    palette.bgStartColorInt(),
                    ColorUtils.blendARGB(palette.bgMidColorInt(), palette.primaryColorInt(), 0.14f),
                    palette.bgEndColorInt()
                )
            )
            topAppBar?.setTitleTextColor(palette.textPrimaryColorInt())
            topAppBar?.setSubtitleTextColor(palette.textSecondaryColorInt())

            vipAccentBar?.visibility = View.VISIBLE
            vipAccentBar?.background = palette.create3DGradientDrawable(
                cornerRadiusPx = 0f,
                strokeWidthPx = 0
            )
            vipBadgeView?.let { badge ->
                badge.visibility = View.VISIBLE
                badge.text = "👑 ${palette.title}"
                badge.setTextColor(Color.WHITE)
                applyCrispTypography(badge)
                badge.background = palette.createCrispBadgePillDrawable(
                    cornerRadiusPx = 16f * density,
                    strokeWidthPx = (1.1f * density).toInt().coerceAtLeast(1)
                )
            }

            val fabFill = ColorUtils.blendARGB(palette.primaryColorInt(), palette.bgMidColorInt(), 0.16f)
            fabDialpad?.backgroundTintList = ColorStateList.valueOf(fabFill)
            fabDialpad?.imageTintList = ColorStateList.valueOf(Color.WHITE)

            bottomNav?.background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(
                    palette.bgStartColorInt(),
                    ColorUtils.blendARGB(palette.cardBgColorInt(), palette.bgMidColorInt(), 0.35f),
                    palette.bgEndColorInt()
                )
            ).apply {
                setStroke(
                    (1f * density).toInt().coerceAtLeast(1),
                    ColorUtils.setAlphaComponent(palette.cardStrokeColorInt(), 95)
                )
            }
            val navStates = arrayOf(
                intArrayOf(android.R.attr.state_checked),
                intArrayOf(-android.R.attr.state_checked)
            )
            val navIconColors = intArrayOf(
                Color.WHITE,
                ColorUtils.setAlphaComponent(palette.textSecondaryColorInt(), 215)
            )
            val navTextColors = intArrayOf(
                palette.cardStrokeColorInt(),
                ColorUtils.setAlphaComponent(palette.textSecondaryColorInt(), 215)
            )
            bottomNav?.itemIconTintList = ColorStateList(navStates, navIconColors)
            bottomNav?.itemTextColor = ColorStateList(navStates, navTextColors)
            bottomNav?.itemActiveIndicatorColor = ColorStateList.valueOf(
                ColorUtils.blendARGB(palette.cardBgColorInt(), palette.primaryColorInt(), 0.48f)
            )
            bottomNav?.itemRippleColor = ColorStateList.valueOf(
                ColorUtils.setAlphaComponent(palette.cardStrokeColorInt(), 42)
            )
        } else if (isPremium) {
            vipAccentBar?.visibility = View.VISIBLE
            vipAccentBar?.background = palette.create3DGradientDrawable(
                cornerRadiusPx = 0f,
                strokeWidthPx = 0
            )
            vipBadgeView?.let { badge ->
                badge.visibility = View.VISIBLE
                badge.text = "👑 VIP PRO"
                badge.setTextColor(Color.WHITE)
                applyCrispTypography(badge)
                badge.background = palette.createCrispBadgePillDrawable(
                    cornerRadiusPx = 16f * density,
                    strokeWidthPx = (1.1f * density).toInt().coerceAtLeast(1)
                )
            }
        } else {
            vipAccentBar?.visibility = View.GONE
            vipBadgeView?.visibility = View.GONE
        }
    }

    /**
     * Recursively styles any Fragment or Activity view hierarchy with crisp typography,
     * and applies the selected 3D Mix-Color Full-App Premium Theme (deep studio background,
     * crisp obsidian glass cards, refined borders, switches, chips, inputs, and high-contrast text)
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
        if (!isPremium || !palette.is3DMix) {
            applyCrispTypographyRecursively(rootView)
            return
        }

        val appliedKey = "${palette.id}|$applyRootBackground"
        if (rootView.getTag(HIERARCHY_3D_PALETTE_TAG_KEY) == appliedKey) return
        rootView.setTag(HIERARCHY_3D_PALETTE_TAG_KEY, appliedKey)

        val density = rootView.resources.displayMetrics.density
        if (applyRootBackground) {
            rootView.background = palette.createFullApp3DBackgroundDrawable()
        }
        styleNodeRecursively(rootView, palette, density)
    }

    /**
     * Forces re-application of the active 3D theme on the next pass (e.g. immediately after
     * switching themes or dynamically binding new rows).
     */
    fun invalidateHierarchyThemeTag(rootView: View?) {
        rootView?.setTag(HIERARCHY_3D_PALETTE_TAG_KEY, null)
    }

    private fun styleNodeRecursively(
        view: View,
        palette: AppThemePalette,
        density: Float
    ) {
        if (view.id == R.id.banner_theme_3d_preview ||
            view.id == R.id.tv_vip_pro_header_badge ||
            view.id == R.id.tv_dialpad_vip_badge ||
            view.id == R.id.tv_active_theme_badge ||
            view.id == R.id.view_vip_3d_top_accent_bar
        ) {
            applyCrispTypographyRecursively(view)
            return
        }

        val checkedStates = arrayOf(
            intArrayOf(android.R.attr.state_checked),
            intArrayOf(-android.R.attr.state_checked)
        )
        val accentFill = ColorUtils.blendARGB(palette.primaryColorInt(), palette.bgMidColorInt(), 0.28f)
        val elevatedSurface = ColorUtils.blendARGB(palette.cardBgColorInt(), palette.bgMidColorInt(), 0.42f)
        val refinedStroke = ColorUtils.setAlphaComponent(palette.cardStrokeColorInt(), 118)
        val hairlinePx = (1f * density).toInt().coerceAtLeast(1)

        when (view) {
            is MaterialCardView -> {
                if (view.id != R.id.card_live_banner_ad && view.id != R.id.card_trial_locked_banner) {
                    view.setCardBackgroundColor(palette.cardBgColorInt())
                    view.strokeColor = refinedStroke
                    view.strokeWidth = hairlinePx
                }
            }
            is TextInputLayout -> {
                view.boxBackgroundColor = elevatedSurface
                view.boxStrokeColor = palette.cardStrokeColorInt()
                view.defaultHintTextColor = ColorStateList.valueOf(
                    ColorUtils.setAlphaComponent(palette.textSecondaryColorInt(), 210)
                )
                view.hintTextColor = ColorStateList.valueOf(palette.cardStrokeColorInt())
                view.setStartIconTintList(ColorStateList.valueOf(palette.cardStrokeColorInt()))
                view.setEndIconTintList(ColorStateList.valueOf(palette.textSecondaryColorInt()))
            }
            is MaterialSwitch -> {
                view.setTextColor(palette.textPrimaryColorInt())
                applyCrispTypography(view)
                val offTrack = ColorUtils.blendARGB(palette.cardBgColorInt(), Color.WHITE, 0.14f)
                view.trackTintList = ColorStateList(
                    checkedStates,
                    intArrayOf(accentFill, offTrack)
                )
                view.thumbTintList = ColorStateList(
                    checkedStates,
                    intArrayOf(Color.WHITE, Color.parseColor("#CBD5E1"))
                )
                view.trackDecorationTintList = ColorStateList(
                    checkedStates,
                    intArrayOf(
                        ColorUtils.setAlphaComponent(palette.cardStrokeColorInt(), 210),
                        ColorUtils.setAlphaComponent(palette.cardStrokeColorInt(), 120)
                    )
                )
            }
            is Chip -> {
                view.chipBackgroundColor = ColorStateList(
                    checkedStates,
                    intArrayOf(accentFill, elevatedSurface)
                )
                view.setTextColor(
                    ColorStateList(
                        checkedStates,
                        intArrayOf(Color.WHITE, palette.textSecondaryColorInt())
                    )
                )
                view.checkedIconTint = ColorStateList.valueOf(Color.WHITE)
                view.chipIconTint = ColorStateList(
                    checkedStates,
                    intArrayOf(Color.WHITE, palette.cardStrokeColorInt())
                )
                view.chipStrokeColor = ColorStateList(
                    checkedStates,
                    intArrayOf(
                        ColorUtils.setAlphaComponent(palette.cardStrokeColorInt(), 230),
                        refinedStroke
                    )
                )
                view.chipStrokeWidth = 1f * density
                applyCrispTypography(view)
            }
            is CompoundButton -> {
                view.setTextColor(palette.textPrimaryColorInt())
                CompoundButtonCompat.setButtonTintList(
                    view,
                    ColorStateList(
                        checkedStates,
                        intArrayOf(palette.cardStrokeColorInt(), palette.textSecondaryColorInt())
                    )
                )
                applyCrispTypography(view)
            }
            is MaterialButton -> {
                val bgTint = view.backgroundTintList
                val isTransparentTextBtn = view.strokeWidth == 0 &&
                    (bgTint == null || Color.alpha(bgTint.defaultColor) == 0)
                when {
                    isTransparentTextBtn -> {
                        view.setTextColor(palette.cardStrokeColorInt())
                        view.iconTint = ColorStateList.valueOf(palette.cardStrokeColorInt())
                    }
                    view.strokeWidth > 0 -> {
                        view.backgroundTintList = ColorStateList.valueOf(elevatedSurface)
                        view.setTextColor(Color.WHITE)
                        view.strokeColor = ColorStateList.valueOf(
                            ColorUtils.setAlphaComponent(palette.cardStrokeColorInt(), 165)
                        )
                        view.strokeWidth = hairlinePx
                        view.iconTint = ColorStateList.valueOf(palette.cardStrokeColorInt())
                    }
                    else -> {
                        view.backgroundTintList = ColorStateList.valueOf(accentFill)
                        view.setTextColor(Color.WHITE)
                        view.iconTint = ColorStateList.valueOf(Color.WHITE)
                        view.strokeColor = ColorStateList.valueOf(
                            ColorUtils.setAlphaComponent(palette.cardStrokeColorInt(), 140)
                        )
                        view.strokeWidth = hairlinePx
                    }
                }
                applyCrispTypography(view)
            }
            is EditText -> {
                view.setTextColor(palette.textPrimaryColorInt())
                view.setHintTextColor(
                    ColorUtils.setAlphaComponent(palette.textSecondaryColorInt(), 185)
                )
                applyCrispTypography(view)
            }
            is TextView -> {
                if (view.id == R.id.tv_avatar_initial) {
                    view.background = palette.createDarkGlassIconTileDrawable(
                        cornerRadiusPx = 24f * density,
                        isOval = true,
                        strokeWidthPx = hairlinePx
                    )
                    view.setTextColor(Color.WHITE)
                } else if (view.id == R.id.tv_caller_location_line) {
                    view.setTextColor(palette.cardStrokeColorInt())
                } else {
                    val isPrimaryText = view.typeface?.style == Typeface.BOLD ||
                        view.typeface?.isBold == true ||
                        view.textSize >= (15f * density)
                    if (isPrimaryText) {
                        view.setTextColor(palette.textPrimaryColorInt())
                    } else {
                        view.setTextColor(palette.textSecondaryColorInt())
                    }
                }
                applyCrispTypography(view)
            }
            is MaterialDivider -> {
                view.dividerColor = ColorUtils.setAlphaComponent(palette.cardStrokeColorInt(), 46)
            }
            is ImageButton -> {
                val bg = view.background
                if (bg is GradientDrawable) {
                    val isOval = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                        bg.shape == GradientDrawable.OVAL
                    view.background = palette.createDarkGlassIconTileDrawable(
                        cornerRadiusPx = 14f * density,
                        isOval = isOval,
                        strokeWidthPx = hairlinePx
                    )
                    ImageViewCompat.setImageTintList(
                        view,
                        ColorStateList.valueOf(palette.cardStrokeColorInt())
                    )
                } else {
                    val existingTint = ImageViewCompat.getImageTintList(view)
                    if (existingTint != null && ColorUtils.calculateLuminance(existingTint.defaultColor) < 0.35) {
                        ImageViewCompat.setImageTintList(
                            view,
                            ColorStateList.valueOf(palette.textSecondaryColorInt())
                        )
                    }
                }
            }
            is ImageView -> {
                val existingTint = ImageViewCompat.getImageTintList(view)
                if (existingTint != null && ColorUtils.calculateLuminance(existingTint.defaultColor) < 0.35) {
                    ImageViewCompat.setImageTintList(
                        view,
                        ColorStateList.valueOf(palette.cardStrokeColorInt())
                    )
                }
            }
            is FrameLayout -> {
                val bg = view.background
                if (bg is GradientDrawable && view.childCount == 1 && view.getChildAt(0) is ImageView) {
                    val isOval = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                        bg.shape == GradientDrawable.OVAL
                    view.background = palette.createDarkGlassIconTileDrawable(
                        cornerRadiusPx = 14f * density,
                        isOval = isOval,
                        strokeWidthPx = hairlinePx
                    )
                    val childIcon = view.getChildAt(0) as ImageView
                    if (ImageViewCompat.getImageTintList(childIcon) != null) {
                        ImageViewCompat.setImageTintList(
                            childIcon,
                            ColorStateList.valueOf(palette.cardStrokeColorInt())
                        )
                    }
                }
            }
        }

        if (view is RecyclerView) {
            for (i in 0 until view.childCount) {
                styleNodeRecursively(view.getChildAt(i), palette, density)
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
                                child.resources.displayMetrics.density
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
                styleNodeRecursively(view.getChildAt(i), palette, density)
            }
        }
    }

    /**
     * Styles the Dialpad Bottom Sheet (`bottom_sheet_dialpad.xml`) with Full-App 3D Mix-Color
     * background, crisp obsidian 3D keys, and sharp typography when the user is an active Premium subscriber.
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
        val hairlinePx = (1f * density).toInt().coerceAtLeast(1)

        if (isPremium && palette.is3DMix) {
            rootContainer?.background = palette.createFullApp3DBackgroundDrawable(
                cornerRadiusPx = 28f * density
            )
            if (rootContainer != null) {
                invalidateHierarchyThemeTag(rootContainer)
                applyFullApp3DThemeToViewHierarchy(
                    rootView = rootContainer,
                    prefs = prefs,
                    applyRootBackground = false
                )
            }
            vipHeaderBadge?.let { badge ->
                badge.visibility = View.VISIBLE
                badge.text = "👑 VIP 3D DIALER • ${palette.title}"
                badge.setTextColor(Color.WHITE)
                applyCrispTypography(badge)
                badge.background = palette.createCrispBadgePillDrawable(
                    cornerRadiusPx = 14f * density,
                    strokeWidthPx = hairlinePx
                )
            }
            displayCard?.setCardBackgroundColor(palette.cardBgColorInt())
            displayCard?.strokeColor = ColorUtils.setAlphaComponent(palette.cardStrokeColorInt(), 135)
            displayCard?.strokeWidth = hairlinePx

            val keyStrokeColor = ColorUtils.setAlphaComponent(palette.cardStrokeColorInt(), 105)
            val keyRippleColor = ColorStateList.valueOf(
                ColorUtils.setAlphaComponent(palette.cardStrokeColorInt(), 52)
            )
            keyCards.forEach { card ->
                card.setCardBackgroundColor(
                    ColorUtils.blendARGB(palette.cardBgColorInt(), palette.secondaryColorInt(), 0.10f)
                )
                card.strokeColor = keyStrokeColor
                card.strokeWidth = hairlinePx
                card.rippleColor = keyRippleColor
                styleNodeRecursively(card, palette, density)
            }
            val callBtnFill = ColorUtils.blendARGB(palette.primaryColorInt(), palette.bgMidColorInt(), 0.16f)
            callButton?.backgroundTintList = ColorStateList.valueOf(callBtnFill)
            callButton?.setTextColor(Color.WHITE)
            callButton?.let { applyCrispTypography(it) }
        } else if (isPremium) {
            if (rootContainer != null) {
                applyCrispTypographyRecursively(rootContainer)
            }
            vipHeaderBadge?.let { badge ->
                badge.visibility = View.VISIBLE
                badge.text = "👑 VIP PRO DIALER"
                badge.setTextColor(Color.WHITE)
                applyCrispTypography(badge)
                badge.background = palette.createCrispBadgePillDrawable(
                    cornerRadiusPx = 14f * density,
                    strokeWidthPx = hairlinePx
                )
            }
        } else {
            if (rootContainer != null) {
                applyCrispTypographyRecursively(rootContainer)
            }
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
        if (!isUserEntitledToPremiumThemes(prefs)) {
            return "Light Mode • Standard Classic (Free Plan)"
        }
        val modeLabel = resolveThemeModeLabel(context, resolveEffectiveNightMode(prefs))
        val palette = resolveEffectivePalette(prefs)
        val dynamicTag = if (prefs.useDynamicColors && !palette.is3DMix) " • Dynamic Colors" else ""
        return "$modeLabel • ${palette.title}$dynamicTag"
    }
}
