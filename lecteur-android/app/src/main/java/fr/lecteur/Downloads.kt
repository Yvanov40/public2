package fr.lecteur

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** File des téléchargements en cours, partagée entre le service et l'écran. */
object Downloads {
    enum class State { WAITING, RUNNING, FAILED, CANCELLED }

    class Item(
        val id: Int, val url: String, val video: Boolean, title: String?,
        val direct: Boolean = false, val artist: String? = null, val thumb: String? = null
    ) {
        @Volatile var title: String = title ?: "Vidéo"
        @Volatile var state = State.WAITING
        @Volatile var pct = -1
        @Volatile var stage = "En attente"
        @Volatile var cancel = false
    }

    private val items = CopyOnWriteArrayList<Item>()
    private val nextId = AtomicInteger(1)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var scheduled = false

    /** Appelé sur le fil principal, au plus ~3 fois par seconde. */
    var onChange: (() -> Unit)? = null

    fun all(): List<Item> = items.toList()

    fun get(id: Int): Item? = items.firstOrNull { it.id == id }

    fun active(): Int = items.count { it.state == State.WAITING || it.state == State.RUNNING }

    fun add(url: String, video: Boolean, title: String?): Item =
        Item(nextId.getAndIncrement(), url, video, title).also { items += it; changed() }

    /** Fichier audio direct (épisode de podcast). */
    fun addDirect(url: String, title: String, artist: String?, thumb: String?): Item =
        Item(nextId.getAndIncrement(), url, false, title, true, artist, thumb).also { items += it; changed() }

    fun remove(item: Item) {
        items.remove(item)
        changed()
    }

    fun cancel(item: Item) {
        item.cancel = true
        if (item.state == State.WAITING) {
            item.state = State.CANCELLED
            item.stage = "Annulé"
        }
        changed()
    }

    fun changed() {
        if (scheduled) return
        scheduled = true
        main.postDelayed({
            scheduled = false
            onChange?.invoke()
        }, 300)
    }
}
