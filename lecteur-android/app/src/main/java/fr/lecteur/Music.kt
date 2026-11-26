package fr.lecteur

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/** Un titre : YouTube Music (url https) ou fichier du téléphone (content://). */
data class Track(
    val url: String,
    val title: String,
    val artist: String?,
    val artistUrl: String?,
    val thumb: String?,
    val duration: Long,
    val album: String? = null
) {
    val local get() = url.startsWith("content://") || url.startsWith("file://")
    val mediaId get() = "music:$url"
}

/**
 * Musique façon Spotify : recherche YouTube Music, albums, artistes, paroles,
 * musique du téléphone, et lecture enchaînée avec aléatoire / répétition.
 */
@OptIn(UnstableApi::class)
object Music {
    private lateinit var app: Context

    fun init(ctx: Context) {
        app = ctx.applicationContext
    }

    // ——— Catalogue YouTube Music ———
    data class Album(val url: String, val title: String, val artist: String?, val thumb: String?, val count: Long = -1)
    data class Artist(val url: String, val name: String, val thumb: String?)
    class SearchResult(val songs: List<Track>, val albums: List<Album>, val artists: List<Artist>, val playlists: List<Album>)

    fun clean(a: String?) = a?.removeSuffix(" - Topic")?.trim()?.ifEmpty { null }
    private fun fix(u: String) = u.replace("music.youtube.com", "www.youtube.com")

    fun track(it: StreamInfoItem) = Track(
        fix(it.url), it.name, clean(it.uploaderName), it.uploaderUrl?.let(::fix),
        Sources.bestThumb(it.thumbnails, 200), it.duration
    )

    fun album(it: PlaylistInfoItem) = Album(
        fix(it.url), it.name, clean(it.uploaderName), Sources.bestThumb(it.thumbnails, 200), it.streamCount
    )

    private fun searchRaw(q: String, filter: String) = try {
        val ex = ServiceList.YouTube.getSearchExtractor(q, listOf(filter), "")
        ex.fetchPage()
        ex.initialPage.items
    } catch (_: Exception) {
        emptyList()
    }

    suspend fun search(q: String): SearchResult = coroutineScope {
        val songs = async(Dispatchers.IO) { searchRaw(q, "music_songs") }
        val albums = async(Dispatchers.IO) { searchRaw(q, "music_albums") }
        val artists = async(Dispatchers.IO) { searchRaw(q, "music_artists") }
        val pls = async(Dispatchers.IO) { searchRaw(q, "music_playlists") }
        SearchResult(
            songs.await().filterIsInstance<StreamInfoItem>().map(::track),
            albums.await().filterIsInstance<PlaylistInfoItem>().map(::album),
            artists.await().filterIsInstance<ChannelInfoItem>().map {
                Artist(fix(it.url), clean(it.name) ?: it.name, Sources.bestThumb(it.thumbnails, 200))
            },
            pls.await().filterIsInstance<PlaylistInfoItem>().map(::album)
        )
    }

    suspend fun songs(q: String): List<Track> = withContext(Dispatchers.IO) {
        searchRaw(q, "music_songs").filterIsInstance<StreamInfoItem>().map(::track)
    }

    /** Titres d'un album ou d'une playlist (jusqu'à 300). */
    suspend fun albumTracks(a: Album): Pair<Album, List<Track>> = withContext(Dispatchers.IO) {
        val url = fix(a.url)
        val info = PlaylistInfo.getInfo(ServiceList.YouTube, url)
        val items = info.relatedItems.toMutableList()
        var next: Page? = info.nextPage
        while (next != null && Page.isValid(next) && items.size < 300) {
            val more = PlaylistInfo.getMoreItems(ServiceList.YouTube, url, next)
            items += more.items
            next = more.nextPage
        }
        val album = Album(
            url, info.name, clean(info.uploaderName) ?: a.artist,
            Sources.bestThumb(info.thumbnails, 300) ?: a.thumb, info.streamCount
        )
        album to items.map(::track).map { it.copy(album = album.title) }
    }

    /** Page d'artiste : titres populaires et albums. */
    suspend fun artist(a: Artist): Triple<Artist, List<Track>, List<Album>> = coroutineScope {
        val info = async(Dispatchers.IO) { runCatching { ChannelInfo.getInfo(a.url) }.getOrNull() }
        val songs = async(Dispatchers.IO) {
            searchRaw(a.name, "music_songs").filterIsInstance<StreamInfoItem>().map(::track)
                .filter { it.artist?.contains(a.name, true) == true }.take(20)
        }
        val ci = info.await()
        val albums = withContext(Dispatchers.IO) {
            val fromTab = try {
                val tab = ci?.tabs?.firstOrNull { it.contentFilters.contains(ChannelTabs.ALBUMS) }
                if (tab == null) emptyList()
                else ChannelTabInfo.getInfo(ServiceList.YouTube, tab).relatedItems
                    .filterIsInstance<PlaylistInfoItem>().map(::album)
            } catch (_: Exception) {
                emptyList()
            }
            fromTab.ifEmpty {
                searchRaw(a.name, "music_albums").filterIsInstance<PlaylistInfoItem>().map(::album)
                    .filter { it.artist?.contains(a.name, true) == true }
            }
        }
        val avatar = ci?.avatars?.let { Sources.bestThumb(it, 200) } ?: a.thumb
        Triple(a.copy(thumb = avatar), songs.await(), albums)
    }

    // ——— Paroles (LRCLIB, gratuit) ———
    class Lyrics(val synced: List<Pair<Long, String>>?, val plain: String?)

    suspend fun lyrics(title: String, artist: String?, durationSec: Long): Lyrics? = withContext(Dispatchers.IO) {
        val t = title.replace(
            Regex("\\s*[(\\[][^)\\]]*(official|clip|lyric|audio|video|paroles|visuali[sz]er|remaster)[^)\\]]*[)\\]]", RegexOption.IGNORE_CASE), ""
        ).substringAfter(" - ").trim()
        val a = clean(artist) ?: title.substringBefore(" - ", "").trim().ifEmpty { null }
        fun get(url: String): String? {
            val req = okhttp3.Request.Builder().url(url).header("User-Agent", "Lecteur (appli personnelle)").build()
            return DownloaderImpl.instance.client.newCall(req).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
        }
        try {
            var o: JSONObject? = null
            if (a != null) {
                val b = Uri.parse("https://lrclib.net/api/get").buildUpon()
                    .appendQueryParameter("track_name", t).appendQueryParameter("artist_name", a)
                if (durationSec > 0) b.appendQueryParameter("duration", durationSec.toString())
                o = get(b.build().toString())?.let { JSONObject(it) }
            }
            if (o == null) {
                val b = Uri.parse("https://lrclib.net/api/search").buildUpon().appendQueryParameter("track_name", t)
                if (a != null) b.appendQueryParameter("artist_name", a)
                o = get(b.build().toString())?.let { JSONArray(it).optJSONObject(0) }
            }
            if (o == null) return@withContext null
            val synced = o.optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" }?.lines()?.mapNotNull { line ->
                val m = Regex("^\\[(\\d+):(\\d+)(?:[.:](\\d+))?]\\s?(.*)$").find(line.trim()) ?: return@mapNotNull null
                val (mm, ss, frac, text) = m.destructured
                val ms = mm.toLong() * 60_000 + ss.toLong() * 1000 +
                    (frac.padEnd(3, '0').take(3).toLongOrNull() ?: 0)
                ms to text
            }
            val plain = o.optString("plainLyrics").takeIf { it.isNotBlank() && it != "null" }
            if (synced.isNullOrEmpty() && plain == null) null else Lyrics(synced?.ifEmpty { null }, plain)
        } catch (_: Exception) {
            null
        }
    }

    // ——— Musique du téléphone ———
    data class LocalAlbum(val id: Long, val title: String, val artist: String?, val thumb: String?, val tracks: List<Track>)

    fun localTracks(ctx: Context): List<Track> {
        // Les épisodes de podcast téléchargés ne sont pas de la musique
        val podIds = Store.downloads().filter { !it.video && !it.source.contains("youtu") }
            .mapNotNull { Uri.parse(it.uri).lastPathSegment?.toLongOrNull() }.toSet()
        val out = mutableListOf<Track>()
        val proj = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.DURATION
        )
        try {
            ctx.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj,
                "${MediaStore.Audio.Media.IS_MUSIC} != 0", null,
                "${MediaStore.Audio.Media.ALBUM} COLLATE NOCASE, ${MediaStore.Audio.Media.TRACK}"
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    if (id in podIds) continue
                    val albumId = c.getLong(4)
                    val art = if (Build.VERSION.SDK_INT >= 29)
                        ContentUris.withAppendedId(MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI, albumId).toString()
                    else null
                    out += Track(
                        ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id).toString(),
                        c.getString(1) ?: "Titre", c.getString(2)?.takeIf { it != "<unknown>" }, null,
                        art, c.getLong(5) / 1000, c.getString(3)?.takeIf { it != "<unknown>" }
                    )
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    fun localAlbums(tracks: List<Track>): List<LocalAlbum> =
        tracks.groupBy { (it.album ?: "Sans album") + "|" + it.thumb }.map { (_, list) ->
            LocalAlbum(
                list.first().thumb?.let { Uri.parse(it).lastPathSegment?.toLongOrNull() } ?: 0,
                list.first().album ?: "Sans album",
                list.mapNotNull { it.artist }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key,
                list.first().thumb, list
            )
        }.sortedBy { it.title.lowercase() }

    // ——— Lecture enchaînée ———
    enum class Repeat { OFF, ALL, ONE }

    var active = false
        private set
    var tracks: List<Track> = emptyList()
        private set
    private var original: List<Track> = emptyList()
    var index = 0
        private set
    var shuffle = false
        private set
    var repeat = Repeat.OFF
        private set

    /** Pour chaque élément du lecteur : sa position dans [tracks] (-1 si inconnue). */
    private val inPlayer = mutableListOf<Int>()
    private val resolving = HashSet<Int>()
    private var generation = 0

    val current: Track? get() = if (active) tracks.getOrNull(index) else null

    private val player get() = PlayerHolder.get(app)

    fun stop() {
        if (!active) return
        active = false
        generation++
        resolving.clear()
        inPlayer.clear()
        player.repeatMode = Player.REPEAT_MODE_OFF
    }

    /** Lance une liste de titres à partir de [start] (éventuellement en aléatoire). */
    fun play(list: List<Track>, start: Int, shuffled: Boolean = false) {
        if (list.isEmpty()) return
        val first = list.getOrElse(start) { list[0] }
        Playback.startedLocal()
        active = true
        generation++
        resolving.clear()
        original = list
        shuffle = shuffled
        tracks = if (shuffled) listOf(first) + (list - first).shuffled() else list
        index = if (shuffled) 0 else start.coerceIn(0, list.size - 1)
        PlayerHolder.videoMode = false
        PlayerHolder.setVideoEnabled(player, false)
        player.repeatMode = if (repeat == Repeat.ONE) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        load(index, now = true)
        Playback.notifyChanged()
    }

    private suspend fun source(t: Track, viaFallback: Boolean = false): MediaSource {
        if (t.local) {
            val item = MediaItem.Builder()
                .setUri(t.url)
                .setMediaId(t.mediaId)
                .setMediaMetadata(
                    MediaMetadata.Builder().setTitle(t.title).setArtist(t.artist).setAlbumTitle(t.album)
                        .setArtworkUri(t.thumb?.let(Uri::parse)).build()
                )
                .build()
            return DefaultMediaSourceFactory(app).createMediaSource(item)
        }
        if (!viaFallback) {
            try {
                val info = withContext(Dispatchers.IO) { StreamInfo.getInfo(t.url) }
                return withContext(Dispatchers.IO) {
                    Sources.build(info, t.mediaId, audioOnly = true, title = t.title, artist = t.artist, artwork = t.thumb)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!Settings.fallback) throw e
            }
        }
        // YouTube refuse : serveur relais
        val id = Fallback.videoId(t.url) ?: throw java.io.IOException("lien non reconnu")
        return withContext(Dispatchers.IO) {
            Fallback.build(id, t.mediaId, audioOnly = true, title = t.title, artist = t.artist, artwork = t.thumb).source
        }
    }

    /**
     * Le titre qui a échoué : celui en cours, ou le suivant s'il était en préparation
     * pendant que le précédent finissait.
     */
    private fun failingIndex(): Int {
        val p = player
        val ci = p.currentMediaItemIndex
        val d = p.duration
        val nearEnd = d > 0 && p.currentPosition >= d - 3000
        val next = inPlayer.getOrNull(ci + 1)
        return if (nearEnd && next != null && next >= 0) next else inPlayer.getOrNull(ci)?.takeIf { it >= 0 } ?: index
    }

    fun failingKey(): String? = tracks.getOrNull(failingIndex())?.mediaId

    /** Relance le titre en échec avec un lien neuf (ou via un serveur relais), au bon endroit. */
    fun recover(viaFallback: Boolean) {
        if (!active) return
        val i = failingIndex()
        val pos = if (i == index) player.currentPosition else 0L
        // Les liens préparés à l'avance pour les titres suivants sont oubliés : ils seront redemandés
        load(i, now = true, viaFallback = viaFallback, startAt = pos)
    }

    fun retryWithFallback() = recover(viaFallback = true)

    /** Abandonne le titre en échec et lance celui d'après. */
    fun skipFailing() {
        if (!active) return
        val i = failingIndex()
        val n = when {
            i + 1 < tracks.size -> i + 1
            repeat == Repeat.ALL && tracks.isNotEmpty() -> 0
            else -> return
        }
        load(n, now = true)
    }

    /** Prépare le titre [i] : tout de suite (now) ou à la suite de la liste du lecteur. */
    private fun load(i: Int, now: Boolean, viaFallback: Boolean = false, startAt: Long = 0) {
        if (i !in tracks.indices || i in resolving) return
        resolving += i
        val gen = generation
        val t = tracks[i]
        Playback.scope.launch {
            try {
                val src = source(t, viaFallback)
                if (gen != generation) return@launch
                if (now) {
                    inPlayer.clear()
                    inPlayer += i
                    index = i
                    player.setMediaSource(src, startAt)
                    player.prepare()
                    player.play()
                } else {
                    player.addMediaSource(src)
                    inPlayer += i
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen == generation) {
                    Toast.makeText(app, "« ${t.title} » indisponible", Toast.LENGTH_SHORT).show()
                    // Titre suivant à la place
                    val n = i + 1
                    if (n in tracks.indices) {
                        resolving -= i
                        load(n, now)
                        return@launch
                    }
                }
            } finally {
                resolving -= i
            }
        }
    }

    private fun nextIndex(): Int? = when {
        index + 1 < tracks.size -> index + 1
        repeat == Repeat.ALL && tracks.isNotEmpty() -> 0
        else -> null
    }

    private fun prefetchNext() {
        val n = nextIndex() ?: return
        val after = inPlayer.drop(player.currentMediaItemIndex + 1)
        if (n !in after) load(n, now = false)
    }

    /** Appelé à chaque changement d'élément dans le lecteur. */
    fun onTransition() {
        if (!active) return
        val p = player
        val ti = inPlayer.getOrNull(p.currentMediaItemIndex) ?: return
        if (ti < 0) return
        index = ti
        val t = tracks.getOrNull(ti) ?: return
        Store.addRecentTrack(t)
        PlayerHolder.current = if (t.local) null else Vid(t.url, t.title, t.artist, t.artistUrl, t.thumb, t.duration)
        prefetchNext()
        // On ne garde pas indéfiniment les titres déjà joués dans le lecteur
        if (p.currentMediaItemIndex > 25) {
            p.removeMediaItems(0, 15)
            for (k in 0 until 15) inPlayer.removeAt(0)
        }
        Playback.notifyChanged()
    }

    /** Fin de la liste du lecteur. Renvoie true si on continue. */
    fun onEnded(): Boolean {
        if (!active) return false
        val n = nextIndex() ?: return false
        load(n, now = true)
        return true
    }

    fun next() {
        val p = player
        if (p.hasNextMediaItem()) p.seekToNextMediaItem()
        else nextIndex()?.let { load(it, now = true) }
    }

    fun previous() {
        val p = player
        when {
            p.currentPosition > 3000 || index == 0 -> p.seekTo(0)
            p.hasPreviousMediaItem() && inPlayer.getOrNull(p.currentMediaItemIndex - 1) == index - 1 -> p.seekToPreviousMediaItem()
            else -> load(index - 1, now = true)
        }
    }

    fun jumpTo(i: Int) = load(i, now = true)

    /** Après un changement d'ordre : on retire du lecteur ce qui était préparé après le titre en cours. */
    private fun resyncAfterCurrent() {
        val p = player
        val ci = p.currentMediaItemIndex
        if (p.mediaItemCount > ci + 1) p.removeMediaItems(ci + 1, p.mediaItemCount)
        val keep = ci + 1
        for (k in inPlayer.indices) if (k < keep - 1) inPlayer[k] = -1
        while (inPlayer.size > keep) inPlayer.removeAt(inPlayer.size - 1)
        if (inPlayer.isNotEmpty()) inPlayer[inPlayer.size - 1] = index
        generation++
        resolving.clear()
        prefetchNext()
        Playback.notifyChanged()
    }

    fun toggleShuffle() {
        if (!active) return
        val cur = current ?: return
        shuffle = !shuffle
        if (shuffle) {
            tracks = listOf(cur) + (original - cur).shuffled()
            index = 0
        } else {
            tracks = original
            index = original.indexOf(cur).coerceAtLeast(0)
        }
        resyncAfterCurrent()
    }

    fun cycleRepeat() {
        repeat = when (repeat) {
            Repeat.OFF -> Repeat.ALL
            Repeat.ALL -> Repeat.ONE
            Repeat.ONE -> Repeat.OFF
        }
        player.repeatMode = if (repeat == Repeat.ONE) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        if (active) prefetchNext()
        Playback.notifyChanged()
    }

    fun insertNext(t: Track) {
        if (!active) return play(listOf(t), 0)
        tracks = tracks.toMutableList().apply { add(index + 1, t) }
        original = original + t
        resyncAfterCurrent()
    }

    fun addLast(t: Track) {
        if (!active) return play(listOf(t), 0)
        tracks = tracks + t
        original = original + t
        prefetchNext()
        Playback.notifyChanged()
    }

    fun upcoming(): List<Pair<Int, Track>> = tracks.withIndex().drop(index + 1).map { it.index to it.value }
}
