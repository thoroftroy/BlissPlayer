package com.blissplayer.music.ui

import android.graphics.Typeface
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.blissplayer.music.R
import com.blissplayer.music.model.Song

/** One row in library lists: either a song or a group header (artist/album/playlist). */
sealed interface Row {
    data class SongRow(val song: Song) : Row
    data class Group(val key: String, val title: String, val sub: String) : Row
}

class LibraryAdapter(
    var rows: List<Row> = emptyList(),
    var dark: Boolean = false,
    val onTap: (Row) -> Unit = {},
    val onMenu: (Row) -> Unit = {}
) : RecyclerView.Adapter<LibraryAdapter.Holder>() {

    class Holder(
        val box: LinearLayout,
        val title: TextView,
        val sub: TextView,
        val more: ImageButton
    ) : RecyclerView.ViewHolder(box)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val ctx = parent.context
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(20, 14, 8, 14)
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val texts = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val title = TextView(ctx).apply {
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setSingleLine(true)
        }
        val sub = TextView(ctx).apply {
            textSize = 12f
            setSingleLine(true)
        }
        texts.addView(title)
        texts.addView(sub)
        val more = ImageButton(ctx).apply {
            setImageDrawable(ContextCompat.getDrawable(ctx, R.drawable.ic_more))
            setBackgroundColor(0x00000000)
            contentDescription = "More options"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(20, 20, 20, 20)
        }
        box.addView(texts)
        box.addView(more)
        return Holder(box, title, sub, more)
    }

    override fun getItemCount() = rows.size

    override fun onBindViewHolder(h: Holder, pos: Int) {
        val row = rows[pos]
        when (row) {
            is Row.SongRow -> {
                h.title.text = row.song.title
                h.sub.text = "${row.song.artist} | ${row.song.album}"
            }
            is Row.Group -> {
                h.title.text = row.title
                h.sub.text = row.sub
            }
        }
        val tc = if (dark) 0xFFE8E8E8.toInt() else 0xFF000000.toInt()
        val sc = if (dark) 0xFFA8ADB5.toInt() else 0xFF5A5A5A.toInt()
        h.title.setTextColor(tc)
        h.sub.setTextColor(sc)
        h.more.setColorFilter(if (dark) 0xFFE8E8E8.toInt() else 0xFF000000.toInt())
        h.box.setBackgroundColor(
            if (dark) (if (pos % 2 == 0) 0xFF23262B.toInt() else 0xFF2E333A.toInt())
            else (if (pos % 2 == 0) 0xFFECE9D8.toInt() else 0xFFFFFFFF.toInt())
        )
        h.box.setOnClickListener { onTap(row) }
        h.box.setOnLongClickListener { onMenu(row); true }
        h.more.setOnClickListener { onMenu(row) }
    }

    fun submit(newRows: List<Row>) {
        rows = newRows
        notifyDataSetChanged()
    }
}
