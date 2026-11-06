package com.blissplayer.core

import org.junit.Assert.*
import org.junit.Test

class ThemeTokensTest {
    @Test fun themeKeysAndModernFlags() {
        assertEquals(BlissTheme.BLISS, BlissTheme.from("bliss"))
        assertEquals(BlissTheme.DARK, BlissTheme.from("dark"))
        assertEquals(BlissTheme.AQUA, BlissTheme.from("aqua"))
        assertEquals(BlissTheme.SUPER, BlissTheme.from("super"))
        assertEquals(BlissTheme.BLISS, BlissTheme.from(null))
        assertEquals(BlissTheme.BLISS, BlissTheme.from("nope"))
        assertFalse(BlissTheme.BLISS.modern)
        assertFalse(BlissTheme.DARK.modern)
        assertTrue(BlissTheme.AQUA.modern)
        assertTrue(BlissTheme.SUPER.modern)
    }

    @Test fun scalarsMatchAndroidMobileTheme() {
        // Values copied from MobileTheme; Android now delegates to these.
        assertEquals(0xFF192C42.toInt(), BlissTheme.AQUA.ink)
        assertEquals(0xFFF4F7FF.toInt(), BlissTheme.BLISS.ink)
        assertEquals(0xFFF4F7FF.toInt(), BlissTheme.DARK.ink)
        assertEquals(0xFFF4F7FF.toInt(), BlissTheme.SUPER.ink)
        assertEquals(0xFF4F637A.toInt(), BlissTheme.AQUA.secondary)
        assertEquals(0xFFAFBED7.toInt(), BlissTheme.SUPER.secondary)
        assertEquals(0xFF096DE1.toInt(), BlissTheme.AQUA.accent)
        assertEquals(0xFF67FFE0.toInt(), BlissTheme.SUPER.accent)
    }

    @Test fun palettesMatchColorsXml() {
        assertEquals(0xFFECE9D8.toInt(), ThemePalettes.XP_FACE)
        assertEquals(0xFF316AC5.toInt(), ThemePalettes.XP_SELECT)
        assertEquals(0xFF23262B.toInt(), ThemePalettes.XPN_FACE)
        assertEquals(0xFFE8E8EC.toInt(), ThemePalettes.AQ_FACE)
        assertEquals(0xFF2E86D4.toInt(), ThemePalettes.AQ_SELECT)
        assertEquals(0xFF1A1230.toInt(), ThemePalettes.SP_FACE2)
        assertEquals(0xFFB01EFF.toInt(), ThemePalettes.SP_SELECT.let { ThemePalettes.SP_BORDER })
    }

    @Test fun fontAssetsMatchBothPlatforms() {
        // ThemeFonts (Android) and XpArt (Linux) load these same files.
        assertEquals("ptsans_400.ttf", BlissTheme.BLISS.fontAsset)
        assertEquals("ptsans_700.ttf", BlissTheme.BLISS.fontAssetBold)
        assertNull(BlissTheme.DARK.fontAsset)
        assertNull(BlissTheme.DARK.fontAssetBold)
        assertEquals("aqua_sans.ttf", BlissTheme.AQUA.fontAsset)
        assertEquals("super_sans.ttf", BlissTheme.SUPER.fontAssetBold)
    }

    @Test fun gradientStopsMatchMaterialDrawable() {
        assertEquals(5, ThemeGradients.AQUA_GEL.size)
        assertEquals(0xFF087BE4.toInt(), ThemeGradients.AQUA_GEL[2])
        assertEquals(4, ThemeGradients.AQUA_SILVER.size)
        assertEquals(2, ThemeGradients.AQUA_ROW_SELECTED.size)
        assertEquals(2, ThemeGradients.AQUA_ROW.size)
        assertEquals(3, ThemeGradients.SUPER_TOOLBAR.size)
        assertEquals(3, ThemeGradients.SUPER_PANEL.size)
    }
}
