package com.blissplayer.desktop

import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities
import java.awt.BorderLayout
import java.awt.FlowLayout

/** XP button: bevel face, gel pill on Aqua, neon on Super, white on press. */
class XpButton(
    text: String,
    private val themeOf: () -> DeskTheme,
    private val green: Boolean = false,
    private val pill: Boolean = false,
    private val onClick: () -> Unit
) : JComponent() {
    private var pressed = false
    private val label = JLabel(text, JLabel.CENTER)

    init {
        layout = BorderLayout()
        add(label, BorderLayout.CENTER)
        isFocusable = true
        cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) { pressed = true; repaint() }
            override fun mouseReleased(e: MouseEvent) {
                val was = pressed
                pressed = false; repaint()
                if (was && contains(e.point)) onClick()
            }
        })
    }

    var text: String
        get() = label.text
        set(v) { label.text = v }

    override fun getPreferredSize(): Dimension {
        val f = label.getFontMetrics(XpArt.sized(themeOf(), 13, green))
        return Dimension((f.stringWidth(label.text) + 28).coerceAtLeast(64), 34)
    }

    override fun paintComponent(g: Graphics) {
        val m = XpPaint.of(themeOf())
        if (green) XpPaint.startButton(g, width, height, m)
        else XpPaint.button(g, width, height, m, pressed, pill && themeOf() == DeskTheme.AQUA)
        label.font = XpArt.sized(themeOf(), 13, green)
        label.foreground = when {
            green -> Color.WHITE
            themeOf() == DeskTheme.AQUA && !pressed -> Color.WHITE
            else -> m.btnText
        }
    }
}

/** XP tab strip: orange top strip on the selected tab, like xp_tab. */
class XpTabStrip(
    private val tabs: List<String>,
    private var selected: Int,
    private val themeOf: () -> DeskTheme,
    private val onPick: (Int) -> Unit
) : JComponent() {
    init {
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val w = width / tabs.size.coerceAtLeast(1)
                val idx = (e.x / w).coerceIn(0, tabs.size - 1)
                if (idx != selected) { selected = idx; repaint(); onPick(idx) }
            }
        })
        preferredSize = Dimension(100, 34)
    }

    fun reselect(i: Int) { selected = i; repaint() }

    override fun paintComponent(g: Graphics) {
        val m = XpPaint.of(themeOf())
        val w = width / tabs.size.coerceAtLeast(1)
        g.color = m.face
        g.fillRect(0, 0, width, height)
        tabs.forEachIndexed { i, name ->
            val gg = g.create()
            gg.translate(i * w, 0)
            XpPaint.tab(gg, w, height, m, i == selected)
            gg.dispose()
            val f = XpArt.sized(themeOf(), 12, i == selected)
            g.font = f
            g.color = if (i == selected) m.ink else m.dim
            val fm = g.getFontMetrics(f)
            g.drawString(name, i * w + (w - fm.stringWidth(name)) / 2, (height + fm.ascent - fm.descent) / 2)
        }
    }
}

/** Luna title bar with brand icon and white bold title. */
class XpTitleBar(
    private val themeOf: () -> DeskTheme,
    private val titleOf: () -> String,
    private val onSearch: () -> Unit
) : JComponent() {
    val searchBtn = XpButton("Search", themeOf, onClick = onSearch)

    init {
        layout = BorderLayout()
        val right = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 6))
        right.isOpaque = false
        right.add(searchBtn)
        add(right, BorderLayout.EAST)
        preferredSize = Dimension(100, 52)
    }

    override fun paintComponent(g: Graphics) {
        val theme = themeOf()
        val m = XpPaint.of(theme)
        XpPaint.titleBar(g, width, height, m)
        // Brand art: the user's icons, same files as Android.
        val brand = when (theme) {
            DeskTheme.AQUA -> XpArt.image("aqua_brand.png")
            DeskTheme.SUPER -> XpArt.image("super_brand.png")
            DeskTheme.BLISS -> XpArt.image("ic_launch_bliss.png")
            DeskTheme.DARK -> XpArt.image("ic_launch_dark.png")
        }
        if (brand != null) {
            val h = 30
            val w = (brand.width * h / brand.height.toFloat()).toInt()
            g.drawImage(brand, 8, (height - h) / 2, w, h, null)
        }
        g.font = XpArt.sized(theme, 20, true)
        g.color = m.titleShadow ?: Color(0, 0, 0, 0)
        if (m.titleShadow != null && m.titleShadow.alpha > 0) {
            g.drawString(titleOf(), 52, 33)
        }
        g.color = m.titleText
        g.drawString(titleOf(), 51, 32)
    }
}

/**
 * Segmented XP seek bar: your xp_seg_tile repeated for the fill, clip-style;
 * Super gets the scrolling rainbow instead. Skip regions paint red, the
 * thumb is the white oval, drag seeks. SegmentSelect mode lets the editor
 * drag out a new region.
 */
class SegBar(
    private val themeOf: () -> DeskTheme,
    var fraction: Float = 0f,
    var buffered: Float = 1f,
    var segments: List<Pair<Long, Long>> = emptyList(),
    var durationMs: Long = 0L,
    var onSeek: (ms: Long) -> Unit = {},
    var onRegion: ((startMs: Long, endMs: Long) -> Unit)? = null,
    /** Same "full"/"reduced"/"off" values as the Android setting. */
    private val motionOf: () -> String = { "full" }
) : JComponent() {
    private var dragging = false
    private var regionStart: Float? = null
    private var rainbowPhase = 0f
    private val rainbowTimer = javax.swing.Timer(66) {
        if (themeOf() == DeskTheme.SUPER && motionOf() != "off" && isShowing) {
            rainbowPhase = (rainbowPhase + 0.02f) % 1f
            repaint()
        }
    }

    init {
        preferredSize = Dimension(100, 34)
        rainbowTimer.start()
        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (onRegion != null && SwingUtilities.isRightMouseButton(e)) {
                    regionStart = e.x / width.toFloat()
                } else {
                    dragging = true
                    seekTo(e.x)
                }
            }
            override fun mouseReleased(e: MouseEvent) {
                val rs = regionStart
                if (rs != null) {
                    regionStart = null
                    val a = (rs * durationMs).toLong().coerceIn(0, durationMs)
                    val b = (e.x / width.toFloat() * durationMs).toLong().coerceIn(0, durationMs)
                    if (durationMs > 0 && kotlin.math.abs(b - a) > 500) onRegion?.invoke(minOf(a, b), maxOf(a, b))
                    repaint()
                } else if (dragging) {
                    dragging = false
                    seekTo(e.x)
                }
            }
        })
        addMouseMotionListener(object : java.awt.event.MouseMotionAdapter() {
            override fun mouseDragged(e: MouseEvent) {
                if (regionStart == null) seekTo(e.x)
                else repaint()
            }
        })
    }

    private fun seekTo(x: Int) {
        if (durationMs <= 0) return
        fraction = (x / width.toFloat()).coerceIn(0f, 1f)
        onSeek((fraction * durationMs).toLong())
        repaint()
    }

    fun setPosition(ms: Long) {
        if (!dragging && durationMs > 0) {
            fraction = (ms / durationMs.toFloat()).coerceIn(0f, 1f)
            repaint()
        }
    }

    override fun paintComponent(g: Graphics) {
        val m = XpPaint.of(themeOf())
        // sunken track
        XpPaint.field(g, width, height, m)
        val innerX = 3; val innerW = width - 6
        val fillW = (innerW * fraction).toInt()
        if (themeOf() == DeskTheme.SUPER) {
            val gg = g.create()
            gg.translate(innerX, 4)
            XpPaint.rainbowFill(gg, fillW, height - 8, rainbowPhase)
            gg.dispose()
        } else {
            XpPaint.segFill(g.create().also { (it as java.awt.Graphics2D).translate(innerX, 4) },
                fillW, height - 8, XpArt.image("xp_seg_tile.png"))
        }
        // skip regions in red over the fill area
        if (durationMs > 0) {
            g.color = Color(255, 60, 60, 170)
            segments.forEach { (s, e) ->
                val x0 = innerX + (s / durationMs.toFloat() * innerW).toInt()
                val x1 = innerX + (e / durationMs.toFloat() * innerW).toInt()
                g.fillRect(x0, 4, (x1 - x0).coerceAtLeast(2), height - 8)
            }
        }
        // white oval thumb
        val tx = (innerX + fillW - 7).coerceIn(0, width - 15)
        g.color = Color.WHITE
        g.fillOval(tx, 5, 15, height - 10)
        g.color = if (themeOf() == DeskTheme.AQUA) Color(0x5A, 0x87, 0xAC) else Color(0x68, 0xB2, 0xCE)
        g.drawOval(tx, 5, 15, height - 11)
    }
}

/** Section header with the Android per-panel hue (same sectionHues map). */
class XpSectionLabel(text: String, hueHex: String, private val themeOf: () -> DeskTheme) : JComponent() {
    private val label = JLabel(text)
    private val base = Color(hueHex.substring(1, 3).toInt(16), hueHex.substring(3, 5).toInt(16), hexTail(hueHex))
    private fun hexTail(h: String) = h.substring(5, 7).toInt(16)

    init {
        layout = BorderLayout()
        add(label, BorderLayout.CENTER)
        preferredSize = Dimension(100, 44)
    }

    override fun paintComponent(g: Graphics) {
        XpPaint.section(g, width, height, base)
        label.font = XpArt.sized(themeOf(), 15, true)
        label.foreground = Color.WHITE
        label.border = javax.swing.BorderFactory.createEmptyBorder(0, 12, 0, 0)
    }
}
