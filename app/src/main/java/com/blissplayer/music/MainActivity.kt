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
import com.blissplayer.music.model.Song
import com.blissplayer.music.playback.LoudnessScanner
import com.blissplayer.music.playback.PlayerService
import com.blissplayer.music.playback.SafeVolumeGuard
import com.blissplayer.music.playback.VolumeManager
import com.blissplayer.music.ui.LibraryAdapter
import com.blissplayer.music.ui.Row
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
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

    // Library view state
    private var tab = 0 // 0 songs 1 artists 2 albums 3 playlists
    private var drillArtist: String? = null
    private var drillAlbum: String? = null
    private var drillPlaylist: Long? = null
    private var drillPlaylistName: String? = null

    private lateinit var adapter: LibraryAdapter
    private lateinit var titleBar: TextView
    private lateinit var titleBarWrap: LinearLayout
    private lateinit var crumb: TextView
    private lateinit var headerBar: TextView
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
            onTrackChanged(item)
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
            setPlayBtnIcon(isPlaying)
            refreshMini()
        }
    }

    private fun onTrackChanged(item: MediaItem?) {
        refreshMini()
        queueDialog?.let { refreshQueueList() }
        val id = item?.mediaId?.toLongOrNull()
        if (id != null && id != lastCountedId) {
            lastCountedId = id
            CoroutineScope(Dispatchers.IO).launch {
                db.bumpPlay(id)
                // Once every song in the library has played at least once,
                // wipe the slate so Smart Shuffle stays fresh forever.
                val counts = db.playCounts()
                if (songs.isNotEmpty() && songs.all { (counts[it.mediaId] ?: 0) > 0 }) {
                    db.resetPlays()
                }
            }
        }
    }

    // ---------- lifecycle ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = getSharedPreferences("bliss", Context.MODE_PRIVATE)
        dark = prefs.getBoolean("dark", false)
        setTheme(if (dark) R.style.Theme_BlissPlayer_Night else R.style.Theme_BlissPlayer_Day)
        super.onCreate(savedInstanceState)
        CrashReporter.install(this)
        val lastCrash = CrashReporter.read(this)
        setContentView(R.layout.activity_main)
        db = BlissDb(this)

        restoredQueueIds = savedInstanceState?.getLongArray("queue")
        lastCountedId = savedInstanceState?.getLong("counted", -1L) ?: -1L

        titleBarWrap = findViewById(R.id.titleBarWrap)
        titleBar = findViewById(R.id.titleBar)
        crumb = findViewById(R.id.crumb)
        headerBar = findViewById(R.id.headerBar)
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

        findViewById<Button>(R.id.tabSongs).setOnClickListener { showTab(0) }
        findViewById<Button>(R.id.tabArtists).setOnClickListener { showTab(1) }
        findViewById<Button>(R.id.tabAlbums).setOnClickListener { showTab(2) }
        findViewById<Button>(R.id.tabPlaylists).setOnClickListener { showTab(3) }
        findViewById<Button>(R.id.tabMore).setOnClickListener { showTab(4) }
        findViewById<Button>(R.id.btnPlayAll).setOnClickListener { playAll(shuffle = false) }
        findViewById<Button>(R.id.btnShuffleAll).setOnClickListener { playAll(shuffle = true) }
        crumb.setOnClickListener { clearDrill() }
        miniPlayPause.setOnClickListener { togglePlay() }
        findViewById<ImageButton>(R.id.miniNext).setOnClickListener { skipNext() }
        findViewById<ImageButton>(R.id.miniQueue).setOnClickListener { openNowPlaying() }
        findViewById<LinearLayout>(R.id.miniPlayer).setOnClickListener { openNowPlaying() }

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
    }

    override fun onDestroy() {
        ticker.removeCallbacksAndMessages(null)
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
            .setTitle("The app crashed last time")
            .setMessage("Copy this and send it to the developer so it can be fixed:")
            .setView(sv)
            .setPositiveButton("Copy") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("crash", log))
                toast("Copied. Paste it in chat.")
            }
            .setNegativeButton("Clear") { _, _ -> CrashReporter.clear(this) }
            .setNeutralButton("Close", null)
            .show()
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
                restoreQueueIfNeeded()
                if (pendingQueueSync) {
                    pendingQueueSync = false
                    syncQueueFromPlayer()
                }
                render()
                updateStatus()
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

    /** If mirror and player disagree in size, the player wins. */
    private fun ensureQueueSync() {
        val p = svc?.player ?: return
        if (p.mediaItemCount != queue.size) syncQueueFromPlayer()
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
        morePane.visibility = if (t == 4) View.VISIBLE else View.GONE
        list.visibility = if (t == 4) View.GONE else View.VISIBLE
        updateTabs()
        if (t == 4) buildMorePane() else render()
    }

    private val tabIds = listOf(R.id.tabSongs, R.id.tabArtists, R.id.tabAlbums, R.id.tabPlaylists, R.id.tabMore)

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
        drillPlaylist = null
        drillPlaylistName = null
        if (!silent) render()
    }

    private fun showingSongs(): Boolean =
        tab == 0 || drillArtist != null || drillAlbum != null || drillPlaylist != null

    private fun render() {
        if (tab == 4) return
        titleBar.text = "BlissPlayer: ${listOf("Songs", "Artists", "Albums", "Playlists")[tab]}"
        headerBar.visibility = if (showingSongs()) View.VISIBLE else View.GONE
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
            drillPlaylist != null -> {
                val ids = db.songsIn(drillPlaylist!!).mapNotNull { byId[it] }
                crumb.text = "${drillPlaylistName ?: "Playlist"} (${ids.size}) | tap here to go back"
                adapter.submit(ids.map { Row.SongRow(it) })
            }
            tab == 0 -> {
                crumb.text = if (songs.isEmpty()) "" else "${songs.size} songs. Tap to play, dots for options."
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
        updateStatus()
    }

    private fun updateStatus() {
        val pct = (vol.boost * 100).roundToInt()
        statusStrip.text = "${songs.size} songs | Boost $pct percent | ${vol.currentDevice}"
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
                row.key == "__new" -> createPlaylistDialog(null)
                row.key.startsWith("ar:") -> {
                    drillArtist = row.key.removePrefix("ar:")
                    render()
                }
                row.key.startsWith("al:") -> {
                    drillAlbum = row.key.removePrefix("al:")
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
                row.key == "__new" -> createPlaylistDialog(null)
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

    private fun playAll(shuffle: Boolean) {
        if (songs.isEmpty()) {
            toast("No music yet. Copy songs on, then Scan.")
            return
        }
        val p = svc?.player ?: run { toast("Player is starting. Try again in a second."); return }
        if (shuffle) {
            val ordered = smartOrder(songs)
            val cur = queue.getOrNull(p.currentMediaItemIndex.coerceAtLeast(0))
            if (p.mediaItemCount > 0 && cur != null && ordered.any { it.mediaId == cur.mediaId }) {
                // Gapless path: keep the playing song, swap everything behind it.
                queue.clear()
                queue.addAll(ordered)
                shuffled = true
                replaceTail(ordered.filter { it.mediaId != cur.mediaId })
                refreshQueueList()
                return
            }
            queue.clear()
            queue.addAll(ordered)
            shuffled = true
            p.setMediaItems(ordered.map(::toItem), 0, 0)
            p.repeatMode = REPEAT_MODE_OFF
            p.prepare()
            p.play()
        } else {
            queue.clear()
            queue.addAll(songs)
            shuffled = false
            p.setMediaItems(songs.map(::toItem), 0, 0)
            p.repeatMode = REPEAT_MODE_OFF
            p.prepare()
            p.play()
        }
        refreshMini()
    }

    /**
     * Smart Shuffle. Each song carries a lifetime play count. Songs played
     * less get a bigger slice of the odds, so the mix stays fresh instead
     * of repeating favorites. When everything has played once, counts reset.
     */
    private fun smartOrder(list: List<Song>): List<Song> {
        val counts = db.playCounts()
        val rnd = Random(System.currentTimeMillis())
        return list.map { s ->
            val weight = 1.0 / (1 + (counts[s.mediaId] ?: 0))
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
            ?: "Tap Play all music to start"
        miniPlayPause.setImageResource(if (p?.isPlaying == true) R.drawable.ic_pause else R.drawable.ic_play)
        adapter.highlightId = song?.mediaId
        if (tab != 4) adapter.notifyDataSetChanged()
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
            nowSeek?.let {
                it.max = p.duration.toInt()
                it.progress = pos.toInt().coerceIn(0, it.max)
            }
            nowTime?.text = "${fmt(pos)} / ${fmt(p.duration)}"
        } else {
            miniProgress.progress = 0
            miniTime.text = "0:00"
        }
    }

    private val progressTick = object : Runnable {
        override fun run() {
            updateProgressViews()
            ticker.postDelayed(this, 500)
        }
    }

    private fun fmt(ms: Long): String {
        val s = (ms / 1000).toInt().coerceAtLeast(0)
        return "%d:%02d".format(s / 60, s % 60)
    }

    // ---------- song menu / tags / delete ----------

    private fun songMenu(song: Song) {
        val items = arrayOf(
            "Play next",
            "Add to queue",
            "Add to playlist",
            "Edit details",
            "Delete song and file",
            "Details"
        )
        AlertDialog.Builder(this)
            .setTitle(song.title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> playNext(song)
                    1 -> addToQueueEnd(song)
                    2 -> addToPlaylistDialog(listOf(song.mediaId))
                    3 -> tagEditorDialog(song)
                    4 -> deleteConfirm(song)
                    5 -> AlertDialog.Builder(this)
                        .setTitle(song.title)
                        .setMessage(
                            "Artist: ${song.artist}\nAlbum: ${song.album}\n" +
                                "Length: ${fmt(song.durationMs)}\nSize: ${song.sizeBytes / 1024} KB\n" +
                                (song.filePath ?: "")
                        )
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
            .show()
    }

    private fun tagEditorDialog(song: Song) {
        val pad = dp(16)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun field(label: String, value: String): EditText {
            box.addView(TextView(this).apply { text = label })
            return EditText(this).apply {
                setText(value)
                box.addView(this)
            }
        }
        val eTitle = field("Title", song.title)
        val eArtist = field("Artist", song.artist)
        val eAlbum = field("Album", song.album)
        box.addView(TextView(this).apply {
            text = "Whatever you type here is what BlissPlayer and most other players will show."
            textSize = 12f
        })
        AlertDialog.Builder(this)
            .setTitle("Edit details")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val edit = TagTools.Edit(
                    eTitle.text.toString().ifBlank { song.title },
                    eArtist.text.toString().ifBlank { song.artist },
                    eAlbum.text.toString().ifBlank { song.album },
                    null, null, null
                )
                CoroutineScope(Dispatchers.Main).launch {
                    when (val r = TagTools.writeTags(this@MainActivity, song, edit)) {
                        is TagTools.WriteResult.Ok -> scan()
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
                is TagTools.WriteResult.Ok -> scan()
                is TagTools.WriteResult.NeedsPermission ->
                    senderLauncher.launch(IntentSenderRequest.Builder(r.sender).build())
                is TagTools.WriteResult.Failed -> toast("Could not save: ${r.msg}")
            }
        }
    }

    private fun deleteConfirm(song: Song) {
        AlertDialog.Builder(this)
            .setTitle("Delete this song?")
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

    private fun createPlaylistDialog(firstSong: Long?) {
        val input = EditText(this).apply { hint = "Example: Gym mix" }
        AlertDialog.Builder(this)
            .setTitle("New playlist")
            .setView(pad(input))
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().ifBlank { "Playlist" }
                val id = db.createPlaylist(name)
                if (firstSong != null) db.addToPlaylist(id, listOf(firstSong))
                if (tab == 3) render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addToPlaylistDialog(ids: List<Long>) {
        val pls = db.playlists().toMutableList()
        val names = (pls.map { it.second } + "New playlist").toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Add to playlist")
            .setItems(names) { _, which ->
                if (which == pls.size) createPlaylistDialog(ids.firstOrNull())
                else {
                    db.addToPlaylist(pls[which].first, ids)
                    if (tab == 3) render()
                }
            }
            .show()
    }

    private fun playlistMenu(id: Long, name: String) {
        AlertDialog.Builder(this)
            .setTitle(name)
            .setItems(arrayOf("Play this playlist", "Rename", "Delete playlist")) { _, which ->
                when (which) {
                    0 -> {
                        val listSongs = db.songsIn(id).mapNotNull { byId[it] }
                        if (listSongs.isEmpty()) toast("Playlist is empty. Its songs may have moved.")
                        else playQueue(listSongs, 0)
                    }
                    1 -> {
                        val input = EditText(this).apply { setText(name) }
                        AlertDialog.Builder(this).setTitle("Rename")
                            .setView(pad(input))
                            .setPositiveButton("Save") { _, _ ->
                                db.renamePlaylist(id, input.text.toString().ifBlank { name })
                                render()
                            }
                            .setNegativeButton("Cancel", null).show()
                    }
                    2 -> AlertDialog.Builder(this)
                        .setTitle("Delete $name?")
                        .setMessage("The playlist goes away. Your songs stay.")
                        .setPositiveButton("Delete") { _, _ ->
                            db.deletePlaylist(id)
                            render()
                        }
                        .setNegativeButton("Keep", null).show()
                }
            }
            .show()
    }

    private fun artistMenu(artist: String) {
        val inA = songs.filter { it.artist == artist }
        AlertDialog.Builder(this)
            .setTitle(artist)
            .setItems(arrayOf("Play all", "Add all to queue", "Add all to playlist")) { _, which ->
                when (which) {
                    0 -> playQueue(inA, 0)
                    1 -> inA.forEach { addToQueueEnd(it) }
                    2 -> addToPlaylistDialog(inA.map { it.mediaId })
                }
            }
            .show()
    }

    private fun albumMenu(album: String) {
        val inA = songs.filter { it.album == album }
        AlertDialog.Builder(this)
            .setTitle(album)
            .setItems(arrayOf("Play album", "Add all to queue", "Add all to playlist")) { _, which ->
                when (which) {
                    0 -> playQueue(inA, 0)
                    1 -> inA.forEach { addToQueueEnd(it) }
                    2 -> addToPlaylistDialog(inA.map { it.mediaId })
                }
            }
            .show()
    }

    // ---------- Now Playing (controls + true-order queue) ----------

    private var queueDialog: AlertDialog? = null
    private var queueAdapter: LibraryAdapter? = null
    private var queueListView: RecyclerView? = null
    private var nowSeek: SeekBar? = null
    private var nowTime: TextView? = null
    private var nowPlayBtn: Button? = null
    private var loopBtn: Button? = null
    private var shuffleBtn: Button? = null

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
        box.addView(TextView(this).apply {
            text = song?.title ?: "Now playing"
            textSize = 18f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        box.addView(TextView(this).apply {
            text = song?.let { "${it.artist} | ${it.album}" } ?: ""
            textSize = 13f
        })
        nowSeek = SeekBar(this).apply {
            max = (p.duration.coerceAtLeast(0)).toInt()
            progress = p.currentPosition.toInt().coerceIn(0, max)
            progressDrawable = xpProgress()
            thumb = xpThumb()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) {
                    if (fromUser) p.seekTo(v.toLong())
                }

                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        box.addView(nowSeek)
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
        queueAdapter = LibraryAdapter(
            dark = dark,
            rows = queue.map { Row.SongRow(it) },
            highlightId = p.currentMediaItem?.mediaId?.toLongOrNull(),
            onTap = { r ->
                ensureQueueSync()
                val idx = queue.indexOfFirst { it.mediaId == (r as Row.SongRow).song.mediaId }
                if (idx >= 0) p.seekTo(idx, 0)
            },
            onMenu = { r ->
                val s = (r as Row.SongRow).song
                queue.removeAll { it.mediaId == s.mediaId }
                removeFromPlayer(s.mediaId)
                refreshQueueList()
            }
        )
        qv.adapter = queueAdapter
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        ) {
            override fun onMove(rv: RecyclerView, h: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder): Boolean {
                val a = h.bindingAdapterPosition
                val b = t.bindingAdapterPosition
                java.util.Collections.swap(queue, a, b)
                queueAdapter!!.notifyItemMoved(a, b)
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
        }).attachToRecyclerView(qv)
        box.addView(qv)

        queueDialog = AlertDialog.Builder(this)
            .setTitle(R.string.queue_title)
            .setView(box)
            .setPositiveButton("Close", null)
            .setOnDismissListener {
                queueDialog = null
                queueListView = null
                nowSeek = null
                nowTime = null
                nowPlayBtn = null
            }
            .show()
        scrollQueueToCurrent()
    }

    private fun scrollQueueToCurrent() {
        val p = svc?.player ?: return
        val qv = queueListView ?: return
        val idx = p.currentMediaItemIndex
        if (idx >= 0) qv.scrollToPosition(idx)
    }

    private fun refreshQueueList() {
        val p = svc?.player
        queueAdapter?.highlightId = p?.currentMediaItem?.mediaId?.toLongOrNull()
        queueAdapter?.submit(queue.map { Row.SongRow(it) })
        scrollQueueToCurrent()
    }

    private fun updateLoopBtn() {
        val p = svc?.player ?: return
        loopBtn?.text = when (p.repeatMode) {
            REPEAT_MODE_ALL -> getString(R.string.loop_all)
            REPEAT_MODE_ONE -> getString(R.string.loop_one)
            else -> getString(R.string.loop_off)
        }
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
            text = "Your volume presets live below. Add as many as you want, with names. Tap one to use it, hold one to delete it. Tap the percent above to type an exact number. Past 150 percent the quality drops, past 500 expect distortion, and nothing is capped, so be careful."
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

        moreBox.addView(section("Look and feel"))
        val themeBtn = xpBtn(if (dark) "Light XP mode" else "Dark XP mode")
        themeBtn.setOnClickListener {
            dark = !dark
            prefs.edit().putBoolean("dark", dark).apply()
            applyThemeLive()
        }
        moreBox.addView(themeBtn)

        moreBox.addView(section("Help"))
        moreBox.addView(TextView(this).apply {
            text = "1. Copy songs anywhere on the phone. The Music folder is fine.\n" +
                "2. Open More and tap Scan for music.\n" +
                "3. Tap Play all music, or Shuffle.\n" +
                "4. If one output is much quieter than the rest, raise Boost for it. Each output remembers its own.\n" +
                "5. Volumes jumping between songs? Run Equalize once.\n" +
                "6. Tap the three dots on any song for play next, playlists, edit, delete."
            textSize = 13f
        })
        moreBox.addView(TextView(this).apply {
            text = "BlissPlayer 0.5. Small, offline, no accounts, no ads."
            textSize = 11f
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
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Delete preset $name?")
                        .setPositiveButton("Delete") { _, _ ->
                            db.deletePreset(name)
                            buildMorePane()
                        }
                        .setNegativeButton("Keep", null)
                        .show()
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

    private fun presetAddDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val eName = EditText(this).apply { hint = "Name, like Quiet room" }
        val ePct = EditText(this).apply {
            hint = "Percent, 1 to 2000"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        box.addView(eName)
        box.addView(ePct)
        AlertDialog.Builder(this)
            .setTitle("New preset")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val name = eName.text.toString().ifBlank { "Preset" }
                val pct = ePct.text.toString().toIntOrNull()?.coerceIn(1, 2000) ?: 100
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
            .setTitle("Exact boost percent")
            .setMessage("Nothing is capped. High numbers get loud and rough.")
            .setView(pad(input))
            .setPositiveButton("Set") { _, _ ->
                val pct = input.text.toString().toIntOrNull()?.coerceIn(1, 2000) ?: 100
                vol.setBoost(pct / 100f)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun applyThemeLive() {
        skinStatic()
        updateTabs()
        adapter.dark = dark
        adapter.notifyDataSetChanged()
        buildMorePane()
        refreshMini()
    }

    private fun equalizeConfirm() {
        if (songs.isEmpty()) {
            toast("Scan your music first.")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Equalize ${songs.size} songs?")
            .setMessage("This runs in the background and you can keep using the app. Nothing about your files changes, and Undo is one tap.")
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
            .setTitle("Equalizing")
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
            val skippedTxt = if (res.skipped > 0) ", skipped ${res.skipped}" else ""
            val reusedTxt = if (res.reused > 0) " ${res.reused} reused from last time." else ""
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Done")
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
            R.id.tabSongs, R.id.tabArtists, R.id.tabAlbums, R.id.tabPlaylists, R.id.tabMore,
            R.id.btnShuffleAll, R.id.btnPlayAll
        ).forEach { id ->
            findViewById<Button>(id).setTextColor(if (id == R.id.btnPlayAll) 0xFFFFFFFF.toInt() else iconColor())
        }
        updateTabs()
        findViewById<Button>(R.id.btnShuffleAll).setBackgroundResource(
            if (dark) R.drawable.xp_button_dark else R.drawable.xp_button
        )
        mapOf(
            R.id.miniPlayPause to R.drawable.ic_play,
            R.id.miniNext to R.drawable.ic_next,
            R.id.miniQueue to R.drawable.ic_queue
        ).forEach { (id, icon) ->
            findViewById<ImageButton>(id).apply {
                setBackgroundResource(if (dark) R.drawable.xp_button_dark else R.drawable.xp_button)
                setImageResource(icon)
                setColorFilter(iconColor())
            }
        }
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
