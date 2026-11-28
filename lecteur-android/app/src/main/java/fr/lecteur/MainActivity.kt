package fr.lecteur

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.app.RecoverableSecurityException
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.util.TypedValue
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
import androidx.core.widget.doAfterTextChanged
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import kotlin.math.abs

@OptIn(UnstableApi::class)
class MainActivity : AppCompatActivity() {

    private class FileEntry(val doc: DocumentFile, val name: String, val dir: Boolean, val type: String?)

    private class ChannelPage(val page: Int, var chan: Chan) {
        var subs: String? = null
        var videos: List<Vid>? = null
        var error: String? = null
    }

    private class Opt(val icon: Int, val label: String, val run: () -> Unit)

    private lateinit var root: View
    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var nowPanel: View
    private lateinit var nowTitle: TextView
    private lateinit var nowSub: TextView
    private lateinit var chipDownload: Chip
    private lateinit var chipChannel: Chip
    private lateinit var searchBar: View
    private lateinit var query: EditText
    private lateinit var clearBtn: ImageButton
    private lateinit var loading: LinearProgressIndicator
    private lateinit var pager: ViewPager2
    private lateinit var miniPlayer: View
    private lateinit var miniThumb: ImageView
    private lateinit var miniTitle: TextView
    private lateinit var miniSub: TextView
    private lateinit var miniPlay: ImageButton
    private lateinit var bottomNav: BottomNavigationView

    private val adapters = Array(PAGES) { RowAdapter() }
    private val lists = arrayOfNulls<RecyclerView>(PAGES)
    private val refreshers = arrayOfNulls<SwipeRefreshLayout>(PAGES)
    private val navIds = intArrayOf(R.id.nav_home, R.id.nav_offline)

    /** Bulle choisie sur l'Accueil : all, music, videos, podcasts. */
    private var homeFilter = "all"
    private var settingsOpen = false
    private var searchOpen = false
    private var bubbleKey = ""

    // ——— Musique ———
    private sealed class MPage {
        class AlbumP(var album: Music.Album) : MPage() { var tracks: List<Track>? = null; var error: String? = null }
        class ArtistP(var artist: Music.Artist) : MPage() {
            var songs: List<Track>? = null; var albums: List<Music.Album>? = null; var error: String? = null
        }
        class PlaylistP(val id: String) : MPage()
        object Liked : MPage()
        object Recent : MPage()
        class LocalAlbumP(val album: Music.LocalAlbum) : MPage()
        class LocalArtistP(val name: String) : MPage()
        object LocalAll : MPage()
        object LocalArtists : MPage()
        object LocalAlbums : MPage()
    }

    private val musicStack = ArrayList<MPage>()
    private var musicQuery: String? = null
    private var musicResult: Music.SearchResult? = null
    private var musicJob: Job? = null
    private var localMusic: List<Track>? = null
    private var localMusicJob: Job? = null

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var loadJob: Job? = null
    private var loadingCount = 0
    private var fullscreen = false

    private var searchQuery: String? = null
    private var searchResults: List<InfoItem> = emptyList()
    private var feed: List<Vid>? = null
    private var feedJob: Job? = null
    private var subsFeed: List<Vid>? = null
    private var subsFeedTime = 0L
    private var subsJob: Job? = null
    private var channelPage: ChannelPage? = null
    private var channelJob: Job? = null

    // Podcasts
    private class PodPage(val page: Int, var podcast: Podcast) {
        var episodes: List<Episode>? = null
        var error: String? = null
    }
    private var podPage: PodPage? = null

    /** Sélection multiple (appui long) : épisodes ou fichiers de la bibliothèque. */
    private class Sel(val episodes: Boolean, val universe: List<Any>) {
        val keys = LinkedHashSet<String>()
        val items = LinkedHashMap<String, Any>()
        var last = -1
    }
    private var sel: Sel? = null
    private var podJob: Job? = null
    private var podResults: List<Podcast> = emptyList()
    private var podFeed: List<Episode>? = null
    private var podFeedTime = 0L
    private var podFeedJob: Job? = null
    private var subsMode = "yt"
    private var podQuery: String? = null
    private var podResults2: List<Podcast>? = null
    private var podSearchJob: Job? = null
    private val folderStack = ArrayList<Pair<DocumentFile, String>>()
    private var folderFiles: List<FileEntry>? = null
    private var folderJob: Job? = null
    private var pendingDownload: Triple<String, Boolean, String?>? = null
    private var localFiles: List<LocalMedia.Item>? = null
    private var scanJob: Job? = null
    private var pendingDelete: OffItem? = null
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            updateChips()
            if (Playback.sleepAt > 0) ui.postDelayed(this, 30_000)
        }
    }

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) lifecycleScope.launch {
            busy(true)
            try {
                val r = withContext(Dispatchers.IO) { Backup.importSubscriptions(applicationContext, uri) }
                snack("Importé : ${r.podcasts.size} podcasts, ${r.channels.size} chaînes")
                podFeed = null
                subsFeed = null
                renderAll()
                // Récupère les pochettes des podcasts importés
                withContext(Dispatchers.IO) {
                    r.podcasts.forEach { p ->
                        runCatching { Pod.feed(p.feedUrl).first }.getOrNull()?.let { info ->
                            Store.subscribePodcast(info.copy(feedUrl = p.feedUrl))
                        }
                    }
                }
                renderAll()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                snack("Import impossible : ${e.message}")
            } finally {
                busy(false)
            }
        }
    }
    private val backupLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) try {
            Backup.export(this, uri)
            snack("Sauvegarde enregistrée")
        } catch (e: Exception) {
            snack("Sauvegarde impossible : ${e.message}")
        }
    }
    private val restoreLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) MaterialAlertDialogBuilder(this)
            .setTitle("Restaurer ?")
            .setMessage("Abonnements, historique, positions de lecture et réglages actuels seront remplacés.")
            .setPositiveButton("Restaurer") { _, _ ->
                try {
                    Backup.restore(this, uri)
                    Settings.applyNightMode()
                    recreate()
                } catch (e: Exception) {
                    snack("Restauration impossible : ${e.message}")
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }
    private val opmlLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/x-opml")) { uri ->
        if (uri != null) try {
            Backup.exportOpml(this, uri)
            snack("${Store.podcasts().size} podcasts exportés")
        } catch (e: Exception) {
            snack("Export impossible : ${e.message}")
        }
    }

    /** Un fichier de Music/Lecteur ou Movies/Lecteur, avec les infos YouTube si on les connaît. */
    private class OffItem(
        val uri: Uri, val title: String, val uploader: String?, val thumb: String?,
        val video: Boolean, val path: String, val storeUri: String?,
        val podcast: Boolean = false, val added: Long = 0
    )

    /** Filtre de la Bibliothèque : all, video, audio, podcast, folders */
    private var libFilter = "all"

    private val mediaPerm = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        localMusic = null
        render(MUSIC)
        rescanLocal()
    }
    private val deleteLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
            val item = pendingDelete
            pendingDelete = null
            if (res.resultCode == RESULT_OK && item != null) {
                if (Build.VERSION.SDK_INT == 29) {
                    try { contentResolver.delete(item.uri, null, null) } catch (_: Exception) {}
                }
                item.storeUri?.let { Store.removeDownload(it) }
            }
            rescanLocal()
        }

    private val openFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) playSingleLocal(uri)
    }
    private val openTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
            }
            Store.addFolder(uri.toString())
            render(OFFLINE)
        }
    }
    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val storagePerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val p = pendingDownload
        pendingDownload = null
        if (granted && p != null) startDownload(p.first, p.second, p.third)
        else if (!granted) snack("Permission refusée : impossible d'enregistrer le fichier")
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = updateMiniPlay()
        override fun onPlaybackStateChanged(state: Int) = updateMiniPlay()
        override fun onMediaMetadataChanged(m: MediaMetadata) = updateNowPlaying()
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = refreshUi()
        override fun onPlayerError(error: PlaybackException) {
            Log.e("Lecteur", "Erreur de lecture", error)
            // Si l'appli est déjà en train de réessayer, pas de message d'erreur
            if (Playback.recovering) return
            val why = if (error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
                "YouTube a refusé le fichier" else "Erreur de lecture (${error.errorCodeName})"
            snack("$why. Réessaie, ou lance Réglages → Diagnostic YouTube.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Ui.applyTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        root = findViewById(R.id.root)
        Ui.applyFont(root)
        playerView = findViewById(R.id.player)
        nowPanel = findViewById(R.id.nowPanel)
        nowTitle = findViewById(R.id.nowTitle)
        nowSub = findViewById(R.id.nowSub)
        chipDownload = findViewById(R.id.chipDownload)
        chipChannel = findViewById(R.id.chipChannel)
        searchBar = findViewById(R.id.searchBar)
        query = findViewById(R.id.query)
        clearBtn = findViewById(R.id.clearBtn)
        loading = findViewById(R.id.loading)
        pager = findViewById(R.id.pager)
        miniPlayer = findViewById(R.id.miniPlayer)
        miniThumb = findViewById(R.id.miniThumb)
        miniTitle = findViewById(R.id.miniTitle)
        miniSub = findViewById(R.id.miniSub)
        miniPlay = findViewById(R.id.miniPlay)
        bottomNav = findViewById(R.id.bottomNav)

        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !night
            isAppearanceLightNavigationBars = !night
        }
        Ui.styleSystemBars(this, MaterialColors.getColor(root, com.google.android.material.R.attr.colorSurface), night)

        player = PlayerHolder.get(this)
        playerView.player = player
        player.addListener(listener)

        // Recherche
        query.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEARCH) { submit(); true } else false
        }
        query.doAfterTextChanged { clearBtn.isVisible = !it.isNullOrEmpty() }
        clearBtn.setOnClickListener { clearSearch() }

        // Écrans : glisser à gauche / à droite, ou barre du bas
        pager.adapter = PagesAdapter()
        // Un seul onglet : la page des fichiers s'ouvre depuis les encarts « Sur cet appareil »
        pager.isUserInputEnabled = false
        bottomNav.isVisible = false
        pager.offscreenPageLimit = PAGES - 1
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                if (bottomNav.selectedItemId != navIds[position]) bottomNav.selectedItemId = navIds[position]
                if (settingsOpen) settingsOpen = false
                if (sel != null) { sel = null; updateSelBar() }
                if (position == 1) rescanLocal() else ensureLoads()
                renderAll()
            }
        })
        findViewById<View>(R.id.settingsBtn).setOnClickListener {
            settingsOpen = !settingsOpen
            renderAll()
            lists[pager.currentItem]?.scrollToPosition(0)
        }
        bottomNav.setOnItemSelectedListener { item ->
            val i = navIds.indexOf(item.itemId)
            if (i >= 0 && pager.currentItem != i) pager.setCurrentItem(i, true)
            true
        }
        bottomNav.setOnItemReselectedListener { item -> onReselect(navIds.indexOf(item.itemId)) }

        // Sous la vidéo
        findViewById<Chip>(R.id.chipAudio).setOnClickListener { setVideoMode(false) }
        chipDownload.setOnClickListener {
            PlayerHolder.current?.let { askDownload(it.url, it.title) }
        }
        chipChannel.setOnClickListener {
            PlayerHolder.current?.let { v ->
                v.uploaderUrl?.let { openChannel(Chan(it, v.uploader ?: "Chaîne", null)) }
            }
        }
        findViewById<Chip>(R.id.chipClose).setOnClickListener { stopPlayback() }
        findViewById<Chip>(R.id.chipSleep).setOnClickListener { sleepMenu() }
        findViewById<Chip>(R.id.chipInfo).setOnClickListener { infoSheet() }
        findViewById<Chip>(R.id.chipQueue).setOnClickListener { queueSheet() }
        miniPlayer.setOnLongClickListener { playerMenu(); true }

        // Mini-lecteur
        miniPlayer.setOnClickListener {
            // Musique ou audio : écran « en cours de lecture » ; vidéo : on réaffiche l'image
            openFromMini()
        }
        findViewById<ImageButton>(R.id.miniVideo).setOnClickListener { setVideoMode(true) }
        findViewById<ImageButton>(R.id.miniClose).setOnClickListener { stopPlayback() }
        miniPlay.setOnClickListener {
            if (player.isPlaying) player.pause() else {
                if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                player.play()
            }
        }

        // Bulles fixes : retour et recherche
        findViewById<View>(R.id.backBubble).setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        findViewById<View>(R.id.searchBubble).setOnClickListener { toggleSearch() }

        // Mise à jour (icône en haut)
        findViewById<View>(R.id.updateBtn).setOnClickListener { checkUpdate(manual = true) }
        showUpdateDot(false)

        // Chromecast
        try {
            com.google.android.gms.cast.framework.CastButtonFactory.setUpMediaRouteButton(
                applicationContext, findViewById(R.id.castBtn)
            )
        } catch (_: Throwable) {
            findViewById<View>(R.id.castBtn).isVisible = false
        }
        findViewById<View>(R.id.castPlay).setOnClickListener { Caster.togglePlayback() }
        findViewById<View>(R.id.castStop).setOnClickListener { Caster.disconnect() }
        findViewById<View>(R.id.castBar).setOnClickListener {
            if (player.mediaItemCount > 0) startActivity(Intent(this, NowPlayingActivity::class.java))
        }
        Caster.listener = { runOnUiThread { updateCastBar() } }

        setupSwipeDown()
        playerView.setFullscreenButtonClickListener { full ->
            requestedOrientation = if (full) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val phys = pager.currentItem
                val v = currentVirtual()
                when {
                    fullscreen -> requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    sel != null -> selEnd()
                    settingsOpen -> { settingsOpen = false; renderAll() }
                    podPage?.page == phys -> closePodcast()
                    channelPage?.page == phys -> closeChannel()
                    v == SUBS && podQuery != null -> clearPodSearch()
                    v == MUSIC && musicStack.isNotEmpty() -> popMusic()
                    v == MUSIC && musicQuery != null -> clearSearch()
                    v == OFFLINE && folderStack.isNotEmpty() -> leaveFolder()
                    v == HOME && searchQuery != null -> clearSearch()
                    searchOpen -> toggleSearch()
                    phys == 0 && homeFilter != "all" -> show(ALL)
                    phys != 0 -> pager.setCurrentItem(0, true)
                    else -> moveTaskToBack(true) // l'audio continue
                }
            }
        })

        // Relie l'écran au service : c'est lui qui maintient la lecture en arrière-plan
        controllerFuture = MediaController.Builder(
            this, SessionToken(this, ComponentName(this, PlaybackService::class.java))
        ).buildAsync()

        Playback.listener = { refreshUi() }
        Store.onChange = { rescanLocal() }
        Downloads.onChange = { render(OFFLINE) }
        refreshUi()
        renderAll()
        savedInstanceState?.getString("filter")?.let { homeFilter = it }
        // Après un changement de couleur / police : on reste dans les réglages
        if (savedInstanceState?.getBoolean("settings") == true) settingsOpen = true
        savedInstanceState?.getInt("page", -1)?.takeIf { it in 0 until PAGES }?.let {
            pager.setCurrentItem(it, false)
        }
        renderAll()
        if (savedInstanceState == null) {
            handleIntent(intent)
            autoUpdateCheck()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    // ——— Image dans l'image ———
    private var inPip = false

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Settings.pip && player.isPlaying && PlayerHolder.videoMode && player.mediaItemCount > 0) {
            try {
                enterPictureInPictureMode(
                    android.app.PictureInPictureParams.Builder()
                        .setAspectRatio(android.util.Rational(16, 9)).build()
                )
            } catch (_: Exception) {
            }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
        playerView.useController = !inPip
        refreshUi()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("page", pager.currentItem)
        outState.putString("filter", homeFilter)
        outState.putBoolean("settings", settingsOpen)
    }

    override fun onStart() {
        super.onStart()
        Updates.resumePending(this)
        // Retour au premier plan : la vidéo revient si on était en mode vidéo
        PlayerHolder.setVideoEnabled(player, PlayerHolder.videoMode)
        refreshUi()
        rescanLocal()
    }

    override fun onStop() {
        super.onStop()
        // Appli en arrière-plan ou écran verrouillé : audio seul, la vidéo n'est plus téléchargée
        if (!isChangingConfigurations && !inPip) PlayerHolder.setVideoEnabled(player, false)
    }

    override fun onDestroy() {
        Store.onChange = null
        Downloads.onChange = null
        Playback.listener = null
        Caster.listener = null
        ui.removeCallbacks(ticker)
        player.removeListener(listener)
        playerView.player = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshUi()
    }

    // ——— Pages ———

    private inner class PagesAdapter : RecyclerView.Adapter<PagesAdapter.PageHolder>() {
        inner class PageHolder(v: View) : RecyclerView.ViewHolder(v) {
            val refresh: SwipeRefreshLayout = v.findViewById(R.id.refresh)
            val list: RecyclerView = v.findViewById(R.id.list)
        }

        override fun getItemCount() = PAGES

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            PageHolder(LayoutInflater.from(parent.context).inflate(R.layout.page, parent, false))

        override fun onBindViewHolder(h: PageHolder, position: Int) {
            if (h.list.layoutManager == null) {
                val glm = GridLayoutManager(h.list.context, columns())
                glm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(p: Int) = adapters[position].spanSize(p, glm.spanCount)
                }
                h.list.layoutManager = glm
                attachPinch(h.list)
            }
            h.list.adapter = adapters[position]
            applyGrid(h.list)
            lists[position] = h.list
            refreshers[position] = h.refresh
            h.refresh.isEnabled = position == 0
            h.refresh.setOnRefreshListener { onPullRefresh(position) }
        }
    }

    /** À 1 colonne, la grille en compte 2 pour que les tuiles carrées aillent par deux. */
    // ——— Pincer / écarter deux doigts : nombre de colonnes ———

    /** Niveaux : 0 = liste, puis cartes à 1, 2, 3, 4 colonnes. */
    private fun layoutLevel() = if (!Settings.cards) 0 else Settings.columns.coerceIn(1, 4)

    private fun setLayoutLevel(level: Int) {
        val l = level.coerceIn(0, 4)
        if (l == layoutLevel()) return
        if (l == 0) Settings.layout = "list" else {
            Settings.layout = "cards"
            Settings.columns = l
        }
        relayout()
        val label = when (l) { 0 -> "Liste"; 1 -> "1 colonne"; else -> "$l colonnes" }
        Toast.makeText(this, label, Toast.LENGTH_SHORT).show()
    }

    /** Au bout de la plage (4 colonnes / liste) : on continue en changeant la taille des tuiles. */
    private fun scaleTiles(delta: Float) {
        val v = ((Settings.tileScale + delta) * 10).let { Math.round(it) / 10f }.coerceIn(0.7f, 1.4f)
        if (v == Settings.tileScale) return
        Settings.tileScale = v
        relayout()
        Toast.makeText(this, "Taille des tuiles : ${Math.round(v * 100)} %", Toast.LENGTH_SHORT).show()
    }

    private fun attachPinch(list: RecyclerView) {
        var acc = 1f
        val detector = android.view.ScaleGestureDetector(this, object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(d: android.view.ScaleGestureDetector): Boolean { acc = 1f; return true }
            override fun onScale(d: android.view.ScaleGestureDetector): Boolean {
                acc *= d.scaleFactor
                when {
                    // Pincer : tuiles plus petites, plus de colonnes
                    acc < 0.75f -> { if (layoutLevel() < 4) setLayoutLevel(layoutLevel() + 1) else scaleTiles(-0.1f); acc = 1f }
                    // Écarter : tuiles plus grandes, moins de colonnes (jusqu'à la liste)
                    acc > 1.35f -> { if (layoutLevel() > 0) setLayoutLevel(layoutLevel() - 1) else scaleTiles(0.1f); acc = 1f }
                }
                return true
            }
        })
        list.addOnItemTouchListener(object : RecyclerView.OnItemTouchListener {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                detector.onTouchEvent(e)
                if (e.pointerCount > 1) {
                    rv.parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
                return false
            }
            override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) { detector.onTouchEvent(e) }
            override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}
        })
    }

    private fun columns() = if (Settings.cards) Settings.columns.coerceIn(1, 4).coerceAtLeast(2) else 1

    private fun applyGrid(list: RecyclerView) {
        (list.layoutManager as? GridLayoutManager)?.spanCount = columns()
        val pad = if (Settings.cards && Settings.columns > 1) (7 * resources.displayMetrics.density).toInt() else 0
        list.setPadding(pad, list.paddingTop, pad, list.paddingBottom)
    }

    private fun onPullRefresh(phys: Int) {
        when (virtualFor(phys)) {
            ALL -> {
                feed = null
                loadFeed()
                loadSubsFeed(force = true)
                loadPodFeed(force = true)
            }
            MUSIC -> refreshers[phys]?.isRefreshing = false
            HOME -> if (searchQuery != null) {
                refresherFor(HOME)?.isRefreshing = false
                searchQuery?.let { search(it) }
            } else {
                feed = null
                loadFeed()
            }
            SUBS -> if (subsMode == "pod") loadPodFeed(force = true) else loadSubsFeed(force = true)
        }
    }

    private fun onReselect(phys: Int) {
        when {
            settingsOpen -> { settingsOpen = false; renderAll() }
            podPage?.page == phys -> closePodcast()
            channelPage?.page == phys -> closeChannel()
            phys == 0 && (searchQuery != null || musicQuery != null || podQuery != null || musicStack.isNotEmpty()) -> {
                musicStack.clear()
                musicQuery = null
                musicResult = null
                searchQuery = null
                searchResults = emptyList()
                podQuery = null
                podResults2 = null
                query.setText("")
                renderAll()
            }
            phys == 0 && homeFilter != "all" -> show(ALL)
            phys == 1 && (folderStack.isNotEmpty() || libFilter != "all") -> {
                libFilter = "all"
                folderStack.clear()
                folderFiles = null
                renderAll()
            }
            else -> lists[phys]?.smoothScrollToPosition(0)
        }
    }

    /** La vue affichée sur un écran réel. */
    private fun virtualFor(phys: Int): Int = when {
        settingsOpen && phys == pager.currentItem -> SETTINGS
        phys == 1 -> OFFLINE
        homeFilter == "music" -> MUSIC
        homeFilter == "videos" -> HOME
        homeFilter == "podcasts" -> SUBS
        else -> ALL
    }

    /** Les vues dont dépend une vue (le « Tout » rassemble un peu de chaque). */
    private fun deps(v: Int): Set<Int> = when (v) {
        ALL -> setOf(ALL, HOME, MUSIC, SUBS)
        HOME -> setOf(HOME, SUBS)
        else -> setOf(v)
    }

    private fun currentVirtual() = virtualFor(pager.currentItem)
    private fun physOf(v: Int): Int? = (0 until PAGES).firstOrNull { v in deps(virtualFor(it)) }
    private fun listFor(v: Int): RecyclerView? = physOf(v)?.let { lists[it] }
    private fun refresherFor(v: Int): SwipeRefreshLayout? = physOf(v)?.let { refreshers[it] }

    /** Affiche une vue : choisit l'onglet et la bulle qui conviennent. */
    private fun show(v: Int) {
        settingsOpen = v == SETTINGS
        when (v) {
            OFFLINE -> pager.setCurrentItem(1, true)
            SETTINGS -> {}
            else -> {
                homeFilter = when (v) {
                    MUSIC -> "music"
                    HOME -> "videos"
                    SUBS -> if (subsMode == "pod") "podcasts" else "videos"
                    else -> "all"
                }
                if (homeFilter == "videos") subsMode = "yt"
                if (homeFilter == "podcasts") subsMode = "pod"
                pager.setCurrentItem(0, true)
            }
        }
        ensureLoads()
        renderAll()
    }

    private fun ensureLoads() {
        if (pager.currentItem != 0) return
        when (homeFilter) {
            "all" -> { loadSubsFeed(); loadPodFeed() }
            "videos" -> loadSubsFeed()
            "podcasts" -> loadPodFeed()
        }
    }

    private fun renderAll() = (0 until PAGES).forEach { renderPhys(it) }

    private fun render(v: Int) {
        for (phys in 0 until PAGES) if (v in deps(virtualFor(phys))) renderPhys(phys)
        if (v == OFFLINE) updateBadge()
    }

    private fun updateBadge() {
        val n = Downloads.active()
        val badge = bottomNav.getOrCreateBadge(R.id.nav_offline)
        badge.isVisible = n > 0
        if (n > 0) badge.number = n
    }

    private fun renderPhys(phys: Int) {
        val rows = mutableListOf<Row>()
        val v = virtualFor(phys)
        val cp = channelPage
        val pp = podPage
        when {
            v == SETTINGS -> {
                rows += Row.Header("Réglages")
                settingsRows(rows)
            }
            pp != null && pp.page == phys -> podcastRows(pp, rows)
            cp != null && cp.page == phys -> channelRows(cp, rows)
            v == ALL -> allRows(rows)
            v == MUSIC -> musicRows(rows)
            v == HOME -> {
                if (searchQuery == null) {
                    quickVideos()?.let { rows += it }
                    stripChannels(rows)
                    deviceRows(rows, "video")
                }
                homeRows(rows, showResume = false)
                // Les nouveautés des chaînes suivies, sous les vidéos
                if (searchQuery == null) {
                    subsMode = "yt"
                    subsRows(rows, strip = false)
                }
            }
            v == SUBS -> subsRows(rows)
            v == OFFLINE -> offlineRows(rows)
        }
        adapters[phys].submit(rows)
        if (phys == pager.currentItem) {
            updateBubbles()
            updateSearchBar()
        }
    }

    // ——— Bulles (en bas) ———

    private fun bubble(label: String, checked: Boolean, icon: Int = 0, onClick: () -> Unit): Chip {
        val d = resources.displayMetrics.density
        return Chip(this).apply {
            setChipDrawable(
                com.google.android.material.chip.ChipDrawable.createFromAttributes(
                    this@MainActivity, null, 0, com.google.android.material.R.style.Widget_Material3_Chip_Filter
                )
            )
            text = label
            isCheckable = true
            isChecked = checked
            isCheckedIconVisible = false
            if (icon != 0) {
                chipIcon = ContextCompat.getDrawable(this@MainActivity, icon)
                isChipIconVisible = true
                if (label.isEmpty()) {
                    textStartPadding = 0f
                    textEndPadding = 0f
                    chipEndPadding = 4 * d
                }
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { marginEnd = (8 * d).toInt() }
            setOnClickListener { onClick() }
            Ui.applyFont(this)
        }
    }

    /** Une sous-page est ouverte sur l'écran courant (réglages, podcast, chaîne, album, dossier). */
    private fun canGoBack(): Boolean {
        val phys = pager.currentItem
        val v = currentVirtual()
        return settingsOpen || phys != 0 || podPage?.page == phys || channelPage?.page == phys ||
            (v == MUSIC && musicStack.isNotEmpty()) || (v == OFFLINE && folderStack.isNotEmpty())
    }

    private fun updateBubbles() {
        val phys = pager.currentItem
        val back = canGoBack()
        val key = "$phys|$homeFilter|$libFilter|$searchOpen|$settingsOpen|$back"
        if (key == bubbleKey) return
        bubbleKey = key
        val box = findViewById<LinearLayout>(R.id.bubbles)
        box.removeAllViews()
        // Retour et recherche : boutons ronds fixes, à gauche des bulles (ne défilent pas)
        findViewById<View>(R.id.backBubble).isVisible = back
        findViewById<com.google.android.material.button.MaterialButton>(R.id.searchBubble).apply {
            isVisible = phys == 0
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (searchOpen) MaterialColors.getColor(this, com.google.android.material.R.attr.colorSecondaryContainer)
                else android.graphics.Color.TRANSPARENT
            )
        }
        if (phys == 0) {
            listOf("all" to "Tout", "music" to "Musique", "videos" to "Vidéos", "podcasts" to "Podcasts").forEach { (k, label) ->
                box.addView(bubble(label, homeFilter == k && !settingsOpen) {
                    settingsOpen = false
                    homeFilter = k
                    if (k == "videos") subsMode = "yt"
                    if (k == "podcasts") subsMode = "pod"
                    ensureLoads()
                    renderAll()
                    lists[0]?.scrollToPosition(0)
                })
            }
        } else {
            // Liste des fichiers : les bulles ramènent à l'accueil sur le bon type
            listOf("all" to "Tout", "music" to "Musique", "videos" to "Vidéos", "podcasts" to "Podcasts").forEach { (k, label) ->
                box.addView(bubble(label, false) {
                    settingsOpen = false
                    homeFilter = k
                    if (k == "videos") subsMode = "yt"
                    if (k == "podcasts") subsMode = "pod"
                    pager.setCurrentItem(0, true)
                    ensureLoads()
                    renderAll()
                    lists[0]?.scrollToPosition(0)
                })
            }
        }
    }

    // ——— Recherche (bulle loupe) ———

    private fun toggleSearch() {
        searchOpen = !searchOpen
        updateSearchBar()
        bubbleKey = ""
        updateBubbles()
        if (searchOpen) {
            query.requestFocus()
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(query, InputMethodManager.SHOW_IMPLICIT)
        } else hideKeyboard()
    }

    private fun updateSearchBar() {
        val active = searchQuery != null || musicQuery != null || podQuery != null
        searchBar.isVisible = (searchOpen || active) && !fullscreen && pager.currentItem == 0 && !settingsOpen
        query.hint = getString(
            when (homeFilter) {
                "music" -> R.string.hint_music
                "podcasts" -> R.string.hint_podcasts
                else -> R.string.hint
            }
        )
    }

    // ——— Entrées : partage, lien, recherche ———

    private fun handleIntent(intent: Intent?) {
        // Notification « Nouveautés » : accueil, bulle « Tout »
        if ((intent?.getIntExtra("page", -1) ?: -1) >= 0) {
            show(ALL)
            return
        }
        val text = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        } ?: return
        val url = Regex("https?://\\S+").find(text)?.value ?: return
        openLink(url)
    }

    private fun submit() {
        val text = query.text.toString().trim()
        if (text.isEmpty()) return
        hideKeyboard()
        when {
            Yt.looksLikeUrl(text) -> openLink(text)
            currentVirtual() == MUSIC -> musicSearch(text)
            currentVirtual() == SUBS -> runPodSearch(text)
            else -> search(text)
        }
    }

    private fun hideKeyboard() {
        query.clearFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(query.windowToken, 0)
    }

    /** Lien YouTube → lecture ; autre lien → flux RSS de podcast. */
    private fun openLink(url: String) {
        val host = Uri.parse(url).host.orEmpty()
        if (host.contains("youtu") || !url.startsWith("http")) play(url)
        else discoverAndOpen(url)
    }

    private fun search(q: String) {
        hideKeyboard()
        Store.addSearch(q)
        feed = null
        if (channelPage?.page == 0) channelPage = null
        if (podPage?.page == 0) podPage = null
        show(HOME)
        lifecycleScope.launch {
            busy(true)
            try {
                val pods = async { runCatching { Pod.search(q) }.getOrDefault(emptyList()) }
                val items = Yt.search(q, "all")
                    .filter { it is StreamInfoItem || it is ChannelInfoItem }
                podResults = pods.await()
                searchQuery = q
                searchResults = items
                render(HOME)
                listFor(HOME)?.scrollToPosition(0)
                if (items.isEmpty()) snack("Aucun résultat")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("Lecteur", "Recherche", e)
                snack("Erreur de recherche : ${e.message}")
            } finally {
                busy(false)
                refresherFor(HOME)?.isRefreshing = false
            }
        }
    }

    private fun clearSearch() {
        searchOpen = false
        if (currentVirtual() == SUBS && podQuery != null) {
            query.setText("")
            clearPodSearch()
            return
        }
        if (currentVirtual() == MUSIC) {
            musicQuery = null
            musicResult = null
            query.setText("")
            hideKeyboard()
            render(MUSIC)
            return
        }
        searchQuery = null
        searchResults = emptyList()
        podResults = emptyList()
        query.setText("")
        hideKeyboard()
        render(HOME)
    }

    private fun play(url: String, audioOnly: Boolean = false) {
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            busy(true)
            try {
                Playback.playYoutube(url, audioOnly)
                refreshUi()
                if (searchQuery == null) render(HOME)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("Lecteur", "Chargement", e)
                snack("Impossible de lire cette vidéo : ${e.message}")
            } finally {
                busy(false)
            }
        }
    }

    private fun stopPlayback() {
        if (fullscreen) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        player.stop()
        player.clearMediaItems()
        Playback.startedLocal()
        refreshUi()
    }

    // ——— Tuiles et ronds propres à chaque bulle ———

    // ——— Encart « Sur cet appareil » de chaque bulle (remplace l'onglet Bibliothèque) ———

    /** Ouvre la liste des fichiers (téléchargés ou dossiers) ; la bulle ← ramène à l'accueil. */
    private fun openLib(key: String) {
        libFilter = key
        if (key != "folders") {
            folderStack.clear()
            folderFiles = null
        }
        settingsOpen = false
        pager.setCurrentItem(1, true)
        renderAll()
        lists[1]?.scrollToPosition(0)
    }

    /** kind : all, music, video, podcast. */
    private fun deviceRows(rows: MutableList<Row>, kind: String) {
        if (localFiles == null && scanJob == null) rescanLocal()
        val all = offlineItems()
        val list = when (kind) {
            "music" -> all.filter { !it.video && !it.podcast }
            "video" -> all.filter { it.video }
            "podcast" -> all.filter { it.podcast }
            else -> all
        }
        val running = Downloads.all().count {
            it.state == Downloads.State.WAITING || it.state == Downloads.State.RUNNING
        }
        val dlTitle = "Téléchargés · ${list.size}" + if (running > 0) " · $running en cours" else ""
        val libKey = when (kind) { "music" -> "audio"; "video" -> "video"; "podcast" -> "podcast"; else -> "all" }
        val items = mutableListOf(
            Row.QuickItem(dlTitle, list.firstOrNull()?.thumb?.takeIf { it.startsWith("http") }, R.drawable.ic_download, { openLib(libKey) })
        )
        items += if (kind == "music") {
            Row.QuickItem("Sur le téléphone", null, R.drawable.ic_folder, {
                if (hasMediaPerm()) pushMusic(MPage.LocalAll) else askMediaPerm()
            })
        } else {
            Row.QuickItem("Sur le téléphone", null, R.drawable.ic_folder, { openLib("folders") })
        }
        rows += Row.Header("Sur cet appareil")
        rows += Row.Quick(items)
    }

    /** « Album - La fête est finie » → « La fête est finie » (titres YouTube Music). */
    private fun albumName(t: String) = t.replace(Regex("^(Album|EP|Single)\\s*[-–−:]\\s*", RegexOption.IGNORE_CASE), "")

    /** Accès rapide musique, en deux parties : mes albums, mes titres. */
    private fun quickMusic(rows: MutableList<Row>) {
        val albums = mutableListOf<Row.QuickItem>()
        val recentA = Store.recentAlbums()
        (recentA + Store.savedAlbums()).distinctBy { it.url }.forEach { a ->
            val recent = recentA.any { it.url == a.url }
            albums += Row.QuickItem(albumName(a.title), a.thumb, R.drawable.ic_music, { pushMusic(MPage.AlbumP(a)) }, { albumMenu(a, recent = recent) })
        }
        Store.playlists().forEach { p ->
            albums += Row.QuickItem(p.name, p.tracks.firstOrNull()?.thumb, R.drawable.ic_queue, { pushMusic(MPage.PlaylistP(p.id)) }, { playlistMenu(p) })
        }
        if (albums.isNotEmpty()) {
            rows += Row.Header("Mes albums")
            rows += Row.Quick(albums.take(6))
        }
        val titles = mutableListOf<Row.QuickItem>()
        val likes = Store.likes()
        if (likes.isNotEmpty()) titles += Row.QuickItem("Titres likés · ${likes.size}", likes.first().thumb, R.drawable.ic_heart, { pushMusic(MPage.Liked) })
        val recent = Store.recentTracks()
        recent.take(6 - titles.size).forEachIndexed { i, t ->
            titles += Row.QuickItem(t.title, t.thumb, R.drawable.ic_music, { Music.play(recent, i); openNowPlayingSoon() }, { trackMenu(t, null, i) })
        }
        if (titles.isNotEmpty()) {
            rows += Row.Header("Mes titres", if (recent.size > 5) "Tout voir" else null) { pushMusic(MPage.Recent) }
            rows += Row.Quick(titles)
        }
    }

    private fun quickVideos(): Row? {
        val w = Store.watched().take(8)
        if (w.isEmpty()) return null
        return Row.Quick(w.map { v -> Row.QuickItem(v.title, v.thumb, R.drawable.ic_video, { play(v.url) }, { videoMenu(v) }) })
    }

    /** Épisodes commencés d'abord, puis les plus récents pas encore écoutés. */
    private fun quickPodcasts(): Row? {
        val f = podFeed ?: return null
        val started = f.filter { Store.position(Pod.mediaId(it)) > 0 }
        val fresh = f.filter { Store.position(Pod.mediaId(it)) == 0L }
        val pick = (started + fresh).distinctBy { it.audioUrl }.take(6)
        if (pick.isEmpty()) return null
        return Row.Quick(pick.map { e ->
            Row.QuickItem(e.title, e.image ?: e.podcastArt, R.drawable.ic_headphones, {
                playEpisodes(f, f.indexOf(e).coerceAtLeast(0))
            }, { episodeMenu(f, f.indexOf(e).coerceAtLeast(0)) })
        })
    }

    private fun stripArtists(rows: MutableList<Row>) {
        val artists = (Store.savedArtists() + Store.recentArtists()).distinctBy { it.url }
        if (artists.isEmpty()) return
        rows += Row.Header("Mes artistes")
        rows += Row.Avatars(
            artists.map { Chan(it.url, it.name, it.thumb) },
            onClick = { c -> artists.firstOrNull { it.url == c.url }?.let { pushMusic(MPage.ArtistP(it)) } },
            onLong = { c -> artists.firstOrNull { it.url == c.url }?.let { artistMenu(it, recent = !Store.isArtistSaved(it.url)) } }
        )
    }

    private fun stripChannels(rows: MutableList<Row>) {
        val subs = Store.subs()
        if (subs.isEmpty()) return
        rows += Row.Header("Mes chaînes")
        rows += Row.Avatars(subs, onClick = { openChannel(it) }, onLong = { confirmUnsubscribe(it) })
    }

    /** Tout : artistes suivis, podcasts et chaînes réunis. */
    private fun stripFavorites(rows: MutableList<Row>) {
        val artists = Store.savedArtists()
        val pods = Store.podcasts()
        val chans = Store.subs()
        val items = artists.map { Chan(it.url, it.name, it.thumb) } +
            pods.map { Chan(it.feedUrl, it.title, it.artwork) } + chans
        if (items.isEmpty()) return
        rows += Row.Header("Mes favoris")
        rows += Row.Avatars(
            items.distinctBy { it.url },
            onClick = { c ->
                val a = artists.firstOrNull { it.url == c.url }
                val p = pods.firstOrNull { it.feedUrl == c.url }
                when {
                    a != null -> { show(MUSIC); pushMusic(MPage.ArtistP(a)) }
                    p != null -> openPodcast(p)
                    else -> openChannel(c)
                }
            },
            onLong = { c ->
                val a = artists.firstOrNull { it.url == c.url }
                val p = pods.firstOrNull { it.feedUrl == c.url }
                when {
                    a != null -> artistMenu(a)
                    p != null -> confirmUnsubscribePodcast(p)
                    else -> confirmUnsubscribe(c)
                }
            }
        )
    }

    // ——— Accueil « Tout » : accès rapide façon Spotify ———

    /** Prend à tour de rôle dans chaque liste : a1, b1, c1, a2, b2… */
    private fun <T> interleave(vararg lists: List<T>): List<T> {
        val out = mutableListOf<T>()
        val n = lists.maxOfOrNull { it.size } ?: 0
        for (i in 0 until n) lists.forEach { l -> l.getOrNull(i)?.let { out += it } }
        return out
    }

    private fun allRows(rows: MutableList<Row>) {
        // Deux colonnes : à gauche albums, podcasts, chaînes ; à droite titres, épisodes, vidéos
        val qPods = Store.podcasts()
        if (podFeed == null && qPods.isNotEmpty()) loadPodFeed()
        val qFeed = podFeed.orEmpty()
        val started = qFeed.filter { Store.position(Pod.mediaId(it)) > 0 }
        val fresh = qFeed.filter { Store.position(Pod.mediaId(it)) == 0L }

        val qAlbums = (Store.recentAlbums() + Store.savedAlbums()).distinctBy { it.url }.take(3).map { a ->
            Row.QuickItem(albumName(a.title), a.thumb, R.drawable.ic_music, { show(MUSIC); pushMusic(MPage.AlbumP(a)) }, { albumMenu(a) })
        } + Store.playlists().take(1).map { p ->
            Row.QuickItem(p.name, p.tracks.firstOrNull()?.thumb, R.drawable.ic_queue, { show(MUSIC); pushMusic(MPage.PlaylistP(p.id)) }, { playlistMenu(p) })
        }
        // Podcasts : d'abord ceux qu'on est en train d'écouter
        val podOrder = (started.map { it.feedUrl } + qPods.map { it.feedUrl }).distinct()
        val podcasts = podOrder.mapNotNull { u -> qPods.firstOrNull { it.feedUrl == u } }.take(3).map { p ->
            Row.QuickItem(p.title, p.artwork, R.drawable.ic_headphones, { openPodcast(p) }, { confirmUnsubscribePodcast(p) })
        }
        // Chaînes : celles des vidéos vues récemment, puis les abonnements
        val qSubs = Store.subs()
        val qWatched = Store.watched()
        val chanOrder = (qWatched.mapNotNull { it.uploaderUrl } + qSubs.map { it.url }).distinct()
        val channels = chanOrder.mapNotNull { u -> qSubs.firstOrNull { it.url == u } }.take(3).map { c ->
            Row.QuickItem(c.name, c.avatar, R.drawable.ic_person, { openChannel(c) })
        }
        val left = interleave(qAlbums, podcasts, channels).take(4)

        val qRecent = Store.recentTracks()
        val tracks = qRecent.take(3).mapIndexed { i, t ->
            Row.QuickItem(t.title, t.thumb, R.drawable.ic_music, { Music.play(qRecent, i); openNowPlayingSoon() }, { trackMenu(t, null, i) })
        }
        val episodes = (started + fresh).distinctBy { it.audioUrl }.take(3).map { e ->
            val i = qFeed.indexOf(e).coerceAtLeast(0)
            Row.QuickItem(e.title, e.image ?: e.podcastArt, R.drawable.ic_headphones, { playEpisodes(qFeed, i) }, { episodeMenu(qFeed, i) })
        }
        val videos = qWatched.take(3).map { v ->
            Row.QuickItem(v.title, v.thumb, R.drawable.ic_video, { play(v.url) }, { videoMenu(v) })
        }
        val right = interleave(tracks, episodes, videos).take(4)

        // Grille de 2 : on alterne gauche / droite pour former les deux colonnes (case vide si besoin)
        val quick = mutableListOf<Row.QuickItem>()
        val blank = Row.QuickItem("", null, 0, {})
        for (i in 0 until maxOf(left.size, right.size)) {
            quick += left.getOrNull(i) ?: blank
            quick += right.getOrNull(i) ?: blank
        }
        if (quick.isNotEmpty()) rows += Row.Quick(quick.take(8), "Mes artistes" to "Mes lectures")
        stripFavorites(rows)
        deviceRows(rows, "all")

        val watched = Store.watched()
        resumeRows(rows, watched)
        val albums = Store.recentAlbums() + Store.savedAlbums().filterNot { s -> Store.recentAlbums().any { it.url == s.url } }
        if (albums.size > 3) {
            rows += Row.Header("Ta musique", "Tout voir") { show(MUSIC) }
            rows += Row.Cards(albums.map { a ->
                Row.Card(a.title, a.artist, a.thumb, onClick = { show(MUSIC); pushMusic(MPage.AlbumP(a)) }, onLong = { albumMenu(a) })
            })
        }
        podFeed?.takeIf { it.isNotEmpty() }?.let { f ->
            rows += Row.Header("Nouveaux épisodes", "Tout voir") { subsMode = "pod"; show(SUBS) }
            f.indices.take(3).forEach { rows += episodeRow(f, it, true) }
        }
        subsFeed?.takeIf { it.isNotEmpty() }?.let { f ->
            rows += Row.Header("Nouveautés de tes chaînes", "Tout voir") { subsMode = "yt"; show(HOME) }
            f.take(4).forEach { rows += videoRow(it) }
        }
        val fd = feed
        if (Store.searches().isNotEmpty()) {
            if (fd == null) loadFeed()
            else if (fd.isNotEmpty()) {
                rows += Row.Header("Pour toi", "Tout voir") { show(HOME) }
                fd.take(4).forEach { rows += videoRow(it) }
            }
        }
        if (rows.isEmpty()) {
            rows += Row.Note(
                "Bienvenue !\nTouche la loupe en bas pour chercher une vidéo, une musique ou un podcast. " +
                    "Cet écran se remplira avec ce que tu écoutes.",
                R.drawable.ic_search
            )
        }
    }

    // ——— Accueil ———

    /** « Reprendre » : vidéos et épisodes de podcast commencés, le plus récent d'abord. */
    private fun resumeRows(rows: MutableList<Row>, watched: List<Vid>) {
        val episodes = (Store.recentEpisodes() + podFeed.orEmpty()).distinctBy { it.audioUrl }
        val epById = episodes.associateBy { Pod.mediaId(it) }
        val vidById = watched.associateBy { it.url }
        // Ordre : lectures commencées (dernière écoutée d'abord), puis les autres vidéos vues
        val started = Store.startedIds().filter { it in epById || it in vidById }
        val ids = (started + watched.map { it.url }).distinct().take(14)
        if (ids.size < 2) return
        val cards = ids.mapNotNull { id ->
            vidById[id]?.let { v -> v.copy(meta = listOfNotNull(v.uploader, resumeText(v.url)).joinToString(" · ")) }
                ?: epById[id]?.let { e ->
                    Vid(id, e.title, e.podcastTitle, null, e.image ?: e.podcastArt, e.duration,
                        meta = listOfNotNull("🎧 " + e.podcastTitle, resumeText(id)).joinToString(" · "))
                }
        }
        rows += Row.Header("Reprendre")
        rows += Row.Carousel(
            cards,
            onClick = { c ->
                val e = epById[c.url]
                if (e != null) {
                    val list = episodes.filter { it.feedUrl == e.feedUrl }.sortedByDescending { it.date }
                    playEpisodes(list, list.indexOf(e).coerceAtLeast(0))
                } else play(c.url)
            },
            onLong = { c ->
                val e = epById[c.url]
                if (e != null) episodeMenu(listOf(e), 0) else videoMenu(c)
            }
        )
    }

    private fun resumeText(id: String): String? {
        val pos = Store.position(id)
        return when {
            pos < 0 -> "Vu"
            pos > 0 -> "Reprise à " + Yt.formatDuration(pos / 1000)
            else -> null
        }
    }

    /** Ajoute « Reprise à 12:34 » ou « Vu » aux infos de la vidéo. */
    private fun withResume(v: Vid): Vid {
        val r = resumeText(v.url) ?: return v
        return v.copy(meta = listOfNotNull((v.meta ?: v.uploader)?.ifEmpty { null }, r).joinToString(" · "))
    }

    private fun videoRow(v: Vid) = Row.Video(withResume(v), onClick = { play(v.url) }, onLong = { videoMenu(v) })

    private fun searchRow(item: InfoItem): Row? = when (item) {
        is StreamInfoItem -> videoRow(Yt.vid(item))
        is ChannelInfoItem -> {
            val c = Chan(item.url, item.name, Sources.bestThumb(item.thumbnails, 88))
            Row.Channel(
                c, Yt.subs(item.subscriberCount), Store.isSubscribed(c.url),
                onClick = { openChannel(c) }, onToggle = { toggleSub(c) }
            )
        }
        else -> null
    }

    private fun homeRows(rows: MutableList<Row>, showResume: Boolean = true) {
        val q = searchQuery
        if (q != null) {
            rows += Row.Header("Résultats pour « $q »")
            val pods = podResults
            val ytRows = searchResults.mapNotNull { searchRow(it) }
            // Chaînes et premières vidéos, puis les podcasts, puis le reste
            ytRows.take(4).forEach { rows += it }
            if (pods.isNotEmpty()) {
                rows += Row.Header("Podcasts")
                pods.take(4).forEach { rows += podRow(it) }
                rows += Row.Header("Vidéos")
            }
            ytRows.drop(4).forEach { rows += it }
            return
        }
        val searches = Store.searches()
        val watched = Store.watched()
        if (searches.isNotEmpty()) {
            rows += Row.Header("Recherches récentes", "Effacer") {
                Store.clearSearches()
                feed = emptyList()
                render(HOME)
            }
            rows += Row.Chips(
                searches.take(15),
                onClick = { s -> query.setText(s); search(s) },
                onLong = { s -> Store.removeSearch(s); feed = null; render(HOME) }
            )
        }
        if (showResume && watched.isNotEmpty()) resumeRows(rows, watched)
        if (searches.isNotEmpty()) {
            rows += Row.Header("Pour toi")
            val f = feed
            if (f == null) {
                rows += Row.Note("Chargement…")
                loadFeed()
            } else f.forEach { rows += videoRow(it) }
        }
        if (searches.isEmpty() && watched.isEmpty()) {
            rows += Row.Note(
                "Cherche une vidéo ou une chaîne, ou colle un lien YouTube.\n" +
                    "L'accueil se remplira d'après tes recherches.",
                R.drawable.ic_search
            )
        }
    }

    /** Fil d'accueil : résultats des 3 dernières recherches, entremêlés. */
    private fun loadFeed() {
        if (feedJob?.isActive == true) return
        feedJob = lifecycleScope.launch {
            val qs = Store.searches().take(3)
            val lists = qs.map { q ->
                async {
                    runCatching { Yt.search(q, "videos").filterIsInstance<StreamInfoItem>().map(Yt::vid) }
                        .getOrDefault(emptyList())
                }
            }.awaitAll()
            val out = LinkedHashMap<String, Vid>()
            val max = lists.maxOfOrNull { it.size } ?: 0
            for (i in 0 until max) for (l in lists) l.getOrNull(i)?.let { out.putIfAbsent(it.url, it) }
            feed = out.values.take(45)
            refresherFor(HOME)?.isRefreshing = false
            render(HOME)
        }
    }

    // ——— Abonnements et chaînes ———

    private fun subsRows(rows: MutableList<Row>, strip: Boolean = true) {
        if (subsMode == "pod") {
            podcastSubsRows(rows)
            return
        }
        val subs = Store.subs()
        if (subs.isEmpty()) {
            rows += Row.Note(
                "Aucun abonnement pour l'instant.\nCherche une chaîne et touche « S'abonner », " +
                    "ou fais un appui long sur une vidéo puis « Voir la chaîne ».",
                R.drawable.ic_subs
            )
            return
        }
        if (strip) {
            rows += Row.Header("Mes chaînes")
            rows += Row.Avatars(subs, onClick = { openChannel(it) }, onLong = { confirmUnsubscribe(it) })
        }
        rows += Row.Header("Nouveautés")
        val f = subsFeed
        when {
            f == null -> rows += Row.Note("Chargement…")
            f.isEmpty() -> rows += Row.Note("Rien de nouveau pour l'instant. Tire vers le bas pour actualiser.")
            else -> f.forEach { rows += videoRow(it) }
        }
    }

    private fun loadSubsFeed(force: Boolean = false) {
        if (subsJob?.isActive == true) return
        if (!force && subsFeed != null && System.currentTimeMillis() - subsFeedTime < 15 * 60_000) return
        val subs = Store.subs()
        if (subs.isEmpty()) {
            subsFeed = emptyList()
            refresherFor(SUBS)?.isRefreshing = false
            render(SUBS)
            return
        }
        subsJob = lifecycleScope.launch {
            val sem = Semaphore(4)
            val all = subs.map { c ->
                async { sem.withPermit { runCatching { Yt.latest(c.url) }.getOrDefault(emptyList()) } }
            }.awaitAll()
            subsFeed = all.flatten().distinctBy { it.url }.sortedByDescending { it.date }.take(150)
            subsFeedTime = System.currentTimeMillis()
            refresherFor(SUBS)?.isRefreshing = false
            render(SUBS)
        }
    }

    // ——— Podcasts ———

    private fun podRow(p: Podcast) = Row.Channel(
        Chan(p.feedUrl, p.title, p.artwork), p.author, Store.isPodSubscribed(p.feedUrl),
        onClick = { openPodcast(p) }, onToggle = { togglePodcast(p) }, kind = "Podcast"
    )

    private fun episodeRow(list: List<Episode>, i: Int, showPodcast: Boolean): Row {
        val e = list[i]
        val pos = Store.position(Pod.mediaId(e))
        val state = when {
            pos < 0 -> "Écouté"
            pos > 0 -> "Reprendre à " + Yt.formatDuration(pos / 1000)
            else -> null
        }
        val sub = listOfNotNull(
            if (showPodcast) e.podcastTitle else null, Pod.ago(e.date),
            Yt.formatDuration(e.duration).ifEmpty { null }, state
        ).joinToString(" · ")
        val key = Pod.mediaId(e)
        val sl = sel?.takeIf { it.episodes }
        return Row.Entry(
            (if (pos == 0L) "● " else "") + e.title, sub, e.image ?: e.podcastArt, R.drawable.ic_audio,
            onClick = { if (sl != null) selToggle(key, e, list, i) else playEpisodes(list, i) },
            onLong = { if (sl != null) selRange(list, i) else selStart(true, list, i) },
            dim = pos < 0, tile = true, selected = sl?.keys?.contains(key) == true
        )
    }

    // ——— Tri et filtre des épisodes ———

    private val podFilters = listOf("all" to "Tous", "unplayed" to "Non écoutés", "started" to "En cours", "played" to "Écoutés")
    private val podSorts = listOf("new" to "Plus récents", "old" to "Plus anciens", "long" to "Plus longs", "short" to "Plus courts")

    private fun sortFilter(eps: List<Episode>, sort: Boolean = true): List<Episode> {
        val f = Settings.podFilter
        val kept = if (f == "all") eps else eps.filter {
            val pos = Store.position(Pod.mediaId(it))
            when (f) {
                "unplayed" -> pos >= 0
                "started" -> pos > 0
                "played" -> pos < 0
                else -> true
            }
        }
        if (!sort) return kept
        return when (Settings.podSort) {
            "old" -> kept.sortedBy { it.date }
            "long" -> kept.sortedByDescending { it.duration }
            "short" -> kept.sortedBy { it.duration.takeIf { d -> d > 0 } ?: Long.MAX_VALUE }
            else -> kept.sortedByDescending { it.date }
        }
    }

    /** Bulles de filtre (et de tri sur la page d'un podcast). */
    private fun episodeControls(rows: MutableList<Row>, withSort: Boolean) {
        val curF = podFilters.first { it.first == Settings.podFilter }.second
        rows += Row.Chips(podFilters.map { it.second }, onClick = { label ->
            Settings.podFilter = podFilters.first { it.second == label }.first
            renderAll()
        }, onLong = {}, selected = setOf(curF))
        if (withSort) {
            val curS = podSorts.firstOrNull { it.first == Settings.podSort }?.second ?: podSorts[0].second
            rows += Row.Chips(podSorts.map { it.second }, onClick = { label ->
                Settings.podSort = podSorts.first { it.second == label }.first
                renderAll()
            }, onLong = {}, selected = setOf(curS))
        }
    }

    private fun markAll(eps: List<Episode>, played: Boolean) {
        Store.setPositions(eps.map { Pod.mediaId(it) }, if (played) -1 else 0)
        snack(if (played) "${eps.size} épisodes marqués comme écoutés" else "${eps.size} épisodes marqués comme non écoutés")
        renderAll()
    }

    private fun markAllMenu(all: List<Episode>, shown: List<Episode>) {
        val opts = mutableListOf(
            Opt(R.drawable.ic_play, "Tout marquer comme écouté") { markAll(all, true) },
            Opt(R.drawable.ic_retry, "Tout marquer comme non écouté") { markAll(all, false) }
        )
        if (shown.size != all.size) {
            opts += Opt(R.drawable.ic_play, "Marquer les ${shown.size} affichés comme écoutés") { markAll(shown, true) }
        }
        sheet("Marquer les épisodes", null, opts)
    }

    private fun podcastSubsRows(rows: MutableList<Row>) {
        val addRss = Row.Entry(
            "Ajouter un flux RSS…",
            "Adresse d'un flux, ou lien d'une page (Acast, site du podcast, Apple Podcasts…)",
            null, R.drawable.ic_add, onClick = { askRssUrl() }
        )
        val q = podQuery
        if (q != null) {
            rows += Row.Header("Résultats pour « $q »", "Fermer") { clearPodSearch() }
            val res = podResults2
            when {
                res == null -> rows += Row.Note("Recherche…")
                res.isEmpty() -> rows += Row.Note(
                    "Aucun podcast ni flux trouvé.\nEssaie d'autres mots, ou colle directement l'adresse du flux RSS."
                )
                else -> res.forEach { rows += podRow(it) }
            }
            return
        }
        val pods = Store.podcasts()
        if (pods.isEmpty()) {
            rows += Row.Note(
                "Aucun podcast pour l'instant.\nTouche la loupe en bas pour chercher un podcast, " +
                    "ou ajoute directement l'adresse de son flux RSS.",
                R.drawable.ic_headphones
            )
            rows += addRss
            return
        }
        quickPodcasts()?.let { rows += it }
        deviceRows(rows, "podcast")
        rows += Row.Header("Mes podcasts")
        rows += Row.Avatars(
            pods.map { Chan(it.feedUrl, it.title, it.artwork) },
            onClick = { c -> pods.firstOrNull { it.feedUrl == c.url }?.let { openPodcast(it) } },
            onLong = { c -> pods.firstOrNull { it.feedUrl == c.url }?.let { confirmUnsubscribePodcast(it) } }
        )
        rows += Row.Header("Nouveaux épisodes")
        val f = podFeed
        when {
            f == null -> rows += Row.Note("Chargement…")
            f.isEmpty() -> rows += Row.Note("Aucun épisode trouvé. Tire vers le bas pour actualiser.")
            else -> {
                episodeControls(rows, false)
                val shown = sortFilter(f, sort = false)
                if (shown.isEmpty()) rows += Row.Note("Aucun épisode dans ce filtre.")
                shown.indices.forEach { rows += episodeRow(shown, it, true) }
            }
        }
        rows += addRss
    }

    private fun loadPodFeed(force: Boolean = false) {
        if (podFeedJob?.isActive == true) return
        if (!force && podFeed != null && System.currentTimeMillis() - podFeedTime < 15 * 60_000) return
        val pods = Store.podcasts()
        if (pods.isEmpty()) {
            podFeed = emptyList()
            refresherFor(SUBS)?.isRefreshing = false
            render(SUBS)
            return
        }
        podFeedJob = lifecycleScope.launch {
            val sem = Semaphore(4)
            val all = pods.map { p ->
                async { sem.withPermit { runCatching { Pod.feed(p.feedUrl).second.take(30) }.getOrDefault(emptyList()) } }
            }.awaitAll()
            podFeed = all.flatten().sortedByDescending { it.date }.take(150)
            podFeedTime = System.currentTimeMillis()
            refresherFor(SUBS)?.isRefreshing = false
            render(SUBS)
        }
    }

    private fun openPodcast(p: Podcast) {
        val page = pager.currentItem
        if (channelPage?.page == page) channelPage = null
        val pp = PodPage(page, p)
        podPage = pp
        renderPhys(page)
        lists[page]?.scrollToPosition(0)
        podJob?.cancel()
        podJob = lifecycleScope.launch {
            try {
                val (info, eps) = Pod.feed(p.feedUrl)
                pp.podcast = info.copy(artwork = info.artwork ?: p.artwork, author = info.author ?: p.author)
                pp.episodes = eps
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("Lecteur", "Podcast", e)
                pp.error = e.message ?: "flux illisible"
            }
            if (podPage === pp) renderPhys(pp.page)
        }
    }

    private fun closePodcast() {
        val p = podPage?.page ?: return
        podPage = null
        podJob?.cancel()
        renderPhys(p)
    }

    private fun podcastRows(pp: PodPage, rows: MutableList<Row>) {
        val p = pp.podcast
        rows += Row.ChannelHeader(Chan(p.feedUrl, p.title, p.artwork), p.author, Store.isPodSubscribed(p.feedUrl)) {
            togglePodcast(pp.podcast)
        }
        val eps = pp.episodes
        when {
            pp.error != null -> rows += Row.Note("Impossible de lire ce flux : ${pp.error}\nVérifie que c'est bien l'adresse d'un flux RSS.")
            eps == null -> rows += Row.Note("Chargement…")
            eps.isEmpty() -> rows += Row.Note("Aucun épisode.")
            else -> {
                val unplayed = eps.count { Store.position(Pod.mediaId(it)) >= 0 }
                val shown = sortFilter(eps)
                rows += Row.Header("${eps.size} épisodes · $unplayed non écoutés", "Marquer…") { markAllMenu(eps, shown) }
                episodeControls(rows, true)
                if (shown.isEmpty()) rows += Row.Note("Aucun épisode dans ce filtre.")
                shown.indices.forEach { rows += episodeRow(shown, it, false) }
            }
        }
    }

    private fun togglePodcast(p: Podcast) {
        if (Store.isPodSubscribed(p.feedUrl)) {
            Store.unsubscribePodcast(p.feedUrl)
            snack("Désabonné de ${p.title}")
        } else {
            val best = podPage?.podcast?.takeIf { it.feedUrl == p.feedUrl } ?: p
            Store.subscribePodcast(best)
            snack("Abonné à ${best.title}")
        }
        podFeed = null
        renderAll()
        loadPodFeed()
    }

    private fun confirmUnsubscribePodcast(p: Podcast) {
        MaterialAlertDialogBuilder(this)
            .setTitle(p.title)
            .setMessage("Se désabonner de ce podcast ?")
            .setPositiveButton("Se désabonner") { _, _ -> togglePodcast(p) }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun askRssUrl() {
        val d = resources.displayMetrics.density
        val input = EditText(this).apply {
            hint = "Nom du podcast, ou https://…"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setSingleLine()
        }
        val box = android.widget.FrameLayout(this).apply {
            setPadding((24 * d).toInt(), (8 * d).toInt(), (24 * d).toInt(), 0)
            addView(input)
        }
        val dlg = MaterialAlertDialogBuilder(this)
            .setTitle("Rechercher un podcast")
            .setMessage("Des mots-clés (annuaires Apple Podcasts et fyyd), l'adresse d'un flux RSS, ou le lien d'une page web du podcast.")
            .setView(box)
            .setPositiveButton("Chercher") { _, _ -> runPodSearch(input.text.toString()) }
            .setNegativeButton("Annuler", null)
            .create()
        input.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEARCH) { dlg.dismiss(); runPodSearch(input.text.toString()); true } else false
        }
        dlg.show()
    }

    private fun looksLikeLink(t: String) =
        t.startsWith("http://") || t.startsWith("https://") || (!t.contains(' ') && Regex("^[\\w.-]+\\.[a-z]{2,}(/.*)?$").matches(t))

    private fun runPodSearch(raw: String) {
        val text = raw.trim()
        if (text.isEmpty()) return
        if (looksLikeLink(text)) {
            discoverAndOpen(text)
            return
        }
        podQuery = text
        podResults2 = null
        subsMode = "pod"
        podPage?.takeIf { it.page == 0 }?.let { podPage = null }
        show(SUBS)
        render(SUBS)
        podSearchJob?.cancel()
        podSearchJob = lifecycleScope.launch {
            busy(true)
            podResults2 = try {
                Pod.search(text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                snack("Recherche impossible : ${e.message}")
                emptyList()
            } finally {
                busy(false)
            }
            render(SUBS)
        }
    }

    private fun clearPodSearch() {
        podQuery = null
        podResults2 = null
        podSearchJob?.cancel()
        render(SUBS)
    }

    /** Ouvre le flux RSS trouvé derrière un lien (flux direct, page web, Apple Podcasts). */
    private fun discoverAndOpen(raw: String) {
        val url = if (raw.startsWith("http")) raw else "https://$raw"
        lifecycleScope.launch {
            busy(true)
            try {
                val feeds = Pod.discover(url)
                when {
                    feeds.isEmpty() -> snack("Aucun flux RSS trouvé à cette adresse")
                    feeds.size == 1 -> openPodcast(Podcast(feeds[0], "Podcast", null, null))
                    else -> {
                        podQuery = url
                        podResults2 = feeds.map { Podcast(it, it.substringAfter("://"), "Flux trouvé sur la page", null) }
                        subsMode = "pod"
                        show(SUBS)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                snack("Impossible d'ouvrir ce lien : ${e.message}")
            } finally {
                busy(false)
            }
        }
    }

    private fun playEpisodes(list: List<Episode>, index: Int) {
        list.getOrNull(index)?.let { Store.addRecentEpisode(it) }
        val items = list.map { e ->
            MediaItem.Builder()
                .setUri(e.audioUrl)
                .setMediaId(Pod.mediaId(e))
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(e.title)
                        .setArtist(e.podcastTitle)
                        .setArtworkUri((e.image ?: e.podcastArt)?.let(Uri::parse))
                        .build()
                )
                .build()
        }
        val start = Store.position(Pod.mediaId(list[index])).coerceAtLeast(0)
        loadJob?.cancel()
        Playback.startedLocal()
        list.forEach { e ->
            if (e.description != null || e.chapters.isNotEmpty())
                Playback.setInfo(Pod.mediaId(e), Playback.Info(e.description, true, e.chapters))
        }
        PlayerHolder.videoMode = false
        PlayerHolder.setVideoEnabled(player, false)
        player.setMediaItems(items, index, start)
        player.prepare()
        player.play()
        refreshUi()
    }

    private fun downloadEpisode(e: Episode) {
        if (Build.VERSION.SDK_INT < 29 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            storagePerm.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        DownloadService.startDirect(this, e.audioUrl, e.title, e.podcastTitle, e.image ?: e.podcastArt)
        Snackbar.make(root, "Téléchargement ajouté", Snackbar.LENGTH_LONG)
            .setAnchorView(if (bottomNav.isVisible) bottomNav else null)
            .setAction("Voir") { show(OFFLINE) }
            .show()
    }

    // ——— Sélection multiple ———

    private fun keyOf(o: Any): String = when (o) {
        is Episode -> Pod.mediaId(o)
        is OffItem -> o.uri.toString()
        else -> o.toString()
    }

    private fun selStart(episodes: Boolean, list: List<Any>, i: Int) {
        val s0 = Sel(episodes, list)
        sel = s0
        val o = list[i]
        s0.keys += keyOf(o)
        s0.items[keyOf(o)] = o
        s0.last = i
        vibrate()
        updateSelBar()
        renderAll()
    }

    private fun selToggle(key: String, o: Any, list: List<Any>, i: Int) {
        val s0 = sel ?: return
        if (!s0.keys.remove(key)) {
            s0.keys += key
            s0.items[key] = o
            s0.last = list.indexOfFirst { keyOf(it) == key }.takeIf { it >= 0 && list === s0.universe } ?: i
        } else s0.items.remove(key)
        if (s0.keys.isEmpty()) selEnd() else { updateSelBar(); renderAll() }
    }

    /** Appui long pendant la sélection : coche tout entre le dernier coché et celui-ci. */
    private fun selRange(list: List<Any>, i: Int) {
        val s0 = sel ?: return
        val from = if (s0.last in list.indices) s0.last else i
        for (j in minOf(from, i)..maxOf(from, i)) {
            val o = list[j]
            s0.keys += keyOf(o)
            s0.items[keyOf(o)] = o
        }
        s0.last = i
        vibrate()
        updateSelBar()
        renderAll()
    }

    private fun selEnd() {
        sel = null
        updateSelBar()
        renderAll()
    }

    private fun vibrate() {
        try { root.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS) } catch (_: Exception) {}
    }

    @Suppress("UNCHECKED_CAST")
    private fun selEpisodes(): List<Episode> = sel?.items?.values?.filterIsInstance<Episode>() ?: emptyList()
    private fun selFiles(): List<OffItem> = sel?.items?.values?.filterIsInstance<OffItem>() ?: emptyList()

    private fun updateSelBar() {
        val bar = findViewById<View>(R.id.selBar)
        val s0 = sel
        bar.isVisible = s0 != null
        findViewById<View>(R.id.topBar).isVisible = s0 == null && !fullscreen
        if (s0 == null) return
        val n = s0.keys.size
        findViewById<TextView>(R.id.selCount).text = "$n sélectionné" + if (n > 1) "s" else ""
        val a = findViewById<ImageButton>(R.id.selA)
        val b = findViewById<ImageButton>(R.id.selB)
        val c = findViewById<ImageButton>(R.id.selC)
        findViewById<View>(R.id.selClose).setOnClickListener { selEnd() }
        b.setOnClickListener { selQueue() }
        findViewById<View>(R.id.selMore).setOnClickListener { selMenu() }
        if (s0.episodes) {
            a.setImageResource(R.drawable.ic_check)
            a.contentDescription = "Marquer comme écouté"
            a.setOnClickListener { selMark(true) }
            c.setImageResource(R.drawable.ic_download)
            c.contentDescription = "Télécharger"
            c.setOnClickListener { selDownload() }
        } else {
            a.setImageResource(R.drawable.ic_play)
            a.contentDescription = "Lire la sélection"
            a.setOnClickListener { selPlayFiles() }
            c.setImageResource(R.drawable.ic_delete)
            c.contentDescription = "Supprimer"
            c.setOnClickListener { selDelete() }
        }
        listOf(a, b, c).forEach { btn ->
            btn.setOnLongClickListener { snack(btn.contentDescription.toString()); true }
        }
    }

    private fun selMark(played: Boolean) {
        val eps = selEpisodes()
        Store.setPositions(eps.map { Pod.mediaId(it) }, if (played) -1 else 0)
        snack("${eps.size} marqué" + (if (eps.size > 1) "s" else "") + if (played) " comme écouté" else " comme non écouté")
        selEnd()
    }

    private fun selQueue() {
        val s0 = sel ?: return
        if (s0.episodes) selEpisodes().forEach { e ->
            Playback.addLast(Playback.QItem(e.title, e.podcastTitle, e.image ?: e.podcastArt, uri = e.audioUrl, mediaId = Pod.mediaId(e)))
        } else selFiles().forEach { d ->
            Playback.addLast(Playback.QItem(d.title, d.uploader, d.thumb, uri = d.uri.toString()))
        }
        snack("${s0.keys.size} ajouté(s) à la file (${Playback.queue.size})")
        selEnd()
    }

    private fun selDownload() {
        val eps = selEpisodes()
        eps.forEach { downloadEpisode(it) }
        if (eps.size > 1) snack("${eps.size} téléchargements ajoutés")
        selEnd()
    }

    private fun selPlayFiles() {
        val files = selFiles()
        if (files.isEmpty()) return
        selEnd()
        playDownloads(files, 0, files.none { it.video })
    }

    private fun selDelete() {
        val files = selFiles()
        MaterialAlertDialogBuilder(this)
            .setTitle("Supprimer ${files.size} fichier" + (if (files.size > 1) "s" else "") + " ?")
            .setMessage("Ils seront supprimés du téléphone.")
            .setPositiveButton("Supprimer") { _, _ ->
                files.forEach { deleteFile(it) }
                selEnd()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun selMenu() {
        val s0 = sel ?: return
        val opts = mutableListOf<Opt>()
        opts += Opt(R.drawable.ic_check, "Tout sélectionner (${s0.universe.size})") {
            s0.universe.forEach { o -> s0.keys += keyOf(o); s0.items[keyOf(o)] = o }
            updateSelBar(); renderAll()
        }
        if (s0.episodes) {
            opts += Opt(R.drawable.ic_check, "Marquer comme écouté") { selMark(true) }
            opts += Opt(R.drawable.ic_retry, "Marquer comme non écouté") { selMark(false) }
            opts += Opt(R.drawable.ic_download, "Télécharger") { selDownload() }
            opts += Opt(R.drawable.ic_queue, "Ajouter à la file d'attente") { selQueue() }
            val eps = selEpisodes()
            if (eps.size == 1) {
                val list = s0.universe.filterIsInstance<Episode>()
                val idx = list.indexOfFirst { Pod.mediaId(it) == Pod.mediaId(eps[0]) }
                if (idx >= 0) opts += Opt(R.drawable.ic_more_vert, "Autres options de l'épisode…") { selEnd(); episodeMenu(list, idx) }
            }
        } else {
            opts += Opt(R.drawable.ic_play, "Lire la sélection") { selPlayFiles() }
            opts += Opt(R.drawable.ic_queue, "Ajouter à la file d'attente") { selQueue() }
            opts += Opt(R.drawable.ic_delete, "Supprimer du téléphone") { selDelete() }
            val files = selFiles()
            if (files.size == 1) {
                val list = s0.universe.filterIsInstance<OffItem>()
                val idx = list.indexOfFirst { it.uri == files[0].uri }
                if (idx >= 0) opts += Opt(R.drawable.ic_more_vert, "Autres options du fichier…") { selEnd(); downloadMenu(list, idx) }
            }
        }
        sheet("${s0.keys.size} sélectionné(s)", "Astuce : appui long sur un autre élément pour cocher tout ce qu'il y a entre les deux", opts)
    }

    private fun episodeMenu(list: List<Episode>, i: Int) {
        val e = list[i]
        val id = Pod.mediaId(e)
        val done = Store.position(id) < 0
        sheet(
            e.title, e.podcastTitle, listOf(
                Opt(R.drawable.ic_headphones, "Écouter") { playEpisodes(list, i) },
                Opt(R.drawable.ic_retry, "Écouter depuis le début") {
                    Store.setPosition(id, 0)
                    playEpisodes(list, i)
                },
                Opt(R.drawable.ic_queue, "Ajouter à la file d'attente") {
                    Playback.addLast(Playback.QItem(e.title, e.podcastTitle, e.image ?: e.podcastArt, uri = e.audioUrl, mediaId = id))
                    snack("Ajouté à la file (${Playback.queue.size})")
                },
                Opt(R.drawable.ic_download, "Télécharger l'épisode") { downloadEpisode(e) },
                Opt(R.drawable.ic_play, if (done) "Marquer comme non écouté" else "Marquer comme écouté") {
                    Store.setPosition(id, if (done) 0 else -1)
                    renderAll()
                },
                Opt(R.drawable.ic_play, "Marquer celui-ci et les plus anciens comme écoutés") {
                    val all = podPage?.episodes?.takeIf { l -> l.any { it.audioUrl == e.audioUrl } } ?: list
                    markAll(all.filter { it.feedUrl == e.feedUrl && it.date <= e.date }, true)
                },
                Opt(R.drawable.ic_share, "Partager le lien") {
                    startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, e.audioUrl),
                            e.title
                        )
                    )
                }
            )
        )
    }

    private fun openChannel(c: Chan) {
        val page = pager.currentItem
        if (podPage?.page == page) podPage = null
        val cp = ChannelPage(page, c)
        channelPage = cp
        renderPhys(page)
        lists[page]?.scrollToPosition(0)
        channelJob?.cancel()
        channelJob = lifecycleScope.launch {
            try {
                val d = Yt.channel(c.url)
                cp.chan = d.chan
                cp.subs = d.subs
                cp.videos = d.videos
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("Lecteur", "Chaîne", e)
                cp.error = e.message ?: "erreur"
            }
            if (channelPage === cp) renderPhys(cp.page)
        }
    }

    private fun closeChannel() {
        val p = channelPage?.page ?: return
        channelPage = null
        channelJob?.cancel()
        renderPhys(p)
    }

    private fun channelRows(cp: ChannelPage, rows: MutableList<Row>) {
        rows += Row.ChannelHeader(cp.chan, cp.subs, Store.isSubscribed(cp.chan.url)) { toggleSub(cp.chan) }
        val vids = cp.videos
        when {
            cp.error != null -> rows += Row.Note("Impossible de charger la chaîne : ${cp.error}")
            vids == null -> rows += Row.Note("Chargement…")
            vids.isEmpty() -> rows += Row.Note("Aucune vidéo.")
            else -> {
                rows += Row.Header("Vidéos")
                vids.forEach { rows += videoRow(it) }
            }
        }
    }

    private fun toggleSub(c: Chan) {
        if (Store.isSubscribed(c.url)) {
            Store.unsubscribe(c.url)
            snack("Désabonné de ${c.name}")
        } else {
            val best = channelPage?.chan?.takeIf { it.url == c.url && it.avatar != null } ?: c
            Store.subscribe(best)
            snack("Abonné à ${c.name}")
        }
        subsFeed = null
        renderAll()
        loadSubsFeed()
    }

    private fun confirmUnsubscribe(c: Chan) {
        MaterialAlertDialogBuilder(this)
            .setTitle(c.name)
            .setMessage("Se désabonner de cette chaîne ?")
            .setPositiveButton("Se désabonner") { _, _ -> toggleSub(c) }
            .setNegativeButton("Annuler", null)
            .show()
    }

    // ——— Menus (appui long) ———

    private fun sheet(title: String?, subtitle: String?, opts: List<Opt>) {
        val dlg = BottomSheetDialog(this)
        val d = resources.displayMetrics.density
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (4 * d).toInt(), 0, (20 * d).toInt())
        }
        if (title != null) {
            box.addView(TextView(this).apply {
                text = title
                maxLines = 2
                TextViewCompat.setTextAppearance(this, attrRes(com.google.android.material.R.attr.textAppearanceTitleMedium))
                setPadding((24 * d).toInt(), (8 * d).toInt(), (24 * d).toInt(), (2 * d).toInt())
            })
        }
        if (subtitle != null) {
            box.addView(TextView(this).apply {
                text = subtitle
                TextViewCompat.setTextAppearance(this, attrRes(com.google.android.material.R.attr.textAppearanceBodySmall))
                setPadding((24 * d).toInt(), 0, (24 * d).toInt(), (8 * d).toInt())
            })
        }
        opts.forEach { o ->
            val row = layoutInflater.inflate(R.layout.item_sheet_option, box, false)
            row.findViewById<ImageView>(R.id.icon).setImageResource(o.icon)
            row.findViewById<TextView>(R.id.label).text = o.label
            row.setOnClickListener { dlg.dismiss(); o.run() }
            box.addView(row)
        }
        Ui.applyFont(box)
        dlg.setContentView(box)
        dlg.show()
    }

    private fun attrRes(attr: Int): Int {
        val tv = TypedValue()
        theme.resolveAttribute(attr, tv, true)
        return tv.resourceId
    }

    private fun videoMenu(v: Vid) {
        val opts = mutableListOf(
            Opt(R.drawable.ic_headphones, "Écouter en arrière-plan") { play(v.url, audioOnly = true) },
            Opt(R.drawable.ic_play, "Lire la vidéo") { play(v.url) },
            Opt(R.drawable.ic_retry, "Lire depuis le début") {
                Store.setPosition(v.url, 0)
                play(v.url)
            },
            Opt(R.drawable.ic_playnext, "Lire ensuite") {
                Playback.addNext(Playback.QItem(v.title, v.uploader, v.thumb, ytUrl = v.url))
                snack("Sera lu ensuite")
            },
            Opt(R.drawable.ic_queue, "Ajouter à la file d'attente") {
                Playback.addLast(Playback.QItem(v.title, v.uploader, v.thumb, ytUrl = v.url))
                snack("Ajouté à la file (${Playback.queue.size})")
            },
            Opt(R.drawable.ic_audio, "Télécharger l'audio") { startDownload(v.url, false, v.title) },
            Opt(R.drawable.ic_download, "Télécharger la vidéo") { startDownload(v.url, true, v.title) }
        )
        v.uploaderUrl?.let { u ->
            opts += Opt(R.drawable.ic_person, "Voir la chaîne") { openChannel(Chan(u, v.uploader ?: "Chaîne", null)) }
        }
        opts += Opt(R.drawable.ic_share, "Partager le lien") {
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, v.url),
                    v.title
                )
            )
        }
        sheet(v.title, v.uploader, opts)
    }

    // ——— Fichiers locaux ———

    private fun mediaItem(uri: Uri, title: String, artist: String?, thumb: String?) =
        MediaItem.Builder()
            .setUri(uri)
            .setMediaId(uri.toString())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setArtworkUri(thumb?.let(Uri::parse))
                    .build()
            )
            .build()

    private fun startLocal(items: List<MediaItem>, index: Int, audio: Boolean) {
        loadJob?.cancel()
        Playback.startedLocal()
        PlayerHolder.videoMode = !audio
        PlayerHolder.setVideoEnabled(player, !audio)
        player.setMediaItems(items, index, Store.position(items[index].mediaId).coerceAtLeast(0))
        player.prepare()
        player.play()
        refreshUi()
    }

    private fun playSingleLocal(uri: Uri) {
        val doc = DocumentFile.fromSingleUri(this, uri)
        val name = doc?.name ?: "Fichier"
        startLocal(listOf(mediaItem(uri, name, null, null)), 0, isAudio(doc?.type, name))
    }

    private fun playDownloads(list: List<OffItem>, index: Int, audioOnly: Boolean = false) {
        val items = list.map { mediaItem(it.uri, it.title, it.uploader, it.thumb?.takeIf { t -> t.startsWith("http") }) }
        startLocal(items, index, audioOnly || !list[index].video)
    }

    private fun playFolder(media: List<FileEntry>, index: Int) {
        val items = media.map { mediaItem(it.doc.uri, it.name, null, null) }
        startLocal(items, index, isAudio(media[index].type, media[index].name))
    }

    private fun ext(name: String) = name.substringAfterLast('.', "").lowercase()

    private fun isAudio(type: String?, name: String) =
        type?.startsWith("audio/") == true || ext(name) in AUDIO_EXT

    private fun isMedia(type: String?, name: String) =
        isAudio(type, name) || type?.startsWith("video/") == true || ext(name) in VIDEO_EXT

    private fun enterFolder(doc: DocumentFile, name: String) {
        libFilter = "folders"
        folderStack += doc to name
        loadFolder()
    }

    private fun leaveFolder() {
        if (folderStack.isNotEmpty()) folderStack.removeAt(folderStack.size - 1)
        if (folderStack.isEmpty()) {
            folderFiles = null
            render(OFFLINE)
        } else loadFolder()
    }

    private fun loadFolder() {
        val doc = folderStack.lastOrNull()?.first ?: return
        folderFiles = null
        render(OFFLINE)
        folderJob?.cancel()
        folderJob = lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) {
                doc.listFiles().mapNotNull { d ->
                    val n = d.name ?: return@mapNotNull null
                    val dir = d.isDirectory
                    val t = d.type
                    if (!dir && !isMedia(t, n)) null else FileEntry(d, n, dir, t)
                }.sortedWith(compareBy<FileEntry>({ !it.dir }, { it.name.lowercase() }))
            }
            folderFiles = list
            render(OFFLINE)
        }
    }

    private fun folderRows(rows: MutableList<Row>) {
        if (folderStack.isEmpty()) {
            rows += Row.Header("Sur ce téléphone")
            rows += Row.Entry(
                "Ouvrir un fichier…", "Audio ou vidéo", null, R.drawable.ic_file,
                onClick = { openFile.launch(arrayOf("audio/*", "video/*")) }
            )
            rows += Row.Entry(
                "Ajouter un dossier…", "Il restera dans la liste", null, R.drawable.ic_add,
                onClick = { openTree.launch(null) }
            )
            val folders = Store.folders()
            if (folders.isNotEmpty()) rows += Row.Header("Mes dossiers")
            folders.forEach { s ->
                val uri = Uri.parse(s)
                val doc = DocumentFile.fromTreeUri(this, uri)
                val name = doc?.name ?: uri.lastPathSegment ?: s
                rows += Row.Entry(
                    name, null, null, R.drawable.ic_folder,
                    onClick = { if (doc != null) enterFolder(doc, name) },
                    onLong = { confirmRemoveFolder(s) }
                )
            }
            return
        }
        rows += Row.Header(folderStack.last().second)
        val files = folderFiles
        if (files == null) {
            rows += Row.Note("Chargement…")
            return
        }
        if (files.isEmpty()) rows += Row.Note("Aucun fichier audio ou vidéo ici.", R.drawable.ic_folder)
        val media = files.filter { !it.dir }
        files.forEach { f ->
            rows += if (f.dir) {
                Row.Entry(f.name, "Dossier", null, R.drawable.ic_folder, onClick = { enterFolder(f.doc, f.name) })
            } else {
                val audio = isAudio(f.type, f.name)
                Row.Entry(
                    f.name, if (audio) "Audio" else "Vidéo", null,
                    if (audio) R.drawable.ic_audio else R.drawable.ic_video,
                    onClick = { playFolder(media, media.indexOf(f)) },
                    tile = true
                )
            }
        }
    }

    private fun confirmRemoveFolder(uri: String) {
        MaterialAlertDialogBuilder(this)
            .setMessage("Retirer ce dossier de la liste ? Les fichiers ne sont pas supprimés.")
            .setPositiveButton("Retirer") { _, _ ->
                try {
                    contentResolver.releasePersistableUriPermission(
                        Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {
                }
                Store.removeFolder(uri)
                render(OFFLINE)
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    // ——— Téléchargements ———

    private fun askDownload(url: String, title: String?) {
        sheet(
            "Télécharger", title, listOf(
                Opt(R.drawable.ic_audio, "Audio seul (écoute hors ligne)") { startDownload(url, false, title) },
                Opt(R.drawable.ic_video, "Vidéo (720p max)") { startDownload(url, true, title) }
            )
        )
    }

    private fun startDownload(url: String, video: Boolean, title: String?) {
        if (Build.VERSION.SDK_INT < 29 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingDownload = Triple(url, video, title)
            storagePerm.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        DownloadService.start(this, url, video, title)
        Snackbar.make(root, "Téléchargement ajouté", Snackbar.LENGTH_LONG)
            .setAnchorView(if (bottomNav.isVisible) bottomNav else null)
            .setAction("Voir") { show(OFFLINE) }
            .show()
    }

    private fun hasMediaPerm(): Boolean {
        fun ok(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
        return if (Build.VERSION.SDK_INT >= 33) ok(Manifest.permission.READ_MEDIA_AUDIO) && ok(Manifest.permission.READ_MEDIA_VIDEO)
        else ok(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun askMediaPerm() {
        mediaPerm.launch(
            if (Build.VERSION.SDK_INT >= 33)
                arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_MEDIA_VIDEO)
            else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        )
    }

    private fun rescanLocal() {
        scanJob?.cancel()
        scanJob = lifecycleScope.launch {
            localFiles = withContext(Dispatchers.IO) { LocalMedia.scan(applicationContext) }
            renderAll()
        }
    }

    /** Contenu réel des dossiers, complété par ce que l'appli sait des téléchargements. */
    private fun offlineItems(): List<OffItem> {
        val store = Store.downloads()
        fun idOf(u: String) = Uri.parse(u).takeIf { it.scheme == "content" }?.lastPathSegment?.toLongOrNull()
        val out = (localFiles ?: emptyList()).map { f ->
            val d = store.firstOrNull { it.video == f.video && idOf(it.uri) == f.id }
            OffItem(
                f.uri, d?.title ?: f.name.substringBeforeLast('.'), d?.uploader,
                if (f.video) f.uri.toString() else (d?.thumb ?: f.uri.toString()),
                f.video, f.path, d?.uri,
                podcast = d != null && !d.video && !d.source.contains("youtu"),
                added = f.added * 1000
            )
        }.toMutableList()
        // Anciennes versions d'Android : fichiers enregistrés directement (file://)
        store.filter { Uri.parse(it.uri).scheme == "file" }.forEach { d ->
            val path = Uri.parse(d.uri).path
            if (path != null && java.io.File(path).exists() && out.none { it.path == d.path }) {
                out += OffItem(
                    Uri.parse(d.uri), d.title, d.uploader, d.thumb, d.video,
                    d.path ?: if (d.video) "Movies/Lecteur" else "Music/Lecteur", d.uri,
                    podcast = !d.video && !d.source.contains("youtu")
                )
            }
        }
        return out
    }

    private fun offlineRows(rows: MutableList<Row>) {
        val queue = Downloads.all()
        if (queue.isNotEmpty()) {
            rows += Row.Header("En cours")
            queue.forEach { d ->
                val kind = if (d.video) "Vidéo" else "Audio"
                val icon = if (d.video) R.drawable.ic_video else R.drawable.ic_audio
                rows += when (d.state) {
                    Downloads.State.WAITING, Downloads.State.RUNNING -> Row.Progress(
                        d.title,
                        "$kind · " + d.stage + if (d.pct >= 0) " · ${d.pct} %" else "",
                        if (d.state == Downloads.State.WAITING) 0 else d.pct,
                        icon, R.drawable.ic_close,
                        onAction = { Downloads.cancel(d) }
                    )
                    else -> Row.Progress(
                        d.title, "$kind · ${d.stage}", null, icon, R.drawable.ic_retry,
                        onAction = {
                            Downloads.remove(d)
                            startDownload(d.url, d.video, d.title)
                        },
                        onClick = {
                            MaterialAlertDialogBuilder(this)
                                .setTitle(d.title)
                                .setMessage(d.stage)
                                .setPositiveButton("Relancer") { _, _ ->
                                    Downloads.remove(d)
                                    startDownload(d.url, d.video, d.title)
                                }
                                .setNegativeButton("Retirer") { _, _ -> Downloads.remove(d) }
                                .show()
                        }
                    )
                }
            }
        }

        if (libFilter == "folders") {
            folderRows(rows)
            return
        }

        val all = offlineItems()
        val videos = all.filter { it.video }
        val audios = all.filter { !it.video && !it.podcast }
        val pods = all.filter { it.podcast }

        if (!hasMediaPerm()) {
            rows += Row.Entry(
                "Retrouver tous les fichiers",
                "Autorise l'accès pour afficher aussi ceux d'avant une réinstallation",
                null, R.drawable.ic_download, onClick = { askMediaPerm() }
            )
        }

        fun entries(list: List<OffItem>, max: Int = Int.MAX_VALUE, showKind: Boolean = false) {
            list.take(max).forEachIndexed { i, d ->
                val kind = when { d.video -> "Vidéo"; d.podcast -> "Podcast"; else -> "Audio" }
                rows += Row.Entry(
                    d.title,
                    listOfNotNull(if (showKind) kind else null, d.uploader, resumeText(d.uri.toString()))
                        .joinToString(" · ").ifEmpty { d.path },
                    d.thumb,
                    if (d.video) R.drawable.ic_video else R.drawable.ic_audio,
                    onClick = { if (sel?.episodes == false) selToggle(d.uri.toString(), d, list, i) else playDownloads(list, i) },
                    onLong = { if (sel?.episodes == false) selRange(list, i) else selStart(false, list, i) },
                    tile = true,
                    selected = sel?.takeIf { !it.episodes }?.keys?.contains(d.uri.toString()) == true
                )
            }
        }

        fun section(title: String, list: List<OffItem>, key: String) {
            if (list.isEmpty()) return
            rows += Row.Header("$title · ${list.size}", if (list.size > 4) "Tout voir" else null) {
                libFilter = key
                render(OFFLINE)
                listFor(OFFLINE)?.scrollToPosition(0)
            }
            entries(list, 4)
        }

        when (libFilter) {
            "video" -> {
                rows += Row.Header("Vidéos · ${videos.size}")
                if (videos.isEmpty()) rows += Row.Note("Aucune vidéo téléchargée.", R.drawable.ic_video)
                entries(videos)
            }
            "audio" -> {
                rows += Row.Header("Audio · ${audios.size}")
                if (audios.isEmpty()) rows += Row.Note("Aucun audio téléchargé.", R.drawable.ic_audio)
                entries(audios)
            }
            "podcast" -> {
                if (pods.isEmpty()) rows += Row.Note("Aucun épisode téléchargé.", R.drawable.ic_headphones)
                // Regroupés par émission
                pods.groupBy { it.uploader ?: "Podcast" }.forEach { (show, eps) ->
                    rows += Row.Header("$show · ${eps.size}")
                    entries(eps)
                }
            }
            else -> {
                if (all.isEmpty()) {
                    rows += Row.Note(
                        "Rien de téléchargé pour l'instant.\nFais un appui long sur une vidéo ou un épisode, " +
                            "ou touche « Télécharger » sous la vidéo en cours.",
                        R.drawable.ic_download
                    )
                }
                val resume = all.filter { Store.position(it.uri.toString()) > 0 }
                if (resume.isNotEmpty()) {
                    rows += Row.Header("À reprendre")
                    entries(resume, 5, showKind = true)
                }
                val weekAgo = System.currentTimeMillis() - 7 * 86_400_000L
                val recent = all.filter { it.added > weekAgo }
                if (recent.isNotEmpty() && recent.size < all.size) {
                    rows += Row.Header("Ajoutés récemment")
                    entries(recent, 5, showKind = true)
                }
                section("Vidéos", videos, "video")
                section("Audio", audios, "audio")
                section("Podcasts", pods, "podcast")

                rows += Row.Note("Téléchargements : mémoire interne › Music/Lecteur (audio) et Movies/Lecteur (vidéo)")
            }
        }
    }

    private fun downloadMenu(list: List<OffItem>, i: Int) {
        val d = list[i]
        val opts = mutableListOf(
            Opt(R.drawable.ic_play, "Lire") { playDownloads(list, i) }
        )
        if (d.video) opts += Opt(R.drawable.ic_headphones, "Écouter en arrière-plan") { playDownloads(list, i, true) }
        opts += Opt(R.drawable.ic_queue, "Ajouter à la file d'attente") {
            Playback.addLast(Playback.QItem(d.title, d.uploader, d.thumb, uri = d.uri.toString()))
            snack("Ajouté à la file (${Playback.queue.size})")
        }
        opts += Opt(R.drawable.ic_share, "Partager le fichier") { share(d) }
        opts += Opt(R.drawable.ic_delete, "Supprimer du téléphone") { confirmDelete(d) }
        sheet(d.title, "Mémoire interne › ${d.path}", opts)
    }

    private fun share(d: OffItem) {
        if (d.uri.scheme != "content") {
            snack("Partage possible depuis l'appli Fichiers")
            return
        }
        val send = Intent(Intent.ACTION_SEND)
            .setType(if (d.video) "video/mp4" else "audio/*")
            .putExtra(Intent.EXTRA_STREAM, d.uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, d.title))
    }

    private fun confirmDelete(d: OffItem) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Supprimer ?")
            .setMessage("« ${d.title} » sera supprimé du téléphone.")
            .setPositiveButton("Supprimer") { _, _ -> deleteFile(d) }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun deleteFile(d: OffItem) {
        try {
            if (d.uri.scheme == "file") d.uri.path?.let { java.io.File(it).delete() }
            else contentResolver.delete(d.uri, null, null)
            d.storeUri?.let { Store.removeDownload(it) }
            rescanLocal()
        } catch (e: SecurityException) {
            // Fichier créé par une ancienne installation : Android demande la confirmation
            val sender = when {
                Build.VERSION.SDK_INT >= 30 ->
                    MediaStore.createDeleteRequest(contentResolver, listOf(d.uri)).intentSender
                Build.VERSION.SDK_INT == 29 && e is RecoverableSecurityException ->
                    e.userAction.actionIntent.intentSender
                else -> null
            }
            if (sender != null) {
                pendingDelete = d
                deleteLauncher.launch(IntentSenderRequest.Builder(sender).build())
            } else snack("Suppression impossible : utilise l'appli Fichiers")
        }
    }

    // ——— Musique ———

    private fun musicSearch(q: String) {
        hideKeyboard()
        musicQuery = q
        musicResult = null
        musicStack.clear()
        render(MUSIC)
        musicJob?.cancel()
        musicJob = lifecycleScope.launch {
            busy(true)
            try {
                musicResult = Music.search(q)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                snack("Recherche impossible : ${e.message}")
                musicResult = Music.SearchResult(emptyList(), emptyList(), emptyList(), emptyList())
            } finally {
                busy(false)
            }
            render(MUSIC)
            listFor(MUSIC)?.scrollToPosition(0)
        }
    }

    private fun pushMusic(p: MPage) {
        musicStack += p
        render(MUSIC)
        listFor(MUSIC)?.scrollToPosition(0)
        when (p) {
            is MPage.AlbumP -> lifecycleScope.launch {
                try {
                    val (a, t) = Music.albumTracks(p.album)
                    p.album = a
                    p.tracks = t
                    // Met à jour la pochette des albums mémorisés qui n'en avaient pas
                    if (a.thumb != null) {
                        Store.recentAlbums().firstOrNull { it.url == a.url && it.thumb == null }?.let { Store.addRecentAlbum(a) }
                        if (Store.savedAlbums().any { it.url == a.url && it.thumb == null }) { Store.toggleAlbum(a); Store.toggleAlbum(a) }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    p.error = e.message ?: "erreur"
                }
                if (musicStack.lastOrNull() === p) render(MUSIC)
            }
            is MPage.ArtistP -> lifecycleScope.launch {
                try {
                    val (a, songs, albums) = Music.artist(p.artist)
                    p.artist = a
                    p.songs = songs
                    p.albums = albums
                    Store.addRecentArtist(a)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    p.error = e.message ?: "erreur"
                }
                if (musicStack.lastOrNull() === p) render(MUSIC)
            }
            else -> {}
        }
    }

    private fun popMusic() {
        if (musicStack.isNotEmpty()) musicStack.removeAt(musicStack.size - 1)
        render(MUSIC)
    }

    private fun loadLocalMusic() {
        if (localMusicJob?.isActive == true) return
        localMusicJob = lifecycleScope.launch {
            localMusic = withContext(Dispatchers.IO) { Music.localTracks(applicationContext) }
            render(MUSIC)
        }
    }

    private fun trackRow(list: List<Track>, i: Int, number: Boolean = false, playlistId: String? = null, onPlayed: (() -> Unit)? = null): Row {
        val t = list[i]
        val sub = listOfNotNull(t.artist, if (number) null else t.album, Yt.formatDuration(t.duration).ifEmpty { null })
            .joinToString(" · ")
        return Row.Track(
            t.title, sub, t.thumb, if (number) "${i + 1}" else null,
            playing = Music.current?.url == t.url, liked = Store.isLiked(t.url),
            onClick = { onPlayed?.invoke(); Music.play(list, i); openNowPlayingSoon() },
            onLong = { trackMenu(t, playlistId, i) }
        )
    }

    private fun openNowPlayingSoon() {
        startActivity(Intent(this, NowPlayingActivity::class.java))
    }

    private fun albumRow(a: Music.Album, kind: String) = Row.Track(
        a.title, listOfNotNull(kind, a.artist).joinToString(" · "), a.thumb,
        liked = Store.isAlbumSaved(a.url),
        onClick = { pushMusic(MPage.AlbumP(a)) }, onLong = { albumMenu(a) }
    )

    private fun artistRow(a: Music.Artist) = Row.Track(
        a.name, "Artiste", a.thumb, round = true, liked = Store.isArtistSaved(a.url),
        onClick = { pushMusic(MPage.ArtistP(a)) }, onLong = { artistMenu(a) }
    )

    /** Lance un album directement (sans ouvrir sa page). */
    private fun playAlbum(a: Music.Album, shuffled: Boolean) {
        lifecycleScope.launch {
            busy(true)
            try {
                val (album, tracks) = Music.albumTracks(a)
                if (tracks.isEmpty()) { snack("Album vide"); return@launch }
                Store.addRecentAlbum(album)
                Music.play(tracks, if (shuffled) tracks.indices.random() else 0, shuffled)
                openNowPlayingSoon()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                snack("Impossible de lire l'album : ${e.message}")
            } finally {
                busy(false)
            }
        }
    }

    private fun albumMenu(a: Music.Album, recent: Boolean = false) {
        val saved = Store.isAlbumSaved(a.url)
        val opts = mutableListOf(
            Opt(R.drawable.ic_play, "Lire l'album") { playAlbum(a, false) },
            Opt(R.drawable.ic_shuffle, "Lecture aléatoire") { playAlbum(a, true) },
            Opt(if (saved) R.drawable.ic_heart else R.drawable.ic_heart_outline,
                if (saved) "Retirer de mes albums" else "Ajouter à mes albums") {
                snack(if (Store.toggleAlbum(a)) "Ajouté à tes albums" else "Retiré de tes albums")
                render(MUSIC)
            }
        )
        if (recent) opts += Opt(R.drawable.ic_close, "Retirer des écoutés récemment") { Store.removeRecentAlbum(a.url); render(MUSIC) }
        sheet(a.title, a.artist, opts)
    }

    private fun artistMenu(a: Music.Artist, recent: Boolean = false) {
        val saved = Store.isArtistSaved(a.url)
        val opts = mutableListOf(
            Opt(R.drawable.ic_person, "Voir l'artiste") { pushMusic(MPage.ArtistP(a)) },
            Opt(R.drawable.ic_shuffle, "Lecture aléatoire de ses titres") {
                lifecycleScope.launch {
                    busy(true)
                    try {
                        val songs = Music.songs(a.name).filter { it.artist?.contains(a.name, true) == true }.ifEmpty { Music.songs(a.name) }
                        if (songs.isEmpty()) snack("Aucun titre trouvé") else {
                            Store.addRecentArtist(a)
                            Music.play(songs, songs.indices.random(), true)
                            openNowPlayingSoon()
                        }
                    } finally {
                        busy(false)
                    }
                }
            },
            Opt(if (saved) R.drawable.ic_heart else R.drawable.ic_heart_outline,
                if (saved) "Ne plus suivre" else "Suivre") {
                snack(if (Store.toggleArtist(a)) "Tu suis ${a.name}" else "Tu ne suis plus ${a.name}")
                render(MUSIC)
            }
        )
        if (recent) opts += Opt(R.drawable.ic_close, "Retirer des récents") { Store.removeRecentArtist(a.url); render(MUSIC) }
        sheet(a.name, "Artiste", opts)
    }

    private fun trackMenu(t: Track, playlistId: String?, index: Int) {
        val liked = Store.isLiked(t.url)
        val opts = mutableListOf(
            Opt(R.drawable.ic_playnext, "Lire ensuite") { Music.insertNext(t); snack("Sera lu ensuite") },
            Opt(R.drawable.ic_queue, "Ajouter à la file d'attente") { Music.addLast(t); snack("Ajouté à la file") },
            Opt(if (liked) R.drawable.ic_heart else R.drawable.ic_heart_outline, if (liked) "Retirer des titres likés" else "J'aime") {
                Store.toggleLike(t)
                render(MUSIC)
            },
            Opt(R.drawable.ic_playlist_add, "Ajouter à une playlist…") { NowPlayingActivity.addToPlaylist(this, t) }
        )
        if (playlistId != null) opts += Opt(R.drawable.ic_close, "Retirer de cette playlist") {
            Store.removeFromPlaylist(playlistId, index)
            render(MUSIC)
        }
        if (!t.local) {
            t.artistUrl?.let { u ->
                opts += Opt(R.drawable.ic_person, "Voir l'artiste") {
                    pushMusic(MPage.ArtistP(Music.Artist(u, t.artist ?: "Artiste", null)))
                }
            }
            opts += Opt(R.drawable.ic_download, "Télécharger") { startDownload(t.url, false, t.title) }
            opts += Opt(R.drawable.ic_video, "Voir le clip") { play(t.url) }
            opts += Opt(R.drawable.ic_share, "Partager le lien") {
                startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, t.url), t.title
                    )
                )
            }
        }
        sheet(t.title, t.artist, opts)
    }

    private fun playlistMenu(p: Store.Playlist) {
        sheet(
            p.name, "${p.tracks.size} titres", listOf(
                Opt(R.drawable.ic_settings, "Renommer") {
                    NowPlayingActivity.askName(this, "Renommer", p.name) { Store.renamePlaylist(p.id, it); render(MUSIC) }
                },
                Opt(R.drawable.ic_delete, "Supprimer la playlist") {
                    MaterialAlertDialogBuilder(this)
                        .setMessage("Supprimer la playlist « ${p.name} » ? Les titres ne sont pas supprimés.")
                        .setPositiveButton("Supprimer") { _, _ ->
                            Store.deletePlaylist(p.id)
                            if ((musicStack.lastOrNull() as? MPage.PlaylistP)?.id == p.id) popMusic() else render(MUSIC)
                        }
                        .setNegativeButton("Annuler", null)
                        .show()
                }
            )
        )
    }

    /** Mix : titres des artistes que tu aimes, mélangés. */
    private fun playMix() {
        val seeds = (Store.likes() + Store.recentTracks()).filter { !it.local }
        val artists = seeds.mapNotNull { it.artist }.distinct().shuffled().take(5)
        if (artists.isEmpty()) {
            snack("Like quelques titres d'abord (♥) pour créer ton mix")
            return
        }
        lifecycleScope.launch {
            busy(true)
            try {
                val found = artists.map { a -> async { runCatching { Music.songs(a).take(6) }.getOrDefault(emptyList()) } }.awaitAll()
                val mix = (found.flatten() + Store.likes().filter { !it.local }.shuffled().take(10)).distinctBy { it.url }.shuffled()
                if (mix.isEmpty()) snack("Mix impossible pour l'instant") else {
                    Music.play(mix, 0)
                    openNowPlayingSoon()
                }
            } finally {
                busy(false)
            }
        }
    }

    private fun musicRows(rows: MutableList<Row>) {
        val page = musicStack.lastOrNull()
        if (page != null) {
            musicPageRows(page, rows)
            return
        }
        val q = musicQuery
        if (q != null) {
            val r = musicResult
            if (r == null) {
                rows += Row.Note("Recherche de « $q »…")
                return
            }
            if (r.songs.isEmpty() && r.albums.isEmpty() && r.artists.isEmpty() && r.playlists.isEmpty()) {
                rows += Row.Note("Aucun résultat pour « $q ».", R.drawable.ic_music)
                return
            }
            if (r.songs.isNotEmpty()) {
                rows += Row.Header("Titres", "Tout lire") { Music.play(r.songs, 0); openNowPlayingSoon() }
                r.songs.indices.take(8).forEach { rows += trackRow(r.songs, it) }
            }
            if (r.artists.isNotEmpty()) {
                rows += Row.Header("Artistes")
                r.artists.take(4).forEach { rows += artistRow(it) }
            }
            if (r.albums.isNotEmpty()) {
                rows += Row.Header("Albums")
                r.albums.take(8).forEach { rows += albumRow(it, "Album") }
            }
            if (r.playlists.isNotEmpty()) {
                rows += Row.Header("Playlists")
                r.playlists.take(6).forEach { rows += albumRow(it, "Playlist") }
            }
            return
        }

        // Accueil musique
        quickMusic(rows)
        stripArtists(rows)
        deviceRows(rows, "music")
        val likes = Store.likes()
        val pls = Store.playlists()
        val recent = Store.recentTracks()
        rows += Row.Header("Ma musique")
        rows += Row.Track(
            "Titres likés", "${likes.size} titre" + if (likes.size > 1) "s" else "", null,
            onClick = { pushMusic(MPage.Liked) }
        )
        rows += Row.Track("Mix d'après tes goûts", "Titres de tes artistes, mélangés", null, onClick = { playMix() })
        pls.forEach { p ->
            rows += Row.Track(
                p.name, "Playlist · ${p.tracks.size} titres", p.tracks.firstOrNull()?.thumb,
                onClick = { pushMusic(MPage.PlaylistP(p.id)) }, onLong = { playlistMenu(p) }
            )
        }
        rows += Row.Track("Nouvelle playlist…", null, null, onClick = {
            NowPlayingActivity.askName(this, "Nouvelle playlist", "") { Store.createPlaylist(it); render(MUSIC) }
        })
        val myAlbums = Store.savedAlbums()
        if (myAlbums.isNotEmpty()) {
            rows += Row.Header("Albums enregistrés")
            rows += Row.Cards(myAlbums.map { a ->
                Row.Card(albumName(a.title), a.artist, a.thumb, onClick = { pushMusic(MPage.AlbumP(a)) }, onLong = { albumMenu(a) })
            })
        }
        val myArtists = Store.savedArtists()
        val recentAlbums = Store.recentAlbums()
        if (recentAlbums.isNotEmpty()) {
            rows += Row.Header("Albums écoutés récemment")
            rows += Row.Cards(recentAlbums.map { a ->
                Row.Card(albumName(a.title), a.artist, a.thumb, onClick = { pushMusic(MPage.AlbumP(a)) }, onLong = { albumMenu(a, recent = true) })
            })
        }
        val recentArtists = Store.recentArtists().filterNot { r -> myArtists.any { it.url == r.url } }
        if (recentArtists.isNotEmpty()) {
            rows += Row.Header("Artistes récents")
            rows += Row.Cards(recentArtists.map { a ->
                Row.Card(a.name, null, a.thumb, round = true, onClick = { pushMusic(MPage.ArtistP(a)) }, onLong = { artistMenu(a, recent = true) })
            })
        }
        if (recent.isNotEmpty()) {
            rows += Row.Header("Titres écoutés récemment", "Tout voir") { pushMusic(MPage.Recent) }
            rows += Row.Cards(recent.take(15).mapIndexed { i, t ->
                Row.Card(t.title, t.artist, t.thumb, onClick = { Music.play(recent, i); openNowPlayingSoon() },
                    onLong = { trackMenu(t, null, i) })
            })
        }

        // Musique du téléphone
        rows += Row.Header("Sur ce téléphone")
        if (!hasMediaPerm()) {
            rows += Row.Entry(
                "Afficher ma musique", "Autorise l'accès aux fichiers audio du téléphone", null, R.drawable.ic_music,
                onClick = { askMediaPerm() }
            )
        } else {
            val local = localMusic
            if (local == null) {
                rows += Row.Note("Chargement…")
                loadLocalMusic()
            } else if (local.isEmpty()) {
                rows += Row.Note("Aucune musique trouvée sur le téléphone.")
            } else {
                val albums = Music.localAlbums(local)
                rows += Row.Cards(albums.take(15).map { a ->
                    Row.Card(a.title, a.artist, a.thumb, onClick = { pushMusic(MPage.LocalAlbumP(a)) })
                })
                rows += Row.Track("Tous les titres", "${local.size} titres", null, onClick = { pushMusic(MPage.LocalAll) })
                rows += Row.Track("Albums", "${albums.size} albums", null, onClick = { pushMusic(MPage.LocalAlbums) })
                rows += Row.Track(
                    "Artistes", "${local.mapNotNull { it.artist }.distinct().size} artistes", null, round = true,
                    onClick = { pushMusic(MPage.LocalArtists) }
                )
            }
        }
        rows += Row.Note("Cherche un titre, un album ou un artiste avec la barre en haut.")
    }

    private fun musicPageRows(page: MPage, rows: MutableList<Row>) {
        fun list(
            title: String, sub: String?, thumb: String?, tracks: List<Track>, numbered: Boolean = false,
            playlistId: String? = null, round: Boolean = false, onMore: (() -> Unit)? = null,
            onPlayed: (() -> Unit)? = null, saveLabel: String? = null, saved: Boolean = false, onSave: (() -> Unit)? = null
        ) {
            rows += Row.MusicHeader(
                albumName(title), sub, thumb, round,
                onPlay = if (tracks.isEmpty()) null else ({ onPlayed?.invoke(); Music.play(tracks, 0); openNowPlayingSoon() }),
                onShuffle = if (tracks.size < 2) null else ({ onPlayed?.invoke(); Music.play(tracks, (tracks.indices).random(), shuffled = true); openNowPlayingSoon() }),
                onMore = onMore, saveLabel = saveLabel, saved = saved, onSave = onSave
            )
            tracks.indices.forEach { rows += trackRow(tracks, it, numbered, playlistId, onPlayed) }
        }
        when (page) {
            is MPage.AlbumP -> {
                val t = page.tracks
                when {
                    page.error != null -> {
                        rows += Row.MusicHeader(albumName(page.album.title), page.album.artist, page.album.thumb, false, null, null)
                        rows += Row.Note("Impossible de charger : ${page.error}")
                    }
                    t == null -> {
                        rows += Row.MusicHeader(albumName(page.album.title), page.album.artist, page.album.thumb, false, null, null)
                        rows += Row.Note("Chargement…")
                    }
                    else -> {
                        val total = t.sumOf { it.duration }
                        val saved = Store.isAlbumSaved(page.album.url)
                        list(
                            page.album.title,
                            listOfNotNull(page.album.artist, "${t.size} titres", Yt.formatDuration(total).ifEmpty { null }).joinToString(" · "),
                            page.album.thumb, t, numbered = true,
                            onPlayed = { Store.addRecentAlbum(page.album) },
                            saveLabel = if (saved) "Ajouté" else "Ajouter", saved = saved,
                            onSave = {
                                snack(if (Store.toggleAlbum(page.album)) "Ajouté à tes albums" else "Retiré de tes albums")
                                render(MUSIC)
                            }
                        )
                    }
                }
            }
            is MPage.ArtistP -> {
                val a = page.artist
                val songs = page.songs
                val followed = Store.isArtistSaved(a.url)
                rows += Row.MusicHeader(
                    a.name, "Artiste", a.thumb, true,
                    onPlay = songs?.takeIf { it.isNotEmpty() }?.let { s -> { Music.play(s, 0); openNowPlayingSoon() } },
                    onShuffle = songs?.takeIf { it.size > 1 }?.let { s -> { Music.play(s, s.indices.random(), true); openNowPlayingSoon() } },
                    saveLabel = if (followed) "Suivi" else "Suivre", saved = followed,
                    onSave = {
                        snack(if (Store.toggleArtist(a)) "Tu suis ${a.name}" else "Tu ne suis plus ${a.name}")
                        render(MUSIC)
                    }
                )
                when {
                    page.error != null -> rows += Row.Note("Impossible de charger : ${page.error}")
                    songs == null -> rows += Row.Note("Chargement…")
                    else -> {
                        if (songs.isNotEmpty()) {
                            rows += Row.Header("Titres populaires")
                            songs.indices.forEach { rows += trackRow(songs, it) }
                        }
                        val al = page.albums.orEmpty()
                        if (al.isNotEmpty()) {
                            rows += Row.Header("Albums")
                            rows += Row.Cards(al.map { x -> Row.Card(x.title, x.artist, x.thumb, onClick = { pushMusic(MPage.AlbumP(x)) }, onLong = { albumMenu(x) }) })
                        }
                        if (songs.isEmpty() && al.isEmpty()) rows += Row.Note("Rien trouvé pour cet artiste.")
                    }
                }
            }
            is MPage.PlaylistP -> {
                val p = Store.playlists().firstOrNull { it.id == page.id }
                if (p == null) {
                    rows += Row.Note("Playlist supprimée.")
                    return
                }
                list(p.name, "Playlist · ${p.tracks.size} titres", p.tracks.firstOrNull()?.thumb, p.tracks, playlistId = p.id, onMore = { playlistMenu(p) })
                if (p.tracks.isEmpty()) rows += Row.Note("Playlist vide.\nAppui long sur un titre → « Ajouter à une playlist… »")
            }
            MPage.Liked -> {
                val l = Store.likes()
                list("Titres likés", "${l.size} titres", null, l)
                if (l.isEmpty()) rows += Row.Note("Touche ♥ sur l'écran de lecture, ou appui long sur un titre → « J'aime ».")
            }
            MPage.Recent -> list("Écoutés récemment", null, null, Store.recentTracks())
            is MPage.LocalAlbumP -> list(page.album.title, listOfNotNull(page.album.artist, "${page.album.tracks.size} titres").joinToString(" · "), page.album.thumb, page.album.tracks, numbered = true)
            is MPage.LocalArtistP -> {
                val t = localMusic.orEmpty().filter { it.artist == page.name }
                list(page.name, "${t.size} titres", t.firstOrNull()?.thumb, t, round = true)
            }
            MPage.LocalAll -> {
                val t = localMusic.orEmpty().sortedBy { it.title.lowercase() }
                list("Tous les titres", "${t.size} titres sur le téléphone", null, t)
            }
            MPage.LocalAlbums -> {
                rows += Row.Header("Albums du téléphone")
                Music.localAlbums(localMusic.orEmpty()).forEach { a ->
                    rows += Row.Track(a.title, listOfNotNull(a.artist, "${a.tracks.size} titres").joinToString(" · "), a.thumb,
                        onClick = { pushMusic(MPage.LocalAlbumP(a)) })
                }
            }
            MPage.LocalArtists -> {
                rows += Row.Header("Artistes du téléphone")
                localMusic.orEmpty().groupBy { it.artist ?: "Artiste inconnu" }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
                    .forEach { (name, t) ->
                        rows += Row.Track(name, "${t.size} titres", t.firstOrNull()?.thumb, round = true,
                            onClick = { pushMusic(MPage.LocalArtistP(name)) })
                    }
            }
        }
    }

    // ——— Minuteur, infos, file d'attente ———

    private fun updateChips() {
        findViewById<Chip>(R.id.chipSleep).text = Playback.sleepLabel()?.let { "Minuteur · $it" } ?: getString(R.string.sleep)
        val n = Playback.queue.size
        findViewById<Chip>(R.id.chipQueue).text = if (n > 0) "File · $n" else getString(R.string.queue)
        val info = Playback.currentInfo()
        findViewById<Chip>(R.id.chipInfo).isVisible = info != null
        if (Playback.sleepAt > 0) {
            ui.removeCallbacks(ticker)
            ui.postDelayed(ticker, 30_000)
        }
    }

    private fun sleepMenu() {
        val opts = mutableListOf<Opt>()
        listOf(10, 15, 30, 45, 60, 90).forEach { m ->
            opts += Opt(R.drawable.ic_timer, "$m minutes") { Playback.setSleep(m); snack("Arrêt dans $m min") }
        }
        opts += Opt(R.drawable.ic_timer, "À la fin de la vidéo / de l'épisode") {
            Playback.setSleepEndOfItem()
            snack("Arrêt à la fin de l'élément en cours")
        }
        if (Playback.sleepLabel() != null) opts += Opt(R.drawable.ic_close, "Désactiver le minuteur") { Playback.cancelSleep() }
        sheet("Minuteur de sommeil", Playback.sleepLabel()?.let { "En cours : $it" }, opts)
    }

    private fun playerMenu() {
        val opts = mutableListOf(
            Opt(R.drawable.ic_timer, "Minuteur de sommeil") { sleepMenu() },
            Opt(R.drawable.ic_queue, "File d'attente (${Playback.queue.size})") { queueSheet() }
        )
        if (Playback.currentInfo() != null) opts.add(1, Opt(R.drawable.ic_info, "Description et chapitres") { infoSheet() })
        sheet(player.mediaMetadata.title?.toString(), player.mediaMetadata.artist?.toString(), opts)
    }

    /** Description et chapitres de l'élément en cours ; toucher un chapitre y saute. */
    private fun infoSheet() {
        val info = Playback.currentInfo() ?: return
        val dlg = BottomSheetDialog(this)
        val d = resources.displayMetrics.density
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (4 * d).toInt(), 0, (24 * d).toInt())
        }
        fun text(t: CharSequence, attr: Int, top: Int = 8) = TextView(this).apply {
            text = t
            TextViewCompat.setTextAppearance(this, attrRes(attr))
            setPadding((24 * d).toInt(), (top * d).toInt(), (24 * d).toInt(), (4 * d).toInt())
        }
        box.addView(text(player.mediaMetadata.title ?: "", com.google.android.material.R.attr.textAppearanceTitleMedium))
        if (info.chapters.isNotEmpty()) {
            box.addView(text("Chapitres", com.google.android.material.R.attr.textAppearanceLabelLarge, 16))
            info.chapters.forEach { (ms, title) ->
                val row = layoutInflater.inflate(R.layout.item_sheet_option, box, false)
                row.findViewById<ImageView>(R.id.icon).setImageResource(R.drawable.ic_play)
                row.findViewById<TextView>(R.id.label).text = Yt.formatDuration(ms / 1000).ifEmpty { "0:00" } + "  ·  " + title
                row.setOnClickListener {
                    player.seekTo(ms)
                    player.play()
                    dlg.dismiss()
                }
                box.addView(row)
            }
        }
        info.description?.let { desc ->
            box.addView(text("Description", com.google.android.material.R.attr.textAppearanceLabelLarge, 16))
            val body = if (info.html || desc.contains("<")) androidx.core.text.HtmlCompat.fromHtml(desc, androidx.core.text.HtmlCompat.FROM_HTML_MODE_COMPACT) else desc
            box.addView(text(body, com.google.android.material.R.attr.textAppearanceBodyMedium).apply {
                setTextIsSelectable(true)
                movementMethod = android.text.method.LinkMovementMethod.getInstance()
                android.text.util.Linkify.addLinks(this, android.text.util.Linkify.WEB_URLS)
            })
        }
        Ui.applyFont(box)
        dlg.setContentView(androidx.core.widget.NestedScrollView(this).apply { addView(box) })
        dlg.show()
    }

    private fun queueSheet() {
        val q = Playback.queue
        val opts = q.mapIndexed { i, item ->
            Opt(R.drawable.ic_close, "${i + 1}. ${item.title}") {
                Playback.removeFromQueue(i)
                snack("Retiré de la file")
            }
        }.toMutableList()
        if (q.isNotEmpty()) opts += Opt(R.drawable.ic_delete, "Vider la file") { Playback.clearQueue() }
        sheet(
            "File d'attente",
            if (q.isEmpty()) "Vide. Appui long sur une vidéo ou un épisode → « Lire ensuite » ou « Ajouter à la file »."
            else "Toucher un élément pour le retirer. La suite démarre à la fin de la lecture en cours.",
            opts
        )
    }

    // ——— Diagnostic ———

    private fun runDiagnostic() {
        val d = resources.displayMetrics.density
        val text = TextView(this).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 12f
            setTextIsSelectable(true)
            setPadding((20 * d).toInt(), (8 * d).toInt(), (20 * d).toInt(), (8 * d).toInt())
            text = "Tests en cours…\n"
        }
        val scroll = android.widget.ScrollView(this).apply { addView(text) }
        val log = StringBuilder()
        val dlg = MaterialAlertDialogBuilder(this)
            .setTitle("Diagnostic YouTube")
            .setView(scroll)
            .setPositiveButton("Copier") { _, _ ->
                val cm = getSystemService(android.content.ClipboardManager::class.java)
                cm.setPrimaryClip(android.content.ClipData.newPlainText("Diagnostic Lecteur", log.toString()))
                snack("Résultat copié : tu peux le coller dans un message")
            }
            .setNegativeButton("Fermer", null)
            .show()
        val job = lifecycleScope.launch {
            Diagnostic.run(applicationContext) { line ->
                log.append(line).append('\n')
                text.text = log
                scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
            }
        }
        dlg.setOnDismissListener { if (!job.isCompleted) job.cancel() }
    }

    // ——— Mises à jour ———

    private fun autoUpdateCheck() {
        if (System.currentTimeMillis() - Settings.lastUpdateCheck < 12 * 3600_000L) {
            // Vérification discrète : seulement la pastille sur l'icône, sans fenêtre
            lifecycleScope.launch {
                try { showUpdateDot(Updates.check(applicationContext) != null) } catch (e: CancellationException) { throw e } catch (_: Exception) {}
            }
            return
        }
        checkUpdate(manual = false)
    }

    private fun showUpdateDot(available: Boolean) {
        findViewById<View>(R.id.updateDot).isVisible = available
        val icon = findViewById<ImageView>(R.id.updateIcon)
        icon.imageTintList = android.content.res.ColorStateList.valueOf(
            MaterialColors.getColor(
                icon, if (available) com.google.android.material.R.attr.colorPrimary
                else com.google.android.material.R.attr.colorOnSurfaceVariant
            )
        )
        findViewById<View>(R.id.updateBtn).contentDescription =
            if (available) "Nouvelle version disponible" else "Rechercher une mise à jour"
    }

    private fun checkUpdate(manual: Boolean) {
        lifecycleScope.launch {
            try {
                val u = Updates.check(applicationContext)
                Settings.lastUpdateCheck = System.currentTimeMillis()
                showUpdateDot(u != null)
                if (u == null) {
                    if (manual) snack("Tu as la dernière version (v${Updates.current(this@MainActivity)})")
                    return@launch
                }
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("Nouvelle version : v${u.version}")
                    .setMessage("Tu as la v${Updates.current(this@MainActivity)}. La nouvelle version va se télécharger puis s'installer par-dessus (tes données sont gardées).")
                    .setPositiveButton("Installer") { _, _ -> Updates.downloadAndInstall(this@MainActivity, u) }
                    .setNegativeButton("Plus tard", null)
                    .show()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (manual) snack("Vérification impossible : ${e.message}")
            }
        }
    }

    // ——— Réglages ———

    private fun settingsRows(rows: MutableList<Row>) {
        rows += Row.Header("Affichage")
        rows += Row.Choice(
            "Disposition", "Partout : vidéos, épisodes, morceaux, albums, téléchargements. Tuiles, ou liste compacte",
            listOf("Cartes", "Liste"), if (Settings.cards) 0 else 1
        ) { i -> Settings.layout = if (i == 0) "cards" else "list"; relayout() }
        if (Settings.cards) {
            rows += Row.Slider(
                "Colonnes", "Nombre de vidéos par ligne (à 1, les tuiles carrées vont par deux)", Settings.columns.toFloat(), 1f, 4f, 1f,
                { it.toInt().toString() }
            ) { v -> Settings.columns = v.toInt(); relayout() }
        }
        val pct = (Math.round(Settings.tileScale * 10) * 10).coerceIn(70, 140)
        rows += Row.Slider(
            "Taille des tuiles", "Taille du texte et des miniatures", pct.toFloat(), 70f, 140f, 10f,
            { "${it.toInt()} %" }
        ) { v -> Settings.tileScale = v.toInt() / 100f; relayout() }

        rows += Row.Header("Apparence")
        val colorIdx = Settings.colors.indexOfFirst { it.key == Settings.color }.coerceAtLeast(0)
        rows += Row.Colors(
            "Couleur", Settings.colors.map { it.label to it.seed }, colorIdx
        ) { i ->
            Settings.color = Settings.colors[i].key
            Toast.makeText(this, "Couleur : ${Settings.colors[i].label}", Toast.LENGTH_SHORT).show()
            recreate()
        }
        val modes = listOf("system", "light", "dark")
        rows += Row.Choice(
            "Thème", null, listOf("Comme le téléphone", "Clair", "Sombre"),
            modes.indexOf(Settings.themeMode).coerceAtLeast(0)
        ) { i -> Settings.themeMode = modes[i]; Settings.applyNightMode() }
        rows += Row.Toggle(
            "Noir pur", "En thème sombre, fond entièrement noir (économise la batterie sur écran OLED)",
            Settings.black
        ) { on -> Settings.black = on; recreate() }
        rows += Row.Choice(
            "Police", null, Settings.fonts.map { it.second },
            Settings.fonts.indexOfFirst { it.first == Settings.font }.coerceAtLeast(0)
        ) { i -> Settings.font = Settings.fonts[i].first; recreate() }

        rows += Row.Header("Lecture")
        val qualities = listOf(360, 480, 720, 1080)
        rows += Row.Choice(
            "Qualité vidéo maximale", "Aussi pour les téléchargements. Plus bas = moins de données.",
            qualities.map { "${it}p" }, qualities.indexOf(Settings.maxHeight).let { if (it < 0) 3 else it }
        ) { i -> Settings.maxHeight = qualities[i]; render(SETTINGS) }

        rows += Row.Toggle(
            "Sauter les silences", "Raccourcit les blancs, surtout utile pour les podcasts", Settings.skipSilence
        ) { on -> Settings.skipSilence = on; PlayerHolder.applySkipSilence() }
        rows += Row.Toggle(
            "Serveurs relais en secours",
            "Si YouTube refuse une vidéo ou une recherche, passe par un serveur public (Piped)", Settings.fallback
        ) { on -> Settings.fallback = on }
        rows += Row.Toggle(
            "Vidéo suivante automatique",
            "À la fin d'une vidéo, enchaîne sur une suggestion si la file d'attente est vide", Settings.autoplay
        ) { on -> Settings.autoplay = on }
        rows += Row.Toggle(
            "Image dans l'image",
            "En quittant l'appli pendant une vidéo, elle continue dans une petite fenêtre (sinon : audio seul)",
            Settings.pip
        ) { on -> Settings.pip = on }

        rows += Row.Header("SponsorBlock")
        rows += Row.Toggle(
            "Sauter les passages sponsorisés",
            "Base collaborative SponsorBlock, vidéos YouTube uniquement", Settings.sponsorBlock
        ) { on -> Settings.sponsorBlock = on; render(SETTINGS) }
        if (Settings.sponsorBlock) {
            Settings.sbCategories.forEach { (key, label) ->
                rows += Row.Toggle(label, null, Settings.sbEnabled(key)) { on -> Settings.setSb(key, on) }
            }
        }

        rows += Row.Header("Nouveautés")
        rows += Row.Toggle(
            "Vérifier les nouveautés", "Toutes les 6 h : notification des nouveaux épisodes et vidéos", Settings.checkNew
        ) { on -> Settings.checkNew = on }
        rows += Row.Toggle(
            "Télécharger les nouveaux épisodes", "Automatiquement, en Wi-Fi seulement (5 au plus à chaque fois)",
            Settings.autoDownload
        ) { on -> Settings.autoDownload = on }

        rows += Row.Header("Données")
        rows += Row.Entry(
            "Importer des abonnements…", "Podcast Addict et autres (OPML), NewPipe (JSON), Google Takeout (CSV)",
            null, R.drawable.ic_download, onClick = { importLauncher.launch(arrayOf("*/*")) }
        )
        rows += Row.Entry(
            "Exporter mes podcasts (OPML)", "Pour les retrouver dans une autre appli", null, R.drawable.ic_upload,
            onClick = { opmlLauncher.launch("lecteur-podcasts.opml") }
        )
        rows += Row.Entry(
            "Sauvegarder tout…", "Abonnements, historique, positions de lecture, réglages", null, R.drawable.ic_upload,
            onClick = {
                val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.FRANCE).format(java.util.Date())
                backupLauncher.launch("lecteur-sauvegarde-$day.json")
            }
        )
        rows += Row.Entry(
            "Restaurer une sauvegarde…", null, null, R.drawable.ic_download,
            onClick = { restoreLauncher.launch(arrayOf("application/json", "*/*")) }
        )
        rows += Row.Entry(
            "Effacer l'historique", "Recherches récentes et vidéos lues", null, R.drawable.ic_delete,
            onClick = {
                MaterialAlertDialogBuilder(this)
                    .setMessage("Effacer les recherches récentes et les vidéos lues ?")
                    .setPositiveButton("Effacer") { _, _ ->
                        Store.clearSearches()
                        Store.clearWatched()
                        feed = emptyList()
                        renderAll()
                        snack("Historique effacé")
                    }
                    .setNegativeButton("Annuler", null)
                    .show()
            }
        )
        val version = try {
            packageManager.getPackageInfo(packageName, 0).let {
                "${it.versionName} (${androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(it)})"
            }
        } catch (_: Exception) {
            "?"
        }
        rows += Row.Header("Application")
        rows += Row.Entry(
            "Diagnostic YouTube", "Teste la lecture directe, le jeton et chaque serveur relais", null, R.drawable.ic_info,
            onClick = { runDiagnostic() }
        )
        rows += Row.Entry(
            "Rechercher une mise à jour", "Vérifie aussi automatiquement deux fois par jour", null, R.drawable.ic_update,
            onClick = { checkUpdate(manual = true) }
        )
        rows += Row.Note("Lecteur · version $version")
    }

    /** Applique disposition, colonnes et taille sans redémarrer l'écran. */
    private fun relayout() {
        lists.forEach { it?.let(::applyGrid) }
        renderAll()
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
        fun vertical(e1: MotionEvent?, e2: MotionEvent, vy: Float): Int {
            if (e1 == null) return 0
            val dy = e2.y - e1.y
            val dx = e2.x - e1.x
            if (abs(dy) < 60 * density || abs(dy) < abs(dx) * 1.5f || abs(vy) < 400) return 0
            return if (dy > 0) 1 else -1
        }
        // Vidéo : vers le haut → plein écran ; vers le bas → sort du plein écran, sinon passe en audio
        // Double appui à gauche / à droite : −10 s / +10 s (sans faire clignoter les commandes)
        // Plein écran : glisser verticalement à gauche → luminosité, à droite → volume
        val audio = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
        var zone = 0 // 0 : rien encore, 1 : luminosité, 2 : volume, -1 : autre
        var volAcc = 0f
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                zone = 0
                volAcc = 0f
                return true
            }

            // Un seul appui (confirmé, donc pas le début d'un double) : commandes affichées / masquées
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (playerView.isControllerFullyVisible) playerView.hideController() else playerView.showController()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val left = e.x < playerView.width / 2f
                val delta = if (left) -10_000L else 10_000L
                val d = player.duration
                var to = (player.currentPosition + delta).coerceAtLeast(0)
                if (d > 0) to = to.coerceAtMost(d - 500)
                player.seekTo(to)
                flash(if (left) "« 10 s" else "10 s »", if (left) -1 else 1)
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                if (!fullscreen || e1 == null) return false
                if (zone == 0) {
                    val dy = abs(e2.y - e1.y)
                    if (dy < 20 * density || dy < abs(e2.x - e1.x)) return false
                    val w = playerView.width
                    zone = when {
                        e1.x < w / 3f -> 1
                        e1.x > w * 2 / 3f -> 2
                        else -> -1
                    }
                }
                val h = playerView.height.coerceAtLeast(1)
                when (zone) {
                    1 -> {
                        val lp = window.attributes
                        var cur = lp.screenBrightness
                        if (cur < 0) cur = try {
                            android.provider.Settings.System.getInt(contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS) / 255f
                        } catch (_: Exception) { 0.5f }
                        val b = (cur + distanceY / h * 1.2f).coerceIn(0.01f, 1f)
                        lp.screenBrightness = b
                        window.attributes = lp
                        flash("☀ ${Math.round(b * 100)} %", -1)
                    }
                    2 -> {
                        val max = audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                        volAcc += distanceY / h * max * 1.2f
                        while (volAcc >= 1f) {
                            audio.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_RAISE, 0)
                            volAcc -= 1f
                        }
                        while (volAcc <= -1f) {
                            audio.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_LOWER, 0)
                            volAcc += 1f
                        }
                        val cur = audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                        flash("🔊 ${Math.round(cur * 100f / max.coerceAtLeast(1))} %", 1)
                    }
                }
                return zone > 0
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (zone > 0) return true // c'était un réglage de luminosité / volume
                when (vertical(e1, e2, vy)) {
                    -1 -> if (!fullscreen) {
                        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        return true
                    }
                    1 -> {
                        if (fullscreen) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        else setVideoMode(false)
                        return true
                    }
                }
                return false
            }
        })
        // On garde tous les touchers : c'est nous qui affichons / masquons les commandes
        playerView.setOnTouchListener { _, e -> detector.onTouchEvent(e); true }

        // Mini-lecteur : vers le haut → vidéo (ou écran « en cours » pour l'audio)
        val miniDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (vertical(e1, e2, vy) != -1) return false
                openFromMini()
                return true
            }
        })
        miniPlayer.setOnTouchListener { v, e ->
            if (miniDetector.onTouchEvent(e)) {
                // Le geste a servi : pas de « clic » en plus
                val c = MotionEvent.obtain(e).apply { action = MotionEvent.ACTION_CANCEL }
                v.onTouchEvent(c)
                c.recycle()
                true
            } else false
        }
    }

    private var seekLabel: TextView? = null

    /** Petite étiquette (−10 s, luminosité, volume) du côté touché, qui s'efface. side : -1 gauche, 1 droite. */
    private fun flash(text: String, side: Int) {
        val left = side < 0
        val frame = playerView.overlayFrameLayout ?: return
        val d = resources.displayMetrics.density
        val tv = seekLabel ?: TextView(this).apply {
            setTextColor(android.graphics.Color.WHITE)
            textSize = 18f
            setPadding((16 * d).toInt(), (8 * d).toInt(), (16 * d).toInt(), (8 * d).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 24 * d
                setColor(0x99000000.toInt())
            }
            frame.addView(this, android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            seekLabel = this
        }
        tv.text = text
        (tv.layoutParams as android.widget.FrameLayout.LayoutParams).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL or if (left) android.view.Gravity.START else android.view.Gravity.END
            marginStart = (32 * d).toInt()
            marginEnd = (32 * d).toInt()
        }
        tv.requestLayout()
        tv.animate().cancel()
        tv.alpha = 1f
        tv.visibility = View.VISIBLE
        tv.animate().alpha(0f).setStartDelay(500).setDuration(300).start()
    }

    private fun openFromMini() {
        val hasVideo = player.currentTracks.containsType(androidx.media3.common.C.TRACK_TYPE_VIDEO) ||
            PlayerHolder.current != null
        if (Music.active || !hasVideo) startActivity(Intent(this, NowPlayingActivity::class.java))
        else setVideoMode(true)
    }

    // ——— Affichage ———

    private fun refreshUi() {
        val has = player.mediaItemCount > 0
        val video = PlayerHolder.videoMode
        val land = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        fullscreen = has && video && (land || inPip)
        val yt = PlayerHolder.current != null

        playerView.isVisible = has && video
        nowPanel.isVisible = has && video && !fullscreen
        updateChips()
        miniPlayer.isVisible = has && !video && !fullscreen
        chipDownload.isVisible = yt
        chipChannel.isVisible = yt && PlayerHolder.current?.uploaderUrl != null
        updateSearchBar()
        findViewById<View>(R.id.topBar).isVisible = !fullscreen && sel == null
        findViewById<View>(R.id.selBar).isVisible = !fullscreen && sel != null
        findViewById<View>(R.id.bubbleBar).isVisible = !fullscreen
        pager.isVisible = !fullscreen
        bottomNav.isVisible = false
        if (fullscreen) loading.visibility = View.INVISIBLE
        else if (window.attributes.screenBrightness >= 0) {
            // Hors plein écran : luminosité normale du téléphone
            window.attributes = window.attributes.also { it.screenBrightness = -1f }
        }

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
        updateNowPlaying()
        updateMiniPlay()
        updateCastBar()
    }

    private fun updateNowPlaying() {
        val m = player.mediaMetadata
        nowTitle.text = m.title
        nowSub.text = m.artist
        nowSub.isVisible = !m.artist.isNullOrEmpty()
        miniTitle.text = m.title
        miniSub.text = m.artist
        miniSub.isVisible = !m.artist.isNullOrEmpty()
        val art = m.artworkUri?.toString()
        if (miniThumb.tag != art || art == null) Thumbs.load(miniThumb, art, R.drawable.ic_audio)
    }

    private fun updateCastBar() {
        val bar = findViewById<View>(R.id.castBar)
        bar.isVisible = Caster.isCasting && !fullscreen
        if (!Caster.isCasting) return
        findViewById<TextView>(R.id.castDevice).text = "Diffusion sur ${Caster.deviceName ?: "l'enceinte"}"
        findViewById<TextView>(R.id.castTitle).text = Caster.title ?: "Choisis un titre à diffuser"
        findViewById<ImageButton>(R.id.castPlay).setImageResource(if (Caster.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        // Pendant la diffusion, la vidéo du téléphone est inutile
        if (playerView.isVisible) setVideoMode(false)
        miniPlayer.isVisible = false
    }

    private fun updateMiniPlay() {
        miniPlay.setImageResource(
            if (player.isPlaying || (player.playWhenReady && player.playbackState == Player.STATE_BUFFERING))
                R.drawable.ic_pause
            else R.drawable.ic_play
        )
    }

    private fun busy(on: Boolean) {
        loadingCount = (loadingCount + if (on) 1 else -1).coerceAtLeast(0)
        loading.visibility = if (loadingCount > 0 && !fullscreen) View.VISIBLE else View.INVISIBLE
    }

    private fun snack(text: String) {
        Snackbar.make(root, text, Snackbar.LENGTH_LONG)
            .setAnchorView(
                when {
                    miniPlayer.isVisible -> miniPlayer
                    bottomNav.isVisible -> bottomNav
                    else -> null
                }
            )
            .show()
    }

    companion object {
        /** Écrans réels (onglets) : Accueil et Bibliothèque. */
        private const val PAGES = 2
        /** Vues : vidéos, musique, abonnements/podcasts, bibliothèque, réglages, « tout ». */
        private const val HOME = 0
        private const val MUSIC = 1
        private const val SUBS = 2
        private const val OFFLINE = 3
        private const val SETTINGS = 4
        private const val ALL = 5
        private val AUDIO_EXT = setOf("mp3", "m4a", "aac", "ogg", "oga", "opus", "flac", "wav", "wma", "amr")
        private val VIDEO_EXT = setOf("mp4", "mkv", "webm", "mov", "3gp", "avi", "m4v", "ts")
    }
}
