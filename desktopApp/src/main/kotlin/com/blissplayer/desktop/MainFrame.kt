package com.blissplayer.desktop

import com.blissplayer.core.AnalyzerRules
import com.blissplayer.core.LoopMode
import com.blissplayer.core.PlaylistPortable
import com.blissplayer.core.QueueItem
import com.blissplayer.core.TransportCommand
import com.blissplayer.music.ShuffleMath
import com.blissplayer.music.model.SongStats
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.GridLayout
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import java.io.File
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JMenuItem
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JProgressBar
import javax.swing.JScrollPane
import javax.swing.JSlider
import javax.swing.JSplitPane
import javax.swing.JTextField
import javax.swing.KeyStroke
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.SwingWorker
import javax.swing.Timer

/** Everything the window needs; built once in DesktopMain. */
data class DesktopContext(
    val settings: DesktopSettings,
    val paths: DesktopPaths,
    val db: DesktopDb,
    val library: DesktopLibrary,
    val player: DesktopPlayer,
    val fx: DesktopFx,
    val notify: DesktopNotify,
    val session: DesktopSession,
    val analyzer: DesktopAnalyzer,
    val loudness: DesktopLoudness
)

class MainFrame(private val ctx: DesktopContext) : JFrame("BlissPlayer") {
    private var theme = DeskTheme.from(ctx.settings.getString("theme", "bliss"))
    private var layoutMode = ctx.settings.getString("layout", "auto") // auto/phone/desktop
    private var tab = 0 // 0 songs 1 artists 2 albums 3 genres 4 playlists 5 more
    private var drillArtist: String? = null
    private var drillAlbum: String? = null
    private var drillGenre: String? = null
    private var drillPlaylist: Long? = null
    private val tabHistory = ArrayDeque<Int>()
    private val listScroll = mutableMapOf<String, Int>()

    private var rows: MutableList<SongRow> = mutableListOf()
    private var byId = mapOf<Long, SongRow>()
    private var queue: List<SongRow> = emptyList()
    private var search = ""
    private var filterArtist = ""
    private var filterAlbum = ""
    private var filterGenre = ""
    private var filterPlaylist: Long? = null
    private var searchOpen = false

    private val sectionHues = mapOf(
        "Your music" to "#2E7D32", "Playback controls" to "#0058E6", "Boost" to "#D84315",
        "Live sound mixer" to "#6A1B9A", "Loudness" to "#00838F", "Shuffle" to "#7B8C00",
        "Look and feel" to "#5C6CBC", "Song analyzer" to "#0D47A1", "Playlists" to "#AD1457",
        "Stats" to "#6D4C41", "About" to "#37474F"
    )

    private lateinit var titleBar: XpTitleBar
    private lateinit var tabStrip: XpTabStrip
    private lateinit var actionRow: JPanel
    private lateinit var searchRow: JPanel
    private lateinit var searchField: JTextField
    private lateinit var crumb: JLabel
    private lateinit var headerBar: HeaderBar
    private lateinit var listModel: DefaultListModel<Row>
    private lateinit var list: JList<Row>
    private lateinit var statusLabel: JLabel
    private lateinit var progress: JProgressBar
    private lateinit var deckTitle: JLabel
    private lateinit var deckSub: JLabel
    private lateinit var deckTime: JLabel
    private lateinit var segMini: SegBar
    private lateinit var playBtn: XpButton
    private lateinit var loopBtn: XpButton
    private lateinit var speedBox: JComboBox<String>
    private lateinit var speedLabel: JLabel
    private lateinit var centerCards: JPanel
    private lateinit var moreBox: JPanel
    private lateinit var split: JSplitPane
    private lateinit var sidePanel: JPanel
    private lateinit var npSeg: SegBar
    private var lastTrackKey: String? = null
    private var rainbowTick = 0
    private val starCache = mutableMapOf<String, BufferedImage?>()

    sealed interface Row {
        data class Song(val row: SongRow) : Row
        data class Group(val key: String, val title: String, val sub: String) : Row
        data object NewPlaylist : Row
        data object Sep : Row
    }

    init {
        defaultCloseOperation = HIDE_ON_CLOSE
        minimumSize = Dimension(760, 560)
        addWindowListener(object : java.awt.event.WindowAdapter() {
            override fun windowClosing(e: java.awt.event.WindowEvent?) { saveResume() }
        })
        ctx.player.setLoop(LoopMode.valueOf(ctx.settings.getString("loop_mode", "ALL")))
        buildChrome()
        applyTheme()
        refreshTransport()
        ctx.player.onError = { msg -> SwingUtilities.invokeLater { setStatus(msg) } }
        ctx.player.onState = { SwingUtilities.invokeLater { refreshTransport() } }
        Timer(500) { tick() }.start()
        Timer(500) {
            if (theme == DeskTheme.SUPER && ctx.settings.getString("theme_motion", "full") != "off") {
                rainbowTick++
                if (list.isShowing) list.repaint()
                if (::npSeg.isInitialized) npSeg.repaint()
            }
        }.start()
        refreshLibrary(first = true)
    }

    // ================= chrome =================

    private fun paintFace(c: JComponent) {
        c.isOpaque = true
        c.background = XpPaint.of(theme).face
    }

    private fun buildChrome() {
        contentPane.layout = BorderLayout()
        val top = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); paintFace(this) }
        val tabNames = listOf("Songs", "Artists", "Albums", "Genres", "Playlists", "More")
        val tabTitle = listOf("Songs", "Artists", "Albums", "Genres", "Playlists", "More")
        titleBar = XpTitleBar({ theme }, {
            "BlissPlayer: ${tabTitle[tab]}"
        }) {
            searchOpen = !searchOpen
            searchRow.isVisible = searchOpen
            if (!searchOpen) { search = ""; searchField.text = ""; render() }
            revalidate()
        }
        top.add(titleBar)
        tabStrip = XpTabStrip(tabNames, tab, { theme }) { showTab(it) }
        top.add(tabStrip)
        actionRow = JPanel(FlowLayout(FlowLayout.LEFT, 6, 4)).apply { paintFace(this) }
        val back = XpButton("Back", { theme }, onClick = { goBack() })
        val playAll = XpButton("Play", { theme }, green = true, onClick = { playContext() })
        val mix = XpButton("Mix", { theme }, green = true, onClick = { playContextShuffled() })
        val sort = XpButton("Sort", { theme }, onClick = { sortDialog() })
        actionRow.add(back); actionRow.add(playAll); actionRow.add(mix); actionRow.add(sort)
        top.add(actionRow)
        searchRow = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply { paintFace(this); isVisible = false }
        searchField = JTextField(22)
        searchField.addActionListener { search = searchField.text.trim(); render() }
        val filterBtn = XpButton("Filter", { theme }, onClick = { filterDialog() })
        val searchClear = XpButton("Clear", { theme }, onClick = { search = ""; searchField.text = ""; render() })
        searchRow.add(JLabel("Search:")); searchRow.add(searchField)
        searchRow.add(filterBtn); searchRow.add(searchClear)
        top.add(searchRow)
        crumb = JLabel().apply { font = XpArt.sized(theme, 11, false) }
        crumb.cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        crumb.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) { clearDrill() }
        })
        val crumbWrap = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply { paintFace(this); add(crumb) }
        top.add(crumbWrap)
        headerBar = HeaderBar()
        top.add(headerBar)
        add(top, BorderLayout.NORTH)
        // Escape walks back like Android.
        rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "back")
        rootPane.actionMap.put("back", object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) { goBack() }
        })

        listModel = DefaultListModel()
        list = JList(listModel)
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = RowRenderer()
        list.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (e.clickCount == 2) activate(list.locationToIndex(e.point))
                else if (e.clickCount == 1) {
                    val idx = list.locationToIndex(e.point)
                    if (idx >= 0) {
                        val r = list.getCellBounds(idx, idx)
                        val x = e.x - r.x
                        if (x >= r.width - 44) maybeDots(idx, e)
                        else if (x >= r.width - 88) toggleFavRow(idx)
                    }
                }
            }
            override fun mousePressed(e: java.awt.event.MouseEvent) { maybePopup(e) }
            override fun mouseReleased(e: java.awt.event.MouseEvent) { maybePopup(e) }
        })
        val listScrollPane = JScrollPane(list)
        moreBox = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); paintFace(this) }
        val moreScroll = JScrollPane(moreBox)
        centerCards = JPanel(java.awt.CardLayout())
        centerCards.add(listScrollPane, "list")
        centerCards.add(moreScroll, "more")

        sidePanel = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); paintFace(this) }
        split = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, centerCards, JScrollPane(sidePanel))
        split.resizeWeight = 1.0
        add(split, BorderLayout.CENTER)

        val bottom = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); paintFace(this) }
        bottom.add(buildDeck())
        val statusWrap = StatusStrip()
        bottom.add(statusWrap)
        add(bottom, BorderLayout.SOUTH)
    }

    inner class HeaderBar : JComponent() {
        private var text = ""
        fun setText(t: String) { text = t; repaint() }
        init { preferredSize = Dimension(100, 26) }
        override fun paintComponent(g: Graphics) {
            val m = XpPaint.of(theme)
            g.color = m.faceShade; g.fillRect(0, 0, width, height)
            g.color = m.faceLight; g.fillRect(1, 1, width - 2, height - 2)
            g.color = m.face; g.fillRect(2, 2, width - 4, height - 4)
            g.font = XpArt.sized(theme, 11, false)
            g.color = m.dim
            g.drawString(text, 10, 17)
        }
    }

    inner class StatusStrip : JComponent() {
        init {
            layout = BorderLayout()
            statusLabel = JLabel()
            statusLabel.font = XpArt.sized(theme, 11, false)
            add(statusLabel, BorderLayout.CENTER)
            progress = JProgressBar()
            progress.isVisible = false
            add(progress, BorderLayout.EAST)
            preferredSize = Dimension(100, 24)
        }
        override fun paintComponent(g: Graphics) {
            XpPaint.status(g, width, height, XpPaint.of(theme))
            statusLabel.foreground = XpPaint.of(theme).ink
        }
    }

    private fun buildDeck(): JComponent {
        val deck = JPanel(BorderLayout()).apply { paintFace(this) }
        val display = object : JPanel(BorderLayout()) {
            override fun paintComponent(g: Graphics) {
                XpPaint.display(g, width, height, XpPaint.of(theme))
                super.paintComponent(g)
            }
        }.apply { isOpaque = false; border = BorderFactory.createEmptyBorder(6, 8, 6, 8) }
        val texts = JPanel(GridLayout(2, 1)).apply { isOpaque = false }
        deckTitle = JLabel().apply { font = XpArt.sized(theme, 15, true) }
        deckSub = JLabel().apply { font = XpArt.sized(theme, 11, false) }
        texts.add(deckTitle); texts.add(deckSub)
        display.add(texts, BorderLayout.CENTER)
        deckTime = JLabel("", JLabel.RIGHT).apply { font = XpArt.sized(theme, 11, false) }
        display.add(deckTime, BorderLayout.EAST)
        deck.add(display, BorderLayout.CENTER)
        val bar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply { paintFace(this) }
        playBtn = XpButton("Play", { theme }, onClick = { togglePlay() })
        val prev = XpButton("|<", { theme }, onClick = { ctx.player.prev() })
        val next = XpButton(">|", { theme }, onClick = { ctx.player.next() })
        val queueBtn = XpButton("Queue", { theme }, onClick = { showQueue() })
        loopBtn = XpButton("Loop: All", { theme }, onClick = { cycleLoop() })
        speedBox = JComboBox(arrayOf("0.5x", "0.75x", "1x", "1.25x", "1.5x", "2x"))
        speedBox.selectedItem = "1x"
        speedBox.addActionListener {
            ctx.player.setSpeed((speedBox.selectedItem as String).removeSuffix("x").toFloat())
        }
        bar.add(playBtn); bar.add(prev); bar.add(next); bar.add(queueBtn); bar.add(loopBtn)
        speedLabel = JLabel("Speed:")
        bar.add(speedLabel); bar.add(speedBox)
        deck.add(bar, BorderLayout.SOUTH)
        segMini = SegBar({ theme }, motionOf = { ctx.settings.getString("theme_motion", "full") }, onSeek = { ctx.player.seekTo(it) })
        segMini.preferredSize = Dimension(100, 30)
        deck.add(segMini, BorderLayout.NORTH)
        return deck
    }


    inner class RowRenderer : ListCellRenderer<Row> {
        private val panel = RowPanel({ theme }, { rainbowTick })
        override fun getListCellRendererComponent(
            list: JList<out Row>, value: Row, index: Int, selected: Boolean, focus: Boolean
        ): Component {
            val fav = ctx.db.favorites()
            panel.row = when (value) {
                is Row.Song -> {
                    val playing = queue.getOrNull(ctx.player.queueIndex)?.mediaId == value.row.mediaId && ctx.player.isPlaying
                    RowData(value.row.title, "${value.row.artist} | ${ctx.db.albumOf(value.row.mediaId) ?: value.row.album}",
                        fmtDur(value.row.durationMs), loved = value.row.mediaId in fav,
                        playing = playing, selected = selected, stripe = index % 2 == 1)
                }
                is Row.Group -> RowData(value.title, value.sub, "", isGroup = true,
                    selected = selected, stripe = index % 2 == 1)
                is Row.NewPlaylist -> RowData("+ New playlist", "", "", isNew = true,
                    selected = selected, stripe = index % 2 == 1)
                is Row.Sep -> RowData("", "", "")
            }
            return panel
        }
    }

    // ================= theme / layout =================

    private fun cycleTheme() {
        theme = when (theme) {
            DeskTheme.BLISS -> DeskTheme.DARK
            DeskTheme.DARK -> DeskTheme.AQUA
            DeskTheme.AQUA -> DeskTheme.SUPER
            DeskTheme.SUPER -> DeskTheme.BLISS
        }
        ctx.settings.putString("theme", theme.key)
        applyTheme()
        setStatus("Theme: ${theme.title}.")
    }

    private fun applyTheme() {
        title = "BlissPlayer — ${theme.title}"
        iconImage = XpArt.image(when (theme) {
            DeskTheme.AQUA -> "ic_launch_aqua.png"
            DeskTheme.DARK -> "ic_launch_dark.png"
            DeskTheme.SUPER -> "ic_launch_super.png"
            else -> "ic_launch_bliss.png"
        })
        contentPane.background = XpPaint.of(theme).face
        val listBackground = if (theme == DeskTheme.BLISS) Color.WHITE else XpPaint.of(theme).face
        list.background = listBackground
        (list.parent as? javax.swing.JViewport)?.background = listBackground
        speedLabel.foreground = XpPaint.of(theme).ink
        speedLabel.font = XpArt.sized(theme, 11, false)
        tabStrip.reselect(tab)
        if (tab == 5) buildMore()
        buildSide()
        render()
        repaint()
    }

    private fun applyLayout() {
        val wide = when (layoutMode) {
            "phone" -> false
            "desktop" -> true
            else -> width >= 1100
        }
        split.rightComponent.isVisible = wide
        split.dividerSize = if (wide) 6 else 0
        revalidate()
    }

    fun applyLayoutPublic() = applyLayout()

    // ================= library =================

    private fun enabledFolders(): Set<String> {
        val raw = ctx.settings.getString("folders", "")
        return if (raw.isBlank()) emptySet() else raw.split(";").filter { it.isNotBlank() }.toSet()
    }

    private fun refreshLibrary(first: Boolean = false) {
        setStatus("Scanning music…")
        progress.isVisible = true
        progress.isIndeterminate = true
        object : SwingWorker<List<SongRow>, Int>() {
            override fun doInBackground(): List<SongRow> {
                val entries = ctx.library.scan(enabledFolders()) { done, total ->
                    publish((100 * done / total.coerceAtLeast(1)))
                }
                return entries.map {
                    SongRow(DesktopLibrary.stableId(it.path), it.path, it.title, it.artist,
                        it.album, it.durationMs, it.sizeBytes, File(it.path).lastModified())
                }
            }
            override fun process(chunks: List<Int>) {
                this@MainFrame.progress.isIndeterminate = false
                this@MainFrame.progress.value = chunks.lastOrNull() ?: 0
            }
            override fun done() {
                this@MainFrame.progress.isVisible = false
                this@MainFrame.progress.isIndeterminate = false
                try {
                    rows = get().toMutableList()
                    byId = rows.associateBy { it.mediaId }
                    setStatus(statusLine())
                    if (first) restoreResume()
                    render()
                } catch (e: Exception) {
                    setStatus("Scan failed: ${e.message}")
                }
            }
        }.execute()
    }

    private fun statusLine(): String {
        val total = rows.size
        val boost = (ctx.fx.boost() * 100).toInt()
        return "$total songs. Double-click to play. Boost $boost%."
    }

    // ================= tabs / drills =================

    private fun listKey(): String = when {
        drillArtist != null -> "ar:$drillArtist"
        drillAlbum != null -> "al:$drillAlbum"
        drillGenre != null -> "ge:$drillGenre"
        drillPlaylist != null -> "pl:$drillPlaylist"
        search.isNotBlank() || filterArtist.isNotBlank() || filterAlbum.isNotBlank() ||
            filterGenre.isNotBlank() || filterPlaylist != null -> "search"
        else -> "tab:$tab"
    }

    private fun saveScroll() { listScroll[listKey()] = list.firstVisibleIndex.coerceAtLeast(0) }
    private fun restoreScroll() {
        val pos = listScroll[listKey()] ?: 0
        if (pos > 0) SwingUtilities.invokeLater { list.ensureIndexIsVisible(pos) }
    }

    private fun showTab(t: Int) {
        if (t != tab) { tabHistory.addLast(tab); while (tabHistory.size > 20) tabHistory.removeFirst() }
        saveScroll()
        tab = t
        tabStrip.reselect(t)
        drillArtist = null; drillAlbum = null; drillGenre = null; drillPlaylist = null
        if (t == 5) { (centerCards.layout as java.awt.CardLayout).show(centerCards, "more"); buildMore() }
        else { (centerCards.layout as java.awt.CardLayout).show(centerCards, "list"); render() }
        actionRow.isVisible = t <= 3
    }

    fun goBack(): Boolean {
        if (drillArtist != null || drillAlbum != null || drillGenre != null || drillPlaylist != null) {
            clearDrill(); return true
        }
        if (search.isNotBlank() || filterArtist.isNotBlank() || filterAlbum.isNotBlank() ||
            filterGenre.isNotBlank() || filterPlaylist != null) {
            search = ""; searchField.text = ""
            filterArtist = ""; filterAlbum = ""; filterGenre = ""; filterPlaylist = null
            render(); return true
        }
        val prev = tabHistory.removeLastOrNull() ?: return false
        showTab(prev); return true
    }

    private fun clearDrill() {
        saveScroll()
        drillArtist = null; drillAlbum = null; drillGenre = null; drillPlaylist = null
        render()
    }

    private fun filtered(): List<SongRow> {
        val ids = filterPlaylist?.let { ctx.db.songsIn(it).toSet() }
        val wanted = com.blissplayer.core.LibraryQueries.narrow(
            rows.map { it.toCore() }, search, filterArtist, filterAlbum, filterGenre, ids,
            genreOf = { ctx.db.genreOf(it) },
            albumOf = { ctx.db.albumOf(it) }
        ).map { it.mediaId }.toSet()
        return rows.filter { it.mediaId in wanted }
    }

    private fun sortedPool(pool: List<SongRow>): List<SongRow> {
        val ordered = com.blissplayer.core.LibraryQueries.sortSongs(
            pool.map { it.toCore() },
            ctx.settings.getString("sort_songs", "AZ"),
            ctx.db.totalPlays(),
            albumOf = { s -> ctx.db.albumOf(s.mediaId) ?: s.album }
        ).map { it.mediaId }
        val rank = ordered.withIndex().associate { it.value to it.index }
        return pool.sortedBy { rank[it.mediaId] ?: Int.MAX_VALUE }
    }

    private fun render() {
        if (tab == 5) return
        val pool = filtered()
        crumb.text = when {
            drillArtist != null -> "Artist: $drillArtist  (click to go back)"
            drillAlbum != null -> "Album: $drillAlbum  (click to go back)"
            drillGenre != null -> "Genre: $drillGenre  (click to go back)"
            drillPlaylist != null -> "Playlist  (click to go back)"
            else -> ""
        }
        crumb.foreground = XpPaint.of(theme).ink
        val items = mutableListOf<Row>()
        when {
            drillArtist != null -> {
                val songs = sortedPool(pool.filter { it.artist == drillArtist })
                headerBar.setText("$drillArtist — ${songs.size} songs. Double-click to play.")
                songs.forEach { items.add(Row.Song(it)) }
            }
            drillAlbum != null -> {
                val songs = sortedPool(pool.filter { (ctx.db.albumOf(it.mediaId) ?: it.album) == drillAlbum })
                headerBar.setText("$drillAlbum — ${songs.size} songs. Double-click to play.")
                songs.forEach { items.add(Row.Song(it)) }
            }
            drillGenre != null -> {
                val songs = sortedPool(pool.filter { ctx.db.genreOf(it.mediaId) == drillGenre })
                headerBar.setText("$drillGenre — ${songs.size} songs. Double-click to play.")
                songs.forEach { items.add(Row.Song(it)) }
            }
            drillPlaylist != null -> {
                val ids = ctx.db.songsIn(drillPlaylist!!)
                headerBar.setText("Playlist — ${ids.size} songs. Double-click to play.")
                ids.mapNotNull { byId[it] }.forEach { items.add(Row.Song(it)) }
            }
            tab == 0 -> {
                headerBar.setText("${pool.size} songs. Double-click to play.")
                sortedPool(pool).forEach { items.add(Row.Song(it)) }
            }
            tab == 1 -> {
                val groups = pool.groupBy { it.artist }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
                headerBar.setText("${groups.size} artists.")
                groups.forEach { (a, songs) -> items.add(Row.Group("ar:$a", a, "${songs.size} songs")) }
            }
            tab == 2 -> {
                val groups = pool.groupBy { ctx.db.albumOf(it.mediaId) ?: it.album }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
                headerBar.setText("${groups.size} albums.")
                groups.forEach { (a, songs) -> items.add(Row.Group("al:$a", a, "${songs.size} songs")) }
            }
            tab == 3 -> {
                val groups = pool.groupBy { ctx.db.genreOf(it.mediaId) }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
                headerBar.setText("${groups.size} genres.")
                groups.forEach { (g, songs) -> items.add(Row.Group("ge:$g", g, "${songs.size} songs")) }
            }
            tab == 4 -> {
                headerBar.setText("Playlists")
                val pls = ctx.db.playlists().toMutableList()
                if (pls.none { it.second == "Favorites" }) {
                    val id = ctx.db.createPlaylist("Favorites")
                    pls.add(0, id to "Favorites")
                }
                pls.forEachIndexed { i, (id, name) ->
                    items.add(Row.Group("pl:$id", name, "${ctx.db.songsIn(id).size} songs"))
                    if (i == 0) items.add(Row.Sep)
                }
                items.add(Row.NewPlaylist)
            }
        }
        listModel.clear()
        items.forEach { listModel.addElement(it) }
        restoreScroll()
    }

    private fun activate(index: Int) {
        if (index < 0 || index >= listModel.size) return
        val row = listModel.get(index)
        saveScroll()
        when (row) {
            is Row.Song -> {
                val pool = currentSongPool()
                // Searching: slip the song in next and jump to it, don't nuke the queue.
                if (search.isNotBlank() || filterArtist.isNotBlank()) {
                    playNext(row.row, quiet = true)
                    playQueue(queue, queue.indexOfFirst { it.mediaId == row.row.mediaId }.coerceAtLeast(0))
                } else {
                    playQueue(pool, pool.indexOfFirst { it.mediaId == row.row.mediaId }.coerceAtLeast(0))
                }
            }
            is Row.Group -> when {
                row.key.startsWith("ar:") -> { drillArtist = row.title; render() }
                row.key.startsWith("al:") -> { drillAlbum = row.title; render() }
                row.key.startsWith("ge:") -> { drillGenre = row.title; render() }
                row.key.startsWith("pl:") -> { drillPlaylist = row.key.removePrefix("pl:").toLongOrNull(); render() }
            }
            is Row.NewPlaylist -> {
                val name = JOptionPane.showInputDialog(this, "Playlist name:")
                if (!name.isNullOrBlank()) { ctx.db.createPlaylist(name.trim()); render() }
            }
            else -> {}
        }
    }

    private fun currentSongPool(): List<SongRow> =
        (0 until listModel.size).mapNotNull { (listModel.get(it) as? Row.Song)?.row }

    private fun maybePopup(e: java.awt.event.MouseEvent) {
        if (!e.isPopupTrigger) return
        val idx = list.locationToIndex(e.point)
        if (idx < 0) return
        list.selectedIndex = idx
        when (val row = listModel.get(idx)) {
            is Row.Song -> songMenu(row.row, e.component, e.x, e.y)
            is Row.Group -> groupMenu(row, e.component, e.x, e.y)
            is Row.NewPlaylist -> activate(idx)
            else -> {}
        }
    }

    private fun maybeDots(idx: Int, e: java.awt.event.MouseEvent) {
        when (val row = listModel.get(idx)) {
            is Row.Song -> songMenu(row.row, e.component, e.x, e.y)
            is Row.Group -> groupMenu(row, e.component, e.x, e.y)
            is Row.NewPlaylist -> activate(idx)
            else -> {}
        }
    }

    private fun toggleFavRow(idx: Int) {
        val row = listModel.get(idx) as? Row.Song ?: return
        ctx.db.setFavorite(row.row.mediaId, row.row.mediaId !in ctx.db.favorites())
        list.repaint()
    }

    private fun popup(items: List<Pair<String, () -> Unit>>, c: Component, x: Int, y: Int) {
        val menu = JPopupMenu()
        items.forEach { (name, fn) ->
            val item = JMenuItem(name)
            item.addActionListener { fn() }
            menu.add(item)
        }
        menu.show(c, x, y)
    }

    private fun songMenu(r: SongRow, c: Component, x: Int, y: Int) {
        val loved = r.mediaId in ctx.db.favorites()
        popup(listOf(
            (if (loved) "★ Unstar" else "☆ Star") to {
                ctx.db.setFavorite(r.mediaId, !loved); list.repaint() },
            "Play now" to { playQueue(listOf(r), 0) },
            "Play next" to { playNext(r); setStatus("Playing next: ${r.title}.") },
            "Add to queue" to { queue = queue + r; setStatus("Added to the end of the queue.") },
            "Add to playlist" to { addSongsDialog(listOf(r.mediaId)) },
            "Skip segments" to { segmentDialog(r) },
            "Edit details" to { editDetails(r) },
            "Delete song and file" to { deleteConfirm(r) },
            "Details" to { showDetails(r) }
        ), c, x, y)
    }

    private fun groupMenu(g: Row.Group, c: Component, x: Int, y: Int) {
        val songs: List<SongRow> = when {
            g.key.startsWith("ar:") -> rows.filter { it.artist == g.title }
            g.key.startsWith("al:") -> rows.filter { (ctx.db.albumOf(it.mediaId) ?: it.album) == g.title }
            g.key.startsWith("ge:") -> rows.filter { ctx.db.genreOf(it.mediaId) == g.title }
            g.key.startsWith("pl:") -> {
                playlistMenu(g.key.removePrefix("pl:").toLong(), g.title, c, x, y); return
            }
            else -> return
        }
        popup(listOf(
            "Play" to { if (songs.isNotEmpty()) playQueue(songs, 0) },
            "Shuffle play" to { if (songs.isNotEmpty()) playQueue(smartOrder(songs), 0) },
            "Add to queue" to { queue = queue + songs; setStatus("Queued ${songs.size} songs.") },
            "Add to playlist" to { addSongsDialog(songs.map { it.mediaId }) }
        ), c, x, y)
    }

    private fun playlistMenu(id: Long, name: String, c: Component, x: Int, y: Int) {
        val listSongs = ctx.db.songsIn(id).mapNotNull { byId[it] }
        if (name == "Favorites") {
            popup(listOf(
                "Play this playlist" to {
                    if (listSongs.isEmpty()) setStatus("Favorites is empty. Star a song to add some.")
                    else playQueue(listSongs, 0) },
                "Shuffle play" to {
                    if (listSongs.isEmpty()) setStatus("Favorites is empty. Star a song to add some.")
                    else playQueue(smartOrder(listSongs), 0) }
            ), c, x, y)
            return
        }
        popup(listOf(
            "Play this playlist" to {
                if (listSongs.isEmpty()) setStatus("Playlist is empty. Its songs may have moved.")
                else playQueue(listSongs, 0) },
            "Shuffle play" to {
                if (listSongs.isEmpty()) setStatus("Playlist is empty. Its songs may have moved.")
                else playQueue(smartOrder(listSongs), 0) },
            "Rename" to {
                val input = JOptionPane.showInputDialog(this, "Rename", name)
                if (!input.isNullOrBlank()) { ctx.db.renamePlaylist(id, input.trim()); render() } },
            "Delete playlist" to {
                if (JOptionPane.showConfirmDialog(this, "The playlist goes away. Your songs stay.",
                        "Delete $name?", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                    ctx.db.removePlaylist(id); render() } }
        ), c, x, y)
    }

    // ================= playback =================

    private fun playQueue(pool: List<SongRow>, start: Int) {
        if (pool.isEmpty()) return
        queue = pool
        ctx.player.setQueue(pool.map { QueueItem(it.path, it.title, it.artist, it.album, it.durationMs) },
            start.coerceIn(pool.indices), 0)
        onTrackStarted(pool[start.coerceIn(pool.indices)])
        ctx.player.play()
        refreshTransport()
    }

    private fun playNext(r: SongRow, quiet: Boolean = false) {
        val (q, _) = com.blissplayer.core.QueueOps.insertNext(queue, ctx.player.queueIndex, r)
        queue = q
        rebuildEngineQueue(keepIndex = ctx.player.queueIndex)
        if (!quiet) setStatus("Playing next: ${r.title}.")
    }

    private fun rebuildEngineQueue(keepIndex: Int) {
        if (queue.isEmpty()) return
        ctx.player.setQueue(queue.map { QueueItem(it.path, it.title, it.artist, it.album, it.durationMs) },
            keepIndex.coerceIn(queue.indices), ctx.player.positionMs)
    }

    private fun playContext() {
        val pool = sortedPool(filtered().ifEmpty { rows })
        if (pool.isEmpty()) { setStatus("Nothing to play."); return }
        playQueue(pool, 0)
    }

    private fun playContextShuffled() {
        val pool = filtered().ifEmpty { rows }
        if (pool.isEmpty()) return
        playQueue(smartOrder(pool), 0)
        setStatus("Shuffled ${pool.size} songs.")
    }

    private fun smartOrder(pool: List<SongRow>): List<SongRow> {
        val smart = ctx.settings.getBoolean("smart_shuffle", true)
        val strength = ctx.settings.getFloat("shuffle_strength", 1f).toDouble()
        val counts = ctx.db.totalPlays()
        val favs = ctx.db.favorites()
        val weights = pool.associate { r ->
            val w = if (!smart) 1.0 else ShuffleMath.weight(counts[r.mediaId] ?: 0, strength)
            val favBoost = if (r.mediaId in favs) when (ctx.settings.getString("fav_mode", "exempt")) {
                "normal" -> 1.0
                "boost" -> ShuffleMath.favoriteWeight(ctx.settings.getFloat("fav_rarity", 1.5f).toDouble())
                else -> 1000.0
            } else 1.0
            r.mediaId to w * favBoost
        }
        val ordered = ShuffleMath.weightedOrder(pool.map { it.mediaId }, weights, System.currentTimeMillis())
        val byIdLocal = pool.associateBy { it.mediaId }
        return ordered.mapNotNull { byIdLocal[it] }
    }

    private fun togglePlay() {
        if (queue.isEmpty()) { playContext(); return }
        ctx.player.toggle()
        refreshTransport()
    }

    private fun onTrackStarted(row: SongRow) {
        ctx.db.bumpPlay(row.mediaId)
        lastTrackKey = row.path
        ctx.fx.setLoudnessGain(ctx.db.allGains()[row.mediaId] ?: 1.0)
        ctx.player.setSegments(row.path, ctx.db.segmentsFor(row.mediaId))
        ctx.session.publish(com.blissplayer.core.NowPlaying(
            QueueItem(row.path, row.title, row.artist, row.album, row.durationMs), true, 0, row.durationMs))
        ctx.notify.notify(row.title, row.artist)
        saveResume()
    }

    private fun tick() {
        if (queue.isEmpty()) return
        val idx = ctx.player.queueIndex
        val cur = queue.getOrNull(idx)
        if (cur != null && cur.path != lastTrackKey) onTrackStarted(cur)
        segMini.durationMs = ctx.player.durationMs
        segMini.segments = cur?.let { ctx.db.segmentsFor(it.mediaId) } ?: emptyList()
        segMini.setPosition(ctx.player.positionMs)
        if (::npSeg.isInitialized) {
            npSeg.durationMs = ctx.player.durationMs
            npSeg.segments = segMini.segments
            npSeg.setPosition(ctx.player.positionMs)
        }
        val pos = fmtDur(ctx.player.positionMs)
        val dur = fmtDur(ctx.player.durationMs)
        deckTime.text = "$pos / $dur"
        ctx.session.publish(com.blissplayer.core.NowPlaying(
            cur?.let { QueueItem(it.path, it.title, it.artist, it.album, it.durationMs) },
            ctx.player.isPlaying, ctx.player.positionMs, ctx.player.durationMs))
    }

    private fun refreshTransport() {
        val cur = queue.getOrNull(ctx.player.queueIndex)
        playBtn.text = if (ctx.player.isPlaying) "Pause" else "Play"
        val m = XpPaint.of(theme)
        if (cur == null) {
            deckTitle.text = "Nothing playing"
            deckSub.text = "Double-click a song."
            deckTitle.foreground = m.dim; deckSub.foreground = m.dim
        } else {
            deckTitle.text = cur.title
            deckSub.text = "${cur.artist} | ${ctx.db.albumOf(cur.mediaId) ?: cur.album}"
            if (theme == DeskTheme.BLISS || theme == DeskTheme.DARK) {
                deckTitle.font = java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.BOLD, 15)
                deckTitle.foreground = m.lcdInk
                deckSub.foreground = m.lcdDim
            } else {
                deckTitle.font = XpArt.sized(theme, 15, true)
                deckTitle.foreground = m.ink
                deckSub.font = XpArt.sized(theme, 11, false)
                deckSub.foreground = m.dim
            }
        }
        val mode = LoopMode.valueOf(ctx.settings.getString("loop_mode", "ALL"))
        loopBtn.text = "Loop: ${mode.name.lowercase().replaceFirstChar { it.uppercase() }}"
        deckTime.foreground = XpPaint.of(theme).lcdDim
        list.repaint()
        buildSide()
    }

    private fun cycleLoop() {
        val cur = LoopMode.valueOf(ctx.settings.getString("loop_mode", "ALL"))
        val next = when (cur) {
            LoopMode.ALL -> LoopMode.ONE
            LoopMode.ONE -> LoopMode.OFF
            LoopMode.OFF -> LoopMode.ALL
        }
        ctx.settings.putString("loop_mode", next.name)
        ctx.player.setLoop(next)
        setStatus("Loop: ${next.name.lowercase().replaceFirstChar { it.uppercase() }} (remembered).")
        refreshTransport()
    }

    private fun toggleFavoriteCurrent() {
        val cur = queue.getOrNull(ctx.player.queueIndex) ?: return
        ctx.db.setFavorite(cur.mediaId, cur.mediaId !in ctx.db.favorites())
        list.repaint()
    }

    private fun fmtDur(ms: Long): String = "%d:%02d".format((ms / 60000), (ms % 60000) / 1000)

    private fun showQueue() {
        val dlg = xpDialog("Queue (${queue.size})")
        val model = DefaultListModel<String>()
        queue.forEachIndexed { i, r -> model.addElement("${i + 1}. ${r.title} — ${r.artist}") }
        val ql = JList(model)
        dlg.add(JScrollPane(ql), BorderLayout.CENTER)
        val bar = JPanel(FlowLayout(FlowLayout.LEFT))
        val playNow = XpButton("Play now", { theme }) {
            val i = ql.selectedIndex
            if (i >= 0) { playQueue(queue, i); dlg.dispose() }
        }
        val up = XpButton("Up", { theme }) {
            val i = ql.selectedIndex
            if (i > 0) {
                queue = com.blissplayer.core.QueueOps.move(queue, i, i - 1)
                rebuildEngineQueue(ctx.player.queueIndex); refreshQueueModel(model); ql.selectedIndex = i - 1
            }
        }
        val down = XpButton("Down", { theme }) {
            val i = ql.selectedIndex
            if (i >= 0 && i < queue.size - 1) {
                queue = com.blissplayer.core.QueueOps.move(queue, i, i + 1)
                rebuildEngineQueue(ctx.player.queueIndex); refreshQueueModel(model); ql.selectedIndex = i + 1
            }
        }
        val remove = XpButton("Remove", { theme }) {
            val i = ql.selectedIndex
            if (i >= 0) {
                queue = com.blissplayer.core.QueueOps.removeAt(queue, i)
                rebuildEngineQueue(ctx.player.queueIndex); refreshQueueModel(model)
            }
        }
        val save = XpButton("Save queue", { theme }) {
            if (queue.isEmpty()) { setStatus("Queue is empty."); return@XpButton }
            val name = JOptionPane.showInputDialog(this, "Playlist name:")
            if (!name.isNullOrBlank()) {
                val pid = ctx.db.createPlaylist(name.trim())
                ctx.db.addToPlaylist(pid, queue.map { it.mediaId })
                setStatus("Saved ${queue.size} songs to $name.")
            }
        }
        val close = XpButton("Close", { theme }) { dlg.dispose() }
        listOf(playNow, up, down, remove, save, close).forEach { bar.add(it) }
        dlg.add(bar, BorderLayout.SOUTH)
        dlg.setSize(480, 420)
        dlg.isVisible = true
    }

    private fun refreshQueueModel(model: DefaultListModel<String>) {
        model.clear()
        queue.forEachIndexed { i, r -> model.addElement("${i + 1}. ${r.title} — ${r.artist}") }
    }

    // ================= resume =================

    private fun saveResume() {
        if (queue.isEmpty()) return
        ctx.settings.putString("resume_queue", queue.joinToString(",") { it.path }.take(200_000))
        ctx.settings.putString("resume_index", ctx.player.queueIndex.toString())
        ctx.settings.putString("resume_pos", ctx.player.positionMs.toString())
    }

    private fun restoreResume() {
        val saved = ctx.settings.getString("resume_queue", "")
        if (saved.isBlank() || queue.isNotEmpty()) return
        val found = saved.split(",").filter { it.isNotBlank() }.mapNotNull { p -> rows.find { it.path == p } }
        if (found.isEmpty()) return
        queue = found
        val idx = ctx.settings.getString("resume_index", "0").toIntOrNull()?.coerceIn(found.indices) ?: 0
        val pos = ctx.settings.getString("resume_pos", "0").toLongOrNull() ?: 0L
        ctx.player.setQueue(found.map { QueueItem(it.path, it.title, it.artist, it.album, it.durationMs) }, idx, pos)
        lastTrackKey = found[idx].path
        refreshTransport()
    }

    // ================= XP dialogs =================

    private fun xpDialog(title: String, wide: Boolean = false): JDialog {
        val dlg = JDialog(this, title, true)
        dlg.contentPane.layout = BorderLayout()
        val bar = object : JComponent() {
            init { preferredSize = Dimension(100, 40) }
            override fun paintComponent(g: Graphics) {
                val m = XpPaint.of(theme)
                XpPaint.titleBar(g, width, height, m)
                g.font = XpArt.sized(theme, 15, true)
                g.color = m.titleText
                g.drawString(title, 12, 26)
            }
        }
        dlg.contentPane.add(bar, BorderLayout.NORTH)
        val body = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = XpPaint.of(theme).face
            border = BorderFactory.createEmptyBorder(10, 12, 10, 12)
        }
        dlg.contentPane.add(JScrollPane(body).apply {
            preferredSize = Dimension(if (wide) 560 else 440, if (wide) 420 else 320)
        }, BorderLayout.CENTER)
        dlg.contentPane.background = XpPaint.of(theme).face
        return dlg
    }

    private fun xpBody(dlg: JDialog): JPanel =
        ((dlg.contentPane.getComponent(1) as JScrollPane).viewport.view as JPanel)

    private fun xpMenu(title: String, items: List<String>, onPick: (Int) -> Unit) {
        val dlg = xpDialog(title)
        val body = xpBody(dlg)
        items.forEachIndexed { i, name ->
            body.add(XpButton(name, { theme }) { dlg.dispose(); onPick(i) })
        }
        dlg.pack()
        dlg.isVisible = true
    }

    // ================= side now-playing =================

    private fun buildSide() {
        sidePanel.removeAll()
        val m = XpPaint.of(theme)
        sidePanel.background = m.face
        val cur = queue.getOrNull(ctx.player.queueIndex)
        val head = JLabel(if (cur == null) "Now playing" else cur.title)
        head.font = XpArt.sized(theme, 16, true)
        head.foreground = m.ink
        sidePanel.add(head)
        val sub = JLabel(cur?.let { "${it.artist} | ${ctx.db.albumOf(it.mediaId) ?: it.album}" } ?: "")
        sub.font = XpArt.sized(theme, 12, false)
        sub.foreground = m.dim
        sidePanel.add(sub)
        npSeg = SegBar({ theme },
            motionOf = { ctx.settings.getString("theme_motion", "full") },
            durationMs = cur?.durationMs ?: 0L,
            segments = cur?.let { ctx.db.segmentsFor(it.mediaId) } ?: emptyList(),
            onSeek = { ctx.player.seekTo(it) })
        npSeg.preferredSize = Dimension(220, 34)
        sidePanel.add(npSeg)
        val row = JPanel(FlowLayout(FlowLayout.LEFT)).apply { background = m.face }
        val speeds = arrayOf("0.5x", "0.75x", "1x", "1.25x", "1.5x", "2x")
        val box = JComboBox(speeds)
        box.addActionListener { ctx.player.setSpeed((box.selectedItem as String).removeSuffix("x").toFloat()) }
        row.add(box)
        row.add(XpButton("Shuffle", { theme }) { playContextShuffled() })
        row.add(XpButton("Save", { theme }) {
            if (queue.isEmpty()) { setStatus("Queue is empty."); return@XpButton }
            val name = JOptionPane.showInputDialog(this, "Playlist name:")
            if (!name.isNullOrBlank()) {
                val pid = ctx.db.createPlaylist(name.trim())
                ctx.db.addToPlaylist(pid, queue.map { it.mediaId })
                setStatus("Saved ${queue.size} songs to $name.")
            }
        })
        row.add(XpButton("Segments", { theme }) {
            cur?.let { segmentDialog(it) } ?: setStatus("Play something first.")
        })
        sidePanel.add(row)
        val fav = XpButton("☆ Star", { theme }) {
            cur?.let {
                ctx.db.setFavorite(it.mediaId, it.mediaId !in ctx.db.favorites())
                buildSide(); list.repaint()
            }
        }
        sidePanel.add(fav)
        sidePanel.revalidate()
        sidePanel.repaint()
    }

    // ================= more =================

    private fun section(name: String): XpSectionLabel = XpSectionLabel(name, sectionHues[name] ?: "#37474F", { theme })

    private fun hint(text: String): JLabel = JLabel("<html>$text</html>").apply {
        font = XpArt.sized(theme, 11, false)
        foreground = XpPaint.of(theme).dim
        alignmentX = Component.LEFT_ALIGNMENT
        border = BorderFactory.createEmptyBorder(0, 12, 6, 0)
    }

    private fun btn(name: String, fn: () -> Unit): XpButton = XpButton(name, { theme }, onClick = fn).apply {
        alignmentX = Component.LEFT_ALIGNMENT
    }

    private fun buildMore() {
        moreBox.removeAll()
        moreBox.add(JLabel("Control Panel").apply {
            font = XpArt.sized(theme, 20, true)
            foreground = XpPaint.of(theme).ink
            alignmentX = Component.LEFT_ALIGNMENT
        })
        moreBox.add(hint("Playback, sound and library settings. Select a panel to open it."))
        moreBox.add(section("Your music"))
        moreBox.add(btn("Scan for music") { refreshLibrary() })
        moreBox.add(btn("Choose folder") {
            val fc = JFileChooser(ctx.paths.music)
            fc.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                ctx.settings.putString("music_dir", fc.selectedFile.absolutePath)
                refreshLibrary()
            }
        })
        moreBox.add(hint("• Tick a folder to play from it.<br>• Unticked folders stay listed, grayed out, and are skipped.<br>• Folders nest: ticking a parent ticks everything under it.<br>• System sounds stay off."))
        folderRows()
        moreBox.add(section("Playback controls"))
        val speedRow = JPanel(FlowLayout(FlowLayout.LEFT)).apply { background = XpPaint.of(theme).face }
        val speeds = arrayOf("0.5x", "0.75x", "1x", "1.25x", "1.5x", "2x")
        val sbox = JComboBox(speeds)
        sbox.addActionListener { ctx.player.setSpeed((sbox.selectedItem as String).removeSuffix("x").toFloat()) }
        speedRow.add(JLabel("Speed:")); speedRow.add(sbox)
        val lbox = JComboBox(arrayOf("Loop all", "Loop one", "Loop off"))
        lbox.selectedIndex = when (LoopMode.valueOf(ctx.settings.getString("loop_mode", "ALL"))) {
            LoopMode.ALL -> 0; LoopMode.ONE -> 1; LoopMode.OFF -> 2
        }
        lbox.addActionListener {
            val mode = listOf(LoopMode.ALL, LoopMode.ONE, LoopMode.OFF)[lbox.selectedIndex]
            ctx.settings.putString("loop_mode", mode.name)
            ctx.player.setLoop(mode)
            refreshTransport()
        }
        speedRow.add(JLabel("Repeat:")); speedRow.add(lbox)
        moreBox.add(speedRow)
        moreBox.add(section("Boost"))
        moreBox.add(btn("Exact boost") { boostDialog() })
        moreBox.add(btn("Boost presets") { boostPresets() })
        moreBox.add(hint("Louder than 200% may clip. Presets remember each output; connecting one restores its boost."))
        moreBox.add(section("Live sound mixer"))
        moreBox.add(btn("Open frequency mixer") { mixerDialog() })
        moreBox.add(hint("Bass through treble plus overall loudness, in plain words."))
        moreBox.add(section("Loudness"))
        val strength = JSlider(0, 200, (ctx.settings.getFloat("eq_strength", 1f) * 100).toInt())
        moreBox.add(strength)
        moreBox.add(btn("Equalize library") {
            runLoudness(strength.value / 100f)
            ctx.settings.putFloat("eq_strength", strength.value / 100f)
        })
        moreBox.add(hint("Matches volume across songs. 100% lands every song on the average; higher pushes further on stubborn libraries."))
        moreBox.add(section("Shuffle"))
        val smart = JCheckBox("Smart Shuffle (plays songs you hear less more often)", ctx.settings.getBoolean("smart_shuffle", true))
        smart.addActionListener { ctx.settings.putBoolean("smart_shuffle", smart.isSelected) }
        moreBox.add(smart)
        val anti = JSlider(0, 200, (ctx.settings.getFloat("shuffle_strength", 1f) * 100).toInt())
        moreBox.add(anti)
        val antiBtn = btn("Set anti-repeat strength (${(ctx.settings.getFloat("shuffle_strength", 1f) * 100).toInt()}%)") {
            ctx.settings.putFloat("shuffle_strength", anti.value / 100f)
            setStatus("Anti-repeat strength: ${anti.value}%.")
        }
        moreBox.add(antiBtn)
        moreBox.add(btn("Favorites in shuffle: ${favModeName()}") { favModeDialog() })
        moreBox.add(hint("• Exempt, the default: starred songs keep the same chance no matter how often they play.<br>• Boosted: starred songs get the rarity slider as extra odds.<br>• Like regular songs: no special treatment."))
        moreBox.add(section("Look and feel"))
        moreBox.add(btn("Theme (now: ${theme.title})") { cycleTheme(); buildMore() })
        moreBox.add(btn("Layout: ${layoutMode.replaceFirstChar { it.uppercase() }}") {
            layoutMode = when (layoutMode) {
                "auto" -> "phone"; "phone" -> "desktop"; else -> "auto"
            }
            ctx.settings.putString("layout", layoutMode)
            applyLayout(); buildMore()
        })
        moreBox.add(hint("Each theme has its own font and materials. Motion off freezes Super's rainbow too."))
        val motionLabels = listOf("Full motion", "Reduced motion", "Motion off")
        val motionKeys = listOf("full", "reduced", "off")
        val curMotion = motionKeys.indexOf(ctx.settings.getString("theme_motion", "full")).coerceAtLeast(0)
        moreBox.add(btn("Motion: ${motionLabels[curMotion]}") {
            xpMenu("Motion", motionLabels) { which ->
                ctx.settings.putString("theme_motion", motionKeys[which])
                buildMore()
                setStatus(when (motionKeys[which]) {
                    "off" -> "Motion off. Everything is still."
                    "reduced" -> "Reduced motion. Gentle transitions, no particle storms."
                    else -> "Full motion."
                })
            }
        })
        moreBox.add(section("Song analyzer"))
        val online = JCheckBox("Online lookup (MusicBrainz + Wikipedia)", ctx.settings.getBoolean("analyzer_online", true))
        online.addActionListener { ctx.settings.putBoolean("analyzer_online", online.isSelected) }
        moreBox.add(online)
        val scope = JComboBox(arrayOf("New/changed", "Everything"))
        moreBox.add(scope)
        moreBox.add(btn("Run analyzer") { runAnalyzer(scope.selectedIndex == 1, online.isSelected) })
        moreBox.add(hint("Cleans names offline, confirms online. Review before anything is written."))
        moreBox.add(section("Playlists"))
        moreBox.add(btn("New playlist") {
            val name = JOptionPane.showInputDialog(this, "Playlist name:")
            if (!name.isNullOrBlank()) { ctx.db.createPlaylist(name.trim()); render() }
        })
        moreBox.add(btn("Export portable playlist") { exportPlaylist() })
        moreBox.add(btn("Import portable playlist") { importPlaylist() })
        moreBox.add(hint("Portable JSON links by file and metadata, never by device-only ids. Missing songs are kept, never dropped."))
        moreBox.add(section("Stats"))
        moreBox.add(btn(if (ctx.settings.getBoolean("stats_enabled", true)) "Keep stats: On" else "Keep stats: Off") {
            val now = !ctx.settings.getBoolean("stats_enabled", true)
            ctx.settings.putBoolean("stats_enabled", now)
            buildMore()
            setStatus(if (now) "Stats on." else "Stats off. Smart Shuffle treats every song as unplayed.")
        })
        moreBox.add(hint("When off, no plays, finishes, skips, listening time or history are kept."))
        moreBox.add(btn("Show stats") { showStats() })
        moreBox.add(btn("Share stats file") { shareStats() })
        moreBox.add(btn("Choose visible stats") { visibleStatsDialog() })
        moreBox.add(hint("Pick which cards show below. Hidden cards stop recording only when tracking is off."))
        statCards()
        moreBox.add(btn("Reset all stats") {
            if (JOptionPane.showConfirmDialog(this, "Wipe play counts and history?",
                    "Reset all stats?", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                ctx.db.exec("DELETE FROM plays"); ctx.db.exec("DELETE FROM song_stats"); ctx.db.exec("DELETE FROM stat_events")
                setStatus("All stats wiped.")
            }
        })
        moreBox.add(section("About"))
        moreBox.add(hint("BlissPlayer ${DesktopMain.version()} — Linux desktop. Same core as Android: analyzer, loudness, playlists, stats."))
        moreBox.revalidate()
        moreBox.repaint()
    }

    private fun favModeName(): String = when (ctx.settings.getString("fav_mode", "exempt")) {
        "normal" -> "Like regular songs"; "boost" -> "Boosted"; else -> "Exempt"
    }

    private fun favModeDialog() {
        xpMenu("Favorites in shuffle", listOf(
            "Exempt (default)", "Boosted (rarity slider)", "Like regular songs")) { which ->
            ctx.settings.putString("fav_mode", listOf("exempt", "boost", "normal")[which])
            if (which == 1) {
                val input = JOptionPane.showInputDialog(this, "Rarity boost (0.05–3.0):",
                    ctx.settings.getFloat("fav_rarity", 1.5f).toString())
                input?.toFloatOrNull()?.let { ctx.settings.putFloat("fav_rarity", it.coerceIn(0.05f, 3f)) }
            }
            buildMore()
        }
    }

    private fun folderRows() {
        val all = rows.mapNotNull { File(it.path).parent }.distinct().sorted()
        val enabled = enabledFolders().toMutableSet()
        val allOn = enabled.isEmpty()
        val openFolders = ctx.settings.getString("folder_tree_open", "").split(";").filter { it.isNotBlank() }.toSet()
        val kids = mutableMapOf<String?, MutableList<String>>()
        all.forEach { p ->
            val parent = all.filter { it != p && p.startsWith(it.trimEnd('/') + "/") }.maxByOrNull { it.length }
            kids.getOrPut(parent) { mutableListOf() }.add(p)
        }
        fun leaves(p: String): List<String> {
            val k = kids[p].orEmpty()
            return if (k.isEmpty()) listOf(p) else k.flatMap { leaves(it) }
        }
        val bar = JPanel(FlowLayout(FlowLayout.LEFT)).apply { background = XpPaint.of(theme).face }
        val expand = XpButton("Expand all", { theme }) {
            ctx.settings.putString("folder_tree_open", all.filter { kids[it].orEmpty().isNotEmpty() }.joinToString(";"))
            buildMore()
        }
        val collapse = XpButton("Collapse all", { theme }) {
            ctx.settings.putString("folder_tree_open", "")
            buildMore()
        }
        bar.add(expand); bar.add(collapse)
        moreBox.add(bar)
        fun addNode(p: String, depth: Int) {
            val children = kids[p].orEmpty()
            val leaves = leaves(p)
            val onCount = leaves.count { l -> allOn || enabled.any { l == it || l.startsWith(it.trimEnd('/') + "/") } }
            val row = JPanel(FlowLayout(FlowLayout.LEFT, 2, 0)).apply { background = XpPaint.of(theme).face }
            if (children.isNotEmpty()) {
                val isOpen = p in openFolders || children.size <= 8
                val t = XpButton(if (isOpen) "[-]" else "[+]", { theme }) {
                    val cur = ctx.settings.getString("folder_tree_open", "").split(";").filter { it.isNotBlank() }.toMutableSet()
                    if (isOpen) cur.remove(p) else cur.add(p)
                    ctx.settings.putString("folder_tree_open", cur.joinToString(";"))
                    buildMore()
                }
                row.add(t)
                if (isOpen) { moreBox.add(row); children.forEach { addNode(it, depth + 1) }; return }
            }
            val short = p.substringAfterLast('/')
            val n = rows.count { s -> s.path == p || s.path.startsWith(p.trimEnd('/') + "/") }
            val box = JCheckBox("$short ($n songs)" + if (onCount in 1 until leaves.size) " (partial)" else "", onCount == leaves.size)
            box.addActionListener {
                val cur = enabledFolders().toMutableSet()
                if (cur.isEmpty()) cur.addAll(all.filter { l ->
                    !DesktopLibrary.isSystemPath(l)
                })
                if (box.isSelected) {
                    cur.add(p)
                    cur.removeAll { it != p && it.startsWith(p.trimEnd('/') + "/") }
                } else {
                    val doomed = leaves.toSet()
                    cur.removeAll { it == p || doomed.any { d -> it == d || it.startsWith(d + "/") } }
                }
                ctx.settings.putString("folders", cur.joinToString(";"))
                buildMore()
                refreshLibrary()
            }
            row.add(box)
            moreBox.add(row)
        }
        kids[null].orEmpty().forEach { addNode(it, 0) }
    }

    private fun visibleStatsDialog() {
        val dlg = xpDialog("Visible stats")
        val body = xpBody(dlg)
        listOf("stats_show_top3" to "Top 3 leaders", "stats_show_month" to "This month", "stats_show_year" to "This year").forEach { (key, label) ->
            val box = JCheckBox(label, ctx.settings.getBoolean(key, true))
            box.addActionListener { ctx.settings.putBoolean(key, box.isSelected); buildMore() }
            body.add(box)
        }
        val hint = JLabel("Library and All time always show.")
        body.add(hint)
        val done = XpButton("Done", { theme }) { dlg.dispose() }
        body.add(done)
        dlg.pack()
        dlg.isVisible = true
    }

    private fun statCards() {
        val totals = ctx.db.totalPlays()
        fun name(id: Long) = byId[id]?.let { "${it.title} (${it.artist})" } ?: "A deleted song"
        fun span(ms: Long): String {
            val h = ms / 3600000; val m = (ms % 3600000) / 60000
            return when {
                h > 0 -> "${h}h ${m}m"; m > 0 -> "${m}m"
                else -> "${(ms / 1000).coerceAtLeast(0)}s"
            }
        }
        moreBox.add(statCard("Library",
            "Songs in library: ${rows.size}\nSongs played: ${totals.size}\nFavorites starred: ${ctx.db.favorites().size}"))
        moreBox.add(statCard("All time", "Total plays: ${totals.values.sum()}"))
        if (ctx.settings.getBoolean("stats_show_top3", true)) {
            val top = totals.entries.sortedByDescending { it.value }.take(3)
                .mapIndexed { i, (id, c) -> "${i + 1}. ${name(id)} ($c plays)" }.joinToString("\n").ifBlank { "None yet" }
            moreBox.add(statCard("Top 3", top))
        }
        val now = System.currentTimeMillis()
        if (ctx.settings.getBoolean("stats_show_month", true)) {
            val plays = ctx.db.playsSince(SongStats.monthStartMs(now)).size
            val label = SongStats.monthLabel(now)
            moreBox.add(statCard(label, "$plays plays", "Clear this month") {
                if (JOptionPane.showConfirmDialog(this,
                        "Month and year share the same history. Clearing this month also removes it from this year's totals. All-time totals stay.",
                        "Clear this month?", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                    ctx.db.exec("DELETE FROM stat_events WHERE at>=${SongStats.monthStartMs(now)}")
                    buildMore()
                    setStatus("This month's history cleared.")
                }
            })
        }
        if (ctx.settings.getBoolean("stats_show_year", true)) {
            val year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR).toString()
            val plays = ctx.db.playsSince(SongStats.yearStartMs(now)).size
            moreBox.add(statCard(year, "$plays plays", "Clear this year") {
                if (JOptionPane.showConfirmDialog(this,
                        "Month and year share the same history. Clearing this year also clears this month's history. All-time totals stay.",
                        "Clear this year?", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                    ctx.db.exec("DELETE FROM stat_events WHERE at>=${SongStats.yearStartMs(now)}")
                    buildMore()
                    setStatus("This year's history cleared.")
                }
            })
        }
    }

    private fun statCard(title: String, body: String, actionLabel: String? = null, onAction: (() -> Unit)? = null): JPanel {
        val box = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = XpPaint.of(theme).faceLight
            border = BorderFactory.createEmptyBorder(8, 10, 8, 10)
            alignmentX = Component.LEFT_ALIGNMENT
        }
        val t = JLabel(title).apply { font = XpArt.sized(theme, 14, true); foreground = XpPaint.of(theme).ink }
        val b = JLabel("<html>${body.replace("\n", "<br>")}</html>").apply {
            font = XpArt.sized(theme, 13, false); foreground = XpPaint.of(theme).ink
        }
        box.add(t); box.add(b)
        if (actionLabel != null && onAction != null) {
            box.add(XpButton(actionLabel, { theme }, onClick = onAction))
        }
        return box
    }

    // ================= analyzer / loudness =================

    private fun runAnalyzer(everything: Boolean, online: Boolean) {
        setStatus("Analyzing…")
        progress.isVisible = true
        progress.isIndeterminate = true
        val target = if (everything) rows else rows.filter {
            ctx.db.needsWork(it.mediaId, "${it.sizeBytes}:${it.modified}", "analyzed")
        }
        object : SwingWorker<List<AnalyzerRules.Proposal>, String>() {
            override fun doInBackground(): List<AnalyzerRules.Proposal> {
                return kotlinx.coroutines.runBlocking {
                    ctx.analyzer.analyze(target, online) { done, total, phase ->
                        publish("$phase $done/$total")
                    }
                }
            }
            override fun process(chunks: List<String>) { setStatus("Analyzing: ${chunks.lastOrNull()}") }
            override fun done() {
                this@MainFrame.progress.isVisible = false
                this@MainFrame.progress.isIndeterminate = false
                try {
                    val proposals = get()
                    setStatus("Analyzer: ${proposals.size} proposals.")
                    if (proposals.isNotEmpty()) reviewDialog(proposals)
                    else JOptionPane.showMessageDialog(this@MainFrame, "Nothing to fix.")
                } catch (e: Exception) {
                    setStatus("Analyzer failed: ${e.message}")
                }
            }
        }.execute()
    }

    private fun reviewDialog(proposals: List<AnalyzerRules.Proposal>) {
        val dlg = xpDialog("Review (${proposals.size})", wide = true)
        val body = xpBody(dlg)
        val model = DefaultListModel<String>()
        proposals.forEach { model.addElement("[${it.confidence}] ${it.newTitle} — ${it.newArtist} (${it.reason})") }
        val ql = JList(model)
        body.add(JScrollPane(ql))
        val bar = JPanel(FlowLayout(FlowLayout.LEFT))
        val all = XpButton("Select all", { theme }) { ql.setSelectionInterval(0, model.size - 1) }
        val apply = XpButton("Apply selected", { theme }) {
            val sel = ql.selectedIndices
            val targets = if (sel.isEmpty()) proposals else sel.toList().map { proposals[it] }
            var ok = 0
            var fail: String? = null
            for (p in targets) {
                val err = ctx.analyzer.apply(p)
                if (err == null) ok++ else fail = err
            }
            setStatus("Applied $ok/${targets.size}.${fail?.let { " Last error: $it" } ?: ""}")
            dlg.dispose()
            refreshLibrary()
        }
        val close = XpButton("Close", { theme }) { dlg.dispose() }
        bar.add(all); bar.add(apply); bar.add(close)
        body.add(bar)
        dlg.pack()
        dlg.isVisible = true
    }

    private fun runLoudness(strength: Float) {
        setStatus("Equalizing in the background…")
        progress.isVisible = true
        progress.isIndeterminate = true
        val target = rows.filter { ctx.db.needsWork(it.mediaId, "${it.sizeBytes}:${it.modified}", "eq") }.ifEmpty { rows }
        object : SwingWorker<DesktopLoudness.Result, String>() {
            override fun doInBackground(): DesktopLoudness.Result {
                return kotlinx.coroutines.runBlocking {
                    ctx.loudness.equalize(target, strength, { id, mult -> ctx.db.putGain(id, mult) }) { done, total, title, eta ->
                        publish("$done/$total $title${eta?.let { " (~${it}s left)" } ?: ""}")
                    }
                }
            }
            override fun process(chunks: List<String>) {
                setStatus("Equalizing: ${chunks.lastOrNull()}")
                ctx.notify.progress("Equalizing", 0, 0)
            }
            override fun done() {
                this@MainFrame.progress.isVisible = false
                this@MainFrame.progress.isIndeterminate = false
                try {
                    val r = get()
                    setStatus("Equalized: ${r.measured} measured, ${r.reused} reused (up ${r.raised}, down ${r.lowered}).")
                    ctx.notify.done("Equalize finished", "${r.measured} measured, ${r.reused} reused.")
                } catch (e: Exception) {
                    setStatus("Equalize failed: ${e.message}")
                }
            }
        }.execute()
    }

    // ================= playlists =================

    private fun addSongsDialog(ids: List<Long>) {
        val dlg = xpDialog("Add songs", wide = true)
        val body = xpBody(dlg)
        val checks = mutableMapOf<String, JCheckBox>()
        body.add(JLabel("Artists:").apply { font = XpArt.sized(theme, 14, true) })
        rows.map { it.artist }.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER).forEach { a ->
            val box = JCheckBox(a)
            checks["a:$a"] = box
            body.add(box)
        }
        body.add(JLabel("Albums:").apply { font = XpArt.sized(theme, 14, true) })
        rows.map { ctx.db.albumOf(it.mediaId) ?: it.album }.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER).forEach { a ->
            val box = JCheckBox(a)
            checks["l:$a"] = box
            body.add(box)
        }
        body.add(JLabel("Songs:").apply { font = XpArt.sized(theme, 14, true) })
        rows.sortedBy { it.title.lowercase() }.forEach { r ->
            val box = JCheckBox("${r.title} — ${r.artist}")
            checks["s:${r.mediaId}"] = box
            body.add(box)
        }
        val pls = ctx.db.playlists()
        if (pls.isEmpty()) { setStatus("Make a playlist first."); return }
        val combo = JComboBox(pls.map { it.second }.toTypedArray())
        body.add(combo)
        val add = XpButton("Add", { theme }) {
            val pid = pls[combo.selectedIndex].first
            val picked = checks.filter { it.value.isSelected }.keys
            val fromArtists = rows.filter { "a:${it.artist}" in picked }.map { it.mediaId }
            val fromAlbums = rows.filter { "l:${ctx.db.albumOf(it.mediaId) ?: it.album}" in picked }.map { it.mediaId }
            val fromSongs = picked.filter { it.startsWith("s:") }.mapNotNull { it.removePrefix("s:").toLongOrNull() }
            ctx.db.addToPlaylist(pid, (ids + fromArtists + fromAlbums + fromSongs).distinct())
            setStatus("Added to ${combo.selectedItem}.")
            dlg.dispose()
            render()
        }
        body.add(add)
        dlg.pack()
        dlg.isVisible = true
    }

    private fun exportPlaylist() {
        val pls = ctx.db.playlists()
        if (pls.isEmpty()) { setStatus("No playlists to export."); return }
        val choice = JOptionPane.showInputDialog(this, "Export:", "Playlists",
            JOptionPane.PLAIN_MESSAGE, null, pls.map { it.second }.toTypedArray(), pls[0].second)
        val target = pls.firstOrNull { it.second == choice } ?: return
        val favs = ctx.db.favorites()
        val shareFav = JOptionPane.showConfirmDialog(this, "Include starred favorites file?") == JOptionPane.YES_OPTION
        val toEntry: (SongRow) -> PlaylistPortable.Entry = { r ->
            val rel = runCatching { ctx.paths.music.toURI().relativize(File(r.path).toURI()).path }.getOrDefault(r.path)
            PlaylistPortable.Entry(rel, File(r.path).name, r.title, r.artist,
                ctx.db.albumOf(r.mediaId) ?: r.album, r.durationMs, r.path)
        }
        val entries = ctx.db.songsIn(target.first).mapNotNull { byId[it] }.map(toEntry)
        val favEntries = if (shareFav) favs.mapNotNull { byId[it] }.map(toEntry) else emptyList()
        val json = PlaylistPortable.export(target.second, entries, favEntries)
        val dir = JFileChooser(ctx.paths.exportDir)
        dir.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        if (dir.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return
        val out = File(dir.selectedFile, "${target.second.replace('/', '_')}.bliss.json")
        out.writeText(json)
        setStatus("Exported to ${out.absolutePath} (${entries.size} songs${if (shareFav) " + favorites" else ""}).")
    }

    private fun importPlaylist() {
        val fc = JFileChooser(ctx.paths.exportDir)
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return
        val parsed = PlaylistPortable.parse(fc.selectedFile.readText())
        if (parsed == null) { setStatus("Not a BlissPlayer v1 playlist."); return }
        val lib = rows.map { r ->
            val rel = runCatching { ctx.paths.music.toURI().relativize(File(r.path).toURI()).path }.getOrDefault(r.path)
            PlaylistPortable.LibTrack(rel, File(r.path).name, r.title, r.artist,
                ctx.db.albumOf(r.mediaId) ?: r.album, r.durationMs) to r.mediaId
        }
        val relinked = PlaylistPortable.relink<Pair<PlaylistPortable.LibTrack, Long>>(parsed.entries, lib) { it.first }
        val pid = ctx.db.createPlaylist(parsed.name)
        ctx.db.addToPlaylist(pid, relinked.found.map { it.second.second })
        var msg = "Imported ${parsed.name}: ${relinked.found.size} linked, ${relinked.missing.size} kept as missing."
        if (parsed.favorites.isNotEmpty()) {
            val how = JOptionPane.showInputDialog(this, "Favorites in file: merge, replace, or ignore?",
                "Favorites", JOptionPane.PLAIN_MESSAGE, null, arrayOf("merge", "replace", "ignore"), "merge")
            val favRelinked = PlaylistPortable.relink<Pair<PlaylistPortable.LibTrack, Long>>(parsed.favorites, lib) { it.first }
            val ids = favRelinked.found.map { it.second.second }.toSet()
            if (how == "replace") {
                ctx.db.favorites().forEach { ctx.db.setFavorite(it, false) }
                ids.forEach { ctx.db.setFavorite(it, true) }
            } else if (how == "merge") ids.forEach { ctx.db.setFavorite(it, true) }
            msg += " Favorites: $how (${ids.size} linked)."
        }
        setStatus(msg)
        render()
    }

    // ================= mixer / boost / filters =================

    private fun boostDialog() {
        val cur = (ctx.fx.boost() * 100).toInt()
        val input = JOptionPane.showInputDialog(this, "Exact boost percent (over 200 may clip):", cur.toString())
        val pct = input?.toIntOrNull() ?: return
        ctx.fx.setBoost(pct / 100.0) { w -> JOptionPane.showMessageDialog(this, w) }
        setStatus("Boost: $pct%.")
    }

    private fun boostPresets() {
        val dlg = xpDialog("Boost presets")
        val body = xpBody(dlg)
        val presets = listOf("Flat" to 1f) // built-ins first, user presets below
        body.add(JLabel("Tap a preset to use it."))
        presets.forEach { (name, mult) ->
            body.add(XpButton("$name (${(mult * 100).toInt()}%)", { theme }) {
                ctx.fx.setBoost(mult.toDouble()); setStatus("Boost: $name."); dlg.dispose()
            })
        }
        val name = JTextField("My preset")
        body.add(name)
        val save = XpButton("Save current as preset", { theme }) {
            ctx.settings.putString("boost_preset_${name.text.trim()}", ctx.fx.boost().toString())
            setStatus("Preset saved."); dlg.dispose()
        }
        body.add(save)
        val stored = ctx.settings.let {
            // user presets live in prefs under boost_preset_*
            (it as DesktopSettings).keys().filter { k -> k.startsWith("boost_preset_") }
        }
        stored.forEach { k ->
            val n = k.removePrefix("boost_preset_")
            val mult = ctx.settings.getString(k, "1").toFloatOrNull() ?: 1f
            body.add(XpButton("$n (${(mult * 100).toInt()}%)", { theme }) {
                ctx.fx.setBoost(mult.toDouble()); setStatus("Boost: $n."); dlg.dispose()
            })
        }
        val close = XpButton("Close", { theme }) { dlg.dispose() }
        body.add(close)
        dlg.pack()
        dlg.isVisible = true
    }

    private fun mixerDialog() {
        val dlg = xpDialog("Live frequency mixer")
        val body = xpBody(dlg)
        body.add(JLabel("Bass through treble, in plain words. Big boosts can clip on loud songs.").apply {
            font = XpArt.sized(theme, 11, false); foreground = XpPaint.of(theme).dim
        })
        val names = listOf("Bass", "Low-mid", "Mid", "High-mid", "Treble")
        val cur = ctx.fx.current()
        val sliders = names.map { JSlider(-12, 12, 0) }
        cur.bandsDb.forEachIndexed { i, v -> if (i < sliders.size) sliders[i].value = v.toInt() }
        names.forEachIndexed { i, n ->
            val row = JPanel(GridLayout(1, 2)).apply { background = XpPaint.of(theme).face }
            row.add(JLabel("$n (dB)")); row.add(sliders[i])
            body.add(row)
        }
        val pre = JSlider(-12, 12, cur.preampDb.toInt())
        val preRow = JPanel(GridLayout(1, 2)).apply { background = XpPaint.of(theme).face }
        preRow.add(JLabel("Overall loudness (dB)")); preRow.add(pre)
        body.add(preRow)
        val desc = JLabel(ctx.fx.describe(cur)).apply {
            font = XpArt.sized(theme, 12, false); foreground = XpPaint.of(theme).ink
        }
        body.add(desc)
        val apply = XpButton("Apply", { theme }) {
            val state = com.blissplayer.core.EqState(true, sliders.map { it.value.toFloat() }, pre.value.toFloat())
            ctx.fx.apply(state)
            desc.text = ctx.fx.describe(state)
            setStatus("Mixer applied.")
        }
        val close = XpButton("Close", { theme }) { dlg.dispose() }
        body.add(apply); body.add(close)
        dlg.pack()
        dlg.isVisible = true
    }

    private fun filterDialog() {
        val dlg = xpDialog("Search filters")
        val body = xpBody(dlg)
        fun field(label: String, value: String): JTextField {
            body.add(JLabel(label))
            return JTextField(value).also { body.add(it) }
        }
        val eArtist = field("Artist contains", filterArtist)
        val eAlbum = field("Album contains", filterAlbum)
        var gSel = filterGenre
        var pSel = filterPlaylist
        val genres = (rows.map { ctx.db.genreOf(it.mediaId) }.distinct().sorted() + "Uncategorized").distinct().sorted()
        val gBtn = XpButton(if (gSel.isBlank()) "Genre: any" else "Genre: $gSel", { theme }) {
            xpMenu("Genre", listOf("Any genre") + genres) { which ->
                gSel = if (which == 0) "" else genres[which - 1]
            }
        }
        body.add(gBtn)
        val pls = ctx.db.playlists()
        val pBtn = XpButton(if (pSel == null) "Playlist: any" else "Playlist", { theme }) {
            xpMenu("In playlist", listOf("Any playlist") + pls.map { it.second }) { which ->
                pSel = if (which == 0) null else pls[which - 1].first
            }
        }
        body.add(pBtn)
        body.add(JLabel("Filters narrow the search to matching songs.").apply {
            font = XpArt.sized(theme, 12, false)
        })
        val apply = XpButton("Apply", { theme }) {
            filterArtist = eArtist.text.trim()
            filterAlbum = eAlbum.text.trim()
            filterGenre = gSel
            filterPlaylist = pSel
            dlg.dispose()
            render()
        }
        body.add(apply)
        dlg.pack()
        dlg.isVisible = true
    }

    private fun sortDialog() {
        val options = listOf("A to Z", "Z to A", "By artist", "By album", "Most played", "Least played", "Longest", "Shortest")
        val keys = listOf("AZ", "ZA", "Artist", "Album", "MostPlayed", "LeastPlayed", "Longest", "Shortest")
        val cur = keys.indexOf(ctx.settings.getString("sort_songs", "AZ")).coerceAtLeast(0)
        xpMenu("Sort songs: ${options[cur]}", options) { which ->
            ctx.settings.putString("sort_songs", keys[which])
            render()
        }
    }

    // ================= segments =================

    private fun segmentDialog(r: SongRow) {
        val dlg = xpDialog("Skip segments: ${r.title}", wide = true)
        val body = xpBody(dlg)
        body.add(JLabel("Red parts are skipped automatically. Drag with the right mouse button on the bar to mark a region, or type exact times.").apply {
            font = XpArt.sized(theme, 11, false); foreground = XpPaint.of(theme).dim
        })
        val listBox = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); background = XpPaint.of(theme).face }
        val bar = SegBar({ theme },
            motionOf = { ctx.settings.getString("theme_motion", "full") },
            durationMs = r.durationMs,
            segments = ctx.db.segmentsFor(r.mediaId),
            onSeek = { ctx.player.seekTo(it) })
        bar.onRegion = { s, e ->
            ctx.db.addSegment(r.mediaId, s, e, "")
            ctx.player.setSegments(r.path, ctx.db.segmentsFor(r.mediaId))
            bar.segments = ctx.db.segmentsFor(r.mediaId)
            refreshSegList(listBox, r)
            setStatus("Segment ${fmtDur(s)}–${fmtDur(e)} added.")
        }
        bar.preferredSize = Dimension(480, 40)
        body.add(bar)
        val posRow = JPanel(FlowLayout(FlowLayout.LEFT)).apply { background = XpPaint.of(theme).face }
        val playB = XpButton("Play", { theme }) {
            playQueue(listOf(r), 0)
        }
        posRow.add(playB)
        val sField = JTextField("0:00", 8)
        val eField = JTextField(fmtDur(r.durationMs), 8)
        val setStart = XpButton("Start = now", { theme }) { sField.text = fmtDur(ctx.player.positionMs) }
        val setEnd = XpButton("End = now", { theme }) { eField.text = fmtDur(ctx.player.positionMs) }
        posRow.add(JLabel("Start:")); posRow.add(sField)
        posRow.add(JLabel("End:")); posRow.add(eField)
        posRow.add(setStart); posRow.add(setEnd)
        val addBtn = XpButton("Add region", { theme }) {
            val s = parseDur(sField.text.trim()) ?: return@XpButton
            val e = parseDur(eField.text.trim()) ?: return@XpButton
            if (e - s < 500) { setStatus("Region too short."); return@XpButton }
            ctx.db.addSegment(r.mediaId, s, e, "")
            ctx.player.setSegments(r.path, ctx.db.segmentsFor(r.mediaId))
            bar.segments = ctx.db.segmentsFor(r.mediaId)
            refreshSegList(listBox, r)
        }
        posRow.add(addBtn)
        body.add(posRow)
        body.add(listBox)
        refreshSegList(listBox, r)
        val close = XpButton("Done", { theme }) { dlg.dispose() }
        body.add(close)
        dlg.pack()
        dlg.isVisible = true
    }

    private fun refreshSegList(box: JPanel, r: SongRow) {
        box.removeAll()
        ctx.db.segmentsFull(r.mediaId).forEach { s ->
            val row = JPanel(FlowLayout(FlowLayout.LEFT)).apply { background = XpPaint.of(theme).face }
            row.add(JLabel("${fmtDur(s.startMs)} – ${fmtDur(s.endMs)}"))
            row.add(XpButton("Delete", { theme }) {
                ctx.db.deleteSegment(s.id)
                ctx.player.setSegments(r.path, ctx.db.segmentsFor(r.mediaId))
                refreshSegList(box, r)
            })
            box.add(row)
        }
        box.revalidate()
        box.repaint()
    }

    private fun parseDur(t: String): Long? {
        val parts = t.split(":").mapNotNull { it.toLongOrNull() }
        if (parts.isEmpty() || parts.size > 3) return null
        return when (parts.size) {
            1 -> parts[0] * 1000
            2 -> parts[0] * 60000 + parts[1] * 1000
            else -> parts[0] * 3600000 + parts[1] * 60000 + parts[2] * 1000
        }
    }

    // ================= songs =================

    private fun showDetails(r: SongRow) {
        val dlg = xpDialog(r.title)
        val body = xpBody(dlg)
        body.add(JLabel("Artist: ${r.artist}<br>Album: ${ctx.db.albumOf(r.mediaId) ?: r.album}<br>" +
            "Genre: ${ctx.db.genreOf(r.mediaId)}<br>Length: ${fmtDur(r.durationMs)}<br>" +
            "Size: ${r.sizeBytes / 1024} KB<br>${r.path}"))
        val ok = XpButton("OK", { theme }) { dlg.dispose() }
        body.add(ok)
        dlg.pack()
        dlg.isVisible = true
    }

    private fun editDetails(r: SongRow) {
        val dlg = xpDialog("Edit details")
        val body = xpBody(dlg)
        fun field(label: String, value: String): JTextField {
            body.add(JLabel(label))
            return JTextField(value).also { body.add(it) }
        }
        val eTitle = field("Title", r.title)
        val eArtist = field("Artist", r.artist)
        val eAlbum = field("Album", ctx.db.albumOf(r.mediaId) ?: r.album)
        val eGenre = field("Genre", ctx.db.genreOf(r.mediaId))
        val save = XpButton("Save", { theme }) {
            val err = DesktopTags.write(r.path, com.blissplayer.core.TagData(
                eTitle.text.trim(), eArtist.text.trim(), eAlbum.text.trim(), eGenre.text.trim()))
            if (err != null) { setStatus("Tag write failed: $err"); return@XpButton }
            ctx.db.setGenre(r.mediaId, eGenre.text.trim().ifBlank { "Uncategorized" })
            ctx.db.setAlbum(r.mediaId, eAlbum.text.trim())
            dlg.dispose()
            setStatus("Saved.")
            refreshLibrary()
        }
        body.add(save)
        dlg.pack()
        dlg.isVisible = true
    }

    private fun deleteConfirm(r: SongRow) {
        if (JOptionPane.showConfirmDialog(this, "Delete ${r.title} and its file?",
                "Delete song and file?", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return
        runCatching { File(r.path).delete() }
        ctx.db.deleteSongEverywhere(r.mediaId)
        rows.removeAll { it.mediaId == r.mediaId }
        byId = rows.associateBy { it.mediaId }
        queue = queue.filter { it.mediaId != r.mediaId }
        setStatus("Deleted ${r.title}.")
        render()
    }

    // ================= stats =================

    private fun showStats() {
        val totals = ctx.db.totalPlays()
        fun name(id: Long) = byId[id]?.let { "${it.title} (${it.artist})" } ?: "A deleted song"
        val top = totals.entries.sortedByDescending { it.value }.take(3)
            .mapIndexed { i, (id, c) -> "${i + 1}. ${name(id)} ($c plays)" }.joinToString("\n").ifBlank { "None yet" }
        val now = System.currentTimeMillis()
        val month = ctx.db.playsSince(SongStats.monthStartMs(now)).size
        val year = ctx.db.playsSince(SongStats.yearStartMs(now)).size
        val dlg = xpDialog("Stats")
        val body = xpBody(dlg)
        body.add(JLabel("<html>Library: ${rows.size} songs<br>Total plays: ${totals.values.sum()}<br><br>Top 3:<br>${top.replace("\n", "<br>")}<br><br>This month: $month plays<br>This year: $year plays</html>"))
        val close = XpButton("Close", { theme }) { dlg.dispose() }
        body.add(close)
        dlg.pack()
        dlg.isVisible = true
    }

    private fun shareStats() {
        val totals = ctx.db.totalPlays()
        val obj = org.json.JSONObject()
        obj.put("songs", rows.size)
        obj.put("totalPlays", totals.values.sum())
        val dir = JFileChooser(ctx.paths.exportDir)
        dir.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        if (dir.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return
        val out = File(dir.selectedFile, "BlissPlayer-stats.json")
        out.writeText(obj.toString(1))
        setStatus("Stats saved to ${out.absolutePath}.")
    }

    private fun setStatus(msg: String) {
        statusLabel.text = msg
    }

    override fun dispose() {
        saveResume()
        super.dispose()
    }
}
