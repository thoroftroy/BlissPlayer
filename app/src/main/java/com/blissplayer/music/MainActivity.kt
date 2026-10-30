package com.blissplayer.music

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
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
import com.blissplayer.music.playback.LoudnessScanner
import com.blissplayer.music.playback.PlayerService
import com.blissplayer.music.playback.SafeVolumeGuard
import com.blissplayer.music.playback.VolumeManager
import com.blissplayer.music.ui.LibraryAdapter
import com.blissplayer.music.ui.PickAdapter
import com.blissplayer.music.ui.PickRow
import com.blissplayer.music.ui.Row
import com.blissplayer.music.ui.SegmentSeekBar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var db: BlissDb
    private var dark = false

    private var songs: List<Song> = emptyList()
    private val byId = mutableMapOf<Long, Song>()

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
    private var searchJob: Job? = null

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
    private lateinit var guard: SafeVolumeGuard
    private val ticker = Handler(Looper.getMainLooper())

    private var pendingRetry: (() -> Unit)? = null
    private var eqJob: Job? = null

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { scan() }

    private val senderLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { r ->
        if (r.resultCode == RESULT_OK) pendingRetry?.invoke()
        pendingRetry = null
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
        scan()
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(n: ComponentName?, b: IBinder?) {
            svc = (b as PlayerService.LocalBinder).service()
            svc!!.boost = vol.boost
            svc!!.applyGainFor(svc!!.player.currentMediaItem)
            svc!!.player.addListener(playerListener)
            if (songs.isNotEmpty()) syncQueueFromPlayer() else pendingQueueSync = true
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

    private fun onTrackChanged(item: MediaItem?, reason: Int) {
        refreshMini()
        if (queueDialog != null) {
            updateNowPlayingHeader()
            refreshQueueList()
        }
        val id = item?.mediaId?.toLongOrNull()
        if (id != null && id != lastCountedId) {
            lastCountedId = id
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) {
                prefs.edit().putLong("stats_skips", prefs.getLong("stats_skips", 0) + 1).apply()
            }
            CoroutineScope(Dispatchers.IO).launch {
                db.bumpPlay(id)
                // Once every song in the library has played at least once,
                // wipe the slate so Smart Shuffle stays fresh forever.
                val counts = db.playCounts()
                if (songs.isNotEmpty() && songs.all { (counts[it.mediaId] ?: 0) > 0 }) {
                    db.resetPlays()
                }
                if (prefs.getBoolean("show_counts", false)) {
                    val lines = statLines()
                    launch(Dispatchers.Main) {
                        adapter.statLines = lines
                        adapter.notifyDataSetChanged()
                    }
                }
            }
        }
    }

    private fun updateNowPlayingHeader() {
        val p = svc?.player ?: return
        val song = p.currentMediaItem?.mediaId?.toLongOrNull()?.let { byId[it] }
        nowTitleView?.text = song?.title ?: "Now playing"
        nowArtistView?.text = song?.let { "${it.artist} | ${it.album}" } ?: ""
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
        val lastCrash = CrashReporter.read(this)
        setContentView(R.layout.activity_main)
        db = BlissDb(this)

        restoredQueueIds = savedInstanceState?.getLongArray("queue")
        lastCountedId = savedInstanceState?.getLong("counted", -1L) ?: -1L
        searchQuery = savedInstanceState?.getString("searchQuery", "") ?: ""
        filterArtist = savedInstanceState?.getString("filterArtist", "") ?: ""
        filterAlbum = savedInstanceState?.getString("filterAlbum", "") ?: ""
        filterGenre = savedInstanceState?.getString("filterGenre", "") ?: ""
        filterPlaylist = savedInstanceState?.getLong("filterPlaylist", -1L)?.takeIf { it != -1L && it != null }
        filterPlaylistName = savedInstanceState?.getString("filterPlaylistName", "") ?: ""

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
                // Drag reorder only makes sense inside a playlist drill.
                if (drillPlaylist == null) return false
                val rows = adapter.rows.toMutableList()
                val a = h.bindingAdapterPosition
                val b = t.bindingAdapterPosition
                if (a !in rows.indices || b !in rows.indices) return false
                java.util.Collections.swap(rows, a, b)
                adapter.submit(rows)
                val pid = drillPlaylist ?: return true
                db.reorderPlaylist(pid, rows.mapNotNull { (it as? Row.SongRow)?.song?.mediaId })
                return true
            }

            override fun onSwiped(h: RecyclerView.ViewHolder, dir: Int) {
                val pid = drillPlaylist
                val row = adapter.rows.getOrNull(h.bindingAdapterPosition) as? Row.SongRow
                if (pid == null || row == null) {
                    adapter.notifyItemChanged(h.bindingAdapterPosition)
                    return
                }
                db.removeFromPlaylist(pid, row.song.mediaId)
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

        vol = VolumeManager(this, db, prefs) { b ->
            svc?.let {
                it.boost = b
                it.applyGainFor(it.player.currentMediaItem)
            }
            updateBoostViews()
        }
        vol.start()
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

        showTab(0)
        ensurePermissions()
        ticker.post(progressTick)
        buildMorePane()
        lastCrash?.let { showCrashDialog(it) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLongArray("queue", queue.map { it.mediaId }.toLongArray())
        outState.putLong("counted", lastCountedId)
        outState.putString("searchQuery", searchQuery)
        outState.putString("filterArtist", filterArtist)
        outState.putString("filterAlbum", filterAlbum)
        outState.putString("filterGenre", filterGenre)
        outState.putLong("filterPlaylist", filterPlaylist ?: -1L)
        outState.putString("filterPlaylistName", filterPlaylistName)
        outState.putBoolean("searchOpen", findViewById<LinearLayout>(R.id.searchRow)?.visibility == View.VISIBLE)
    }

    override fun onDestroy() {
        ticker.removeCallbacksAndMessages(null)
        persistListenTime()
        runCatching { unbindService(conn) }
        vol.stop()
        guard.stop()
        eqJob?.cancel()
        super.onDestroy()
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
            .setCustomTitle(xpTitle("The app crashed last time"))
            .setMessage("Copy this and send it to the developer so it can be fixed. Or save it as a file and send the file.")
            .setView(sv)
            .setPositiveButton("Copy") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("crash", log))
                toast("Copied. Now paste it in chat.")
            }
            .setNeutralButton("Save to file") { _, _ -> saveCrashToDownloads(log) }
            .setNegativeButton("Close") { _, _ -> CrashReporter.clear(this) }
            .show()
    }

    private fun saveCrashToDownloads(log: String) {
        try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "BlissPlayer-crash.txt")
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: run { toast("Could not save the file."); return }
            contentResolver.openOutputStream(uri)?.use { it.write(log.toByteArray()) }
            toast("Saved in the Download folder as BlissPlayer-crash.txt.")
        } catch (e: Exception) {
            toast("Could not save the file.")
        }
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
        if (need.isEmpty()) scan() else permLauncher.launch(need.toTypedArray())
    }

    private fun hasAudioPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= 33)
            checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
        else checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun hasNotifPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= 33)
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        else true

    private fun scan() {
        if (!hasAudioPermission()) {
            adapter.submit(emptyList())
            crumb.text = "BlissPlayer needs Music permission. Grant it, then tap Scan in More."
            updateStatus()
            return
        }
        CoroutineScope(Dispatchers.IO).launch {
            val all = MusicScanner.scanAll(this@MainActivity)
            val folders = prefs.getStringSet("folders", emptySet())!!
            val filtered = if (folders.isEmpty()) all
            else all.filter { s -> folders.any { f -> (s.filePath ?: "").startsWith(f) } }
            launch(Dispatchers.Main) {
                songs = filtered
                byId.clear()
                filtered.forEach { byId[it.mediaId] = it }
                genreMap = db.allGenres()
                searchIndex = filtered.map { s ->
                    val g = genreMap[s.mediaId] ?: "Uncategorized"
                    val t = norm(s.title)
                    val a = norm(s.artist)
                    val al = norm(s.album)
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
                render()
                updateStatus()
                refreshMini()
                if (filtered.isEmpty()) {
                    crumb.text = if (all.isEmpty())
                        "No music found. Copy songs into the Music folder, then open More and tap Scan."
                    else "No songs in the chosen folders. Clear folders in More to scan everything."
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
        crumb.visibility = mainUi
        playlistAdd.visibility = View.GONE
        headerBar.visibility = View.GONE
        updateTabs()
        if (t == 5) buildMorePane() else render()
    }

    private val tabIds = listOf(R.id.tabSongs, R.id.tabArtists, R.id.tabAlbums, R.id.tabGenres, R.id.tabPlaylists, R.id.tabMore)

    private fun updateTabs() {
        tabIds.forEachIndexed { i, id ->
            findViewById<Button>(id).setBackgroundResource(
                if (i == tab) (if (dark) R.drawable.xp_button_pressed_dark else R.drawable.xp_button_pressed)
                else (if (dark) R.drawable.xp_button_dark else R.drawable.xp_button)
            )
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
        searchJob = CoroutineScope(Dispatchers.Main).launch {
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
        val wantStats = prefs.getBoolean("show_counts", false)
        adapter.showStats = wantStats
        adapter.statLines = if (wantStats) statLines() else emptyMap()
        when {
            drillArtist != null -> {
                val a = drillArtist!!
                val inA = songs.filter { it.artist == a }
                crumb.text = "$a (${inA.size}) | tap here to go back"
                adapter.submit(inA.map { Row.SongRow(it) })
            }
            drillAlbum != null -> {
                val a = drillAlbum!!
                val inA = songs.filter { it.album == a }
                crumb.text = "$a (${inA.size}) | tap here to go back"
                adapter.submit(inA.map { Row.SongRow(it) })
            }
            drillGenre != null -> {
                val g = drillGenre!!
                val inG = songs.filter { (genreMap[it.mediaId] ?: "Uncategorized") == g }
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
                adapter.submit(songs.map { Row.SongRow(it) })
            }
            tab == 1 -> {
                val groups = songs.groupBy { it.artist }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
                crumb.text = "${groups.size} artists"
                adapter.submit(groups.map { (k, v) -> Row.Group("ar:$k", k, "${v.size} songs") })
            }
            tab == 2 -> {
                val groups = songs.groupBy { it.album }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
                crumb.text = "${groups.size} albums"
                adapter.submit(groups.map { (k, v) ->
                    Row.Group("al:$k", k, "${v.first().artist} | ${v.size} songs")
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
                else "${pls.size} playlists. Tap to open, dots to rename or delete."
                val rows = mutableListOf<Row>(
                    Row.Group("__new", "Create playlist", "Keep a mix together")
                )
                pls.forEach { (id, name) ->
                    rows.add(Row.Group("pl:$id", name, "${db.songsIn(id).size} songs"))
                }
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
     * and the chance of coming up next under Smart Shuffle.
     */
    private fun statLines(): Map<Long, String> {
        val smart = prefs.getBoolean("shuffle_smart", true)
        val strength = prefs.getFloat("shuffle_strength", 1.0f).toDouble().coerceIn(0.0, 3.0)
        val counts = db.playCounts()
        val totals = db.totalPlays()
        val weights = if (!smart) {
            songs.associate { it.mediaId to 1.0 }
        } else {
            songs.associate { it.mediaId to 1.0 / Math.pow((1 + (counts[it.mediaId] ?: 0)).toDouble(), strength) }
        }
        val sum = weights.values.sum().coerceAtLeast(1e-9)
        return songs.associate { s ->
            val ever = totals[s.mediaId] ?: 0
            val cycle = counts[s.mediaId] ?: 0
            val pct = (weights[s.mediaId] ?: 0.0) / sum * 100
            val pctTxt = if (pct < 0.1) "<0.1"
            else String.format(Locale.US, "%.1f", pct).trimEnd('0').trimEnd('.')
            s.mediaId to "$ever total | $cycle cycle | $pctTxt% next"
        }
    }

    private fun visibleSongs(): List<Song> =
        adapter.rows.mapNotNull { (it as? Row.SongRow)?.song }

    private fun onRowTap(row: Row) {
        when (row) {
            is Row.SongRow -> {
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
        queue.clear()
        queue.addAll(context)
        shuffled = false
        p.setMediaItems(context.map(::toItem), start.coerceIn(0, context.size - 1), 0)
        p.repeatMode = REPEAT_MODE_OFF
        p.prepare()
        p.play()
        refreshMini()
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
        val ctx = visibleSongs()
        if (ctx.isNotEmpty()) {
            playQueue(ctx, 0)
            return
        }
        if (songs.isEmpty()) {
            toast("No music yet. Copy songs on, then Scan.")
            return
        }
        playQueue(songs, 0)
    }

    /**
     * Smart Shuffle. Each song carries a lifetime play count. Songs played
     * less get a bigger slice of the odds, so the mix stays fresh instead
     * of repeating favorites. When everything has played once, counts reset.
     * Strength 0 is gentle, 1 is balanced, higher is harsher. Off = true random.
     */
    private fun smartOrder(list: List<Song>): List<Song> {
        if (!prefs.getBoolean("shuffle_smart", true)) {
            return list.shuffled(Random(System.currentTimeMillis()))
        }
        val strength = prefs.getFloat("shuffle_strength", 1.0f).toDouble().coerceIn(0.0, 3.0)
        val counts = db.playCounts()
        val rnd = Random(System.currentTimeMillis())
        return list.map { s ->
            val weight = 1.0 / Math.pow((1 + (counts[s.mediaId] ?: 0)).toDouble(), strength)
            s to Math.pow(rnd.nextDouble().coerceAtLeast(1e-9), 1.0 / weight)
        }.sortedBy { it.second }.map { it.first }
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
        p.seekToNextMediaItem()
    }

    private fun skipPrev() {
        val p = svc?.player ?: return
        ensureQueueSync()
        p.seekToPreviousMediaItem()
    }

    // ---------- mini player / ticker ----------

    private fun refreshMini() {
        val p = svc?.player
        val item = p?.currentMediaItem
        val song = item?.mediaId?.toLongOrNull()?.let { byId[it] }
        miniTitle.text = song?.title ?: "Nothing playing"
        miniSub.text = song?.let { "${it.artist} | Boost ${(vol.boost * 100).roundToInt()} percent" }
            ?: "Tap Play to start"
        miniPlayPause.setImageResource(if (p?.isPlaying == true) R.drawable.ic_pause else R.drawable.ic_play)
        npTitle?.text = song?.title ?: "Nothing playing"
        npArtist?.text = song?.let { "${it.artist} | ${it.album}" } ?: ""
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
                0 -> playNext(song)
                1 -> addToQueueEnd(song)
                2 -> addToPlaylistDialog(listOf(song.mediaId))
                3 -> segmentDialog(song)
                4 -> CoroutineScope(Dispatchers.Main).launch {
                    val tags = TagTools.readTags(this@MainActivity, song)
                    tagEditorDialog(song, tags)
                }
                5 -> deleteConfirm(song)
                6 -> AlertDialog.Builder(this)
                    .setCustomTitle(xpTitle(song.title))
                    .setMessage(
                        "Artist: ${song.artist}\nAlbum: ${song.album}\nGenre: ${db.getGenre(song.mediaId)}\n" +
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
        val eAlbum = field("Album", fileTags[2] ?: song.album)
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
                CoroutineScope(Dispatchers.Main).launch {
                    when (val r = TagTools.writeTags(this@MainActivity, song, edit)) {
                        is TagTools.WriteResult.Ok -> {
                            db.setGenre(song.mediaId, edit.genre ?: "Uncategorized")
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
        CoroutineScope(Dispatchers.Main).launch {
            when (val r = TagTools.writeTags(this@MainActivity, song, edit)) {
                is TagTools.WriteResult.Ok -> {
                    db.setGenre(song.mediaId, edit.genre ?: "Uncategorized")
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
            typeface = android.graphics.Typeface.DEFAULT_BOLD
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
                val id = segSelectedId ?: return@setOnClickListener
                db.deleteSegment(id)
                segSelectedId = null
                svc?.refreshSegments()
                reloadSegEditor()
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
        } else {
            db.updateSegment(id, start, end.coerceAtMost(song.durationMs), label)
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
                CoroutineScope(Dispatchers.Main).launch {
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
        CoroutineScope(Dispatchers.Main).launch {
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

    private fun createPlaylistDialog(firstSongs: List<Long>) {
        val input = EditText(this).apply { hint = "Example: Gym mix" }
        AlertDialog.Builder(this)
            .setCustomTitle(xpTitle("New playlist"))
            .setView(pad(input))
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().ifBlank { "Playlist" }
                val id = db.createPlaylist(name)
                if (firstSongs.isNotEmpty()) db.addToPlaylist(id, firstSongs)
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
                if (tab == 4) render()
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
            val artists = songs.map { it.artist }.distinct()
                .sortedWith(String.CASE_INSENSITIVE_ORDER)
                .filter { q.isBlank() || it.contains(q, ignoreCase = true) }
            if (artists.isNotEmpty()) {
                out.add(PickRow.Header("Artists"))
                artists.forEach { a ->
                    val n = songs.count { it.artist == a }
                    out.add(PickRow.Item("a:$a", a, "$n songs"))
                }
            }
            val albums = songs.map { it.album }.distinct()
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
                        key.startsWith("a:") -> ids.addAll(songs.filter { it.artist == key.removePrefix("a:") }.map { it.mediaId })
                        key.startsWith("b:") -> ids.addAll(songs.filter { it.album == key.removePrefix("b:") }.map { it.mediaId })
                        key.startsWith("s:") -> key.removePrefix("s:").toLongOrNull()?.let { ids.add(it) }
                    }
                }
                if (ids.isNotEmpty()) {
                    db.addToPlaylist(playlistId, ids.distinct())
                    if (drillPlaylist == playlistId) render()
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
                            render()
                        }
                        .setNegativeButton("Cancel", null).show()
                }
                3 -> AlertDialog.Builder(this)
                    .setCustomTitle(xpTitle("Delete $name?"))
                    .setMessage("The playlist goes away. Your songs stay.")
                    .setPositiveButton("Delete") { _, _ ->
                        db.deletePlaylist(id)
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
                2 -> inG.forEach { addToQueueEnd(it) }
                3 -> addToPlaylistDialog(inG.map { it.mediaId })
            }
        }
    }

    private fun artistMenu(artist: String) {
        val inA = songs.filter { it.artist == artist }
        xpMenuDialog(artist, listOf("Play all", "Add all to queue", "Add all to playlist")) { which ->
                when (which) {
                    0 -> playQueue(inA, 0)
                    1 -> inA.forEach { addToQueueEnd(it) }
                    2 -> addToPlaylistDialog(inA.map { it.mediaId })
                }
        }
    }

    private fun albumMenu(album: String) {
        val inA = songs.filter { it.album == album }
        xpMenuDialog(album, listOf("Play album", "Add all to queue", "Add all to playlist")) { which ->
                when (which) {
                    0 -> playQueue(inA, 0)
                    1 -> inA.forEach { addToQueueEnd(it) }
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
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        box.addView(nowTitleView)
        nowArtistView = TextView(this).apply {
            text = song?.let { "${it.artist} | ${it.album}" } ?: ""
            textSize = 13f
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
            setOnClickListener { cycleRepeat() }
        }
        shuffleBtn = Button(this).apply {
            background = xpBtnBg()
            setTextColor(iconColor())
            text = getString(R.string.shuffle_off)
            setOnClickListener { shuffleQueue() }
        }
        updateLoopBtn()
        listOf(loopBtn!!, shuffleBtn!!).forEach {
            it.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
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

        queueDialog = AlertDialog.Builder(this)
            .setCustomTitle(xpTitle(getString(R.string.queue_title)))
            .setView(box)
            .setNegativeButton("Save queue") { _, _ ->
                if (queue.isEmpty()) {
                    toast("Queue is empty.")
                    return@setNegativeButton
                }
                createPlaylistDialog(queue.map { it.mediaId })
            }
            .setNeutralButton("Segment Editor") { _, _ ->
                val cur = currentSong()
                if (cur == null) {
                    toast("Play something first.")
                    return@setNeutralButton
                }
                segmentDialog(cur)
            }
            .setPositiveButton("Close", null)
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

    private fun scrollQueueToCurrent() {
        val p = svc?.player ?: return
        val idx = p.currentMediaItemIndex
        if (idx < 0) return
        queueListView?.post { queueListView?.scrollToPosition(idx) }
        findViewById<RecyclerView>(R.id.npQueue)?.let { npqv ->
            npqv.post { npqv.scrollToPosition(idx) }
        }
    }

    private fun refreshQueueList() {
        val p = svc?.player
        val cur = p?.currentMediaItem?.mediaId?.toLongOrNull()
        queueAdapter?.highlightId = cur
        queueAdapter?.submit(queue.map { Row.SongRow(it) })
        landQueueAdapter?.highlightId = cur
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

    private fun buildMorePane() {
        moreBox.removeAllViews()
        moreBox.addView(section("Your music"))
        moreBox.addView(xpBtn("Scan for music").apply {
            setOnClickListener { scan() }
        })
        moreBox.addView(xpBtn("Choose a folder (optional)").apply {
            setOnClickListener {
                runCatching { folderLauncher.launch(null) }
                    .onFailure { toast("No file picker on this device. Scan-all stays on.") }
            }
        })
        val folders = prefs.getStringSet("folders", emptySet())!!
        moreBox.addView(TextView(this).apply {
            text = if (folders.isEmpty())
                "Looks everywhere on this phone automatically (mp3, m4a, opus, ogg, flac, wav)."
            else "Only these folders:\n" + folders.joinToString("\n")
            textSize = 12f
        })
        if (folders.isNotEmpty()) moreBox.addView(xpBtn("Clear folders (scan everything)").apply {
            setOnClickListener {
                prefs.edit().putStringSet("folders", emptySet()).apply()
                buildMorePane()
                scan()
            }
        })

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
            text = "Your volume presets live below. Add as many as you want, with names. Tap one to use it, hold one to delete it. Tap the percent above to type an exact number. Past 150 percent the quality drops, past 500 expect distortion, and nothing is capped, so be careful. The phone hardware sets the final ceiling."
            textSize = 12f
        })
        renderPresets()
        moreBox.addView(xpBtn("Add preset").apply {
            setOnClickListener { presetAddDialog() }
        })
        deviceToggle = xpBtn("").apply {
            setOnClickListener {
                val now = !prefs.getBoolean(VolumeManager.KEY_AUTO_DEVICE, true)
                prefs.edit().putBoolean(VolumeManager.KEY_AUTO_DEVICE, now).apply()
                updateDeviceToggle()
            }
        }
        moreBox.addView(deviceToggle)
        moreBox.addView(TextView(this).apply {
            text = "With auto device on, each Bluetooth gadget keeps its own boost and it comes back when you reconnect."
            textSize = 12f
        })

        moreBox.addView(section("Phone volume guard"))
        moreBox.addView(TextView(this).apply {
            text = "If the phone lowers the music by itself and shows a loud warning, no app can switch that off. The real switch is in the sound settings below. Turn the guard on and it will put the volume back while music plays. If you lower the volume yourself and it fights you, turn the guard back off."
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
            typeface = android.graphics.Typeface.DEFAULT_BOLD
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
            text = "Smart Shuffle plays songs you play less more often, so favorites stop repeating. Turn it off for plain random. The slider sets how harsh that is: gentle lets favorites come back sooner, harsh keeps them away longer."
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
        moreBox.addView(xpBtn("Reset shuffle cycle").apply {
            setOnClickListener {
                db.resetPlays()
                buildMorePane()
                render()
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
            dark = !dark
            prefs.edit().putBoolean("dark", dark).apply()
            applyThemeLive()
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
        val showCounts = prefs.getBoolean("show_counts", false)
        moreBox.addView(xpBtn("Show play counts: ${if (showCounts) "On" else "Off"}").apply {
            setOnClickListener {
                val now = !prefs.getBoolean("show_counts", false)
                prefs.edit().putBoolean("show_counts", now).apply()
                buildMorePane()
                render()
            }
        })
        moreBox.addView(TextView(this).apply {
            text = "With play counts on, each song shows its total plays, plays this cycle, and its chance of coming up next."
            textSize = 12f
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
                val a = byId[id]?.artist ?: return@forEach
                byArtist[a] = (byArtist[a] ?: 0) + c
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
        moreBox.addView(xpBtn("Reset shuffle cycle").apply {
            setOnClickListener {
                db.resetPlays()
                buildMorePane()
            }
        })
        moreBox.addView(TextView(this).apply {
            text = "Starts the Smart Shuffle cycle over. Lifetime totals stay."
            textSize = 12f
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
                        buildMorePane()
                        render()
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
        paintMore()
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
                buildMorePane()
            }
            .setNegativeButton("Cancel", null)
            .show()
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
            pct > 150 -> " (past here quality drops)"
            else -> ""
        }
        boostLabel?.text = "Boost: $pct percent$warn. Tap to type an exact number."
        boostLabel?.setTextColor(if (pct > 150) 0xFFB00020.toInt() else iconColor())
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
            .setMessage("Nothing is capped. High numbers get loud and rough.")
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

    private fun applyThemeLive() {
        skinStatic()
        updateTabs()
        adapter.dark = dark
        adapter.notifyDataSetChanged()
        landQueueAdapter?.dark = dark
        landQueueAdapter?.notifyDataSetChanged()
        buildMorePane()
        refreshMini()
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
        eqJob = CoroutineScope(Dispatchers.Main).launch {
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
        }
    }

    // ---------- small helpers ----------

    private fun iconColor() = if (dark) 0xFFE8E8E8.toInt() else 0xFF000000.toInt()

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
            text = t
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
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
            typeface = android.graphics.Typeface.DEFAULT_BOLD
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
        findViewById<LinearLayout>(R.id.root).setBackgroundColor(
            if (dark) 0xFF23262B.toInt() else 0xFFECE9D8.toInt()
        )
        titleBarWrap.setBackgroundResource(if (dark) R.drawable.xp_title_bar_dark else R.drawable.xp_title_bar)
        findViewById<ImageButton>(R.id.searchToggle)?.setColorFilter(0xFFFFFFFF.toInt())
        findViewById<LinearLayout>(R.id.miniPlayer).setBackgroundResource(
            if (dark) R.drawable.xp_panel_dark else R.drawable.xp_panel
        )
        statusStrip.setBackgroundResource(if (dark) R.drawable.xp_status_dark else R.drawable.xp_status)
        statusStrip.setTextColor(iconColor())
        crumb.setTextColor(if (dark) 0xFFA8ADB5.toInt() else 0xFF5A5A5A.toInt())
        miniTitle.setTextColor(iconColor())
        miniSub.setTextColor(if (dark) 0xFFA8ADB5.toInt() else 0xFF5A5A5A.toInt())
        miniTime.setTextColor(if (dark) 0xFFA8ADB5.toInt() else 0xFF5A5A5A.toInt())
        headerBar.setBackgroundResource(if (dark) R.drawable.xp_title_bar_dark else R.drawable.xp_title_bar)
        listOf(
            R.id.tabSongs, R.id.tabArtists, R.id.tabAlbums, R.id.tabGenres, R.id.tabPlaylists, R.id.tabMore,
            R.id.btnPlayAll
        ).forEach { id ->
            findViewById<Button>(id).setTextColor(if (id == R.id.btnPlayAll) 0xFFFFFFFF.toInt() else iconColor())
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
