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

    private val PARALLEL = Runtime.getRuntime().availableProcessors().coerceIn(4, 8)
    private const val WINDOW_MS = 8000L

    suspend fun equalizeAll(
        context: Context,
        songs: List<Song>,
        cached: Map<Long, Cached>,
        strength: Float = 1f,
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
                    val r = runCatching { measureSampled(context, Uri.parse(s.contentUri), s.durationMs) }.getOrNull()
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
        val avg = rms.values.average()
        // Strength blends full correction toward doing nothing: 100 percent
        // lands every song on the average, 0 percent leaves volumes alone.
        val k = strength.coerceIn(0f, 1f).toDouble()
        var raised = 0
        var lowered = 0
        rms.forEach { (id, r) ->
            val full = avg / r
            val mult = (1.0 + (full - 1.0) * k).coerceIn(0.25, 4.0) // 12 dB window either way
            runCatching { putGain(id, mult) }
            if (mult > 1.03) raised++ else if (mult < 0.97) lowered++
        }
        Result(rms.size, reused, raised, lowered, skipped)
    }

    /**
     * RMS from two 8 second windows (about 15 percent and 55 percent in).
     * Skips intros and dead air at the edges, much faster than full decode.
     */
    private suspend fun measureSampled(context: Context, uri: Uri, durationMs: Long): Double? {
        val starts = if (durationMs > 30_000) {
            listOf(durationMs * 15 / 100, durationMs * 55 / 100)
        } else {
            listOf(0L)
        }
        var sum = 0.0
        var n = 0L
        for (start in starts) {
            val (s, c) = decodeWindow(context, uri, start, WINDOW_MS) ?: return null
            sum += s
            n += c
            if (!coroutineContext.isActive) return null
        }
        if (n == 0L) return null
        return sqrt(sum / n)
    }

    private suspend fun decodeWindow(context: Context, uri: Uri, startMs: Long, lengthMs: Long): Pair<Double, Long>? {
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
                            if (stride % 4 == 0) {
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
