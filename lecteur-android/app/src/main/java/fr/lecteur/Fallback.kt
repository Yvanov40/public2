package fr.lecteur

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import java.io.IOException

/**
 * Plan B : serveurs relais publics. Deux familles indépendantes :
 * Piped et Invidious. Ils récupèrent la vidéo chez YouTube à notre place et la retransmettent.
 * Utilisé seulement quand la lecture directe échoue.
 */
@OptIn(UnstableApi::class)
object Fallback {
    /** Un serveur relais : famille (piped / invidious) + adresse. */
    data class Relay(val kind: String, val url: String) {
        val label get() = (if (kind == "piped") "Piped" else "Invidious") + " · " + Uri.parse(url).host
    }

    private val pipedDefaults = listOf(
        "https://pipedapi.kavin.rocks",
        "https://pipedapi.adminforge.de",
        "https://api.piped.private.coffee",
        "https://pipedapi.r4fo.com",
        "https://pipedapi.leptons.xyz"
    )
    private val invidiousDefaults = listOf(
        "https://inv.nadeko.net",
        "https://yewtu.be",
        "https://invidious.nerdvpn.de",
        "https://invidious.privacyredirect.com",
        "https://iv.melmac.space"
    )

    @Volatile private var piped: List<String>? = null
    @Volatile private var invidious: List<String>? = null
    @Volatile private var lastGood: Relay? = null

    class Result(val source: MediaSource, val vid: Vid, val info: Playback.Info, val related: List<Vid>)

    private fun get(url: String): String {
        val req = okhttp3.Request.Builder().url(url).header("User-Agent", DownloaderImpl.USER_AGENT).build()
        DownloaderImpl.instance.client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            return r.body?.string() ?: throw IOException("réponse vide")
        }
    }

    private fun pipedList(): List<String> = piped ?: (pipedDefaults + runCatching {
        val arr = JSONArray(get("https://piped-instances.kavin.rocks/"))
        (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("api_url")?.trimEnd('/')?.ifEmpty { null } }
    }.getOrDefault(emptyList())).distinct().also { piped = it }

    /** Liste publique Invidious : seulement les serveurs https qui acceptent l'API. */
    private fun invidiousList(): List<String> = invidious ?: (invidiousDefaults + runCatching {
        val arr = JSONArray(get("https://api.invidious.io/instances.json?sort_by=health"))
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONArray(i)?.optJSONObject(1) ?: return@mapNotNull null
            if (o.optString("type") != "https" || !o.optBoolean("api")) null
            else o.optString("uri").trimEnd('/').ifEmpty { null }
        }
    }.getOrDefault(emptyList())).distinct().also { invidious = it }

    /** Relais en échec récent : mis de côté 30 minutes. */
    private val benched = java.util.concurrent.ConcurrentHashMap<Relay, Long>()

    /**
     * Relais à essayer : le dernier qui a marché, puis les listes publiques à jour
     * (classées par santé), Piped et Invidious en alternance ; la liste intégrée en dernier.
     */
    fun relays(max: Int = 10, includeBenched: Boolean = false): List<Relay> {
        val now = System.currentTimeMillis()
        fun live(list: List<String>, defaults: List<String>) =
            list.filterNot { it in defaults } + defaults.filter { it in list }
        val p = live(pipedList(), pipedDefaults).map { Relay("piped", it) }
        val v = live(invidiousList(), invidiousDefaults).map { Relay("invidious", it) }
        val mixed = mutableListOf<Relay>()
        for (i in 0 until maxOf(p.size, v.size)) {
            v.getOrNull(i)?.let { mixed += it }
            p.getOrNull(i)?.let { mixed += it }
        }
        return (listOfNotNull(lastGood) + mixed).distinct()
            .filter { includeBenched || (benched[it] ?: 0) < now }
            .take(max)
    }

    /** Échec propre à une vidéo (le relais lui-même marche) : on ne le met pas de côté. */
    private class VideoRefused(msg: String) : IOException(msg)

    private fun <T> firstWorking(max: Int = 10, block: (Relay) -> T): T {
        var last: Exception? = null
        for (r in relays(max)) {
            try {
                val res = block(r)
                lastGood = r
                benched.remove(r)
                return res
            } catch (e: VideoRefused) {
                last = e
            } catch (e: Exception) {
                last = e
                benched[r] = System.currentTimeMillis() + 30 * 60_000L
                if (lastGood == r) lastGood = null
            }
        }
        throw IOException("Aucun serveur relais n'a répondu" + (last?.message?.let { " ($it)" } ?: ""))
    }

    /** Vérifie qu'un fichier se télécharge vraiment (premiers 64 ko). */
    private fun probe(url: String): Int {
        val req = okhttp3.Request.Builder().url(url)
            .header("User-Agent", DownloaderImpl.USER_AGENT)
            .header("Range", "bytes=0-65535")
            .build()
        DownloaderImpl.instance.client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw VideoRefused("fichier : HTTP ${resp.code}")
            return resp.body?.bytes()?.size ?: 0
        }
    }

    fun videoId(url: String) = SponsorBlock.videoId(url)

    private fun abs(base: String, path: String?) = when {
        path.isNullOrEmpty() -> null
        path.startsWith("http") -> path
        path.startsWith("//") -> "https:$path"
        else -> base.trimEnd('/') + path
    }

    private fun yt(path: String?) = abs("https://www.youtube.com", path)

    /** Description commune d'une vidéo, quelle que soit la famille de relais. */
    private class Streams(
        val title: String, val uploader: String?, val uploaderUrl: String?, val thumb: String?, val duration: Long,
        val audio: String?, val video: String?, val muxed: String?, val hls: String?,
        val subs: List<Triple<String, String, String>>, // url, langue, libellé (vtt)
        val description: String?, val chapters: List<Pair<Long, String>>, val related: List<Vid>
    )

    /** Construit la source de lecture d'une vidéo via un serveur relais. */
    fun build(
        videoId: String, mediaId: String, audioOnly: Boolean,
        title: String? = null, artist: String? = null, artwork: String? = null
    ): Result {
        // Chaque relais est vérifié sur le fichier lui-même : certains donnent les infos mais refusent le flux
        val s = firstWorking(14) { r ->
            val st = if (r.kind == "piped") piped(r.url, videoId) else invidious(r.url, videoId)
            val first = if (audioOnly) st.audio ?: st.muxed else st.video ?: st.muxed ?: st.audio
            if (first != null) probe(first) else if (st.hls == null) throw VideoRefused("aucun flux")
            st
        }
        return toResult(s, videoId, mediaId, audioOnly, title, artist, artwork)
    }

    private fun toResult(
        s: Streams, videoId: String, mediaId: String, audioOnly: Boolean,
        title: String?, artist: String?, artwork: String?
    ): Result {
        val ds = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent(DownloaderImpl.USER_AGENT)
        val name = title ?: s.title
        val uploader = artist ?: s.uploader
        val thumb = artwork ?: s.thumb
        val meta = MediaMetadata.Builder().setTitle(name).setArtist(uploader)
            .setArtworkUri(thumb?.let(Uri::parse)).build()
        fun item(url: String) = MediaItem.Builder().setMediaId(mediaId).setMediaMetadata(meta).setUri(url)
        fun progressive(url: String) = ProgressiveMediaSource.Factory(ds).createMediaSource(item(url).build())

        val parts = mutableListOf<MediaSource>()
        when {
            audioOnly && s.audio != null -> parts += progressive(s.audio)
            s.video != null && s.audio != null -> {
                parts += progressive(s.video)
                parts += progressive(s.audio)
            }
            s.muxed != null -> parts += progressive(s.muxed)
            s.audio != null -> parts += progressive(s.audio)
            s.hls != null -> parts += androidx.media3.exoplayer.hls.HlsMediaSource.Factory(ds)
                .createMediaSource(item(s.hls).setMimeType(MimeTypes.APPLICATION_M3U8).build())
        }
        if (parts.isEmpty()) throw IOException("aucun flux disponible sur le serveur relais")
        if (!audioOnly) s.subs.forEach { (url, lang, label) ->
            Sources.subtitleFrom(url, MimeTypes.TEXT_VTT, lang, label, ds)?.let { parts += it }
        }
        val source = if (parts.size == 1) parts[0] else MergingMediaSource(*parts.toTypedArray())
        val vid = Vid("https://www.youtube.com/watch?v=$videoId", name, uploader, s.uploaderUrl, thumb, s.duration)
        return Result(source, vid, Playback.Info(s.description, true, s.chapters), s.related)
    }

    // ——— Piped ———

    private fun piped(api: String, videoId: String): Streams {
        val o = JSONObject(get("$api/streams/$videoId"))
        if (o.has("error")) throw IOException(o.optString("error"))
        val audios = o.optJSONArray("audioStreams") ?: JSONArray()
        val videos = o.optJSONArray("videoStreams") ?: JSONArray()
        val audio = (0 until audios.length()).mapNotNull { audios.optJSONObject(it) }
            .filter { it.optString("url").isNotEmpty() }
            .sortedWith(compareByDescending<JSONObject> { it.optString("mimeType").contains("mp4") }.thenByDescending { it.optInt("bitrate") })
            .firstOrNull()?.optString("url")
        val all = (0 until videos.length()).mapNotNull { videos.optJSONObject(it) }.filter { it.optString("url").isNotEmpty() }
        fun height(v: JSONObject) = v.optInt("height").takeIf { it > 0 } ?: v.optString("quality").substringBefore('p').toIntOrNull() ?: 0
        val video = all.filter { it.optBoolean("videoOnly") && height(it) in 1..Settings.maxHeight }
            .sortedWith(compareByDescending<JSONObject> { it.optString("codec").startsWith("avc") }.thenByDescending { height(it) })
            .firstOrNull()?.optString("url")
        val muxed = all.filter { !it.optBoolean("videoOnly") }.maxByOrNull { height(it) }?.optString("url")
        val subs = o.optJSONArray("subtitles")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                .filter { it.optString("url").isNotEmpty() && it.optString("mimeType").contains("vtt") }
                .map { Triple(it.optString("url"), it.optString("code"), it.optString("name") + if (it.optBoolean("autoGenerated")) " (auto)" else "") }
        }.orEmpty()
        val chapters = o.optJSONArray("chapters")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { it.optLong("start") * 1000 to it.optString("title") }
        }.orEmpty()
        val related = o.optJSONArray("relatedStreams")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.filter { it.optString("type", "stream") == "stream" }.mapNotNull { j ->
                val url = yt(j.optString("url")) ?: return@mapNotNull null
                Vid(url, j.optString("title"), j.optString("uploaderName").ifEmpty { null }, yt(j.optString("uploaderUrl")),
                    j.optString("thumbnail").ifEmpty { null }, j.optLong("duration"),
                    listOfNotNull(j.optString("uploaderName").ifEmpty { null }, Yt.views(j.optLong("views", -1))).joinToString(" · "))
            }
        }.orEmpty()
        return Streams(
            o.optString("title"), o.optString("uploader").ifEmpty { null }, yt(o.optString("uploaderUrl")),
            o.optString("thumbnailUrl").ifEmpty { null }, o.optLong("duration"),
            audio, video, muxed, o.optString("hls").ifEmpty { null }, subs,
            o.optString("description").ifEmpty { null }, chapters, related
        )
    }

    // ——— Invidious ———

    private fun invThumb(base: String, arr: JSONArray?): String? {
        if (arr == null) return null
        val list = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        val best = list.firstOrNull { it.optString("quality") == "medium" || it.optString("quality") == "high" } ?: list.firstOrNull()
        return abs(base, best?.optString("url"))
    }

    private fun invidious(base: String, videoId: String): Streams {
        // local=true : les fichiers passent aussi par le serveur relais
        val o = JSONObject(get("$base/api/v1/videos/$videoId?local=true"))
        if (o.has("error")) throw IOException(o.optString("error"))
        val adaptive = o.optJSONArray("adaptiveFormats")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()
            .filter { it.optString("url").isNotEmpty() }
        val audio = adaptive.filter { it.optString("type").startsWith("audio/") }
            .sortedWith(compareByDescending<JSONObject> { it.optString("type").contains("mp4") }.thenByDescending { it.optString("bitrate").toLongOrNull() ?: 0 })
            .firstOrNull()?.let { abs(base, it.optString("url")) }
        fun height(v: JSONObject) = v.optString("resolution").substringBefore('p').toIntOrNull()
            ?: v.optString("qualityLabel").substringBefore('p').toIntOrNull() ?: 0
        val video = adaptive.filter { it.optString("type").startsWith("video/") && height(it) in 1..Settings.maxHeight }
            .sortedWith(compareByDescending<JSONObject> { it.optString("type").contains("avc1") }.thenByDescending { height(it) })
            .firstOrNull()?.let { abs(base, it.optString("url")) }
        val muxed = o.optJSONArray("formatStreams")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()
            .maxByOrNull { height(it) }?.let { abs(base, it.optString("url")) }
        val subs = o.optJSONArray("captions")?.let { a ->
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { c ->
                val url = abs(base, c.optString("url")) ?: return@mapNotNull null
                Triple(url, c.optString("language_code"), c.optString("label"))
            }
        }.orEmpty()
        val related = o.optJSONArray("recommendedVideos")?.let { a ->
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { j ->
                val id = j.optString("videoId").ifEmpty { null } ?: return@mapNotNull null
                Vid("https://www.youtube.com/watch?v=$id", j.optString("title"), j.optString("author").ifEmpty { null },
                    yt(j.optString("authorUrl")), invThumb(base, j.optJSONArray("videoThumbnails")), j.optLong("lengthSeconds"),
                    listOfNotNull(j.optString("author").ifEmpty { null }, Yt.views(j.optLong("viewCount", -1))).joinToString(" · "))
            }
        }.orEmpty()
        return Streams(
            o.optString("title"), o.optString("author").ifEmpty { null }, yt(o.optString("authorUrl")),
            invThumb(base, o.optJSONArray("videoThumbnails")), o.optLong("lengthSeconds"),
            audio, video, muxed, o.optString("hlsUrl").ifEmpty { null }?.let { abs(base, it) }, subs,
            o.optString("descriptionHtml").ifEmpty { o.optString("description") }.ifEmpty { null }, emptyList(), related
        )
    }

    /** Adresse du son d'une vidéo via un relais (pour l'enceinte Chromecast). */
    fun audioUrl(videoId: String): String = firstWorking(14) { r ->
        val s = if (r.kind == "piped") piped(r.url, videoId) else invidious(r.url, videoId)
        val url = s.audio ?: s.muxed ?: throw VideoRefused("aucun flux")
        probe(url)
        url
    }

    // ——— Recherche ———

    /** Recherche de vidéos via un serveur relais, au même format que NewPipe. */
    fun search(q: String): List<StreamInfoItem> = firstWorking { r ->
        if (r.kind == "piped") {
            val o = JSONObject(get("${r.url}/search?q=${Uri.encode(q)}&filter=videos"))
            val items = o.optJSONArray("items") ?: JSONArray()
            (0 until items.length()).mapNotNull { items.optJSONObject(it) }
                .filter { it.optString("type", "stream") == "stream" }
                .mapNotNull { j ->
                    item(
                        yt(j.optString("url")) ?: return@mapNotNull null, j.optString("title"), j.optString("uploaderName"),
                        yt(j.optString("uploaderUrl")), j.optLong("duration"), j.optLong("views", -1),
                        j.optString("thumbnail").ifEmpty { null }, j.optString("uploadedDate").ifEmpty { null }
                    )
                }
        } else {
            val arr = JSONArray(get("${r.url}/api/v1/search?q=${Uri.encode(q)}&type=video"))
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                .filter { it.optString("type") == "video" }
                .mapNotNull { j ->
                    val id = j.optString("videoId").ifEmpty { null } ?: return@mapNotNull null
                    item(
                        "https://www.youtube.com/watch?v=$id", j.optString("title"), j.optString("author"),
                        yt(j.optString("authorUrl")), j.optLong("lengthSeconds"), j.optLong("viewCount", -1),
                        invThumb(r.url, j.optJSONArray("videoThumbnails")), j.optString("publishedText").ifEmpty { null }
                    )
                }
        }
    }

    private fun item(
        url: String, title: String, uploader: String?, uploaderUrl: String?, duration: Long, views: Long,
        thumb: String?, date: String?
    ) = StreamInfoItem(0, url, title, StreamType.VIDEO_STREAM).apply {
        uploaderName = uploader
        this.uploaderUrl = uploaderUrl
        this.duration = duration
        viewCount = views
        thumb?.let { thumbnails = listOf(Image(it, Image.HEIGHT_UNKNOWN, Image.WIDTH_UNKNOWN, Image.ResolutionLevel.UNKNOWN)) }
        date?.let { textualUploadDate = it }
    }

    // ——— Diagnostic ———

    /** Teste un relais précis : récupération des infos puis des premiers octets de l'audio. */
    fun test(r: Relay, videoId: String): String {
        val s = if (r.kind == "piped") piped(r.url, videoId) else invidious(r.url, videoId)
        val url = s.audio ?: s.muxed ?: throw IOException("aucun flux")
        return "OK (${probe(url) / 1024} ko reçus)"
    }
}
