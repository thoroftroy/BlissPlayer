package com.blissplayer.music

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.DocumentsContract
import android.view.View
import android.widget.Button
import android.widget.EditText
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
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Player.REPEAT_MODE_ALL
import androidx.media3.common.Player.REPEAT_MODE_OFF
import androidx.media3.common.Player.REPEAT_MODE_ONE
import com.blissplayer.music.data.BlissDb
import com.blissplayer.music.data.MusicScanner
import com.blissplayer.music.data.TagTools
import com.blissplayer.music.model.Song
import com.blissplayer.music.playback.LoudnessScanner
import com.blissplayer.music.playback.PlayerService
import com.blissplayer.music.playback.VolumeManager
import com.blissplayer.music.ui.LibraryAdapter
import com.blissplayer.music.ui.Row
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Collections
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var db: BlissDb
    private var dark = false

    private var songs: List<Song> = emptyList()
    private val byId = mutableMapOf<Long, Song>()

    // Queue mirror — always matches the player item-for-item.
    private val queue = mutableListOf<Song>()
    private var shuffled = false

    // Library view state
    private var tab = 0 // 0 songs 1 artists 2 albums 3 playlists
    private var drillArtist: String? = null
    private var drillAlbum: String? = null
    private var drillPlaylist: Long? = null
    private var drillPlaylistName: String? = null

    private lateinit var adapter: LibraryAdapter
    private lateinit var titleBar: TextView
    private lateinit var crumb: TextView
    private lateinit var list: RecyclerView
    private lateinit var morePane: ScrollView
    private lateinit var moreBox: LinearLayout
    private lateinit var miniTitle: TextView
    private lateinit var miniSub: TextView
    private lateinit var miniPlayPause: Button

    private var svc: PlayerService? = null
    private lateinit var vol: VolumeManager
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
            toast("Picked, but Android didn't give a file path — keeping scan-all.")
            return@registerForActivityResult
        }
        val set = prefs.getStringSet("folders", emptySet())!!.toMutableSet()
        set.add(path)
        prefs.edit().putStringSet("folders", set).apply()
        toast("Added folder: $path")
        buildMorePane()
        scan()
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(n: ComponentName?, b: IBinder?) {
            svc = (b as PlayerService.LocalBinder).service()
            svc!!.boost = vol.boost
            svc!!.applyGainFor(svc!!.player.currentMediaItem)
            svc!!.player.addListener(playerListener)
            refreshMini()
        }

        override fun onServiceDisconnected(n: ComponentName?) {
            svc = null
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
            refreshMini()
            queueDialog?.let { refreshQueueList() }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            miniPlayPause.text = if (isPlaying) "⏸" else "▶"
            nowPlayBtn?.text = if (isPlaying) "⏸ Pause" else "▶ Play"
        }
    }

    // ---------- lifecycle ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = getSharedPreferences("bliss", Context.MODE_PRIVATE)
        dark = prefs.getBoolean("dark", false)
        setTheme(if (dark) R.style.Theme_BlissPlayer_Night else R.style.Theme_BlissPlayer_Day)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        db = BlissDb(this)

        titleBar = findViewById(R.id.titleBar)
        crumb = findViewById(R.id.crumb)
        list = findViewById(R.id.list)
        morePane = findViewById(R.id.morePane)
        moreBox = findViewById(R.id.moreBox)
        miniTitle = findViewById(R.id.miniTitle)
        miniSub = findViewById(R.id.miniSub)
        miniPlayPause = findViewById(R.id.miniPlayPause)

        skinStatic()
        adapter = LibraryAdapter(dark = dark, onTap = ::onRowTap, onLong = ::onRowLong)
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
        findViewById<Button>(R.id.miniNext).setOnClickListener { svc?.player?.seekToNext() }
        findViewById<Button>(R.id.miniQueue).setOnClickListener { openNowPlaying() }
        findViewById<LinearLayout>(R.id.miniPlayer).setOnClickListener { openNowPlaying() }

        vol = VolumeManager(this, db, prefs) { b ->
            svc?.let {
                it.boost = b
                it.applyGainFor(it.player.currentMediaItem)
            }
            updateBoostViews()
        }
        vol.start()

        startService(Intent(this, PlayerService::class.java))
        bindService(
            Intent(this, PlayerService::class.java).setAction("com.blissplayer.music.PLAYER_UI"),
            conn, Context.BIND_AUTO_CREATE
        )

        showTab(0)
        ensurePermissions()
        ticker.post(progressTick)
        buildMorePane()
    }

    override fun onDestroy() {
        ticker.removeCallbacksAndMessages(null)
        runCatching { unbindService(conn) }
        vol.stop()
        eqJob?.cancel()
        super.onDestroy()
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

    private fun scan() {
        if (!hasAudioPermission()) {
            adapter.submit(emptyList())
            crumb.text = "BlissPlayer needs Music permission — grant it, then tap Scan."
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
                render()
                if (filtered.isEmpty()) {
                    crumb.text = if (all.isEmpty())
                        "No music found. Copy your YouTube downloads into /Music, then tap More → Scan."
                    else "No songs in chosen folders. Clear folders in More to scan everything."
                }
            }
        }
    }

    // ---------- tabs / lists ----------

    private fun showTab(t: Int) {
        tab = t
        clearDrill(silent = true)
        morePane.visibility = if (t == 4) View.VISIBLE else View.GONE
        list.visibility = if (t == 4) View.GONE else View.VISIBLE
        if (t == 4) buildMorePane() else render()
    }

    private fun clearDrill(silent: Boolean = false) {
        drillArtist = null
        drillAlbum = null
        drillPlaylist = null
        drillPlaylistName = null
        if (!silent) render()
    }

    private fun render() {
        if (tab == 4) return
        titleBar.text = "BlissPlayer — ${listOf("Songs", "Artists", "Albums", "Playlists")[tab]}"
        when {
            drillArtist != null -> {
                val a = drillArtist!!
                val inA = songs.filter { it.artist == a }
                crumb.text = "◀ $a (${inA.size}) — tap here to go back"
                adapter.submit(inA.map { Row.SongRow(it) })
            }
            drillAlbum != null -> {
                val a = drillAlbum!!
                val inA = songs.filter { it.album == a }
                crumb.text = "◀ $a (${inA.size}) — tap here to go back"
                adapter.submit(inA.map { Row.SongRow(it) })
            }
            drillPlaylist != null -> {
                val ids = db.songsIn(drillPlaylist!!).mapNotNull { byId[it] }
                crumb.text = "◀ ${drillPlaylistName ?: "Playlist"} (${ids.size}) — tap here to go back"
                adapter.submit(ids.map { Row.SongRow(it) })
            }
            tab == 0 -> {
                crumb.text = if (songs.isEmpty()) "" else "${songs.size} songs — tap to play, hold for options"
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
                    Row.Group("al:$k", k, "${v.first().artist} • ${v.size} songs")
                })
            }
            else -> {
                val pls = db.playlists()
                crumb.text = if (pls.isEmpty()) "No playlists yet — tap ＋ below, or hold a song → Add to playlist"
                else "${pls.size} playlists — tap to open, hold to rename/delete"
                val rows = mutableListOf<Row>(
                    Row.Group("__new", "＋ Create playlist", "Keep a mix together")
                )
                pls.forEach { (id, name) ->
                    rows.add(Row.Group("pl:$id", name, "${db.songsIn(id).size} songs"))
                }
                adapter.submit(rows)
            }
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

    private fun onRowLong(row: Row) {
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
            .build()

    private fun playQueue(context: List<Song>, start: Int) {
        val p = svc?.player ?: run { toast("Player starting… try again in a second"); return }
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
            toast("No music yet — copy songs on, then Scan.")
            return
        }
        val listSongs = songs.toMutableList()
        if (shuffle) Collections.shuffle(listSongs)
        shuffled = shuffle
        playQueue(listSongs, 0)
        if (shuffle) toast("Shuffled ${listSongs.size} songs — queue shows the new order")
    }

    /** Shuffle = the queue itself is reordered and the screen shows it. Tap again = re-shuffle. */
    private fun shuffleQueue() {
        val p = svc?.player ?: return
        if (queue.isEmpty()) {
            toast("Queue is empty — play something first")
            return
        }
        val curIdx = p.currentMediaItemIndex.coerceAtLeast(0)
        val cur = queue.getOrNull(curIdx)
        val rest = queue.toMutableList()
        if (cur != null) rest.removeAll { it.mediaId == cur.mediaId }
        Collections.shuffle(rest)
        val wasPlaying = p.isPlaying
        queue.clear()
        if (cur != null) queue.add(cur)
        queue.addAll(rest)
        shuffled = true
        p.setMediaItems(queue.map(::toItem), 0, p.currentPosition)
        p.prepare()
        if (wasPlaying) p.play()
        refreshQueueList()
        toast("Re-shuffled — this is the new play order")
    }

    /** Adding to the queue always lands right after the current song, even shuffled. */
    private fun playNext(song: Song) {
        val p = svc?.player ?: return
        if (queue.isEmpty()) {
            playQueue(listOf(song), 0)
            return
        }
        val at = (p.currentMediaItemIndex + 1).coerceIn(0, queue.size)
        queue.add(at, song)
        p.addMediaItem(at, toItem(song))
        toast("Up next: ${song.title}")
        refreshQueueList()
    }

    private fun addToQueueEnd(song: Song) {
        val p = svc?.player ?: return
        if (queue.isEmpty()) {
            playQueue(listOf(song), 0)
            return
        }
        queue.add(song)
        p.addMediaItem(toItem(song))
        toast("Added to queue: ${song.title}")
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

    // ---------- mini player / ticker ----------

    private fun refreshMini() {
        val p = svc?.player
        val item = p?.currentMediaItem
        val song = item?.mediaId?.toLongOrNull()?.let { byId[it] }
        miniTitle.text = song?.title ?: "Nothing playing"
        miniSub.text = song?.let { "${it.artist} • boost ${(vol.boost * 100).roundToInt()}%" }
            ?: "Tap Play all music to start"
        miniPlayPause.text = if (p?.isPlaying == true) "⏸" else "▶"
    }

    private val progressTick = object : Runnable {
        override fun run() {
            val p = svc?.player
            if (p != null && nowSeek != null && p.duration > 0) {
                nowSeek!!.progress = p.currentPosition.toInt().coerceIn(0, p.duration.toInt())
                nowTime?.text = "${fmt(p.currentPosition)} / ${fmt(p.duration)}"
            }
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
            "▶ Play next (right after this song)",
            "＋ Add to queue",
            "＋ Add to playlist…",
            "✎ Edit details (title, artist…)",
            "🗑 Delete song + file",
            "ℹ Details"
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
            text = "Plain and simple: whatever you type here is what the app (and most other players) will show."
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
                    toast("Saving…")
                    when (val r = TagTools.writeTags(this@MainActivity, song, edit)) {
                        is TagTools.WriteResult.Ok -> {
                            toast("Saved ✓")
                            scan()
                        }
                        is TagTools.WriteResult.NeedsPermission -> {
                            pendingRetry = { tagEditorSaveRetry(song, edit) }
                            senderLauncher.launch(IntentSenderRequest.Builder(r.sender).build())
                        }
                        is TagTools.WriteResult.Failed -> toast("Couldn't save: ${r.msg}")
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
                    toast("Saved ✓")
                    scan()
                }
                is TagTools.WriteResult.NeedsPermission ->
                    senderLauncher.launch(IntentSenderRequest.Builder(r.sender).build())
                is TagTools.WriteResult.Failed -> toast("Couldn't save: ${r.msg}")
            }
        }
    }

    private fun deleteConfirm(song: Song) {
        AlertDialog.Builder(this)
            .setTitle("Delete this song?")
            .setMessage("“${song.title}” will be removed from the app AND the file deleted from your phone. This can't be undone.")
            .setPositiveButton("Delete it") { _, _ ->
                CoroutineScope(Dispatchers.Main).launch {
                    when (val r = TagTools.delete(this@MainActivity, song)) {
                        is TagTools.WriteResult.Ok -> {
                            toast("Deleted")
                            queue.removeAll { it.mediaId == song.mediaId }
                            syncPlayerToQueue()
                            scan()
                        }
                        is TagTools.WriteResult.NeedsPermission -> {
                            pendingRetry = { deleteRetry(song) }
                            senderLauncher.launch(IntentSenderRequest.Builder(r.sender).build())
                        }
                        is TagTools.WriteResult.Failed -> toast("Couldn't delete: ${r.msg}")
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
                    toast("Deleted")
                    scan()
                }
                is TagTools.WriteResult.NeedsPermission ->
                    senderLauncher.launch(IntentSenderRequest.Builder(r.sender).build())
                is TagTools.WriteResult.Failed -> toast("Couldn't delete: ${r.msg}")
            }
        }
    }

    private fun syncPlayerToQueue() {
        val p = svc?.player ?: return
        val pos = p.currentPosition
        p.setMediaItems(queue.map(::toItem), 0, pos)
        p.prepare()
    }

    // ---------- playlists ----------

    private fun createPlaylistDialog(firstSong: Long?) {
        val input = EditText(this).apply { hint = "e.g. Car bangers" }
        AlertDialog.Builder(this)
            .setTitle("New playlist")
            .setView(pad(input))
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().ifBlank { "Playlist" }
                val id = db.createPlaylist(name)
                if (firstSong != null) db.addToPlaylist(id, listOf(firstSong))
                toast("Playlist “$name” ready")
                if (tab == 3) render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addToPlaylistDialog(ids: List<Long>) {
        val pls = db.playlists().toMutableList()
        val names = (pls.map { it.second } + "＋ New playlist").toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Add to playlist")
            .setItems(names) { _, which ->
                if (which == pls.size) createPlaylistDialog(ids.firstOrNull())
                else {
                    db.addToPlaylist(pls[which].first, ids)
                    toast("Added to “${pls[which].second}” ✓")
                    if (tab == 3) render()
                }
            }
            .show()
    }

    private fun playlistMenu(id: Long, name: String) {
        AlertDialog.Builder(this)
            .setTitle(name)
            .setItems(arrayOf("▶ Play this playlist", "✎ Rename", "🗑 Delete playlist")) { _, which ->
                when (which) {
                    0 -> {
                        val listSongs = db.songsIn(id).mapNotNull { byId[it] }
                        if (listSongs.isEmpty()) toast("Playlist is empty (songs may have moved)")
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
                        .setTitle("Delete “$name”?")
                        .setMessage("The playlist goes away, your songs stay.")
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
            .setItems(arrayOf("▶ Play all", "＋ Add all to queue", "＋ Add all to playlist…")) { _, which ->
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
            .setItems(arrayOf("▶ Play album", "＋ Add all to queue", "＋ Add all to playlist…")) { _, which ->
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
    private var nowSeek: SeekBar? = null
    private var nowTime: TextView? = null
    private var nowPlayBtn: Button? = null
    private var loopBtn: Button? = null
    private var shuffleBtn: Button? = null

    private fun openNowPlaying() {
        val p = svc?.player
        if (p == null || queue.isEmpty()) {
            toast("Nothing in the queue — tap a song or Play all music")
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
            text = song?.let { "${it.artist} • ${it.album}" } ?: ""
            textSize = 13f
        })
        nowSeek = SeekBar(this).apply {
            max = (p.duration.coerceAtLeast(0)).toInt()
            progress = p.currentPosition.toInt().coerceIn(0, max)
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
        val bPrev = Button(this).apply { text = "⏮"; setOnClickListener { p.seekToPrevious() } }
        nowPlayBtn = Button(this).apply {
            text = if (p.isPlaying) "⏸ Pause" else "▶ Play"
            setOnClickListener { togglePlay() }
        }
        val bNext = Button(this).apply { text = "⏭"; setOnClickListener { p.seekToNext() } }
        listOf(bPrev, nowPlayBtn!!, bNext).forEach {
            it.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            row1.addView(it)
        }
        box.addView(row1)

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        loopBtn = Button(this).apply { setOnClickListener { cycleRepeat() } }
        shuffleBtn = Button(this).apply {
            setOnClickListener {
                shuffleQueue()
                text = shuffleLabel()
            }
        }
        updateLoopBtn()
        shuffleBtn!!.text = shuffleLabel()
        listOf(loopBtn!!, shuffleBtn!!).forEach {
            it.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            row2.addView(it)
        }
        box.addView(row2)

        box.addView(TextView(this).apply {
            text = "What plays next — this list IS the play order."
            textSize = 12f
        })
        val qv = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(220)
            )
        }
        queueAdapter = LibraryAdapter(
            dark = dark,
            rows = queue.map { Row.SongRow(it) },
            onTap = { r ->
                val idx = queue.indexOfFirst { it.mediaId == (r as Row.SongRow).song.mediaId }
                if (idx >= 0) p.seekTo(idx, 0)
            },
            onLong = { r ->
                val s = (r as Row.SongRow).song
                queue.removeAll { it.mediaId == s.mediaId }
                syncPlayerToQueue()
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
                Collections.swap(queue, a, b)
                queueAdapter!!.notifyItemMoved(a, b)
                return true
            }

            override fun onSwiped(h: RecyclerView.ViewHolder, dir: Int) {
                val pos = h.bindingAdapterPosition
                queue.removeAt(pos)
                syncPlayerToQueue()
                refreshQueueList()
            }
        }).attachToRecyclerView(qv)
        box.addView(qv)

        queueDialog = AlertDialog.Builder(this)
            .setView(box)
            .setPositiveButton("Close", null)
            .setOnDismissListener {
                queueDialog = null
                nowSeek = null
                nowTime = null
                nowPlayBtn = null
            }
            .show()
    }

    private fun refreshQueueList() {
        queueAdapter?.submit(queue.map { Row.SongRow(it) })
        queueAdapter?.dark = dark
    }

    private fun shuffleLabel() =
        if (shuffled) getString(R.string.shuffle_on) else getString(R.string.shuffle_off)

    private fun updateLoopBtn() {
        val p = svc?.player ?: return
        loopBtn?.text = when (p.repeatMode) {
            REPEAT_MODE_ALL -> getString(R.string.loop_all)
            REPEAT_MODE_ONE -> getString(R.string.loop_one)
            else -> getString(R.string.loop_off)
        }
    }

    // ---------- More pane (settings, boost, equalize, help) ----------

    private var boostSeek: SeekBar? = null
    private var boostLabel: TextView? = null
    private var deviceLabel: TextView? = null

    private fun buildMorePane() {
        moreBox.removeAllViews()
        moreBox.addView(section("Your music"))
        moreBox.addView(xpBtn("Scan for music").apply {
            setOnClickListener {
                toast("Scanning…")
                scan()
            }
        })
        moreBox.addView(xpBtn("Choose a folder (optional)").apply {
            setOnClickListener {
                runCatching { folderLauncher.launch(null) }
                    .onFailure { toast("No file picker on this device — scan-all stays on.") }
            }
        })
        val folders = prefs.getStringSet("folders", emptySet())!!
        moreBox.addView(TextView(this).apply {
            text = if (folders.isEmpty())
                "Looking everywhere on this phone automatically (mp3, m4a, opus, ogg, flac, wav…)."
            else "Only these folders:\n" + folders.joinToString("\n") + "\n(tap Clear to go back to everywhere)"
            textSize = 12f
        })
        if (folders.isNotEmpty()) moreBox.addView(xpBtn("Clear folders (scan everything)").apply {
            setOnClickListener {
                prefs.edit().putStringSet("folders", emptySet()).apply()
                buildMorePane()
                scan()
            }
        })

        moreBox.addView(section("Boost — hear it in the car"))
        deviceLabel = TextView(this).apply { textSize = 13f }
        moreBox.addView(deviceLabel)
        boostLabel = TextView(this).apply { textSize = 14f }
        moreBox.addView(boostLabel)
        boostSeek = SeekBar(this).apply {
            max = 375 // 25%..400%
            progress = ((vol.boost * 100).roundToInt() - 25).coerceIn(0, 375)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) {
                    if (fromUser) vol.setBoost((v + 25) / 100f)
                }

                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        moreBox.addView(boostSeek)
        val presets = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        mapOf("Quiet 60%" to 0.6f, "Headphones 100%" to 1.0f, "Car 200%" to 2.0f).forEach { (k, v) ->
            presets.addView(xpBtn(k).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { vol.setBoost(v) }
            })
        }
        moreBox.addView(presets)
        moreBox.addView(TextView(this).apply {
            text = "Your car remembers its own boost, headphones remember theirs. Past 150% the app caps harsh peaks so nothing crackles."
            textSize = 12f
        })
        updateBoostViews()

        moreBox.addView(section("One-tap loudness fix"))
        moreBox.addView(TextView(this).apply {
            text = "YouTube songs are all over the place volume-wise. Press the button once: BlissPlayer listens to every song, finds the average, turns the quiet ones up and the loud ones down. Nothing is rewritten and you can undo it."
            textSize = 12f
        })
        moreBox.addView(xpBtn(getString(R.string.equalize_all)).apply {
            setOnClickListener { equalizeConfirm() }
        })
        moreBox.addView(xpBtn(getString(R.string.equalize_undo)).apply {
            setOnClickListener {
                db.clearGains()
                svc?.refreshGains()
                toast("Equalize undone — back to original volumes")
            }
        })

        moreBox.addView(section("Look & feel"))
        val themeBtn = xpBtn(if (dark) "Switch to light XP" else "Switch to dark XP")
        themeBtn.setOnClickListener {
            prefs.edit().putBoolean("dark", !dark).apply()
            recreate()
        }
        moreBox.addView(themeBtn)

        moreBox.addView(section(getString(R.string.help_title)))
        moreBox.addView(TextView(this).apply {
            text = "1. Copy songs anywhere on the phone (Music folder is fine).\n" +
                "2. Tap Scan for music.\n" +
                "3. Tap ▶ Play all music — or Shuffle everything.\n" +
                "4. In the car? Set Boost to ~200% once; BlissPlayer remembers it.\n" +
                "5. Volumes jumping between songs? Run Equalize once.\n" +
                "6. Hold any song for play-next, playlists, edit, delete."
            textSize = 13f
        })
        moreBox.addView(TextView(this).apply {
            text = "BlissPlayer v0.1 — tiny, offline, no ads, no accounts. Placeholder icon until yours lands."
            textSize = 11f
        })
    }

    private fun updateBoostViews() {
        val pct = (vol.boost * 100).roundToInt()
        boostLabel?.text = "Boost: $pct%" + if (pct > 150) " — careful, loud!" else ""
        boostSeek?.let { if ((it.progress + 25) != pct) it.progress = (pct - 25).coerceIn(0, 375) }
        deviceLabel?.text = "Output: ${vol.currentDevice} (auto-remembers per device)"
        refreshMini()
    }

    private fun equalizeConfirm() {
        if (songs.isEmpty()) {
            toast("Scan your music first")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Equalize ${songs.size} songs?")
            .setMessage("Takes a few minutes in the background. You can keep using the app. Nothing about your files changes — Undo is one tap.")
            .setPositiveButton("Equalize") { _, _ -> runEqualize() }
            .setNegativeButton("Not now", null)
            .show()
    }

    private fun runEqualize() {
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressDrawable = ContextCompat.getDrawable(this@MainActivity, R.drawable.xp_progress)
        }
        val label = TextView(this).apply { text = "Starting…" }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            addView(label)
            addView(bar)
        }
        val dlg = AlertDialog.Builder(this)
            .setTitle("Equalizing…")
            .setView(box)
            .setNegativeButton("Cancel") { _, _ -> eqJob?.cancel() }
            .setCancelable(false)
            .show()
        eqJob = CoroutineScope(Dispatchers.Main).launch {
            val res = LoudnessScanner.equalizeAll(
                this@MainActivity, songs,
                putGain = { id, mult -> db.putGain(id, mult) },
                onProgress = { done, total, title ->
                    bar.max = total
                    bar.progress = done
                    label.text = "Listening: $title"
                }
            )
            runCatching { dlg.dismiss() }
            svc?.refreshGains()
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Done ✓")
                .setMessage(
                    "Checked ${res.measured} songs.\nTurned up ${res.raised}, turned down ${res.lowered}" +
                        (if (res.skipped > 0) ", skipped ${res.skipped} (couldn't read)." else ".") +
                        "\nFrom now on everything plays at the same average volume — on top of your Boost."
                )
                .setPositiveButton("Nice", null)
                .show()
        }
    }

    // ---------- small helpers ----------

    private fun section(t: String): TextView =
        TextView(this).apply {
            text = t
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, dp(12), 0, dp(4))
        }

    private fun xpBtn(t: String): Button =
        Button(this, null, android.R.attr.buttonStyle).apply {
            text = t
            background = ContextCompat.getDrawable(
                this@MainActivity,
                if (dark) R.drawable.xp_button_dark else R.drawable.xp_button
            )
            setTextColor(if (dark) 0xFFE8E8E8.toInt() else 0xFF000000.toInt())
        }

    private fun pad(v: View): View =
        LinearLayout(this).apply {
            setPadding(dp(16), dp(8), dp(16), dp(8))
            addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

    private fun dp(n: Int): Int = (n * resources.displayMetrics.density).toInt()

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    private fun skinStatic() {
        titleBar.setBackgroundResource(if (dark) R.drawable.xp_title_bar_dark else R.drawable.xp_title_bar)
        findViewById<LinearLayout>(R.id.miniPlayer).setBackgroundResource(
            if (dark) R.drawable.xp_panel_dark else R.drawable.xp_panel
        )
        listOf(
            R.id.tabSongs, R.id.tabArtists, R.id.tabAlbums, R.id.tabPlaylists, R.id.tabMore,
            R.id.btnShuffleAll, R.id.miniPlayPause, R.id.miniNext, R.id.miniQueue
        ).forEach { id ->
            findViewById<Button>(id).setBackgroundResource(
                if (dark) R.drawable.xp_button_dark else R.drawable.xp_button
            )
        }
    }

    /** content://…/tree/primary:Music → /storage/emulated/0/Music (primary volume only). */
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
