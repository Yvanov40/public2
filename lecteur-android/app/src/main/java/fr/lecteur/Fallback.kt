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
 * Plan B : serveurs relais publics Piped. Ils récupèrent la vidéo chez YouTube à notre place
 * et la retransmettent. Utilisé seulement quand la lecture directe échoue.
 */
@OptIn(UnstableApi::class)
object Fallback {
    private val defaults = listOf(
        "https://pipedapi.kavin.rocks",
        "https://pipedapi.adminforge.de",
        "https://api.piped.private.coffee",
        "https://pipedapi.r4fo.com",
        "https://pipedapi.leptons.xyz"
    )
    @Volatile private var instances: List<String>? = null
    @Volatile private var lastGood: String? = null

    class Result(val source: MediaSource, val vid: Vid, val info: Playback.Info, val related: List<Vid>)

    private fun get(url: String): String {
        val req = okhttp3.Request.Builder().url(url).header("User-Agent", DownloaderImpl.USER_AGENT).build()
        DownloaderImpl.instance.client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            return r.body?.string() ?: throw IOException("réponse vide")
        }
    }

    /** Liste des serveurs : le dernier qui a marché d'abord, puis la liste publique. */
    private fun apis(): List<String> {
        val list = instances ?: (defaults + runCatching {
            val arr = JSONArray(get("https://piped-instances.kavin.rocks/"))
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("api_url")?.trimEnd('/')?.ifEmpty { null } }
        }.getOrDefault(emptyList())).distinct().also { instances = it }
        return (listOfNotNull(lastGood) + list).distinct().take(8)
    }

    private fun <T> firstWorking(block: (String) -> T): T {
        var last: Exception? = null
        for (api in apis()) {
            try {
                val r = block(api)
                lastGood = api
                return r
            } catch (e: Exception) {
                last = e
            }
        }
        throw IOException("Aucun serveur relais n'a répondu" + (last?.message?.let { " ($it)" } ?: ""))
    }

    fun videoId(url: String) = SponsorBlock.videoId(url)

    private fun abs(path: String?) = when {
        path.isNullOrEmpty() -> null
        path.startsWith("http") -> path
        else -> "https://www.youtube.com$path"
    }

    private fun vidOf(o: JSONObject): Vid? {
        val url = abs(o.optString("url")) ?: return null
        return Vid(
            url, o.optString("title"), o.optString("uploaderName").ifEmpty { null },
            abs(o.optString("uploaderUrl")), o.optString("thumbnail").ifEmpty { null }, o.optLong("duration"),
            listOfNotNull(o.optString("uploaderName").ifEmpty { null }, Yt.views(o.optLong("views", -1))).joinToString(" · ")
        )
    }

    /** Construit la source de lecture d'une vidéo via un serveur relais. */
    fun build(
        videoId: String, mediaId: String, audioOnly: Boolean,
        title: String? = null, artist: String? = null, artwork: String? = null
    ): Result {
        val o = firstWorking { api ->
            JSONObject(get("$api/streams/$videoId")).also { if (it.has("error")) throw IOException(it.optString("error")) }
        }
        val ds = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent(DownloaderImpl.USER_AGENT)
        val name = title ?: o.optString("title")
        val uploader = artist ?: o.optString("uploader").ifEmpty { null }
        val thumb = artwork ?: o.optString("thumbnailUrl").ifEmpty { null }
        val meta = MediaMetadata.Builder().setTitle(name).setArtist(uploader)
            .setArtworkUri(thumb?.let(Uri::parse)).build()
        val item = MediaItem.Builder().setMediaId(mediaId).setMediaMetadata(meta)

        fun progressive(url: String) = ProgressiveMediaSource.Factory(ds).createMediaSource(item.setUri(url).build())

        val audios = o.optJSONArray("audioStreams") ?: JSONArray()
        val videos = o.optJSONArray("videoStreams") ?: JSONArray()
        val audio = (0 until audios.length()).mapNotNull { audios.optJSONObject(it) }
            .filter { it.optString("url").isNotEmpty() }
            .sortedWith(compareByDescending<JSONObject> { it.optString("mimeType").contains("mp4") }.thenByDescending { it.optInt("bitrate") })
            .firstOrNull()
        val all = (0 until videos.length()).mapNotNull { videos.optJSONObject(it) }.filter { it.optString("url").isNotEmpty() }
        fun height(v: JSONObject) = v.optInt("height").takeIf { it > 0 }
            ?: v.optString("quality").substringBefore('p').toIntOrNull() ?: 0
        val videoOnly = all.filter { it.optBoolean("videoOnly") && height(it) in 1..Settings.maxHeight }
            .sortedWith(
                compareByDescending<JSONObject> { it.optString("codec").startsWith("avc") }
                    .thenByDescending { height(it) }
            ).firstOrNull()
        val muxed = all.filter { !it.optBoolean("videoOnly") }.maxByOrNull { height(it) }

        val parts = mutableListOf<MediaSource>()
        when {
            audioOnly && audio != null -> parts += progressive(audio.getString("url"))
            videoOnly != null && audio != null -> {
                parts += progressive(videoOnly.getString("url"))
                parts += progressive(audio.getString("url"))
            }
            muxed != null -> parts += progressive(muxed.getString("url"))
            audio != null -> parts += progressive(audio.getString("url"))
            else -> o.optString("hls").ifEmpty { null }?.let { hls ->
                parts += androidx.media3.exoplayer.hls.HlsMediaSource.Factory(ds)
                    .createMediaSource(item.setUri(hls).setMimeType(MimeTypes.APPLICATION_M3U8).build())
            }
        }
        if (parts.isEmpty()) throw IOException("aucun flux disponible sur le serveur relais")

        if (!audioOnly) {
            val subs = o.optJSONArray("subtitles") ?: JSONArray()
            for (i in 0 until subs.length()) {
                val s = subs.optJSONObject(i) ?: continue
                val url = s.optString("url").ifEmpty { null } ?: continue
                val mime = when {
                    s.optString("mimeType").contains("vtt") -> MimeTypes.TEXT_VTT
                    s.optString("mimeType").contains("ttml") || s.optString("mimeType").contains("xml") -> MimeTypes.APPLICATION_TTML
                    else -> continue
                }
                val label = s.optString("name").ifEmpty { s.optString("code") } + if (s.optBoolean("autoGenerated")) " (auto)" else ""
                Sources.subtitleFrom(url, mime, s.optString("code").ifEmpty { null }, label, ds)?.let { parts += it }
            }
        }

        val source = if (parts.size == 1) parts[0] else MergingMediaSource(*parts.toTypedArray())
        val vid = Vid(
            "https://www.youtube.com/watch?v=$videoId", name, uploader, abs(o.optString("uploaderUrl")),
            thumb, o.optLong("duration")
        )
        val chapters = o.optJSONArray("chapters")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { it.optLong("start") * 1000 to it.optString("title") }
        }.orEmpty()
        val info = Playback.Info(o.optString("description").ifEmpty { null }, true, chapters)
        val related = o.optJSONArray("relatedStreams")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.filter { it.optString("type", "stream") == "stream" }.mapNotNull(::vidOf)
        }.orEmpty()
        return Result(source, vid, info, related)
    }

    /** Recherche de vidéos via un serveur relais, au même format que NewPipe. */
    fun search(q: String): List<StreamInfoItem> {
        val o = firstWorking { api ->
            JSONObject(get("$api/search?q=${Uri.encode(q)}&filter=videos"))
        }
        val items = o.optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).mapNotNull { items.optJSONObject(it) }
            .filter { it.optString("type", "stream") == "stream" }
            .mapNotNull { j ->
                val url = abs(j.optString("url")) ?: return@mapNotNull null
                StreamInfoItem(0, url, j.optString("title"), StreamType.VIDEO_STREAM).apply {
                    uploaderName = j.optString("uploaderName")
                    uploaderUrl = abs(j.optString("uploaderUrl"))
                    duration = j.optLong("duration")
                    viewCount = j.optLong("views", -1)
                    j.optString("thumbnail").ifEmpty { null }?.let {
                        thumbnails = listOf(Image(it, Image.HEIGHT_UNKNOWN, Image.WIDTH_UNKNOWN, Image.ResolutionLevel.UNKNOWN))
                    }
                    j.optString("uploadedDate").ifEmpty { null }?.let { textualUploadDate = it }
                }
            }
    }
}
