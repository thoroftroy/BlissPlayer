package com.blissplayer.core

/**
 * The four theme personalities, shared by Android and Linux. Android is the
 * source of truth: scalar colors come from `MobileTheme`, flat colors from
 * `res/values/colors.xml`, gradient stops from `MaterialDrawable`.
 * All colors are ARGB ints, exactly as written on Android.
 */
enum class BlissTheme(val key: String, val modern: Boolean) {
    BLISS("bliss", false),
    DARK("dark", false),
    AQUA("aqua", true),
    SUPER("super", true);

    companion object {
        fun from(key: String?): BlissTheme = entries.firstOrNull { it.key == key } ?: BLISS
    }

    /** MobileTheme.ink. */
    val ink: Int
        get() = if (this == AQUA) 0xFF192C42.toInt() else 0xFFF4F7FF.toInt()

    /** MobileTheme.secondary. */
    val secondary: Int
        get() = if (this == AQUA) 0xFF4F637A.toInt() else 0xFFAFBED7.toInt()

    /** MobileTheme.accent. */
    val accent: Int
        get() = if (this == AQUA) 0xFF096DE1.toInt() else 0xFF67FFE0.toInt()

    /**
     * Bundled font asset, matching ThemeFonts (Android) and XpArt (Linux).
     * Null means the platform system font (Dark uses sans-serif both sides).
     */
    val fontAsset: String?
        get() = when (this) {
            BLISS -> "ptsans_400.ttf"
            DARK -> null
            AQUA -> "aqua_sans.ttf"
            SUPER -> "super_sans.ttf"
        }

    /** Bold bundled font asset; same convention as [fontAsset]. */
    val fontAssetBold: String?
        get() = when (this) {
            BLISS -> "ptsans_700.ttf"
            DARK -> null
            AQUA -> "aqua_sans.ttf"
            SUPER -> "super_sans.ttf"
        }
}

/** Flat per-theme colors, verbatim from `res/values/colors.xml`. */
object ThemePalettes {
    // Windows XP Luna (light).
    const val XP_FACE = 0xFFECE9D8.toInt()
    const val XP_FACE_DARK = 0xFFD4D0C8.toInt()
    const val XP_TITLE_START = 0xFF0058E6.toInt()
    const val XP_TITLE_END = 0xFF3A93FF.toInt()
    const val XP_TITLE_TEXT = 0xFFFFFFFF.toInt()
    const val XP_TEXT = 0xFF000000.toInt()
    const val XP_TEXT_DIM = 0xFF5A5A5A.toInt()
    const val XP_BORDER = 0xFF003C74.toInt()
    const val XP_BUTTON_TOP = 0xFFFFFFFF.toInt()
    const val XP_BUTTON_BOTTOM = 0xFFD4D0C8.toInt()
    const val XP_SELECT = 0xFF316AC5.toInt()
    const val XP_SELECT_TEXT = 0xFFFFFFFF.toInt()
    const val XP_PROGRESS = 0xFF3C9C3C.toInt()
    const val XP_START_GREEN = 0xFF3C9C3C.toInt()

    // XP night (dark mode that still feels like XP).
    const val XPN_FACE = 0xFF23262B.toInt()
    const val XPN_FACE2 = 0xFF2E333A.toInt()
    const val XPN_TITLE_START = 0xFF0A246A.toInt()
    const val XPN_TITLE_END = 0xFF1E4FA3.toInt()
    const val XPN_TEXT = 0xFFE8E8E8.toInt()
    const val XPN_TEXT_DIM = 0xFFA8ADB5.toInt()
    const val XPN_BORDER = 0xFF4A525E.toInt()
    const val XPN_BUTTON_TOP = 0xFF3A4048.toInt()
    const val XPN_BUTTON_BOTTOM = 0xFF26292F.toInt()
    const val XPN_SELECT = 0xFF1E4FA3.toInt()

    // Aqua Bliss (brushed silver, gel blue, linen).
    const val AQ_FACE = 0xFFE8E8EC.toInt()
    const val AQ_FACE2 = 0xFFD8D8DE.toInt()
    const val AQ_TITLE_TOP = 0xFFF4F6F8.toInt()
    const val AQ_TITLE_MID = 0xFFC9CDD4.toInt()
    const val AQ_TITLE_BOT = 0xFFA8ADB6.toInt()
    const val AQ_GEL_TOP = 0xFF7DB9E8.toInt()
    const val AQ_GEL_MID = 0xFF2E86D4.toInt()
    const val AQ_GEL_BOT = 0xFF1A5FA8.toInt()
    const val AQ_TEXT = 0xFF1A1A1A.toInt()
    const val AQ_TEXT_DIM = 0xFF6E6E72.toInt()
    const val AQ_BORDER = 0xFF8A8F98.toInt()
    const val AQ_SELECT = 0xFF2E86D4.toInt()
    const val AQ_SELECT_TEXT = 0xFFFFFFFF.toInt()

    // Super Bliss (near-black base, hot accents).
    const val SP_FACE = 0xFF0D0A1A.toInt()
    const val SP_FACE2 = 0xFF1A1230.toInt()
    const val SP_TITLE_TOP = 0xFF5B2EE5.toInt()
    const val SP_TITLE_BOT = 0xFFB01EFF.toInt()
    const val SP_NEON = 0xFF00F0FF.toInt()
    const val SP_PINK = 0xFFFF2E9A.toInt()
    const val SP_TEXT = 0xFFF2ECFF.toInt()
    const val SP_TEXT_DIM = 0xFF9D8FD0.toInt()
    const val SP_BORDER = 0xFFB01EFF.toInt()
    const val SP_SELECT = 0xFF5B2EE5.toInt()
}

/**
 * Gradient stops from `MaterialDrawable`, per modern theme and role.
 * Kept as ordered ARGB lists so both renderers walk the same stops.
 */
object ThemeGradients {
    // Aqua gel (PRIMARY / selected TAB).
    val AQUA_GEL = listOf(0xFFE2F6FF.toInt(), 0xFF68BDFF.toInt(), 0xFF087BE4.toInt(), 0xFF07449E.toInt(), 0xFF45B6FF.toInt())
    const val AQUA_GEL_EDGE = 0xFF134A88.toInt()
    // Aqua brushed silver (TOOLBAR / CONTROL / unselected TAB / COLUMN / ICON).
    val AQUA_SILVER = listOf(0xFFFBFDFF.toInt(), 0xFFDCE3E9.toInt(), 0xFFB3BFCB.toInt(), 0xFFEEF3F7.toInt())
    const val AQUA_SILVER_EDGE = 0xFF8093A6.toInt()
    // Aqua display glass.
    val AQUA_DISPLAY = listOf(0xFFCEE7EA.toInt(), 0xFFF2FBF5.toInt(), 0xFFB2CED0.toInt())
    const val AQUA_DISPLAY_EDGE = 0xFF688E9B.toInt()
    // Aqua selected row.
    val AQUA_ROW_SELECTED = listOf(0xFFD2EEFF.toInt(), 0xFF95CFFF.toInt())
    const val AQUA_ROW_SELECTED_EDGE = 0xFF4A9AE1.toInt()
    // Aqua idle row (translucent near-white over the water backdrop).
    val AQUA_ROW = listOf(0xEAFAFDFF.toInt(), 0xD4E3EFF6.toInt())
    const val AQUA_ROW_EDGE = 0x66FFFFFF
    // Aqua field.
    val AQUA_FIELD = listOf(0xE3D5E4EF.toInt(), 0xEEFFFFFF.toInt())
    const val AQUA_FIELD_EDGE = 0xFF8CA5B9.toInt()

    // Super surfaces.
    val SUPER_TOOLBAR = listOf(0xD51A3456.toInt(), 0xE92A2546.toInt(), 0xB51B4A55.toInt())
    val SUPER_DIALOG = listOf(0xF418283B.toInt(), 0xF4131A2C.toInt())
    val SUPER_DISPLAY = listOf(0xD721354E.toInt(), 0xB5192A38.toInt())
    val SUPER_ROW_SELECTED = listOf(0xDC294956.toInt(), 0xCC373152.toInt())
    val SUPER_PANEL = listOf(0xC125344D.toInt(), 0xA619233B.toInt(), 0xB3223043.toInt())
    val SUPER_PRIMARY = listOf(0xFFFFD07D.toInt(), 0xFFFF855E.toInt(), 0xFFF65C9C.toInt())
    val SUPER_ALT = listOf(0xFFB2FFF1.toInt(), 0xFF63EFD9.toInt(), 0xFF65B8ED.toInt())
}
