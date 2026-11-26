package fr.lecteur

import android.content.Context
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.media3.common.Player
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.images.WebImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.util.concurrent.Executors

/**
 * Diffusion sur une enceinte ou un téléviseur Google Cast.
 * Le son est envoyé à l'appareil ; le téléphone garde la file, les playlists et les commandes.
 */
object Caster {
    private const val TAG = "Caster"
    private lateinit var app: Context
    private var cast: CastContext? = null
    private var session: CastSession? = null
    private var loadJob: Job? = null

    /** Ce qui est en cours sur l'enceinte. */
    var title: String? = null
        private set

    /** Prévenir l'écran (nom de l'appareil, lecture/pause…). */
    var listener: (() -> Unit)? = null
    private fun changed() = listener?.invoke()

    val available get() = cast != null
    val isCasting get() = session?.isConnected == true
    val deviceName get() = session?.castDevice?.friendlyName
    private val client: RemoteMediaClient? get() = session?.remoteMediaClient
    val isPlaying get() = client?.isPlaying == true

    fun position(): Long = client?.approximateStreamPosition ?: 0
    fun duration(): Long = client?.streamDuration ?: 0

    fun init(ctx: Context) {
        app = ctx.applicationContext
        try {
            CastContext.getSharedInstance(app, Executors.newSingleThreadExecutor())
                .addOnSuccessListener { c ->
                    cast = c
                    c.sessionManager.addSessionManagerListener(sessions, CastSession::class.java)
                    c.sessionManager.currentCastSession?.let { started(it) }
                    changed()
                }
                .addOnFailureListener { Log.w(TAG, "Cast indisponible", it) }
        } catch (e: Throwable) {
            Log.w(TAG, "Cast indisponible (services Google absents ?)", e)
        }
    }

    // ——— Connexion / déconnexion ———

    private val sessions = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(s: CastSession, sessionId: String) = started(s)
        override fun onSessionResumed(s: CastSession, wasSuspended: Boolean) = started(s)
        override fun onSessionEnded(s: CastSession, error: Int) = ended()
        override fun onSessionSuspended(s: CastSession, reason: Int) = changed()
        override fun onSessionStarting(s: CastSession) {}
        override fun onSessionStartFailed(s: CastSession, error: Int) {
            Toast.makeText(app, "Connexion à l'enceinte impossible", Toast.LENGTH_SHORT).show()
        }
        override fun onSessionEnding(s: CastSession) {
            // On retient où en était l'enceinte, pour reprendre sur le téléphone
            lastRemotePos = client?.approximateStreamPosition ?: 0
        }
        override fun onSessionResuming(s: CastSession, sessionId: String) {}
        override fun onSessionResumeFailed(s: CastSession, error: Int) {}
    }

    private var lastRemotePos = 0L

    private val remote = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() {
            val st = client?.mediaStatus
            if (st != null && st.playerState == MediaStatus.PLAYER_STATE_IDLE && st.idleReason == MediaStatus.IDLE_REASON_FINISHED) {
                next()
            }
            changed()
        }
    }

    private fun started(s: CastSession) {
        session = s
        s.remoteMediaClient?.registerCallback(remote)
        val p = PlayerHolder.peek()
        if (p != null && p.mediaItemCount > 0) {
            val wasPlaying = p.isPlaying || p.playWhenReady
            p.pause()
            castCurrent(autoplay = wasPlaying)
        }
        Toast.makeText(app, "Connecté à ${s.castDevice?.friendlyName ?: "l'enceinte"}", Toast.LENGTH_SHORT).show()
        changed()
    }

    private fun ended() {
        session?.remoteMediaClient?.unregisterCallback(remote)
        session = null
        title = null
        loadJob?.cancel()
        // Retour sur le téléphone, en pause, au même endroit
        PlayerHolder.peek()?.let { p ->
            if (p.mediaItemCount > 0 && lastRemotePos > 0) p.seekTo(lastRemotePos)
        }
        changed()
    }

    fun disconnect() {
        try { cast?.sessionManager?.endCurrentSession(true) } catch (_: Exception) {}
    }

    // ——— Lecture à distance ———

    /** Le lecteur du téléphone s'est mis à jouer pendant la diffusion : on renvoie vers l'enceinte. */
    fun onLocalPlay(p: Player) {
        if (!isCasting) return
        p.pause()
        castCurrent(autoplay = true)
    }

    private class Media(val url: String, val mime: String, val title: String, val artist: String?, val art: String?)

    /** Prépare ce que l'enceinte doit lire, d'après l'élément en cours sur le téléphone. */
    private suspend fun resolve(p: Player): Media {
        val item = p.currentMediaItem ?: throw java.io.IOException("rien à diffuser")
        val m = p.mediaMetadata
        val title = m.title?.toString() ?: "Lecteur"
        val artist = m.artist?.toString()
        val art = m.artworkUri?.toString()?.takeIf { it.startsWith("http") }
        val id = item.mediaId
        val ytUrl = when {
            id.startsWith("music:http") -> id.removePrefix("music:")
            id.contains("youtube.com/watch") || id.contains("youtu.be/") -> id
            else -> null
        }
        if (ytUrl != null) {
            // YouTube : le son passe par le téléphone (l'enceinte ne sait pas le récupérer seule)
            return withContext(Dispatchers.IO) {
                try {
                    val info = StreamInfo.getInfo(ytUrl)
                    val a = Sources.pickAudio(info.audioStreams) ?: throw java.io.IOException("pas de son")
                    val len = a.itagItem?.contentLength?.takeIf { it > 0 }
                        ?: Uri.parse(a.content).getQueryParameter("clen")?.toLongOrNull() ?: -1L
                    val mime = a.format?.mimeType ?: "audio/mp4"
                    Media(LocalStream.publish(LocalStream.Entry(ytUrl = a.content, mime = mime, length = len)), mime, title, artist, art)
                } catch (e: Exception) {
                    if (!Settings.fallback) throw e
                    // Serveur relais : l'enceinte peut lire son adresse directement
                    val vid = Fallback.videoId(ytUrl) ?: throw e
                    Media(Fallback.audioUrl(vid), "audio/mp4", title, artist, art)
                }
            }
        }
        val uri = item.localConfiguration?.uri ?: Uri.parse(id)
        return when (uri.scheme) {
            "http", "https" -> Media(uri.toString(), item.localConfiguration?.mimeType ?: "audio/mpeg", title, artist, art)
            "content", "file" -> withContext(Dispatchers.IO) {
                val mime = app.contentResolver.getType(uri) ?: "audio/mpeg"
                val len = try {
                    app.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
                } catch (_: Exception) {
                    -1L
                }
                Media(LocalStream.publish(LocalStream.Entry(contentUri = uri, mime = mime, length = len)), mime, title, artist, art)
            }
            else -> throw java.io.IOException("format non pris en charge")
        }
    }

    fun castCurrent(autoplay: Boolean = true) {
        val p = PlayerHolder.peek() ?: return
        val pos = p.currentPosition
        loadJob?.cancel()
        loadJob = Playback.scope.launch {
            try {
                val m = resolve(p)
                val c = client ?: return@launch
                val md = com.google.android.gms.cast.MediaMetadata(com.google.android.gms.cast.MediaMetadata.MEDIA_TYPE_MUSIC_TRACK).apply {
                    putString(com.google.android.gms.cast.MediaMetadata.KEY_TITLE, m.title)
                    m.artist?.let { putString(com.google.android.gms.cast.MediaMetadata.KEY_ARTIST, it) }
                    m.art?.let { addImage(WebImage(Uri.parse(it))) }
                }
                val info = MediaInfo.Builder(m.url)
                    .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                    .setContentType(m.mime)
                    .setMetadata(md)
                    .build()
                c.load(
                    MediaLoadRequestData.Builder().setMediaInfo(info).setAutoplay(autoplay).setCurrentTime(pos).build()
                )
                title = m.title
                changed()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Diffusion impossible", e)
                Toast.makeText(app, "Diffusion impossible : ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun togglePlayback() {
        client?.togglePlayback()
    }

    fun seekTo(ms: Long) {
        client?.seek(MediaSeekOptions.Builder().setPosition(ms).build())
    }

    fun seekBy(deltaMs: Long) = seekTo((position() + deltaMs).coerceAtLeast(0))

    /** Fin du titre sur l'enceinte : suivant (musique, liste, file d'attente). */
    fun next() {
        val p = PlayerHolder.peek() ?: return
        when {
            Music.active -> Music.next()                 // le lecteur repart, puis est renvoyé vers l'enceinte
            p.hasNextMediaItem() -> {
                p.seekToNextMediaItem()
                castCurrent(autoplay = true)
            }
            else -> if (!Playback.onEnded()) changed()
        }
    }

    fun previous() {
        val p = PlayerHolder.peek() ?: return
        when {
            position() > 3000 -> seekTo(0)
            Music.active -> Music.previous()
            p.hasPreviousMediaItem() -> {
                p.seekToPreviousMediaItem()
                castCurrent(autoplay = true)
            }
            else -> seekTo(0)
        }
    }
}
