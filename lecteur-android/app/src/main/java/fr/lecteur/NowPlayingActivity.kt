package fr.lecteur

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.palette.graphics.Palette
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Écran « en cours de lecture » façon Spotify : grande pochette, couleurs de la pochette, paroles. */
@OptIn(UnstableApi::class)
class NowPlayingActivity : AppCompatActivity() {

    private lateinit var player: ExoPlayer
    private lateinit var root: View
    private lateinit var cover: ImageView
    private lateinit var coverCard: MaterialCardView
    private lateinit var title: TextView
    private lateinit var artist: TextView
    private lateinit var like: ImageButton
    private lateinit var seek: SeekBar
    private lateinit var pos: TextView
    private lateinit var dur: TextView
    private lateinit var play: ImageView
    private lateinit var shuffle: ImageButton
    private lateinit var repeat: ImageButton
    private lateinit var lyricsScroll: ScrollView
    private lateinit var lyricsBox: LinearLayout
    private lateinit var lyricsBtn: MaterialButton
    private lateinit var timerBtn: MaterialButton

    private val ui = Handler(Looper.getMainLooper())
    private var prevCast: (() -> Unit)? = null
    private var dragging = false
    private var showLyrics = false
    private var lyrics: Music.Lyrics? = null
    private var lyricsFor: String? = null
    private var lyricsJob: Job? = null
    private var lyricLine = -1
    private var artFor: String? = null

    private val listener = object : Player.Listener {
        override fun onMediaMetadataChanged(m: MediaMetadata) = refresh()
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = refresh()
        override fun onIsPlayingChanged(isPlaying: Boolean) = refresh()
        override fun onPlaybackStateChanged(state: Int) = refresh()
    }

    private val tick = object : Runnable {
        override fun run() {
            updateProgress()
            ui.postDelayed(this, 400)
        }
    }

    // ——— Ouverture par le bas, fermeture vers le bas (glisser vers le bas pour refermer) ———

    @Suppress("DEPRECATION")
    private fun slideIn() {
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, R.anim.np_in, R.anim.np_stay)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, R.anim.np_stay, R.anim.np_out)
        } else overridePendingTransition(R.anim.np_in, R.anim.np_stay)
    }

    @Suppress("DEPRECATION")
    override fun finish() {
        super.finish()
        if (android.os.Build.VERSION.SDK_INT < 34) overridePendingTransition(R.anim.np_stay, R.anim.np_out)
    }

    private val swipeDown by lazy {
        val density = resources.displayMetrics.density
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 == null) return false
                val dy = e2.y - e1.y
                val dx = e2.x - e1.x
                if (dy < 90 * density || dy < Math.abs(dx) * 1.5f || vy < 500) return false
                // Pas pendant qu'on fait défiler les paroles
                val lyr = findViewById<View>(R.id.lyricsScroll)
                if (lyr != null && lyr.isShown && lyr.canScrollVertically(-1)) return false
                finish()
                return true
            }
        })
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        swipeDown.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Ui.applyTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_now_playing)
        slideIn()
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        player = PlayerHolder.get(this)

        root = findViewById(R.id.root)
        cover = findViewById(R.id.cover)
        coverCard = findViewById(R.id.coverCard)
        title = findViewById(R.id.title)
        artist = findViewById(R.id.artist)
        like = findViewById(R.id.like)
        seek = findViewById(R.id.seek)
        pos = findViewById(R.id.pos)
        dur = findViewById(R.id.dur)
        play = findViewById(R.id.play)
        shuffle = findViewById(R.id.shuffle)
        repeat = findViewById(R.id.repeat)
        lyricsScroll = findViewById(R.id.lyricsScroll)
        lyricsBox = findViewById(R.id.lyricsBox)
        lyricsBtn = findViewById(R.id.lyricsBtn)
        timerBtn = findViewById(R.id.timerBtn)
        Ui.applyFont(root)
        title.isSelected = true // défilement des titres longs

        // Pochette carrée, la plus grande possible
        val frame = coverCard.parent as FrameLayout
        frame.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val size = minOf(v.width - v.paddingLeft - v.paddingRight, v.height - v.paddingTop - v.paddingBottom)
            val lp = coverCard.layoutParams as FrameLayout.LayoutParams
            if (size > 0 && lp.width != size) {
                lp.width = size
                lp.height = size
                coverCard.layoutParams = lp
            }
        }

        findViewById<View>(R.id.close).setOnClickListener { finish() }
        try {
            com.google.android.gms.cast.framework.CastButtonFactory.setUpMediaRouteButton(
                applicationContext, findViewById(R.id.castBtn)
            )
        } catch (_: Throwable) {
            findViewById<View>(R.id.castBtn).isVisible = false
        }
        // On garde aussi la mise à jour de l'écran principal
        prevCast = Caster.listener
        Caster.listener = {
            prevCast?.invoke()
            runOnUiThread { refresh() }
        }
        findViewById<View>(R.id.playCard).setOnClickListener {
            if (Caster.isCasting) Caster.togglePlayback()
            else if (player.isPlaying) player.pause() else {
                if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                player.play()
            }
        }
        findViewById<View>(R.id.next).setOnClickListener {
            if (Caster.isCasting) Caster.next()
            else if (Music.active) Music.next() else if (player.hasNextMediaItem()) player.seekToNextMediaItem() else player.seekForward()
        }
        findViewById<View>(R.id.prev).setOnClickListener {
            if (Caster.isCasting) Caster.previous()
            else if (Music.active) Music.previous() else player.seekToPrevious()
        }
        // −15 / +15 s (podcasts, vidéos : à la place d'aléatoire / répéter)
        findViewById<View>(R.id.back15).setOnClickListener { jump(-15_000) }
        findViewById<View>(R.id.fwd15).setOnClickListener { jump(15_000) }
        shuffle.setOnClickListener { Music.toggleShuffle(); refresh() }
        repeat.setOnClickListener { Music.cycleRepeat(); refresh() }
        like.setOnClickListener {
            val t = currentTrack() ?: return@setOnClickListener
            val liked = Store.toggleLike(t)
            Toast.makeText(this, if (liked) "Ajouté aux titres likés" else "Retiré des titres likés", Toast.LENGTH_SHORT).show()
            refresh()
        }
        findViewById<View>(R.id.addBtn).setOnClickListener { currentTrack()?.let { addToPlaylist(this, it) } }
        findViewById<View>(R.id.queue).setOnClickListener { showQueue() }
        timerBtn.setOnClickListener { sleepDialog() }
        lyricsBtn.setOnClickListener {
            showLyrics = !showLyrics
            refresh()
        }
        seek.max = 1000
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser && player.duration > 0) pos.text = fmt(player.duration * p / 1000)
            }
            override fun onStartTrackingTouch(s: SeekBar) { dragging = true }
            override fun onStopTrackingTouch(s: SeekBar) {
                dragging = false
                val d = if (Caster.isCasting) Caster.duration() else player.duration
                if (d > 0) {
                    if (Caster.isCasting) Caster.seekTo(d * s.progress / 1000) else player.seekTo(d * s.progress / 1000)
                }
            }
        })
        player.addListener(listener)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        ui.post(tick)
        refresh()
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(tick)
    }

    override fun onDestroy() {
        Caster.listener = prevCast
        player.removeListener(listener)
        super.onDestroy()
    }

    private fun fmt(ms: Long) = Yt.formatDuration(ms / 1000).ifEmpty { "0:00" }

    /** Le titre en cours, quelle que soit son origine (musique, vidéo, fichier). */
    private fun currentTrack(): Track? {
        Music.current?.let { return it }
        PlayerHolder.current?.let { return Track(it.url, it.title, it.uploader, it.uploaderUrl, it.thumb, it.duration) }
        val item = player.currentMediaItem ?: return null
        val m = player.mediaMetadata
        val uri = item.localConfiguration?.uri?.toString() ?: item.mediaId
        return Track(uri, m.title?.toString() ?: "Titre", m.artist?.toString(), null, m.artworkUri?.toString(),
            (player.duration / 1000).coerceAtLeast(0))
    }

    private fun jump(deltaMs: Long) {
        if (Caster.isCasting) {
            Caster.seekBy(deltaMs)
        } else {
            val d = player.duration
            var to = (player.currentPosition + deltaMs).coerceAtLeast(0)
            if (d > 0) to = to.coerceAtMost(d - 1000)
            player.seekTo(to)
        }
        refresh()
    }

    private fun refresh() {
        if (player.mediaItemCount == 0) {
            finish()
            return
        }
        val m = player.mediaMetadata
        title.text = m.title
        artist.text = listOfNotNull(m.artist?.toString(), m.albumTitle?.toString()).joinToString(" · ")
        val playing = if (Caster.isCasting) Caster.isPlaying
        else player.isPlaying || (player.playWhenReady && player.playbackState == Player.STATE_BUFFERING)
        play.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        val t = currentTrack()
        like.setImageResource(if (t != null && Store.isLiked(t.url)) R.drawable.ic_heart else R.drawable.ic_heart_outline)
        shuffle.isVisible = Music.active
        repeat.isVisible = Music.active
        findViewById<View>(R.id.back15).isVisible = !Music.active
        findViewById<View>(R.id.fwd15).isVisible = !Music.active
        shuffle.alpha = if (Music.shuffle) 1f else 0.45f
        repeat.alpha = if (Music.repeat != Music.Repeat.OFF) 1f else 0.45f
        repeat.setImageResource(if (Music.repeat == Music.Repeat.ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat)
        findViewById<TextView>(R.id.context).text = if (Caster.isCasting) "Diffusion sur ${Caster.deviceName ?: "l'enceinte"}" else if (Music.active) "Musique · ${Music.index + 1} / ${Music.tracks.size}" else "Lecture en cours"
        timerBtn.text = Playback.sleepLabel() ?: "Minuteur"

        // Pochette et couleurs
        val art = m.artworkUri?.toString()
        if (art != artFor) {
            artFor = art
            Thumbs.load(cover, art, R.drawable.ic_music)
            Thumbs.get(this, art) { bmp ->
                if (art != artFor) return@get
                val base = if (bmp == null) 0xFF303030.toInt() else Palette.from(bmp).generate().let { p ->
                    p.getDarkVibrantColor(p.getDarkMutedColor(p.getMutedColor(0xFF303030.toInt())))
                }
                root.background = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(base, blend(base, Color.BLACK, 0.75f))
                )
            }
        }

        // Paroles
        val key = (t?.title ?: "") + "|" + (t?.artist ?: "")
        if (showLyrics && key != lyricsFor) loadLyrics(key, t)
        coverCard.isVisible = !showLyrics
        lyricsScroll.isVisible = showLyrics
        lyricsBtn.alpha = if (showLyrics) 1f else 0.7f
        updateProgress()
    }

    private fun blend(a: Int, b: Int, f: Float): Int {
        fun ch(x: Int, y: Int) = (x + (y - x) * f).toInt()
        return Color.rgb(ch(Color.red(a), Color.red(b)), ch(Color.green(a), Color.green(b)), ch(Color.blue(a), Color.blue(b)))
    }

    private fun updateProgress() {
        val d = if (Caster.isCasting) Caster.duration() else player.duration
        val p = if (Caster.isCasting) Caster.position() else player.currentPosition
        if (d > 0) {
            if (!dragging) {
                seek.progress = (p * 1000 / d).toInt()
                pos.text = fmt(p)
            }
            dur.text = fmt(d)
        }
        // Ligne de paroles en cours
        val synced = lyrics?.synced
        if (showLyrics && synced != null) {
            val line = synced.indexOfLast { it.first <= p }
            if (line != lyricLine) {
                lyricLine = line
                for (i in 0 until lyricsBox.childCount) {
                    val tv = lyricsBox.getChildAt(i) as TextView
                    tv.alpha = if (i == line) 1f else 0.45f
                    tv.setTypeface(tv.typeface, if (i == line) Typeface.BOLD else Typeface.NORMAL)
                }
                lyricsBox.getChildAt(line.coerceAtLeast(0))?.let { v ->
                    lyricsScroll.smoothScrollTo(0, (v.top - lyricsScroll.height / 3).coerceAtLeast(0))
                }
            }
        }
    }

    private fun loadLyrics(key: String, t: Track?) {
        lyricsFor = key
        lyrics = null
        lyricLine = -1
        lyricsBox.removeAllViews()
        lyricsBox.addView(line("Recherche des paroles…", false))
        lyricsJob?.cancel()
        if (t == null) return
        lyricsJob = lifecycleScope.launch {
            val l = Music.lyrics(t.title, t.artist, (player.duration / 1000).coerceAtLeast(t.duration))
            if (lyricsFor != key) return@launch
            lyrics = l
            lyricsBox.removeAllViews()
            when {
                l == null -> lyricsBox.addView(line("Paroles introuvables pour ce titre.", false))
                l.synced != null -> l.synced.forEach { (ms, text) ->
                    lyricsBox.addView(line(text.ifBlank { "♪" }, true).apply {
                        alpha = 0.45f
                        setOnClickListener { player.seekTo(ms) }
                    })
                }
                else -> lyricsBox.addView(line(l.plain ?: "", false).apply { alpha = 1f })
            }
            Ui.applyFont(lyricsBox)
            updateProgress()
        }
    }

    private fun line(text: String, big: Boolean) = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = if (big) 22f else 17f
        setLineSpacing(0f, 1.15f)
        val d = resources.displayMetrics.density
        setPadding(0, (8 * d).toInt(), 0, (8 * d).toInt())
    }

    private fun showQueue() {
        if (Music.active) {
            val up = Music.upcoming().take(50)
            if (up.isEmpty()) {
                Toast.makeText(this, "Rien après ce titre", Toast.LENGTH_SHORT).show()
                return
            }
            MaterialAlertDialogBuilder(this)
                .setTitle("À suivre")
                .setItems(up.map { (_, t) -> listOfNotNull(t.title, t.artist).joinToString(" · ") }.toTypedArray()) { _, w ->
                    Music.jumpTo(up[w].first)
                }
                .show()
        } else {
            val q = Playback.queue
            MaterialAlertDialogBuilder(this)
                .setTitle("File d'attente")
                .setItems(if (q.isEmpty()) arrayOf("Vide") else q.map { it.title }.toTypedArray(), null)
                .show()
        }
    }

    private fun sleepDialog() {
        val mins = listOf(10, 15, 30, 45, 60, 90)
        val labels = mins.map { "$it minutes" } + "Fin du titre" + "Désactiver"
        MaterialAlertDialogBuilder(this)
            .setTitle("Minuteur de sommeil")
            .setItems(labels.toTypedArray()) { _, w ->
                when {
                    w < mins.size -> Playback.setSleep(mins[w])
                    w == mins.size -> Playback.setSleepEndOfItem()
                    else -> Playback.cancelSleep()
                }
                refresh()
            }
            .show()
    }

    companion object {
        /** Choisir une playlist (ou en créer une) pour y ajouter un titre. */
        fun addToPlaylist(a: Activity, t: Track) {
            val pls = Store.playlists()
            val names = pls.map { "${it.name} (${it.tracks.size})" } + "➕ Nouvelle playlist…"
            MaterialAlertDialogBuilder(a)
                .setTitle("Ajouter à une playlist")
                .setItems(names.toTypedArray()) { _, w ->
                    if (w < pls.size) {
                        Store.addToPlaylist(pls[w].id, t)
                        Toast.makeText(a, "Ajouté à « ${pls[w].name} »", Toast.LENGTH_SHORT).show()
                    } else {
                        askName(a, "Nouvelle playlist", "") { name ->
                            val id = Store.createPlaylist(name)
                            Store.addToPlaylist(id, t)
                            Toast.makeText(a, "Playlist « $name » créée", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .show()
        }

        fun askName(a: Activity, title: String, initial: String, done: (String) -> Unit) {
            val d = a.resources.displayMetrics.density
            val input = EditText(a).apply {
                setText(initial)
                hint = "Nom de la playlist"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                setSingleLine()
            }
            val box = FrameLayout(a).apply {
                setPadding((24 * d).toInt(), (8 * d).toInt(), (24 * d).toInt(), 0)
                addView(input)
            }
            MaterialAlertDialogBuilder(a)
                .setTitle(title)
                .setView(box)
                .setPositiveButton("OK") { _, _ ->
                    input.text.toString().trim().takeIf { it.isNotEmpty() }?.let(done)
                }
                .setNegativeButton("Annuler", null)
                .show()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refresh()
    }
}
