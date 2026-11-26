package com.lemon.mcdevmanagermp.briefing

import androidx.compose.ui.graphics.luminance
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BriefingAppearanceTest {
    @Test
    fun savesDisplaySettingsAndNormalizesOlderValues() {
        val prefs = Preferences.userRoot().node("mcbriefing-test-" + UUID.randomUUID())
        try {
            val store = BriefingAppearanceStore(prefs)
            assertEquals(125, store.load().textPercent)
            val chosen = BriefingAppearance(
                textPercent = 145,
                placement = ProjectPlacement.RIGHT,
                sidebarWidth = 310,
                dialogWidth = 820,
                spacing = BriefingSpacing.COMFORTABLE,
                background = BriefingTone.WARM.background,
            )
            store.save(chosen)
            assertEquals(chosen, BriefingAppearanceStore(prefs).load())

            prefs.putInt("text-percent", 200)
            prefs.putInt("dialog-width", 150)
            prefs.put("project-placement", "unknown")
            val loaded = store.load()
            assertEquals(160, loaded.textPercent)
            assertEquals(520, loaded.dialogWidth)
            assertEquals(ProjectPlacement.LEFT, loaded.placement)
        } finally {
            prefs.removeNode()
        }
    }

    @Test
    fun parsesCustomColorsAndKeepsPresetTextReadable() {
        assertEquals(0xFFABCDEF.toInt(), parseBriefingColor(" #abcdef "))
        assertEquals("#ABCDEF", briefingColorText(0xFFABCDEF.toInt()))
        assertNull(parseBriefingColor("#abc"))
        assertNull(parseBriefingColor("#FFFFGG"))
        BriefingTone.entries.forEach { tone ->
            val colors = briefingColors(tone.background)
            val a = colors.onSurface.luminance()
            val b = colors.surface.luminance()
            assertTrue((maxOf(a, b) + 0.05f) / (minOf(a, b) + 0.05f) >= 4.5f, tone.label)
        }
    }
}
