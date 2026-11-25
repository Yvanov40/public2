package fr.lecteur

import android.net.Uri
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

data class Podcast(val feedUrl: String, val title: String, val author: String?, val artwork: String?)

data class Episode(
    val title: String,
    val audioUrl: String,
    val date: Long,
    val duration: Long,
    val image: String?,
    val podcastTitle: String,
    val podcastArt: String?,
    val feedUrl: String
)

/** Podcasts : recherche dans l'annuaire Apple et lecture des flux RSS (Acast, etc.). */
object Pod {
    private val client get() = DownloaderImpl.instance.client

    private fun get(url: String): String {
        val req = okhttp3.Request.Builder().url(url).header("User-Agent", DownloaderImpl.USER_AGENT).build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            return r.body?.string() ?: throw IOException("réponse vide")
        }
    }

    suspend fun search(term: String): List<Podcast> = withContext(Dispatchers.IO) {
        val url = Uri.parse("https://itunes.apple.com/search").buildUpon()
            .appendQueryParameter("media", "podcast")
            .appendQueryParameter("entity", "podcast")
            .appendQueryParameter("country", "FR")
            .appendQueryParameter("limit", "15")
            .appendQueryParameter("term", term)
            .build().toString()
        val arr = JSONObject(get(url)).optJSONArray("results") ?: return@withContext emptyList()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val feed = o.optString("feedUrl").ifEmpty { return@mapNotNull null }
            Podcast(
                feed, o.optString("collectionName"), o.optString("artistName").ifEmpty { null },
                o.optString("artworkUrl600").ifEmpty { o.optString("artworkUrl100") }.ifEmpty { null }
            )
        }
    }

    suspend fun feed(url: String): Pair<Podcast, List<Episode>> = withContext(Dispatchers.IO) { parse(url, get(url)) }

    private fun parse(feedUrl: String, xml: String): Pair<Podcast, List<Episode>> {
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(xml.reader())

        var title = ""; var author: String? = null; var art: String? = null
        val eps = mutableListOf<Triple<MutableMap<String, String>, String?, Unit>>()
        var item: MutableMap<String, String>? = null
        var inImage = false
        val text = StringBuilder()

        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> {
                    text.setLength(0)
                    when (p.name) {
                        "item", "entry" -> item = mutableMapOf()
                        "image" -> if (item == null) inImage = true
                        "itunes:image" -> p.getAttributeValue(null, "href")?.let {
                            if (item != null) item!!["image"] = it else if (art == null) art = it
                        }
                        "enclosure" -> item?.let { m ->
                            p.getAttributeValue(null, "url")?.let { m["url"] = it }
                            p.getAttributeValue(null, "type")?.let { m["type"] = it }
                        }
                        "media:content" -> item?.let { m ->
                            if (m["url"] == null) p.getAttributeValue(null, "url")?.let { m["url"] = it }
                        }
                    }
                }
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> text.append(p.text)
                XmlPullParser.END_TAG -> {
                    val t = text.toString().trim()
                    val m = item
                    when {
                        p.name == "item" || p.name == "entry" -> {
                            if (m != null) eps += Triple(m, null, Unit)
                            item = null
                        }
                        p.name == "image" -> inImage = false
                        m != null -> when (p.name) {
                            "title" -> if (t.isNotEmpty()) m["title"] = t
                            "pubDate", "published" -> m["date"] = t
                            "itunes:duration" -> m["duration"] = t
                        }
                        inImage && p.name == "url" -> if (t.isNotEmpty()) art = art ?: t
                        p.name == "title" && title.isEmpty() -> title = t
                        p.name == "itunes:author" && author == null -> author = t.ifEmpty { null }
                    }
                    text.setLength(0)
                }
            }
            ev = p.next()
        }

        val podcast = Podcast(feedUrl, title.ifEmpty { feedUrl }, author, art)
        val episodes = eps.mapNotNull { (m, _, _) ->
            val audio = m["url"] ?: return@mapNotNull null
            Episode(
                m["title"] ?: "Épisode", audio, parseDate(m["date"]), parseDuration(m["duration"]),
                m["image"], podcast.title, art, feedUrl
            )
        }
        return podcast to episodes
    }

    private val dateFormats = listOf(
        DateTimeFormatter.RFC_1123_DATE_TIME,
        DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss Z", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss zzz", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm Z", Locale.ENGLISH),
        DateTimeFormatter.ISO_OFFSET_DATE_TIME
    )

    private fun parseDate(s: String?): Long {
        if (s.isNullOrBlank()) return 0
        val v = s.trim().replace(Regex("\\s+"), " ")
        for (f in dateFormats) {
            try {
                return ZonedDateTime.parse(v, f).toInstant().toEpochMilli()
            } catch (_: Exception) {
            }
        }
        return 0
    }

    private fun parseDuration(s: String?): Long {
        if (s.isNullOrBlank()) return 0
        val parts = s.trim().split(":").map { it.trim().toDoubleOrNull() ?: return 0 }
        return parts.fold(0.0) { acc, x -> acc * 60 + x }.toLong()
    }

    fun ago(ms: Long): String? =
        if (ms <= 0) null
        else Yt.ago(java.time.Instant.ofEpochMilli(ms).atOffset(java.time.ZoneOffset.UTC))

    fun mediaId(e: Episode) = "pod:" + e.audioUrl
}
