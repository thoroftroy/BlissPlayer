package com.blissplayer.desktop

import com.blissplayer.core.LoopMode
import com.blissplayer.core.PlayerEngine
import com.blissplayer.core.QueueItem
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Linux playback behind the shared PlayerEngine contract: one persistent
 * `cvlc --intf rc` process driven over its TCP control socket.
 * Same queue/loop/resume/skip-segment rules as Media3; VLC owns decoding
 * (m4a/mp3/flac/ogg all play) and publishes MPRIS itself when a session
 * bus exists. Speed is approximated to VLC's slower/normal/faster steps.
 */
class DesktopPlayer(private val dummyAudio: Boolean = false) : PlayerEngine {
    var onError: (String) -> Unit = {}
    var onState: () -> Unit = {}

    private var proc: Process? = null
    private var sock: Socket? = null
    private val lock = Any()
    private var queue: List<QueueItem> = emptyList()
    private var index = -1
    private var loop = LoopMode.ALL
    private var speed = 1f
    private var volMult = 1.0
    private var playing = false
    private var lengthSec = 0L
    private var segments: Map<String, List<Pair<Long, Long>>> = emptyMap()
    private val monitor = AtomicBoolean(false)
    private var lastPosMs = 0L

    val queueSize: Int get() = queue.size
    val queueIndex: Int get() = index
    fun itemAt(i: Int): QueueItem? = queue.getOrNull(i)
    fun setSegments(key: String, segs: List<Pair<Long, Long>>) { segments = segments + (key to segs) }

    private fun vlcBinary(): String? =
        listOf("cvlc", "vlc").firstOrNull { runCatching {
            ProcessBuilder("which", it).start().waitFor() == 0
        }.getOrDefault(false) }

    private fun freePort(): Int {
        java.net.ServerSocket(0).use { return it.localPort }
    }

    private fun ensureRunning(): Boolean {
        if (proc?.isAlive == true && sock?.isConnected == true) return true
        release()
        val bin = vlcBinary() ?: run { onError("Install VLC (cvlc) for Linux playback."); return false }
        val port = freePort()
        val extra = if (System.getenv("DBUS_SESSION_BUS_ADDRESS") != null) arrayOf("--extraintf", "dbus") else emptyArray()
        return try {
            val audio = if (dummyAudio) arrayOf("--aout", "dummy") else emptyArray()
            proc = ProcessBuilder(bin, "--intf", "rc", "--rc-host", "127.0.0.1:$port",
                "--no-video", "--no-qt-bgcone", *audio, *extra)
                .redirectErrorStream(true).start()
            // Drain the banner so the socket buffer never blocks playback.
            thread(isDaemon = true, name = "vlc-drain") {
                runCatching { proc?.inputStream?.bufferedReader()?.forEachLine { } }
            }
            var s: Socket? = null
            for (attempt in 0 until 60) {
                Thread.sleep(250)
                s = runCatching { Socket("127.0.0.1", port).also { it.soTimeout = 5000 } }.getOrNull()
                if (s != null) break
            }
            sock = s ?: run { onError("VLC control socket never opened."); release(); return false }
            synchronized(lock) {
                val r = sock!!.getInputStream().bufferedReader()
                // Consume the banner + first prompt.
                readUntilPrompt(r)
            }
            startMonitor()
            true
        } catch (e: Exception) {
            onError("Could not start VLC: ${e.message}"); release(); false
        }
    }

    private fun readUntilPrompt(r: java.io.BufferedReader, idleMs: Long = 400): List<String> {
        val lines = mutableListOf<String>()
        val end = System.currentTimeMillis() + 4000
        val buf = StringBuilder()
        while (System.currentTimeMillis() < end) {
            while (r.ready()) {
                val c = r.read()
                if (c < 0) break
                buf.append(c.toChar())
            }
            val text = buf.toString()
            if ("> " in text || text.trimEnd().endsWith(">")) break
            Thread.sleep(50)
            if (System.currentTimeMillis() > end - 3500 + idleMs) {
                // No prompt yet; keep waiting for slow commands.
            }
        }
        return buf.toString().lines().map { it.trim() }.filter { it.isNotEmpty() && it != ">" }
    }

    private fun cmd(vararg parts: String): List<String> = synchronized(lock) {
        val s = sock ?: return emptyList()
        return try {
            val w = s.getOutputStream().bufferedWriter()
            w.write(parts.joinToString(" ") + "\n"); w.flush()
            readUntilPrompt(s.getInputStream().bufferedReader())
        } catch (e: Exception) {
            onError("VLC command failed: ${e.message}")
            emptyList()
        }
    }

    override fun setQueue(items: List<QueueItem>, index: Int, positionMs: Long) {
        if (!ensureRunning()) return
        queue = items
        this.index = index.coerceIn(items.indices)
        cmd("clear")
        items.forEach { cmd("enqueue", it.key) }
        goto(this.index)
        if (positionMs > 1000) seekTo(positionMs)
    }

    private fun goto(i: Int) {
        index = i.coerceIn(queue.indices)
        cmd("goto", index.toString())
        lengthSec = (queue.getOrNull(index)?.durationMs ?: 0L) / 1000
        lastPosMs = 0
        applySpeed()
        applyVolume()
    }

    override fun play() {
        if (!ensureRunning()) return
        if (queue.isEmpty()) return
        if (index !in queue.indices) goto(0)
        if (!vlcPlaying()) cmd("play")
        if (!vlcPlaying()) {
            // Input was still opening after goto/enqueue: one retry lands it.
            Thread.sleep(400)
            cmd("play")
        }
        playing = true
        onState()
    }

    override fun pause() {
        if (!ensureRunning()) return
        // rc `pause` toggles: only send it while actually playing.
        if (vlcPlaying()) cmd("pause")
        playing = false
        lastPosMs = positionMs
        onState()
    }

    override fun toggle(): Boolean {
        if (vlcPlaying()) pause() else play()
        return isPlaying
    }

    private fun vlcPlaying(): Boolean = cmd("status").any { "state playing" in it }

    override fun next() {
        if (queue.isEmpty()) return
        goto(if (index + 1 < queue.size) index + 1 else 0)
        if (playing) cmd("play")
        onState()
    }

    override fun prev() {
        if (queue.isEmpty()) return
        // Same previous-button rule as Android (shared PlaybackRules).
        when (com.blissplayer.core.PlaybackRules.prevAction(positionMs, index)) {
            com.blissplayer.core.PlaybackRules.PrevAction.RESTART -> seekTo(0)
            com.blissplayer.core.PlaybackRules.PrevAction.PREVIOUS -> {
                goto(if (index - 1 >= 0) index - 1 else queue.size - 1)
                if (playing) cmd("play")
            }
        }
        onState()
    }

    override fun seekTo(positionMs: Long) {
        if (!ensureRunning()) return
        cmd("seek", (positionMs / 1000).toString())
        lastPosMs = positionMs
        onState()
    }

    override fun setSpeed(speed: Float) {
        this.speed = speed.coerceIn(0.5f, 2f)
        applySpeed()
    }

    private fun applySpeed() {
        if (!ensureRunning()) return
        cmd("normal")
        if (speed <= 0.75f) cmd("slower") else if (speed >= 1.5f) cmd("faster")
    }

    override fun setLoop(mode: LoopMode) { loop = mode }

    override fun setVolumeMultiplier(mult: Double) {
        volMult = mult.coerceIn(0.0, 8.0)
        applyVolume()
    }

    private fun applyVolume() {
        if (!ensureRunning()) return
        // VLC rc volume scale is 0-512 (256 = 100%).
        cmd("volume", (256 * volMult).toInt().coerceIn(0, 1024).toString())
    }

    override val isPlaying: Boolean get() = playing && proc?.isAlive == true

    override val positionMs: Long get() {
        if (proc?.isAlive != true) return lastPosMs
        val t = cmd("get_time").firstOrNull { it.all { c -> c.isDigit() } }?.toLongOrNull()
        if (t != null) lastPosMs = t * 1000
        return lastPosMs
    }

    override val durationMs: Long get() {
        val live = cmd("get_length").firstOrNull { it.all { c -> c.isDigit() } }?.toLongOrNull()
        if (live != null && live > 0) lengthSec = live
        return if (lengthSec > 0) lengthSec * 1000 else queue.getOrNull(index)?.durationMs ?: 0L
    }

    private fun startMonitor() {
        if (monitor.getAndSet(true)) return
        thread(isDaemon = true, name = "bliss-watch") {
            while (monitor.get()) {
                try {
                    Thread.sleep(1000)
                    if (!playing || proc?.isAlive != true) continue
                    val key = queue.getOrNull(index)?.key ?: continue
                    val segs = segments[key].orEmpty()
                    if (segs.isNotEmpty()) {
                        val pos = positionMs
                        for ((s, e) in segs.sortedBy { it.first }) {
                            if (pos in s until e) { seekTo(e); break }
                        }
                    }
                    val dur = durationMs
                    if (dur > 5000 && positionMs >= dur - 800) onTrackEnd()
                } catch (e: Exception) { /* keep watching */ }
            }
        }
    }

    private fun onTrackEnd() {
        when (loop) {
            LoopMode.ONE -> { seekTo(0); if (!playing) play() else cmd("play") }
            LoopMode.ALL -> next()
            LoopMode.OFF -> {
                if (index + 1 < queue.size) next()
                else { playing = false; onState() }
            }
        }
    }

    override fun release() {
        monitor.set(false)
        runCatching { cmd("shutdown") }
        // shutdown is async; do not wait on it.
        runCatching { sock?.close() }
        runCatching { proc?.destroy() }
        sock = null; proc = null; playing = false
    }

    // NOTE: the rc interface takes the rest of the line as the path verbatim;
    // quoting (as in a shell) becomes part of the filename and breaks opens.
}

/** Desktop queue item carrying its filesystem path (sourceHint is the path). */
data class DesktopItem(
    val path: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long
) {
    fun toQueue(): QueueItem = QueueItem(path, title, artist, album, durationMs)
}
