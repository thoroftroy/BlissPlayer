package com.blissplayer.desktop

import java.awt.Font
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Your actual art, loaded verbatim: bundled TTFs (Bliss PT Sans, Aqua, Super),
 * star PNGs, brand icons, launcher icons and the segmented-bar tile. Synced
 * from app/src/main/res at build time (see syncArt), so both platforms always
 * use the same files.
 */
object XpArt {
    private val fonts = mutableMapOf<String, Font>()
    private val images = mutableMapOf<String, BufferedImage>()

    fun font(theme: DeskTheme, bold: Boolean): Font {
        val key = "${theme.key}:$bold"
        return fonts.getOrPut(key) {
            val res = when (theme) {
                DeskTheme.BLISS -> if (bold) "/ptsans_700.ttf" else "/ptsans_400.ttf"
                DeskTheme.DARK -> null // system sans, like the XP-night theme
                DeskTheme.AQUA -> "/aqua_sans.ttf"
                DeskTheme.SUPER -> "/super_sans.ttf"
            }
            val base = res?.let { r ->
                runCatching {
                    val stream = XpArt::class.java.getResourceAsStream(r) ?: return@runCatching null
                    stream.use { Font.createFont(Font.TRUETYPE_FONT, it) }
                }.getOrNull()
            } ?: Font(Font.SANS_SERIF, Font.PLAIN, 13)
            // TTFs ship plain-styled files; bold is derived like Android's weight call.
            if (bold && !base.isBold) base.deriveFont(Font.BOLD) else base
        }
    }

    /** Sized instance of the theme face (the one UI code should use). */
    fun sized(theme: DeskTheme, size: Int, bold: Boolean): Font =
        font(theme, bold).deriveFont(if (bold) Font.BOLD else Font.PLAIN, size.toFloat())

    fun image(name: String): BufferedImage? = images[name] ?: runCatching {
        val stream = XpArt::class.java.getResourceAsStream("/$name") ?: return null
        ImageIO.read(stream)?.also { images[name] = it }
    }.getOrNull()

    fun fileImage(path: String): BufferedImage? = images[path] ?: runCatching {
        ImageIO.read(File(path))?.also { images[path] = it }
    }.getOrNull()
}
