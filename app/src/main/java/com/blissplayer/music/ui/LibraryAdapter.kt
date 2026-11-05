package com.blissplayer.music.ui

import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.blissplayer.music.R
import com.blissplayer.music.model.Song
import com.blissplayer.music.ui.theme.ThemeRenderers
import com.blissplayer.music.ui.theme.ThemeFonts
import com.blissplayer.music.ui.theme.MobileTheme

/** One row in library lists: either a song or a group header (artist/album/playlist). */
sealed interface Row {
    data class SongRow(val song: Song) : Row
    data class Group(val key: String, val title: String, val sub: String) : Row
    /** Slim divider, e.g. between Favorites and the other playlists. */
    data object Sep : Row
}

class LibraryAdapter(
    var rows: List<Row> = emptyList(),
    var dark: Boolean = false,
    /** mediaId of the currently playing song, tinted like an XP selection. */
    var highlightId: Long? = null,
    /** Extra small stats line under the duration, keyed by mediaId. */
    var showStats: Boolean = false,
    var statLines: Map<Long, String> = emptyMap(),
    /** Display album per song (overrides folder-name albums), keyed by mediaId. */
    var albumLines: Map<Long, String> = emptyMap(),
    /** When true, song rows show track numbers (playlist order). */
    var numbered: Boolean = false,
    /** Favorite ids: star filled when present. Empty = no stars shown. */
    var favoriteIds: Set<Long> = emptySet(),
    /** Favorites playlist row id: gets the star instead of the folder. */
    var favoritesPlaylistId: Long? = null,
    /** Theme id ("bliss", "dark", "aqua", "super"): tints the favorite star. */
    var themeId: String = "bliss",
    /** Full motion: Super gets the animated rainbow star; otherwise static. */
    var motionFull: Boolean = true,
    val onTap: (Row) -> Unit = {},
    val onMenu: (Row) -> Unit = {},
    val onFav: ((Song) -> Unit)? = null
) : RecyclerView.Adapter<LibraryAdapter.Holder>() {

    class Holder(
        val box: LinearLayout,
        val icon: ImageView,
        val title: TextView,
        val sub: TextView,
        val dur: TextView,
        val fav: ImageButton,
        val more: ImageButton
    ) : RecyclerView.ViewHolder(box)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val ctx = parent.context
        fun dp(value: Int) = (value * ctx.resources.displayMetrics.density).toInt()
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(6), dp(4), dp(3), dp(4))
            minimumHeight = dp(56)
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val icon = ImageView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply { rightMargin = dp(8) }
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val texts = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val title = TextView(ctx).apply {
            textSize = 15f
            typeface = runCatching {
                androidx.core.content.res.ResourcesCompat.getFont(ctx, R.font.ptsans_700)
            }.getOrNull() ?: Typeface.DEFAULT_BOLD
            setSingleLine(true)
            marquee()
        }
        val sub = TextView(ctx).apply {
            textSize = 12f
            marquee()
        }
        texts.addView(title)
        texts.addView(sub)
        val dur = TextView(ctx).apply {
            textSize = 10f
            setPadding(8, 0, 8, 0)
            gravity = android.view.Gravity.END
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            maxWidth = (150 * resources.displayMetrics.density).toInt()
        }
        val more = ImageButton(ctx).apply {
            setImageDrawable(ContextCompat.getDrawable(ctx, R.drawable.ic_more))
            setBackgroundResource(if (dark) R.drawable.xp_button_dark else R.drawable.xp_button)
            contentDescription = "More options"
            layoutParams = LinearLayout.LayoutParams(
                dp(40), dp(44)
            )
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val fav = ImageButton(ctx).apply {
            // No button face: the star floats on its own. (The dots keep theirs.)
            background = null
            contentDescription = "Favorite"
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(44))
            setPadding(dp(6), dp(6), dp(6), dp(6))
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = false
        }
        box.addView(icon)
        box.addView(texts)
        box.addView(dur)
        box.addView(fav)
        box.addView(more)
        return Holder(box, icon, title, sub, dur, fav, more)
    }

    override fun getItemCount() = rows.size

    override fun onBindViewHolder(h: Holder, pos: Int) {
        val row = rows[pos]
        val d = h.box.resources.displayMetrics.density
        if (row is Row.Sep) {
            // Slim breathing room between sections. Everything hidden so a
            // recycled separator never leaks, and vice versa (reset below).
            h.icon.visibility = android.view.View.GONE
            h.more.visibility = android.view.View.GONE
            h.fav.visibility = android.view.View.GONE
            h.fav.setOnClickListener(null)
            h.dur.visibility = android.view.View.GONE
            h.title.text = ""
            h.sub.text = ""
            h.box.minimumHeight = (10 * d).toInt()
            h.box.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            h.box.setOnClickListener(null)
            h.box.setOnLongClickListener(null)
            return
        }
        h.icon.visibility = android.view.View.VISIBLE
        h.more.visibility = android.view.View.VISIBLE
        h.box.minimumHeight = (56 * d).toInt()
        val selected = highlightId != null && (row as? Row.SongRow)?.song?.mediaId == highlightId
        h.icon.setImageResource(if (row is Row.Group) R.drawable.xp_folder else R.drawable.xp_audio_file)
        // Favorite star: the user's filled art when loved, outline otherwise.
        if (row is Row.SongRow && onFav != null) {
            val loved = favoriteIds.contains(row.song.mediaId)
            h.fav.visibility = android.view.View.VISIBLE
            styleFav(h, loved)
            h.fav.contentDescription = if (loved) "Unfavorite ${row.song.title}" else "Favorite ${row.song.title}"
            h.fav.setOnClickListener { onFav?.invoke(row.song) }
        } else {
            h.fav.visibility = android.view.View.GONE
            h.fav.setOnClickListener(null)
        }
        when (row) {
            is Row.SongRow -> {
                h.title.text = if (numbered) "${pos + 1}. ${row.song.title}" else row.song.title
                h.sub.text = "${row.song.artist} | ${albumLines[row.song.mediaId] ?: row.song.album}"
                val base = fmt(row.song.durationMs)
                if (showStats) {
                    h.dur.setSingleLine(false)
                    h.dur.maxLines = 2
                    h.dur.text = base + "\n" + (statLines[row.song.mediaId] ?: "")
                    h.dur.visibility = android.view.View.VISIBLE
                } else {
                    h.dur.setSingleLine(true)
                    h.dur.maxLines = 1
                    h.dur.text = base
                    h.dur.visibility = android.view.View.VISIBLE
                }
            }
            is Row.Group -> {
                h.title.text = if (numbered && row.key.startsWith("pl:")) "${pos + 1}. ${row.title}" else row.title
                h.sub.text = row.sub
                h.dur.text = ""
                h.dur.visibility = android.view.View.GONE
            }
            is Row.Sep -> { /* handled above; never reaches here */ }
        }
        val renderer = ThemeRenderers.get(h.box.context)
        if (renderer != null && renderer.theme.modern) {
            renderer.row(h.box, h.title, h.sub, h.dur, h.more, h.icon, selected, row is Row.Group, h.fav)
        } else {
        val theme = MobileTheme.from(themeId)
        ThemeFonts.apply(h.title, theme, true)
        ThemeFonts.apply(h.sub, theme, false)
        ThemeFonts.apply(h.dur, theme, false)
        // Selected rows keep white icons; unselected follow the theme.
        val tc = if (dark) 0xFFE8E8E8.toInt() else 0xFF000000.toInt()
        val sc = if (dark) 0xFFA8ADB5.toInt() else 0xFF5A5A5A.toInt()
        h.title.setTextColor(if (selected) 0xFFFFFFFF.toInt() else tc)
        h.sub.setTextColor(if (selected) 0xFFFFFFFF.toInt() else sc)
        h.dur.setTextColor(if (selected) 0xFFFFFFFF.toInt() else sc)
        // Dots keep the theme color even on a selected row: the button face
        // stays beige, so white dots would vanish on it.
        h.more.setColorFilter(tc)
        h.box.setBackgroundResource(0)
        // Playing row: XP blue with the classic dotted focus frame.
        // Idle rows keep the striped face.
        if (selected) {
            h.box.setBackgroundResource(
                if (dark) R.drawable.xp_row_selected_dark else R.drawable.xp_row_selected
            )
        } else {
            h.box.setBackgroundColor(
                if (dark) {
                    if (pos % 2 == 0) 0xFF1B222D.toInt() else 0xFF252E3C.toInt()
                } else {
                    if (pos % 2 == 0) 0xFFFFFFFF.toInt() else 0xFFF0F5FC.toInt()
                }
            )
        }
        }
        h.box.setOnClickListener { onTap(row) }
        h.box.setOnLongClickListener { onMenu(row); true }
        h.more.setOnClickListener { onMenu(row) }
        // The Favorites playlist gets the star, not the folder.
        val favKey = favoritesPlaylistId?.let { "pl:$it" }
        if (favKey != null && (row as? Row.Group)?.key == favKey) {
            h.icon.setImageResource(R.drawable.fav_star_filled)
            h.icon.clearColorFilter()
        }
    }

    override fun onViewRecycled(holder: Holder) {
        holder.box.animate().cancel()
        holder.box.scaleX = 1f
        holder.box.scaleY = 1f
        holder.more.animate().cancel()
        holder.more.scaleX = 1f
        holder.more.scaleY = 1f
        super.onViewRecycled(holder)
    }

    fun submit(newRows: List<Row>) {
        rows = newRows
        notifyDataSetChanged()
    }

    /**
     * Per-theme favorite star. Retro themes use flat shapes (Bliss light
     * yellow loved, Dark gold loved, gray empty). Aqua and Super use the
     * textured art: blue-shifted for Aqua, animated rainbow for Super.
     */
    private fun styleFav(h: Holder, loved: Boolean) {
        h.fav.clearColorFilter()
        val retro = themeId == "bliss" || themeId == "dark"
        if (!loved) {
            if (retro) {
                h.fav.setImageResource(R.drawable.fav_star_flat_outline)
                h.fav.setColorFilter(
                    if (themeId == "dark") 0xFF9A9A9A.toInt() else 0xFF7A7A7A.toInt(),
                    android.graphics.PorterDuff.Mode.SRC_IN
                )
            } else {
                // Pre-lightened art, no runtime filter: tint modes would
                // flood the transparent padding into an opaque box.
                h.fav.setImageResource(R.drawable.fav_star_outline_lt)
            }
            h.fav.tag = null
            return
        }
        h.fav.tag = "loved"
        when (themeId) {
            "super" -> {
                h.fav.setImageDrawable(
                    com.blissplayer.music.ui.theme.FavStarDrawable.rainbowOf(h.fav.context, motionFull)
                )
            }
            "aqua" -> {
                h.fav.setImageDrawable(
                    com.blissplayer.music.ui.theme.FavStarDrawable.bluedOf(h.fav.context)
                )
            }
            "dark" -> {
                h.fav.setImageResource(R.drawable.fav_star_flat)
            }
            else -> {
                h.fav.setImageResource(R.drawable.fav_star_flat_light)
            }
        }
    }

    private fun fmt(ms: Long): String {
        val s = (ms / 1000).toInt().coerceAtLeast(0)
        return "%d:%02d".format(s / 60, s % 60)
    }
}
