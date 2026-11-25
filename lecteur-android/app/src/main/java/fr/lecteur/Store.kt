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

    private fun positionsMap(): LinkedHashMap<String, Long> = positions ?: LinkedHashMap<String, Long>().also { m ->
        try {
            val o = JSONObject(prefs.getString("positions", "{}")!!)
            o.keys().forEach { k -> m[k] = o.optLong(k) }
        } catch (_: Exception) {
        }
        positions = m
    }

    /** Où en était la lecture (ms) ; 0 = jamais commencé ; -1 = vu / écouté jusqu'au bout. */
    @Synchronized fun position(id: String): Long = positionsMap()[id] ?: 0

    @Synchronized fun setPosition(id: String, ms: Long) {
        val m = positionsMap()
        m.remove(id)
        if (ms != 0L) m[id] = ms // les plus récents à la fin
        // Limite la taille : on garde les 600 dernières entrées
        while (m.size > 600) m.remove(m.keys.first())
        val o = JSONObject()
        m.forEach { (k, v) -> o.put(k, v) }
        prefs.edit().putString("positions", o.toString()).apply()
    }

    /** Date (ms) de la dernière nouveauté vue pour un flux ou une chaîne. */
    @Synchronized fun lastSeen(key: String): Long = prefs.getLong("seen_$key", 0)

    @Synchronized fun setLastSeen(key: String, ms: Long) = prefs.edit().putLong("seen_$key", ms).apply()

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
