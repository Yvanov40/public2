package fr.lecteur

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.annotation.OptIn
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

    override fun onCreate() {
        super.onCreate()
        val player = PlayerHolder.get(this)
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // Bouton « Fermer » dans la notification / la bulle / l'écran de verrouillage
        val closeButton = CommandButton.Builder()
            .setDisplayName("Fermer")
            .setIconResId(R.drawable.ic_notif_close)
            .setSessionCommand(closeCommand)
            .build()

        session = MediaSession.Builder(this, player)
            .setSessionActivity(open)
            .setCustomLayout(ImmutableList.of(closeButton))
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo
                ): MediaSession.ConnectionResult {
                    val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                        .add(closeCommand)
                        .build()
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(commands)
                        .build()
                }

                override fun onCustomCommand(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    customCommand: SessionCommand,
                    args: Bundle
                ): ListenableFuture<SessionResult> {
                    if (customCommand.customAction == ACTION_CLOSE) {
                        close()
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    return super.onCustomCommand(session, controller, customCommand, args)
                }
            })
            .build()
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
    }
}
