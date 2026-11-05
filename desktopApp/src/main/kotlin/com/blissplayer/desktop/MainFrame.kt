package com.blissplayer.desktop

import com.blissplayer.core.AnalyzerRules
import com.blissplayer.core.LoopMode
import com.blissplayer.core.PlaylistPortable
import com.blissplayer.core.QueueItem
import com.blissplayer.music.ShuffleMath
import com.blissplayer.music.model.SongStats
import com.blissplayer.core.TransportCommand
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridLayout
import java.io.File
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
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
import javax.swing.JTextArea
import javax.swing.JTextField
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

    private val titleBar = JLabel()
    private val tabBar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2))
    private val searchField = JTextField(18)
    private val crumb = JLabel()
    private val header = JLabel()
    private val listModel = DefaultListModel<Row>()
    private val list = JList(listModel)
    private val status = JLabel(" ")
    private val progress = JProgressBar()
    private val npTitle = JLabel("Nothing playing")
    private val npTime = JLabel("")
    private val posSlider = JSlider(0, 1000, 0)
    private val playBtn = JButton("Play")
    private val loopBtn = JButton("Loop: All")
    private val speedBox = JComboBox(arrayOf("0.5x", "0.75x", "1x", "1.25x", "1.5x", "2x"))
    private val centerCards = JPanel(java.awt.CardLayout())
    private val listPane = JPanel(BorderLayout())
    private val morePane = JPanel()
    private var lastTrackKey: String? = null
    private var sliderHeld = false
    private val fmt = java.text.SimpleDateFormat("m:ss")

    sealed interface Row {
        data class Song(val row: SongRow) : Row
        data class Group(val key: String, val title: String, val sub: String) : Row
        data object Sep : Row
    }

    init {
        defaultCloseOperation = HIDE_ON_CLOSE
        minimumSize = Dimension(720, 520)
        addWindowListener(object : java.awt.event.WindowAdapter() {
            override fun windowClosing(e: java.awt.event.WindowEvent?) { saveResume() }
        })
        ctx.player.setLoop(LoopMode.valueOf(ctx.settings.getString("loop_mode", "ALL")))
        buildChrome()
        applyTheme()
        ctx.player.onError = { msg -> SwingUtilities.invokeLater { setStatus(msg) } }
        ctx.player.onState = { SwingUtilities.invokeLater { refreshTransport() } }
        Timer(500) { tick() }.start()
        refreshLibrary(first = true)
    }

    // ---------- chrome ----------

    private fun buildChrome() {
        layout = BorderLayout()
        val top = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
        val titleRow = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2))
        titleRow.add(titleBar)
        val themeBtn = JButton("Theme")
        themeBtn.addActionListener { cycleTheme() }
        titleRow.add(themeBtn)
        val layoutBox = JComboBox(arrayOf("Auto", "Phone", "Desktop"))
        layoutBox.selectedItem = layoutMode.replaceFirstChar { it.uppercase() }
        layoutBox.addActionListener {
            layoutMode = (layoutBox.selectedItem as String).lowercase()
            ctx.settings.putString("layout", layoutMode)
            applyLayout()
        }
        titleRow.add(JLabel("Layout:"))
        titleRow.add(layoutBox)
        top.add(titleRow)
        listOf("Songs", "Artists", "Albums", "Genres", "Playlists", "More").forEachIndexed { i, name ->
            val b = JButton(name)
            b.addActionListener { showTab(i) }
            tabBar.add(b)
        }
        val searchRow = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2))
        searchRow.add(JLabel("Search:"))
        searchRow.add(searchField)
        searchField.addActionListener {
            search = searchField.text.trim()
            render()
        }
        val clear = JButton("Clear")
        clear.addActionListener { search = ""; searchField.text = ""; render() }
        searchRow.add(clear)
        top.add(tabBar)
        top.add(searchRow)
        top.add(crumb)
        crumb.cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        crumb.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) { clearDrill() }
        })
        top.add(header)
        add(top, BorderLayout.NORTH)

        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = RowRenderer()
        list.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (e.clickCount == 2) activate(list.locationToIndex(e.point))
            }
            override fun mousePressed(e: java.awt.event.MouseEvent) { maybePopup(e) }
            override fun mouseReleased(e: java.awt.event.MouseEvent) { maybePopup(e) }
        })
        listPane.add(JScrollPane(list), BorderLayout.CENTER)
        morePane.layout = BoxLayout(morePane, BoxLayout.Y_AXIS)
        centerCards.add(listPane, "list")
        centerCards.add(JScrollPane(morePane), "more")
        add(centerCards, BorderLayout.CENTER)

        val bottom = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
        val transport = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2))
        transport.add(playBtn)
        playBtn.addActionListener { togglePlay() }
        val prev = JButton("|<"); prev.addActionListener { ctx.player.prev() }
        val next = JButton(">|"); next.addActionListener { ctx.player.next() }
        transport.add(prev); transport.add(next)
        val queueBtn = JButton("Queue"); queueBtn.addActionListener { showQueue() }
        transport.add(queueBtn)
        transport.add(loopBtn)
        loopBtn.addActionListener { cycleLoop() }
        speedBox.selectedItem = "1x"
        speedBox.addActionListener {
            ctx.player.setSpeed((speedBox.selectedItem as String).removeSuffix("x").toFloat())
        }
        transport.add(JLabel("Speed:")); transport.add(speedBox)
        val favBtn = JButton("Star"); favBtn.addActionListener { toggleFavoriteCurrent() }
        transport.add(favBtn)
        bottom.add(transport)
        val posRow = JPanel(BorderLayout())
        posSlider.addChangeListener {
            if (posSlider.valueIsAdjusting) sliderHeld = true
            else if (sliderHeld) { sliderHeld = false; ctx.player.seekTo((posSlider.value / 1000.0 * ctx.player.durationMs).toLong()) }
        }
        posRow.add(npTitle, BorderLayout.WEST)
        posRow.add(posSlider, BorderLayout.CENTER)
        posRow.add(npTime, BorderLayout.EAST)
        bottom.add(posRow)
        val statusRow = JPanel(BorderLayout())
        statusRow.add(status, BorderLayout.CENTER)
        progress.isVisible = false
        statusRow.add(progress, BorderLayout.EAST)
        bottom.add(statusRow)
        add(bottom, BorderLayout.SOUTH)
    }

    inner class RowRenderer : ListCellRenderer<Row> {
        private val panel = JPanel(BorderLayout())
        private val top = JLabel()
        private val sub = JLabel()
        override fun getListCellRendererComponent(
            list: JList<out Row>, value: Row, index: Int, selected: Boolean, focus: Boolean
        ): Component {
            if (value is Row.Sep) {
                panel.removeAll()
                panel.add(JLabel(" "), BorderLayout.CENTER)
                panel.background = UiTheme.palette(theme).face
                return panel
            }
            panel.removeAll()
            val center = JPanel(GridLayout(2, 1))
            center.add(top); center.add(sub)
            panel.add(center, BorderLayout.CENTER)
            val fav = ctx.db.favorites()
            when (value) {
                is Row.Song -> {
                    val playing = queue.getOrNull(ctx.player.queueIndex)?.mediaId == value.row.mediaId && ctx.player.isPlaying
                    top.text = (if (playing) "> " else "") + value.row.title +
                        (if (value.row.mediaId in fav) "  *" else "")
                    sub.text = "${value.row.artist} | ${value.row.album}  (${fmtDur(value.row.durationMs)})"
                }
                is Row.Group -> { top.text = value.title; sub.text = value.sub }
                else -> {}
            }
            val font = UiTheme.font(theme, 13, value is Row.Group || value is Row.Song)
            top.font = font
            sub.font = UiTheme.font(theme, 11, false)
            UiTheme.styleRow(panel, theme, selected, index % 2 == 1)
            UiTheme.styleRow(center, theme, selected, index % 2 == 1)
            UiTheme.styleRow(top, theme, selected, index % 2 == 1)
            UiTheme.styleRow(sub, theme, selected, index % 2 == 1)
            return panel
        }
    }

    // ---------- theme / layout ----------

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
        UiTheme.applyFrame(rootPane, theme)
        titleBar.text = "BlissPlayer: ${theme.title}"
        titleBar.font = UiTheme.font(theme, 18, true)
        title = "BlissPlayer — ${theme.title}"
        contentPane.background = UiTheme.palette(theme).face
        if (tab == 5) buildMore()
        render()
        repaint()
    }

    fun applyLayoutPublic() = applyLayout()

    private fun applyLayout() {
        // Phone: single column. Desktop/wide: the same components; the More
        // tab and queue open side-by-side via dialogs. Auto follows width.
        val wide = when (layoutMode) {            "phone" -> false
            "desktop" -> true
            else -> width >= 1100
        }
        listPane.preferredSize = if (wide) Dimension(700, 400) else Dimension(480, 400)
        revalidate()
    }

    // ---------- library ----------

    private fun enabledFolders(): Set<String> {
        val raw = ctx.settings.getString("folders", "")
        return if (raw.isBlank()) emptySet() else raw.split(";").filter { it.isNotBlank() }.toSet()
    }

    private fun refreshLibrary(first: Boolean = false) {
        setStatus("Scanning music…")
        progress.isVisible = true
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
                this@MainFrame.progress.value = chunks.lastOrNull() ?: 0
            }
            override fun done() {
                this@MainFrame.progress.isVisible = false
                try {
                    rows = get().toMutableList()
                    byId = rows.associateBy { it.mediaId }
                    setStatus("Library: ${rows.size} songs.")
                    if (first) restoreResume()
                    render()
                } catch (e: Exception) {
                    setStatus("Scan failed: ${e.message}")
                }
            }
        }.execute()
    }

    // ---------- tabs / drills ----------

    private fun listKey(): String = when {
        drillArtist != null -> "ar:$drillArtist"
        drillAlbum != null -> "al:$drillAlbum"
        drillGenre != null -> "ge:$drillGenre"
        drillPlaylist != null -> "pl:$drillPlaylist"
        search.isNotBlank() -> "search"
        else -> "tab:$tab"
    }

    private fun saveScroll() {
        listScroll[listKey()] = list.firstVisibleIndex.coerceAtLeast(0)
    }

    private fun restoreScroll() {
        val pos = listScroll[listKey()] ?: 0
        if (pos > 0) SwingUtilities.invokeLater { list.ensureIndexIsVisible(pos) }
    }

    private fun showTab(t: Int) {
        if (t != tab) { tabHistory.addLast(tab); while (tabHistory.size > 20) tabHistory.removeFirst() }
        saveScroll()
        tab = t
        drillArtist = null; drillAlbum = null; drillGenre = null; drillPlaylist = null
        if (t == 5) { (centerCards.layout as java.awt.CardLayout).show(centerCards, "more"); buildMore() }
        else { (centerCards.layout as java.awt.CardLayout).show(centerCards, "list"); render() }
    }

    fun goBack(): Boolean {
        if (drillArtist != null || drillAlbum != null || drillGenre != null || drillPlaylist != null) {
            clearDrill(); return true
        }
        if (search.isNotBlank()) { search = ""; searchField.text = ""; render(); return true }
        val prev = tabHistory.removeLastOrNull() ?: return false
        showTab(prev); return true
    }

    private fun clearDrill() {
        saveScroll()
        drillArtist = null; drillAlbum = null; drillGenre = null; drillPlaylist = null
        render()
    }

    private fun searched(): List<SongRow> {
        if (search.isBlank()) return rows
        val q = search.lowercase()
        return rows.filter {
            it.title.lowercase().contains(q) || it.artist.lowercase().contains(q) || it.album.lowercase().contains(q)
        }
    }

    private fun render() {
        if (tab == 5) return
        val fav = ctx.db.favorites()
        void(fav)
        val items = mutableListOf<Row>()
        val pool = searched()
        crumb.text = when {
            drillArtist != null -> "Artist: $drillArtist  (click to go back)"
            drillAlbum != null -> "Album: $drillAlbum  (click to go back)"
            drillGenre != null -> "Genre: $drillGenre  (click to go back)"
            drillPlaylist != null -> "Playlist  (click to go back)"
            else -> ""
        }
        val head = when (tab) {
            0 -> "${pool.size} songs. Double-click to play."
            1 -> "Artists"
            2 -> "Albums"
            3 -> "Genres"
            4 -> "Playlists"
            else -> ""
        }
        when {
            drillArtist != null -> {
                val a = drillArtist!!
                val songs = pool.filter { it.artist == a }
                header.text = "$a — ${songs.size} songs."
                songs.forEach { items.add(Row.Song(it)) }
            }
            drillAlbum != null -> {
                val songs = pool.filter { (ctx.db.albumOf(it.mediaId) ?: it.album) == drillAlbum }
                header.text = "$drillAlbum — ${songs.size} songs."
                songs.forEach { items.add(Row.Song(it)) }
            }
            drillGenre != null -> {
                val songs = pool.filter { ctx.db.genreOf(it.mediaId) == drillGenre }
                header.text = "$drillGenre — ${songs.size} songs."
                songs.forEach { items.add(Row.Song(it)) }
            }
            drillPlaylist != null -> {
                val ids = ctx.db.songsIn(drillPlaylist!!)
                header.text = "Playlist — ${ids.size} songs."
                ids.mapNotNull { byId[it] }.forEach { items.add(Row.Song(it)) }
            }
            tab == 0 -> {
                header.text = head
                pool.forEach { items.add(Row.Song(it)) }
            }
            tab == 1 -> {
                val groups = pool.groupBy { it.artist }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
                header.text = "${groups.size} artists."
                groups.forEach { (a, songs) -> items.add(Row.Group("ar:$a", a, "${songs.size} songs")) }
            }
            tab == 2 -> {
                val groups = pool.groupBy { ctx.db.albumOf(it.mediaId) ?: it.album }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
                header.text = "${groups.size} albums."
                groups.forEach { (a, songs) -> items.add(Row.Group("al:$a", a, "${songs.size} songs")) }
            }
            tab == 3 -> {
                val groups = pool.groupBy { ctx.db.genreOf(it.mediaId) }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
                header.text = "${groups.size} genres."
                groups.forEach { (g, songs) -> items.add(Row.Group("ge:$g", g, "${songs.size} songs")) }
            }
            tab == 4 -> {
                header.text = "Playlists"
                val pls = ctx.db.playlists().toMutableList()
                if (pls.none { it.second == "Favorites" }) {
                    val id = ctx.db.createPlaylist("Favorites")
                    pls.add(0, id to "Favorites")
                }
                pls.forEachIndexed { i, (id, name) ->
                    items.add(Row.Group("pl:$id", name, "${ctx.db.songsIn(id).size} songs"))
                    if (i == 0) items.add(Row.Sep)
                }
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
                playQueue(pool, pool.indexOfFirst { it.mediaId == row.row.mediaId }.coerceAtLeast(0))
            }
            is Row.Group -> when {
                row.key.startsWith("ar:") -> { drillArtist = row.title; render() }
                row.key.startsWith("al:") -> { drillAlbum = row.title; render() }
                row.key.startsWith("ge:") -> { drillGenre = row.title; render() }
                row.key.startsWith("pl:") -> { drillPlaylist = row.key.removePrefix("pl:").toLongOrNull(); render() }
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
        val row = listModel.get(idx)
        val menu = JPopupMenu()
        fun add(name: String, fn: () -> Unit) {
            val item = JMenuItem(name)
            item.addActionListener { fn() }
            menu.add(item)
        }
        when (row) {
            is Row.Song -> {
                add("Play now") { playQueue(listOf(row.row), 0) }
                add("Add to queue") { queue = queue + row.row; setStatus("Queued: ${row.row.title}") }
                add("Add to playlist") { addSongsDialog(listOf(row.row.mediaId)) }
                add(if (row.row.mediaId in ctx.db.favorites()) "Unstar" else "Star") {
                    ctx.db.setFavorite(row.row.mediaId, row.row.mediaId !in ctx.db.favorites()); render()
                }
                add("Edit details") { editDetails(row.row) }
                add("Details") { showDetails(row.row) }
            }
            is Row.Group -> add("Open") { activate(idx) }
            else -> {}
        }
        menu.show(e.component, e.x, e.y)
    }

    // ---------- playback ----------

    private fun playQueue(pool: List<SongRow>, start: Int) {
        if (pool.isEmpty()) return
        queue = pool
        val items = pool.map { QueueItem(it.path, it.title, it.artist, it.album, it.durationMs) }
        ctx.player.setQueue(items, start.coerceIn(pool.indices), 0)
        onTrackStarted(pool[start.coerceIn(pool.indices)])
        ctx.player.play()
        refreshTransport()
    }

    private fun playContextShuffled() {
        val pool = currentSongPool().ifEmpty { searched() }
        if (pool.isEmpty()) return
        val smart = ctx.settings.getBoolean("smart_shuffle", true)
        val strength = ctx.settings.getFloat("shuffle_strength", 1f).toDouble()
        val counts = ctx.db.totalPlays()
        val favs = ctx.db.favorites()
        val weights = pool.associate { r ->
            val w = if (!smart) 1.0
            else ShuffleMath.weight(counts[r.mediaId] ?: 0, strength)
            val favBoost = if (r.mediaId in favs) {
                when (ctx.settings.getString("fav_mode", "exempt")) {
                    "normal" -> 1.0
                    "boost" -> ShuffleMath.favoriteWeight(ctx.settings.getFloat("fav_rarity", 1.5f).toDouble())
                    else -> 1000.0
                }
            } else 1.0
            r.mediaId to w * favBoost
        }
        val ordered = ShuffleMath.weightedOrder(pool.map { it.mediaId }, weights, System.currentTimeMillis())
        val byIdLocal = pool.associateBy { it.mediaId }
        playQueue(ordered.mapNotNull { byIdLocal[it] }, 0)
        setStatus("Shuffled ${pool.size} songs.")
    }

    private fun togglePlay() {
        if (queue.isEmpty()) {
            val pool = currentSongPool().ifEmpty { searched() }
            if (pool.isEmpty()) { setStatus("Nothing to play."); return }
            playQueue(pool, 0); return
        }
        ctx.player.toggle()
        refreshTransport()
    }

    private fun onTrackStarted(row: SongRow) {
        ctx.db.bumpPlay(row.mediaId)
        lastTrackKey = row.path
        val gain = ctx.db.allGains()[row.mediaId] ?: 1.0
        ctx.fx.setLoudnessGain(gain)
        ctx.player.setSegments(row.path, ctx.db.segmentsFor(row.mediaId))
        ctx.session.publish(com.blissplayer.core.NowPlaying(
            QueueItem(row.path, row.title, row.artist, row.album, row.durationMs),
            true, 0, row.durationMs))
        ctx.notify.notify(row.title, row.artist)
        saveResume()
    }

    private fun tick() {
        if (queue.isEmpty()) return
        val idx = ctx.player.queueIndex
        val cur = queue.getOrNull(idx)
        if (cur != null && cur.path != lastTrackKey) onTrackStarted(cur)
        if (!sliderHeld) {
            val dur = ctx.player.durationMs.coerceAtLeast(1)
            posSlider.value = ((ctx.player.positionMs * 1000.0 / dur).toInt()).coerceIn(0, 1000)
        }
        val pos = fmtDur(ctx.player.positionMs)
        val dur = fmtDur(ctx.player.durationMs)
        npTime.text = "$pos / $dur"
        ctx.session.publish(com.blissplayer.core.NowPlaying(
            cur?.let { QueueItem(it.path, it.title, it.artist, it.album, it.durationMs) },
            ctx.player.isPlaying, ctx.player.positionMs, ctx.player.durationMs))
    }

    private fun refreshTransport() {
        val cur = queue.getOrNull(ctx.player.queueIndex)
        playBtn.text = if (ctx.player.isPlaying) "Pause" else "Play"
        npTitle.text = cur?.let { "${it.title} — ${it.artist}" } ?: "Nothing playing"
        val mode = LoopMode.valueOf(ctx.settings.getString("loop_mode", "ALL"))
        loopBtn.text = "Loop: ${mode.name.lowercase().replaceFirstChar { it.uppercase() }}"
        list.repaint()
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
        render()
    }

    private fun fmtDur(ms: Long): String = "%d:%02d".format((ms / 60000), (ms % 60000) / 1000)

    private fun showQueue() {
        val dlg = JDialog(this, "Queue (${queue.size})", false)
        dlg.layout = BorderLayout()
        val model = DefaultListModel<String>()
        queue.forEachIndexed { i, r -> model.addElement("${i + 1}. ${r.title} — ${r.artist}") }
        val ql = JList(model)
        dlg.add(JScrollPane(ql), BorderLayout.CENTER)
        val bar = JPanel()
        val playNow = JButton("Play now")
        playNow.addActionListener {
            val i = ql.selectedIndex
            if (i >= 0) { playQueue(queue, i); dlg.dispose() }
        }
        val remove = JButton("Remove")
        remove.addActionListener {
            val i = ql.selectedIndex
            if (i >= 0) { queue = queue.filterIndexed { j, _ -> j != i }; model.remove(i) }
        }
        val close = JButton("Close")
        close.addActionListener { dlg.dispose() }
        bar.add(playNow); bar.add(remove); bar.add(close)
        dlg.add(bar, BorderLayout.SOUTH)
        dlg.setSize(420, 400)
        dlg.isVisible = true
    }

    // ---------- resume ----------

    private fun saveResume() {
        val q = queue.joinToString(",") { it.path }
        ctx.settings.putString("resume_queue", q.take(200_000))
        ctx.settings.putString("resume_index", ctx.player.queueIndex.toString())
        ctx.settings.putString("resume_pos", ctx.player.positionMs.toString())
    }

    private fun restoreResume() {
        val saved = ctx.settings.getString("resume_queue", "")
        if (saved.isBlank() || queue.isNotEmpty()) return
        val paths = saved.split(",").filter { it.isNotBlank() }
        if (paths.isEmpty()) return
        val found = paths.mapNotNull { p -> rows.find { it.path == p } }
        if (found.isEmpty()) return
        queue = found
        val idx = ctx.settings.getString("resume_index", "0").toIntOrNull()?.coerceIn(found.indices) ?: 0
        val pos = ctx.settings.getString("resume_pos", "0").toLongOrNull() ?: 0L
        ctx.player.setQueue(found.map { QueueItem(it.path, it.title, it.artist, it.album, it.durationMs) }, idx, pos)
        lastTrackKey = found[idx].path
        refreshTransport()
    }

    // ---------- more panels ----------

    private fun section(name: String): JLabel {
        val l = JLabel(name)
        UiTheme.styleSection(l, theme)
        l.alignmentX = Component.LEFT_ALIGNMENT
        return l
    }

    private fun hint(text: String): JLabel {
        val l = JLabel("<html>$text</html>")
        l.font = UiTheme.font(theme, 11, false)
        l.foreground = UiTheme.palette(theme).dim
        l.alignmentX = Component.LEFT_ALIGNMENT
        return l
    }

    private fun btn(name: String, fn: () -> Unit): JButton {
        val b = JButton(name)
        b.alignmentX = Component.LEFT_ALIGNMENT
        b.addActionListener { fn() }
        return b
    }

    private fun buildMore() {
        morePane.removeAll()
        morePane.add(section("Your music"))
        morePane.add(btn("Scan for music") { refreshLibrary() })
        morePane.add(btn("Choose folder") {
            val fc = JFileChooser(ctx.paths.music)
            fc.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                ctx.settings.putString("music_dir", fc.selectedFile.absolutePath)
                refreshLibrary()
            }
        })
        morePane.add(hint("Tick a folder to play from it. Unticked folders stay listed, grayed out, and are skipped."))
        folderRows()
        morePane.add(section("Song analyzer"))
        val online = JCheckBox("Online lookup (MusicBrainz + Wikipedia)", ctx.settings.getBoolean("analyzer_online", true))
        online.addActionListener { ctx.settings.putBoolean("analyzer_online", online.isSelected) }
        morePane.add(online)
        val scope = JComboBox(arrayOf("New/changed", "Everything"))
        morePane.add(scope)
        morePane.add(btn("Run analyzer") { runAnalyzer(scope.selectedIndex == 1, online.isSelected) })
        morePane.add(hint("Cleans names offline, confirms online. Review before anything is written."))
        morePane.add(section("Loudness"))
        val strength = JSlider(0, 200, (ctx.settings.getFloat("eq_strength", 1f) * 100).toInt())
        morePane.add(strength)
        morePane.add(btn("Equalize library") {
            runLoudness(strength.value / 100f)
            ctx.settings.putFloat("eq_strength", strength.value / 100f)
        })
        morePane.add(hint("Matches volume across songs. 100% lands every song on the average; higher pushes further."))
        morePane.add(section("Boost"))
        morePane.add(btn("Exact boost") { boostDialog() })
        morePane.add(hint("Louder than 200% may clip. Per-output presets are remembered."))
        morePane.add(section("Live sound mixer"))
        morePane.add(btn("Open frequency mixer") { mixerDialog() })
        morePane.add(hint("Plain words, same controls as the phone: bass through treble plus overall loudness."))
        morePane.add(section("Playback controls"))
        morePane.add(btn("Shuffle play") { playContextShuffled() })
        morePane.add(hint("Smart Shuffle favors songs you play less. Favorites stay exempt unless you change that below."))
        morePane.add(section("Playlists"))
        morePane.add(btn("New playlist") {
            val name = JOptionPane.showInputDialog(this, "Playlist name:")
            if (!name.isNullOrBlank()) { ctx.db.createPlaylist(name.trim()); render() }
        })
        morePane.add(btn("Export portable playlist") { exportPlaylist() })
        morePane.add(btn("Import portable playlist") { importPlaylist() })
        morePane.add(hint("Portable JSON links by file and metadata, never by device-only ids. Missing songs are kept, never dropped."))
        morePane.add(section("Stats"))
        morePane.add(btn("Show stats") { showStats() })
        morePane.add(btn("Share stats file") { shareStats() })
        morePane.add(btn("Reset all stats") {
            if (JOptionPane.showConfirmDialog(this, "Wipe play counts and history?") == JOptionPane.YES_OPTION) {
                // Fresh start: rebuild stats tables.
                ctx.db.exec("DELETE FROM plays"); ctx.db.exec("DELETE FROM song_stats"); ctx.db.exec("DELETE FROM stat_events")
                setStatus("Stats wiped.")
            }
        })
        morePane.add(section("Look and feel"))
        morePane.add(btn("Switch theme (now: ${theme.title})") { cycleTheme(); buildMore() })
        morePane.add(hint("Each theme has its own font and materials. Desktop switches live, no restart."))
        morePane.add(section("About"))
        morePane.add(hint("BlissPlayer ${DesktopMain.version()} — Linux desktop. Same core as Android: analyzer, loudness, playlists, stats."))
        morePane.revalidate()
        morePane.repaint()
    }

    private fun folderRows() {
        val all = rows.mapNotNull { File(it.path).parent }.distinct().sorted()
        val enabled = enabledFolders().toMutableSet()
        val allOn = enabled.isEmpty()
        for (p in all.take(60)) {
            val on = allOn || enabled.any { p == it || p.startsWith(it.trimEnd('/') + "/") }
            val box = JCheckBox("$p ${if (on) "" else "(off)"}", on)
            box.addActionListener {
                val cur = enabledFolders().toMutableSet()
                if (box.isSelected) cur.add(p) else {
                    cur.remove(p)
                    if (cur.isEmpty()) { /* keep empty = all on */ }
                }
                ctx.settings.putString("folders", cur.joinToString(";"))
                refreshLibrary()
            }
            morePane.add(box)
        }
        if (all.size > 60) morePane.add(hint("…and ${all.size - 60} more folders (use Choose folder to narrow)."))
    }

    // ---------- analyzer / loudness ----------

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
        val dlg = JDialog(this, "Review (${proposals.size})", true)
        dlg.layout = BorderLayout()
        val model = DefaultListModel<String>()
        proposals.forEach { model.addElement("[${it.confidence}] ${it.newTitle} — ${it.newArtist} (${it.reason})") }
        val ql = JList(model)
        dlg.add(JScrollPane(ql), BorderLayout.CENTER)
        val bar = JPanel()
        val apply = JButton("Apply selected")
        apply.addActionListener {
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
        val all = JButton("Select all")
        all.addActionListener { ql.setSelectionInterval(0, model.size - 1) }
        val close = JButton("Close")
        close.addActionListener { dlg.dispose() }
        bar.add(all); bar.add(apply); bar.add(close)
        dlg.add(bar, BorderLayout.SOUTH)
        dlg.setSize(640, 420)
        dlg.isVisible = true
    }

    private fun runLoudness(strength: Float) {
        setStatus("Equalizing in the background…")
        progress.isVisible = true
        progress.isIndeterminate = true
        val target = rows.filter { ctx.db.needsWork(it.mediaId, "${it.sizeBytes}:${it.modified}", "eq") }
            .ifEmpty { rows }
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

    // ---------- playlists ----------

    private fun addSongsDialog(ids: List<Long>) {
        val pls = ctx.db.playlists()
        if (pls.isEmpty()) { setStatus("Make a playlist first."); return }
        val choice = JOptionPane.showInputDialog(this, "Add to playlist:", "Playlists",
            JOptionPane.PLAIN_MESSAGE, null, pls.map { it.second }.toTypedArray(), pls[0].second)
        val target = pls.firstOrNull { it.second == choice } ?: return
        ctx.db.addToPlaylist(target.first, ids)
        setStatus("Added ${ids.size} to ${target.second}.")
        render()
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

    // ---------- mixer / boost / stats ----------

    private fun boostDialog() {
        val cur = (ctx.fx.boost() * 100).toInt()
        val input = JOptionPane.showInputDialog(this, "Exact boost percent (over 200 may clip):", cur.toString())
        val pct = input?.toIntOrNull() ?: return
        ctx.fx.setBoost(pct / 100.0) { w -> JOptionPane.showMessageDialog(this, w) }
        setStatus("Boost: $pct%.")
    }

    private fun mixerDialog() {
        val dlg = JDialog(this, "Frequency mixer", true)
        dlg.layout = GridLayout(0, 2, 8, 4)
        val names = listOf("Bass", "Low-mid", "Mid", "High-mid", "Treble")
        val sliders = names.map { JSlider(-12, 12, 0) }
        val cur = ctx.fx.current()
        cur.bandsDb.forEachIndexed { i, v -> if (i < sliders.size) sliders[i].value = v.toInt() }
        names.forEachIndexed { i, n ->
            dlg.add(JLabel("$n (dB)")); dlg.add(sliders[i])
        }
        val pre = JSlider(-12, 12, cur.preampDb.toInt())
        dlg.add(JLabel("Overall loudness (dB)")); dlg.add(pre)
        val desc = JLabel(ctx.fx.describe(cur))
        dlg.add(desc); dlg.add(JLabel(""))
        val apply = JButton("Apply")
        apply.addActionListener {
            val state = com.blissplayer.core.EqState(true, sliders.map { it.value.toFloat() }, pre.value.toFloat())
            ctx.fx.apply(state)
            desc.text = ctx.fx.describe(state)
            setStatus("Mixer applied.")
        }
        val close = JButton("Close")
        close.addActionListener { dlg.dispose() }
        dlg.add(apply); dlg.add(close)
        dlg.pack()
        dlg.isVisible = true
    }

    private fun showStats() {
        val totals = ctx.db.totalPlays()
        val byIdLocal = byId
        fun name(id: Long) = byIdLocal[id]?.let { "${it.title} (${it.artist})" } ?: "A deleted song"
        val top = totals.entries.sortedByDescending { it.value }.take(3)
            .mapIndexed { i, (id, c) -> "${i + 1}. ${name(id)} ($c plays)" }.joinToString("\n").ifBlank { "None yet" }
        val now = System.currentTimeMillis()
        val month = ctx.db.playsSince(SongStats.monthStartMs(now)).size
        val year = ctx.db.playsSince(SongStats.yearStartMs(now)).size
        JOptionPane.showMessageDialog(this,
            "Library: ${rows.size} songs\nTotal plays: ${totals.values.sum()}\n\nTop 3:\n$top\n\n" +
                "This month: $month plays\nThis year: $year plays")
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

    // ---------- song details ----------

    private fun showDetails(r: SongRow) {
        val genre = ctx.db.genreOf(r.mediaId)
        val album = ctx.db.albumOf(r.mediaId) ?: r.album
        JOptionPane.showMessageDialog(this,
            "${r.title}\n${r.artist} | $album\nGenre: $genre\n${fmtDur(r.durationMs)} — ${r.sizeBytes / 1024} KB\n${r.path}")
    }

    private fun editDetails(r: SongRow) {
        val genre = ctx.db.genreOf(r.mediaId)
        val album = ctx.db.albumOf(r.mediaId) ?: r.album
        val title = JTextField(r.title)
        val artist = JTextField(r.artist)
        val alb = JTextField(album)
        val gen = JTextField(genre)
        val panel = JPanel(GridLayout(0, 2))
        panel.add(JLabel("Title:")); panel.add(title)
        panel.add(JLabel("Artist:")); panel.add(artist)
        panel.add(JLabel("Album:")); panel.add(alb)
        panel.add(JLabel("Genre:")); panel.add(gen)
        if (JOptionPane.showConfirmDialog(this, panel, "Edit details", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return
        val err = DesktopTags.write(r.path, com.blissplayer.core.TagData(
            title.text.trim(), artist.text.trim(), alb.text.trim(), gen.text.trim()))
        if (err != null) { setStatus("Tag write failed: $err"); return }
        ctx.db.setGenre(r.mediaId, gen.text.trim().ifBlank { "Uncategorized" })
        ctx.db.setAlbum(r.mediaId, alb.text.trim())
        setStatus("Saved.")
        refreshLibrary()
    }

    private fun setStatus(msg: String) {
        status.text = msg
    }

    private fun void(@Suppress("UNUSED_PARAMETER") x: Any) {}

    override fun dispose() {
        saveResume()
        super.dispose()
    }
}
