package com.blissplayer.desktop

import org.junit.Test
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.FlowLayout
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Offscreen screenshots (headless-safe: no Frame/Window): every themed
 * component plus a composed mock of the main window, per theme. Look at
 * desktopApp/build/reports/polish-shots/ to inspect component rendering.
 */
class PolishShots {
    private val dir = File(System.getProperty("bliss.polish.dir", "build/reports/polish-shots")).apply { mkdirs() }

    private fun shot(name: String, w: Int, h: Int, paint: (Graphics) -> Unit) {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.color = Color(0, 0, 0, 0)
        g.fillRect(0, 0, w, h)
        paint(g)
        g.dispose()
        ImageIO.write(img, "png", File(dir, "$name.png"))
    }

    private fun layoutAll(c: java.awt.Component) {
        (c as? java.awt.Container)?.let { cont ->
            cont.doLayout()
            cont.components.forEach { layoutAll(it) }
        }
    }

    private fun compShot(name: String, w: Int, h: Int, c: JComponent) {
        c.setSize(w, h)
        c.preferredSize = Dimension(w, h)
        layoutAll(c)
        shot(name, w, h) {
            c.paint(it)
        }
    }

    @Test fun captureAll() {
        for (t in DeskTheme.entries) {
            var theme = t
            // Title bar.
            compShot("${t.key}_title", 900, 52, XpTitleBar({ theme }, { "BlissPlayer: Songs" }, {}))
            // Tabs.
            compShot("${t.key}_tabs", 900, 34, XpTabStrip(
                listOf("Songs", "Artists", "Albums", "Genres", "Playlists", "More"), 0, { theme }, {}))
            // Buttons.
            val bars = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                background = XpPaint.of(theme).face
                add(XpButton("Play", { theme }, green = true, onClick = {}))
                add(XpButton("Mix", { theme }, green = true, onClick = {}))
                add(XpButton("Sort", { theme }, onClick = {}))
                add(XpButton("Search", { theme }, pill = true, onClick = {}))
            }
            compShot("${t.key}_buttons", 460, 44, bars)
            // Rows.
            val rows = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                background = XpPaint.of(theme).face
                val samples = listOf(
                    RowData("16 Stitch - Things They Say", "16 Stitch | Single", "3:12", loved = true),
                    RowData("The Hearse Song", "Rusty Cage | Unreleased", "2:48", playing = true),
                    RowData("Adele", "24 songs", "", isGroup = true),
                    RowData("Panic at the disco", "6 songs", "", isGroup = true, loved = false, selected = true),
                    RowData("+ New playlist", "", "", isNew = true)
                )
                samples.forEachIndexed { i, r ->
                    add(RowPanel({ theme }).apply {
                        row = r.copy(stripe = i % 2 == 1)
                        preferredSize = Dimension(900, RowPanel.H)
                    })
                }
            }
            compShot("${t.key}_rows", 900, RowPanel.H * 5, rows)
            // Seek bar with skip regions.
            compShot("${t.key}_seg", 900, 36, SegBar({ theme },
                fraction = 0.35f, durationMs = 240000,
                segments = listOf(0L to 18000L, 200000L to 240000L)))
            // Section headers.
            val secs = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                background = XpPaint.of(theme).face
                listOf("Your music" to "#2E7D32", "Boost" to "#D84315", "Live sound mixer" to "#6A1B9A",
                    "Song analyzer" to "#0D47A1", "Playlists" to "#AD1457", "Stats" to "#6D4C41").forEach { (n, h) ->
                    add(XpSectionLabel(n, h, { theme }))
                }
            }
            compShot("${t.key}_sections", 900, 44 * 6, secs)
            // Composed mock window.
            val win = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                background = XpPaint.of(theme).face
                add(XpTitleBar({ theme }, { "BlissPlayer: Songs" }, {}))
                add(XpTabStrip(listOf("Songs", "Artists", "Albums", "Genres", "Playlists", "More"), 0, { theme }, {}))
                add(rows)
                val seg = SegBar({ theme }, fraction = 0.35f, durationMs = 243000,
                    segments = listOf(0L to 18000L))
                seg.preferredSize = Dimension(900, 34)
                add(seg)
            }
            compShot("${t.key}_window", 900, 52 + 34 + RowPanel.H * 5 + 34, win)
            // Mini deck mock: LCD display + transport + status strip.
            val deck = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                background = XpPaint.of(theme).face
                val m = XpPaint.of(theme)
                val display = object : JPanel(java.awt.BorderLayout()) {
                    init { preferredSize = Dimension(900, 56); border = null }
                    override fun paintComponent(g: Graphics) {
                        XpPaint.display(g, width, height, m)
                        g.font = XpArt.sized(theme, 15, true)
                        g.color = if (theme == DeskTheme.BLISS || theme == DeskTheme.DARK) m.lcdInk else m.ink
                        g.drawString("16 Stitch - Things They Say", 14, 26)
                        g.font = XpArt.sized(theme, 11, false)
                        g.color = if (theme == DeskTheme.BLISS || theme == DeskTheme.DARK) m.lcdDim else m.dim
                        g.drawString("16 Stitch | Single", 14, 44)
                        g.color = m.lcdDim
                        g.drawString("1:12 / 3:24", width - 90, 44)
                    }
                }
                add(display)
                val bar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply { background = m.face }
                bar.add(XpButton("Pause", { theme }, onClick = {}))
                bar.add(XpButton("|<", { theme }, onClick = {}))
                bar.add(XpButton(">|", { theme }, onClick = {}))
                bar.add(XpButton("Queue", { theme }, onClick = {}))
                add(bar)
                val status = object : JComponent() {
                    init { preferredSize = Dimension(900, 24) }
                    override fun paintComponent(g: Graphics) {
                        XpPaint.status(g, width, height, m)
                        g.font = XpArt.sized(theme, 11, false)
                        g.color = m.ink
                        g.drawString("587 songs. Double-click to play. Boost 100%.", 10, 16)
                    }
                }
                add(status)
            }
            compShot("${t.key}_deck", 900, 56 + 44 + 24, deck)
        }
        println("shots in ${dir.absolutePath}")
    }
}
