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
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import kotlin.math.abs

@OptIn(UnstableApi::class)
class MainActivity : AppCompatActivity() {

    private enum class Tab { HOME, OFFLINE, FOLDERS }

    private class FileEntry(val doc: DocumentFile, val name: String, val dir: Boolean, val type: String?)

    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var miniBar: View
    private lateinit var miniTitle: TextView
    private lateinit var miniPlay: ImageButton
    private lateinit var miniVideo: Button
    private lateinit var miniDownload: ImageButton
    private lateinit var searchRow: View
    private lateinit var tabs: View
    private lateinit var tabButtons: Map<Tab, Button>
    private lateinit var query: EditText
    private lateinit var status: TextView
    private lateinit var results: RecyclerView

    private val adapter = RowAdapter()
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var loadJob: Job? = null
    private var fullscreen = false

    private var tab = Tab.HOME
    private var searchQuery: String? = null
    private var searchResults: List<StreamInfoItem> = emptyList()
    private var feed: List<StreamInfoItem>? = null
    private var feedJob: Job? = null
    private val folderStack = ArrayList<Pair<DocumentFile, String>>()
    private var folderFiles: List<FileEntry>? = null
    private var folderJob: Job? = null
    private var pendingDownload: Pair<String, Boolean>? = null

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
            render()
        }
    }
    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val storagePerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val p = pendingDownload
        pendingDownload = null
        if (granted && p != null) startDownload(p.first, p.second)
        else if (!granted) toast("Permission refusée : impossible d'enregistrer le fichier")
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = updateMiniPlay()
        override fun onPlaybackStateChanged(state: Int) = updateMiniPlay()
        override fun onMediaMetadataChanged(m: MediaMetadata) {
            miniTitle.text = m.title
        }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = refreshUi()
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
        miniVideo = findViewById(R.id.miniVideo)
        miniDownload = findViewById(R.id.miniDownload)
        searchRow = findViewById(R.id.searchRow)
        tabs = findViewById(R.id.tabs)
        query = findViewById(R.id.query)
        status = findViewById(R.id.status)
        results = findViewById(R.id.results)
        tabButtons = mapOf(
            Tab.HOME to findViewById(R.id.tabHome),
            Tab.OFFLINE to findViewById(R.id.tabOffline),
            Tab.FOLDERS to findViewById(R.id.tabFolders)
        )

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
        tabButtons.forEach { (t, b) -> b.setOnClickListener { selectTab(t) } }

        miniPlay.setOnClickListener {
            if (player.isPlaying) player.pause() else {
                if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                player.play()
            }
        }
        miniVideo.setOnClickListener { setVideoMode(true) }
        miniBar.setOnClickListener { if (!PlayerHolder.videoMode) setVideoMode(true) }
        miniDownload.setOnClickListener { currentYoutubeUrl()?.let { askDownload(it) } }

        setupSwipeDown()
        playerView.setFullscreenButtonClickListener { full ->
            requestedOrientation = if (full) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    fullscreen -> requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    tab == Tab.FOLDERS && folderStack.isNotEmpty() -> leaveFolder()
                    tab == Tab.HOME && searchQuery != null -> clearSearch()
                    tab != Tab.HOME -> selectTab(Tab.HOME)
                    else -> moveTaskToBack(true) // l'audio continue
                }
            }
        })

        // Relie l'écran au service : c'est lui qui maintient la lecture en arrière-plan
        controllerFuture = MediaController.Builder(
            this, SessionToken(this, ComponentName(this, PlaybackService::class.java))
        ).buildAsync()

        Store.onChange = { if (tab == Tab.OFFLINE) render() }
        refreshUi()
        render()
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
        if (tab == Tab.OFFLINE) render()
    }

    override fun onStop() {
        super.onStop()
        // Appli en arrière-plan ou écran verrouillé : audio seul, la vidéo n'est plus téléchargée
        if (!isChangingConfigurations) PlayerHolder.setVideoEnabled(player, false)
    }

    override fun onDestroy() {
        Store.onChange = null
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
        hideKeyboard()
        if (Yt.looksLikeUrl(text)) play(text) else search(text)
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(query.windowToken, 0)
    }

    private fun search(q: String) {
        hideKeyboard()
        Store.addSearch(q)
        feed = null
        tab = Tab.HOME
        lifecycleScope.launch {
            showStatus("Recherche…")
            try {
                val items = Yt.search(q)
                searchQuery = q
                searchResults = items
                render()
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

    private fun clearSearch() {
        searchQuery = null
        searchResults = emptyList()
        query.setText("")
        hideStatus()
        render()
    }

    private fun play(url: String) {
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            showStatus("Chargement…")
            try {
                val (info, source) = withContext(Dispatchers.IO) {
                    val i = Yt.info(url)
                    i to Sources.build(i)
                }
                player.setMediaSource(source)
                player.prepare()
                player.play()
                hideStatus()
                Store.addWatched(
                    Store.Video(info.url, info.name, info.uploaderName, Sources.bestThumb(info.thumbnails), info.duration)
                )
                setVideoMode(true)
                if (tab == Tab.HOME && searchQuery == null) render()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("Lecteur", "Chargement", e)
                showStatus("Impossible de lire cette vidéo : ${e.message}")
            }
        }
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
        hideStatus()
        player.setMediaItems(items, index, 0L)
        player.prepare()
        player.play()
        setVideoMode(!audio)
    }

    private fun playSingleLocal(uri: Uri) {
        val doc = DocumentFile.fromSingleUri(this, uri)
        val name = doc?.name ?: "Fichier"
        startLocal(listOf(mediaItem(uri, name, null, null)), 0, isAudio(doc?.type, name))
    }

    private fun playDownloads(list: List<Store.Download>, index: Int) {
        val items = list.map { mediaItem(Uri.parse(it.uri), it.title, it.uploader, it.thumb) }
        startLocal(items, index, !list[index].video)
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
        folderStack += doc to name
        loadFolder()
    }

    private fun leaveFolder() {
        if (folderStack.isNotEmpty()) folderStack.removeAt(folderStack.size - 1)
        if (folderStack.isEmpty()) {
            folderFiles = null
            render()
        } else loadFolder()
    }

    private fun loadFolder() {
        val doc = folderStack.lastOrNull()?.first ?: return
        folderFiles = null
        render()
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
            render()
        }
    }

    // ——— Téléchargements ———

    private fun currentYoutubeUrl(): String? =
        player.currentMediaItem?.mediaId?.takeIf { it.startsWith("http") }

    private fun askDownload(url: String) {
        AlertDialog.Builder(this)
            .setTitle("Télécharger")
            .setItems(arrayOf("Audio seul (écoute hors ligne)", "Vidéo (720p max)")) { _, w ->
                startDownload(url, w == 1)
            }
            .show()
    }

    private fun ytMenu(url: String) {
        AlertDialog.Builder(this)
            .setItems(arrayOf("Lire", "Télécharger l'audio", "Télécharger la vidéo")) { _, w ->
                when (w) {
                    0 -> play(url)
                    1 -> startDownload(url, false)
                    else -> startDownload(url, true)
                }
            }
            .show()
    }

    private fun startDownload(url: String, video: Boolean) {
        if (Build.VERSION.SDK_INT < 29 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingDownload = url to video
            storagePerm.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        DownloadService.start(this, url, video)
        toast(if (video) "Téléchargement de la vidéo lancé" else "Téléchargement de l'audio lancé")
    }

    private fun confirmDelete(d: Store.Download) {
        AlertDialog.Builder(this)
            .setMessage("Supprimer « ${d.title} » du téléphone ?")
            .setPositiveButton("Supprimer") { _, _ ->
                Saver.delete(this, Uri.parse(d.uri))
                Store.removeDownload(d.uri)
                render()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun confirmRemoveFolder(uri: String) {
        AlertDialog.Builder(this)
            .setMessage("Retirer ce dossier de la liste ? (les fichiers ne sont pas supprimés)")
            .setPositiveButton("Retirer") { _, _ ->
                try {
                    contentResolver.releasePersistableUriPermission(
                        Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {
                }
                Store.removeFolder(uri)
                render()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    // ——— Onglets et listes ———

    private fun selectTab(t: Tab) {
        if (t == Tab.HOME && tab == Tab.HOME && searchQuery != null) clearSearch()
        tab = t
        hideStatus()
        render()
        results.scrollToPosition(0)
    }

    private fun render() {
        tabButtons.forEach { (t, b) -> b.alpha = if (t == tab) 1f else 0.5f }
        val rows = mutableListOf<Row>()
        when (tab) {
            Tab.HOME -> homeRows(rows)
            Tab.OFFLINE -> offlineRows(rows)
            Tab.FOLDERS -> folderRows(rows)
        }
        adapter.submit(rows)
    }

    private fun ytRow(url: String, title: String, uploader: String?, thumb: String?, duration: Long) =
        Row.Entry(
            title,
            listOfNotNull(uploader?.ifEmpty { null }, Yt.formatDuration(duration).ifEmpty { null })
                .joinToString(" · "),
            thumb, R.drawable.ic_video,
            onClick = { play(url) },
            onLong = { ytMenu(url) }
        )

    private fun itemRow(it: StreamInfoItem) =
        ytRow(it.url, it.name, it.uploaderName, Sources.bestThumb(it.thumbnails), it.duration)

    private fun homeRows(rows: MutableList<Row>) {
        val q = searchQuery
        if (q != null) {
            rows += Row.Header("Résultats pour « $q »")
            searchResults.forEach { rows += itemRow(it) }
            return
        }
        val searches = Store.searches()
        val watched = Store.watched()
        if (searches.isNotEmpty()) {
            rows += Row.Header("Recherches récentes")
            rows += Row.Chips(
                searches.take(15),
                onClick = { s -> query.setText(s); search(s) },
                onLong = { s -> Store.removeSearch(s); feed = null; render() }
            )
        }
        if (watched.isNotEmpty()) {
            rows += Row.Header("Récemment lus")
            watched.take(6).forEach { rows += ytRow(it.url, it.title, it.uploader, it.thumb, it.duration) }
        }
        if (searches.isNotEmpty()) {
            rows += Row.Header("D'après tes recherches")
            val f = feed
            if (f == null) {
                rows += Row.Header("Chargement…", small = true)
                loadFeed()
            } else {
                f.forEach { rows += itemRow(it) }
            }
        }
        if (searches.isEmpty() && watched.isEmpty()) {
            rows += Row.Header(
                "Cherche une vidéo ou colle un lien YouTube. L'accueil se remplira avec tes recherches.",
                small = true
            )
        }
    }

    /** Fil d'accueil : résultats des 3 dernières recherches, entremêlés. */
    private fun loadFeed() {
        if (feedJob?.isActive == true) return
        feedJob = lifecycleScope.launch {
            val qs = Store.searches().take(3)
            val lists = qs.map { q ->
                async { runCatching { Yt.search(q) }.getOrDefault(emptyList()) }
            }.awaitAll()
            val out = LinkedHashMap<String, StreamInfoItem>()
            val max = lists.maxOfOrNull { it.size } ?: 0
            for (i in 0 until max) for (l in lists) l.getOrNull(i)?.let { out.putIfAbsent(it.url, it) }
            feed = out.values.take(45)
            if (tab == Tab.HOME && searchQuery == null) render()
        }
    }

    private fun offlineRows(rows: MutableList<Row>) {
        val list = Store.downloads()
        if (list.isEmpty()) {
            rows += Row.Header("Aucun téléchargement")
            rows += Row.Header(
                "Pendant une lecture YouTube, touche ⬇, ou fais un appui long sur une vidéo. " +
                    "Les fichiers vont dans Musique/Lecteur et Films/Lecteur.",
                small = true
            )
            return
        }
        rows += Row.Header("Téléchargements")
        list.forEachIndexed { i, d ->
            rows += Row.Entry(
                d.title,
                (if (d.video) "Vidéo" else "Audio") + (d.uploader?.let { " · $it" } ?: ""),
                d.thumb,
                if (d.video) R.drawable.ic_video else R.drawable.ic_audio,
                onClick = { playDownloads(list, i) },
                onLong = { confirmDelete(d) }
            )
        }
    }

    private fun folderRows(rows: MutableList<Row>) {
        if (folderStack.isEmpty()) {
            rows += Row.Entry(
                "Ouvrir un fichier…", "Audio ou vidéo du téléphone", null, R.drawable.ic_file,
                onClick = { openFile.launch(arrayOf("audio/*", "video/*")) }
            )
            rows += Row.Entry(
                "Ajouter un dossier…", "Il restera dans la liste ci-dessous", null, R.drawable.ic_add,
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
        rows += Row.Entry("Retour", null, null, R.drawable.ic_back, onClick = { leaveFolder() })
        rows += Row.Header(folderStack.last().second)
        val files = folderFiles
        if (files == null) {
            rows += Row.Header("Chargement…", small = true)
            return
        }
        if (files.isEmpty()) rows += Row.Header("Aucun fichier audio ou vidéo ici.", small = true)
        val media = files.filter { !it.dir }
        files.forEach { f ->
            rows += if (f.dir) {
                Row.Entry(f.name, null, null, R.drawable.ic_folder, onClick = { enterFolder(f.doc, f.name) })
            } else {
                val audio = isAudio(f.type, f.name)
                Row.Entry(
                    f.name, if (audio) "Audio" else "Vidéo", null,
                    if (audio) R.drawable.ic_audio else R.drawable.ic_video,
                    onClick = { playFolder(media, media.indexOf(f)) }
                )
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
        miniBar.isVisible = has && !fullscreen
        miniPlay.isVisible = !video
        miniVideo.isVisible = !video
        miniDownload.isVisible = currentYoutubeUrl() != null
        searchRow.isVisible = !fullscreen
        tabs.isVisible = !fullscreen
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

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    companion object {
        private val AUDIO_EXT = setOf("mp3", "m4a", "aac", "ogg", "oga", "opus", "flac", "wav", "wma", "amr")
        private val VIDEO_EXT = setOf("mp4", "mkv", "webm", "mov", "3gp", "avi", "m4v", "ts")
    }
}
