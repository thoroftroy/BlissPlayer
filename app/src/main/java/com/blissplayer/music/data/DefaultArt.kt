package com.blissplayer.music.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.blissplayer.music.R

/**
 * The Bliss hill as the one true default artwork: notification background,
 * lock-screen/session art, anywhere a song has no better image. Center-cropped
 * full-bleed (cover, never stretched). Cached; safe to call anywhere.
 */
object DefaultArt {
    private const val SIZE = 512

    @Volatile private var bitmap: Bitmap? = null

    private fun load(ctx: Context): Bitmap? = runCatching {
        val opts = BitmapFactory.Options().apply { inScaled = false }
        val src = BitmapFactory.decodeResource(ctx.resources, R.drawable.notif_bg_full, opts)
            ?: return null
        val scale = maxOf(SIZE.toFloat() / src.width, SIZE.toFloat() / src.height)
        val sw = (SIZE / scale).toInt().coerceIn(1, src.width)
        val sh = (SIZE / scale).toInt().coerceIn(1, src.height)
        val ox = ((src.width - sw) / 2).coerceAtLeast(0)
        val oy = ((src.height - sh) / 2).coerceAtLeast(0)
        val cropped = Bitmap.createBitmap(src, ox, oy, sw, sh)
        val out = Bitmap.createScaledBitmap(cropped, SIZE, SIZE, true)
        if (cropped != src) runCatching { cropped.recycle() }
        if (src.width * src.height > SIZE * SIZE * 2) runCatching { src.recycle() }
        out
    }.getOrNull()

    /** Full-bleed square bitmap for large icons and custom views. */
    fun bitmap(ctx: Context): Bitmap? {
        bitmap?.let { return it }
        synchronized(this) {
            bitmap?.let { return it }
            val b = load(ctx.applicationContext)
            if (b != null) bitmap = b
            return b
        }
    }

    /**
     * Resource URI for MediaSession artwork: the system card loads the hill
     * itself (full quality) instead of embedded pics. A URI costs nothing per
     * queue item; inline bytes get cloned by Media3 once per item and OOM.
     */
    fun artworkUri(ctx: Context): android.net.Uri =
        android.net.Uri.parse("android.resource://${ctx.packageName}/${R.drawable.notif_bg_full}")
}
