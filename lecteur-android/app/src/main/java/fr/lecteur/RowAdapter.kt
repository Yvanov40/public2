package fr.lecteur

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.color.MaterialColors
import com.google.android.material.progressindicator.LinearProgressIndicator

sealed class Row {
    class Header(val text: String, val action: String? = null, val onAction: (() -> Unit)? = null) : Row()
    class Note(val text: String, val icon: Int = 0) : Row()
    class Chips(val items: List<String>, val onClick: (String) -> Unit, val onLong: (String) -> Unit) : Row()
    class Video(val v: Vid, val onClick: () -> Unit, val onLong: () -> Unit) : Row()
    class Carousel(val items: List<Vid>, val onClick: (Vid) -> Unit, val onLong: (Vid) -> Unit) : Row()
    class Avatars(val items: List<Chan>, val onClick: (Chan) -> Unit, val onLong: (Chan) -> Unit) : Row()
    class Channel(
        val c: Chan, val subs: String?, val subscribed: Boolean,
        val onClick: () -> Unit, val onToggle: () -> Unit
    ) : Row()
    class ChannelHeader(val c: Chan, val subs: String?, val subscribed: Boolean, val onToggle: () -> Unit) : Row()
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

/** Une liste pour tout : titres, recherches, cartes vidéo, chaînes, fichiers, téléchargements. */
class RowAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private var rows: List<Row> = emptyList()

    fun submit(list: List<Row>) {
        rows = list
        notifyDataSetChanged()
    }

    override fun getItemCount() = rows.size

    override fun getItemViewType(position: Int) = when (rows[position]) {
        is Row.Header -> 0
        is Row.Note -> 1
        is Row.Chips -> 2
        is Row.Video -> 3
        is Row.Carousel -> 4
        is Row.Avatars -> 5
        is Row.Channel -> 6
        is Row.ChannelHeader -> 7
        is Row.Entry -> 8
        is Row.Progress -> 9
    }

    private class H(v: View) : RecyclerView.ViewHolder(v)

    private class StripHolder(v: View, val box: LinearLayout) : RecyclerView.ViewHolder(v)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        fun make(layout: Int) = H(inf.inflate(layout, parent, false))
        return when (viewType) {
            0 -> make(R.layout.item_header)
            1 -> make(R.layout.item_note)
            2, 4, 5 -> {
                val ctx = parent.context
                val d = ctx.resources.displayMetrics.density
                val box = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding((12 * d).toInt(), (4 * d).toInt(), (12 * d).toInt(), (4 * d).toInt())
                }
                val scroll = NestedHScroll(ctx).apply {
                    layoutParams = RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    isHorizontalScrollBarEnabled = false
                    addView(box)
                }
                StripHolder(scroll, box)
            }
            3 -> make(R.layout.item_video_card)
            6 -> make(R.layout.item_channel)
            7 -> make(R.layout.item_channel_header)
            8 -> make(R.layout.item_entry)
            else -> make(R.layout.item_download)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val v = holder.itemView
        when (val row = rows[position]) {
            is Row.Header -> {
                v.findViewById<TextView>(R.id.text).text = row.text
                val b = v.findViewById<Button>(R.id.action)
                b.visibility = if (row.action != null) View.VISIBLE else View.GONE
                b.text = row.action
                b.setOnClickListener { row.onAction?.invoke() }
            }
            is Row.Note -> {
                v.findViewById<TextView>(R.id.text).text = row.text
                val icon = v.findViewById<ImageView>(R.id.icon)
                icon.visibility = if (row.icon != 0) View.VISIBLE else View.GONE
                if (row.icon != 0) icon.setImageResource(row.icon)
            }
            is Row.Chips -> {
                val box = (holder as StripHolder).box
                box.removeAllViews()
                val d = box.resources.displayMetrics.density
                row.items.forEach { s ->
                    box.addView(Chip(box.context).apply {
                        text = s
                        isCheckable = false
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { marginEnd = (8 * d).toInt() }
                        setOnClickListener { row.onClick(s) }
                        setOnLongClickListener { row.onLong(s); true }
                    })
                }
            }
            is Row.Carousel -> {
                val box = (holder as StripHolder).box
                box.removeAllViews()
                val inf = LayoutInflater.from(box.context)
                row.items.forEach { item ->
                    val card = inf.inflate(R.layout.item_small_card, box, false)
                    card.findViewById<TextView>(R.id.title).text = item.title
                    card.findViewById<TextView>(R.id.sub).text = item.uploader
                    Thumbs.load(card.findViewById(R.id.thumb), item.thumb, R.drawable.ic_video)
                    card.setOnClickListener { row.onClick(item) }
                    card.setOnLongClickListener { row.onLong(item); true }
                    box.addView(card)
                }
            }
            is Row.Avatars -> {
                val box = (holder as StripHolder).box
                box.removeAllViews()
                val inf = LayoutInflater.from(box.context)
                row.items.forEach { c ->
                    val a = inf.inflate(R.layout.item_avatar, box, false)
                    a.findViewById<TextView>(R.id.name).text = c.name
                    Thumbs.load(a.findViewById(R.id.avatar), c.avatar, R.drawable.ic_person)
                    a.setOnClickListener { row.onClick(c) }
                    a.setOnLongClickListener { row.onLong(c); true }
                    box.addView(a)
                }
            }
            is Row.Video -> {
                v.findViewById<TextView>(R.id.title).text = row.v.title
                v.findViewById<TextView>(R.id.sub).text = row.v.meta ?: row.v.uploader
                val dur = v.findViewById<TextView>(R.id.duration)
                val ds = Yt.formatDuration(row.v.duration)
                dur.text = ds
                dur.visibility = if (ds.isEmpty()) View.GONE else View.VISIBLE
                Thumbs.load(v.findViewById(R.id.thumb), row.v.thumb, R.drawable.ic_video)
                v.setOnClickListener { row.onClick() }
                v.setOnLongClickListener { row.onLong(); true }
            }
            is Row.Channel -> {
                v.findViewById<TextView>(R.id.name).text = row.c.name
                v.findViewById<TextView>(R.id.sub).text = listOfNotNull("Chaîne", row.subs).joinToString(" · ")
                Thumbs.load(v.findViewById(R.id.avatar), row.c.avatar, R.drawable.ic_person)
                styleSubscribe(v.findViewById(R.id.subscribe), row.subscribed, row.onToggle)
                v.setOnClickListener { row.onClick() }
            }
            is Row.ChannelHeader -> {
                v.findViewById<TextView>(R.id.name).text = row.c.name
                val sub = v.findViewById<TextView>(R.id.sub)
                sub.text = row.subs
                sub.visibility = if (row.subs == null) View.GONE else View.VISIBLE
                Thumbs.load(v.findViewById(R.id.avatar), row.c.avatar, R.drawable.ic_person)
                styleSubscribe(v.findViewById(R.id.subscribe), row.subscribed, row.onToggle)
            }
            is Row.Entry -> {
                v.findViewById<TextView>(R.id.title).text = row.title
                val sub = v.findViewById<TextView>(R.id.sub)
                sub.text = row.subtitle
                sub.visibility = if (row.subtitle.isNullOrEmpty()) View.GONE else View.VISIBLE
                Thumbs.load(v.findViewById(R.id.thumb), row.thumb, row.icon)
                v.setOnClickListener { row.onClick() }
                val long = row.onLong
                if (long != null) v.setOnLongClickListener { long(); true } else v.setOnLongClickListener(null)
            }
            is Row.Progress -> {
                v.findViewById<ImageView>(R.id.kind).setImageResource(row.icon)
                v.findViewById<TextView>(R.id.title).text = row.title
                v.findViewById<TextView>(R.id.state).text = row.state
                val bar = v.findViewById<LinearProgressIndicator>(R.id.progress)
                val pct = row.pct
                if (pct == null) {
                    bar.visibility = View.GONE
                } else {
                    val indeterminate = pct < 0
                    if (bar.isIndeterminate != indeterminate) {
                        bar.visibility = View.INVISIBLE
                        bar.isIndeterminate = indeterminate
                    }
                    if (!indeterminate) bar.setProgressCompat(pct, true)
                    bar.visibility = View.VISIBLE
                }
                val action = v.findViewById<ImageButton>(R.id.action)
                action.setImageResource(row.actionIcon)
                action.setOnClickListener { row.onAction() }
                val click = row.onClick
                if (click != null) v.setOnClickListener { click() } else v.setOnClickListener(null)
            }
        }
    }

    private fun styleSubscribe(b: MaterialButton, subscribed: Boolean, onToggle: () -> Unit) {
        val bg = MaterialColors.getColor(
            b, if (subscribed) com.google.android.material.R.attr.colorSurfaceContainerHighest
            else com.google.android.material.R.attr.colorPrimary
        )
        val fg = MaterialColors.getColor(
            b, if (subscribed) com.google.android.material.R.attr.colorOnSurface
            else com.google.android.material.R.attr.colorOnPrimary
        )
        b.text = if (subscribed) "Abonné" else "S'abonner"
        b.backgroundTintList = ColorStateList.valueOf(bg)
        b.setTextColor(fg)
        b.setOnClickListener { onToggle() }
    }
}
