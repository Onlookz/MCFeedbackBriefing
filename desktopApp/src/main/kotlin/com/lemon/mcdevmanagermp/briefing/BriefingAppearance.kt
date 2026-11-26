package com.lemon.mcdevmanagermp.briefing

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import java.util.prefs.Preferences

internal enum class ProjectPlacement(val label: String) {
    LEFT("左侧"), RIGHT("右侧"), TOP("顶部"),
}

internal enum class BriefingSpacing(val label: String, val rowPadding: Int, val pagePadding: Int, val gap: Int) {
    COMPACT("紧凑", 8, 12, 7),
    STANDARD("标准", 13, 16, 10),
    COMFORTABLE("宽松", 19, 23, 14),
}

internal enum class BriefingTone(val label: String, val background: Int) {
    GRAY("中灰", 0xFFCCCECA.toInt()),
    COOL("冷灰", 0xFFC6CDD4.toInt()),
    WARM("暖灰", 0xFFD1C8BE.toInt()),
    DARK("深灰", 0xFF252928.toInt()),
}

internal data class BriefingAppearance(
    val textPercent: Int = 125,
    val placement: ProjectPlacement = ProjectPlacement.LEFT,
    val sidebarWidth: Int = 282,
    val dialogWidth: Int = 740,
    val spacing: BriefingSpacing = BriefingSpacing.STANDARD,
    val background: Int = BriefingTone.GRAY.background,
)

internal val LocalBriefingAppearance = staticCompositionLocalOf { BriefingAppearance() }
internal val LocalBriefingViewport = staticCompositionLocalOf { DpSize(1120.dp, 750.dp) }

internal class BriefingAppearanceStore(
    private val prefs: Preferences = Preferences.userRoot().node("mc-feedback-briefing/appearance"),
) {
    fun load(): BriefingAppearance {
        val defaults = BriefingAppearance()
        return BriefingAppearance(
            textPercent = prefs.getInt("text-percent", defaults.textPercent).coerceIn(100, 160),
            placement = ProjectPlacement.entries.firstOrNull { it.name == prefs.get("project-placement", "") }
                ?: defaults.placement,
            sidebarWidth = prefs.getInt("sidebar-width", defaults.sidebarWidth).coerceIn(190, 410),
            dialogWidth = prefs.getInt("dialog-width", defaults.dialogWidth).coerceIn(520, 920),
            spacing = BriefingSpacing.entries.firstOrNull { it.name == prefs.get("spacing", "") } ?: defaults.spacing,
            background = prefs.getInt("background", defaults.background) or 0xFF000000.toInt(),
        )
    }

    fun save(settings: BriefingAppearance) {
        prefs.putInt("text-percent", settings.textPercent)
        prefs.put("project-placement", settings.placement.name)
        prefs.putInt("sidebar-width", settings.sidebarWidth)
        prefs.putInt("dialog-width", settings.dialogWidth)
        prefs.put("spacing", settings.spacing.name)
        prefs.putInt("background", settings.background)
        prefs.flush()
    }
}

internal fun parseBriefingColor(input: String): Int? {
    val hex = input.trim().removePrefix("#")
    if (!hex.matches(Regex("[0-9A-Fa-f]{6}"))) return null
    return hex.toInt(16) or 0xFF000000.toInt()
}

internal fun briefingColorText(color: Int): String = "#%06X".format(color and 0xFFFFFF)

internal fun briefingColors(background: Int): ColorScheme {
    val base = Color(background)
    val dark = base.luminance() < 0.20f
    val shift = if (dark) Color.Black else Color.White
    val contrast = if (dark) Color.White else Color.Black
    val surface = lerp(base, shift, 0.20f)
    val darkInk = Color(0xFF202420)
    val lightInk = Color(0xFFF0F1EE)
    val onBase = if (base.luminance() >= 0.18f) darkInk else lightInk
    val ink = if (dark) lightInk else darkInk
    val button = if (dark) Color(0xFFB9CAC5) else Color(0xFF373F3C)
    val onButton = if (dark) darkInk else lightInk
    val muted = lerp(ink, surface, if (dark) 0.23f else 0.18f)
    val scheme = if (dark) darkColorScheme() else lightColorScheme()

    return scheme.copy(
        primary = button,
        onPrimary = onButton,
        primaryContainer = lerp(surface, contrast, 0.09f),
        onPrimaryContainer = ink,
        secondary = muted,
        onSecondary = onButton,
        secondaryContainer = lerp(surface, contrast, 0.07f),
        onSecondaryContainer = ink,
        background = base,
        onBackground = onBase,
        surface = surface,
        onSurface = ink,
        surfaceTint = button,
        surfaceVariant = lerp(surface, contrast, 0.09f),
        onSurfaceVariant = muted,
        surfaceContainerLowest = lerp(base, shift, 0.32f),
        surfaceContainerLow = lerp(base, shift, 0.25f),
        surfaceContainer = lerp(base, shift, 0.09f),
        surfaceContainerHigh = lerp(base, contrast, 0.02f),
        surfaceContainerHighest = lerp(base, contrast, 0.08f),
        outline = lerp(surface, contrast, if (dark) 0.41f else 0.45f),
        outlineVariant = lerp(surface, contrast, if (dark) 0.25f else 0.19f),
        error = if (dark) Color(0xFFF0BAB2) else Color(0xFF802F28),
        errorContainer = if (dark) Color(0xFF452D2B) else Color(0xFFDCCDC8),
        onErrorContainer = if (dark) Color(0xFFF1CBC4) else Color(0xFF652820),
    )
}
