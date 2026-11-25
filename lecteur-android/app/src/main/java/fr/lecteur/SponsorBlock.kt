package fr.lecteur

import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

/** Passages sponsorisés à sauter (base collaborative sponsor.ajay.app). */
object SponsorBlock {
    class Segment(val start: Long, val end: Long, val category: String)

    @Volatile private var segments: List<Segment> = emptyList()
    @Volatile private var forId: String? = null

    fun videoId(url: String): String? {
        val u = Uri.parse(url)
        u.getQueryParameter("v")?.let { return it }
        if (u.host.orEmpty().contains("youtu.be")) return u.lastPathSegment
        val seg = u.pathSegments
        val i = seg.indexOfFirst { it == "shorts" || it == "live" || it == "embed" }
        return if (i >= 0 && i + 1 < seg.size) seg[i + 1] else null
    }

    fun clear() {
        segments = emptyList()
        forId = null
    }

    fun load(scope: CoroutineScope, mediaId: String) {
        clear()
        if (!Settings.sponsorBlock) return
        val id = videoId(mediaId) ?: return
        val cats = Settings.sbCategories.map { it.first }.filter { Settings.sbEnabled(it) }
        if (cats.isEmpty()) return
        forId = mediaId
        scope.launch {
            val list = withContext(Dispatchers.IO) {
                try {
                    val url = Uri.parse("https://sponsor.ajay.app/api/skipSegments").buildUpon()
                        .appendQueryParameter("videoID", id)
                        .appendQueryParameter("categories", JSONArray(cats).toString())
                        .build().toString()
                    val req = okhttp3.Request.Builder().url(url).header("User-Agent", "Lecteur").build()
                    DownloaderImpl.instance.client.newCall(req).execute().use { r ->
                        if (!r.isSuccessful) return@use emptyList<Segment>()
                        val arr = JSONArray(r.body?.string() ?: "[]")
                        (0 until arr.length()).mapNotNull { i ->
                            val o = arr.optJSONObject(i) ?: return@mapNotNull null
                            val s = o.optJSONArray("segment") ?: return@mapNotNull null
                            Segment(
                                (s.optDouble(0) * 1000).toLong(), (s.optDouble(1) * 1000).toLong(),
                                o.optString("category")
                            )
                        }.filter { it.end > it.start + 1000 }
                    }
                } catch (_: Exception) {
                    emptyList()
                }
            }
            if (forId == mediaId) segments = list
        }
    }

    /** Renvoie le libellé du passage sauté, ou null. */
    fun check(p: androidx.media3.common.Player): String? {
        val segs = segments
        if (segs.isEmpty() || p.currentMediaItem?.mediaId != forId) return null
        val pos = p.currentPosition
        val s = segs.firstOrNull { pos >= it.start && pos < it.end - 700 } ?: return null
        p.seekTo(s.end)
        return Settings.sbCategories.firstOrNull { it.first == s.category }?.second ?: "Passage"
    }
}
