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

    class Video(val url: String, val title: String, val uploader: String?, val thumb: String?, val duration: Long)

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
    @Synchronized fun watched(): List<Video> = objects("watched").map {
        Video(it.optString("url"), it.optString("title"), it.str("uploader"), it.str("thumb"), it.optLong("duration"))
    }

    @Synchronized fun addWatched(v: Video) {
        val list = listOf(v) + watched().filterNot { it.url == v.url }.take(49)
        put("watched", JSONArray(list.map {
            JSONObject().put("url", it.url).put("title", it.title).put("uploader", it.uploader)
                .put("thumb", it.thumb).put("duration", it.duration)
        }))
    }

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
