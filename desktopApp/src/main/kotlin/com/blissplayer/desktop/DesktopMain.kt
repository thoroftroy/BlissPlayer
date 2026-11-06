package com.blissplayer.desktop

import com.blissplayer.core.LoopMode
import com.blissplayer.core.PlaylistPortable
import com.blissplayer.core.TransportCommand
import java.awt.SystemTray
import java.io.File
import javax.swing.SwingUtilities

object DesktopMain {
    fun version(): String = runCatching {
        DesktopMain::class.java.getResourceAsStream("/bliss-version.txt")
            ?.bufferedReader()?.readText()?.trim()
    }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: runCatching {
            File(System.getProperty("user.dir"), "app/version.properties").readLines()
                .firstOrNull { it.startsWith("versionName=") }?.substringAfter("=")
        }.getOrNull() ?: "0.11.x"

    @JvmStatic
    fun main(args: Array<String>) {
        var musicDir: String? = null
        var smoke = false
        var quit = false
        var i = 0
        while (i < args.size) {
            when {
                args[i] == "--music-dir" && i + 1 < args.size -> { musicDir = args[i + 1]; i += 2 }
                args[i] == "--headless-smoke" -> { smoke = true; i++ }
                args[i] == "--quit" -> { quit = true; i++ }
                else -> i++
            }
        }
        if (quit) kotlin.system.exitProcess(if (quitRunningInstance()) 0 else 1)
        if (smoke) return headlessSmoke(musicDir)
        if (!singleInstance()) {
            System.err.println(
                "BlissPlayer is already running (lock held). Quit it from the tray menu, " +
                    "or run with --quit (or `pkill -f blissplayer-desktop.jar`) first."
            )
            return
        }
        writePidFile()
        val settings = DesktopSettings()
        val dir = musicDir?.let { File(it) }
            ?: settings.getString("music_dir", "").takeIf { it.isNotBlank() }?.let { File(it) }
        val paths = DesktopPaths(dir)
        val ctx = buildContext(settings, paths)
        if (musicDir != null) settings.putString("music_dir", musicDir)
        SwingUtilities.invokeLater {
            val frame = MainFrame(ctx)
            frame.setSize(900, 640)
            frame.isVisible = true
            frame.applyLayoutPublic()
            // Native SNI first (works on Wayland/X11 wherever a StatusNotifier
            // host exists); AWT is only a fallback for X11 trays without SNI.
            val sni = DesktopSniTray(
                show = { frame.isVisible = true; frame.toFront() },
                onCommand = { cmd -> ctx.session.onCommand(cmd) },
                quit = { ctx.notify.quit?.invoke() },
                tooltip = { ctx.notify.tooltipText() }
            )
            if (sni.start()) {
                frame.defaultCloseOperation = javax.swing.JFrame.HIDE_ON_CLOSE
                ctx.notify.trayBackend = { sni.stop() }
            } else if (SystemTray.isSupported()) {
                frame.defaultCloseOperation = javax.swing.JFrame.HIDE_ON_CLOSE
                ctx.notify.installTray(XpPaint.of(DeskTheme.from(ctx.settings.getString("theme", "bliss"))).select) {
                    frame.isVisible = true
                    frame.toFront()
                }
            } else {
                // No tray to retreat to: closing the window quits.
                frame.defaultCloseOperation = javax.swing.JFrame.EXIT_ON_CLOSE
            }
            ctx.notify.quit = {
                ctx.notify.trayBackend?.invoke()
                ctx.notify.trayBackend = null
                ctx.notify.removeTray()
                ctx.player.release()
                ctx.db.close()
                deletePidFile()
                kotlin.system.exitProcess(0)
            }
            Runtime.getRuntime().addShutdownHook(Thread {
                runCatching { ctx.notify.trayBackend?.invoke() }
                runCatching { ctx.notify.removeTray() }
                runCatching { ctx.player.release() }
                runCatching { ctx.db.close() }
                runCatching { deletePidFile() }
            })
        }
    }

    /** One GUI at a time: a second launch exits instead of adding a second tray icon. */
    private var lockChannel: java.nio.channels.FileChannel? = null

    fun singleInstance(lock: File = File(System.getProperty("user.home"), ".config/blissplayer/app.lock")): Boolean {
        return try {
            lock.parentFile?.mkdirs()
            val ch = java.io.FileOutputStream(lock, true).channel
            if (ch.tryLock() == null) {
                ch.close()
                false
            } else {
                lockChannel = ch
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    fun pidFile(): File = File(System.getProperty("user.home"), ".config/blissplayer/app.pid")

    private fun writePidFile() {
        runCatching {
            val f = pidFile()
            f.parentFile?.mkdirs()
            f.writeText(ProcessHandle.current().pid().toString())
        }
    }

    private fun deletePidFile() {
        runCatching { pidFile().delete() }
    }

    /**
     * CLI escape hatch when the tray menu is unreachable: `--quit` stops the
     * running instance (pid file first, `pkill` fallback for older builds).
     */
    fun quitRunningInstance(): Boolean {
        val pid = runCatching { pidFile().readText().trim().toLong() }.getOrNull()
        if (pid != null) {
            val handle = ProcessHandle.of(pid).orElse(null)
            if (handle != null && handle.isAlive) {
                handle.destroy()
                val end = System.currentTimeMillis() + 5000
                while (handle.isAlive && System.currentTimeMillis() < end) Thread.sleep(100)
                if (handle.isAlive) handle.destroyForcibly()
                println("Stopped BlissPlayer (pid $pid).")
                return true
            }
            println("No live BlissPlayer for pid file ($pid).")
            return false
        }
        val pkill = runCatching {
            ProcessBuilder("pkill", "-f", "blissplayer-desktop.jar").start().waitFor()
        }.getOrDefault(-1)
        if (pkill == 0) {
            println("Stopped BlissPlayer via pkill.")
            return true
        }
        System.err.println("No running BlissPlayer found.")
        return false
    }

    fun buildContext(settings: DesktopSettings, paths: DesktopPaths, dummyAudio: Boolean = false): DesktopContext {
        val db = DesktopDb(paths.databaseFile)
        val library = DesktopLibrary(paths.music)
        val player = DesktopPlayer(dummyAudio)
        val fx = DesktopFx(settings) { mult -> player.setVolumeMultiplier(mult) }
        val notify = DesktopNotify()
        val transport: (TransportCommand) -> Unit = { cmd ->
            when (cmd) {
                TransportCommand.PLAY, TransportCommand.TOGGLE -> player.toggle()
                TransportCommand.PAUSE -> player.pause()
                TransportCommand.NEXT -> player.next()
                TransportCommand.PREV -> player.prev()
                TransportCommand.FAVORITE -> {}
            }
        }
        val session = DesktopSession(notify, transport)
        return DesktopContext(settings, paths, db, library, player, fx, notify, session,
            DesktopAnalyzer(db, settings), DesktopLoudness(db))
    }

    /**
     * Headless parity smoke (no display needed): scan, tag read, playlist
     * round-trip, offline analyzer, loudness sample and an actual VLC
     * play/pause/seek/stop cycle with the dummy audio output.
     */
    fun headlessSmoke(musicDir: String?) {
        val dir = musicDir ?: System.getenv("BLISS_MUSIC_DIR") ?: "${System.getProperty("user.home")}/Music"
        val settings = DesktopSettings()
        val paths = DesktopPaths(File(dir))
        val ctx = buildContext(settings, paths, dummyAudio = true)
        var failures = 0
        fun check(name: String, cond: Boolean, detail: String = "") {
            println((if (cond) "PASS" else "FAIL") + " $name $detail")
            if (!cond) failures++
        }
        val entries = ctx.library.scan(emptySet()) { _, _ -> }
        check("scan finds audio", entries.isNotEmpty(), "(${entries.size} files in $dir)")
        val first = entries.firstOrNull()
        if (first != null) {
            val tags = DesktopTags.read(first.path)
            check("tag read", tags != null, "(${first.path.substringAfterLast('/')})")
            val firstEntry = entries.first()
            val json = PlaylistPortable.export("Smoke", listOf(
                PlaylistPortable.Entry("a/b.m4a", "b.m4a", "B", "A", "Al", 1000)), emptyList())
            val back = PlaylistPortable.parse(json)
            check("playlist round-trip", back != null && back.entries.size == 1)
            val libTracks = entries.map {
                PlaylistPortable.LibTrack(it.path, File(it.path).name, it.title, it.artist, it.album, it.durationMs)
            }
            val portableMiss = PlaylistPortable.relink(back!!.entries, libTracks) { it }
            check("playlist relink keeps missing", portableMiss.found.isEmpty() && portableMiss.missing.size == 1)
            val fuzzyEntry = PlaylistPortable.Entry("Elsewhere/${File(firstEntry.path).name}",
                File(firstEntry.path).name, firstEntry.title, firstEntry.artist, firstEntry.album, firstEntry.durationMs)
            val fuzzy = PlaylistPortable.relink(listOf(fuzzyEntry), libTracks) { it }
            check("playlist fuzzy relink links", fuzzy.found.size == 1, "(${fuzzy.found.firstOrNull()?.second?.fileName})")
            val rms = ctx.loudness.measureSampled(first.path, 240000)
            check("loudness sample", rms != null && rms > 0, "(rms=$rms)")
            val items = entries.take(2).map {
                com.blissplayer.core.QueueItem(it.path, it.title, it.artist, it.album, it.durationMs)
            }
            var err: String? = null
            ctx.player.onError = { err = it }
            ctx.player.setQueue(items, 0, 0)
            ctx.player.setLoop(LoopMode.ALL)
            ctx.player.setVolumeMultiplier(0.5)
            ctx.player.play()
            var pos = -1L
            for (attempt in 0 until 20) {
                Thread.sleep(500)
                pos = ctx.player.positionMs
                if (pos > 0) break
            }
            ctx.player.seekTo(30_000)
            Thread.sleep(1500)
            val pos2 = ctx.player.positionMs
            ctx.player.pause()
            val paused = !ctx.player.isPlaying
            ctx.player.release()
            check("vlc engine plays", err == null && pos >= 0, "(err=$err pos=$pos)")
            check("seek advances", pos2 >= 25000, "(pos2=$pos2)")
            check("pause holds", paused)
        }
        ctx.db.close()
        println(if (failures == 0) "SMOKE OK" else "SMOKE $failures FAILURES")
        if (failures > 0) kotlin.system.exitProcess(1)
    }
}
