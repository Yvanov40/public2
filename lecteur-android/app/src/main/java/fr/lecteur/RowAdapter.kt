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
import com.google.android.material.chip.ChipDrawable
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
        val onClick: () -> Unit, val onToggle: () -> Unit, val kind: String = "Chaîne"
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
    class Choice(val title: String, val sub: String?, val labels: List<String>, val selected: Int, val onSelect: (Int) -> Unit) : Row()
    class Slider(
        val title: String, val sub: String?, val value: Float, val from: Float, val to: Float, val step: Float,
        val format: (Float) -> String, val onChange: (Float) -> Unit
    ) : Row()
    class Toggle(val title: String, val sub: String?, val checked: Boolean, val onChange: (Boolean) -> Unit) : Row()
    class Colors(val title: String, val items: List<Pair<String, Int>>, val selected: Int, val onSelect: (Int) -> Unit) : Row()
    class Track(
        val title: String, val sub: String?, val thumb: String?, val number: String? = null,
        val playing: Boolean = false, val liked: Boolean = false, val round: Boolean = false,
        val onClick: () -> Unit, val onLong: (() -> Unit)? = null
    ) : Row()
    class Card(val title: String, val sub: String?, val thumb: String?, val round: Boolean = false, val onClick: () -> Unit, val onLong: (() -> Unit)? = null)
    class Cards(val items: List<Card>) : Row()
    class MusicHeader(
        val title: String, val sub: String?, val thumb: String?, val round: Boolean,
        val onPlay: (() -> Unit)?, val onShuffle: (() -> Unit)?, val onMore: (() -> Unit)? = null,
        val saveLabel: String? = null, val saved: Boolean = false, val onSave: (() -> Unit)? = null
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
        is Row.Video -> if (Settings.cards) 3 else 10
        is Row.Carousel -> 4
        is Row.Avatars -> 5
        is Row.Channel -> 6
        is Row.ChannelHeader -> 7
        is Row.Entry -> 8
        is Row.Progress -> 9
        is Row.Choice -> 11
        is Row.Slider -> 12
        is Row.Toggle -> 13
        is Row.Colors -> 14
        is Row.Track -> 15
        is Row.Cards -> 16
        is Row.MusicHeader -> 17
    }

    /** Nombre de colonnes occupées : seules les cartes vidéo se partagent la largeur. */
    fun spanSize(position: Int, spanCount: Int): Int =
        if (position in rows.indices && rows[position] is Row.Video && Settings.cards) 1 else spanCount

    private class H(v: View) : RecyclerView.ViewHolder(v)

    private class StripHolder(v: View, val box: LinearLayout) : RecyclerView.ViewHolder(v)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        fun make(layout: Int) = H(inf.inflate(layout, parent, false).also { Ui.applyFont(it) })
        return when (viewType) {
            10 -> make(R.layout.item_video_row)
            11, 12, 13, 14 -> make(R.layout.item_setting)
            0 -> make(R.layout.item_header)
            1 -> make(R.layout.item_note)
            15 -> make(R.layout.item_track)
            17 -> make(R.layout.item_music_header)
            2, 4, 5, 16 -> {
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
                        Ui.applyFont(this)
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
                    card.findViewById<TextView>(R.id.sub).text = item.meta ?: item.uploader
                    Thumbs.load(card.findViewById(R.id.thumb), item.thumb, R.drawable.ic_video)
                    card.setOnClickListener { row.onClick(item) }
                    card.setOnLongClickListener { row.onLong(item); true }
                    Ui.applyFont(card)
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
                    Ui.applyFont(a)
                    box.addView(a)
                }
            }
            is Row.Video -> {
                val scale = Settings.tileScale
                val d = v.resources.displayMetrics.density
                val title = v.findViewById<TextView>(R.id.title)
                val sub = v.findViewById<TextView>(R.id.sub)
                if (Settings.cards) {
                    val cols = Settings.columns
                    val f = scale * when (cols) { 1 -> 1f; 2 -> 0.9f; else -> 0.8f }
                    title.textSize = 15f * f
                    sub.textSize = 12.5f * f
                    title.maxLines = if (cols >= 3) 3 else 2
                    val ph = ((if (cols > 1) 5 else 12) * d).toInt()
                    v.setPadding(ph, v.paddingTop, ph, v.paddingBottom)
                } else {
                    val box = v.findViewById<View>(R.id.thumbBox)
                    val w = (150 * scale * d).toInt()
                    box.layoutParams = box.layoutParams.apply { width = w; height = w * 9 / 16 }
                    title.textSize = 14.5f * scale
                    sub.textSize = 12f * scale
                }
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
                v.findViewById<TextView>(R.id.sub).text = listOfNotNull(row.kind, row.subs).joinToString(" · ")
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
            is Row.Choice -> {
                val content = settingBase(v, row.title, row.sub, null)
                val d = v.resources.displayMetrics.density
                val box = LinearLayout(v.context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding((12 * d).toInt(), (4 * d).toInt(), (12 * d).toInt(), 0)
                }
                row.labels.forEachIndexed { i, label ->
                    val chip = Chip(v.context)
                    chip.setChipDrawable(
                        ChipDrawable.createFromAttributes(
                            v.context, null, 0, com.google.android.material.R.style.Widget_Material3_Chip_Filter
                        )
                    )
                    chip.text = label
                    chip.isCheckable = true
                    chip.isChecked = i == row.selected
                    chip.layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { marginEnd = (8 * d).toInt() }
                    chip.setOnClickListener { if (i != row.selected) row.onSelect(i) else chip.isChecked = true }
                    Ui.applyFont(chip)
                    box.addView(chip)
                }
                content.addView(NestedHScroll(v.context).apply {
                    isHorizontalScrollBarEnabled = false
                    addView(box)
                })
            }
            is Row.Slider -> {
                val valueView = v.findViewById<TextView>(R.id.value)
                val content = settingBase(v, row.title, row.sub, row.format(row.value))
                val d = v.resources.displayMetrics.density
                val slider = com.google.android.material.slider.Slider(v.context).apply {
                    valueFrom = row.from
                    valueTo = row.to
                    stepSize = row.step
                    value = row.value.coerceIn(row.from, row.to)
                    setLabelFormatter { row.format(it) }
                    addOnChangeListener { _, value, _ -> valueView.text = row.format(value) }
                    addOnSliderTouchListener(object : com.google.android.material.slider.Slider.OnSliderTouchListener {
                        override fun onStartTrackingTouch(slider: com.google.android.material.slider.Slider) {}
                        override fun onStopTrackingTouch(slider: com.google.android.material.slider.Slider) {
                            row.onChange(slider.value)
                        }
                    })
                }
                slider.setPadding((4 * d).toInt(), 0, (4 * d).toInt(), 0)
                content.addView(slider)
            }
            is Row.Toggle -> {
                settingBase(v, row.title, row.sub, null)
                val t = v.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.toggle)
                t.visibility = View.VISIBLE
                t.setOnCheckedChangeListener(null)
                t.isChecked = row.checked
                t.setOnCheckedChangeListener { _, c -> row.onChange(c) }
                v.setOnClickListener { t.toggle() }
            }
            is Row.Colors -> {
                val content = settingBase(v, row.title, null, row.items.getOrNull(row.selected)?.first)
                val d = v.resources.displayMetrics.density
                val box = LinearLayout(v.context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding((12 * d).toInt(), (8 * d).toInt(), (12 * d).toInt(), (4 * d).toInt())
                }
                val ring = MaterialColors.getColor(v, com.google.android.material.R.attr.colorOnSurface)
                row.items.forEachIndexed { i, (_, color) ->
                    val dot = View(v.context)
                    val bg = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        if (color == 0) {
                            gradientType = android.graphics.drawable.GradientDrawable.SWEEP_GRADIENT
                            colors = intArrayOf(
                                0xFFE53935.toInt(), 0xFFFDD835.toInt(), 0xFF43A047.toInt(),
                                0xFF1E88E5.toInt(), 0xFF8E24AA.toInt(), 0xFFE53935.toInt()
                            )
                        } else setColor(color)
                        if (i == row.selected) setStroke((3 * d).toInt(), ring)
                    }
                    dot.background = bg
                    dot.contentDescription = row.items[i].first
                    dot.layoutParams = LinearLayout.LayoutParams((40 * d).toInt(), (40 * d).toInt())
                        .apply { marginEnd = (12 * d).toInt() }
                    dot.setOnClickListener { row.onSelect(i) }
                    box.addView(dot)
                }
                content.addView(NestedHScroll(v.context).apply {
                    isHorizontalScrollBarEnabled = false
                    addView(box)
                })
            }
            is Row.Track -> {
                val title = v.findViewById<TextView>(R.id.title)
                title.text = row.title
                title.setTextColor(
                    MaterialColors.getColor(
                        v, if (row.playing) com.google.android.material.R.attr.colorPrimary
                        else com.google.android.material.R.attr.colorOnSurface
                    )
                )
                val sub = v.findViewById<TextView>(R.id.sub)
                sub.text = row.sub
                sub.visibility = if (row.sub.isNullOrEmpty()) View.GONE else View.VISIBLE
                val num = v.findViewById<TextView>(R.id.number)
                num.text = row.number
                num.visibility = if (row.number == null) View.GONE else View.VISIBLE
                v.findViewById<View>(R.id.liked).visibility = if (row.liked) View.VISIBLE else View.GONE
                val img = v.findViewById<com.google.android.material.imageview.ShapeableImageView>(R.id.thumb)
                shape(img, row.round, 8f)
                Thumbs.load(img, row.thumb, if (row.round) R.drawable.ic_person else R.drawable.ic_music)
                v.setOnClickListener { row.onClick() }
                val long = row.onLong
                if (long != null) v.setOnLongClickListener { long(); true } else v.setOnLongClickListener(null)
            }
            is Row.Cards -> {
                val box = (holder as StripHolder).box
                box.removeAllViews()
                val inf = LayoutInflater.from(box.context)
                row.items.forEach { c ->
                    val card = inf.inflate(R.layout.item_square_card, box, false)
                    card.findViewById<TextView>(R.id.title).text = c.title
                    val sub = card.findViewById<TextView>(R.id.sub)
                    sub.text = c.sub
                    sub.visibility = if (c.sub.isNullOrEmpty()) View.GONE else View.VISIBLE
                    val img = card.findViewById<com.google.android.material.imageview.ShapeableImageView>(R.id.thumb)
                    shape(img, c.round, 12f)
                    Thumbs.load(img, c.thumb, if (c.round) R.drawable.ic_person else R.drawable.ic_music)
                    card.setOnClickListener { c.onClick() }
                    c.onLong?.let { l -> card.setOnLongClickListener { l(); true } }
                    Ui.applyFont(card)
                    box.addView(card)
                }
            }
            is Row.MusicHeader -> {
                v.findViewById<TextView>(R.id.title).text = row.title
                val sub = v.findViewById<TextView>(R.id.sub)
                sub.text = row.sub
                sub.visibility = if (row.sub.isNullOrEmpty()) View.GONE else View.VISIBLE
                val img = v.findViewById<com.google.android.material.imageview.ShapeableImageView>(R.id.thumb)
                shape(img, row.round, 16f)
                Thumbs.load(img, row.thumb, if (row.round) R.drawable.ic_person else R.drawable.ic_music)
                val playAll = v.findViewById<View>(R.id.playAll)
                val shuf = v.findViewById<View>(R.id.shuffleAll)
                val more = v.findViewById<View>(R.id.more)
                playAll.visibility = if (row.onPlay != null) View.VISIBLE else View.GONE
                shuf.visibility = if (row.onShuffle != null) View.VISIBLE else View.GONE
                more.visibility = if (row.onMore != null) View.VISIBLE else View.GONE
                playAll.setOnClickListener { row.onPlay?.invoke() }
                shuf.setOnClickListener { row.onShuffle?.invoke() }
                more.setOnClickListener { row.onMore?.invoke() }
                val save = v.findViewById<MaterialButton>(R.id.save)
                save.visibility = if (row.onSave != null) View.VISIBLE else View.GONE
                save.text = row.saveLabel
                save.setIconResource(if (row.saved) R.drawable.ic_heart else R.drawable.ic_heart_outline)
                save.setOnClickListener { row.onSave?.invoke() }
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

    private fun shape(img: com.google.android.material.imageview.ShapeableImageView, round: Boolean, cornerDp: Float) {
        val d = img.resources.displayMetrics.density
        img.shapeAppearanceModel = if (round)
            com.google.android.material.shape.ShapeAppearanceModel.builder()
                .setAllCornerSizes(com.google.android.material.shape.RelativeCornerSize(0.5f)).build()
        else com.google.android.material.shape.ShapeAppearanceModel.builder().setAllCornerSizes(cornerDp * d).build()
    }

    /** Prépare une ligne de réglage et renvoie la zone où placer le contrôle. */
    private fun settingBase(v: View, title: String, sub: String?, value: String?): ViewGroup {
        val t = v.findViewById<TextView>(R.id.title)
        t.text = title
        t.visibility = if (title.isEmpty()) View.GONE else View.VISIBLE
        val s = v.findViewById<TextView>(R.id.sub)
        s.text = sub
        s.visibility = if (sub == null) View.GONE else View.VISIBLE
        val val_ = v.findViewById<TextView>(R.id.value)
        val_.text = value
        val_.visibility = if (value == null) View.GONE else View.VISIBLE
        v.findViewById<View>(R.id.toggle).visibility = View.GONE
        v.setOnClickListener(null)
        v.isClickable = false
        val content = v.findViewById<ViewGroup>(R.id.content)
        content.removeAllViews()
        return content
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
