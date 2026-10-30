package com.blissplayer.music.ui

import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** Rows for the Add songs picker: headers plus checkable artists, albums, songs. */
sealed interface PickRow {
    data class Header(val title: String) : PickRow
    data class Item(val key: String, val title: String, val sub: String) : PickRow
}

class PickAdapter(
    var rows: List<PickRow> = emptyList(),
    val checked: MutableSet<String> = mutableSetOf(),
    var dark: Boolean = false,
    val onToggle: () -> Unit = {}
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ITEM = 1
    }

    override fun getItemViewType(pos: Int) =
        if (rows[pos] is PickRow.Header) TYPE_HEADER else TYPE_ITEM

    override fun getItemCount() = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val ctx = parent.context
        return if (viewType == TYPE_HEADER) {
            val tv = TextView(ctx).apply {
                textSize = 13f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setPadding(20, 18, 20, 6)
            }
            object : RecyclerView.ViewHolder(tv) {}
        } else {
            val cb = CheckBox(ctx).apply {
                textSize = 14f
                setPadding(12, 10, 12, 10)
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            object : RecyclerView.ViewHolder(cb) {}
        }
    }

    override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
        when (val row = rows[pos]) {
            is PickRow.Header -> {
                val tv = h.itemView as TextView
                tv.text = row.title
                tv.setTextColor(if (dark) 0xFFA8ADB5.toInt() else 0xFF5A5A5A.toInt())
            }
            is PickRow.Item -> {
                val cb = h.itemView as CheckBox
                cb.text = if (row.sub.isBlank()) row.title else "${row.title}\n${row.sub}"
                cb.setTextColor(if (dark) 0xFFE8E8E8.toInt() else 0xFF000000.toInt())
                cb.buttonTintList = android.content.res.ColorStateList.valueOf(0xFF0058E6.toInt())
                cb.setOnCheckedChangeListener(null)
                cb.isChecked = row.key in checked
                cb.setOnCheckedChangeListener { _, on ->
                    if (on) checked.add(row.key) else checked.remove(row.key)
                    onToggle()
                }
            }
        }
    }

    fun submit(newRows: List<PickRow>) {
        rows = newRows
        notifyDataSetChanged()
    }
}
