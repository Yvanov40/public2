package fr.lecteur

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.schabi.newpipe.extractor.stream.StreamInfoItem

class ResultsAdapter(private val onClick: (StreamInfoItem) -> Unit) :
    RecyclerView.Adapter<ResultsAdapter.Holder>() {

    private var items: List<StreamInfoItem> = emptyList()

    fun submit(list: List<StreamInfoItem>) {
        items = list
        notifyDataSetChanged()
    }

    class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val thumb: ImageView = v.findViewById(R.id.thumb)
        val title: TextView = v.findViewById(R.id.title)
        val sub: TextView = v.findViewById(R.id.sub)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_video, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: Holder, position: Int) {
        val it = items[position]
        h.title.text = it.name
        h.sub.text = listOfNotNull(it.uploaderName, Yt.formatDuration(it.duration).ifEmpty { null })
            .joinToString(" · ")
        Thumbs.load(h.thumb, Sources.bestThumb(it.thumbnails))
        h.itemView.setOnClickListener { _ -> onClick(it) }
    }
}
