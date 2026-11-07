package com.blissplayer.music.playback

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.blissplayer.music.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.sqrt

/**
 * One tap loudness equalizer.
 *
 * Fast path for big libraries: instead of decoding every file end to end,
 * two short loudness windows are sampled per song (early and late in the
 * track), four songs are measured in parallel, and results are cached by
 * file date so repeat runs only touch new or changed songs.
 *
 * Gains stay non destructive (stored in the app DB, originals untouched).
 */
object LoudnessScanner {

    data class Cached(val rms: Double, val modified: Long)

    data class Result(
        val measured: Int,
        val reused: Int,
        val raised: Int,
        val lowered: Int,
        val skipped: Int
    )

    private val PARALLEL = (Runtime.getRuntime().availableProcessors() * 2).coerceIn(8, 12)
    private const val WINDOW_MS = 8000L

    /**
     * Accuracy 0..1. Fast (0) samples one short window with coarse stride.
     * Balanced (0.4) matches the old two window behavior. Accurate (1)
     * samples four longer windows with fine stride for users who want to
     * wait for tighter loudness matching.
     */
    fun windowsFor(accuracy: Float, durationMs: Long): List<Long> {
        val a = accuracy.coerceIn(0f, 1f)
        return when {
            durationMs <= 30_000L && a < 0.65f -> listOf(0L)
            a <= 0.25f -> listOf(durationMs * 30 / 100)
            a <= 0.65f -> listOf(durationMs * 15 / 100, durationMs * 55 / 100)
            else -> listOf(
                durationMs * 8 / 100,
                durationMs * 32 / 100,
                durationMs * 58 / 100,
                durationMs * 84 / 100
            )
        }
    }

    fun windowMsFor(accuracy: Float): Long {
        val a = accuracy.coerceIn(0f, 1f)
        return when {
            a <= 0.25f -> 4_000L
            a <= 0.65f -> 8_000L
            else -> 10_000L
        }
    }

    fun strideFor(accuracy: Float): Int {
        val a = accuracy.coerceIn(0f, 1f)
        return when {
            a <= 0.25f -> 8
            a <= 0.65f -> 4
            else -> 2
        }
    }

    fun accuracyLabel(v: Int): String {
        return when {
            v <= 25 -> "Fast: one short sample per song. Fastest."
            v <= 65 -> "Balanced: two samples per song. Good for most libraries."
            else -> "Accurate: four longer samples per song. Slower but tighter."
        }
    }

    suspend fun equalizeAll(
        context: Context,
        songs: List<Song>,
        cached: Map<Long, Cached>,
        strength: Float = 1f,
        accuracy: Float = 0.35f,
        putGain: (mediaId: Long, mult: Double) -> Unit,
        putCache: (mediaId: Long, rms: Double, modified: Long) -> Unit,
        onProgress: (done: Int, total: Int, title: String, etaSec: Long?) -> Unit
    ): Result = coroutineScope {
        val rms = LinkedHashMap<Long, Double>()
        var skipped = 0
        var reused = 0
        val sem = Semaphore(PARALLEL)
        val t0 = System.currentTimeMillis()

        val jobs = songs.map { s ->
            async(Dispatchers.Default) {
                sem.withPermit {
                    val hit = cached[s.mediaId]
                    if (hit != null && hit.modified == s.modified && hit.rms > 0.0) {
                        synchronized(rms) { rms[s.mediaId] = hit.rms }
                        return@withPermit "cached"
                    }
                    val r = runCatching { measureSampled(context, Uri.parse(s.contentUri), s.durationMs, accuracy) }.getOrNull()
                    synchronized(rms) {
                        if (r == null || r <= 0.0) skipped++ else {
                            rms[s.mediaId] = r
                            // A failed cache write must not fail the whole job.
                            runCatching { putCache(s.mediaId, r, s.modified) }
                        }
                    }
                    if (r != null && r > 0.0) "fresh" else "skipped"
                }
            }
        }
        // Drive progress as jobs finish, in order.
        jobs.forEachIndexed { i, j ->
            val kind = j.await()
            if (kind == "cached") reused++
            val done = i + 1
            val eta: Long? = if (done >= 5) {
                val elapsed = (System.currentTimeMillis() - t0) / 1000.0
                val rate = done / elapsed.coerceAtLeast(0.1)
                ((songs.size - done) / rate).toLong().coerceAtLeast(0)
            } else null
            onProgress(done, songs.size, songs[i].title, eta)
        }

        if (rms.isEmpty()) return@coroutineScope Result(0, reused, 0, 0, skipped)
        // One shared formula on both platforms (see core LoudnessMath):
        // strength blends full correction toward doing nothing.
        val gains = com.blissplayer.core.LoudnessMath.gains(rms, strength)
        gains.forEach { (id, mult) -> runCatching { putGain(id, mult) } }
        val (raised, lowered) = com.blissplayer.core.LoudnessMath.counts(gains)
        Result(rms.size, reused, raised, lowered, skipped)
    }

    /**
     * RMS from sampled windows. Fast mode uses one short window, balanced
     * uses two 8 second windows, accurate uses four 10 second windows.
     * Skips intros and dead air at the edges, much faster than full decode.
     */
    private suspend fun measureSampled(context: Context, uri: Uri, durationMs: Long, accuracy: Float = 0.35f): Double? {
        val starts = windowsFor(accuracy, durationMs)
        val windowMs = windowMsFor(accuracy)
        val stride = strideFor(accuracy)
        var sum = 0.0
        var n = 0L
        for (start in starts) {
            val (s, c) = decodeWindow(context, uri, start, windowMs, stride) ?: return null
            sum += s
            n += c
            if (!coroutineContext.isActive) return null
        }
        if (n == 0L) return null
        return sqrt(sum / n)
    }

    internal suspend fun measureSampledPublic(context: Context, uri: Uri, durationMs: Long, accuracy: Float): Double? {
        return measureSampled(context, uri, durationMs, accuracy)
    }

    /**
     * Listening fingerprint: 8 RMS samples spread across the song.
     * Same recording scores 90+, different songs rarely pass 50.
     * Kept to 8 short windows so a thousand song library finishes.
     */
    suspend fun fingerprint(context: Context, uri: Uri, durationMs: Long): com.blissplayer.core.AudioFingerprint.Print? {
        val safeDur = if (durationMs > 0) durationMs else 180_000L
        val buckets = mutableListOf<Double>()
        val n = 8
        for (i in 0 until n) {
            val pos = (safeDur * (i + 0.5) / n).toLong().coerceAtLeast(0)
            val r = runCatching { decodeWindow(context, uri, pos, 1000L, 4) }.getOrNull()
            if (r == null) continue
            val (sum, count) = r
            if (count <= 0) {
                buckets.add(0.0)
            } else {
                buckets.add(sqrt(sum / count))
            }
            if (!coroutineContext.isActive) return null
        }
        if (buckets.isEmpty()) return null
        return com.blissplayer.core.AudioFingerprint.fromBuckets(buckets, durationMs)
    }

    /**
     * Leading and trailing near silence as (trimStartMs, trimEndMsExclusive).
     * Returns null when there is nothing worth skipping. Threshold catches
     * complete silence and almost complete silence.
     */
    suspend fun silenceTrim(context: Context, uri: Uri, durationMs: Long): Pair<Long, Long>? {
        val safeDur = if (durationMs > 0) durationMs else return null
        if (safeDur < 5_000L) return null
        val edgeLen = minOf(20_000L, safeDur / 3)
        val lead = decodeBuckets(context, uri, 0L, edgeLen) ?: return null
        // 100ms buckets from a continuous edge decode.
        val trim = com.blissplayer.core.AudioFingerprint.trimEdges(lead, 100L) ?: return null
        val (leadMs, _) = trim
        // Trailing edge needs its own decode at the end of the file.
        val trail = decodeBuckets(context, uri, (safeDur - edgeLen).coerceAtLeast(0), edgeLen)
        var trailMs = 0L
        if (trail != null) {
            var t = 0
            for (i in trail.indices.reversed()) {
                if (trail[i] < 0.012) t++ else break
            }
            trailMs = t * 100L
        }
        val leadTrim = if (leadMs >= 400) leadMs.coerceAtMost(safeDur / 2) else 0L
        val trailTrim = if (trailMs >= 400) trailMs.coerceAtMost(safeDur / 2) else 0L
        if (leadTrim == 0L && trailTrim == 0L) return null
        // Keep a small 150ms handle so attacks and reverb tails survive.
        val start = (leadTrim - 150).coerceAtLeast(0)
        val end = (safeDur - trailTrim + 150).coerceAtMost(safeDur).coerceAtLeast(start)
        if (end - start < 1_000) return null
        // Only report actual trims at the very edges.
        if (start == 0L && end == safeDur) return null
        return start to end
    }

    /** RMS per ~100ms bucket for silence detection. */
    private suspend fun decodeBuckets(context: Context, uri: Uri, startMs: Long, lengthMs: Long): List<Double>? {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(context, uri, null)
        } catch (e: Exception) {
            ex.release()
            return null
        }
        var track = -1
        var format: MediaFormat? = null
        for (i in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                track = i
                format = f
                break
            }
        }
        if (track < 0 || format == null) {
            ex.release()
            return null
        }
        ex.selectTrack(track)
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val codec = try {
            MediaCodec.createDecoderByType(mime)
        } catch (e: Exception) {
            ex.release()
            return null
        }
        return try {
            codec.configure(format, null, null, 0)
            codec.start()
            if (startMs > 0) {
                runCatching { ex.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC) }
            }
            val stopAt = ex.sampleTime + lengthMs * 1000
            val buckets = mutableListOf<Double>()
            var bSum = 0.0
            var bCount = 0L
            var bucketStart = startMs * 1000
            val info = MediaCodec.BufferInfo()
            var sawInputEos = false
            var outputEos = false
            val t0 = System.currentTimeMillis()
            while (!outputEos) {
                if (System.currentTimeMillis() - t0 > 20_000 || !coroutineContext.isActive) break
                if (ex.sampleTime > stopAt && !sawInputEos) {
                    val bi = codec.dequeueInputBuffer(2_000)
                    if (bi >= 0) codec.queueInputBuffer(bi, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    sawInputEos = true
                }
                if (!sawInputEos) {
                    val bi = codec.dequeueInputBuffer(2_000)
                    if (bi >= 0) {
                        val buf = codec.getInputBuffer(bi)!!
                        val read = ex.readSampleData(buf, 0)
                        if (read < 0 || ex.sampleTime > stopAt) {
                            codec.queueInputBuffer(bi, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEos = true
                        } else {
                            codec.queueInputBuffer(bi, 0, read, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val bo = codec.dequeueOutputBuffer(info, 2_000)
                when {
                    bo >= 0 -> {
                        val out: ByteBuffer = codec.getOutputBuffer(bo)!!
                        out.order(ByteOrder.nativeOrder())
                        var pos = out.position()
                        val end = pos + info.size
                        while (pos + 1 < end) {
                            val v = out.getShort(pos).toInt() / 32768.0
                            bSum += v * v
                            bCount++
                            pos += 2
                        }
                        val pts = info.presentationTimeUs
                        if (pts - bucketStart >= 100_000) {
                            buckets.add(if (bCount > 0) sqrt(bSum / bCount) else 0.0)
                            bSum = 0.0
                            bCount = 0L
                            bucketStart = pts
                        }
                        codec.releaseOutputBuffer(bo, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputEos = true
                    }
                    bo == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {}
                    bo == MediaCodec.INFO_TRY_AGAIN_LATER -> if (sawInputEos) outputEos = true
                }
            }
            if (bCount > 0) buckets.add(sqrt(bSum / bCount))
            buckets.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { ex.release() }
        }
    }

    private suspend fun decodeWindow(context: Context, uri: Uri, startMs: Long, lengthMs: Long, strideMod: Int = 4): Pair<Double, Long>? {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(context, uri, null)
        } catch (e: Exception) {
            ex.release()
            return null
        }
        var track = -1
        var format: MediaFormat? = null
        for (i in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                track = i
                format = f
                break
            }
        }
        if (track < 0 || format == null) {
            ex.release()
            return null
        }
        ex.selectTrack(track)
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val codec = try {
            MediaCodec.createDecoderByType(mime)
        } catch (e: Exception) {
            ex.release()
            return null
        }
        return try {
            codec.configure(format, null, null, 0)
            codec.start()
            if (startMs > 0) {
                runCatching { ex.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC) }
            }
            val stopAt = if (startMs > 0) ex.sampleTime + lengthMs * 1000 else Long.MAX_VALUE
            var sum = 0.0
            var n = 0L
            val info = MediaCodec.BufferInfo()
            var sawInputEos = false
            var outputEos = false
            val t0 = System.currentTimeMillis()
            var windowDone = false
            while (!outputEos && !windowDone) {
                if (System.currentTimeMillis() - t0 > 30_000 || !coroutineContext.isActive) break
                if (!sawInputEos) {
                    val bi = codec.dequeueInputBuffer(5_000)
                    if (bi >= 0) {
                        val buf = codec.getInputBuffer(bi)!!
                        val read = ex.readSampleData(buf, 0)
                        if (read < 0 || (startMs > 0 && ex.sampleTime > stopAt)) {
                            codec.queueInputBuffer(bi, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEos = true
                        } else {
                            codec.queueInputBuffer(bi, 0, read, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val bo = codec.dequeueOutputBuffer(info, 5_000)
                when {
                    bo >= 0 -> {
                        val out: ByteBuffer = codec.getOutputBuffer(bo)!!
                        out.order(ByteOrder.nativeOrder())
                        var pos = out.position()
                        val end = pos + info.size
                        var stride = 0
                        while (pos + 1 < end) {
                            val v = out.getShort(pos).toInt()
                            if (stride % strideMod == 0) {
                                val f = v / 32768.0
                                sum += f * f
                                n++
                            }
                            stride++
                            pos += 2
                        }
                        if (startMs > 0 && info.presentationTimeUs >= stopAt) windowDone = true
                        codec.releaseOutputBuffer(bo, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputEos = true
                    }
                    bo == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {}
                    bo == MediaCodec.INFO_TRY_AGAIN_LATER -> if (sawInputEos) {
                        outputEos = true
                    }
                }
            }
            Pair(sum, n)
        } catch (e: Exception) {
            null
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { ex.release() }
        }
    }
}
