package fr.lecteur

import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleExtractor
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeProgressiveDashManifestCreator
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.Stream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.SubtitlesStream
import org.schabi.newpipe.extractor.stream.VideoStream
import java.io.ByteArrayInputStream

/**
 * Construit la source à lire : vidéo seule + audio seul (fusionnés) + sous-titres.
 * Vidéo et audio étant séparés, couper la piste vidéo arrête aussi son téléchargement :
 * c'est ce qui rend la bascule vidéo ⇄ audio instantanée et économe.
 */
@OptIn(UnstableApi::class)
object Sources {
    private const val TAG = "Sources"

    fun build(
        info: StreamInfo, mediaId: String = info.url, audioOnly: Boolean = false,
        title: String? = null, artist: String? = null, artwork: String? = null
    ): MediaSource {
        val ds = YtDataSource.Factory()
        val meta = MediaMetadata.Builder()
            .setTitle(title ?: info.name)
            .setArtist(artist ?: info.uploaderName)
            .setArtworkUri((artwork ?: bestThumb(info.thumbnails))?.let(Uri::parse))
            .build()
        val item = MediaItem.Builder().setMediaId(mediaId).setMediaMetadata(meta).build()

        // Musique : audio seul, la vidéo n'est même pas préparée
        if (audioOnly) {
            pickAudio(info.audioStreams)?.let { dash(it, info.duration, item, ds) }?.let { return it }
            info.audioStreams.firstOrNull { it.isUrl }?.let { return progressive(it.content, item, ds) }
        }

        val parts = mutableListOf<MediaSource>()
        val live = info.streamType == StreamType.LIVE_STREAM ||
            info.streamType == StreamType.AUDIO_LIVE_STREAM

        if (live && !info.hlsUrl.isNullOrEmpty()) {
            parts += HlsMediaSource.Factory(ds).createMediaSource(
                item.buildUpon().setUri(info.hlsUrl).setMimeType(MimeTypes.APPLICATION_M3U8).build()
            )
        } else {
            val video = pickVideo(info.videoOnlyStreams)?.let { dash(it, info.duration, item, ds) }
            val audio = pickAudio(info.audioStreams)?.let { dash(it, info.duration, item, ds) }
            when {
                video != null && audio != null -> { parts += video; parts += audio }
                else -> {
                    // Repli : flux unique (vidéo + son), ou audio seul
                    val muxed = info.videoStreams.filter { it.isUrl }.maxByOrNull { it.height }
                    when {
                        muxed != null -> parts += progressive(muxed.content, item, ds)
                        audio != null -> parts += audio
                        else -> info.audioStreams.firstOrNull { it.isUrl }
                            ?.let { parts += progressive(it.content, item, ds) }
                    }
                }
            }
            info.subtitles.forEach { sub -> subtitle(sub, ds)?.let { parts += it } }
        }

        if (parts.isEmpty()) error("Aucun flux lisible pour cette vidéo")
        return if (parts.size == 1) parts[0] else MergingMediaSource(*parts.toTypedArray())
    }

    fun pickVideo(list: List<VideoStream>): VideoStream? {
        val ok = list.filter {
            it.isUrl && it.itagItem != null &&
                it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.height in 1..Settings.maxHeight
        }
        fun best(pred: (VideoStream) -> Boolean) = ok.filter(pred).maxByOrNull { it.height * 1000 + it.fps }
        return best { it.codec?.startsWith("avc") == true }
            ?: best { it.codec?.startsWith("vp9") == true || it.codec?.startsWith("vp09") == true }
            ?: best { true }
    }

    fun pickAudio(list: List<AudioStream>): AudioStream? {
        val ok = list.filter {
            it.isUrl && it.itagItem != null && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP
        }
        val original = ok.filter { it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL }
            .ifEmpty { ok }
        return original.filter { it.format == MediaFormat.M4A }.maxByOrNull { it.averageBitrate }
            ?: original.maxByOrNull { it.averageBitrate }
    }

    /** Vidéo pour téléchargement : H.264 (assemblable en MP4), 720p max pour rester léger. */
    fun pickVideoForDownload(list: List<VideoStream>): VideoStream? = list.filter {
        it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP &&
            it.format == MediaFormat.MPEG_4 && it.codec?.startsWith("avc") == true &&
            it.height in 1..Settings.maxHeight
    }.maxByOrNull { it.height * 1000 + it.fps }

    private fun dash(s: Stream, durationSec: Long, item: MediaItem, ds: DataSource.Factory): MediaSource? {
        val itag = s.itagItem ?: return null
        return try {
            val mpd = YoutubeProgressiveDashManifestCreator
                .fromProgressiveStreamingUrl(s.content, itag, durationSec)
            val manifest = DashManifestParser()
                .parse(Uri.parse(s.content), ByteArrayInputStream(mpd.toByteArray()))
            DashMediaSource.Factory(ds).createMediaSource(manifest, item)
        } catch (e: Exception) {
            Log.w(TAG, "Manifeste DASH impossible", e)
            null
        }
    }

    private fun progressive(url: String, item: MediaItem, ds: DataSource.Factory): MediaSource =
        ProgressiveMediaSource.Factory(ds).createMediaSource(item.buildUpon().setUri(url).build())

    private fun subtitle(sub: SubtitlesStream, ds: DataSource.Factory): MediaSource? {
        if (!sub.isUrl) return null
        val mime = when (sub.format) {
            MediaFormat.TTML -> MimeTypes.APPLICATION_TTML
            MediaFormat.VTT -> MimeTypes.TEXT_VTT
            else -> return null
        }
        val label = sub.displayLanguageName + if (sub.isAutoGenerated) " (auto)" else ""
        val format = Format.Builder()
            .setSampleMimeType(mime)
            .setLanguage(sub.languageTag)
            .setLabel(label)
            .setId("sub-${sub.languageTag}-${sub.isAutoGenerated}")
            .build()
        val parsers = DefaultSubtitleParserFactory()
        if (!parsers.supportsFormat(format)) return null
        val extractors = ExtractorsFactory { arrayOf(SubtitleExtractor(parsers.create(format), format)) }
        return ProgressiveMediaSource.Factory(ds, extractors).createMediaSource(MediaItem.fromUri(sub.content))
    }

    fun bestThumb(images: List<Image>, minHeight: Int = 180): String? {
        val sorted = images.sortedBy { it.height }
        return (sorted.firstOrNull { it.height >= minHeight } ?: sorted.lastOrNull())?.url
    }
}
