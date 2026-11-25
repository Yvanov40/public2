package fr.lecteur

import android.net.Uri
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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

    /** Recherche dans deux annuaires (Apple Podcasts et fyyd), résultats fusionnés. */
    suspend fun search(term: String): List<Podcast> = coroutineScope {
        val apple = async(Dispatchers.IO) { runCatching { searchApple(term) }.getOrDefault(emptyList()) }
        val fyyd = async(Dispatchers.IO) { runCatching { searchFyyd(term) }.getOrDefault(emptyList()) }
        val a = apple.await()
        val f = fyyd.await()
        if (a.isEmpty() && f.isEmpty()) {
            // Les deux ont échoué ou rien trouvé : on relance Apple pour remonter l'erreur éventuelle
            return@coroutineScope withContext(Dispatchers.IO) { searchApple(term) }
        }
        val seen = HashSet<String>()
        val out = mutableListOf<Podcast>()
        // Entremêle les deux sources, sans doublons (même flux ou même titre + auteur)
        for (i in 0 until maxOf(a.size, f.size)) {
            for (p in listOfNotNull(a.getOrNull(i), f.getOrNull(i))) {
                val k1 = norm(p.feedUrl)
                val k2 = (p.title + "|" + p.author.orEmpty()).lowercase().trim()
                if (seen.add(k1) && seen.add(k2)) out += p
            }
        }
        out
    }

    private fun norm(u: String) = u.lowercase().removePrefix("https://").removePrefix("http://")
        .removePrefix("www.").trimEnd('/')

    private fun searchApple(term: String): List<Podcast> {
        val url = Uri.parse("https://itunes.apple.com/search").buildUpon()
            .appendQueryParameter("media", "podcast")
            .appendQueryParameter("entity", "podcast")
            .appendQueryParameter("country", "FR")
            .appendQueryParameter("limit", "20")
            .appendQueryParameter("term", term)
            .build().toString()
        val arr = JSONObject(get(url)).optJSONArray("results") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val feed = o.optString("feedUrl").ifEmpty { return@mapNotNull null }
            Podcast(
                feed, o.optString("collectionName"), o.optString("artistName").ifEmpty { null },
                o.optString("artworkUrl600").ifEmpty { o.optString("artworkUrl100") }.ifEmpty { null }
            )
        }
    }

    private fun searchFyyd(term: String): List<Podcast> {
        val url = Uri.parse("https://api.fyyd.de/0.2/search/podcast").buildUpon()
            .appendQueryParameter("title", term)
            .appendQueryParameter("count", "20")
            .build().toString()
        val arr = JSONObject(get(url)).optJSONArray("data") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val feed = o.optString("xmlURL").ifEmpty { return@mapNotNull null }
            Podcast(
                feed, o.optString("title").ifEmpty { feed }, o.optString("author").ifEmpty { null },
                o.optString("imgURL").ifEmpty { o.optString("layoutImageURL") }.ifEmpty { null }
            )
        }
    }

    /**
     * Trouve le(s) flux RSS derrière un lien : flux direct, page Apple Podcasts,
     * ou page web qui annonce son flux (balise <link rel="alternate">).
     */
    suspend fun discover(input: String): List<String> = withContext(Dispatchers.IO) {
        val url = if (input.startsWith("http")) input else "https://$input"
        val uri = Uri.parse(url)
        // Lien Apple Podcasts : …/id1234567890
        if (uri.host.orEmpty().contains("apple.com")) {
            Regex("id(\\d{5,})").find(url)?.groupValues?.get(1)?.let { id ->
                val arr = JSONObject(get("https://itunes.apple.com/lookup?id=$id&entity=podcast"))
                    .optJSONArray("results")
                val feed = arr?.optJSONObject(0)?.optString("feedUrl").orEmpty()
                if (feed.isNotEmpty()) return@withContext listOf(feed)
            }
        }
        val body = get(url)
        val head = body.take(2000).lowercase()
        if (head.contains("<rss") || head.contains("<feed") || head.contains("<rdf:rdf")) return@withContext listOf(url)
        val links = Regex("<link[^>]+>", RegexOption.IGNORE_CASE).findAll(body).map { it.value }
            .filter { tag ->
                val t = tag.lowercase()
                t.contains("alternate") && (t.contains("application/rss+xml") || t.contains("application/atom+xml"))
            }
            .mapNotNull { tag -> Regex("href\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1) }
            .map { href -> java.net.URI(url).resolve(href.replace("&amp;", "&")).toString() }
            .distinct()
            .toList()
        links
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
                        "itunes:image" -> {
                            val href = p.getAttributeValue(null, "href")
                            val m = item
                            if (href != null) {
                                if (m != null) m["image"] = href
                                else if (art == null) art = href
                            }
                        }
                        "enclosure" -> {
                            val m = item
                            val u = p.getAttributeValue(null, "url")
                            if (m != null && u != null) m["url"] = u
                        }
                        "media:content" -> {
                            val m = item
                            val u = p.getAttributeValue(null, "url")
                            if (m != null && u != null && m["url"] == null) m["url"] = u
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
                        inImage && p.name == "url" -> { if (t.isNotEmpty() && art == null) art = t }
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
