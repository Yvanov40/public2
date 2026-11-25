package fr.lecteur

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Garde la lecture en vie écran verrouillé / appli fermée (notification, contrôles),
 * et expose une bibliothèque parcourable pour Android Auto (téléchargements, podcasts).
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {
    private var session: MediaLibrarySession? = null
    private val closeCommand = SessionCommand(ACTION_CLOSE, Bundle.EMPTY)
    private val backCommand = SessionCommand(ACTION_BACK, Bundle.EMPTY)
    private val fwdCommand = SessionCommand(ACTION_FWD, Bundle.EMPTY)

    /** Éléments déjà montrés à Android Auto, pour pouvoir les lire ensuite. */
    private val known = HashMap<String, MediaItem>()

    private val sessionCommands
        get() = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
            .add(closeCommand).add(backCommand).add(fwdCommand).build()

    /**
     * Sans liste de lecture, on retire « précédent / suivant » : Android place alors
     * −10 s et +10 s de part et d'autre de lecture/pause (bulle, notification, écran verrouillé).
     */
    private fun playerCommands(p: Player): Player.Commands {
        val b = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
        if (p.mediaItemCount <= 1) {
            b.removeAll(
                Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM
            )
        }
        return b.build()
    }

    override fun onCreate() {
        super.onCreate()
        val player = PlayerHolder.get(this)
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val backButton = CommandButton.Builder()
            .setDisplayName("Reculer de 10 s")
            .setIconResId(R.drawable.ic_notif_back10)
            .setSessionCommand(backCommand)
            .build()
        val fwdButton = CommandButton.Builder()
            .setDisplayName("Avancer de 10 s")
            .setIconResId(R.drawable.ic_notif_fwd10)
            .setSessionCommand(fwdCommand)
            .build()
        val closeButton = CommandButton.Builder()
            .setDisplayName("Fermer")
            .setIconResId(R.drawable.ic_notif_close)
            .setSessionCommand(closeCommand)
            .build()

        session = MediaLibrarySession.Builder(this, player, Callback())
            .setSessionActivity(open)
            .setCustomLayout(ImmutableList.of(backButton, fwdButton, closeButton))
            .build()

        // Liste d'un seul élément ⇄ plusieurs : on met à jour les boutons proposés
        player.addListener(object : Player.Listener {
            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                val s = session ?: return
                val cmds = playerCommands(player)
                s.connectedControllers.forEach { s.setAvailableCommands(it, sessionCommands, cmds) }
            }
        })
    }

    private inner class Callback : MediaLibrarySession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult =
            MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .setAvailablePlayerCommands(playerCommands(session.player))
                .build()

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                ACTION_CLOSE -> close()
                ACTION_BACK -> session.player.seekBack()
                ACTION_FWD -> session.player.seekForward()
                else -> return super.onCustomCommand(session, controller, customCommand, args)
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(folder(ROOT, "Lecteur"), params))

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val item = known[mediaId] ?: return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
            return Futures.immediateFuture(LibraryResult.ofItem(item, null))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
            Playback.scope.launch {
                val items: List<MediaItem> = try {
                    withContext(Dispatchers.IO) { children(parentId) }
                } catch (_: Exception) {
                    emptyList()
                }
                items.forEach { known[it.mediaId] = it }
                future.set(LibraryResult.ofItemList(ImmutableList.copyOf(items), params))
            }
            return future
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            val resolved = mediaItems.map { known[it.mediaId] ?: it }.toMutableList()
            Playback.startedLocal()
            PlayerHolder.videoMode = false
            return Futures.immediateFuture(resolved)
        }
    }

    private fun folder(id: String, title: String) = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder().setTitle(title).setIsBrowsable(true).setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED).build()
        )
        .build()

    private fun playable(id: String, uri: String, title: String, artist: String?, art: String?) = MediaItem.Builder()
        .setMediaId(id)
        .setUri(uri)
        .setMediaMetadata(
            MediaMetadata.Builder().setTitle(title).setArtist(artist)
                .setArtworkUri(art?.takeIf { it.startsWith("http") }?.let(Uri::parse))
                .setIsBrowsable(false).setIsPlayable(true).build()
        )
        .build()

    /** Arborescence : Téléchargés / Podcasts → épisodes. */
    private suspend fun children(parentId: String): List<MediaItem> = when {
        parentId == ROOT -> listOf(folder(DOWNLOADS, "Téléchargés"), folder(PODCASTS, "Podcasts"))
        parentId == DOWNLOADS -> {
            val store = Store.downloads()
            LocalMedia.scan(this).filter { !it.video }.map { f ->
                val d = store.firstOrNull { Uri.parse(it.uri).lastPathSegment?.toLongOrNull() == f.id }
                playable(f.uri.toString(), f.uri.toString(), d?.title ?: f.name.substringBeforeLast('.'), d?.uploader, d?.thumb)
            }
        }
        parentId == PODCASTS -> Store.podcasts().map { folder("feed:" + it.feedUrl, it.title) }
        parentId.startsWith("feed:") -> Pod.feed(parentId.removePrefix("feed:")).second.take(50).map { e ->
            playable(Pod.mediaId(e), e.audioUrl, e.title, e.podcastTitle, e.image ?: e.podcastArt)
        }
        else -> emptyList()
    }

    /** Arrête tout : plus de son, plus de notification ni de bulle. */
    private fun close() {
        val p = session?.player ?: return
        p.stop()
        p.clearMediaItems()
        PlayerHolder.current = null
        stopSelf()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.let {
            val p = it.player
            it.release()
            p.release()
            PlayerHolder.clear(p)
        }
        session = null
        super.onDestroy()
    }

    companion object {
        private const val ACTION_CLOSE = "fr.lecteur.CLOSE"
        private const val ACTION_BACK = "fr.lecteur.BACK10"
        private const val ACTION_FWD = "fr.lecteur.FWD10"
        private const val ROOT = "root"
        private const val DOWNLOADS = "downloads"
        private const val PODCASTS = "podcasts"
    }
}
