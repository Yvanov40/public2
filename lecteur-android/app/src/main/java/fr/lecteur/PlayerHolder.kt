package fr.lecteur

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

/** Un seul lecteur, partagé entre l'écran et le service d'arrière-plan. */
@OptIn(UnstableApi::class)
object PlayerHolder {
    private var player: ExoPlayer? = null

    /** Ce que l'utilisateur veut : vidéo affichée (true) ou audio seul (false). */
    var videoMode = true

    /** Vidéo YouTube en cours (null pour un fichier local). */
    var current: Vid? = null

    fun get(ctx: Context): ExoPlayer {
        player?.let { return it }
        val app = ctx.applicationContext
        val prefs = app.getSharedPreferences("lecteur", Context.MODE_PRIVATE)
        val attrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()
        val p = ExoPlayer.Builder(app)
            .setAudioAttributes(attrs, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        // La vitesse choisie est retenue d'une vidéo à l'autre et entre les lancements
        p.playbackParameters = PlaybackParameters(prefs.getFloat("speed", 1f))
        p.addListener(object : Player.Listener {
            override fun onPlaybackParametersChanged(pp: PlaybackParameters) {
                prefs.edit().putFloat("speed", pp.speed).apply()
            }
        })
        player = p
        return p
    }

    fun setVideoEnabled(p: Player, on: Boolean) {
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !on)
            .build()
    }

    fun clear(p: Player) {
        if (player === p) player = null
    }
}
