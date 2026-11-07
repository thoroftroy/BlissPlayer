package com.blissplayer.desktop

import com.blissplayer.core.LoudnessMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Desktop loudness: same shared gain formula as Android. Fast mode samples
 * one short window, balanced uses two 8s windows, accurate uses four 10s
 * windows. Measurement decodes with ffmpeg to float PCM; gains persist
 * in DesktopDb and apply through the player volume path.
 */
class DesktopLoudness(private val db: DesktopDb) {
    data class Result(val measured: Int, val reused: Int, val raised: Int, val lowered: Int, val skipped: Int)

    fun windowsFor(accuracy: Float, durationMs: Long): List<Long> {
        val a = accuracy.coerceIn(0f, 1f)
        return when {
            durationMs <= 30_000L && a < 0.65f -> listOf(0L)
            a <= 0.25f -> listOf(durationMs * 30 / 100)
            a <= 0.65f -> listOf(durationMs * 15 / 100, durationMs * 55 / 100)
            else -> listOf(durationMs * 8 / 100, durationMs * 32 / 100, durationMs * 58 / 100, durationMs * 84 / 100)
        }
    }

    fun windowMsFor(accuracy: Float): Long {
        val a = accuracy.coerceIn(0f, 1f)
        return when { a <= 0.25f -> 4_000L; a <= 0.65f -> 8_000L; else -> 10_000L }
    }

    fun strideFor(accuracy: Float): Int {
        val a = accuracy.coerceIn(0f, 1f)
        return when { a <= 0.25f -> 8; a <= 0.65f -> 4; else -> 2 }
    }

    suspend fun equalize(
        rows: List<SongRow>,
        strength: Float,
        accuracy: Float = 0.35f,
        putGain: (Long, Double) -> Unit,
        onProgress: (done: Int, total: Int, title: String, etaSec: Long?) -> Unit
    ): Result = withContext(Dispatchers.IO) {
        val cached = db.allMeasures()
        val rms = LinkedHashMap<Long, Double>()
        var skipped = 0
        var reused = 0
        val sem = Semaphore(8)
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
                    val v = measureSampled(r.path, r.durationMs, accuracy)
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

    /** RMS over sampled windows. Null when ffmpeg fails. */
    fun measureSampled(path: String, durationMs: Long, accuracy: Float = 0.35f): Double? {
        val starts = windowsFor(accuracy, durationMs)
        val winMs = windowMsFor(accuracy)
        val stride = strideFor(accuracy)
        var sum = 0.0
        var n = 0L
        for (start in starts) {
            val (s, c) = decodeWindow(path, start, winMs, stride) ?: return null
            sum += s; n += c
        }
        if (n == 0L) return null
        return kotlin.math.sqrt(sum / n)
    }

    /** Listening fingerprint: 8 RMS samples across the song. */
    fun fingerprint(path: String, durationMs: Long): com.blissplayer.core.AudioFingerprint.Print? {
        val safeDur = if (durationMs > 0) durationMs else 180_000L
        val buckets = mutableListOf<Double>()
        for (i in 0 until 8) {
            val pos = (safeDur * (i + 0.5) / 8).toLong().coerceAtLeast(0)
            val r = decodeWindow(path, pos, 1000, 4) ?: continue
            val (sum, count) = r
            buckets.add(if (count <= 0) 0.0 else kotlin.math.sqrt(sum / count))
        }
        if (buckets.isEmpty()) return null
        return com.blissplayer.core.AudioFingerprint.fromBuckets(buckets, durationMs)
    }

    /** Leading and trailing near silence as (trimStartMs, trimEndMsExclusive). */
    fun silenceTrim(path: String, durationMs: Long): Pair<Long, Long>? {
        if (durationMs < 5_000L) return null
        val edgeLen = minOf(20_000L, durationMs / 3)
        val lead = decodeBuckets(path, 0L, edgeLen) ?: return null
        val trail = decodeBuckets(path, (durationMs - edgeLen).coerceAtLeast(0), edgeLen) ?: return null
        fun rmsOf(samples: List<Float>): Double {
            if (samples.isEmpty()) return 0.0
            var sum = 0.0
            for (v in samples) sum += v * v
            return kotlin.math.sqrt(sum / samples.size)
        }
        // 100ms buckets at 44100 mono.
        fun toBuckets(samples: List<Float>): List<Double> {
            if (samples.isEmpty()) return emptyList()
            val per = 4410
            val out = mutableListOf<Double>()
            var i = 0
            while (i < samples.size) {
                val end = minOf(i + per, samples.size)
                out.add(rmsOf(samples.subList(i, end)))
                i = end
            }
            return out
        }
        val leadBuckets = toBuckets(lead)
        val trailBuckets = toBuckets(trail)
        val trim = com.blissplayer.core.AudioFingerprint.trimEdges(leadBuckets, 100L) ?: return null
        var trailCount = 0
        for (i in trailBuckets.indices.reversed()) {
            if (trailBuckets[i] < 0.012) trailCount++ else break
        }
        val leadMs = trim.first
        val trailMs = trailCount * 100L
        if (leadMs < 400 && trailMs < 400) return null
        val start = ((if (leadMs >= 400) leadMs else 0L) - 150).coerceAtLeast(0)
        val end = (durationMs - (if (trailMs >= 400) trailMs else 0L) + 150).coerceAtMost(durationMs).coerceAtLeast(start)
        if (end - start < 1_000) return null
        if (start == 0L && end == durationMs) return null
        return start to end
    }

    private fun decodeBuckets(path: String, startMs: Long, lengthMs: Long): List<Float>? {
        if (!java.io.File(path).exists()) return null
        return try {
            val p = ProcessBuilder("ffmpeg", "-v", "error",
                "-ss", "%.3f".format(startMs / 1000.0),
                "-t", "%.3f".format(lengthMs / 1000.0),
                "-i", path, "-ac", "1", "-ar", "44100", "-f", "f32le", "-")
                .redirectErrorStream(false).start()
            val drain = Thread { runCatching { p.errorStream.bufferedReader().forEachLine { } } }
            drain.isDaemon = true; drain.start()
            val bytes = p.inputStream.readBytes()
            p.waitFor()
            if (bytes.size < 4) return null
            val out = ArrayList<Float>(bytes.size / 4)
            var i = 0
            while (i + 4 <= bytes.size) {
                val bits = (bytes[i].toInt() and 0xFF) or ((bytes[i + 1].toInt() and 0xFF) shl 8) or
                    ((bytes[i + 2].toInt() and 0xFF) shl 16) or (bytes[i + 3].toInt() shl 24)
                val v = java.lang.Float.intBitsToFloat(bits)
                if (v.isFinite()) out.add(v)
                i += 4
            }
            out.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }

    private fun decodeWindow(path: String, startMs: Long, lengthMs: Long, stride: Int = 1): Pair<Double, Long>? {
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
            var idx = 0
            val step = stride.coerceAtLeast(1)
            while (i + 4 <= bytes.size) {
                val bits = (bytes[i].toInt() and 0xFF) or ((bytes[i + 1].toInt() and 0xFF) shl 8) or
                    ((bytes[i + 2].toInt() and 0xFF) shl 16) or (bytes[i + 3].toInt() shl 24)
                if (idx % step == 0) {
                    val v = java.lang.Float.intBitsToFloat(bits).toDouble()
                    if (v.isFinite()) { sum += v * v; count++ }
                }
                idx++
                i += 4
            }
            if (count == 0L) null else sum to count
        } catch (e: Exception) {
            null
        }
    }
}
