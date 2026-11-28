package fr.lecteur

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject

/** Historique (recherches, vidéos lues), téléchargements et dossiers, en local. */
object Store {
    private lateinit var prefs: SharedPreferences
    private val main = Handler(Looper.getMainLooper())

    /** Appelé (sur le fil principal) quand la liste des téléchargements change. */
    var onChange: (() -> Unit)? = null

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("store", Context.MODE_PRIVATE)
        positions = null
        played = null
    }

    class Download(
        val uri: String, val title: String, val uploader: String?,
        val thumb: String?, val video: Boolean, val source: String, val path: String? = null
    )

    private fun arr(key: String) = try {
        JSONArray(prefs.getString(key, "[]"))
    } catch (_: Exception) {
        JSONArray()
    }

    private fun put(key: String, a: JSONArray) = prefs.edit().putString(key, a.toString()).apply()

    private fun strings(key: String): List<String> = arr(key).let { a -> List(a.length()) { a.optString(it) } }

    private fun objects(key: String): List<JSONObject> =
        arr(key).let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }

    private fun JSONObject.str(k: String) = optString(k).ifEmpty { null }

    // ——— Recherches ———
    @Synchronized fun searches(): List<String> = strings("searches")

    @Synchronized fun addSearch(q: String) {
        put("searches", JSONArray(listOf(q) + searches().filterNot { it.equals(q, true) }.take(29)))
    }

    @Synchronized fun removeSearch(q: String) = put("searches", JSONArray(searches().filterNot { it == q }))

    // ——— Vidéos lues ———
    @Synchronized fun watched(): List<Vid> = objects("watched").map {
        Vid(
            it.optString("url"), it.optString("title"), it.str("uploader"), it.str("uploaderUrl"),
            it.str("thumb"), it.optLong("duration")
        )
    }

    @Synchronized fun addWatched(v: Vid) {
        val list = listOf(v) + watched().filterNot { it.url == v.url }.take(49)
        put("watched", JSONArray(list.map {
            JSONObject().put("url", it.url).put("title", it.title).put("uploader", it.uploader)
                .put("uploaderUrl", it.uploaderUrl).put("thumb", it.thumb).put("duration", it.duration)
        }))
    }

    @Synchronized fun clearSearches() = put("searches", JSONArray())

    @Synchronized fun clearWatched() = put("watched", JSONArray())

    // ——— Abonnements ———
    @Synchronized fun subs(): List<Chan> = objects("subs").map {
        Chan(it.optString("url"), it.optString("name"), it.str("avatar"))
    }.sortedBy { it.name.lowercase() }

    @Synchronized fun isSubscribed(url: String) = subs().any { it.url == url }

    @Synchronized fun subscribe(c: Chan) {
        val list = subs().filterNot { it.url == c.url } + c
        put("subs", JSONArray(list.map {
            JSONObject().put("url", it.url).put("name", it.name).put("avatar", it.avatar)
        }))
    }

    @Synchronized fun unsubscribe(url: String) {
        put("subs", JSONArray(subs().filterNot { it.url == url }.map {
            JSONObject().put("url", it.url).put("name", it.name).put("avatar", it.avatar)
        }))
    }

    // ——— Podcasts ———
    @Synchronized fun podcasts(): List<Podcast> = objects("podcasts").map {
        Podcast(it.optString("feed"), it.optString("title"), it.str("author"), it.str("art"))
    }.sortedBy { it.title.lowercase() }

    @Synchronized fun isPodSubscribed(feed: String) = podcasts().any { it.feedUrl == feed }

    private fun savePodcasts(list: List<Podcast>) = put("podcasts", JSONArray(list.map {
        JSONObject().put("feed", it.feedUrl).put("title", it.title).put("author", it.author).put("art", it.artwork)
    }))

    @Synchronized fun subscribePodcast(p: Podcast) = savePodcasts(podcasts().filterNot { it.feedUrl == p.feedUrl } + p)

    @Synchronized fun unsubscribePodcast(feed: String) = savePodcasts(podcasts().filterNot { it.feedUrl == feed })

    private var positions: LinkedHashMap<String, Long>? = null
    private var played: LinkedHashSet<String>? = null

    private fun positionsMap(): LinkedHashMap<String, Long> = positions ?: LinkedHashMap<String, Long>().also { m ->
        try {
            val o = JSONObject(prefs.getString("positions", "{}")!!)
            o.keys().forEach { k -> m[k] = o.optLong(k) }
        } catch (_: Exception) {
        }
        positions = m
    }

    /** Écoutés / vus jusqu'au bout : liste séparée, bien plus longue que les positions. */
    private fun playedSet(): LinkedHashSet<String> = played ?: LinkedHashSet<String>().also { set ->
        try {
            val a = JSONArray(prefs.getString("played", "[]")!!)
            for (i in 0 until a.length()) set += a.getString(i)
        } catch (_: Exception) {
        }
        // Reprise des anciens « -1 » rangés avec les positions
        val m = positionsMap()
        val old = m.filterValues { it < 0 }.keys
        if (old.isNotEmpty()) {
            set += old
            old.forEach { m.remove(it) }
            savePositions(m)
            played = set
            savePlayed(set)
        }
        played = set
    }

    private fun savePositions(m: LinkedHashMap<String, Long>) {
        val o = JSONObject()
        m.forEach { (k, v) -> o.put(k, v) }
        prefs.edit().putString("positions", o.toString()).apply()
    }

    private fun savePlayed(set: LinkedHashSet<String>) {
        while (set.size > 8000) set.remove(set.first())
        prefs.edit().putString("played", JSONArray(set.toList()).toString()).apply()
    }

    /** Où en était la lecture (ms) ; 0 = jamais commencé ; -1 = vu / écouté jusqu'au bout. */
    @Synchronized fun position(id: String): Long =
        if (id in playedSet()) -1 else positionsMap()[id] ?: 0

    @Synchronized fun setPosition(id: String, ms: Long) = setPositions(listOf(id), ms)

    /** Même chose pour plusieurs à la fois (tout marquer comme écouté…). */
    @Synchronized fun setPositions(ids: Collection<String>, ms: Long) {
        val set = playedSet()
        val m = positionsMap()
        ids.forEach { id ->
            m.remove(id)
            set.remove(id)
            if (ms < 0) set += id else if (ms != 0L) m[id] = ms // les plus récents à la fin
        }
        // Limite la taille : on garde les 600 dernières positions
        while (m.size > 600) m.remove(m.keys.first())
        savePositions(m)
        savePlayed(set)
    }

    /** Date (ms) de la dernière nouveauté vue pour un flux ou une chaîne. */
    @Synchronized fun lastSeen(key: String): Long = prefs.getLong("seen_$key", 0)

    @Synchronized fun setLastSeen(key: String, ms: Long) = prefs.edit().putLong("seen_$key", ms).apply()

    // ——— Musique : likes, playlists, écoutés récemment ———
    private fun trackJson(t: Track) = JSONObject().put("url", t.url).put("title", t.title).put("artist", t.artist)
        .put("artistUrl", t.artistUrl).put("thumb", t.thumb).put("duration", t.duration).put("album", t.album)

    private fun trackOf(o: JSONObject) = Track(
        o.optString("url"), o.optString("title"), o.str("artist"), o.str("artistUrl"),
        o.str("thumb"), o.optLong("duration"), o.str("album")
    )

    private fun tracks(key: String): List<Track> = objects(key).map(::trackOf)
    private fun saveTracks(key: String, list: List<Track>) = put(key, JSONArray(list.map(::trackJson)))

    @Synchronized fun likes(): List<Track> = tracks("likes")
    @Synchronized fun isLiked(url: String) = likes().any { it.url == url }
    @Synchronized fun toggleLike(t: Track): Boolean {
        val l = likes()
        return if (l.any { it.url == t.url }) {
            saveTracks("likes", l.filterNot { it.url == t.url }); false
        } else {
            saveTracks("likes", listOf(t) + l); true
        }
    }

    @Synchronized fun recentTracks(): List<Track> = tracks("recentTracks")
    @Synchronized fun addRecentTrack(t: Track) =
        saveTracks("recentTracks", (listOf(t) + recentTracks().filterNot { it.url == t.url }).take(60))

    // Albums et artistes : enregistrés (« Mes albums », « Mes artistes ») et récents
    private fun albumJson(a: Music.Album) = JSONObject().put("url", a.url).put("title", a.title)
        .put("artist", a.artist).put("thumb", a.thumb).put("count", a.count)

    private fun albums(key: String): List<Music.Album> = objects(key).map {
        Music.Album(it.optString("url"), it.optString("title"), it.str("artist"), it.str("thumb"), it.optLong("count", -1))
    }

    private fun saveAlbums(key: String, l: List<Music.Album>) = put(key, JSONArray(l.map(::albumJson)))

    private fun artistJson(a: Music.Artist) = JSONObject().put("url", a.url).put("name", a.name).put("thumb", a.thumb)

    private fun artists(key: String): List<Music.Artist> = objects(key).map {
        Music.Artist(it.optString("url"), it.optString("name"), it.str("thumb"))
    }

    private fun saveArtists(key: String, l: List<Music.Artist>) = put(key, JSONArray(l.map(::artistJson)))

    @Synchronized fun savedAlbums() = albums("savedAlbums")
    @Synchronized fun isAlbumSaved(url: String) = savedAlbums().any { it.url == url }
    @Synchronized fun toggleAlbum(a: Music.Album): Boolean {
        val l = savedAlbums()
        return if (l.any { it.url == a.url }) { saveAlbums("savedAlbums", l.filterNot { it.url == a.url }); false }
        else { saveAlbums("savedAlbums", listOf(a) + l); true }
    }

    @Synchronized fun recentAlbums() = albums("recentAlbums")
    @Synchronized fun addRecentAlbum(a: Music.Album) =
        saveAlbums("recentAlbums", (listOf(a) + recentAlbums().filterNot { it.url == a.url }).take(20))
    @Synchronized fun removeRecentAlbum(url: String) = saveAlbums("recentAlbums", recentAlbums().filterNot { it.url == url })

    @Synchronized fun savedArtists() = artists("savedArtists")
    @Synchronized fun isArtistSaved(url: String) = savedArtists().any { it.url == url }
    @Synchronized fun toggleArtist(a: Music.Artist): Boolean {
        val l = savedArtists()
        return if (l.any { it.url == a.url }) { saveArtists("savedArtists", l.filterNot { it.url == a.url }); false }
        else { saveArtists("savedArtists", listOf(a) + l); true }
    }

    @Synchronized fun recentArtists() = artists("recentArtists")
    @Synchronized fun addRecentArtist(a: Music.Artist) =
        saveArtists("recentArtists", (listOf(a) + recentArtists().filterNot { it.url == a.url }).take(20))
    @Synchronized fun removeRecentArtist(url: String) = saveArtists("recentArtists", recentArtists().filterNot { it.url == url })

    class Playlist(val id: String, val name: String, val tracks: List<Track>)

    @Synchronized fun playlists(): List<Playlist> = objects("playlists").map { o ->
        val arr = o.optJSONArray("tracks") ?: JSONArray()
        Playlist(o.optString("id"), o.optString("name"), (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(::trackOf) })
    }

    private fun savePlaylists(list: List<Playlist>) = put("playlists", JSONArray(list.map { p ->
        JSONObject().put("id", p.id).put("name", p.name).put("tracks", JSONArray(p.tracks.map(::trackJson)))
    }))

    @Synchronized fun createPlaylist(name: String): String {
        val id = System.currentTimeMillis().toString(36)
        savePlaylists(playlists() + Playlist(id, name, emptyList()))
        return id
    }

    @Synchronized fun renamePlaylist(id: String, name: String) =
        savePlaylists(playlists().map { if (it.id == id) Playlist(id, name, it.tracks) else it })

    @Synchronized fun deletePlaylist(id: String) = savePlaylists(playlists().filterNot { it.id == id })

    @Synchronized fun addToPlaylist(id: String, t: Track) =
        savePlaylists(playlists().map { if (it.id == id) Playlist(id, it.name, it.tracks.filterNot { x -> x.url == t.url } + t) else it })

    @Synchronized fun removeFromPlaylist(id: String, index: Int) =
        savePlaylists(playlists().map { if (it.id == id) Playlist(id, it.name, it.tracks.filterIndexed { i, _ -> i != index }) else it })

    // ——— Téléchargements ———
    @Synchronized fun downloads(): List<Download> = objects("downloads").map {
        Download(
            it.optString("uri"), it.optString("title"), it.str("uploader"),
            it.str("thumb"), it.optBoolean("video"), it.optString("source"), it.str("path")
        )
    }

    private fun saveDownloads(list: List<Download>) {
        put("downloads", JSONArray(list.map {
            JSONObject().put("uri", it.uri).put("title", it.title).put("uploader", it.uploader)
                .put("thumb", it.thumb).put("video", it.video).put("source", it.source)
                .put("path", it.path)
        }))
        main.post { onChange?.invoke() }
    }

    @Synchronized fun addDownload(d: Download) = saveDownloads(listOf(d) + downloads())

    @Synchronized fun removeDownload(uri: String) = saveDownloads(downloads().filterNot { it.uri == uri })

    // ——— Dossiers du téléphone ———
    @Synchronized fun folders(): List<String> = strings("folders")

    @Synchronized fun addFolder(uri: String) {
        if (uri !in folders()) put("folders", JSONArray(folders() + uri))
    }

    @Synchronized fun removeFolder(uri: String) = put("folders", JSONArray(folders().filterNot { it == uri }))
}
