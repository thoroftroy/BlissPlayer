package com.blissplayer.desktop

import com.blissplayer.core.LoudnessMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Desktop loudness: same windows as Android (early + late 8s), same shared
 * gain formula. Measurement decodes with ffmpeg to float PCM; gains persist
 * in DesktopDb and apply through the player volume path.
 */
class DesktopLoudness(private val db: DesktopDb) {
    data class Result(val measured: Int, val reused: Int, val raised: Int, val lowered: Int, val skipped: Int)

    suspend fun equalize(
        rows: List<SongRow>,
        strength: Float,
        putGain: (Long, Double) -> Unit,
        onProgress: (done: Int, total: Int, title: String, etaSec: Long?) -> Unit
    ): Result = withContext(Dispatchers.IO) {
        val cached = db.allMeasures()
        val rms = LinkedHashMap<Long, Double>()
        var skipped = 0
        var reused = 0
        val sem = Semaphore(4)
        val t0 = System.currentTimeMillis()
        val jobs = rows.map { r ->
            async {
                sem.withPermit {
                    val fp = "${r.sizeBytes}:${r.modified}"
                    val hit = cached[r.mediaId]
                    if (hit != null && hit.second == r.modified && hit.first > 0.0) {
                        synchronized(rms) { rms[r.mediaId] = hit.first }
                        return@withPermit "cached"
                    }
                    val v = measureSampled(r.path, r.durationMs)
                    synchronized(rms) {
                        if (v == null || v <= 0.0) skipped++
                        else {
                            rms[r.mediaId] = v
                            runCatching { db.putMeasure(r.mediaId, v, r.modified) }
                            runCatching { db.markEq(r.mediaId, fp) }
                        }
                    }
                    if (v != null && v > 0.0) "fresh" else "skipped"
                }
            }
        }
        jobs.forEachIndexed { i, j ->
            if (j.await() == "cached") reused++
            val done = i + 1
            val eta: Long? = if (done >= 5) {
                val rate = (done / ((System.currentTimeMillis() - t0) / 1000.0).coerceAtLeast(0.1))
                ((rows.size - done) / rate).toLong().coerceAtLeast(0)
            } else null
            onProgress(done, rows.size, rows[i].title, eta)
        }
        if (rms.isEmpty()) return@withContext Result(0, reused, 0, 0, skipped)
        val gains = LoudnessMath.gains(rms, strength)
        gains.forEach { (id, mult) -> runCatching { putGain(id, mult) } }
        val (raised, lowered) = LoudnessMath.counts(gains)
        Result(rms.size, reused, raised, lowered, skipped)
    }

    /** RMS over the same two 8s windows Android samples. Null when ffmpeg fails. */
    fun measureSampled(path: String, durationMs: Long): Double? {
        val starts = if (durationMs > 30_000) listOf(durationMs * 15 / 100, durationMs * 55 / 100) else listOf(0L)
        var sum = 0.0
        var n = 0L
        for (start in starts) {
            val (s, c) = decodeWindow(path, start, 8000) ?: return null
            sum += s; n += c
        }
        if (n == 0L) return null
        return kotlin.math.sqrt(sum / n)
    }

    private fun decodeWindow(path: String, startMs: Long, lengthMs: Long): Pair<Double, Long>? {
        if (!File(path).exists()) return null
        return try {
            val p = ProcessBuilder("ffmpeg", "-v", "error",
                "-ss", "%.3f".format(startMs / 1000.0),
                "-t", "%.3f".format(lengthMs / 1000.0),
                "-i", path, "-ac", "1", "-ar", "44100", "-f", "f32le", "-")
                .redirectErrorStream(false).start()
            // f32le mono samples; drain stderr so a chatty ffmpeg never blocks.
            val drain = Thread { runCatching { p.errorStream.bufferedReader().forEachLine { } } }
            drain.isDaemon = true; drain.start()
            val bytes = p.inputStream.readBytes()
            p.waitFor()
            if (bytes.size < 4) return null
            var sum = 0.0
            var count = 0L
            var i = 0
            while (i + 4 <= bytes.size) {
                val bits = (bytes[i].toInt() and 0xFF) or ((bytes[i + 1].toInt() and 0xFF) shl 8) or
                    ((bytes[i + 2].toInt() and 0xFF) shl 16) or (bytes[i + 3].toInt() shl 24)
                val v = java.lang.Float.intBitsToFloat(bits).toDouble()
                if (v.isFinite()) { sum += v * v; count++ }
                i += 4
            }
            if (count == 0L) null else sum to count
        } catch (e: Exception) {
            null
        }
    }
}
