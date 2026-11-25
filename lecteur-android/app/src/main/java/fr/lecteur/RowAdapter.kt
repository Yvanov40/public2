package fr.lecteur

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

sealed class Row {
    class Header(val text: String, val small: Boolean = false) : Row()
    class Chips(val items: List<String>, val onClick: (String) -> Unit, val onLong: (String) -> Unit) : Row()
    class Entry(
        val title: String,
        val subtitle: String?,
        val thumb: String?,
        val icon: Int,
        val onClick: () -> Unit,
        val onLong: (() -> Unit)? = null
    ) : Row()
    class Progress(
        val title: String,
        val state: String,
        val pct: Int?,
        val icon: Int,
        val actionIcon: Int,
        val onAction: () -> Unit,
        val onClick: (() -> Unit)? = null
    ) : Row()
}

/** Une seule liste pour tout : titres, recherches récentes, vidéos, fichiers, dossiers. */
class RowAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private var rows: List<Row> = emptyList()

    fun submit(list: List<Row>) {
        rows = list
        notifyDataSetChanged()
    }

    override fun getItemCount() = rows.size

    override fun getItemViewType(position: Int) = when (rows[position]) {
        is Row.Header -> 0
        is Row.Chips -> 1
        is Row.Entry -> 2
        is Row.Progress -> 3
    }

    private class HeaderHolder(val text: TextView) : RecyclerView.ViewHolder(text)
    private class ChipsHolder(scroll: View, val box: LinearLayout) : RecyclerView.ViewHolder(scroll)
    private class EntryHolder(v: View) : RecyclerView.ViewHolder(v) {
        val thumb: ImageView = v.findViewById(R.id.thumb)
        val title: TextView = v.findViewById(R.id.title)
        val sub: TextView = v.findViewById(R.id.sub)
    }

    private class ProgressHolder(v: View) : RecyclerView.ViewHolder(v) {
        val kind: ImageView = v.findViewById(R.id.kind)
        val title: TextView = v.findViewById(R.id.title)
        val state: TextView = v.findViewById(R.id.state)
        val bar: android.widget.ProgressBar = v.findViewById(R.id.progress)
        val action: android.widget.ImageButton = v.findViewById(R.id.action)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val ctx = parent.context
        val d = ctx.resources.displayMetrics.density
        return when (viewType) {
            0 -> HeaderHolder(TextView(ctx).apply {
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setPadding((12 * d).toInt(), (14 * d).toInt(), (12 * d).toInt(), (6 * d).toInt())
            })
            1 -> {
                val box = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding((8 * d).toInt(), (2 * d).toInt(), (8 * d).toInt(), (4 * d).toInt())
                }
                val scroll = HorizontalScrollView(ctx).apply {
                    layoutParams = RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    isHorizontalScrollBarEnabled = false
                    addView(box)
                }
                ChipsHolder(scroll, box)
            }
            3 -> ProgressHolder(LayoutInflater.from(ctx).inflate(R.layout.item_download, parent, false))
            else -> EntryHolder(LayoutInflater.from(ctx).inflate(R.layout.item_video, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Header -> (holder as HeaderHolder).text.apply {
                text = row.text
                if (row.small) {
                    textSize = 14f
                    setTypeface(null, Typeface.NORMAL)
                    setTextColor(attrColor(context, android.R.attr.textColorSecondary))
                } else {
                    textSize = 16f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(attrColor(context, android.R.attr.textColorPrimary))
                }
            }
            is Row.Chips -> {
                val box = (holder as ChipsHolder).box
                val ctx = box.context
                val d = ctx.resources.displayMetrics.density
                box.removeAllViews()
                row.items.forEach { s ->
                    box.addView(TextView(ctx).apply {
                        text = s
                        textSize = 14f
                        maxLines = 1
                        background = ContextCompat.getDrawable(ctx, R.drawable.chip_bg)
                        setTextColor(attrColor(ctx, android.R.attr.textColorPrimary))
                        setPadding((14 * d).toInt(), (8 * d).toInt(), (14 * d).toInt(), (8 * d).toInt())
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { marginEnd = (8 * d).toInt() }
                        setOnClickListener { row.onClick(s) }
                        setOnLongClickListener { row.onLong(s); true }
                    })
                }
            }
            is Row.Progress -> {
                val h = holder as ProgressHolder
                h.kind.setImageResource(row.icon)
                h.title.text = row.title
                h.state.text = row.state
                val pct = row.pct
                if (pct == null) {
                    h.bar.visibility = View.GONE
                } else {
                    h.bar.visibility = View.VISIBLE
                    h.bar.isIndeterminate = pct < 0
                    if (pct >= 0) h.bar.progress = pct
                }
                h.action.setImageResource(row.actionIcon)
                h.action.setOnClickListener { row.onAction() }
                val click = row.onClick
                if (click != null) h.itemView.setOnClickListener { click() }
                else h.itemView.setOnClickListener(null)
            }
            is Row.Entry -> {
                val h = holder as EntryHolder
                h.title.text = row.title
                h.sub.text = row.subtitle
                h.sub.visibility = if (row.subtitle.isNullOrEmpty()) View.GONE else View.VISIBLE
                Thumbs.load(h.thumb, row.thumb, row.icon)
                h.itemView.setOnClickListener { row.onClick() }
                val long = row.onLong
                if (long != null) h.itemView.setOnLongClickListener { long(); true }
                else h.itemView.setOnLongClickListener(null)
            }
        }
    }

    private fun attrColor(ctx: Context, attr: Int): ColorStateList? {
        val tv = TypedValue()
        if (!ctx.theme.resolveAttribute(attr, tv, true)) return null
        return if (tv.resourceId != 0) ContextCompat.getColorStateList(ctx, tv.resourceId)
        else ColorStateList.valueOf(tv.data)
    }
}
