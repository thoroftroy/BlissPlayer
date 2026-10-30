package com.blissplayer.music.ui

import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
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
    val onLong: (Row) -> Unit = {}
) : RecyclerView.Adapter<LibraryAdapter.Holder>() {

    class Holder(val box: LinearLayout, val title: TextView, val sub: TextView) :
        RecyclerView.ViewHolder(box)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val ctx = parent.context
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 18, 24, 18)
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
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
        box.addView(title)
        box.addView(sub)
        return Holder(box, title, sub)
    }

    override fun getItemCount() = rows.size

    override fun onBindViewHolder(h: Holder, pos: Int) {
        val row = rows[pos]
        when (row) {
            is Row.SongRow -> {
                h.title.text = row.song.title
                h.sub.text = "${row.song.artist} • ${row.song.album}"
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
        h.box.setBackgroundColor(
            if (dark) (if (pos % 2 == 0) 0xFF23262B.toInt() else 0xFF2E333A.toInt())
            else (if (pos % 2 == 0) 0xFFECE9D8.toInt() else 0xFFFFFFFF.toInt())
        )
        h.box.setOnClickListener { onTap(row) }
        h.box.setOnLongClickListener { onLong(row); true }
        h.box.visibility = View.VISIBLE
    }

    fun submit(newRows: List<Row>) {
        rows = newRows
        notifyDataSetChanged()
    }
}
