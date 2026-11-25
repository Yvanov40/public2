package fr.lecteur

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/**
 * Lecture partagée entre l'écran et l'arrière-plan : vidéo YouTube, file d'attente,
 * vidéo suivante automatique, minuteur de sommeil, chapitres et description.
 */
object Playback {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var app: Context

    fun init(ctx: Context) {
        app = ctx.applicationContext
    }

    /** Prévenir l'écran qu'il faut se rafraîchir. */
    var listener: (() -> Unit)? = null
    private fun changed() = listener?.invoke()

    // ——— Infos de l'élément en cours (par mediaId) ———
    class Info(val description: String?, val html: Boolean, val chapters: List<Pair<Long, String>>)

    private val infos = HashMap<String, Info>()
    private var related: List<Vid> = emptyList()

    fun setInfo(mediaId: String, info: Info) {
        infos[mediaId] = info
    }

    fun currentInfo(): Info? = PlayerHolder.peek()?.currentMediaItem?.mediaId?.let { infos[it] }

    // ——— YouTube ———
    private var autoJob: Job? = null

    /** Charge et lance une vidéo YouTube (reprise à la dernière position). */
    suspend fun playYoutube(url: String, audioOnly: Boolean): Vid {
        val (info, source) = withContext(Dispatchers.IO) {
            val i = Yt.info(url)
            i to Sources.build(i)
        }
        val p = PlayerHolder.get(app)
        // En arrière-plan direct : la piste vidéo n'est jamais chargée
        PlayerHolder.videoMode = !audioOnly
        PlayerHolder.setVideoEnabled(p, !audioOnly)
        p.setMediaSource(source, Store.position(info.url).coerceAtLeast(0))
        p.prepare()
        p.play()
        val v = Vid(
            info.url, info.name, info.uploaderName, info.uploaderUrl,
            Sources.bestThumb(info.thumbnails, 300), info.duration
        )
        PlayerHolder.current = v
        Store.addWatched(v)
        val desc = info.description
        infos[info.url] = Info(
            desc?.content?.ifBlank { null }, desc?.type == 1, // 1 = HTML
            info.streamSegments.map { it.startTimeSeconds * 1000L to it.title }
        )
        related = info.relatedItems.filterIsInstance<StreamInfoItem>().map(Yt::vid)
        SponsorBlock.load(scope, info.url)
        changed()
        return v
    }

    /** Lecture de fichiers ou d'épisodes (adresses directes). */
    fun startedLocal() {
        PlayerHolder.current = null
        related = emptyList()
        SponsorBlock.clear()
        changed()
    }

    // ——— File d'attente ———
    data class QItem(
        val title: String, val artist: String?, val thumb: String?,
        val ytUrl: String? = null, val uri: String? = null, val mediaId: String = ytUrl ?: uri.orEmpty()
    )

    val queue = mutableListOf<QItem>()

    fun addNext(q: QItem) {
        queue.add(0, q)
        changed()
    }

    fun addLast(q: QItem) {
        queue.add(q)
        changed()
    }

    fun removeFromQueue(i: Int) {
        if (i in queue.indices) queue.removeAt(i)
        changed()
    }

    fun clearQueue() {
        queue.clear()
        changed()
    }

    /** Appelé quand la lecture arrive au bout. Renvoie true si quelque chose d'autre démarre. */
    fun onEnded(): Boolean {
        if (sleepEndOfItem) {
            cancelSleep()
            return false
        }
        val next = queue.removeFirstOrNull()
        if (next != null) {
            playQueued(next)
            return true
        }
        val cur = PlayerHolder.current ?: return false
        if (!Settings.autoplay) return false
        val watched = Store.watched().map { it.url }.toSet()
        val sug = related.firstOrNull { it.url !in watched && it.url != cur.url } ?: related.firstOrNull() ?: return false
        startYoutube(sug.url, !PlayerHolder.videoMode)
        return true
    }

    private fun playQueued(q: QItem) {
        if (q.ytUrl != null) {
            startYoutube(q.ytUrl, !PlayerHolder.videoMode)
        } else if (q.uri != null) {
            val p = PlayerHolder.get(app)
            val item = MediaItem.Builder()
                .setUri(q.uri)
                .setMediaId(q.mediaId)
                .setMediaMetadata(
                    MediaMetadata.Builder().setTitle(q.title).setArtist(q.artist)
                        .setArtworkUri(q.thumb?.takeIf { it.startsWith("http") }?.let(Uri::parse)).build()
                )
                .build()
            startedLocal()
            p.setMediaItem(item, Store.position(q.mediaId).coerceAtLeast(0))
            p.prepare()
            p.play()
        }
        changed()
    }

    fun startYoutube(url: String, audioOnly: Boolean) {
        autoJob?.cancel()
        autoJob = scope.launch {
            try {
                playYoutube(url, audioOnly)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(app, "Lecture impossible : ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ——— Minuteur de sommeil ———
    var sleepAt = 0L
        private set
    var sleepEndOfItem = false
        private set
    private val sleeper = Runnable {
        PlayerHolder.peek()?.pause()
        sleepAt = 0
        changed()
    }

    fun setSleep(minutes: Int) {
        cancelSleep()
        sleepAt = System.currentTimeMillis() + minutes * 60_000L
        handler.postDelayed(sleeper, minutes * 60_000L)
        changed()
    }

    fun setSleepEndOfItem() {
        cancelSleep()
        sleepEndOfItem = true
        changed()
    }

    fun cancelSleep() {
        handler.removeCallbacks(sleeper)
        sleepAt = 0
        sleepEndOfItem = false
        changed()
    }

    /** Fin d'un élément dans une liste : le minuteur « fin de l'élément » met en pause. */
    fun onItemFinishedInList() {
        if (sleepEndOfItem) {
            PlayerHolder.peek()?.pause()
            cancelSleep()
        }
    }

    fun sleepLabel(): String? = when {
        sleepEndOfItem -> "Fin de l'élément"
        sleepAt > 0 -> "${((sleepAt - System.currentTimeMillis()) / 60_000 + 1).coerceAtLeast(1)} min"
        else -> null
    }
}
