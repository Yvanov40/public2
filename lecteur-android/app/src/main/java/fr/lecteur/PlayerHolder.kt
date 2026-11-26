package fr.lecteur

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
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
        // Redirections http ⇄ https autorisées (fréquentes pour les épisodes de podcast)
        val http = androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent(DownloaderImpl.USER_AGENT)
        val sources = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
            androidx.media3.datasource.DefaultDataSource.Factory(app, http)
        )
        val p = ExoPlayer.Builder(app)
            .setMediaSourceFactory(sources)
            .setAudioAttributes(attrs, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        // La vitesse choisie est retenue d'une vidéo à l'autre et entre les lancements
        p.playbackParameters = PlaybackParameters(prefs.getFloat("speed", 1f))
        p.skipSilenceEnabled = Settings.skipSilence
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Playback.onPlayerError(error)
            }

            override fun onPlaybackParametersChanged(pp: PlaybackParameters) {
                prefs.edit().putFloat("speed", pp.speed).apply()
            }

            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                sessionId = audioSessionId
                applyNormalize()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                // Pendant une diffusion Chromecast, le son part sur l'enceinte
                if (isPlaying && Caster.isCasting) {
                    handler.post { Caster.onLocalPlay(p) }
                    return
                }
                // L'égaliseur ne tourne que pendant la lecture (jamais en pause)
                if (android.os.Build.VERSION.SDK_INT >= 28) {
                    try { (dynamics as? android.media.audiofx.DynamicsProcessing)?.enabled = isPlaying && Settings.normalize } catch (_: Exception) {}
                }
                if (isPlaying) {
                    handler.postDelayed(saver, 15_000)
                    handler.removeCallbacks(sponsorCheck)
                    handler.postDelayed(sponsorCheck, 500)
                } else {
                    handler.removeCallbacks(saver)
                    handler.removeCallbacks(sponsorCheck)
                    savePosition(p)
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // Élément précédent terminé : marqué comme vu / écouté
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                    lastId?.takeIf { !it.startsWith("music:") }?.let { Store.setPosition(it, -1) }
                    Playback.onItemFinishedInList()
                }
                lastId = mediaItem?.mediaId?.ifEmpty { null }
                Music.onTransition()
                if (lastId?.startsWith("music:") == true) return
                // Élément suivant d'une liste : reprise là où on s'était arrêté
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) {
                    val pos = lastId?.let { Store.position(it) } ?: 0
                    if (pos > 0) p.seekTo(pos)
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    lastId?.takeIf { !it.startsWith("music:") }?.let { Store.setPosition(it, -1) }
                    // File d'attente, vidéo suivante automatique ou minuteur « fin de l'élément »
                    handler.post { Playback.onEnded() }
                }
            }
        })
        saver = object : Runnable {
            override fun run() {
                savePosition(p)
                if (p.isPlaying) handler.postDelayed(this, 15_000)
            }
        }
        sponsorCheck = object : Runnable {
            override fun run() {
                SponsorBlock.check(p)?.let { label ->
                    android.widget.Toast.makeText(app, "$label passé", android.widget.Toast.LENGTH_SHORT).show()
                }
                if (p.isPlaying) handler.postDelayed(this, 1_000)
            }
        }
        player = p
        return p
    }

    /** Le lecteur s'il existe déjà (sans le créer). */
    fun peek(): ExoPlayer? = player

    private var sponsorCheck: Runnable = Runnable { }
    private var sessionId = 0
    private var dynamics: Any? = null

    fun applySkipSilence() {
        player?.skipSilenceEnabled = Settings.skipSilence
    }

    /** Égaliseur de volume : compresse les écarts (voix plus régulières), Android 9+. */
    fun applyNormalize() {
        if (android.os.Build.VERSION.SDK_INT < 28) return
        (dynamics as? android.media.audiofx.DynamicsProcessing)?.release()
        dynamics = null
        if (!Settings.normalize || sessionId == 0) return
        try {
            val cfg = android.media.audiofx.DynamicsProcessing.Config.Builder(
                android.media.audiofx.DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                2, false, 0, true, 1, false, 0, true
            ).build()
            val d = android.media.audiofx.DynamicsProcessing(0, sessionId, cfg)
            // Réglage doux : compression légère, pas d'amplification du bruit de fond
            d.setMbcBandAllChannelsTo(
                0, android.media.audiofx.DynamicsProcessing.MbcBand(
                    true, 20000f, 10f, 300f, 2f, -24f, 6f, -60f, 1f, 0f, 2f
                )
            )
            d.setLimiterAllChannelsTo(
                android.media.audiofx.DynamicsProcessing.Limiter(true, true, 1, 2f, 80f, 10f, -2f, 0f)
            )
            d.enabled = player?.isPlaying == true
            dynamics = d
        } catch (_: Exception) {
        }
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var saver: Runnable = Runnable { }
    private var lastId: String? = null

    /** Mémorise où on en est (vidéo, épisode, fichier) pour reprendre plus tard. */
    private fun savePosition(p: Player) {
        val id = p.currentMediaItem?.mediaId?.ifEmpty { null } ?: return
        if (id.startsWith("music:") || p.isCurrentMediaItemLive) return
        val dur = p.duration
        if (dur == C.TIME_UNSET || dur <= 0) return
        val pos = p.currentPosition
        val end = if (dur > 120_000) dur - 30_000 else dur - 2_000
        when {
            pos >= end -> Store.setPosition(id, -1)
            pos > 5_000 -> Store.setPosition(id, pos)
        }
    }

    fun setVideoEnabled(p: Player, on: Boolean) {
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !on)
            .build()
    }

    fun clear(p: Player) {
        if (player === p) {
            player = null
            if (android.os.Build.VERSION.SDK_INT >= 28) (dynamics as? android.media.audiofx.DynamicsProcessing)?.release()
            dynamics = null
        }
    }
}
