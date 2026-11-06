package com.blissplayer.desktop

import org.junit.Test
import java.awt.Color
import java.awt.Dimension
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JLabel
import javax.swing.JPanel

class DbgTest {
    private val dir = File(System.getProperty("bliss.polish.dir", "build/reports/polish-shots")).apply { mkdirs() }
    private fun dump(name: String, c: javax.swing.JComponent, w: Int, h: Int) {
        c.setSize(w, h)
        c.preferredSize = Dimension(w, h)
        c.doLayout()
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.color = Color.MAGENTA
        g.fillRect(0, 0, w, h)
        c.paint(g)
        g.dispose()
        ImageIO.write(img, "png", File(dir, "dbg_$name.png"))
        println("dumped $name size=${c.size} kids=${(c as? JPanel)?.componentCount}")
    }

    @Test fun debugLabels() {
        val l = JLabel("HelloLabel")
        dump("bare_label", object : javax.swing.JComponent() {
            init { layout = java.awt.BorderLayout(); add(l) }
        }, 200, 40)
        dump("xpbutton", XpButton("PlayBtn", { DeskTheme.BLISS }, green = true, onClick = {}), 140, 40)
        dump("section", XpSectionLabel("Your music", "#2E7D32", { DeskTheme.BLISS }), 400, 44)
    }
}
