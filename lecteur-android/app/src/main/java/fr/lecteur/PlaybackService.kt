package fr.lecteur

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/** Garde la lecture en vie écran verrouillé / appli fermée, avec notification et contrôles. */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private val closeCommand = SessionCommand(ACTION_CLOSE, Bundle.EMPTY)
    private val backCommand = SessionCommand(ACTION_BACK, Bundle.EMPTY)
    private val fwdCommand = SessionCommand(ACTION_FWD, Bundle.EMPTY)

    private val sessionCommands
        get() = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
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
        // Bouton « Fermer » dans la notification / la bulle / l'écran de verrouillage
        val closeButton = CommandButton.Builder()
            .setDisplayName("Fermer")
            .setIconResId(R.drawable.ic_notif_close)
            .setSessionCommand(closeCommand)
            .build()

        session = MediaSession.Builder(this, player)
            .setSessionActivity(open)
            .setCustomLayout(ImmutableList.of(backButton, fwdButton, closeButton))
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo
                ): MediaSession.ConnectionResult {
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(sessionCommands)
                        .setAvailablePlayerCommands(playerCommands(session.player))
                        .build()
                }

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
                    return super.onCustomCommand(session, controller, customCommand, args)
                }
            })
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

    /** Arrête tout : plus de son, plus de notification ni de bulle. */
    private fun close() {
        val p = session?.player ?: return
        p.stop()
        p.clearMediaItems()
        PlayerHolder.current = null
        stopSelf()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

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
    }
}
