package com.blissplayer.music

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Player.REPEAT_MODE_ALL
import androidx.media3.common.Player.REPEAT_MODE_OFF
import androidx.media3.common.Player.REPEAT_MODE_ONE
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.blissplayer.music.data.BlissDb
import com.blissplayer.music.data.MusicScanner
import com.blissplayer.music.data.TagTools
import com.blissplayer.music.model.Seg
import com.blissplayer.music.model.Song
import com.blissplayer.music.model.SongNames
import com.blissplayer.music.playback.LoudnessScanner
import com.blissplayer.music.playback.PlayerService
import com.blissplayer.music.playback.SafeVolumeGuard
import com.blissplayer.music.playback.VolumeManager
import com.blissplayer.music.ui.LibraryAdapter
import com.blissplayer.music.ui.marquee
import com.blissplayer.music.ui.PickAdapter
import com.blissplayer.music.ui.PickRow
import com.blissplayer.music.ui.Row
import com.blissplayer.music.ui.SegmentSeekBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var db: BlissDb
    private var dark = false

    private var songs: List<Song> = emptyList()
    private val byId = mutableMapOf<Long, Song>()

    // In-memory play-count caches so list sorting and stats never touch
    // the database on the main thread (that stalls with 1000+ songs).
    // Refreshed after scan, after every counted play, and after resets.
    private var cachedCounts: Map<Long, Int> = emptyMap()
    private var cachedTotals: Map<Long, Int> = emptyMap()

    /** Reload count caches in the background, then refresh visible stats. */
    private fun refreshCountCache() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val c = db.playCounts()
                val t = db.totalPlays()
                launch(Dispatchers.Main) {
                    cachedCounts = c
                    cachedTotals = t
                    if (wantStats() && tab != 5) {
                        adapter.statLines = statLines()
                        adapter.notifyDataSetChanged()
                    }
                }
            } catch (e: Exception) {
                // Counts stay stale rather than crashing the UI.
            }
        }
    }

    private fun wantStats(): Boolean =
        prefs.getBoolean("show_total", false) ||
            prefs.getBoolean("show_cycle", false) ||
            prefs.getBoolean("show_chance", false)

    // Queue mirror. Always matches the player item for item.
    private val queue = mutableListOf<Song>()
    private var shuffled = false
    private var lastCountedId: Long = -1L
    private var pendingQueueSync = false
    private var restoredQueueIds: LongArray? = null
    private var listenAccumMs: Long = 0L
    private var lastListenSave: Long = 0L

    // Search state. Active when the query or any filter is set.
    private var searchQuery = ""
    private var filterArtist = ""
    private var filterAlbum = ""
    private var filterGenre = ""
    private var filterPlaylist: Long? = null
    private var filterPlaylistName = ""
    private data class SearchIdx(
        val song: Song,
        val title: String,
        val artist: String,
        val album: String,
        val genre: String,
        val file: String,
        val all: String,
        val words: List<String>
    )
    private var searchIndex: List<SearchIdx> = emptyList()
    private var genreMap: Map<Long, String> = emptyMap()
    private var albumMap: Map<Long, String> = emptyMap()

    /** Display album: override wins, file tag otherwise. */
    private fun effAlbum(s: Song): String =
        albumMap[s.mediaId] ?: s.album

    /** Play counts credited to every artist name on the song. */
    private fun creditArtists(counts: Map<Long, Int>): Map<String, Int> {
        val m = mutableMapOf<String, Int>()
        for (s in songs) {
            val c = counts[s.mediaId] ?: 0
            if (c <= 0) continue
            for (a in com.blissplayer.music.data.SongAnalyzer.splitArtists(s.artist).ifEmpty { listOf("Unknown artist") }) {
                m[a] = (m[a] ?: 0) + c
            }
        }
        return m
    }
    private var searchJob: Job? = null
    private var scanJob: Job? = null

    // Library view state
    private var tab = 0 // 0 songs, 1 artists, 2 albums, 3 genres, 4 playlists, 5 more
    private var drillArtist: String? = null
    private var drillAlbum: String? = null
    private var drillGenre: String? = null
    private var drillPlaylist: Long? = null
    private var drillPlaylistName: String? = null

    private lateinit var adapter: LibraryAdapter
    private lateinit var titleBar: TextView
    private lateinit var titleBarWrap: LinearLayout
    private lateinit var crumb: TextView
    private lateinit var playlistAdd: Button
    private lateinit var headerBar: TextView
    private lateinit var searchBox: EditText
    private lateinit var filterBtn: Button
    private lateinit var searchClear: Button
    private lateinit var chipsRow: LinearLayout
    private lateinit var searchToggle: ImageButton
    private lateinit var statusStrip: TextView
    private lateinit var list: RecyclerView
    private lateinit var morePane: ScrollView
    private lateinit var moreBox: LinearLayout
    private lateinit var miniTitle: TextView
    private lateinit var miniSub: TextView
    private lateinit var miniPlayPause: ImageButton
    private lateinit var miniProgress: ProgressBar
    private lateinit var miniTime: TextView

    private var svc: PlayerService? = null
    private lateinit var vol: VolumeManager
    private var lastUiDevice: String? = null
    private val volumeUiListener: () -> Unit = {
        updateBoostViews()
        if (lastUiDevice != vol.currentDevice && tab == 5) buildMorePane()
        lastUiDevice = vol.currentDevice
    }
    private lateinit var guard: SafeVolumeGuard
    private val ticker = Handler(Looper.getMainLooper())

    private var pendingRetry: (() -> Unit)? = null
    private var eqJob: Job? = null
    private var permissionRequestInFlight = false

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        permissionRequestInFlight = false
        scan()
        if (::vol.isInitialized) vol.refreshRoute()
        maybePromptBackgroundPlay()
    }

    private val senderLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { r ->
        val retry = pendingRetry
        pendingRetry = null
        if (r.resultCode == RESULT_OK) {
            if (retry != null) retry.invoke() else toast("Access granted. Run the operation again to continue.")
        } else toast("Permission was not granted. No further files were changed.")
    }

    private var crashExportText: String? = null
    private val crashExportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        val log = crashExportText ?: CrashReporter.read(this)
        crashExportText = null
        if (uri != null && log != null) lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    checkNotNull(contentResolver.openOutputStream(uri, "wt")).use { it.write(log.toByteArray()) }
                }
                toast("Crash log saved to the file you chose.")
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                toast("Could not save crash log: ${e.message}")
            }
        }
    }

    private val folderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        contentResolver.takePersistableUriPermission(
            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
        val path = treeUriToPath(uri)
        if (path == null) {
            toast("Picked, but Android gave no file path. Scan-all stays on.")
            return@registerForActivityResult
        }
        val set = prefs.getStringSet("folders", emptySet())!!.toMutableSet()
        set.add(path)
        prefs.edit().putStringSet("folders", set).apply()
        buildMorePane()
        scan(fromTap = true)
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(n: ComponentName?, b: IBinder?) {
            svc = (b as PlayerService.LocalBinder).service()
            svc!!.boost = vol.boost
            svc!!.applyGainFor(svc!!.player.currentMediaItem)
            svc!!.player.addListener(playerListener)
            if (songs.isNotEmpty()) {
                syncQueueFromPlayer()
                // If the player is empty (fresh service) but we have a saved
                // session, bring it back so the user never starts from nothing.
                if ((svc!!.player.mediaItemCount == 0)) restoreResume()
            } else pendingQueueSync = true
            refreshMini()
        }

        override fun onServiceDisconnected(n: ComponentName?) {
            svc = null
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
            onTrackChanged(item, reason)
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            // Catches every seek and auto advance, so the title on screen
            // can never disagree with what is actually playing.
            refreshMini()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            miniPlayPause.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
            npPlay?.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
            setPlayBtnIcon(isPlaying)
            refreshMini()
        }
    }

    private var lastTrackIndex = -1

    private fun onTrackChanged(item: MediaItem?, reason: Int) {
        val p = svc?.player
        val newIdx = p?.currentMediaItemIndex ?: -1
        val wrapped = prefs.getBoolean("loop_shuffle", true) &&
            p?.repeatMode == REPEAT_MODE_ALL &&
            reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO &&
            lastTrackIndex > 0 && newIdx == 0 && queue.size > 1
        lastTrackIndex = newIdx
        refreshMini()
        saveResume()
        if (queueDialog != null) {
            updateNowPlayingHeader()
            refreshQueueList()
        }
        // Loop All reshuffles on wrap so the next lap plays in a fresh order.
        if (wrapped) {
            val cur = queue.firstOrNull()
            if (cur != null) {
                val rest = queue.drop(1)
                lifecycleScope.launch {
                    replaceTail(smartOrder(rest))
                    refreshQueueList()
                }
            }
        }
        val id = item?.mediaId?.toLongOrNull()
        if (id != null && id != lastCountedId) {
            lastCountedId = id
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) {
                prefs.edit().putLong("stats_skips", prefs.getLong("stats_skips", 0) + 1).apply()
            }
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    db.bumpPlay(id)
                    // Once every song in the library has played at least once,
                    // wipe the slate so Smart Shuffle stays fresh forever.
                    val counts = db.playCounts()
                    if (songs.isNotEmpty() && songs.all { (counts[it.mediaId] ?: 0) > 0 }) {
                        db.resetPlays()
                    }
                    launch(Dispatchers.Main) { refreshCountCache() }
                } catch (e: Exception) {
                    // A failed count write must never kill playback.
                }
            }
        }
    }

    private fun updateNowPlayingHeader() {
        val p = svc?.player ?: return
        val song = p.currentMediaItem?.mediaId?.toLongOrNull()?.let { byId[it] }
        nowTitleView?.text = song?.title ?: "Now playing"
        nowArtistView?.text = song?.let { "${it.artist} | ${effAlbum(it)}" } ?: ""
        val segs = song?.let { db.segmentsFor(it.mediaId) } ?: emptyList()
        nowSegBar?.let { bar ->
            bar.segments = segs
            bar.maxMs = p.duration.coerceAtLeast(1)
            bar.invalidate()
        }
        npBar?.let { bar ->
            bar.segments = segs
            bar.maxMs = p.duration.coerceAtLeast(1)
            bar.invalidate()
        }
    }

    // ---------- lifecycle ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = getSharedPreferences("bliss", Context.MODE_PRIVATE)
        dark = prefs.getBoolean("dark", false)
        setTheme(if (dark) R.style.Theme_BlissPlayer_Night else R.style.Theme_BlissPlayer_Day)
        super.onCreate(savedInstanceState)
        CrashReporter.install(this)
        applyRotation()
        val lastCrash = CrashReporter.takePending(this)
        crashExportText = savedInstanceState?.getString("crashExportText")
        setContentView(R.layout.activity_main)
        db = BlissDb(this)
        // Reimport any JSON playlists missing from the DB (reinstall safety).
        try {
            com.blissplayer.music.data.PlaylistJson.importMissing(this, db)
        } catch (e: Exception) {
        }
        refreshCountCache()

        restoredQueueIds = savedInstanceState?.getLongArray("queue")
        lastCountedId = savedInstanceState?.getLong("counted", -1L) ?: -1L
        searchQuery = savedInstanceState?.getString("searchQuery", "") ?: ""
        filterArtist = savedInstanceState?.getString("filterArtist", "") ?: ""
        filterAlbum = savedInstanceState?.getString("filterAlbum", "") ?: ""
        filterGenre = savedInstanceState?.getString("filterGenre", "") ?: ""
        filterPlaylist = savedInstanceState?.getLong("filterPlaylist", -1L)?.takeIf { it != -1L && it != null }
        filterPlaylistName = savedInstanceState?.getString("filterPlaylistName", "") ?: ""
        tab = savedInstanceState?.getInt("tab", 0) ?: 0
        drillArtist = savedInstanceState?.getString("drillArtist")
        drillAlbum = savedInstanceState?.getString("drillAlbum")
        drillGenre = savedInstanceState?.getString("drillGenre")
        drillPlaylist = savedInstanceState?.let {
            if (it.containsKey("drillPlaylist")) it.getLong("drillPlaylist") else null
        }
        drillPlaylistName = savedInstanceState?.getString("drillPlaylistName")

        titleBarWrap = findViewById(R.id.titleBarWrap)
        titleBar = findViewById(R.id.titleBar)
        crumb = findViewById(R.id.crumb)
        playlistAdd = findViewById(R.id.playlistAdd)
        playlistAdd.setOnClickListener {
            val id = drillPlaylist ?: return@setOnClickListener
            addSongsDialog(id, drillPlaylistName ?: "Playlist")
        }
        headerBar = findViewById(R.id.headerBar)
        searchBox = findViewById(R.id.searchBox)
        filterBtn = findViewById(R.id.filterBtn)
        searchClear = findViewById(R.id.searchClear)
        chipsRow = findViewById(R.id.chipsRow)
        searchToggle = findViewById(R.id.searchToggle)
        searchToggle.setOnClickListener { toggleSearch() }
        searchBox.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                searchQuery = s?.toString() ?: ""
                refreshResults()
            }
        })
        filterBtn.setOnClickListener { filterDialog() }
        searchClear.setOnClickListener { clearSearch() }
        if (searchQuery.isNotEmpty()) {
            findViewById<LinearLayout>(R.id.searchRow)?.visibility = View.VISIBLE
            searchBox.setText(searchQuery)
        } else if (savedInstanceState?.getBoolean("searchOpen", false) == true) {
            findViewById<LinearLayout>(R.id.searchRow)?.visibility = View.VISIBLE
        }
        statusStrip = findViewById(R.id.statusStrip)
        list = findViewById(R.id.list)
        morePane = findViewById(R.id.morePane)
        moreBox = findViewById(R.id.moreBox)
        miniTitle = findViewById(R.id.miniTitle)
        miniSub = findViewById(R.id.miniSub)
        miniPlayPause = findViewById(R.id.miniPlayPause)
        miniProgress = findViewById(R.id.miniProgress)
        miniTime = findViewById(R.id.miniTime)
        // Cut-off song lines scroll sideways on their own loop.
        miniTitle.marquee()
        miniSub.marquee()
        crumb.marquee()

        // Keep the layout clear of the status bar and gesture bar.
        val root = findViewById<LinearLayout>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(dp(8), b.top + dp(4), dp(8), b.bottom + dp(4))
            insets
        }

        skinStatic()
        miniProgress.progressDrawable = xpProgress()
        adapter = LibraryAdapter(dark = dark, onTap = ::onRowTap, onMenu = ::onRowMenu)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        ) {
            override fun onMove(rv: RecyclerView, h: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder): Boolean {
                val rows = adapter.rows.toMutableList()
                val a = h.bindingAdapterPosition
                val b = t.bindingAdapterPosition
                if (a !in rows.indices || b !in rows.indices) return false
                // Inside a playlist drill: reorder its songs, persist the order.
                if (drillPlaylist != null) {
                    java.util.Collections.swap(rows, a, b)
                    adapter.submit(rows)
                    val pid = drillPlaylist ?: return true
                    db.reorderPlaylist(pid, rows.mapNotNull { (it as? Row.SongRow)?.song?.mediaId })
                    syncPlaylists()
                    return true
                }
                // On the playlists tab: reorder the playlists themselves.
                if (tab == 4) {
                    val ra = rows.getOrNull(a) as? Row.Group ?: return false
                    val rb = rows.getOrNull(b) as? Row.Group ?: return false
                    if (!ra.key.startsWith("pl:") || !rb.key.startsWith("pl:")) return false
                    java.util.Collections.swap(rows, a, b)
                    adapter.submit(rows)
                    db.reorderPlaylists(rows.mapNotNull { (it as? Row.Group)?.key?.removePrefix("pl:")?.toLongOrNull() })
                    syncPlaylists()
                    return true
                }
                return false
            }

            override fun onSwiped(h: RecyclerView.ViewHolder, dir: Int) {
                val pid = drillPlaylist
                val row = adapter.rows.getOrNull(h.bindingAdapterPosition) as? Row.SongRow
                if (pid == null || row == null) {
                    adapter.notifyItemChanged(h.bindingAdapterPosition)
                    return
                }
                db.removeFromPlaylist(pid, row.song.mediaId)
                syncPlaylists()
                render()
            }
        }).attachToRecyclerView(list)

        findViewById<Button>(R.id.tabSongs).setOnClickListener { showTab(0) }
        findViewById<Button>(R.id.tabArtists).setOnClickListener { showTab(1) }
        findViewById<Button>(R.id.tabAlbums).setOnClickListener { showTab(2) }
        findViewById<Button>(R.id.tabPlaylists).setOnClickListener { showTab(4) }
        findViewById<Button>(R.id.tabGenres).setOnClickListener { showTab(3) }
        findViewById<Button>(R.id.tabMore).setOnClickListener { showTab(5) }
        findViewById<Button>(R.id.btnPlayAll).setOnClickListener { playContext() }
        findViewById<Button>(R.id.btnPlayShuffle)?.setOnClickListener { playContextShuffled() }
        findViewById<Button>(R.id.btnSort)?.setOnClickListener { sortDialog() }
        crumb.setOnClickListener {
            if (isSearchActive()) clearSearch() else clearDrill()
        }
        miniPlayPause.setOnClickListener { togglePlay() }
        findViewById<ImageButton>(R.id.miniNext).setOnClickListener { skipNext() }
        findViewById<ImageButton>(R.id.miniQueue).setOnClickListener { openNowPlaying() }
        findViewById<LinearLayout>(R.id.miniPlayer).setOnClickListener { openNowPlaying() }

        // Landscape right panel. Absent in portrait, so everything is null-safe.
        npBar = findViewById(R.id.npBar)
        npTime = findViewById(R.id.npTime)
        npTitle = findViewById(R.id.npTitle)
        npArtist = findViewById(R.id.npArtist)
        npPlay = findViewById(R.id.npPlay)
        npLoop = findViewById(R.id.npLoop)
        npBar?.dark = dark
        npTitle?.marquee()
        npArtist?.marquee()
        findViewById<ImageButton>(R.id.npPlay)?.setOnClickListener { togglePlay() }
        findViewById<ImageButton>(R.id.npPrev)?.setOnClickListener { skipPrev() }
        findViewById<ImageButton>(R.id.npNext)?.setOnClickListener { skipNext() }
        findViewById<Button>(R.id.npLoop)?.setOnClickListener { cycleRepeat() }
        findViewById<Button>(R.id.npShuffle)?.setOnClickListener { shuffleQueue() }
        findViewById<Button>(R.id.npSaveQueue)?.setOnClickListener {
            if (queue.isEmpty()) {
                toast("Queue is empty.")
                return@setOnClickListener
            }
            createPlaylistDialog(queue.map { it.mediaId })
        }
        findViewById<Button>(R.id.npSegEdit)?.setOnClickListener {
            currentSong()?.let { segmentDialog(it) } ?: toast("Play something first.")
        }
        findViewById<RecyclerView>(R.id.npQueue)?.let { qv ->
            qv.layoutManager = LinearLayoutManager(this)
            landQueueAdapter = makeQueueAdapter()
            qv.adapter = landQueueAdapter
            attachQueueTouch(qv) { landQueueAdapter }
        }

        vol = (application as BlissApplication).volumeManager
        vol.addListener(volumeUiListener)
        guard = SafeVolumeGuard(this, prefs) { svc?.player?.isPlaying == true }
        guard.onFightingSystem = {
            runOnUiThread {
                toast("The phone keeps lowering the volume. Open Sound settings and turn off the volume limit.")
            }
        }
        guard.start()

        startService(Intent(this, PlayerService::class.java))
        bindService(
            Intent(this, PlayerService::class.java).setAction("com.blissplayer.music.PLAYER_UI"),
            conn, Context.BIND_AUTO_CREATE
        )

        // Restored drill state survives showTab's clearing so rotation and
        // theme switches land back exactly where the user was.
        val keepA = drillArtist
        val keepB = drillAlbum
        val keepG = drillGenre
        val keepP = drillPlaylist
        val keepN = drillPlaylistName
        showTab(tab.coerceIn(0, 5))
        drillArtist = keepA
        drillAlbum = keepB
        drillGenre = keepG
        drillPlaylist = keepP
        drillPlaylistName = keepN
        render()
        ensurePermissions()
        ticker.post(progressTick)
        buildMorePane()
        if (lastCrash != null) showCrashDialog(lastCrash)
        else if (hasAudioPermission()) maybePromptBackgroundPlay()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLongArray("queue", queue.map { it.mediaId }.toLongArray())
        outState.putString("crashExportText", crashExportText)
        outState.putLong("counted", lastCountedId)
        outState.putString("searchQuery", searchQuery)
        outState.putString("filterArtist", filterArtist)
        outState.putString("filterAlbum", filterAlbum)
        outState.putString("filterGenre", filterGenre)
        outState.putLong("filterPlaylist", filterPlaylist ?: -1L)
        outState.putString("filterPlaylistName", filterPlaylistName)
        outState.putBoolean("searchOpen", findViewById<LinearLayout>(R.id.searchRow)?.visibility == View.VISIBLE)
        outState.putInt("tab", tab)
        outState.putString("drillArtist", drillArtist)
        outState.putString("drillAlbum", drillAlbum)
        outState.putString("drillGenre", drillGenre)
        if (drillPlaylist != null) outState.putLong("drillPlaylist", drillPlaylist!!)
        outState.putString("drillPlaylistName", drillPlaylistName)
    }

    override fun onPause() {
        super.onPause()
        saveResume()
        persistListenTime()
    }

    override fun onDestroy() {
        ticker.removeCallbacksAndMessages(null)
        saveResume()
        persistListenTime()
        svc?.player?.removeListener(playerListener)
        vol.removeListener(volumeUiListener)
        runCatching { unbindService(conn) }
        guard.stop()
        eqJob?.cancel()
        analyzerJob?.cancel()
        scanJob?.cancel()
        searchJob?.cancel()
        super.onDestroy()
    }

    /** Copy text to the clipboard. True when the system took it. */
    private fun copyText(label: String, text: String): Boolean {
        return try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText(label, text))
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun showCrashDialog(log: String) {
        val tv = TextView(this).apply {
            text = log
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val sv = ScrollView(this).apply {
            addView(tv)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(300)
            )
        }
        AlertDialog.Builder(this)
            .setCustomTitle(xpTitle("Previous crash report"))
            .setMessage("Kept privately in the app. Copy it or choose where to export it. This alert will not repeat; the report stays available in More.")
            .setView(sv)
            .setPositiveButton("Copy") { _, _ ->
                if (copyText("crash", log)) {
                    toast("Copied. Now paste it in chat.")
                } else {
                    toast("Copy failed. Use Export to choose a file instead.")
                }
            }
            .setNeutralButton("Export") { _, _ -> exportCrash(log) }
            .setNegativeButton("Close", null)
            .setOnDismissListener { maybePromptBackgroundPlay() }
            .show()
    }

    private fun exportCrash(log: String) {
        crashExportText = log
        runCatching { crashExportLauncher.launch("BlissPlayer-crash.txt") }
            .onFailure { toast("No file picker is available. Nothing was saved.") }
    }

    // ---------- permissions / scan ----------

    private fun ensurePermissions() {
        val need = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED)
                need.add(Manifest.permission.READ_MEDIA_AUDIO)
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                need.add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
                need.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission("android.permission.BLUETOOTH_CONNECT") != PackageManager.PERMISSION_GRANTED
        ) need.add("android.permission.BLUETOOTH_CONNECT")
        if (need.isEmpty()) scan() else {
            permissionRequestInFlight = true
            permLauncher.launch(need.toTypedArray())
        }
    }

    private fun hasAudioPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= 33)
            checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
        else checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun hasNotifPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= 33)
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        else true

    private fun isBackgroundAllowed(): Boolean = try {
        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val am = getSystemService(android.app.ActivityManager::class.java)
        pm.isIgnoringBatteryOptimizations(packageName) && !am.isBackgroundRestricted
    } catch (e: Exception) {
        false
    }

    private var backgroundPromptShown = false

    private fun maybePromptBackgroundPlay() {
        if (isFinishing || isDestroyed || permissionRequestInFlight || backgroundPromptShown || isBackgroundAllowed()) return
        if (prefs.getBoolean("background_prompt_declined", false)) return
        backgroundPromptShown = true
        AlertDialog.Builder(this)
            .setCustomTitle(xpTitle("Allow background playback?"))
            .setMessage("Android is limiting BlissPlayer in the background. Music may stop with the screen off or in another app. Choose Allow, then select Unrestricted or allow battery optimization exemption in Android settings. You can change this later in More.")
            .setPositiveButton("Open settings") { _, _ -> openBackgroundSettings() }
            .setNegativeButton("Not now") { _, _ ->
                prefs.edit().putBoolean("background_prompt_declined", true).apply()
            }
            .show()
    }

    private fun openBackgroundSettings() {
        val am = getSystemService(android.app.ActivityManager::class.java)
        val request = if (am.isBackgroundRestricted) Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:$packageName")) else Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$packageName"))
        runCatching { startActivity(request) }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                .onFailure { toast("Open Android app settings, BlissPlayer, Battery, then Unrestricted.") }
        }
    }

    override fun onResume() {
        super.onResume()
        // Coming back from system settings: refresh states like the
        // background-play button so they show the truth.
        if (tab == 5 && ::moreBox.isInitialized) buildMorePane()
        if (::vol.isInitialized) vol.refreshRoute()
    }

    private fun scan(fromTap: Boolean = false) {
        if (!hasAudioPermission()) {
            adapter.submit(emptyList())
            crumb.text = "BlissPlayer needs Music permission. Grant it, then tap Scan in More."
            updateStatus()
            if (fromTap) toast("Music permission is missing, so there is nothing to scan.")
            return
        }
        if (fromTap) toast("Scanning for music.")
        // One scan at a time: rapid taps cancel the stale run instead of
        // racing it on the shared song list.
        scanJob?.cancel()
        scanJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                val all = MusicScanner.scanAll(this@MainActivity)
                val folders = prefs.getStringSet("folders", emptySet())!!
                // Songs with no readable path cannot be proven outside the
                // chosen folders, so they stay included instead of vanishing.
                val filtered = if (folders.isEmpty()) all
                else all.filter { s ->
                    val fp = s.filePath
                    fp == null || s.isInFolders(folders)
                }
                val genres = db.allGenres()
                var albums = db.allAlbums()
                // Folder names are not albums: seed an honest override once
                // so "Music" never poses as a record.
                var seeded = false
                for (s in filtered) {
                    if (!albums.containsKey(s.mediaId) &&
                        com.blissplayer.music.data.SongAnalyzer.isPlaceholderAlbum(s.album, s.filePath)
                    ) {
                        db.setAlbum(s.mediaId, "Unknown album")
                        seeded = true
                    }
                }
                if (seeded) albums = db.allAlbums()
                val counts = db.playCounts()
                val totals = db.totalPlays()
                launch(Dispatchers.Main) {
                    songs = filtered
                    byId.clear()
                    filtered.forEach { byId[it.mediaId] = it }
                    genreMap = genres
                    albumMap = albums
                    cachedCounts = counts
                    cachedTotals = totals
                    searchIndex = filtered.map { s ->
                        val g = genreMap[s.mediaId] ?: "Uncategorized"
                        val t = norm(s.title)
                        val a = norm(s.artist)
                        val al = norm(albums[s.mediaId] ?: s.album)
                        val gn = norm(g)
                        val fn = norm(s.filePath?.substringAfterLast('/')?.substringBeforeLast('.') ?: "")
                        val all = "$t $a $al $gn $fn"
                        SearchIdx(s, t, a, al, gn, fn, all, all.split(' ').filter { it.isNotEmpty() })
                    }
                    restoreQueueIfNeeded()
                    if (pendingQueueSync) {
                        pendingQueueSync = false
                        syncQueueFromPlayer()
                    }
                    // Fresh launch with no queue: pick up where the user left off.
                    if (queue.isEmpty()) restoreResume()
                    render()
                    updateStatus()
                    refreshMini()
                    if (tab == 5) buildMorePane()
                    if (fromTap) {
                        toast(
                            if (filtered.isEmpty()) "Scan done. No songs found."
                            else "Scan done. ${filtered.size} songs."
                        )
                    }
                    if (filtered.isEmpty()) {
                        crumb.text = if (all.isEmpty())
                            "No music found. Copy songs into the Music folder, then open More and tap Scan."
                        else "No songs in the chosen folders. Clear folders in More to scan everything."
                    }
                }
            } catch (e: Exception) {
                launch(Dispatchers.Main) {
                    toast("Scan ran into a problem and stopped. Try again.")
                }
            }
        }
    }

    /** Rebuild the queue mirror from the player (fixes empty or stale queue). */
    private fun syncQueueFromPlayer() {
        val p = svc?.player ?: return
        if (p.mediaItemCount == 0 || byId.isEmpty()) return
        val ids = (0 until p.mediaItemCount).mapNotNull {
            p.getMediaItemAt(it).mediaId.toLongOrNull()
        }
        val found = ids.mapNotNull { byId[it] }
        if (found.size == ids.size && found.isNotEmpty()) {
            queue.clear()
            queue.addAll(found)
        }
    }

    /** If mirror and player disagree in size OR on the current song, the player wins. */
    private fun ensureQueueSync() {
        val p = svc?.player ?: return
        if (p.mediaItemCount != queue.size) {
            syncQueueFromPlayer()
            return
        }
        if (p.mediaItemCount == 0 || byId.isEmpty()) return
        val curId = p.currentMediaItem?.mediaId?.toLongOrNull()
        val mirrorId = queue.getOrNull(p.currentMediaItemIndex.coerceAtLeast(0))?.mediaId
        if (curId != null && mirrorId != null && curId != mirrorId) {
            syncQueueFromPlayer()
            refreshMini()
            refreshQueueList()
        }
    }

    private fun restoreQueueIfNeeded() {
        val ids = restoredQueueIds ?: return
        restoredQueueIds = null
        if (queue.isNotEmpty() || ids.isEmpty()) return
        val found = ids.toList().mapNotNull { byId[it] }
        if (found.isNotEmpty()) {
            queue.clear()
            queue.addAll(found)
        }
    }

    /** Persist queue + position so relaunch picks up exactly where it left off. */
    private fun saveResume() {
        val p = svc?.player ?: return
        if (queue.isEmpty() || p.mediaItemCount == 0) return
        try {
            val ids = queue.joinToString(",") { it.mediaId.toString() }
            // Cap stored length so 10k-song queues do not blow up prefs.
            val trimmed = if (ids.length > 200_000) ids.substring(0, 200_000) else ids
            prefs.edit()
                .putString("resume_queue", trimmed)
                .putInt("resume_index", p.currentMediaItemIndex.coerceAtLeast(0))
                .putLong("resume_pos", p.currentPosition.coerceAtLeast(0))
                .putBoolean("resume_shuffled", shuffled)
                .putInt("resume_repeat", p.repeatMode)
                .apply()
        } catch (e: Exception) {
            // Never let resume saving interrupt playback.
        }
    }

    /** Restore saved queue after scan. Seeks to saved position, stays paused until user plays. */
    private fun restoreResume() {
        val saved = prefs.getString("resume_queue", null) ?: return
        if (saved.isBlank() || queue.isNotEmpty()) return
        val p = svc?.player ?: return
        try {
            val ids = saved.split(',').mapNotNull { it.toLongOrNull() }
            if (ids.isEmpty()) return
            val found = ids.mapNotNull { byId[it] }
            if (found.isEmpty()) return
            val idx = prefs.getInt("resume_index", 0).coerceIn(0, found.size - 1)
            val pos = prefs.getLong("resume_pos", 0).coerceAtLeast(0)
            queue.clear()
            queue.addAll(found)
            shuffled = prefs.getBoolean("resume_shuffled", false)
            p.setMediaItems(found.map(::toItem), idx, pos)
            p.repeatMode = prefs.getInt("resume_repeat", REPEAT_MODE_OFF)
            p.prepare()
            p.pause()
            lastCountedId = found[idx].mediaId
            lastTrackIndex = idx
            refreshMini()
        } catch (e: Exception) {
            // Corrupt resume data: drop it.
            prefs.edit().remove("resume_queue").apply()
        }
    }

    // ---------- tabs / lists ----------

    private fun showTab(t: Int) {
        tab = t
        clearDrill(silent = true)
        titleBar.text = "BlissPlayer: ${listOf("Songs", "Artists", "Albums", "Genres", "Playlists", "More")[t]}"
        morePane.visibility = if (t == 5) View.VISIBLE else View.GONE
        list.visibility = if (t == 5) View.GONE else View.VISIBLE
        // The action row belongs to the library, not to settings.
        val mainUi = if (t == 5) View.GONE else View.VISIBLE
        findViewById<Button>(R.id.btnPlayAll).visibility = mainUi
        findViewById<Button>(R.id.btnPlayShuffle)?.visibility = mainUi
        findViewById<Button>(R.id.btnSort)?.visibility = mainUi
        findViewById<LinearLayout>(R.id.playRow)?.visibility = mainUi
        crumb.visibility = mainUi
        playlistAdd.visibility = View.GONE
        headerBar.visibility = View.GONE
        updateTabs()
        if (t == 5) buildMorePane() else render()
    }

    private val tabIds = listOf(R.id.tabSongs, R.id.tabArtists, R.id.tabAlbums, R.id.tabGenres, R.id.tabPlaylists, R.id.tabMore)

    private fun updateTabs() {
        tabIds.forEachIndexed { i, id ->
            findViewById<Button>(id).apply {
                setBackgroundResource(R.drawable.xp_tab)
                isSelected = i == tab
                setPadding(dp(2), dp(7), dp(2), dp(7))
                minimumWidth = 0
            }
        }
    }

    private fun clearDrill(silent: Boolean = false) {
        drillArtist = null
        drillAlbum = null
        drillGenre = null
        drillPlaylist = null
        drillPlaylistName = null
        if (!silent) render()
    }

    private fun showingSongs(): Boolean =
        tab == 0 || drillArtist != null || drillAlbum != null || drillGenre != null || drillPlaylist != null

    // ---------- search (fuzzy + filters) ----------

    private fun isSearchActive(): Boolean =
        searchQuery.isNotBlank() || filterArtist.isNotBlank() || filterAlbum.isNotBlank() ||
            filterGenre.isNotBlank() || filterPlaylist != null

    /** Lowercase, no accents, only letters and digits. Typos still match after this. */
    private fun norm(s: String): String {
        val n = java.text.Normalizer.normalize(s.lowercase(Locale.US), java.text.Normalizer.Form.NFD)
        return n.replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
    }

    /** Levenshtein with an early exit past cap. Small words must match closer. */
    private fun lev(a: String, b: String, cap: Int): Int {
        if (kotlin.math.abs(a.length - b.length) > cap) return cap + 1
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            var rowMin = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                rowMin = minOf(rowMin, cur[j])
            }
            if (rowMin > cap) return cap + 1
            val t = prev
            prev = cur
            cur = t
        }
        return prev[b.length]
    }

    private fun computeResults(): List<Song> {
        val q = norm(searchQuery)
        val tokens = q.split(' ').filter { it.isNotEmpty() }
        val inPlaylist: Set<Long>? = filterPlaylist?.let { db.songsIn(it).toSet() }
        val fA = norm(filterArtist)
        val fB = norm(filterAlbum)
        val fG = norm(filterGenre)
        val out = mutableListOf<Pair<Song, Int>>()
        for (e in searchIndex) {
            val s = e.song
            if (inPlaylist != null && s.mediaId !in inPlaylist) continue
            if (fA.isNotEmpty() && !e.artist.contains(fA)) continue
            if (fB.isNotEmpty() && !e.album.contains(fB)) continue
            if (fG.isNotEmpty() && !e.genre.contains(fG)) continue
            if (tokens.isEmpty()) {
                out.add(s to 0)
                continue
            }
            var score = 0
            var ok = true
            if (e.title.contains(q)) score -= 100
            if (e.artist.contains(q)) score -= 50
            if (e.album.contains(q)) score -= 20
            if (e.genre.contains(q)) score -= 10
            if (e.file.contains(q)) score -= 8
            for (t in tokens) {
                if (e.all.contains(t)) {
                    score -= 5
                    continue
                }
                if (t.length <= 2) {
                    ok = false
                    break
                }
                val cap = if (t.length <= 4) 1 else 2
                val hit = e.words.any { w -> w.length >= 2 && lev(t, w, cap) <= cap }
                if (!hit) {
                    ok = false
                    break
                } else {
                    score += t.length
                }
            }
            if (ok) out.add(s to score)
        }
        return out.sortedWith(compareBy({ it.second }, { it.first.sortTitle })).map { it.first }
    }

    private fun refreshResults() {
        searchJob?.cancel()
        searchJob = lifecycleScope.launch {
            kotlinx.coroutines.delay(150)
            val res = kotlinx.coroutines.withContext(Dispatchers.Default) { computeResults() }
            if (tab != 5 && isSearchActive()) {
                adapter.submit(res.map { Row.SongRow(it) })
                crumb.text = "${res.size} results | tap to clear"
                headerBar.visibility = View.VISIBLE
                playlistAdd.visibility = View.GONE
                searchClear.visibility = View.VISIBLE
                rebuildChips()
            } else {
                searchClear.visibility = if (isSearchActive()) View.VISIBLE else View.GONE
                if (!isSearchActive()) render()
            }
        }
    }

    private fun toggleSearch() {
        val row = findViewById<LinearLayout>(R.id.searchRow) ?: return
        if (row.visibility == View.VISIBLE) {
            row.visibility = View.GONE
            chipsRow.visibility = View.GONE
            clearSearch()
        } else {
            row.visibility = View.VISIBLE
            searchBox.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(searchBox, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun clearSearch() {
        searchQuery = ""
        filterArtist = ""
        filterAlbum = ""
        filterGenre = ""
        filterPlaylist = null
        filterPlaylistName = ""
        searchBox.setText("")
        rebuildChips()
        render()
    }

    private fun rebuildChips() {
        chipsRow.removeAllViews()
        fun chip(label: String, clear: () -> Unit) {
            val b = xpBtn("$label ×")
            b.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(6) }
            b.setOnClickListener { clear() }
            chipsRow.addView(b)
        }
        if (filterArtist.isNotBlank()) chip("Artist: $filterArtist") { filterArtist = ""; refreshResults() }
        if (filterAlbum.isNotBlank()) chip("Album: $filterAlbum") { filterAlbum = ""; refreshResults() }
        if (filterGenre.isNotBlank()) chip("Genre: $filterGenre") { filterGenre = ""; refreshResults() }
        if (filterPlaylist != null) chip("Playlist: $filterPlaylistName") {
            filterPlaylist = null
            filterPlaylistName = ""
            refreshResults()
        }
        chipsRow.visibility = if (chipsRow.childCount == 0) View.GONE else View.VISIBLE
    }

    private fun filterDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = xpBtnPanel()
        }
        fun field(label: String, value: String): EditText {
            box.addView(TextView(this).apply { text = label })
            return EditText(this).apply {
                setText(value)
                box.addView(this)
            }
        }
        val eArtist = field("Artist contains", filterArtist)
        val eAlbum = field("Album contains", filterAlbum)
        var gSel = filterGenre
        var pSel = filterPlaylist
        var pName = filterPlaylistName
        val gBtn = xpBtn(if (gSel.isBlank()) "Genre: any" else "Genre: $gSel").apply {
            setOnClickListener {
                val all = (genreMap.values.distinct().sorted() + "Uncategorized").distinct().sorted()
                val names = (listOf("Any genre") + all)
                xpMenuDialog("Genre", names) { which ->
                    gSel = if (which == 0) "" else all[which - 1]
                    text = if (gSel.isBlank()) "Genre: any" else "Genre: $gSel"
                }
            }
        }
        box.addView(gBtn)
        val pBtn = xpBtn(if (pSel == null) "Playlist: any" else "Playlist: $pName").apply {
            setOnClickListener {
                val pls = db.playlists()
                val names = (listOf("Any playlist") + pls.map { it.second })
                xpMenuDialog("In playlist", names) { which ->
                    if (which == 0) {
                        pSel = null
                        pName = ""
                    } else {
                        pSel = pls[which - 1].first
                        pName = pls[which - 1].second
                    }
                    text = if (pSel == null) "Playlist: any" else "Playlist: $pName"
                }
            }
        }
        box.addView(pBtn)
        box.addView(TextView(this).apply {
            text = "Filters narrow the search to matching songs. Titles and file names still match loosely, so typos are fine."
            textSize = 12f
        })
        AlertDialog.Builder(this)
            .setCustomTitle(xpTitle("Search filters"))
            .setView(box)
            .setPositiveButton("Apply") { _, _ ->
                filterArtist = eArtist.text.toString()
                filterAlbum = eAlbum.text.toString()
                filterGenre = gSel
                filterPlaylist = pSel
                filterPlaylistName = pName
                rebuildChips()
                refreshResults()
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Clear all") { _, _ -> clearSearch() }
            .show()
    }

    private fun render() {
        if (tab == 5) return
        adapter.numbered = false
        titleBar.text = "BlissPlayer: ${listOf("Songs", "Artists", "Albums", "Genres", "Playlists")[tab]}"
        headerBar.visibility = if (showingSongs()) View.VISIBLE else View.GONE
        if (isSearchActive()) {
            titleBar.text = "BlissPlayer: Search"
            searchClear.visibility = View.VISIBLE
            refreshResults()
            return
        }
        searchClear.visibility = View.GONE
        playlistAdd.visibility = if (drillPlaylist != null) View.VISIBLE else View.GONE
        // Migrate old single toggle to three separate toggles once.
        if (!prefs.contains("show_total") && prefs.getBoolean("show_counts", false)) {
            prefs.edit().putBoolean("show_total", true).putBoolean("show_cycle", true).putBoolean("show_chance", true).apply()
        }
        val wantStats = prefs.getBoolean("show_total", false) ||
            prefs.getBoolean("show_cycle", false) ||
            prefs.getBoolean("show_chance", false)
        adapter.showStats = wantStats
        adapter.statLines = if (wantStats) statLines() else emptyMap()
        adapter.albumLines = songs.associate { it.mediaId to effAlbum(it) }
        val sortKey = currentSortKey()
        when {
            drillArtist != null -> {
                val a = drillArtist!!
                // Multi-artist songs appear under every credited name.
                val inA = applySort(
                    songs.filter { a in com.blissplayer.music.data.SongAnalyzer.splitArtists(it.artist) },
                    sortKey
                )
                crumb.text = "$a (${inA.size}) | tap here to go back"
                adapter.submit(inA.map { Row.SongRow(it) })
            }
            drillAlbum != null -> {
                // Albums come from tags and overrides, never from folder names.
                val a = drillAlbum!!
                val inA = songs.filter { effAlbum(it) == a }.sortedBy { it.sortTitle }
                crumb.text = "$a (${inA.size}) | tap here to go back"
                adapter.numbered = true
                adapter.submit(inA.map { Row.SongRow(it) })
            }
            drillGenre != null -> {
                val g = drillGenre!!
                val inG = applySort(songs.filter { (genreMap[it.mediaId] ?: "Uncategorized") == g }, sortKey)
                crumb.text = "$g (${inG.size}) | tap here to go back"
                adapter.submit(inG.map { Row.SongRow(it) })
            }
            drillPlaylist != null -> {
                val ids = db.songsIn(drillPlaylist!!).mapNotNull { byId[it] }
                crumb.text = "${drillPlaylistName ?: "Playlist"} (${ids.size}) | tap here to go back"
                adapter.numbered = true
                adapter.submit(ids.map { Row.SongRow(it) })
            }
            tab == 0 -> {
                crumb.text = if (songs.isEmpty()) "" else "${songs.size} songs. Tap a song to play."
                adapter.submit(applySort(songs, sortKey).map { Row.SongRow(it) })
            }
            tab == 1 -> {
                // Artists alphabetized by default; multi-artist songs list
                // under every credited name.
                val groups = mutableMapOf<String, MutableList<Song>>()
                for (s in songs) {
                    val names = com.blissplayer.music.data.SongAnalyzer.splitArtists(s.artist)
                        .ifEmpty { listOf("Unknown artist") }
                    for (a in names) {
                        groups.getOrPut(a) { mutableListOf() }.add(s)
                    }
                }
                val totals = cachedTotals
                fun artistPlays(name: String): Int = groups[name]?.sumOf { totals[it.mediaId] ?: 0 } ?: 0
                val sortedKeys = when (sortKey) {
                    "MostPlayed" -> groups.keys.sortedByDescending { artistPlays(it) }
                    "LeastPlayed" -> groups.keys.sortedBy { artistPlays(it) }
                    "Album", "Artist", "AZ" -> groups.keys.sortedWith(String.CASE_INSENSITIVE_ORDER)
                    "ZA" -> groups.keys.sortedWith(String.CASE_INSENSITIVE_ORDER).reversed()
                    "Longest" -> groups.keys.sortedByDescending { k -> groups[k]?.sumOf { it.durationMs } ?: 0 }
                    "Shortest" -> groups.keys.sortedBy { k -> groups[k]?.sumOf { it.durationMs } ?: 0 }
                    else -> groups.keys.sortedWith(String.CASE_INSENSITIVE_ORDER)
                }
                crumb.text = "${groups.size} artists"
                adapter.submit(sortedKeys.map { k -> Row.Group("ar:$k", k, "${groups[k]?.size ?: 0} songs") })
            }
            tab == 2 -> {
                val groups = songs.groupBy { effAlbum(it) }
                val sortedKeys = when (sortKey) {
                    "ZA" -> groups.keys.sortedWith(String.CASE_INSENSITIVE_ORDER).reversed()
                    "Artist" -> groups.keys.sortedBy { (groups[it]?.firstOrNull()?.artist ?: "").lowercase() }
                    "Album", "AZ" -> groups.keys.sortedWith(String.CASE_INSENSITIVE_ORDER)
                    "Longest" -> groups.keys.sortedByDescending { k -> groups[k]?.sumOf { it.durationMs } ?: 0 }
                    "Shortest" -> groups.keys.sortedBy { k -> groups[k]?.sumOf { it.durationMs } ?: 0 }
                    "MostPlayed" -> {
                        val t = cachedTotals
                        groups.keys.sortedByDescending { k -> groups[k]?.sumOf { t[it.mediaId] ?: 0 } ?: 0 }
                    }
                    "LeastPlayed" -> {
                        val t = cachedTotals
                        groups.keys.sortedBy { k -> groups[k]?.sumOf { t[it.mediaId] ?: 0 } ?: 0 }
                    }
                    else -> groups.keys.sortedWith(String.CASE_INSENSITIVE_ORDER)
                }
                crumb.text = "${groups.size} albums"
                adapter.submit(sortedKeys.map { k ->
                    val v = groups[k] ?: emptyList()
                    Row.Group("al:$k", k, "${v.firstOrNull()?.artist ?: ""} | ${v.size} songs")
                })
            }
            tab == 3 -> {
                val groups = songs.groupBy { genreMap[it.mediaId] ?: "Uncategorized" }
                    .toSortedMap(String.CASE_INSENSITIVE_ORDER)
                crumb.text = "${groups.size} genres"
                adapter.submit(groups.map { (k, v) -> Row.Group("ge:$k", k, "${v.size} songs") })
            }
            else -> {
                val pls = db.playlists()
                crumb.text = if (pls.isEmpty()) "No playlists yet. Tap the first row, or use the dots on a song."
                else "${pls.size} playlists. Tap to open, dots to rename or delete, drag to reorder."
                val rows = mutableListOf<Row>(
                    Row.Group("__new", "Create playlist", "Keep a mix together")
                )
                pls.forEach { (id, name) ->
                    rows.add(Row.Group("pl:$id", name, "${db.songsIn(id).size} songs"))
                }
                adapter.numbered = true
                adapter.submit(rows)
            }
        }
        rebuildChips()
        updateStatus()
    }

    private fun updateStatus() {
        val pct = (vol.boost * 100).roundToInt()
        statusStrip.text = "${songs.size} songs | Boost $pct percent | ${vol.currentDevice}"
    }

    /**
     * Per song stats line for the list: total plays ever, plays this cycle,
     * and the chance of coming up next under Smart Shuffle. Each part has its
     * own toggle. Chance is a compact percentage with decimals so big
     * libraries still read clearly (0.08 percent, not a capped label).
     * Reads the in-memory count caches: safe to call on the main thread.
     */
    private fun statLines(): Map<Long, String> {
        val smart = prefs.getBoolean("shuffle_smart", true)
        val strength = prefs.getFloat("shuffle_strength", 1.0f).toDouble().coerceIn(0.0, 3.0)
        val showTotal = prefs.getBoolean("show_total", true)
        val showCycle = prefs.getBoolean("show_cycle", true)
        val showChance = prefs.getBoolean("show_chance", true)
        val artistPenalty = prefs.getBoolean("shuffle_artist", false)
        val counts = cachedCounts
        val totals = cachedTotals
        val artistCounts: Map<String, Int> = if (smart && artistPenalty) {
            creditArtists(counts)
        } else emptyMap()
        fun wOf(id: Long, artist: String): Double {
            var w = if (!smart) 1.0
            else 1.0 / Math.pow((1 + (counts[id] ?: 0)).toDouble(), strength)
            if (smart && artistPenalty) {
                val ac = com.blissplayer.music.data.SongAnalyzer.splitArtists(artist)
                    .ifEmpty { listOf("Unknown artist") }
                    .sumOf { artistCounts[it] ?: 0 }
                if (ac > 0) w /= Math.pow((1 + ac).toDouble(), strength * 0.5)
            }
            return w.coerceAtLeast(1e-12)
        }
        val weights = songs.associate { it.mediaId to wOf(it.mediaId, it.artist) }
        val sum = weights.values.sum().coerceAtLeast(1e-9)
        return songs.associate { s ->
            val parts = mutableListOf<String>()
            if (showTotal) parts.add("${totals[s.mediaId] ?: 0} total")
            if (showCycle) parts.add("${counts[s.mediaId] ?: 0} cycle")
            if (showChance) {
                val w = weights[s.mediaId] ?: 0.0
                parts.add("${fmtChance(w / sum)} next")
            }
            if (parts.isEmpty()) parts.add("${totals[s.mediaId] ?: 0} plays")
            s.mediaId to parts.joinToString(" | ")
        }
    }

    /** Compact percentage: whole numbers stay whole, small ones get decimals. */
    fun fmtChance(frac: Double): String = ShuffleMath.fmtChance(frac)

    private fun visibleSongs(): List<Song> =
        adapter.rows.mapNotNull { (it as? Row.SongRow)?.song }

    private fun currentSortKey(): String =
        prefs.getString("sort_songs", "AZ") ?: "AZ"

    /** Sort songs for display and contextual play. Albums use metadata, never folders. */
    private fun applySort(list: List<Song>, key: String): List<Song> {
        return when (key) {
            "ZA" -> list.sortedByDescending { it.sortTitle }
            "Artist" -> list.sortedWith(compareBy({ SongNames.sortKey(it.artist) }, { SongNames.sortKey(it.album) }, { it.sortTitle }))
            "Album" -> list.sortedWith(compareBy({ SongNames.sortKey(it.album) }, { it.sortTitle }))
            "Longest" -> list.sortedByDescending { it.durationMs }
            "Shortest" -> list.sortedBy { it.durationMs }
            "MostPlayed" -> {
                val t = cachedTotals
                list.sortedByDescending { t[it.mediaId] ?: 0 }
            }
            "LeastPlayed" -> {
                val t = cachedTotals
                list.sortedBy { t[it.mediaId] ?: 0 }
            }
            else -> list.sortedBy { it.sortTitle }
        }
    }

    private fun sortDialog() {
        val options = listOf("A to Z", "Z to A", "By artist", "By album", "Most played", "Least played", "Longest", "Shortest")
        val keys = listOf("AZ", "ZA", "Artist", "Album", "MostPlayed", "LeastPlayed", "Longest", "Shortest")
        val curKey = currentSortKey()
        val curIdx = keys.indexOf(curKey).coerceAtLeast(0)
        val curName = options[curIdx]
        xpMenuDialog("Sort songs: $curName", options) { which ->
            prefs.edit().putString("sort_songs", keys[which]).apply()
            render()
        }
    }

    private fun onRowTap(row: Row) {
        when (row) {
            is Row.SongRow -> {
                // Searching: do not nuke the queue. Slip the song in next
                // and jump to it, as if Play Next + Skip had been tapped.
                if (isSearchActive()) {
                    playSearchedSong(row.song)
                    return
                }
                val context = visibleSongs()
                val idx = context.indexOfFirst { it.mediaId == row.song.mediaId }.coerceAtLeast(0)
                playQueue(context, idx)
            }
            is Row.Group -> when {
                row.key == "__new" -> createPlaylistDialog(emptyList())
                row.key.startsWith("ar:") -> {
                    drillArtist = row.key.removePrefix("ar:")
                    render()
                }
                row.key.startsWith("al:") -> {
                    drillAlbum = row.key.removePrefix("al:")
                    render()
                }
                row.key.startsWith("ge:") -> {
                    drillGenre = row.key.removePrefix("ge:")
                    render()
                }
                row.key.startsWith("pl:") -> {
                    drillPlaylist = row.key.removePrefix("pl:").toLong()
                    drillPlaylistName = row.title
                    render()
                }
            }
        }
    }

    private fun onRowMenu(row: Row) {
        when (row) {
            is Row.SongRow -> songMenu(row.song)
            is Row.Group -> when {
                row.key.startsWith("pl:") && row.key != "__new" ->
                    playlistMenu(row.key.removePrefix("pl:").toLong(), row.title)
                row.key.startsWith("ar:") -> artistMenu(row.key.removePrefix("ar:"))
                row.key.startsWith("al:") -> albumMenu(row.key.removePrefix("al:"))
                row.key.startsWith("ge:") -> genreMenu(row.key.removePrefix("ge:"))
                row.key == "__new" -> createPlaylistDialog(emptyList())
            }
        }
    }

    // ---------- playback ops (mirror list + player, always in sync) ----------

    private fun toItem(s: Song): MediaItem =
        MediaItem.Builder()
            .setUri(Uri.parse(s.contentUri))
            .setMediaId(s.mediaId.toString())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(s.title)
                    .setArtist(s.artist)
                    .setAlbumTitle(s.album)
                    .build()
            )
            .build()

    private fun playQueue(context: List<Song>, start: Int) {
        val p = svc?.player ?: run { toast("Player is starting. Try again in a second."); return }
        if (context.isEmpty()) {
            toast("Nothing to play.")
            return
        }
        queue.clear()
        queue.addAll(context)
        shuffled = false
        lastTrackIndex = start.coerceIn(0, context.size - 1)
        p.setMediaItems(context.map(::toItem), start.coerceIn(0, context.size - 1), 0)
        // Keep the user's loop mode; do not force it off.
        p.prepare()
        p.play()
        saveResume()
        refreshMini()
    }

    /** Tapping a search hit slips it in next and jumps to it. Queue survives. */
    private fun playSearchedSong(song: Song) {
        val p = svc?.player ?: run { toast("Player is starting. Try again in a second."); return }
        ensureQueueSync()
        if (queue.isEmpty() || p.mediaItemCount == 0) {
            playQueue(listOf(song), 0)
            return
        }
        val at = (p.currentMediaItemIndex + 1).coerceIn(0, queue.size)
        queue.add(at, song)
        p.addMediaItem(at, toItem(song))
        p.seekTo(at, 0)
        p.play()
        saveResume()
        refreshQueueList()
    }

    /** The Play button plays whatever is showing: search results, a drill list, or everything. */
    private fun playContext() {
        if (isSearchActive()) {
            val res = visibleSongs()
            if (res.isEmpty()) {
                toast("No results to play.")
                return
            }
            playQueue(res, 0)
            return
        }
        val ctx = applySort(visibleSongs(), currentSortKey())
        if (ctx.isNotEmpty()) {
            playQueue(ctx, 0)
            return
        }
        if (songs.isEmpty()) {
            toast("No music yet. Copy songs on, then Scan.")
            return
        }
        playQueue(applySort(songs, currentSortKey()), 0)
    }

    /** Split Play button: last slice starts shuffled from a random song. */
    private fun playContextShuffled() {
        val base = if (isSearchActive()) visibleSongs() else {
            val v = visibleSongs()
            if (v.isNotEmpty()) v else songs
        }
        if (base.isEmpty()) {
            toast("No music yet. Copy songs on, then Scan.")
            return
        }
        val ordered = smartOrder(base)
        if (ordered.isEmpty()) return
        playQueue(ordered, 0)
        shuffled = true
        saveResume()
        refreshQueueList()
    }

    /**
     * Smart Shuffle. Each song carries a lifetime play count. Songs played
     * less get a bigger slice of the odds, so the mix stays fresh instead
     * of repeating common songs. When everything has played once, counts reset.
     * Strength 0 is gentle, 1 is balanced, higher is harsher. Off = true random.
     *
     * Uses an exponential race (Efraimidis-Spirakis): key = u^(1/w), sorted
     * largest first. Large weight (unplayed) -> key near 1 -> plays first.
     * Small weight (played 11 times) -> key near 0 -> plays last. Sorting
     * ascending here would invert the odds and replay favorites first.
     */
    private fun smartOrder(list: List<Song>): List<Song> {
        if (!prefs.getBoolean("shuffle_smart", true)) {
            return list.shuffled(Random(System.currentTimeMillis()))
        }
        val strength = prefs.getFloat("shuffle_strength", 1.0f).toDouble().coerceIn(0.0, 3.0)
        val counts = cachedCounts
        val artistPenalty = prefs.getBoolean("shuffle_artist", false)
        val artistCounts: Map<String, Int> = if (artistPenalty) {
            creditArtists(counts)
        } else emptyMap()
        val weights = list.associate { s ->
            var w = 1.0 / Math.pow((1 + (counts[s.mediaId] ?: 0)).toDouble(), strength)
            if (artistPenalty) {
                val ac = com.blissplayer.music.data.SongAnalyzer.splitArtists(s.artist)
                    .ifEmpty { listOf("Unknown artist") }
                    .sumOf { artistCounts[it] ?: 0 }
                if (ac > 0) w /= Math.pow((1 + ac).toDouble(), strength * 0.5)
            }
            s.mediaId to w.coerceAtLeast(1e-9)
        }
        val orderedIds = ShuffleMath.weightedOrder(
            list.map { it.mediaId }, weights, System.currentTimeMillis()
        )
        val byIdLocal = list.associateBy { it.mediaId }
        return orderedIds.mapNotNull { byIdLocal[it] }
    }

    /**
     * Gapless reorder. The playing song is never touched, only the songs
     * behind it are swapped, so there is no pause or restart.
     */
    private fun replaceTail(newUpcoming: List<Song>) {
        val p = svc?.player ?: return
        ensureQueueSync()
        val ci = p.currentMediaItemIndex.coerceAtLeast(0)
        val cur = queue.getOrNull(ci) ?: return
        val total = queue.size
        if (ci > 0) p.removeMediaItems(0, ci)
        if (total - ci - 1 > 0) p.removeMediaItems(1, total - ci)
        if (newUpcoming.isNotEmpty()) p.addMediaItems(1, newUpcoming.map(::toItem))
        queue.clear()
        queue.add(cur)
        queue.addAll(newUpcoming)
    }

    /** Shuffle means the queue itself is reordered and the screen shows it. Tap again to re-shuffle. */
    private fun shuffleQueue() {
        val p = svc?.player ?: return
        ensureQueueSync()
        if (queue.isEmpty()) {
            toast("Queue is empty. Play something first.")
            return
        }
        val curIdx = p.currentMediaItemIndex.coerceAtLeast(0)
        val cur = queue.getOrNull(curIdx) ?: return
        val rest = queue.toMutableList()
        rest.removeAll { it.mediaId == cur.mediaId }
        shuffled = true
        replaceTail(smartOrder(rest))
        refreshQueueList()
    }

    /** Adding to the queue always lands right after the current song, even when shuffled. */
    private fun playNext(song: Song) {
        val p = svc?.player ?: return
        ensureQueueSync()
        if (queue.isEmpty()) {
            playQueue(listOf(song), 0)
            return
        }
        val at = (p.currentMediaItemIndex + 1).coerceIn(0, queue.size)
        queue.add(at, song)
        p.addMediaItem(at, toItem(song))
        refreshQueueList()
    }

    private fun addToQueueEnd(song: Song) {
        val p = svc?.player ?: return
        ensureQueueSync()
        if (queue.isEmpty()) {
            playQueue(listOf(song), 0)
            return
        }
        queue.add(song)
        p.addMediaItem(toItem(song))
        refreshQueueList()
    }

    private fun cycleRepeat(): Int {
        val p = svc?.player ?: return REPEAT_MODE_OFF
        p.repeatMode = when (p.repeatMode) {
            REPEAT_MODE_OFF -> REPEAT_MODE_ALL
            REPEAT_MODE_ALL -> REPEAT_MODE_ONE
            else -> REPEAT_MODE_OFF
        }
        updateLoopBtn()
        return p.repeatMode
    }

    private fun togglePlay() {
        val p = svc?.player ?: return
        if (p.isPlaying) p.pause() else p.play()
    }

    private fun skipNext() {
        val p = svc?.player ?: return
        ensureQueueSync()
        saveResume()
        p.seekToNextMediaItem()
    }

    private fun skipPrev() {
        val p = svc?.player ?: return
        ensureQueueSync()
        // More than 3 seconds in: restart the song. Less: go to previous.
        // First song in the queue always restarts.
        if (p.currentPosition > 3000 || p.currentMediaItemIndex <= 0) {
            p.seekTo(0)
        } else {
            p.seekToPreviousMediaItem()
        }
    }

    // ---------- mini player / ticker ----------

    private fun refreshMini() {
        val p = svc?.player
        val item = p?.currentMediaItem
        val song = item?.mediaId?.toLongOrNull()?.let { byId[it] }
        miniTitle.text = song?.title ?: "Nothing playing"
        // Just the song here. Boost already shows right above in the status strip.
        miniSub.text = song?.artist ?: "Tap Play to start"
        miniPlayPause.setImageResource(if (p?.isPlaying == true) R.drawable.ic_pause else R.drawable.ic_play)
        npTitle?.text = song?.title ?: "Nothing playing"
        npArtist?.text = song?.let { "${it.artist} | ${effAlbum(it)}" } ?: ""
        npPlay?.setImageResource(if (p?.isPlaying == true) R.drawable.ic_pause else R.drawable.ic_play)
        adapter.highlightId = song?.mediaId
        if (tab != 5) adapter.notifyDataSetChanged()
        updateProgressViews()
        updateStatus()
    }

    private fun updateProgressViews() {
        val p = svc?.player
        if (p != null && p.duration > 0) {
            val pos = p.currentPosition.coerceIn(0, p.duration)
            miniProgress.max = 1000
            miniProgress.progress = ((pos * 1000) / p.duration).toInt().coerceIn(0, 1000)
            miniTime.text = fmt(pos)
            nowSegBar?.setPosition(pos, p.duration)
            nowTime?.text = "${fmt(pos)} / ${fmt(p.duration)}"
            npBar?.setPosition(pos, p.duration)
            npTime?.text = "${fmt(pos)} / ${fmt(p.duration)}"
            segEditBar?.let { bar ->
                if (segEditSong?.mediaId == p.currentMediaItem?.mediaId?.toLongOrNull()) {
                    bar.setPosition(pos, p.duration)
                    segEditTime?.text = "${fmt(pos)} / ${fmt(p.duration)}"
                } else {
                    val d = segEditSong?.durationMs ?: p.duration
                    bar.setPosition(0, d)
                    segEditTime?.text = "0:00 / ${fmt(d)}"
                }
                updateSegPlayBtn()
            }
        } else {
            miniProgress.progress = 0
            miniTime.text = "0:00"
        }
    }

    private val progressTick = object : Runnable {
        override fun run() {
            val playing = svc?.player?.isPlaying == true
            if (playing) {
                listenAccumMs += 500
                val now = System.currentTimeMillis()
                if (now - lastListenSave > 30_000) {
                    lastListenSave = now
                    persistListenTime()
                    saveResume()
                }
            }
            ensureQueueSync()
            updateProgressViews()
            ticker.postDelayed(this, 500)
        }
    }

    private fun persistListenTime() {
        if (listenAccumMs <= 0) return
        prefs.edit().putLong("stats_listen_ms", prefs.getLong("stats_listen_ms", 0) + listenAccumMs).apply()
        listenAccumMs = 0
    }

    private fun fmt(ms: Long): String {
        val s = (ms / 1000).toInt().coerceAtLeast(0)
        return "%d:%02d".format(s / 60, s % 60)
    }

    // ---------- song menu / tags / delete ----------

    private fun songMenu(song: Song) {
        val items = listOf(
            "Play next",
            "Add to queue",
            "Add to playlist",
            "Skip segments",
            "Edit details",
            "Delete song and file",
            "Details"
        )
        xpMenuDialog(song.title, items) { which ->
            when (which) {
                0 -> {
                    playNext(song)
                    toast("Playing next: ${song.title}.")
                }
                1 -> {
                    addToQueueEnd(song)
                    toast("Added to the end of the queue.")
                }
                2 -> addToPlaylistDialog(listOf(song.mediaId))
                3 -> segmentDialog(song)
                4 -> lifecycleScope.launch {
                    val tags = TagTools.readTags(this@MainActivity, song)
                    tagEditorDialog(song, tags)
                }
                5 -> deleteConfirm(song)
                6 -> AlertDialog.Builder(this)
                    .setCustomTitle(xpTitle(song.title))
                    .setMessage(
                        "Artist: ${song.artist}\nAlbum: ${effAlbum(song)}\nGenre: ${db.getGenre(song.mediaId)}\n" +
                            "Length: ${fmt(song.durationMs)}\nSize: ${song.sizeBytes / 1024} KB\n" +
                            (song.filePath ?: "")
                    )
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    private fun tagEditorDialog(song: Song, fileTags: Array<String?>) {
        val pad = dp(16)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            background = xpBtnPanel()
        }
        fun field(label: String, value: String): EditText {
            box.addView(TextView(this).apply { text = label })
            return EditText(this).apply {
                setText(value)
                box.addView(this)
            }
        }
        val eTitle = field("Title", fileTags[0] ?: song.title)
        val eArtist = field("Artist", fileTags[1] ?: song.artist)
        val eAlbum = field("Album", fileTags[2] ?: effAlbum(song))
        val eGenre = field("Genre", fileTags[3] ?: db.getGenre(song.mediaId))
        box.addView(TextView(this).apply {
            text = "Whatever you type here is what BlissPlayer and most other players will show. Empty genre means Uncategorized."
            textSize = 12f
        })
        AlertDialog.Builder(this)
            .setCustomTitle(xpTitle("Edit details"))
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val edit = TagTools.Edit(
                    eTitle.text.toString().ifBlank { song.title },
                    eArtist.text.toString().ifBlank { song.artist },
                    eAlbum.text.toString().ifBlank { song.album },
                    eGenre.text.toString().ifBlank { "Uncategorized" },
                    null, null
                )
                lifecycleScope.launch {
                    when (val r = TagTools.writeTags(this@MainActivity, song, edit)) {
                        is TagTools.WriteResult.Ok -> {
                            db.setGenre(song.mediaId, edit.genre ?: "Uncategorized")
                            db.setAlbum(song.mediaId, edit.album.ifBlank { song.album })
                            toast("Details saved.")
                            scan()
                        }
                        is TagTools.WriteResult.NeedsPermission -> {
                            pendingRetry = { tagEditorSaveRetry(song, edit) }
                            senderLauncher.launch(IntentSenderRequest.Builder(r.sender).build())
                        }
                        is TagTools.WriteResult.Failed -> toast("Could not save: ${r.msg}")
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun tagEditorSaveRetry(song: Song, edit: TagTools.Edit) {
        lifecycleScope.launch {
            when (val r = TagTools.writeTags(this@MainActivity, song, edit)) {
                is TagTools.WriteResult.Ok -> {
                    db.setGenre(song.mediaId, edit.genre ?: "Uncategorized")
                    db.setAlbum(song.mediaId, edit.album.ifBlank { song.album })
                    toast("Details saved.")
                    scan()
                }
                is TagTools.WriteResult.NeedsPermission ->
                    senderLauncher.launch(IntentSenderRequest.Builder(r.sender).build())
                is TagTools.WriteResult.Failed -> toast("Could not save: ${r.msg}")
            }
        }
    }

    /** Skip segments for one song. Red on the bar, jumped over automatically. */
    private var segEditDialog: AlertDialog? = null
    private var segEditSong: Song? = null
    private var segEditBar: SegmentSeekBar? = null
    private var segEditTime: TextView? = null
    private var segEditList: RecyclerView? = null
    private var segEditPlayBtn: Button? = null
    private var segFieldLabel: EditText? = null
    private var segFieldStart: EditText? = null
    private var segFieldEnd: EditText? = null
    private var segSelectedId: Long? = null

    /**
     * Full skip-part studio for one song. Big bar with red zones, transport to
     * play and scrub, tap a part to select it (white frame), type exact times
     * or grab them live, test with skipping paused.
     */
    private fun segmentDialog(song: Song) {
        svc?.let {
            it.previewSongId = song.mediaId
            it.refreshSegments()
        }
        segEditSong = song
        segSelectedId = null

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = xpBtnPanel()
        }
        box.addView(TextView(this).apply {
            text = "Skip parts: ${song.title}"
            textSize = 15f
            xpBold()
            xpTitleShadow()
            setTextColor(0xFFFFFFFF.toInt())
            background = ContextCompat.getDrawable(
                this@MainActivity,
                if (dark) R.drawable.xp_title_bar_dark else R.drawable.xp_title_bar
            )
            setPadding(dp(8), dp(8), dp(8), dp(8))
        })
        box.addView(TextView(this).apply {
            text = "Red parts get jumped over. Tap a part below to select it, drag the bar to scrub, type times or grab them live."
            textSize = 12f
            setTextColor(iconColor())
        })

        segEditBar = SegmentSeekBar(this).apply {
            this.dark = this@MainActivity.dark
            maxMs = song.durationMs.coerceAtLeast(1)
            segments = db.segmentsFor(song.mediaId)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(56)
            )
            onSeek = { ms ->
                if (svc?.player?.currentMediaItem?.mediaId?.toLongOrNull() == song.mediaId) {
                    svc?.player?.seekTo(ms)
                } else {
                    toast("Play the song first, then scrub.")
                }
            }
        }
        box.addView(segEditBar)

        val transport = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        segEditPlayBtn = Button(this).apply {
            text = "Play"
            background = xpBtnBg()
            setTextColor(iconColor())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { toggleSegPlay() }
        }
        transport.addView(segEditPlayBtn)
        segEditTime = TextView(this).apply {
            text = "0:00 / ${fmt(song.durationMs)}"
            textSize = 13f
            setTextColor(iconColor())
            setPadding(dp(8), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        transport.addView(segEditTime)
        box.addView(transport)

        segFieldLabel = EditText(this).apply { hint = "Name, like Intro noise" }
        segFieldStart = EditText(this).apply {
            hint = "Start minutes:seconds"
            inputType = InputType.TYPE_CLASS_DATETIME
        }
        segFieldEnd = EditText(this).apply {
            hint = "End minutes:seconds"
            inputType = InputType.TYPE_CLASS_DATETIME
        }
        box.addView(labeled("Name (optional)", segFieldLabel!!))
        val times = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        times.addView(labeled("Start", segFieldStart!!).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        times.addView(labeled("End", segFieldEnd!!).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        box.addView(times)
        val nowRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        nowRow.addView(xpBtn("Start = now").apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { grabNow(segFieldStart!!, song) }
        })
        nowRow.addView(xpBtn("End = now").apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { grabNow(segFieldEnd!!, song) }
        })
        box.addView(nowRow)
        val saveRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        saveRow.addView(xpBtn("Save part").apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { saveSegFields(song) }
        })
        saveRow.addView(xpBtn("Delete part").apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                val id = segSelectedId
                if (id == null) {
                    toast("Tap a part in the list first, then delete it.")
                    return@setOnClickListener
                }
                db.deleteSegment(id)
                segSelectedId = null
                svc?.refreshSegments()
                reloadSegEditor()
                toast("Part deleted.")
            }
        })
        box.addView(saveRow)

        segEditList = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(180)
            )
        }
        val segAdapter = LibraryAdapter(
            dark = dark,
            onTap = { row ->
                val key = (row as? Row.Group)?.key ?: return@LibraryAdapter
                if (key.startsWith("seg:")) {
                    selectSeg(song, key.removePrefix("seg:").toLongOrNull())
                }
            },
            onMenu = { row ->
                val key = (row as? Row.Group)?.key ?: return@LibraryAdapter
                if (key.startsWith("seg:")) {
                    val id = key.removePrefix("seg:").toLongOrNull() ?: return@LibraryAdapter
                    val seg = db.segmentsFor(song.mediaId).firstOrNull { it.id == id } ?: return@LibraryAdapter
                    xpMenuDialog(
                        (seg.label.ifBlank { "Segment" }) + " (${fmt(seg.startMs)} to ${fmt(seg.endMs)})",
                        listOf("Test it", "Delete")
                    ) { which ->
                        when (which) {
                            0 -> testSegment(song, seg)
                            1 -> {
                                db.deleteSegment(seg.id)
                                if (segSelectedId == seg.id) segSelectedId = null
                                svc?.refreshSegments()
                                reloadSegEditor()
                            }
                        }
                    }
                }
            }
        )
        segEditList!!.adapter = segAdapter
        box.addView(segEditList)
        reloadSegEditor()

        segEditDialog = AlertDialog.Builder(this)
            .setView(box)
            .setPositiveButton("Done", null)
            .setOnDismissListener {
                segEditDialog = null
                segEditSong = null
                segEditBar = null
                segEditTime = null
                segEditList = null
                segEditPlayBtn = null
                segSelectedId = null
                if (svc?.previewSongId == song.mediaId) svc?.previewSongId = null
            }
            .show()
        updateSegPlayBtn()
    }

    private fun labeled(label: String, v: View): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = label
                textSize = 12f
                setTextColor(iconColor())
            })
            addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

    private fun xpBtnPanel() = ContextCompat.getDrawable(
        this, if (dark) R.drawable.xp_panel_dark else R.drawable.xp_panel
    )!!

    private fun selectSeg(song: Song, id: Long?) {
        segSelectedId = id
        val seg = db.segmentsFor(song.mediaId).firstOrNull { it.id == id }
        segFieldLabel?.setText(seg?.label ?: "")
        segFieldStart?.setText(seg?.let { fmt(it.startMs) } ?: "")
        segFieldEnd?.setText(seg?.let { fmt(it.endMs) } ?: "")
        segEditBar?.selectedId = id
        segEditBar?.invalidate()
        reloadSegRowsOnly(song)
    }

    private fun reloadSegRowsOnly(song: Song) {
        val rows = db.segmentsFor(song.mediaId).map { s ->
            val mark = if (s.id == segSelectedId) "[selected] " else ""
            Row.Group(
                "seg:${s.id}",
                mark + (s.label.ifBlank { "Segment" }) + " (${fmt(s.startMs)} to ${fmt(s.endMs)})",
                "Tap to select, hold to test or delete"
            )
        }
        (segEditList?.adapter as? LibraryAdapter)?.submit(rows)
    }

    private fun reloadSegEditor() {
        val song = segEditSong ?: return
        val segs = db.segmentsFor(song.mediaId)
        segEditBar?.segments = segs
        segEditBar?.selectedId = segSelectedId
        segEditBar?.invalidate()
        reloadSegRowsOnly(song)
    }

    private fun grabNow(target: EditText, song: Song) {
        if (svc?.player?.currentMediaItem?.mediaId?.toLongOrNull() != song.mediaId) {
            toast("Play this song first, then capture the position.")
            return
        }
        target.setText(fmt(svc?.player?.currentPosition ?: 0))
    }

    private fun saveSegFields(song: Song) {
        val start = parseTimeMs(segFieldStart?.text?.toString() ?: "")
        val end = parseTimeMs(segFieldEnd?.text?.toString() ?: "")
        if (start == null || end == null || end <= start) {
            toast("End must be after start. Use minutes:seconds.")
            return
        }
        val label = segFieldLabel?.text?.toString() ?: ""
        val id = segSelectedId
        if (id == null) {
            val newId = db.addSegment(song.mediaId, start, end.coerceAtMost(song.durationMs), label)
            segSelectedId = newId
            toast("Part saved. It turns red on the bar.")
        } else {
            db.updateSegment(id, start, end.coerceAtMost(song.durationMs), label)
            toast("Part updated.")
        }
        svc?.refreshSegments()
        reloadSegEditor()
    }

    private fun toggleSegPlay() {
        val song = segEditSong ?: return
        val p = svc?.player ?: return
        if (p.currentMediaItem?.mediaId?.toLongOrNull() == song.mediaId) {
            if (p.isPlaying) p.pause() else p.play()
        } else {
            playQueue(listOf(song), 0)
        }
        updateSegPlayBtn()
    }

    private fun updateSegPlayBtn() {
        val song = segEditSong
        val playing = svc?.player?.isPlaying == true &&
            svc?.player?.currentMediaItem?.mediaId?.toLongOrNull() == song?.mediaId
        segEditPlayBtn?.text = if (playing) "Pause" else "Play"
    }

    /** Play a segment's neighborhood so its boundaries can be checked by ear. */
    private fun testSegment(song: Song, seg: Seg) {
        val p = svc?.player
        val curId = p?.currentMediaItem?.mediaId?.toLongOrNull()
        if (p == null || curId != song.mediaId) {
            playQueue(listOf(song), 0)
        }
        svc?.player?.seekTo((seg.startMs - 3000).coerceAtLeast(0))
    }

    /** Accepts minutes:seconds or plain seconds. */
    private fun parseTimeMs(t: String): Long? {
        val s = t.trim()
        if (s.isEmpty()) return null
        return try {
            if (':' in s) {
                val parts = s.split(':')
                if (parts.size != 2) return null
                parts[0].toLong() * 60000 + (parts[1].toDouble() * 1000).toLong()
            } else {
                (s.toDouble() * 1000).toLong()
            }.coerceAtLeast(0)
        } catch (e: Exception) {
            null
        }
    }

    private fun deleteConfirm(song: Song) {
        AlertDialog.Builder(this)
            .setCustomTitle(xpTitle("Delete this song?"))
            .setMessage("This removes it from the app and deletes the file from your phone. This cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    when (val r = TagTools.delete(this@MainActivity, song)) {
                        is TagTools.WriteResult.Ok -> {
                            toast("Deleted.")
                            queue.removeAll { it.mediaId == song.mediaId }
                            removeFromPlayer(song.mediaId)
                            scan()
                        }
                        is TagTools.WriteResult.NeedsPermission -> {
                            pendingRetry = { deleteRetry(song) }
                            senderLauncher.launch(IntentSenderRequest.Builder(r.sender).build())
                        }
                        is TagTools.WriteResult.Failed -> toast("Could not delete: ${r.msg}")
                    }
                }
            }
            .setNegativeButton("Keep it", null)
            .show()
    }

    private fun deleteRetry(song: Song) {
        lifecycleScope.launch {
            when (val r = TagTools.delete(this@MainActivity, song)) {
                is TagTools.WriteResult.Ok -> {
                    toast("Deleted.")
                    scan()
                }
                is TagTools.WriteResult.NeedsPermission ->
                    senderLauncher.launch(IntentSenderRequest.Builder(r.sender).build())
                is TagTools.WriteResult.Failed -> toast("Could not delete: ${r.msg}")
            }
        }
    }

    /** Remove every occurrence of a song from the live player playlist, gapless. */
    private fun removeFromPlayer(mediaId: Long) {
        val p = svc?.player ?: return
        for (i in p.mediaItemCount - 1 downTo 0) {
            if (p.getMediaItemAt(i).mediaId == mediaId.toString()) p.removeMediaItem(i)
        }
    }

    // ---------- playlists ----------

    /** Mirror playlists to JSON files in the app working dir. Best effort, never blocks UI. */
    private fun syncPlaylists() {
        lifecycleScope.launch(Dispatchers.IO) {
            com.blissplayer.music.data.PlaylistJson.exportAll(this@MainActivity, db)
        }
    }

    private fun createPlaylistDialog(firstSongs: List<Long>) {
        val input = EditText(this).apply { hint = "Example: Gym mix" }
        AlertDialog.Builder(this)
            .setCustomTitle(xpTitle("New playlist"))
            .setView(pad(input))
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().ifBlank { "Playlist" }
                val id = db.createPlaylist(name)
                if (firstSongs.isNotEmpty()) db.addToPlaylist(id, firstSongs)
                syncPlaylists()
                // Land straight inside the new playlist.
                tab = 4
                showTab(4)
                drillPlaylist = id
                drillPlaylistName = name
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addToPlaylistDialog(ids: List<Long>) {
        val pls = db.playlists().toMutableList()
        val names = (pls.map { it.second } + "New playlist").toList()
        xpMenuDialog("Add to playlist", names) { which ->
            if (which == pls.size) createPlaylistDialog(ids)
            else {
                db.addToPlaylist(pls[which].first, ids)
                syncPlaylists()
                if (tab == 4) render()
                else toast("Added ${ids.size} songs to ${pls[which].second}.")
            }
        }
    }

    /** Add whole artists, whole albums, or individual songs to a playlist. */
    private fun addSongsDialog(playlistId: Long, name: String) {
        val filter = EditText(this).apply { hint = "Type to filter" }
        val checked = mutableSetOf<String>()
        val listView = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(380)
            )
        }
        val pickAdapter = PickAdapter(dark = dark, checked = checked)
        listView.adapter = pickAdapter

        fun buildRows(q: String): List<PickRow> {
            val out = mutableListOf<PickRow>()
            val artists = songs.flatMap { com.blissplayer.music.data.SongAnalyzer.splitArtists(it.artist) }
                .distinct()
                .sortedWith(String.CASE_INSENSITIVE_ORDER)
                .filter { q.isBlank() || it.contains(q, ignoreCase = true) }
            if (artists.isNotEmpty()) {
                out.add(PickRow.Header("Artists"))
                artists.forEach { a ->
                    val n = songs.count { a in com.blissplayer.music.data.SongAnalyzer.splitArtists(it.artist) }
                    out.add(PickRow.Item("a:$a", a, "$n songs"))
                }
            }
            val albums = songs.map { effAlbum(it) }.distinct()
                .sortedWith(String.CASE_INSENSITIVE_ORDER)
                .filter { q.isBlank() || it.contains(q, ignoreCase = true) }
            if (albums.isNotEmpty()) {
                out.add(PickRow.Header("Albums"))
                albums.forEach { a ->
                    val n = songs.count { it.album == a }
                    out.add(PickRow.Item("b:$a", a, "$n songs"))
                }
            }
            val solo = songs.filter { q.isBlank() || it.title.contains(q, ignoreCase = true) }
            if (solo.isNotEmpty()) {
                out.add(PickRow.Header("Songs"))
                solo.forEach { s -> out.add(PickRow.Item("s:${s.mediaId}", s.title, "${s.artist} | ${s.album}")) }
            }
            return out
        }

        filter.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                pickAdapter.submit(buildRows(s?.toString() ?: ""))
            }
        })
        pickAdapter.submit(buildRows(""))

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            addView(filter, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(listView)
        }
        AlertDialog.Builder(this)
            .setCustomTitle(xpTitle("Add to $name"))
            .setView(box)
            .setPositiveButton("Add selected") { _, _ ->
                val ids = mutableListOf<Long>()
                checked.forEach { key ->
                    when {
                        key.startsWith("a:") -> ids.addAll(songs.filter { key.removePrefix("a:") in com.blissplayer.music.data.SongAnalyzer.splitArtists(it.artist) }.map { it.mediaId })
                        key.startsWith("b:") -> ids.addAll(songs.filter { effAlbum(it) == key.removePrefix("b:") }.map { it.mediaId })
                        key.startsWith("s:") -> key.removePrefix("s:").toLongOrNull()?.let { ids.add(it) }
                    }
                }
                if (ids.isNotEmpty()) {
                    db.addToPlaylist(playlistId, ids.distinct())
                    syncPlaylists()
                    toast("Added ${ids.distinct().size} songs.")
                    if (drillPlaylist == playlistId) render()
                } else {
                    toast("Nothing selected.")
                }
            }
            .setNegativeButton("Done", null)
            .show()
    }

    private fun playlistMenu(id: Long, name: String) {
        xpMenuDialog(name, listOf("Play this playlist", "Shuffle play", "Rename", "Delete playlist")) { which ->
            when (which) {
                0 -> {
                    val listSongs = db.songsIn(id).mapNotNull { byId[it] }
                    if (listSongs.isEmpty()) toast("Playlist is empty. Its songs may have moved.")
                    else playQueue(listSongs, 0)
                }
                1 -> {
                    val listSongs = db.songsIn(id).mapNotNull { byId[it] }
                    if (listSongs.isEmpty()) {
                        toast("Playlist is empty. Its songs may have moved.")
                        return@xpMenuDialog
                    }
                    playQueue(smartOrder(listSongs), 0)
                    shuffled = true
                }
                2 -> {
                    val input = EditText(this).apply { setText(name) }
                    AlertDialog.Builder(this).setCustomTitle(xpTitle("Rename"))
                        .setView(pad(input))
                        .setPositiveButton("Save") { _, _ ->
                            db.renamePlaylist(id, input.text.toString().ifBlank { name })
                            syncPlaylists()
                            render()
                        }
                        .setNegativeButton("Cancel", null).show()
                }
                3 -> AlertDialog.Builder(this)
                    .setCustomTitle(xpTitle("Delete $name?"))
                    .setMessage("The playlist goes away. Your songs stay.")
                    .setPositiveButton("Delete") { _, _ ->
                        db.deletePlaylist(id)
                        syncPlaylists()
                        render()
                    }
                    .setNegativeButton("Keep", null).show()
            }
        }
    }

    private fun genreMenu(genre: String) {
        val inG = songs.filter { (genreMap[it.mediaId] ?: "Uncategorized") == genre }
        xpMenuDialog(genre, listOf("Play all", "Shuffle play", "Add all to queue", "Add all to playlist")) { which ->
            when (which) {
                0 -> playQueue(inG, 0)
                1 -> {
                    if (inG.isEmpty()) {
                        toast("Nothing here yet.")
                        return@xpMenuDialog
                    }
                    playQueue(smartOrder(inG), 0)
                    shuffled = true
                }
                2 -> {
                    if (inG.isEmpty()) toast("Nothing here yet.")
                    else {
                        inG.forEach { addToQueueEnd(it) }
                        toast("Added ${inG.size} songs to the queue.")
                    }
                }
                3 -> addToPlaylistDialog(inG.map { it.mediaId })
            }
        }
    }

    private fun artistMenu(artist: String) {
        val inA = songs.filter { artist in com.blissplayer.music.data.SongAnalyzer.splitArtists(it.artist) }
        xpMenuDialog(artist, listOf("Play all", "Add all to queue", "Add all to playlist")) { which ->
                when (which) {
                    0 -> playQueue(inA, 0)
                    1 -> {
                        if (inA.isEmpty()) toast("Nothing here yet.")
                        else {
                            inA.forEach { addToQueueEnd(it) }
                            toast("Added ${inA.size} songs to the queue.")
                        }
                    }
                    2 -> addToPlaylistDialog(inA.map { it.mediaId })
                }
        }
    }

    private fun albumMenu(album: String) {
        val inA = songs.filter { effAlbum(it) == album }
        xpMenuDialog(album, listOf("Play album", "Add all to queue", "Add all to playlist")) { which ->
                when (which) {
                    0 -> playQueue(inA, 0)
                    1 -> {
                        if (inA.isEmpty()) toast("Nothing here yet.")
                        else {
                            inA.forEach { addToQueueEnd(it) }
                            toast("Added ${inA.size} songs to the queue.")
                        }
                    }
                    2 -> addToPlaylistDialog(inA.map { it.mediaId })
                }
        }
    }

    // ---------- Now Playing (controls + true-order queue) ----------

    private var queueDialog: AlertDialog? = null
    private var queueAdapter: LibraryAdapter? = null
    private var queueListView: RecyclerView? = null
    private var nowTitleView: TextView? = null
    private var nowArtistView: TextView? = null
    private var nowSegBar: SegmentSeekBar? = null
    private var landQueueAdapter: LibraryAdapter? = null
    private var npBar: SegmentSeekBar? = null
    private var npTime: TextView? = null
    private var npTitle: TextView? = null
    private var npArtist: TextView? = null
    private var npPlay: ImageButton? = null
    private var npLoop: Button? = null
    private var nowTime: TextView? = null
    private var nowPlayBtn: Button? = null
    private var loopBtn: Button? = null
    private var shuffleBtn: Button? = null

    private fun currentSong(): Song? =
        svc?.player?.currentMediaItem?.mediaId?.toLongOrNull()?.let { byId[it] }

    /** Queue adapter shared by the Now Playing dialog and the landscape panel. */
    private fun makeQueueAdapter(): LibraryAdapter {
        val p = svc?.player
        return LibraryAdapter(
            dark = dark,
            rows = queue.map { Row.SongRow(it) },
            highlightId = p?.currentMediaItem?.mediaId?.toLongOrNull(),
            onTap = { r ->
                ensureQueueSync()
                val idx = queue.indexOfFirst { it.mediaId == (r as Row.SongRow).song.mediaId }
                if (idx >= 0) svc?.player?.seekTo(idx, 0)
            },
            onMenu = { r ->
                val s = (r as Row.SongRow).song
                queue.removeAll { it.mediaId == s.mediaId }
                removeFromPlayer(s.mediaId)
                refreshQueueList()
            }
        )
    }

    private fun attachQueueTouch(rv: RecyclerView, getAdapter: () -> LibraryAdapter?) {
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        ) {
            override fun onMove(rv2: RecyclerView, h: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder): Boolean {
                val a = h.bindingAdapterPosition
                val b = t.bindingAdapterPosition
                java.util.Collections.swap(queue, a, b)
                getAdapter()?.notifyItemMoved(a, b)
                if (a != b) svc?.player?.moveMediaItem(a, b)
                return true
            }

            override fun onSwiped(h: RecyclerView.ViewHolder, dir: Int) {
                val pos = h.bindingAdapterPosition
                val s = queue.getOrNull(pos) ?: return
                queue.removeAt(pos)
                svc?.player?.let { p ->
                    if (pos < p.mediaItemCount) p.removeMediaItem(pos)
                }
                refreshQueueList()
            }
        }).attachToRecyclerView(rv)
    }

    private fun openNowPlaying() {
        ensureQueueSync()
        val p = svc?.player
        if (p == null || queue.isEmpty()) {
            toast("Nothing in the queue. Tap a song or Play all music.")
            return
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val song = p.currentMediaItem?.mediaId?.toLongOrNull()?.let { byId[it] }
        nowTitleView = TextView(this).apply {
            text = song?.title ?: "Now playing"
            textSize = 18f
            xpBold()
            marquee()
        }
        box.addView(nowTitleView)
        nowArtistView = TextView(this).apply {
            text = song?.let { "${it.artist} | ${it.album}" } ?: ""
            textSize = 13f
            marquee()
        }
        box.addView(nowArtistView)
        nowSegBar = SegmentSeekBar(this).apply {
            dark = this@MainActivity.dark
            maxMs = p.duration.coerceAtLeast(1)
            posMs = p.currentPosition.coerceIn(0, maxMs)
            segments = song?.let { db.segmentsFor(it.mediaId) } ?: emptyList()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            onSeek = { ms -> p.seekTo(ms) }
        }
        box.addView(nowSegBar)
        nowTime = TextView(this).apply { text = "${fmt(p.currentPosition)} / ${fmt(p.duration)}" }
        box.addView(nowTime)

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val bPrev = ImageButton(this).apply {
            setImageResource(R.drawable.ic_prev)
            background = xpBtnBg()
            setColorFilter(iconColor())
            contentDescription = "Previous song"
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { skipPrev() }
        }
        nowPlayBtn = Button(this).apply {
            text = if (p.isPlaying) "Pause" else "Play"
            background = xpBtnBg()
            setTextColor(iconColor())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f)
            setOnClickListener { togglePlay() }
        }
        setPlayBtnIcon(p.isPlaying)
        val bNext = ImageButton(this).apply {
            setImageResource(R.drawable.ic_next)
            background = xpBtnBg()
            setColorFilter(iconColor())
            contentDescription = "Next song"
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { skipNext() }
        }
        row1.addView(bPrev)
        row1.addView(nowPlayBtn)
        row1.addView(bNext)
        box.addView(row1)

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        loopBtn = Button(this).apply {
            background = xpBtnBg()
            setTextColor(iconColor())
            textSize = 12f
            minimumHeight = dp(24)
            setPadding(dp(4), dp(1), dp(4), dp(1))
            setOnClickListener { cycleRepeat() }
        }
        shuffleBtn = Button(this).apply {
            background = xpBtnBg()
            setTextColor(iconColor())
            text = getString(R.string.shuffle_off)
            textSize = 12f
            minimumHeight = dp(24)
            setPadding(dp(4), dp(1), dp(4), dp(1))
            setOnClickListener { shuffleQueue() }
        }
        updateLoopBtn()
        listOf(loopBtn!!, shuffleBtn!!).forEach {
            it.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(4)
            }
            row2.addView(it)
        }
        box.addView(row2)

        box.addView(TextView(this).apply {
            text = "Up next, in order. Tap to jump, swipe to remove, drag to reorder."
            textSize = 12f
        })
        val qv = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(220)
            )
        }
        queueListView = qv
        queueAdapter = makeQueueAdapter()
        qv.adapter = queueAdapter
        attachQueueTouch(qv) { queueAdapter }
        box.addView(qv)

        // One slim row: Save queue | Segment Editor | Close (bottom right).
        // Small buttons on a single line so the queue keeps its space.
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
        }
        val saveBtn = Button(this).apply {
            text = "Save queue"
            textSize = 11f
            minimumHeight = dp(24)
            setPadding(dp(6), dp(1), dp(6), dp(1))
            background = xpBtnBg()
            setTextColor(iconColor())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) }
            setOnClickListener {
                if (queue.isEmpty()) {
                    toast("Queue is empty.")
                    return@setOnClickListener
                }
                createPlaylistDialog(queue.map { it.mediaId })
            }
        }
        val segEditBtn = Button(this).apply {
            text = "Segments"
            textSize = 11f
            minimumHeight = dp(24)
            setPadding(dp(6), dp(1), dp(6), dp(1))
            background = xpBtnBg()
            setTextColor(iconColor())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) }
            setOnClickListener {
                val cur = currentSong()
                if (cur == null) {
                    toast("Play something first.")
                    return@setOnClickListener
                }
                segmentDialog(cur)
            }
        }
        val closeBtn = Button(this).apply {
            text = "Close"
            textSize = 11f
            minimumHeight = dp(24)
            setPadding(dp(6), dp(1), dp(6), dp(1))
            background = xpBtnBg()
            setTextColor(iconColor())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { queueDialog?.dismiss() }
        }
        actionRow.addView(saveBtn)
        actionRow.addView(segEditBtn)
        actionRow.addView(closeBtn)
        box.addView(actionRow)

        queueDialog = AlertDialog.Builder(this)
            .setCustomTitle(xpTitle(getString(R.string.queue_title)))
            .setView(box)
            .setOnDismissListener {
                queueDialog = null
                queueListView = null
                nowSegBar = null
                nowTime = null
                nowPlayBtn = null
                nowTitleView = null
                nowArtistView = null
            }
            .show()
        scrollQueueToCurrent()
    }

    /** Queue always opens pinned to the current song at the top. */
    private fun scrollQueueToCurrent() {
        val p = svc?.player ?: return
        val idx = p.currentMediaItemIndex
        if (idx < 0) return
        queueListView?.post {
            (queueListView?.layoutManager as? LinearLayoutManager)
                ?.scrollToPositionWithOffset(idx, 0)
        }
        findViewById<RecyclerView>(R.id.npQueue)?.let { npqv ->
            npqv.post {
                (npqv.layoutManager as? LinearLayoutManager)
                    ?.scrollToPositionWithOffset(idx, 0)
            }
        }
    }

    private fun refreshQueueList() {
        val p = svc?.player
        val cur = p?.currentMediaItem?.mediaId?.toLongOrNull()
        val albums = songs.associate { it.mediaId to effAlbum(it) }
        queueAdapter?.highlightId = cur
        queueAdapter?.albumLines = albums
        queueAdapter?.submit(queue.map { Row.SongRow(it) })
        landQueueAdapter?.highlightId = cur
        landQueueAdapter?.albumLines = albums
        landQueueAdapter?.submit(queue.map { Row.SongRow(it) })
        scrollQueueToCurrent()
    }

    private fun updateLoopBtn() {
        val p = svc?.player ?: return
        val txt = when (p.repeatMode) {
            REPEAT_MODE_ALL -> getString(R.string.loop_all)
            REPEAT_MODE_ONE -> getString(R.string.loop_one)
            else -> getString(R.string.loop_off)
        }
        loopBtn?.text = txt
        npLoop?.text = txt
    }

    private fun setPlayBtnIcon(isPlaying: Boolean) {
        val b = nowPlayBtn ?: return
        b.text = if (isPlaying) "Pause" else "Play"
        val d = ContextCompat.getDrawable(this, if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)!!.mutate()
        d.setTint(iconColor())
        b.setCompoundDrawablesWithIntrinsicBounds(d, null, null, null)
        b.compoundDrawablePadding = dp(8)
    }

    // ---------- More pane (settings, boost, equalize, help) ----------

    private var boostSeek: SeekBar? = null
    private var boostLabel: TextView? = null
    private var deviceLabel: TextView? = null
    private var deviceToggle: Button? = null
    private var holdToggle: Button? = null
    private var smartToggle: Button? = null
    private var shuffleSeek: SeekBar? = null
    private var shuffleStrengthLabel: TextView? = null

    private fun mixerDialog() {
        val service = svc ?: return toast("Playback is still starting. Try again in a moment.")
        val tone = service.tone
        val bands = tone.bands()
        if (bands.isEmpty()) {
            AlertDialog.Builder(this).setCustomTitle(xpTitle("Frequency mixer"))
                .setMessage(tone.problem ?: "Play a song first so Android can open its audio session, then open the mixer again.")
                .setPositiveButton("OK", null).show()
            return
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val toggle = xpBtn("")
        val headroom = xpBtn("")
        val warning = TextView(this).apply {
            text = "Positive gain can clip or distort. Headroom lowers the overall level to make room for boosted frequencies; it is not a limiter or a hearing-safety guarantee. Start with low phone volume. Turning headroom off and adding preamp can get very loud."
            textSize = 12f
            setTextColor(if (dark) 0xFFFFCC88.toInt() else 0xFF8B3000.toInt())
        }
        fun refresh() {
            toggle.text = "Mixer: ${if (tone.enabled) "On" else "Bypassed (original sound)"}"
            headroom.text = "Automatic headroom: ${if (tone.headroom) "On" else "Off (clipping risk)"}"
            service.applyGainFor(service.player.currentMediaItem)
        }
        toggle.setOnClickListener { tone.setEnabled(!tone.enabled); refresh() }
        headroom.setOnClickListener { tone.setHeadroom(!tone.headroom); refresh() }
        box.addView(toggle)
        box.addView(warning)
        bands.forEach { band ->
            val label = TextView(this).apply { textSize = 13f; setTextColor(iconColor()) }
            val frequency = if (band.hz >= 1000) "${"%.1f".format(band.hz / 1000f)} kHz" else "${band.hz} Hz"
            fun labelLevel(value: Int) { label.text = "$frequency   ${"%+.1f".format(value / 100f)} dB" }
            labelLevel(band.level)
            box.addView(label)
            box.addView(SeekBar(this).apply {
                max = (band.max - band.min) / 50
                progress = (band.level - band.min) / 50
                progressDrawable = xpProgress()
                thumb = xpThumb()
                contentDescription = "Frequency $frequency"
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, value: Int, user: Boolean) {
                        if (!user) return
                        val level = band.min + value * 50
                        tone.setBand(band.index, level)
                        tone.setEnabled(true)
                        labelLevel(tone.bands().firstOrNull { it.index == band.index }?.level ?: level)
                        refresh()
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {
                        tone.problem?.let { toast(it) }
                    }
                })
            })
        }
        val preampLabel = TextView(this).apply { text = "Preamp: ${tone.preampDb} dB"; textSize = 13f; setTextColor(iconColor()) }
        box.addView(preampLabel)
        box.addView(SeekBar(this).apply {
            max = 36
            progress = tone.preampDb + 24
            progressDrawable = xpProgress()
            thumb = xpThumb()
            contentDescription = "Preamp"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, value: Int, user: Boolean) {
                    if (!user) return
                    tone.setPreamp(value - 24)
                    tone.setEnabled(true)
                    preampLabel.text = "Preamp: ${tone.preampDb} dB"
                    refresh()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        })
        box.addView(headroom)
        refresh()
        val dialog = AlertDialog.Builder(this).setCustomTitle(xpTitle("Live frequency mixer"))
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Close", null)
            .setNeutralButton("Reset to flat") { _, _ ->
                tone.reset()
                service.applyGainFor(service.player.currentMediaItem)
                mixerDialog()
            }.create()
        dialog.show()
    }

    private fun buildMorePane() {
        val scrollPosition = morePane.scrollY
        moreBox.removeAllViews()
        moreBox.addView(section("Your music"))
        moreBox.addView(xpBtn("Scan for music").apply {
            setOnClickListener { scan(fromTap = true) }
        })
        moreBox.addView(xpBtn("Choose a folder (optional)").apply {
            setOnClickListener {
                runCatching { folderLauncher.launch(null) }
                    .onFailure { toast("No file picker on this device. Scan-all stays on.") }
            }
        })
        // Folder checklist: every folder with music, tick to include.
        // System sounds are off by default so ringtones never become songs.
        val folders = prefs.getStringSet("folders", emptySet())!!.toMutableSet()
        val detected = songs.mapNotNull { it.filePath?.substringBeforeLast('/') }.distinct().sorted()
        if (detected.isNotEmpty()) {
            moreBox.addView(TextView(this).apply {
                text = "Tick the folders to play from. Unticked folders are skipped. System sounds stay off."
                textSize = 12f
            })
            val sysKeys = listOf("/Ringtones", "/Alarms", "/Notifications", "/Android/", "/MIUI/", "/DCIM/")
            detected.forEach { folder ->
                val isSys = sysKeys.any { folder.contains(it, ignoreCase = true) }
                // First run: system folders start unticked even though prefs are empty (scan-all).
                val checked = if (folders.isEmpty()) !isSys else folders.any { folder.startsWith(it) || it.startsWith(folder) }
                val cb = android.widget.CheckBox(this).apply {
                    text = folder
                    textSize = 12f
                    isChecked = checked
                    setTextColor(iconColor())
                    buttonTintList = android.content.res.ColorStateList.valueOf(0xFF0058E6.toInt())
                    setOnCheckedChangeListener { _, on ->
                        val cur = prefs.getStringSet("folders", emptySet())!!.toMutableSet()
                        if (on) {
                            // Enabling: if we were in scan-all, start from detected non-sys set.
                            if (cur.isEmpty()) {
                                cur.addAll(detected.filter { d -> !sysKeys.any { d.contains(it, ignoreCase = true) } })
                            }
                            cur.add(folder)
                        } else {
                            if (cur.isEmpty()) {
                                cur.addAll(detected.filter { d -> !sysKeys.any { d.contains(it, ignoreCase = true) } })
                            }
                            cur.removeAll { it == folder || folder.startsWith(it) || it.startsWith(folder) }
                        }
                        prefs.edit().putStringSet("folders", cur).apply()
                        scan(fromTap = true)
                    }
                }
                moreBox.addView(cb)
            }
            moreBox.addView(xpBtn("Add a folder path by hand").apply {
                setOnClickListener { manualFolderDialog() }
            })
            moreBox.addView(xpBtn("Clear folders (scan everything)").apply {
                setOnClickListener {
                    prefs.edit().putStringSet("folders", emptySet()).apply()
                    buildMorePane()
                    scan(fromTap = true)
                }
            })
        } else {
            moreBox.addView(TextView(this).apply {
                text = if (folders.isEmpty())
                    "Looks everywhere on this phone automatically (mp3, m4a, opus, ogg, flac, wav). System ringtones and alarms stay out."
                else "Only these folders:\n" + folders.joinToString("\n")
                textSize = 12f
            })
            moreBox.addView(xpBtn("Add a folder path by hand").apply {
                setOnClickListener { manualFolderDialog() }
            })
            if (folders.isNotEmpty()) moreBox.addView(xpBtn("Clear folders (scan everything)").apply {
                setOnClickListener {
                    prefs.edit().putStringSet("folders", emptySet()).apply()
                    buildMorePane()
                    scan(fromTap = true)
                }
            })
        }

        moreBox.addView(section("Playback controls"))
        moreBox.addView(TextView(this).apply {
            text = if (hasNotifPermission())
                "On. While music plays, the song, position bar, and skip buttons sit in the notification shade and on the lockscreen."
            else
                "Off. The phone is blocking notifications, so no shade or lockscreen controls. Tap below to allow them, then come back."
            textSize = 12f
        })
        if (!hasNotifPermission()) moreBox.addView(xpBtn("Allow notifications").apply {
            setOnClickListener {
                val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:$packageName"))
                runCatching { startActivity(i) }
            }
        })
        // Music focus: calls, all interruptions, or never.
        val respectFocus = prefs.getString("focus_mode", "CallsOnly") != "Off"
        moreBox.addView(xpBtn("Audio interruptions: ${if (respectFocus) "Automatic" else "Ignore"}").apply {
            setOnClickListener {
                xpMenuDialog("Audio interruptions", listOf("Automatic (recommended)", "Ignore focus requests (experimental)")) { which ->
                    val v = if (which == 0) "CallsOnly" else "Off"
                    prefs.edit().putString("focus_mode", v).apply()
                    svc?.configureAudioFocus()
                    buildMorePane()
                }
            }
        })
        moreBox.addView(TextView(this).apply {
            text = "Automatic ducks short notification sounds and resumes after temporary interruptions, including calls. Starting other media can pause playback. Ignore may play over other apps; Android can still enforce interruptions."
            textSize = 12f
        })
        val bgAllowed = isBackgroundAllowed()
        moreBox.addView(xpBtn("Background play: ${if (bgAllowed) "Allowed" else "Restricted"}").apply {
            setOnClickListener {
                if (isBackgroundAllowed()) {
                    toast("Background play is already allowed.")
                    return@setOnClickListener
                }
                openBackgroundSettings()
            }
        })
        moreBox.addView(TextView(this).apply {
            text = "Allow unrestricted battery use for screen-off playback. Calls, disconnected headphones and other apps taking audio focus can still pause music."
            textSize = 12f
        })

        moreBox.addView(section("Boost"))
        deviceLabel = TextView(this).apply { textSize = 13f }
        moreBox.addView(deviceLabel)
        boostLabel = TextView(this).apply {
            textSize = 14f
            setOnClickListener { boostEditDialog() }
        }
        moreBox.addView(boostLabel)
        boostSeek = SeekBar(this).apply {
            max = 499 // 1 percent to 500 percent
            progress = ((vol.boost * 100).roundToInt() - 1).coerceIn(0, 499)
            progressDrawable = xpProgress()
            thumb = xpThumb()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) {
                    if (fromUser) vol.setBoost((v + 1) / 100f)
                }

                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        moreBox.addView(boostSeek)
        moreBox.addView(TextView(this).apply {
            text = "Tap a preset to use it, hold to edit or delete. Tap the percent to enter 1 to 2000%. Boost can clip or distort at any setting above 100%, depending on the recording. Loud sound can damage hearing and speakers; start low. Phone hardware sets the final ceiling."
            textSize = 12f
            xpBold()
        })
        renderPresets()
        moreBox.addView(xpBtn("Add preset").apply {
            setOnClickListener { presetAddDialog() }
        })
        moreBox.addView(TextView(this).apply {
            text = "Auto device boost. Each output keeps its own boost and comes back on reconnect."
            textSize = 12f
            xpBold()
        })
        deviceToggle = xpBtn("").apply {
            setOnClickListener {
                val now = !prefs.getBoolean(VolumeManager.KEY_AUTO_DEVICE, true)
                prefs.edit().putBoolean(VolumeManager.KEY_AUTO_DEVICE, now).apply()
                vol.refreshRoute()
                if (now) vol.setBoost(db.getBoost(vol.currentDevice, 1.0).toFloat())
                updateDeviceToggle()
            }
        }
        moreBox.addView(deviceToggle)
        moreBox.addView(TextView(this).apply {
            text = "With auto device on, each Bluetooth gadget keeps its own boost and it comes back when you reconnect. Off leaves the boost where it is."
            textSize = 12f
        })
        renderDeviceList()
        moreBox.addView(xpBtn("Add a device by hand").apply {
            setOnClickListener { deviceAddDialog() }
        })
        moreBox.addView(TextView(this).apply {
            text = "Tap a device to set its boost and autoplay. When it connects, that boost comes back, and autoplay starts the music where it left off."
            textSize = 12f
        })

        moreBox.addView(section("Live sound mixer"))
        moreBox.addView(TextView(this).apply {
            text = "Bass, midrange, treble and preamp for every song. Flat and bypassed by default; changes are live and never rewrite files."
            textSize = 12f
        })
        moreBox.addView(xpBtn("Open frequency mixer").apply { setOnClickListener { mixerDialog() } })

        moreBox.addView(section("Phone volume guard"))
        moreBox.addView(TextView(this).apply {
            text = "If the phone keeps turning the volume down by itself, turn this on to put it back while music plays. You can also fix it for good in the phone sound settings below. If this fights you when you change the volume yourself, turn it back off."
            textSize = 12f
        })
        holdToggle = xpBtn("").apply {
            setOnClickListener {
                val now = !prefs.getBoolean(SafeVolumeGuard.KEY_HOLD, false)
                prefs.edit().putBoolean(SafeVolumeGuard.KEY_HOLD, now).apply()
                updateHoldToggle()
            }
        }
        moreBox.addView(holdToggle)
        moreBox.addView(xpBtn("Open sound settings").apply {
            setOnClickListener {
                runCatching { startActivity(Intent(android.provider.Settings.ACTION_SOUND_SETTINGS)) }
            }
        })
        updateHoldToggle()
        updateDeviceToggle()
        updateBoostViews()

        moreBox.addView(section("Loudness"))
        moreBox.addView(TextView(this).apply {
            text = "Songs from different sources are all over the place volume wise. Press the button once and BlissPlayer samples every song, finds the average, and turns the quiet ones up and the loud ones down. Nothing is rewritten, and Undo is one tap. Songs already done are skipped unless they changed."
            textSize = 12f
        })
        moreBox.addView(TextView(this).apply {
            val gains = db.allGains()
            val last = prefs.getLong("eq_last", 0)
            text = if (last == 0L) {
                "Nothing equalized yet."
            } else {
                val d = java.text.SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
                    .format(java.util.Date(last))
                val covered = songs.count { it.mediaId in gains }
                "Equalized. Last run $d: ${prefs.getInt("eq_measured", 0)} songs set " +
                    "(up ${prefs.getInt("eq_up", 0)}, down ${prefs.getInt("eq_down", 0)}). " +
                    "$covered of ${songs.size} songs carry loudness data now."
            }
            textSize = 12f
            xpBold()
        })
        moreBox.addView(xpBtn(getString(R.string.equalize_all)).apply {
            setOnClickListener { equalizeConfirm() }
        })
        moreBox.addView(xpBtn(getString(R.string.equalize_undo)).apply {
            setOnClickListener {
                db.clearGains()
                svc?.refreshGains()
                toast("Equalize undone. Back to original volumes.")
            }
        })

        moreBox.addView(section("Shuffle"))
        moreBox.addView(TextView(this).apply {
            text = "Smart Shuffle plays songs you play less more often, so common songs stop repeating. Turn it off for plain random. The slider sets how harsh that is: gentle lets common songs come back sooner, harsh keeps them away longer."
            textSize = 12f
        })
        smartToggle = xpBtn("").apply {
            setOnClickListener {
                val now = !prefs.getBoolean("shuffle_smart", true)
                prefs.edit().putBoolean("shuffle_smart", now).apply()
                updateShuffleViews()
            }
        }
        moreBox.addView(smartToggle)
        shuffleStrengthLabel = TextView(this).apply { textSize = 13f }
        moreBox.addView(shuffleStrengthLabel)
        shuffleSeek = SeekBar(this).apply {
            max = 300
            progress = (prefs.getFloat("shuffle_strength", 1.0f) * 100).roundToInt().coerceIn(0, 300)
            progressDrawable = xpProgress()
            thumb = xpThumb()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) {
                    if (fromUser) {
                        prefs.edit().putFloat("shuffle_strength", v / 100f).apply()
                        updateShuffleViews()
                    }
                }

                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        moreBox.addView(shuffleSeek)
        val artistPenalty = prefs.getBoolean("shuffle_artist", false)
        moreBox.addView(xpBtn("Rare artists too: ${if (artistPenalty) "On" else "Off"}").apply {
            setOnClickListener {
                prefs.edit().putBoolean("shuffle_artist", !artistPenalty).apply()
                buildMorePane()
                render()
            }
        })
        moreBox.addView(TextView(this).apply {
            text = "When on, artists you hear a lot get picked less too. Off by default."
            textSize = 12f
        })
        val loopShuffle = prefs.getBoolean("loop_shuffle", true)
        moreBox.addView(xpBtn("Reshuffle on loop: ${if (loopShuffle) "On" else "Off"}").apply {
            setOnClickListener {
                prefs.edit().putBoolean("loop_shuffle", !loopShuffle).apply()
                buildMorePane()
            }
        })
        moreBox.addView(TextView(this).apply {
            text = "When on, Loop All starts the next lap in a fresh shuffled order."
            textSize = 12f
        })
        moreBox.addView(xpBtn("Reset shuffle cycle").apply {
            setOnClickListener {
                db.resetPlays()
                refreshCountCache()
                buildMorePane()
                render()
                toast("Shuffle cycle reset. Lifetime totals kept.")
            }
        })
        moreBox.addView(TextView(this).apply {
            text = "Starts the Smart Shuffle cycle over. Lifetime totals stay."
            textSize = 12f
        })
        updateShuffleViews()

        moreBox.addView(section("Look and feel"))
        val themeBtn = xpBtn(if (dark) "Light XP mode" else "Dark XP mode")
        themeBtn.setOnClickListener {
            prefs.edit().putBoolean("dark", !dark).apply()
            // Full recreate so every widget (including system-styled ones like
            // text fields) picks up the theme. Tab, drill, queue, search, and
            // playback all restore, nothing restarts from the beginning.
            recreate()
        }
        moreBox.addView(themeBtn)
        val rotBtn = xpBtn("Rotation: ${prefs.getString("rotation", "Auto")}")
        rotBtn.setOnClickListener {
            val next = when (prefs.getString("rotation", "Auto")) {
                "Auto" -> "Portrait"
                "Portrait" -> "Landscape"
                else -> "Auto"
            }
            prefs.edit().putString("rotation", next).apply()
            rotBtn.text = "Rotation: $next"
            applyRotation()
        }
        moreBox.addView(rotBtn)
        moreBox.addView(TextView(this).apply {
            text = "Auto follows the phone. Portrait and Landscape lock it. Landscape shows two panels: lists on the left, the song and queue on the right."
            textSize = 12f
        })
        val notifBtn = xpBtn("Notification style: ${prefs.getString("notif_style", "Full")}")
        notifBtn.setOnClickListener {
            val next = if (prefs.getString("notif_style", "Full") == "Full") "Simple" else "Full"
            prefs.edit().putString("notif_style", next).apply()
            notifBtn.text = "Notification style: $next"
            svc?.repostNotif()
        }
        moreBox.addView(notifBtn)
        moreBox.addView(TextView(this).apply {
            text = "Full shows the artwork and progress bar. Simple is plain text and buttons."
            textSize = 12f
        })
        val showTotal = prefs.getBoolean("show_total", false)
        val showCycle = prefs.getBoolean("show_cycle", false)
        val showChance = prefs.getBoolean("show_chance", false)
        moreBox.addView(xpBtn("Show total plays: ${if (showTotal) "On" else "Off"}").apply {
            setOnClickListener {
                prefs.edit().putBoolean("show_total", !showTotal).apply()
                buildMorePane()
                render()
            }
        })
        moreBox.addView(xpBtn("Show cycle plays: ${if (showCycle) "On" else "Off"}").apply {
            setOnClickListener {
                prefs.edit().putBoolean("show_cycle", !showCycle).apply()
                buildMorePane()
                render()
            }
        })
        moreBox.addView(xpBtn("Show next chance: ${if (showChance) "On" else "Off"}").apply {
            setOnClickListener {
                prefs.edit().putBoolean("show_chance", !showChance).apply()
                buildMorePane()
                render()
            }
        })
        moreBox.addView(TextView(this).apply {
            text = "Each toggle adds a small line by the song time. Chance is a compact percentage with decimals."
            textSize = 12f
        })

        moreBox.addView(section("Song analyzer"))
        moreBox.addView(TextView(this).apply {
            text = "Cleans video junk and quotation wrappers, then checks an online index for title, artist and genre. Review the suggestions and choose Apply. Android may ask for write access. Only enabled folders are touched; filenames and playlists are kept. " +
                if (prefs.getBoolean("analyzer_online", true)) "Online lookup is on."
                else "Online lookup is off, names only."
            textSize = 12f
        })
        moreBox.addView(xpBtn("Analyze songs").apply {
            setOnClickListener { analyzeDialog() }
        })
        moreBox.addView(xpBtn("Organize into BlissPlayer folder").apply {
            setOnClickListener { organizeConfirm() }
        })
        moreBox.addView(TextView(this).apply {
            text = "Organize files into Music, BlissPlayer, Artist, Album. Only touches folders you enabled."
            textSize = 12f
        })

        moreBox.addView(section("Playlists"))
        moreBox.addView(TextView(this).apply {
            text = "Playlists also save as JSON in:\n${com.blissplayer.music.data.PlaylistJson.exportPath(this@MainActivity)}"
            textSize = 12f
        })
        moreBox.addView(xpBtn("Save playlists now").apply {
            setOnClickListener {
                com.blissplayer.music.data.PlaylistJson.exportAll(this@MainActivity, db)
                toast("Playlists saved.")
            }
        })

        moreBox.addView(section("Stats"))
        moreBox.addView(TextView(this).apply {
            val totals = db.totalPlays()
            val totalPlays = totals.values.sum()
            val skips = prefs.getLong("stats_skips", 0)
            val listenMs = prefs.getLong("stats_listen_ms", 0) + listenAccumMs
            val h = listenMs / 3600000
            val m = (listenMs % 3600000) / 60000
            val topSong = totals.maxByOrNull { it.value }
            val topSongTxt = topSong?.let { (id, c) ->
                "${byId[id]?.title ?: "A deleted song"} ($c plays)"
            } ?: "None yet"
            val byArtist = mutableMapOf<String, Int>()
            totals.forEach { (id, c) ->
                val names = byId[id]?.let { com.blissplayer.music.data.SongAnalyzer.splitArtists(it.artist) }
                    .orEmpty().ifEmpty { listOf("Unknown artist") }
                names.forEach { a -> byArtist[a] = (byArtist[a] ?: 0) + c }
            }
            val topArtist = byArtist.maxByOrNull { it.value }
            val topArtistTxt = topArtist?.let { "${it.key} (${it.value} plays)" } ?: "None yet"
            text = "Songs in library: ${songs.size}\n" +
                "Songs played: ${totals.size}\n" +
                "Total plays: $totalPlays\n" +
                "Skips: $skips\n" +
                "Time listening: ${h}h ${m}m\n" +
                "Most played song: $topSongTxt\n" +
                "Most played artist: $topArtistTxt"
            textSize = 13f
        })
        moreBox.addView(xpBtn("Reset all stats").apply {
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setCustomTitle(xpTitle("Reset all stats?"))
                    .setMessage("This wipes play counts, skips, and listening time. Equalize data and playlists stay.")
                    .setPositiveButton("Reset") { _, _ ->
                        db.resetPlays()
                        db.clearTotals()
                        prefs.edit()
                            .putLong("stats_skips", 0)
                            .putLong("stats_listen_ms", 0)
                            .apply()
                        listenAccumMs = 0
                        refreshCountCache()
                        buildMorePane()
                        render()
                        toast("All stats wiped. Playlists and equalize data kept.")
                    }
                    .setNegativeButton("Keep", null)
                    .show()
            }
        })

        moreBox.addView(section("About"))
        moreBox.addView(TextView(this).apply {
            val v = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: ""
            text = "BlissPlayer $v"
            textSize = 13f
        })
        moreBox.addView(xpBtn("Copy last crash log").apply {
            setOnClickListener {
                val log = CrashReporter.read(this@MainActivity)
                if (log.isNullOrBlank()) {
                    toast("No crash recorded. If it crashes, this button copies the log.")
                } else if (copyText("crash", log)) {
                    toast("Crash log copied. Paste it in chat.")
                } else toast("Copy failed. Use Export and choose where to save it.")
            }
        })
        moreBox.addView(xpBtn("Export last crash log").apply {
            setOnClickListener {
                CrashReporter.read(this@MainActivity)?.let { exportCrash(it) } ?: toast("No crash recorded.")
            }
        })
        prefs.getString("last_playback_error", null)?.let { error ->
            moreBox.addView(TextView(this).apply { text = "Last playback problem: $error"; textSize = 12f })
        }
        moreBox.addView(TextView(this).apply {
            text = "If the app ever closes on its own, come back here to copy what happened."
            textSize = 12f
        })
        paintMore()
        groupControlPanels()
        morePane.post { morePane.scrollTo(0, scrollPosition) }
    }

    /** Property windows rather than a single endless, unstyled settings sheet. */
    private fun groupControlPanels() {
        val children = (0 until moreBox.childCount).map { moreBox.getChildAt(it) }
        moreBox.removeAllViews()
        moreBox.addView(TextView(this).apply {
            text = "Control Panel"
            textSize = 21f
            xpBold()
            setTextColor(iconColor())
            setPadding(dp(8), dp(10), dp(8), dp(2))
        })
        moreBox.addView(TextView(this).apply {
            text = "Playback, sound and library settings. Select a panel to open it."
            textSize = 12f
            setTextColor(iconColor())
            setPadding(dp(8), 0, dp(8), dp(8))
        })
        var body: LinearLayout? = null
        val expanded = prefs.getStringSet("more_panels", setOf("Playback controls", "Boost"))!!.toMutableSet()
        children.forEach { child ->
            val heading = child.getTag(R.id.tabMore) as? String
            if (heading?.startsWith("section:") == true && child is TextView) {
                val name = heading.removePrefix("section:")
                val panel = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    background = xpBtnPanel()
                    setPadding(dp(3), dp(3), dp(3), dp(3))
                    layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
                }
                val contents = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                    visibility = if (name in expanded) View.VISIBLE else View.GONE
                }
                child.layoutParams = LinearLayout.LayoutParams(-1, -2)
                child.minimumHeight = dp(44)
                child.gravity = android.view.Gravity.CENTER_VERTICAL
                fun label() { child.text = "[${if (contents.visibility == View.VISIBLE) "-" else "+"}]  $name" }
                label()
                child.isFocusable = true
                child.setOnClickListener {
                    val open = contents.visibility != View.VISIBLE
                    contents.visibility = if (open) View.VISIBLE else View.GONE
                    if (open) expanded.add(name) else expanded.remove(name)
                    prefs.edit().putStringSet("more_panels", expanded.toSet()).apply()
                    label()
                }
                panel.addView(child)
                panel.addView(contents)
                moreBox.addView(panel)
                body = contents
            } else {
                if (child is Button) child.layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                    topMargin = dp(4); bottomMargin = dp(4)
                }
                else if (child is TextView) child.setPadding(0, dp(4), 0, dp(4))
                body?.addView(child)
            }
        }
    }

    /** Recolor plain labels in More after a theme switch (section bars keep white). */
    private fun paintMore() {
        paintGroup(moreBox)
    }

    private fun paintGroup(vg: ViewGroup) {
        for (i in 0 until vg.childCount) {
            val c = vg.getChildAt(i)
            if (c is TextView && !(c is Button) && c.background == null) {
                c.setTextColor(iconColor())
            } else if (c is ViewGroup) {
                paintGroup(c)
            }
        }
    }

    private fun renderPresets() {
        // Preset rows live between the hint text and the Add button; rebuild them.
        val presets = db.presets()
        // Remove old preset rows (tagged views).
        for (i in moreBox.childCount - 1 downTo 0) {
            val v = moreBox.getChildAt(i)
            if (v.getTag(R.id.tabMore) == "preset") moreBox.removeViewAt(i)
        }
        presets.forEach { (name, mult) ->
            val pct = (mult * 100).roundToInt()
            val b = xpBtn("$name ($pct percent)").apply {
                setTag(R.id.tabMore, "preset")
                setOnClickListener { vol.setBoost(mult.toFloat()) }
                setOnLongClickListener {
                    xpMenuDialog(name, listOf("Use", "Edit", "Delete")) { which ->
                        when (which) {
                            0 -> vol.setBoost(mult.toFloat())
                            1 -> presetAddDialog(name, pct)
                            2 -> AlertDialog.Builder(this@MainActivity)
                                .setCustomTitle(xpTitle("Delete preset $name?"))
                                .setPositiveButton("Delete") { _, _ ->
                                    db.deletePreset(name)
                                    buildMorePane()
                                }
                                .setNegativeButton("Keep", null)
                                .show()
                        }
                    }
                    true
                }
            }
            moreBox.addView(b)
        }
        if (presets.isEmpty()) {
            moreBox.addView(TextView(this).apply {
                setTag(R.id.tabMore, "preset")
                text = "No presets yet."
                textSize = 12f
            })
        }
    }

    private fun presetAddDialog(existingName: String? = null, existingPct: Int = 100) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val eName = EditText(this).apply {
            hint = "Name, like Quiet room"
            existingName?.let { setText(it) }
        }
        val ePct = EditText(this).apply {
            hint = "Percent, 1 to 500"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(existingPct.toString())
        }
        box.addView(eName)
        box.addView(ePct)
        AlertDialog.Builder(this)
            .setCustomTitle(xpTitle(if (existingName == null) "New preset" else "Edit preset"))
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val name = eName.text.toString().ifBlank { existingName ?: "Preset" }
                val pct = ePct.text.toString().toIntOrNull()?.coerceIn(1, 500) ?: 100
                if (existingName != null && existingName != name) db.deletePreset(existingName)
                db.putPreset(name, pct / 100.0)
                toast("Preset $name saved at $pct percent.")
                buildMorePane()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun manualFolderDialog() {
        val input = EditText(this).apply {
            hint = "/storage/emulated/0/Music"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        AlertDialog.Builder(this)
            .setCustomTitle(xpTitle("Add a folder"))
            .setMessage("Type the full folder path. Songs under it will be included.")
            .setView(pad(input))
            .setPositiveButton("Add") { _, _ ->
                val p = input.text.toString().trim()
                if (p.isNotBlank()) {
                    val cur = prefs.getStringSet("folders", emptySet())!!.toMutableSet()
                    cur.add(p)
                    prefs.edit().putStringSet("folders", cur).apply()
                    buildMorePane()
                    toast("Folder added.")
                    scan(fromTap = true)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun renderDeviceList() {
        for (i in moreBox.childCount - 1 downTo 0) {
            if (moreBox.getChildAt(i).getTag(R.id.tabMore) == "device") moreBox.removeViewAt(i)
        }
        val devices = try { vol.allDevices() } catch (e: Exception) { emptyList() }
        if (devices.isEmpty()) {
            moreBox.addView(TextView(this).apply {
                setTag(R.id.tabMore, "device")
                text = "No remembered outputs yet. Connect Bluetooth once and it appears here."
                textSize = 12f
            })
            return
        }
        devices.forEach { (name, mult) ->
            val pct = (mult * 100).roundToInt()
            val cur = name == vol.currentDevice
            val auto = vol.isAutoplay(name)
            val b = xpBtn(
                "${if (cur) "> " else ""}$name ($pct percent${if (auto) ", autoplays" else ""})"
            ).apply {
                setTag(R.id.tabMore, "device")
                // Tap opens that device's options: use, boost, autoplay, forget.
                setOnClickListener { deviceDialog(name, pct) }
                setOnLongClickListener {
                    vol.useDevicePreset(name)
                    toast("Applied $name's boost to ${vol.currentDevice}.")
                    buildMorePane()
                    true
                }
            }
            moreBox.addView(b)
        }
    }

    /** One device, every option: use it, its boost, autoplay on connect, forget it. */
    private fun deviceDialog(name: String, curPct: Int) {
        val auto = vol.isAutoplay(name)
        val items = listOf(
            "Apply this boost to the current output",
            "Set boost ($curPct percent)",
            "Autoplay on connect: ${if (auto) "On" else "Off"}",
            "Forget this device"
        )
        xpMenuDialog(name, items) { which ->
            when (which) {
                0 -> {
                    vol.useDevicePreset(name)
                    toast("Applied $name's boost to ${vol.currentDevice}.")
                    buildMorePane()
                }
                1 -> deviceBoostDialog(name, curPct)
                2 -> {
                    vol.setAutoplay(name, !auto)
                    toast(
                        if (!auto) "$name will start the music when it connects."
                        else "Autoplay off for $name."
                    )
                    buildMorePane()
                }
                3 -> {
                    if (name == vol.currentDevice || name == VolumeManager.DEVICE_SPEAKER) {
                        toast("The active output and speaker fallback cannot be forgotten.")
                        return@xpMenuDialog
                    }
                    vol.forgetDevice(name)
                    toast("Forgot $name.")
                    buildMorePane()
                }
            }
        }
    }

    private fun deviceAddDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val eName = EditText(this).apply { hint = "BT: My Car (must match the Bluetooth name)" }
        val ePct = EditText(this).apply {
            hint = "Boost percent, 1 to 2000"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText("200")
        }
        box.addView(eName)
        box.addView(ePct)
        AlertDialog.Builder(this)
            .setCustomTitle(xpTitle("Remember a device"))
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val name = eName.text.toString().trim().ifBlank { return@setPositiveButton }
                val pct = ePct.text.toString().toIntOrNull()?.coerceIn(1, 2000) ?: 100
                db.putBoost(name, pct / 100.0)
                toast("$name remembered at $pct percent.")
                buildMorePane()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deviceBoostDialog(name: String, curPct: Int) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(curPct.toString())
        }
        AlertDialog.Builder(this)
            .setCustomTitle(xpTitle("Boost for $name"))
            .setView(pad(input))
            .setPositiveButton("Save") { _, _ ->
                val pct = input.text.toString().toIntOrNull()?.coerceIn(1, 2000) ?: 100
                db.putBoost(name, pct / 100.0)
                // If this is the live output, apply now.
                if (name == vol.currentDevice) vol.setBoost(pct / 100f)
                toast("$name set to $pct percent.")
                buildMorePane()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun analyzeDialog() {
        if (songs.isEmpty()) {
            toast("Scan your music first.")
            return
        }
        val folders = prefs.getStringSet("folders", emptySet())!!
        val inScope = if (folders.isEmpty()) songs
        else songs.filter { s ->
            s.isInFolders(folders)
        }
        if (inScope.isEmpty()) {
            toast("No songs in the enabled folders.")
            return
        }
        val online = prefs.getBoolean("analyzer_online", true)
        AlertDialog.Builder(this)
            .setCustomTitle(xpTitle("Analyze ${inScope.size} songs?"))
            .setMessage(
                "Cleans video junk from names, then checks each song against an online music index for the right title, artist, and genre. " +
                    "Review the suggestions, then choose Apply. Android may ask for write access. Only enabled folders are touched; filenames and playlists are kept."
            )
            .setPositiveButton(if (online) "Analyze (online)" else "Analyze (offline)") { _, _ -> runAnalyze(inScope, online) }
            .setNeutralButton("Online lookup: ${if (online) "On" else "Off"}") { _, _ ->
                prefs.edit().putBoolean("analyzer_online", !online).apply()
                toast("Online lookup ${if (!online) "on. Slower, but smarter." else "off. Names only."}")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private var analyzerJob: Job? = null

    private fun runAnalyze(inScope: List<Song>, online: Boolean) {
        val genreSnapshot = genreMap.toMap()
        val albumSnapshot = inScope.associate { it.mediaId to effAlbum(it) }
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = inScope.size.coerceAtLeast(1)
            progressDrawable = xpProgress()
        }
        val label = TextView(this).apply { text = "Starting." }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            addView(label)
            addView(bar)
        }
        var dlg: AlertDialog? = null
        dlg = AlertDialog.Builder(this)
            .setCustomTitle(xpTitle("Analyzing"))
            .setView(box)
            .setNegativeButton("Stop") { _, _ ->
                analyzerJob?.cancel()
                toast("Analyzer stopped. Nothing was changed yet.")
            }
            .setCancelable(false)
            .show()
        analyzerJob?.cancel()
        analyzerJob = lifecycleScope.launch {
            try {
                val proposals = try {
                    com.blissplayer.music.data.SongAnalyzer.analyze(
                        inScope, { genreSnapshot[it] ?: "Uncategorized" },
                        { id -> albumSnapshot[id] ?: "Unknown album" },
                        online,
                        onProgress = { done, total, phase ->
                            bar.max = total.coerceAtLeast(1)
                            bar.progress = done
                            label.text = when (phase) {
                                "names" -> "Cleaning names ($done of $total)."
                                "lookup" -> "Checking online index ($done of $total)."
                                else -> "Finishing up."
                            }
                        }
                    )
                } catch (e: com.blissplayer.music.data.SongAnalyzer.OfflineException) {
                    runCatching { dlg?.dismiss() }
                    toast("No internet, so names were cleaned offline only.")
                    showProposals(e.partial)
                    return@launch
                }
                runCatching { dlg?.dismiss() }
                if (proposals.isEmpty()) {
                    AlertDialog.Builder(this@MainActivity)
                        .setCustomTitle(xpTitle("Analyzer"))
                        .setMessage(if (online) "No verified metadata changes were found. Some songs may not be in the online index. Nothing was changed."
                            else "No name-cleanup changes were found. Nothing was changed.")
                        .setPositiveButton("OK", null)
                        .show()
                    return@launch
                }
                toast("Analyzer found ${proposals.size} songs to fix.")
                showProposals(proposals)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                runCatching { dlg?.dismiss() }
                toast("Analyzer stopped early: ${e.message ?: "unknown problem"}.")
            } finally {
                runCatching { dlg?.dismiss() }
            }
        }
    }

    private fun showProposals(proposals: List<com.blissplayer.music.data.SongAnalyzer.Proposal>) {
        if (proposals.isEmpty()) {
            toast("No metadata changes were found. No files were changed.")
            return
        }
        val high = proposals.filter { it.confidence == "High" }
        val check = proposals.filter { it.confidence != "High" }
        val msg = StringBuilder()
        msg.append("${proposals.size} songs can be cleaned (${high.size} confident, ${check.size} to confirm).\n\n")
        proposals.take(12).forEach { p ->
            msg.append("- ${p.song.title}\n  becomes ${p.newTitle} (${p.newArtist}) [${p.confidence}: ${p.reason}]\n")
        }
        if (proposals.size > 12) msg.append("\n...and ${proposals.size - 12} more.")
        msg.append("\n\nConfident fixes keep playlists working. Files are edited in place.")
        AlertDialog.Builder(this)
            .setCustomTitle(xpTitle("Apply analyzer?"))
            .setMessage(msg.toString())
            .setPositiveButton(if (check.isEmpty()) "Apply all" else "Apply confident (${high.size})") { _, _ ->
                applyProposals(high, after = { if (check.isNotEmpty()) showConfirmList(check) })
            }
            .setNeutralButton(if (check.isEmpty()) null else "Review all") { _, _ -> showConfirmList(proposals) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showConfirmList(list: List<com.blissplayer.music.data.SongAnalyzer.Proposal>) {
        val selected = mutableSetOf<Int>()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val sv = ScrollView(this).apply {
            addView(box)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(380)
            )
        }
        list.forEachIndexed { i, p ->
            box.addView(android.widget.CheckBox(this).apply {
                text = "${p.song.title}\nTo: ${p.newTitle}\nArtist: ${p.newArtist}\nAlbum: ${p.newAlbum}\nGenre: ${p.newGenre}\n${p.reason}"
                textSize = 13f
                setTextColor(iconColor())
                setOnCheckedChangeListener { _, checked -> if (checked) selected.add(i) else selected.remove(i) }
            })
        }
        AlertDialog.Builder(this)
            .setCustomTitle(xpTitle("Confirm each fix"))
            .setView(sv)
            .setPositiveButton("Apply selected") { _, _ ->
                if (selected.isEmpty()) toast("No songs selected. Nothing changed.")
                else applyProposals(list.filterIndexed { i, _ -> i in selected })
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun applyProposals(
        list: List<com.blissplayer.music.data.SongAnalyzer.Proposal>,
        previousDone: Int = 0,
        previousErrors: List<String> = emptyList(),
        after: (() -> Unit)? = null,
        permissionAttempts: Set<String> = emptySet()
    ) {
        if (list.isEmpty()) { after?.invoke(); return }
        if (analyzerJob?.isActive == true) { toast("Another library operation is still running."); return }
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = list.size.coerceAtLeast(1)
            progressDrawable = xpProgress()
        }
        val label = TextView(this).apply { text = "Saving fixes." }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            addView(label)
            addView(bar)
        }
        val dlg = AlertDialog.Builder(this)
            .setCustomTitle(xpTitle("Applying fixes"))
            .setView(box)
            .setCancelable(false)
            .show()
        analyzerJob = lifecycleScope.launch {
            var done = previousDone
            val errors = previousErrors.toMutableList()
            try {
            for ((i, p) in list.withIndex()) {
                bar.progress = i
                label.text = "Saving ($i of ${list.size}): ${p.newTitle}"
                try {
                    val edit = com.blissplayer.music.data.TagTools.Edit(
                        p.newTitle, p.newArtist, p.newAlbum, p.newGenre, null, null
                    )
                    when (val r = com.blissplayer.music.data.TagTools.writeTags(this@MainActivity, p.song, edit)) {
                        is com.blissplayer.music.data.TagTools.WriteResult.Ok -> {
                            db.setGenre(p.song.mediaId, p.newGenre)
                            db.setAlbum(p.song.mediaId, p.newAlbum)
                            done++
                        }
                        is com.blissplayer.music.data.TagTools.WriteResult.NeedsPermission -> {
                            if (p.song.contentUri in permissionAttempts) {
                                errors.add("${p.song.title}: Android still denied access after permission was granted. The file was not changed.")
                                continue
                            }
                            runCatching { dlg.dismiss() }
                            val remaining = list.drop(i)
                            val requested = remaining.map { it.song.contentUri }.distinct().take(500)
                            val savedSoFar = done
                            val failuresSoFar = errors.toList()
                            pendingRetry = { applyProposals(remaining, savedSoFar, failuresSoFar, after, permissionAttempts + requested) }
                            try {
                                val sender = if (Build.VERSION.SDK_INT >= 30) MediaStore.createWriteRequest(
                                    contentResolver, requested.map { Uri.parse(it) }
                                ).intentSender else r.sender
                                senderLauncher.launch(IntentSenderRequest.Builder(sender).build())
                            } catch (e: Exception) {
                                pendingRetry = null
                                AlertDialog.Builder(this@MainActivity).setCustomTitle(xpTitle("Write access failed"))
                                    .setMessage("$done songs saved. Android could not request access for the remaining songs: ${e.message}")
                                    .setPositiveButton("OK", null).show()
                            }
                            scan()
                            return@launch
                        }
                        is com.blissplayer.music.data.TagTools.WriteResult.Failed -> {
                            errors.add("${p.song.title}: ${r.msg}")
                        }
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    errors.add("${p.song.title}: ${e.message ?: "unknown problem"}")
                }
            }
            runCatching { dlg.dismiss() }
            scan()
            refreshCountCache()
            val extra = if (errors.isNotEmpty()) "\n\n${errors.size} could not be saved:\n" + errors.take(20).joinToString("\n") else ""
            AlertDialog.Builder(this@MainActivity)
                .setCustomTitle(xpTitle("Analyzer done"))
                .setMessage("Saved and verified $done songs.$extra")
                .setPositiveButton("OK") { _, _ -> after?.invoke() }
                .setOnCancelListener { after?.invoke() }
                .show()
            } finally {
                runCatching { dlg.dismiss() }
            }
        }
    }

    private fun organizeConfirm() {
        if (songs.isEmpty()) {
            toast("Scan your music first.")
            return
        }
        AlertDialog.Builder(this)
            .setCustomTitle(xpTitle("Organize into BlissPlayer folder?"))
            .setMessage("Moves your enabled songs into Music, BlissPlayer, Artist, Album. Playlists keep working. System folders are never touched.")
            .setPositiveButton("Organize") { _, _ -> runOrganize() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun runOrganize(retrySongs: List<Song>? = null, permissionAttempts: Set<String> = emptySet()) {
        if (analyzerJob?.isActive == true) { toast("Another library operation is still running."); return }
        val folders = prefs.getStringSet("folders", emptySet())!!
        val inScope = (retrySongs ?: if (folders.isEmpty()) songs else songs.filter { s ->
            s.isInFolders(folders)
        }).map { it.copy(album = effAlbum(it)) }
        if (inScope.isEmpty()) {
            toast("No songs in the enabled folders.")
            return
        }
        // Keep moved files in the enabled library, including after a partial/cancelled run.
        if (folders.isNotEmpty()) {
            val destinations = inScope.mapNotNull { s ->
                val path = s.filePath ?: return@mapNotNull null
                val root = if (path.startsWith("/storage/emulated/0/")) "/storage/emulated/0" else
                    Regex("^/storage/[^/]+").find(path)?.value
                root?.let { "$it/Music/BlissPlayer" }
            }
            prefs.edit().putStringSet("folders", folders + destinations).apply()
        }
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = inScope.size.coerceAtLeast(1)
            progressDrawable = xpProgress()
        }
        val label = TextView(this).apply { text = "Starting." }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            addView(label)
            addView(bar)
        }
        var dlg: AlertDialog? = null
        dlg = AlertDialog.Builder(this)
            .setCustomTitle(xpTitle("Organizing"))
            .setView(box)
            .setNegativeButton("Stop") { _, _ ->
                analyzerJob?.cancel()
                toast("Organizer stopped. Moves so far are kept.")
            }
            .setCancelable(false)
            .show()
        analyzerJob?.cancel()
        analyzerJob = lifecycleScope.launch {
            try {
                val res = com.blissplayer.music.data.SongAnalyzer.organize(
                    this@MainActivity, inScope
                ) { done, total, name ->
                    bar.max = total.coerceAtLeast(1)
                    bar.progress = done
                    label.text = if (name.isBlank()) "Done." else "Moving ($done of $total): $name"
                }
                runCatching { dlg?.dismiss() }
                var permissionError = ""
                val requestable = res.needsPermission.filter { it !in permissionAttempts }.take(500)
                if (requestable.isNotEmpty() && Build.VERSION.SDK_INT >= 30) {
                    // One system tap covers the whole batch; then run again
                    // for the files it just unlocked.
                    pendingRetry = { runOrganize(inScope.filter { it.contentUri in res.needsPermission }, permissionAttempts + requestable) }
                    val uris = requestable.map { Uri.parse(it) }.distinct()
                    if (uris.isNotEmpty()) {
                        try {
                            val sender = MediaStore.createWriteRequest(contentResolver, uris).intentSender
                            senderLauncher.launch(IntentSenderRequest.Builder(sender).build())
                            toast("Allow once, then organizing finishes the rest.")
                            return@launch
                        } catch (e: Exception) {
                            pendingRetry = null
                            permissionError = "\nAndroid could not request write permission: ${e.message}"
                        }
                    }
                }
                if (res.needsPermission.any { it in permissionAttempts }) {
                    permissionError += "\nAndroid still denied some files after permission was granted. They were left unchanged. Check file access or whether another app has moved/deleted them."
                }
                scan()
                val notes = (if (res.notes.isNotEmpty()) "\n\n" + res.notes.joinToString("\n") else "") + permissionError
                AlertDialog.Builder(this@MainActivity)
                    .setCustomTitle(xpTitle("Done"))
                    .setMessage(
                        "Moved ${res.moved} songs into the BlissPlayer folder. " +
                            "${res.alreadyOrganized} already in the right folder. " +
                            "${res.needsPermission.size} need Android write permission. " +
                            "${res.skipped - res.alreadyOrganized - res.needsPermission.size} failed.$notes"
                    )
                    .setPositiveButton("OK", null)
                    .show()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                runCatching { dlg?.dismiss() }
                AlertDialog.Builder(this@MainActivity).setCustomTitle(xpTitle("Organizer stopped"))
                    .setMessage("Moves already completed are kept. ${e.javaClass.simpleName}: ${e.message ?: "Unknown error"}")
                    .setPositiveButton("OK", null).show()
            } finally {
                runCatching { dlg?.dismiss() }
                if (!isDestroyed && !isFinishing) scan()
            }
        }
    }

    private fun updateDeviceToggle() {
        val on = prefs.getBoolean(VolumeManager.KEY_AUTO_DEVICE, true)
        deviceToggle?.text = "Auto device boost: ${if (on) "On" else "Off"}"
    }

    private fun updateShuffleViews() {
        val smart = prefs.getBoolean("shuffle_smart", true)
        val strength = prefs.getFloat("shuffle_strength", 1.0f)
        smartToggle?.text = "Smart shuffle: ${if (smart) "On" else "Off"}"
        val band = when {
            !smart -> "off, plain random"
            strength < 0.5f -> "Gentle"
            strength < 1.5f -> "Balanced"
            else -> "Harsh"
        }
        shuffleStrengthLabel?.text = "Anti-repeat strength: ${"%.1f".format(strength)} ($band)"
        shuffleSeek?.let {
            val want = (strength * 100).roundToInt().coerceIn(0, 300)
            if (it.progress != want) it.progress = want
        }
    }

    private fun updateHoldToggle() {
        val on = prefs.getBoolean(SafeVolumeGuard.KEY_HOLD, false)
        holdToggle?.text = "Hold system volume steady: ${if (on) "On" else "Off"}"
    }

    private fun updateBoostViews() {
        val pct = (vol.boost * 100).roundToInt()
        val warn = when {
            pct > 500 -> " (expect heavy distortion)"
            pct > 100 -> " (clipping risk)"
            else -> ""
        }
        boostLabel?.text = "Boost: $pct percent$warn. Tap to type an exact number."
        boostLabel?.setTextColor(if (pct > 100) (if (dark) 0xFFFFCC88.toInt() else 0xFF9B2500.toInt()) else iconColor())
        boostSeek?.let { if ((it.progress + 1) != pct.coerceIn(1, 500)) it.progress = (pct.coerceIn(1, 500) - 1).coerceIn(0, 499) }
        val auto = prefs.getBoolean(VolumeManager.KEY_AUTO_DEVICE, true)
        deviceLabel?.text = "Output: ${vol.currentDevice}" + if (auto) " (remembered per device)" else " (auto remember off)"
        refreshMini()
    }

    private fun boostEditDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText((vol.boost * 100).roundToInt().toString())
            hint = "1 to 2000"
        }
        AlertDialog.Builder(this)
            .setCustomTitle(xpTitle("Exact boost percent"))
            .setMessage("1 to 2000%. High boost can distort, damage speakers or harm hearing. Start low; no boost setting guarantees safe listening.")
            .setView(pad(input))
            .setPositiveButton("Set") { _, _ ->
                val pct = input.text.toString().toIntOrNull()?.coerceIn(1, 2000) ?: 100
                vol.setBoost(pct / 100f)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun applyRotation() {
        requestedOrientation = when (prefs.getString("rotation", "Auto")) {
            "Portrait" -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            "Landscape" -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    private fun equalizeConfirm() {
        if (songs.isEmpty()) {
            toast("Scan your music first.")
            return
        }
        val gains = db.allGains()
        val cached = db.getMeasures()
        val need = songs.count { s -> s.mediaId !in gains || cached[s.mediaId]?.second != s.modified }
        if (need == 0) {
            AlertDialog.Builder(this)
                .setCustomTitle(xpTitle("Already equalized"))
                .setMessage("Every song is already equalized and nothing changed, so there is nothing to do.")
                .setPositiveButton("OK", null)
                .setNegativeButton("Measure again anyway") { _, _ -> runEqualize() }
                .show()
            return
        }
        AlertDialog.Builder(this)
            .setCustomTitle(xpTitle("Equalize ${songs.size} songs?"))
            .setMessage("$need new or changed, the rest are reused from last time. This runs in the background and you can keep using the app. Nothing about your files changes, and Undo is one tap.")
            .setPositiveButton("Equalize") { _, _ -> runEqualize() }
            .setNegativeButton("Not now", null)
            .show()
    }

    private fun fmtEta(sec: Long): String = when {
        sec < 5 -> "finishing up"
        sec < 90 -> "about $sec sec left"
        else -> "about ${sec / 60} min left"
    }

    private fun runEqualize() {
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressDrawable = xpProgress()
        }
        val label = TextView(this).apply { text = "Starting." }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            addView(label)
            addView(bar)
        }
        val dlg = AlertDialog.Builder(this)
            .setCustomTitle(xpTitle("Equalizing"))
            .setView(box)
            .setNegativeButton("Cancel") { _, _ -> eqJob?.cancel() }
            .setCancelable(false)
            .show()
        eqJob = lifecycleScope.launch {
            try {
                val cached = db.getMeasures().mapValues { LoudnessScanner.Cached(it.value.first, it.value.second) }
                val res = LoudnessScanner.equalizeAll(
                    this@MainActivity, songs,
                    cached = cached,
                    putGain = { id, mult -> db.putGain(id, mult) },
                    putCache = { id, rms, mod -> db.putMeasure(id, rms, mod) },
                    onProgress = { done, total, title, eta ->
                        bar.max = total
                        bar.progress = done
                        val etaTxt = if (eta != null) ", ${fmtEta(eta)}" else ""
                        label.text = "Listening ($done of $total$etaTxt): $title"
                    }
                )
                runCatching { dlg.dismiss() }
                svc?.refreshGains()
                refreshCountCache()
                prefs.edit()
                    .putLong("eq_last", System.currentTimeMillis())
                    .putInt("eq_measured", res.measured)
                    .putInt("eq_up", res.raised)
                    .putInt("eq_down", res.lowered)
                    .apply()
                if (tab == 5) buildMorePane()
                val skippedTxt = if (res.skipped > 0) ", skipped ${res.skipped}" else ""
                val reusedTxt = if (res.reused > 0) " ${res.reused} reused from last time." else ""
                AlertDialog.Builder(this@MainActivity)
                    .setCustomTitle(xpTitle("Done"))
                    .setMessage(
                        "Checked ${res.measured} songs.$reusedTxt Turned up ${res.raised}, turned down ${res.lowered}$skippedTxt. " +
                            "From now on everything plays at the same average volume, on top of your Boost."
                    )
                    .setPositiveButton("OK", null)
                    .show()
            } catch (e: Exception) {
                // Cancellation from the Cancel button is normal, stay quiet.
                if (e is kotlinx.coroutines.CancellationException) throw e
                runCatching { dlg.dismiss() }
                AlertDialog.Builder(this@MainActivity)
                    .setCustomTitle(xpTitle("Equalize stopped"))
                    .setMessage("It ran into a problem halfway. What finished is kept. Try again when ready.")
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    // ---------- small helpers ----------

    private fun iconColor() = if (dark) 0xFFE8E8E8.toInt() else 0xFF000000.toInt()

    /** Bundled PT Sans bold (Tahoma-spirited XP face). Falls back to system bold. */
    private fun TextView.xpBold() {
        val f = runCatching {
            androidx.core.content.res.ResourcesCompat.getFont(context, R.font.ptsans_700)
        }.getOrNull()
        typeface = f ?: android.graphics.Typeface.DEFAULT_BOLD
    }

    /** Hard XP title-bar text shadow. */
    private fun TextView.xpTitleShadow() {
        setShadowLayer(2f, 1f, 1f, 0x80000000.toInt())
    }

    private fun xpBtnBg() = ContextCompat.getDrawable(
        this, if (dark) R.drawable.xp_button_dark else R.drawable.xp_button
    )!!

    private fun xpProgress() = ContextCompat.getDrawable(this, R.drawable.xp_progress_bg)!!.let { bg ->
        val bmp = android.graphics.BitmapFactory.decodeResource(resources, R.drawable.xp_seg_tile)
        val tile = android.graphics.drawable.BitmapDrawable(resources, bmp).apply {
            tileModeX = android.graphics.Shader.TileMode.REPEAT
        }
        val clip = ClipDrawable(tile, android.view.Gravity.LEFT, ClipDrawable.HORIZONTAL)
        LayerDrawable(arrayOf(bg, clip)).apply {
            setId(0, android.R.id.background)
            setId(1, android.R.id.progress)
        }
    }

    private fun xpThumb() = ContextCompat.getDrawable(this, R.drawable.xp_thumb)!!

    private fun section(t: String): TextView =
        TextView(this).apply {
            setTag(R.id.tabMore, "section:$t")
            text = t
            textSize = 14f
            xpBold()
            xpTitleShadow()
            setTextColor(0xFFFFFFFF.toInt())
            background = ContextCompat.getDrawable(
                this@MainActivity,
                if (dark) R.drawable.xp_title_bar_dark else R.drawable.xp_title_bar
            )
            setPadding(dp(8), dp(6), dp(8), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12); bottomMargin = dp(4) }
        }

    private fun xpBtn(t: String): Button =
        Button(this, null, android.R.attr.buttonStyle).apply {
            text = t
            background = xpBtnBg()
            setTextColor(iconColor())
        }

    /** XP blue gradient title bar for every dialog. */
    private fun xpTitle(t: String): TextView =
        TextView(this).apply {
            text = t
            textSize = 16f
            xpBold()
            xpTitleShadow()
            setTextColor(0xFFFFFFFF.toInt())
            background = ContextCompat.getDrawable(
                this@MainActivity,
                if (dark) R.drawable.xp_title_bar_dark else R.drawable.xp_title_bar
            )
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

    /** A context menu that looks like the rest of the app: title bar + beveled buttons. */
    private fun xpMenuDialog(title: String, items: List<String>, onPick: (Int) -> Unit) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        lateinit var dlg: AlertDialog
        items.forEachIndexed { i, label ->
            box.addView(xpBtn(label).apply {
                setOnClickListener {
                    runCatching { dlg.dismiss() }
                    onPick(i)
                }
            })
        }
        dlg = AlertDialog.Builder(this)
            .setCustomTitle(xpTitle(title))
            .setView(box)
            .create()
        dlg.show()
    }

    private fun pad(v: View): View =
        LinearLayout(this).apply {
            setPadding(dp(16), dp(8), dp(16), dp(8))
            addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

    private fun dp(n: Int): Int = (n * resources.displayMetrics.density).toInt()

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    private fun skinStatic() {
        findViewById<LinearLayout>(R.id.root).setBackgroundResource(R.drawable.xp_workspace)
        androidx.core.view.WindowInsetsControllerCompat(window, findViewById(R.id.root)).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
        titleBarWrap.setBackgroundResource(if (dark) R.drawable.xp_title_bar_dark else R.drawable.xp_title_bar)
        titleBar.setTextColor(0xFFFFFFFF.toInt())
        titleBar.xpTitleShadow()
        findViewById<ImageButton>(R.id.searchToggle)?.setColorFilter(0xFFFFFFFF.toInt())
        findViewById<LinearLayout>(R.id.miniPlayer).setBackgroundResource(
            if (dark) R.drawable.xp_panel_dark else R.drawable.xp_panel
        )
        statusStrip.setBackgroundResource(if (dark) R.drawable.xp_status_dark else R.drawable.xp_status)
        statusStrip.setTextColor(iconColor())
        crumb.setTextColor(if (dark) 0xFFA8ADB5.toInt() else 0xFF5A5A5A.toInt())
        (miniTitle.parent as? LinearLayout)?.apply {
            setBackgroundResource(R.drawable.xp_display)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            (layoutParams as? LinearLayout.LayoutParams)?.rightMargin = dp(6)
        }
        miniTitle.setTextColor(0xFFCAEF9B.toInt())
        miniTitle.typeface = android.graphics.Typeface.MONOSPACE
        miniSub.setTextColor(0xFF99C9B0.toInt())
        miniTime.setTextColor(if (dark) 0xFFA8ADB5.toInt() else 0xFF5A5A5A.toInt())
        headerBar.setBackgroundResource(R.drawable.xp_column_header)
        headerBar.setTextColor(iconColor())
        list.setBackgroundResource(if (dark) R.drawable.xp_field_dark else R.drawable.xp_field)
        list.setPadding(dp(2), dp(2), dp(2), dp(2))
        list.clipToPadding = true
        findViewById<LinearLayout>(R.id.nowPlayingPanel)?.background = xpBtnPanel()
        findViewById<LinearLayout>(R.id.npDisplay)?.apply {
            setBackgroundResource(R.drawable.xp_display)
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        findViewById<TextView>(R.id.npTitle)?.apply {
            setTextColor(0xFFCAEF9B.toInt())
            typeface = android.graphics.Typeface.MONOSPACE
        }
        findViewById<TextView>(R.id.npArtist)?.setTextColor(0xFF99C9B0.toInt())
        listOf(
            R.id.tabSongs, R.id.tabArtists, R.id.tabAlbums, R.id.tabGenres, R.id.tabPlaylists, R.id.tabMore,
            R.id.btnPlayAll
        ).forEach { id ->
            findViewById<Button>(id)?.setTextColor(if (id == R.id.btnPlayAll) 0xFFFFFFFF.toInt() else iconColor())
        }
        findViewById<Button>(R.id.btnPlayShuffle)?.apply {
            setBackgroundResource(if (dark) R.drawable.xp_button_dark else R.drawable.xp_start)
            setTextColor(0xFFFFFFFF.toInt())
        }
        findViewById<Button>(R.id.btnSort)?.apply {
            setBackgroundResource(if (dark) R.drawable.xp_button_dark else R.drawable.xp_button)
            setTextColor(iconColor())
        }
        updateTabs()
        playlistAdd.setBackgroundResource(if (dark) R.drawable.xp_button_dark else R.drawable.xp_button)
        playlistAdd.setTextColor(iconColor())
        filterBtn.setBackgroundResource(if (dark) R.drawable.xp_button_dark else R.drawable.xp_button)
        filterBtn.setTextColor(iconColor())
        searchClear.setBackgroundResource(if (dark) R.drawable.xp_button_dark else R.drawable.xp_button)
        searchClear.setTextColor(iconColor())
        mapOf(
            R.id.miniPlayPause to R.drawable.ic_play,
            R.id.miniNext to R.drawable.ic_next,
            R.id.miniQueue to R.drawable.ic_queue
        ).forEach { (id, icon) ->
            findViewById<ImageButton>(id)?.apply {
                setBackgroundResource(if (dark) R.drawable.xp_button_dark else R.drawable.xp_button)
                setImageResource(icon)
                setColorFilter(iconColor())
            }
        }
        mapOf(
            R.id.npPlay to R.drawable.ic_play,
            R.id.npPrev to R.drawable.ic_prev,
            R.id.npNext to R.drawable.ic_next
        ).forEach { (id, icon) ->
            findViewById<ImageButton>(id)?.apply {
                setBackgroundResource(if (dark) R.drawable.xp_button_dark else R.drawable.xp_button)
                setImageResource(icon)
                setColorFilter(iconColor())
            }
        }
        findViewById<Button>(R.id.npLoop)?.setTextColor(iconColor())
        findViewById<Button>(R.id.npShuffle)?.setTextColor(iconColor())
        findViewById<Button>(R.id.npSaveQueue)?.setTextColor(iconColor())
        findViewById<Button>(R.id.npSegEdit)?.setTextColor(iconColor())
        npBar?.dark = dark
        npBar?.invalidate()
    }

    /** content://.../tree/primary:Music to /storage/emulated/0/Music (primary volume only). */
    private fun treeUriToPath(uri: Uri): String? {
        return try {
            val docId = DocumentsContract.getTreeDocumentId(uri) ?: return null
            val parts = docId.split(":")
            if (parts.size < 2) return null
            val root = parts[0]
            val rel = parts.drop(1).joinToString(":")
            val base = if (root == "primary") android.os.Environment.getExternalStorageDirectory().absolutePath
            else "/storage/$root"
            "$base/$rel"
        } catch (e: Exception) {
            null
        }
    }
}
