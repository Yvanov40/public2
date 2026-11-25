package fr.lecteur

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

@OptIn(UnstableApi::class)
class MainActivity : AppCompatActivity() {

    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var miniBar: View
    private lateinit var miniTitle: TextView
    private lateinit var miniPlay: ImageButton
    private lateinit var searchRow: View
    private lateinit var query: EditText
    private lateinit var status: TextView
    private lateinit var results: RecyclerView

    private val adapter = ResultsAdapter { play(it.url) }
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var loadJob: Job? = null
    private var fullscreen = false

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = updateMiniPlay()
        override fun onPlaybackStateChanged(state: Int) = updateMiniPlay()
        override fun onMediaMetadataChanged(m: MediaMetadata) {
            miniTitle.text = m.title
        }
        override fun onPlayerError(error: PlaybackException) {
            Log.e("Lecteur", "Erreur de lecture", error)
            showStatus("Erreur de lecture (${error.errorCodeName}). Réessaie ou mets l'appli à jour.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        playerView = findViewById(R.id.player)
        miniBar = findViewById(R.id.miniBar)
        miniTitle = findViewById(R.id.miniTitle)
        miniPlay = findViewById(R.id.miniPlay)
        searchRow = findViewById(R.id.searchRow)
        query = findViewById(R.id.query)
        status = findViewById(R.id.status)
        results = findViewById(R.id.results)

        player = PlayerHolder.get(this)
        playerView.player = player
        player.addListener(listener)
        miniTitle.text = player.mediaMetadata.title

        results.layoutManager = LinearLayoutManager(this)
        results.adapter = adapter

        query.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEARCH) { submit(); true } else false
        }
        findViewById<ImageButton>(R.id.searchBtn).setOnClickListener { submit() }

        miniPlay.setOnClickListener {
            if (player.isPlaying) player.pause() else {
                if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                player.play()
            }
        }
        findViewById<Button>(R.id.miniVideo).setOnClickListener { setVideoMode(true) }
        miniBar.setOnClickListener { setVideoMode(true) }

        setupSwipeDown()
        playerView.setFullscreenButtonClickListener { full ->
            requestedOrientation = if (full) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (fullscreen) {
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                } else {
                    // Retour = l'appli passe derrière, l'audio continue
                    moveTaskToBack(true)
                }
            }
        })

        // Relie l'écran au service : c'est lui qui maintient la lecture en arrière-plan
        controllerFuture = MediaController.Builder(
            this, SessionToken(this, ComponentName(this, PlaybackService::class.java))
        ).buildAsync()

        refreshUi()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        // Retour au premier plan : la vidéo revient si on était en mode vidéo
        PlayerHolder.setVideoEnabled(player, PlayerHolder.videoMode)
        updateMiniPlay()
    }

    override fun onStop() {
        super.onStop()
        // Appli en arrière-plan ou écran verrouillé : audio seul, la vidéo n'est plus téléchargée
        if (!isChangingConfigurations) PlayerHolder.setVideoEnabled(player, false)
    }

    override fun onDestroy() {
        player.removeListener(listener)
        playerView.player = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshUi()
    }

    // ——— Entrées : partage, lien, recherche ———

    private fun handleIntent(intent: Intent?) {
        val text = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        } ?: return
        val url = Regex("https?://\\S+").find(text)?.value ?: return
        play(url)
    }

    private fun submit() {
        val text = query.text.toString().trim()
        if (text.isEmpty()) return
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(query.windowToken, 0)
        if (Yt.looksLikeUrl(text)) play(text) else search(text)
    }

    private fun search(q: String) {
        lifecycleScope.launch {
            showStatus("Recherche…")
            try {
                val items = Yt.search(q)
                adapter.submit(items)
                results.scrollToPosition(0)
                if (items.isEmpty()) showStatus("Aucun résultat") else hideStatus()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("Lecteur", "Recherche", e)
                showStatus("Erreur de recherche : ${e.message}")
            }
        }
    }

    private fun play(url: String) {
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            showStatus("Chargement…")
            try {
                val source = withContext(Dispatchers.IO) { Sources.build(Yt.info(url)) }
                player.setMediaSource(source)
                player.prepare()
                player.play()
                hideStatus()
                setVideoMode(true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("Lecteur", "Chargement", e)
                showStatus("Impossible de lire cette vidéo : ${e.message}")
            }
        }
    }

    // ——— Bascule vidéo ⇄ audio ———

    private fun setVideoMode(on: Boolean) {
        PlayerHolder.videoMode = on
        PlayerHolder.setVideoEnabled(player, on)
        if (!on && fullscreen) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        refreshUi()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupSwipeDown() {
        val density = resources.displayMetrics.density
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 == null) return false
                val dy = e2.y - e1.y
                val dx = e2.x - e1.x
                if (dy > 80 * density && abs(dy) > abs(dx) * 1.5f && vy > 500) {
                    setVideoMode(false)
                    return true
                }
                return false
            }
        })
        playerView.setOnTouchListener { _, e -> detector.onTouchEvent(e); false }
    }

    // ——— Affichage ———

    private fun refreshUi() {
        val has = player.mediaItemCount > 0
        val video = PlayerHolder.videoMode
        val land = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        fullscreen = has && video && land

        playerView.isVisible = has && video
        miniBar.isVisible = has && !video
        searchRow.isVisible = !fullscreen
        results.isVisible = !fullscreen
        if (fullscreen) status.isVisible = false

        val lp = playerView.layoutParams as LinearLayout.LayoutParams
        if (fullscreen) {
            lp.height = LinearLayout.LayoutParams.MATCH_PARENT
            lp.weight = 1f
        } else {
            lp.height = resources.displayMetrics.widthPixels * 9 / 16
            lp.weight = 0f
        }
        playerView.layoutParams = lp

        val insets = WindowCompat.getInsetsController(window, window.decorView)
        if (fullscreen) {
            insets.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insets.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            insets.show(WindowInsetsCompat.Type.systemBars())
        }
        updateMiniPlay()
    }

    private fun updateMiniPlay() {
        miniPlay.setImageResource(
            if (player.isPlaying || (player.playWhenReady && player.playbackState == Player.STATE_BUFFERING))
                android.R.drawable.ic_media_pause
            else android.R.drawable.ic_media_play
        )
    }

    private fun showStatus(text: String) {
        status.text = text
        status.isVisible = !fullscreen
    }

    private fun hideStatus() {
        status.isVisible = false
    }
}
