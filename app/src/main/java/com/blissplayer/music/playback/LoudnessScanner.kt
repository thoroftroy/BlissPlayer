package com.blissplayer.music.playback

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.blissplayer.music.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * One-tap loudness equalizer.
 * Measures each song's RMS loudness by decoding it, takes the library average,
 * then stores per-song multipliers (louder songs turned down, quiet ones up).
 * Non-destructive: gains live in the app DB, originals untouched, Undo = one tap.
 */
object LoudnessScanner {

    data class Result(
        val measured: Int,
        val raised: Int,
        val lowered: Int,
        val skipped: Int
    )

    suspend fun equalizeAll(
        context: Context,
        songs: List<Song>,
        putGain: (mediaId: Long, mult: Double) -> Unit,
        onProgress: (done: Int, total: Int, title: String) -> Unit
    ): Result = withContext(Dispatchers.Default) {
        val rms = LinkedHashMap<Long, Double>()
        var skipped = 0
        songs.forEachIndexed { i, s ->
            if (!isActive) return@withContext Result(rms.size, 0, 0, skipped)
            onProgress(i, songs.size, s.title)
            val r = runCatching { measureRms(context, Uri.parse(s.contentUri)) }.getOrNull()
            if (r == null || r <= 0.0) skipped++ else rms[s.mediaId] = r
        }
        if (rms.isEmpty()) return@withContext Result(0, 0, 0, skipped)
        val avg = rms.values.average()
        var raised = 0
        var lowered = 0
        rms.forEach { (id, r) ->
            var mult = avg / r
            // ±12 dB window: smooth, never pumps or distorts.
            mult = mult.coerceIn(0.25, 4.0)
            putGain(id, mult)
            if (mult > 1.03) raised++ else if (mult < 0.97) lowered++
        }
        Result(rms.size, raised, lowered, skipped)
    }

    /** Decode to 16-bit PCM and return RMS (0..1). Null if the format can't decode. */
    private fun measureRms(context: Context, uri: Uri): Double? {
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
            var sum = 0.0
            var n = 0L
            val info = MediaCodec.BufferInfo()
            var sawInputEos = false
            var outputEos = false
            val t0 = System.currentTimeMillis()
            while (!outputEos) {
                if (System.currentTimeMillis() - t0 > 60_000) break // 60s guard per song
                if (!sawInputEos) {
                    val bi = codec.dequeueInputBuffer(5_000)
                    if (bi >= 0) {
                        val buf = codec.getInputBuffer(bi)!!
                        val read = ex.readSampleData(buf, 0)
                        if (read < 0) {
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
                        // Count every 8th sample: plenty for RMS, ~8x faster.
                        out.order(ByteOrder.nativeOrder())
                        var pos = out.position()
                        val end = pos + info.size
                        var stride = 0
                        while (pos + 1 < end) {
                            val v = out.getShort(pos).toInt()
                            if (stride % 8 == 0) {
                                val f = v / 32768.0
                                sum += f * f
                                n++
                            }
                            stride++
                            pos += 2
                        }
                        codec.releaseOutputBuffer(bo, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputEos = true
                    }
                    bo == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {}
                    bo == MediaCodec.INFO_TRY_AGAIN_LATER -> if (sawInputEos) outputEos = true
                }
            }
            if (n == 0L) null else sqrt(sum / n)
        } catch (e: Exception) {
            null
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { ex.release() }
        }
    }
}
